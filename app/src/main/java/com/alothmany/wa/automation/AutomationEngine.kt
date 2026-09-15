package com.alothmany.wa.automation

import com.alothmany.wa.accessibility.AutomationRuntime
import com.alothmany.wa.accessibility.WhatsAppUiBridge
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.CheckpointEntity
import com.alothmany.wa.data.DiagnosticEventEntity
import com.alothmany.wa.data.GroupEntity
import com.alothmany.wa.data.ExtractionQueueItemEntity
import com.alothmany.wa.data.JobEntity
import com.alothmany.wa.r7.extraction.ConversationEvidence
import com.alothmany.wa.r7.extraction.ConversationVerificationStatus
import com.alothmany.wa.r7.extraction.ConversationVerifier
import com.alothmany.wa.r7.extraction.ExtractionController
import com.alothmany.wa.r7.extraction.ExtractionProgress
import com.alothmany.wa.r7.extraction.NewOnlyExtractionStrategy
import com.alothmany.wa.r7.extraction.NewOnlyMessageEvidence
import com.alothmany.wa.r7.extraction.NewOnlyStopReason
import com.alothmany.wa.r7.extraction.VerifiedConversation
import com.alothmany.wa.r7.navigation.ConversationNavigator
import com.alothmany.wa.r7.navigation.RecoveryAction
import com.alothmany.wa.r7.navigation.RecoveryController
import com.alothmany.wa.r7.navigation.RecoveryDirective
import com.alothmany.wa.r7.navigation.RecoverySignal
import com.alothmany.wa.r7.navigation.NavigationAction
import com.alothmany.wa.r7.navigation.SemanticScrollDirection
import com.alothmany.wa.r7.navigation.SmartScrollController
import com.alothmany.wa.r7.persistence.CheckpointRepository
import com.alothmany.wa.r7.persistence.ExtractionQueueStatus
import com.alothmany.wa.r7.persistence.GroupRepository
import com.alothmany.wa.r7.persistence.LinkRepository
import com.alothmany.wa.r7.persistence.OperationRepository
import com.alothmany.wa.r7.automation.Watchdog
import com.alothmany.wa.r7.automation.WatchdogPulse
import com.alothmany.wa.r7.automation.WatchdogReason
import com.alothmany.wa.r7.sync.DiscoveryProvenance
import com.alothmany.wa.r7.sync.GrabberClassification
import com.alothmany.wa.r7.sync.GroupCandidate
import com.alothmany.wa.r7.sync.GroupGrabberController
import com.alothmany.wa.r7.sync.GroupProbeEvidence
import com.alothmany.wa.r7.sync.SyncAction
import com.alothmany.wa.r7.sync.SyncController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.security.MessageDigest
import java.util.UUID

class AutomationEngine(
    private val db: AppDatabase,
    private val bridge: WhatsAppUiBridge,
    val controller: AutomationController = AutomationController()
) {
    enum class Mode { DEEP, NEW_ONLY }

    private val recoveryController = RecoveryController()
    private val watchdog = Watchdog()
    private var expectedServiceGeneration: Long = 0L

    suspend fun syncGroups(targetPackage: String): Int {
        diagnostic("INFO", "sync", "SYNC_START", "R7-M2 group synchronization started", "{\"package\":\"$targetPackage\"}")
        if (!ensureWhatsApp(targetPackage)) {
            diagnostic("ERROR", "sync", "PACKAGE_UNAVAILABLE", "Unable to activate $targetPackage")
            return 0
        }
        if (!bridge.ensureChatList()) {
            diagnostic("ERROR", "sync", "CHAT_LIST_NOT_REACHED", "Could not return to WhatsApp chat list")
            return 0
        }

        val filterNavigation = bridge.navigateToGroupsFilterVerifiedProfiled(targetPackage)
        if (filterNavigation.verified) {
            diagnostic(
                "INFO", "sync", "GROUP_FILTER_VERIFIED", "Groups filter transition verified",
                "{\"viewId\":\"${jsonEscape(filterNavigation.matchedViewId)}\",\"profileHint\":${filterNavigation.usedProfileHint}}"
            )
            bridge.resetListToTop()
            val groups = syncViaGroupsFilter(targetPackage)
            diagnostic("INFO", "sync", "SYNC_COMPLETE", "Verified Groups-filter synchronization complete", "{\"groups\":$groups,\"strategy\":\"GROUPS_FILTER\"}")
            return groups
        }
        bridge.captureR7DiagnosticArtifact("sync:$targetPackage", "groups-filter-not-verified")

        diagnostic(
            "WARN", "sync", "SYNC_STRATEGY_SWITCH",
            "Groups filter could not be verified; switching to conservative chat probing",
            "{\"from\":\"GROUPS_FILTER\",\"to\":\"CHAT_GRABBER\"}"
        )
        if (!bridge.ensureChatList()) {
            diagnostic("ERROR", "sync", "FALLBACK_CHAT_LIST_NOT_REACHED", "Could not reach chat list for conservative fallback")
            return 0
        }
        bridge.resetListToTop()
        val groups = syncViaChatGrabber(targetPackage)
        diagnostic("INFO", "sync", "SYNC_COMPLETE", "Conservative chat-grabber synchronization complete", "{\"groups\":$groups,\"strategy\":\"CHAT_GRABBER\"}")
        return groups
    }

    private suspend fun syncViaGroupsFilter(targetPackage: String): Int {
        val sync = SyncController(requiredTerminalConfirmations = 3)
        val existing = db.groupDao().byPackage(targetPackage).associateBy { stableName(it.displayName) }
        var nextDiscoveryOrder = (existing.values.maxOfOrNull { it.discoveryOrder } ?: -1L) + 1L

        repeat(1800) {
            if (!controller.awaitRunnable()) return sync.totalSeen()
            val snapshot = bridge.currentSnapshot()
            val viewport = snapshot?.windowFingerprint.orEmpty()
            val rows = bridge.visibleChatRowCandidates()
            val capture = sync.captureRows(rows, DiscoveryProvenance.GROUPS_FILTER, viewport)
            for (candidate in capture.newGroups) {
                val old = existing[candidate.stableKey]
                persistDiscoveredGroup(targetPackage, candidate, old, if (old == null) nextDiscoveryOrder++ else old.discoveryOrder)
            }

            val scroll = bridge.scrollListForwardVerified(2_500)
            val directive = sync.onScroll(
                actionAccepted = scroll.actionAccepted,
                progressed = scroll.progressed,
                telemetryAdvanced = false,
                afterViewportSignature = scroll.afterFingerprint.ifBlank { viewport }
            )
            if (directive.action == SyncAction.COMPLETE) {
                diagnostic(
                    "INFO", "sync", "SYNC_TERMINAL_CONSENSUS",
                    "End of group list accepted after repeated stable no-progress evidence",
                    "{\"confirmations\":${directive.terminalConfirmations},\"groups\":${sync.totalSeen()}}"
                )
                return sync.totalSeen()
            }
            if (directive.action == SyncAction.RECOVER) {
                diagnostic("WARN", "sync", "SYNC_OBSERVATION_INSUFFICIENT", directive.reason)
                delay(250)
            } else {
                delay(if (directive.terminalConfirmations > 0) 320 else 160)
            }
        }
        diagnostic("WARN", "sync", "SYNC_LIMIT", "Group sync hit bounded scroll limit", "{\"groups\":${sync.totalSeen()}}")
        return sync.totalSeen()
    }

    private suspend fun syncViaChatGrabber(targetPackage: String): Int {
        val traversal = SyncController(requiredTerminalConfirmations = 3)
        val grabber = GroupGrabberController()
        val existing = db.groupDao().byPackage(targetPackage).associateBy { stableName(it.displayName) }
        val discovered = linkedSetOf<String>()
        var nextDiscoveryOrder = (existing.values.maxOfOrNull { it.discoveryOrder } ?: -1L) + 1L
        var recoveryMisses = 0

        repeat(1200) {
            if (!controller.awaitRunnable()) return discovered.size
            val snapshot = bridge.currentSnapshot()
            val viewport = snapshot?.windowFingerprint.orEmpty()
            val rows = bridge.visibleChatRowCandidates()
            val capture = traversal.captureRows(rows, DiscoveryProvenance.CHAT_GRABBER, viewport)

            for (chat in capture.newGroups) {
                if (!controller.awaitRunnable()) return discovered.size
                if (!bridge.openVisibleChatByName(chat.displayName)) {
                    diagnostic("WARN", "sync", "CHAT_GRABBER_OPEN_FAILED", chat.displayName)
                    continue
                }
                val probeLabels = bridge.currentConversationProbeLabels(chat.displayName)
                val decision = grabber.classify(GroupProbeEvidence(chat.displayName, probeLabels, targetPackage))
                if (decision.classification == GrabberClassification.GROUP && decision.candidate != null) {
                    val candidate = decision.candidate
                    val old = existing[candidate.stableKey]
                    persistDiscoveredGroup(targetPackage, candidate, old, if (old == null) nextDiscoveryOrder++ else old.discoveryOrder)
                    discovered += candidate.stableKey
                    diagnostic(
                        "INFO", "sync", "CHAT_GRABBER_GROUP",
                        candidate.displayName,
                        "{\"provenance\":\"CHAT_GRABBER\",\"reason\":\"${jsonEscape(decision.reason)}\"}"
                    )
                }
                bridge.goBack()
                delay(180)
                if (!bridge.ensureChatList(maxBacks = 2)) {
                    recoveryMisses++
                    diagnostic("WARN", "sync", "CHAT_GRABBER_RETURN_FAILED", chat.displayName, "{\"misses\":$recoveryMisses}")
                    if (recoveryMisses >= 3) return discovered.size
                    ensureWhatsApp(targetPackage)
                    bridge.ensureChatList()
                } else {
                    recoveryMisses = 0
                }
            }

            val beforeScroll = bridge.currentSnapshot()?.windowFingerprint.orEmpty().ifBlank { viewport }
            val scroll = bridge.scrollListForwardVerified(2_500)
            val directive = traversal.onScroll(
                actionAccepted = scroll.actionAccepted,
                progressed = scroll.progressed,
                telemetryAdvanced = false,
                afterViewportSignature = scroll.afterFingerprint.ifBlank { beforeScroll }
            )
            if (directive.action == SyncAction.COMPLETE) {
                diagnostic(
                    "INFO", "sync", "CHAT_GRABBER_TERMINAL_CONSENSUS",
                    "All-chats fallback reached verified terminal consensus",
                    "{\"confirmations\":${directive.terminalConfirmations},\"groups\":${discovered.size}}"
                )
                return discovered.size
            }
            if (directive.action == SyncAction.RECOVER) {
                recoveryMisses++
                if (recoveryMisses >= 3) return discovered.size
                bridge.ensureChatList()
            }
            delay(if (directive.terminalConfirmations > 0) 320 else 160)
        }
        diagnostic("WARN", "sync", "CHAT_GRABBER_LIMIT", "Fallback sync hit bounded scroll limit", "{\"groups\":${discovered.size}}")
        return discovered.size
    }

    private suspend fun persistDiscoveredGroup(
        targetPackage: String,
        candidate: GroupCandidate,
        existing: GroupEntity?,
        discoveryOrder: Long
    ) {
        val id = existing?.id ?: sha256("$targetPackage|${candidate.stableKey}")
        val signatureSeed = if (candidate.identityHint.isNotBlank()) candidate.identityHint else "$targetPackage|${candidate.displayName}"
        db.groupDao().upsert(
            GroupEntity(
                id = id,
                targetPackage = targetPackage,
                displayName = existing?.displayName ?: candidate.displayName,
                accessibilitySignature = sha256(signatureSeed),
                lastSeenAt = System.currentTimeMillis(),
                discoveryOrder = discoveryOrder,
                classification = candidate.provenance.name,
                lastNewOnlyBoundary = existing?.lastNewOnlyBoundary
            )
        )
    }

    private fun stableName(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase()
    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    suspend fun start(mode: Mode, targetPackage: String, existingJobId: String? = null): String {
        val jobId = existingJobId ?: UUID.randomUUID().toString()
        recoveryController.resetRun()
        watchdog.reset()
        expectedServiceGeneration = AutomationRuntime.currentServiceGeneration()
        val now = System.currentTimeMillis()
        val existingJob = db.jobDao().get(jobId)
        db.jobDao().upsert(
            if (existingJob == null) {
                JobEntity(jobId, mode.name, targetPackage, "RUNNING", now, now, lastProgressAt = now)
            } else {
                existingJob.copy(mode = mode.name, targetPackage = targetPackage, status = "RUNNING", updatedAt = now, lastError = null, lastProgressAt = now)
            }
        )
        try {
            if (!ensureWhatsApp(targetPackage)) {
                failJob(jobId, "WhatsApp package could not be activated")
                return jobId
            }
            val groupRepository = GroupRepository(db)
            var groups = groupRepository.byPackage(targetPackage)
            if (groups.isEmpty()) {
                syncGroups(targetPackage)
                groups = groupRepository.byPackage(targetPackage)
            }
            if (groups.isEmpty()) {
                failJob(jobId, "No groups were synchronized")
                return jobId
            }

            val operationRepository = OperationRepository(db)
            val checkpointRepository = CheckpointRepository(db)
            val queue = operationRepository.ensureImmutableQueue(jobId, mode.name, groups)
            if (queue.isEmpty()) {
                failJob(jobId, "Immutable extraction queue could not be created")
                return jobId
            }
            diagnostic(
                "INFO", "queue", "QUEUE_FROZEN",
                "Immutable extraction queue created or restored",
                "{\"items\":${queue.size},\"mode\":\"${mode.name}\"}"
            )

            bridge.ensureChatList()
            bridge.navigateToGroupsFilter()
            bridge.resetListToTop()
            var hadPartial = false

            for (item in queue) {
                if (item.status == ExtractionQueueStatus.COMPLETED.name) continue
                if (checkpointRepository.get(jobId, item.groupId)?.phase == "COMPLETE") {
                    operationRepository.reconcileCompletedCheckpoint(jobId, item.groupId)
                    continue
                }
                if (!controller.awaitRunnable {
                        operationRepository.transition(
                            jobId, item.groupId, ExtractionQueueStatus.PAUSED,
                            lastState = "PAUSED_AT_QUEUE_BOUNDARY"
                        )
                        updateJobStatus(jobId, "PAUSED")
                        diagnostic("INFO", "lifecycle", "PAUSED_ATOMIC_BOUNDARY", item.expectedTitle)
                    }) {
                    updateJobStatus(jobId, "STOPPED")
                    return jobId
                }
                if (db.jobDao().get(jobId)?.status == "PAUSED") {
                    updateJobStatus(jobId, "RUNNING")
                    diagnostic("INFO", "lifecycle", "RESUMED_DURABLE_JOB", item.expectedTitle)
                }

                val watchdogDirective = checkWatchdog(jobId, item.groupId, item.lastState, targetPackage)
                if (watchdogDirective != null && !executeRecoveryDirective(item.toFrozenGroupEntity(), watchdogDirective)) {
                    hadPartial = true
                    operationRepository.transition(
                        jobId, item.groupId, ExtractionQueueStatus.FAILED,
                        lastState = "WATCHDOG_RECOVERY_EXHAUSTED", incrementRetry = true
                    )
                    continue
                }

                val openingAccepted = operationRepository.transition(
                    jobId,
                    item.groupId,
                    ExtractionQueueStatus.OPENING,
                    lastState = "OPENING_CONVERSATION",
                    incrementRetry = item.status == ExtractionQueueStatus.FAILED.name
                )
                if (!openingAccepted) {
                    hadPartial = true
                    diagnostic("ERROR", "queue", "QUEUE_TRANSITION_REJECTED", item.expectedTitle, "{\"to\":\"OPENING\"}")
                    continue
                }
                val group = item.toFrozenGroupEntity()
                val verifiedConversation = openVerifiedConversation(group)
                if (verifiedConversation == null) {
                    hadPartial = true
                    operationRepository.transition(
                        jobId,
                        item.groupId,
                        ExtractionQueueStatus.FAILED,
                        lastState = "CONVERSATION_VERIFICATION_FAILED",
                        incrementRetry = true
                    )
                    diagnostic("WARN", "navigator", "GROUP_VERIFICATION_FAILED", group.displayName)
                    continue
                }

                val verifyingAccepted = operationRepository.transition(
                    jobId,
                    item.groupId,
                    ExtractionQueueStatus.VERIFYING,
                    lastState = "EXACT_CONVERSATION_VERIFIED"
                )
                if (!verifyingAccepted) {
                    hadPartial = true
                    operationRepository.transition(jobId, item.groupId, ExtractionQueueStatus.FAILED, lastState = "VERIFYING_TRANSITION_REJECTED")
                    diagnostic("ERROR", "queue", "QUEUE_TRANSITION_REJECTED", item.expectedTitle, "{\"to\":\"VERIFYING\"}")
                    bridge.goBack()
                    continue
                }
                val extractingAccepted = operationRepository.transition(
                    jobId,
                    item.groupId,
                    ExtractionQueueStatus.EXTRACTING,
                    lastState = "DEEP_EXTRACTION_ACTIVE"
                )
                if (!extractingAccepted) {
                    hadPartial = true
                    operationRepository.transition(jobId, item.groupId, ExtractionQueueStatus.FAILED, lastState = "EXTRACTING_TRANSITION_REJECTED")
                    diagnostic("ERROR", "queue", "QUEUE_TRANSITION_REJECTED", item.expectedTitle, "{\"to\":\"EXTRACTING\"}")
                    bridge.goBack()
                    continue
                }

                val complete = extractGroup(jobId, group, mode, verifiedConversation)
                if (complete) {
                    operationRepository.transition(
                        jobId,
                        item.groupId,
                        ExtractionQueueStatus.COMPLETED,
                        lastState = "COMPLETE",
                        checkpointId = "$jobId:${item.groupId}"
                    )
                } else {
                    hadPartial = true
                    operationRepository.transition(
                        jobId,
                        item.groupId,
                        ExtractionQueueStatus.FAILED,
                        lastState = "PARTIAL",
                        checkpointId = "$jobId:${item.groupId}",
                        incrementRetry = true
                    )
                    diagnostic("WARN", "extract", "GROUP_PARTIAL", group.displayName)
                }
                bridge.goBack()
                delay(260)
            }
            updateJobStatus(jobId, if (hadPartial) "PARTIAL" else "COMPLETE")
        } catch (ce: CancellationException) {
            if (controller.state.value == ControlState.STOPPED) updateJobStatus(jobId, "STOPPED")
            throw ce
        } catch (t: Throwable) {
            diagnostic("ERROR", "engine", "UNCAUGHT", t.message ?: t.javaClass.simpleName)
            updateJobStatus(jobId, "RECOVERING", t.message)
        }
        return jobId
    }

    private fun ExtractionQueueItemEntity.toFrozenGroupEntity(): GroupEntity = GroupEntity(
        id = groupId,
        targetPackage = targetPackage,
        displayName = expectedTitle,
        accessibilitySignature = expectedIdentitySignature.orEmpty(),
        lastSeenAt = createdAt,
        discoveryOrder = ordinal.toLong(),
        classification = discoveryProvenance,
        lastNewOnlyBoundary = newOnlyBoundaryAtStart
    )

    private suspend fun openVerifiedConversation(group: GroupEntity): VerifiedConversation? {
        val verifier = ConversationVerifier()
        val navigator = ConversationNavigator(maxWeakReopens = 2)
        val recoverySignal = RecoverySignal(
            operationId = "navigation:${group.id}",
            itemId = group.id,
            state = "OPENING",
            category = "NAVIGATION",
            adapterProfile = group.classification
        )
        var attempts = 0

        while (attempts < 8) {
            attempts++
            if (!bridge.ensureChatList()) {
                diagnostic("WARN", "navigator", "CHAT_LIST_NOT_REACHED", group.displayName, "{\"attempt\":$attempts}")
                val directive = recoveryController.onFailure(recoverySignal)
                if (!executeRecoveryDirective(group, directive)) return null
                continue
            }
            if (group.classification == DiscoveryProvenance.GROUPS_FILTER.name && !bridge.navigateToGroupsFilterVerifiedProfiled(group.targetPackage).verified) {
                diagnostic("WARN", "navigator", "GROUP_FILTER_REVERIFY_FAILED", group.displayName, "{\"attempt\":$attempts}")
                val directive = recoveryController.onFailure(recoverySignal)
                if (!executeRecoveryDirective(group, directive)) return null
                continue
            }
            if (!bridge.openChatByName(group.displayName)) {
                diagnostic("WARN", "navigator", "GROUP_OPEN_FAILED", group.displayName, "{\"attempt\":$attempts}")
                val directive = recoveryController.onFailure(recoverySignal)
                if (!executeRecoveryDirective(group, directive)) return null
                continue
            }

            val snapshot = bridge.currentSnapshot()
            val observedIdentitySignature = observedIdentitySignature(group)
            val verification = verifier.verify(
                ConversationEvidence(
                    expectedPackage = group.targetPackage,
                    observedPackage = snapshot?.packageName.orEmpty(),
                    expectedTitle = group.displayName,
                    observedTitle = snapshot?.chatTitle,
                    expectedIdentitySignature = if (group.classification == DiscoveryProvenance.CHAT_GRABBER.name) group.accessibilitySignature else null,
                    observedIdentitySignature = observedIdentitySignature
                )
            )
            val directive = navigator.onVerification(verification)
            diagnostic(
                if (verification.status == ConversationVerificationStatus.PASS) "INFO" else "WARN",
                "navigator",
                "CONVERSATION_${verification.status.name}",
                group.displayName,
                "{\"attempt\":$attempts,\"action\":\"${directive.action}\",\"reason\":\"${jsonEscape(verification.reason)}\"}"
            )

            when (directive.action) {
                NavigationAction.ACCEPT -> {
                    recoveryController.onVerifiedProgress(recoverySignal)
                    watchdog.verifiedProgress()
                    return verification.verifiedConversation
                }
                NavigationAction.REJECT -> {
                    // Exact title/package mismatch remains a hard rejection from R7-M3.
                    bridge.goBack()
                    return null
                }
                NavigationAction.REOPEN, NavigationAction.RECOVER -> {
                    bridge.goBack()
                    delay(150)
                    val recovery = recoveryController.onFailure(recoverySignal)
                    if (!executeRecoveryDirective(group, recovery)) return null
                }
            }
        }
        return null
    }

    private suspend fun observedIdentitySignature(group: GroupEntity): String? {
        if (group.classification != DiscoveryProvenance.CHAT_GRABBER.name) return null
        val labels = bridge.currentConversationProbeLabels(group.displayName)
        val decision = GroupGrabberController().classify(
            GroupProbeEvidence(group.displayName, labels, group.targetPackage)
        )
        if (decision.classification != GrabberClassification.GROUP || decision.identityHint.isBlank()) return null
        return sha256(decision.identityHint)
    }

    private suspend fun extractGroup(
        jobId: String,
        group: GroupEntity,
        mode: Mode,
        verifiedConversation: VerifiedConversation
    ): Boolean {
        val expectedTitle = ConversationVerifier.normalizeTitle(group.displayName)
        if (verifiedConversation.packageName != group.targetPackage || verifiedConversation.normalizedTitle != expectedTitle) {
            diagnostic("ERROR", "extract", "VERIFIED_TOKEN_MISMATCH", group.displayName)
            return false
        }

        val initial = bridge.currentSnapshot() ?: return false
        val initialVerification = ConversationVerifier().verify(
            ConversationEvidence(group.targetPackage, initial.packageName, group.displayName, initial.chatTitle)
        )
        if (initialVerification.status != ConversationVerificationStatus.PASS) {
            diagnostic("WARN", "extract", "GROUP_IDENTITY_MISMATCH", group.displayName)
            return false
        }

        val linkRepository = LinkRepository(db)
        val checkpointRepository = CheckpointRepository(db)
        val extractionController = ExtractionController(linkRepository, checkpointRepository)
        val smartScroll = SmartScrollController(requiredTerminalConfirmations = 3)
        val previousCheckpoint = checkpointRepository.get(jobId, group.id)
        val newOnlyStrategy = NewOnlyExtractionStrategy(noProgressThreshold = 3)
        val previousNewOnlyCheckpoint = if (mode == Mode.NEW_ONLY) {
            newOnlyStrategy.decode(group.lastNewOnlyBoundary)
        } else null
        val nextNewOnlyCheckpoint = if (mode == Mode.NEW_ONLY) {
            newOnlyStrategy.buildCheckpoint(
                newOnlyStrategy.observe(
                    windowFingerprint = initial.windowFingerprint,
                    messages = initial.messages.map { NewOnlyMessageEvidence(it.fingerprint, it.text) },
                    visibleTexts = initial.visibleTexts,
                    terminalNoProgressCount = 0
                )
            )
        } else null
        var scrolls = 0
        var terminalEvidenceCount = 0
        var linksFound = previousCheckpoint?.linksFound ?: 0L
        var occurrences = previousCheckpoint?.occurrences ?: 0L
        var completionProven = false
        var snapshot = initial
        val conversationKey = "${verifiedConversation.packageName}|${verifiedConversation.normalizedTitle}|${group.id}"

        while (scrolls < 5000) {
            val durable = extractionController.persistVerifiedViewport(
                jobId = jobId,
                groupId = group.id,
                conversationKey = conversationKey,
                messages = snapshot.messages,
                progress = ExtractionProgress(
                    scrollCount = scrolls,
                    terminalEvidenceCount = terminalEvidenceCount,
                    linksFound = linksFound,
                    occurrences = occurrences,
                    windowFingerprint = snapshot.windowFingerprint
                )
            )
            linksFound = durable.progress.linksFound
            occurrences = durable.progress.occurrences
            refreshJobCounters(jobId, linkRepository)

            if (!controller.awaitRunnable {
                    checkpointRepository.save(
                        CheckpointEntity(
                            jobId, group.id, "PAUSED", scrolls, snapshot.windowFingerprint,
                            terminalEvidenceCount, linksFound, occurrences, System.currentTimeMillis()
                        )
                    )
                    OperationRepository(db).transition(
                        jobId, group.id, ExtractionQueueStatus.PAUSED,
                        lastState = "PAUSED_AFTER_DURABLE_BATCH", checkpointId = "$jobId:${group.id}"
                    )
                    updateJobStatus(jobId, "PAUSED")
                    diagnostic("INFO", "lifecycle", "PAUSED_AFTER_DURABLE_BATCH", group.displayName)
                }) return false
            if (db.jobDao().get(jobId)?.status == "PAUSED") {
                updateJobStatus(jobId, "RUNNING")
                OperationRepository(db).transition(
                    jobId, group.id, ExtractionQueueStatus.EXTRACTING,
                    lastState = "RESUMED_FROM_DURABLE_CHECKPOINT", checkpointId = "$jobId:${group.id}"
                )
                diagnostic("INFO", "lifecycle", "RESUMED_DURABLE_ITEM", group.displayName)
            }

            val watchdogDirective = checkWatchdog(jobId, group.id, "EXTRACTING", group.targetPackage)
            if (watchdogDirective != null) {
                if (!executeRecoveryDirective(group, watchdogDirective)) {
                    diagnostic("WARN", "watchdog", "WATCHDOG_ITEM_QUARANTINED", group.displayName)
                    break
                }
                val recovered = openVerifiedConversation(group) ?: break
                snapshot = bridge.currentSnapshot() ?: snapshot
                recoveryController.onVerifiedProgress(
                    RecoverySignal(jobId, group.id, "EXTRACTING", "WATCHDOG", group.classification)
                )
                diagnostic("INFO", "recovery", "DURABLE_ITEM_REVERIFIED", recovered.normalizedTitle)
            }

            if (mode == Mode.NEW_ONLY) {
                val boundaryDecision = newOnlyStrategy.evaluate(
                    current = newOnlyStrategy.observe(
                        windowFingerprint = snapshot.windowFingerprint,
                        messages = snapshot.messages.map { NewOnlyMessageEvidence(it.fingerprint, it.text) },
                        visibleTexts = snapshot.visibleTexts,
                        terminalNoProgressCount = terminalEvidenceCount
                    ),
                    previous = previousNewOnlyCheckpoint
                )
                if (boundaryDecision.shouldStop) {
                    completionProven = true
                    diagnostic(
                        "INFO", "extract", "NEW_ONLY_BOUNDARY_CONFIRMED", group.displayName,
                        "{\"reason\":\"${boundaryDecision.reason}\",\"anchors\":${boundaryDecision.anchorMatches},\"timestamps\":${boundaryDecision.timestampMatches},\"urls\":${boundaryDecision.urlMatches},\"viewport\":${boundaryDecision.viewportMatch}}"
                    )
                    break
                }
            }

            val scroll = bridge.semanticScroll(
                SemanticScrollDirection.OLDER,
                smartScroll,
                timeoutMs = 3_000
            )
            scrolls++
            terminalEvidenceCount = scroll.terminalConfirmations
            if (scroll.recoveryRequired) {
                diagnostic(
                    "WARN", "scroll", "SMART_SCROLL_RECOVERY_REQUIRED", group.displayName,
                    "{\"scrolls\":$scrolls,\"strategy\":\"${scroll.finalStrategy}\"}"
                )
                val directive = recoveryController.onFailure(
                    RecoverySignal(jobId, group.id, "EXTRACTING", "SMART_SCROLL", group.classification)
                )
                if (!executeRecoveryDirective(group, directive)) break
                val reverified = openVerifiedConversation(group) ?: break
                snapshot = bridge.currentSnapshot() ?: snapshot
                diagnostic("INFO", "recovery", "SMART_SCROLL_RECOVERED", reverified.normalizedTitle)
                continue
            }
            if (scroll.boundaryConfirmed) {
                snapshot = bridge.currentSnapshot() ?: snapshot
                if (mode == Mode.NEW_ONLY) {
                    val terminalDecision = newOnlyStrategy.evaluate(
                        current = newOnlyStrategy.observe(
                            windowFingerprint = snapshot.windowFingerprint,
                            messages = snapshot.messages.map { NewOnlyMessageEvidence(it.fingerprint, it.text) },
                            visibleTexts = snapshot.visibleTexts,
                            terminalNoProgressCount = scroll.terminalConfirmations
                        ),
                        previous = previousNewOnlyCheckpoint
                    )
                    if (terminalDecision.shouldStop && terminalDecision.reason == NewOnlyStopReason.HISTORICAL_NO_PROGRESS) {
                        completionProven = true
                        diagnostic(
                            "INFO", "extract", "NEW_ONLY_HISTORICAL_TERMINAL_CONSENSUS", group.displayName,
                            "{\"confirmations\":${scroll.terminalConfirmations},\"scrolls\":$scrolls}"
                        )
                        break
                    }
                } else {
                    completionProven = true
                    diagnostic(
                        "INFO", "scroll", "CHAT_START_TERMINAL_CONSENSUS", group.displayName,
                        "{\"confirmations\":${scroll.terminalConfirmations},\"scrolls\":$scrolls}"
                    )
                    break
                }
            }
            snapshot = bridge.currentSnapshot() ?: snapshot

            if (scroll.terminalConfirmations > 0) delay(180)
            else if (scrolls % 25 == 0) delay(350)
            else delay(45)
        }

        val finalSnapshot = bridge.currentSnapshot() ?: return false
        val finalVerification = ConversationVerifier().verify(
            ConversationEvidence(group.targetPackage, finalSnapshot.packageName, group.displayName, finalSnapshot.chatTitle)
        )
        if (finalVerification.status != ConversationVerificationStatus.PASS) {
            checkpointRepository.save(
                CheckpointEntity(jobId, group.id, "PARTIAL", scrolls, finalSnapshot.windowFingerprint,
                    terminalEvidenceCount, linksFound, occurrences, System.currentTimeMillis())
            )
            refreshJobCounters(jobId, linkRepository)
            return false
        }

        if (!completionProven || scrolls >= 5000) {
            checkpointRepository.save(
                CheckpointEntity(jobId, group.id, "PARTIAL", scrolls, finalSnapshot.windowFingerprint,
                    terminalEvidenceCount, linksFound, occurrences, System.currentTimeMillis())
            )
            refreshJobCounters(jobId, linkRepository)
            return false
        }
        if (mode == Mode.NEW_ONLY && nextNewOnlyCheckpoint != null) {
            GroupRepository(db).updateNewOnlyBoundary(
                group.id,
                newOnlyStrategy.encode(nextNewOnlyCheckpoint),
                System.currentTimeMillis()
            )
        }
        checkpointRepository.save(
            CheckpointEntity(jobId, group.id, "COMPLETE", scrolls, finalSnapshot.windowFingerprint,
                terminalEvidenceCount, linksFound, occurrences, System.currentTimeMillis())
        )
        refreshJobCounters(jobId, linkRepository)
        return true
    }

    private suspend fun checkWatchdog(
        jobId: String,
        groupId: String?,
        state: String,
        expectedPackage: String
    ): RecoveryDirective? {
        val now = System.currentTimeMillis()
        val snapshot = bridge.currentSnapshot()
        val job = db.jobDao().get(jobId)
        val currentServiceGeneration = AutomationRuntime.currentServiceGeneration()
        val eventAt = AutomationRuntime.eventBuffer.lastEventAt()
        val result = watchdog.observe(
            WatchdogPulse(
                now = now,
                lastAccessibilityEventAt = eventAt,
                lastProgressAt = job?.lastProgressAt ?: now,
                state = state,
                observationSignature = snapshot?.windowFingerprint.orEmpty(),
                windowUpdatedAt = eventAt,
                gestureStartedAt = AutomationRuntime.activeGestureStartedAt(),
                expectedPackage = expectedPackage,
                observedPackage = snapshot?.packageName,
                expectedServiceGeneration = expectedServiceGeneration,
                observedServiceGeneration = currentServiceGeneration
            )
        )
        if (result.healthy) return null
        val categories = result.reasons.joinToString("+") { it.name }
        diagnostic(
            "WARN", "watchdog", "WATCHDOG_TRIGGERED", categories,
            "{\"state\":\"${jsonEscape(state)}\",\"sameStateCount\":${result.sameStateCount}}"
        )
        if (WatchdogReason.SERVICE_RECREATED in result.reasons) expectedServiceGeneration = currentServiceGeneration
        return recoveryController.onFailure(
            RecoverySignal(jobId, groupId, state, "WATCHDOG", snapshot?.packageName ?: "unknown")
        )
    }

    private suspend fun executeRecoveryDirective(group: GroupEntity, directive: RecoveryDirective): Boolean {
        diagnostic(
            if (directive.terminalForItem) "WARN" else "INFO",
            "recovery",
            "RECOVERY_${directive.action.name}",
            group.displayName,
            "{\"attempt\":${directive.attempt},\"circuit\":\"${directive.circuitDecision}\"}"
        )
        return when (directive.action) {
            RecoveryAction.REOBSERVE -> { delay(120); bridge.currentSnapshot() != null }
            RecoveryAction.RECLASSIFY -> { delay(120); bridge.currentSnapshot()?.packageName != null }
            RecoveryAction.RETRY_ACTION -> true
            RecoveryAction.BACK_TO_KNOWN_SCREEN -> {
                bridge.goBack()
                delay(150)
                bridge.ensureChatList(maxBacks = 3)
            }
            RecoveryAction.RELAUNCH_PACKAGE -> ensureWhatsApp(group.targetPackage) && bridge.ensureChatList()
            RecoveryAction.RESTORE_DURABLE_ITEM -> {
                ensureWhatsApp(group.targetPackage) && bridge.ensureChatList()
            }
            RecoveryAction.FAIL_ITEM_CONTINUE -> false
            RecoveryAction.DEGRADE_RUN -> false
        }
    }

    private suspend fun ensureWhatsApp(targetPackage: String): Boolean {
        val launched = bridge.launchPackage(targetPackage, 8_000)
        if (!launched) diagnostic("WARN", "navigator", "LAUNCH_BLOCKED", "Unable to activate $targetPackage")
        return launched
    }

    private suspend fun refreshJobCounters(jobId: String, links: LinkRepository) {
        val job = db.jobDao().get(jobId) ?: return
        val now = System.currentTimeMillis()
        db.jobDao().upsert(
            job.copy(
                updatedAt = now,
                linksFound = links.countLinksForJob(jobId),
                occurrences = links.countOccurrencesForJob(jobId),
                lastProgressAt = now
            )
        )
    }

    private suspend fun updateJobStatus(jobId: String, status: String, error: String? = null) {
        val job = db.jobDao().get(jobId) ?: return
        val now = System.currentTimeMillis()
        db.jobDao().upsert(job.copy(status = status, updatedAt = now, lastError = error, lastProgressAt = now))
    }

    private suspend fun failJob(jobId: String, message: String) {
        updateJobStatus(jobId, "FAILED", message)
        diagnostic("ERROR", "engine", "JOB_FAILED", message)
    }

    private suspend fun diagnostic(severity: String, component: String, code: String, message: String, contextJson: String? = null) {
        db.diagnosticDao().insert(
            DiagnosticEventEntity(
                timestamp = System.currentTimeMillis(), severity = severity, component = component,
                code = code, message = message, contextJson = contextJson
            )
        )
        if (severity == "ERROR" && code in setOf(
                "JOB_FAILED", "UNCAUGHT", "QUEUE_TRANSITION_REJECTED", "VERIFIED_TOKEN_MISMATCH"
            )) {
            bridge.captureR7DiagnosticArtifact("auto:$component", code.lowercase())
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
