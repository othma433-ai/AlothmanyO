package com.alothmany.wa.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import com.alothmany.wa.domain.InviteStateClassifier
import com.alothmany.wa.domain.InviteUiState
import com.alothmany.wa.domain.SendVerification
import com.alothmany.wa.domain.SendVerificationPolicy
import com.alothmany.wa.r7.diagnostics.R7ShadowRuntime
import com.alothmany.wa.r7.diagnostics.DiagnosticArtifactStore
import com.alothmany.wa.r7.diagnostics.DiagnosticCapture
import com.alothmany.wa.r7.diagnostics.ReplayFixture
import com.alothmany.wa.r7.accessibility.AndroidAccessibilityGateway
import com.alothmany.wa.r7.accessibility.NodeSnapshotFactory
import com.alothmany.wa.r7.accessibility.ResolutionStatus
import com.alothmany.wa.r7.join.JoinActionTarget
import com.alothmany.wa.r7.join.JoinPostconditionVerifier
import com.alothmany.wa.r7.join.JoinTargetCandidate
import com.alothmany.wa.r7.join.JoinTargetKind
import com.alothmany.wa.r7.join.JoinTargetResolutionStatus
import com.alothmany.wa.r7.join.JoinTargetResolver
import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import com.alothmany.wa.r7.snapshot.SnapshotSignature
import com.alothmany.wa.r7.observe.ScreenClassifier
import com.alothmany.wa.r7.profile.AndroidRuntimeProfileStore
import com.alothmany.wa.r7.profile.ProfileHintType
import com.alothmany.wa.r7.profile.RuntimeProfileKey
import com.alothmany.wa.r7.profile.RuntimeProfileRepository
import com.alothmany.wa.r7.navigation.ScrollAttemptEvidence
import com.alothmany.wa.r7.navigation.ScrollDirectiveAction
import com.alothmany.wa.r7.navigation.ScrollStrategy
import com.alothmany.wa.r7.navigation.SemanticScrollDirection
import com.alothmany.wa.r7.navigation.SemanticScrollResult
import com.alothmany.wa.r7.navigation.SmartScrollController
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.ArrayDeque
import java.util.Locale

class WhatsAppUiBridge(
    private val service: AccessibilityService,
    private val snapshotter: AccessibilitySnapshotter = AccessibilitySnapshotter()
) {
    companion object {
        val DEFAULT_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private val GROUP_FILTER_TOKENS = listOf("Groups", "المجموعات")
        private val JOIN_DIRECT_TOKENS = listOf("Join group", "Join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام")
        private val JOIN_APPROVAL_TOKENS = listOf("Request to join", "طلب الانضمام", "إرسال طلب انضمام")
        private val JOIN_COMMUNITY_TOKENS = listOf("Join community", "View community", "انضم إلى المجتمع", "عرض المجتمع")
        private val SEND_TOKENS = listOf("Send", "إرسال")
        private val ROW_NOISE = setOf(
            "chats", "updates", "calls", "communities", "archived", "search", "new chat", "settings", "groups", "all", "unread", "favorites",
            "الدردشات", "المستجدات", "المكالمات", "المجتمعات", "المؤرشفة", "بحث", "دردشة جديدة", "الإعدادات", "المجموعات", "الكل", "غير مقروءة", "المفضلة"
        )
    }

    private data class UiActionTarget(
        val node: AccessibilityNodeInfo,
        val bounds: Rect,
        val label: String
    )

    data class GroupsFilterNavigationEvidence(
        val verified: Boolean,
        val matchedViewId: String,
        val usedProfileHint: Boolean
    )

    private val runtimeProfileStore by lazy { AndroidRuntimeProfileStore(service.applicationContext) }
    private val runtimeProfiles by lazy { RuntimeProfileRepository(runtimeProfileStore) }
    private val diagnosticArtifacts by lazy { DiagnosticArtifactStore(service.applicationContext) }
    private val r7NodeCollector = R7NodeSnapshotCollector()
    private val joinTargetResolver = JoinTargetResolver()
    private val joinPostconditionVerifier = JoinPostconditionVerifier()
    private val joinNodeSnapshotFactory = NodeSnapshotFactory()
    private val joinAccessibilityGateway by lazy { AndroidAccessibilityGateway(service, joinNodeSnapshotFactory) }

    fun currentSnapshot(): WindowSnapshot? = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching null
        val height = service.resources.displayMetrics.heightPixels
        snapshotter.snapshot(root, height)
    }.getOrNull()

    suspend fun waitForPackage(targetPackage: String, timeoutMs: Long = 8_000): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (currentSnapshot()?.packageName == targetPackage) return@withTimeoutOrNull true
                delay(120)
            }
        } ?: false

    suspend fun ensureChatList(maxBacks: Int = 6): Boolean {
        repeat(maxBacks + 1) { attempt ->
            val texts = currentSnapshot()?.visibleTexts.orEmpty()
            val onList = texts.any { value ->
                value.equals("Chats", true) || value == "الدردشات" ||
                    value.equals("Groups", true) || value == "المجموعات"
            }
            if (onList) return true
            if (attempt == maxBacks) return false
            goBack()
            delay(220)
        }
        return false
    }

    suspend fun navigateToGroupsFilter(): Boolean = clickAnyText(GROUP_FILTER_TOKENS, 4_000)

    /**
     * R7-M2 verified variant. A successful click is dispatch evidence only; this method waits for
     * selection/structural evidence before reporting success.
     */
    suspend fun navigateToGroupsFilterVerified(timeoutMs: Long = 5_000): Boolean {
        val targetPackage = currentSnapshot()?.packageName.orEmpty()
        return navigateToGroupsFilterVerifiedProfiled(targetPackage, timeoutMs).verified
    }

    suspend fun navigateToGroupsFilterVerifiedProfiled(
        targetPackage: String,
        timeoutMs: Long = 5_000
    ): GroupsFilterNavigationEvidence {
        val profileKey = runtimeProfileKey(targetPackage)
        val preferredIds = runtimeProfiles
            .recommendations(profileKey, ProfileHintType.GROUP_FILTER_VIEW_ID)
            .map { it.value }
        val before = currentSnapshot()?.windowFingerprint.orEmpty()
        val root = service.rootInActiveWindow
            ?: return GroupsFilterNavigationEvidence(false, "", false)
        val target = findGroupsFilterNode(root, preferredIds)
            ?: return GroupsFilterNavigationEvidence(false, "", false)
        val matchedViewId = target.viewIdResourceName.orEmpty()
        val usedProfileHint = matchedViewId.isNotBlank() && preferredIds.any { it == matchedViewId }
        if (!clickNodeOrAncestor(target)) {
            if (usedProfileHint) runtimeProfiles.recordFailure(
                profileKey, ProfileHintType.GROUP_FILTER_VIEW_ID, matchedViewId, System.currentTimeMillis()
            )
            return GroupsFilterNavigationEvidence(false, matchedViewId, usedProfileHint)
        }
        val verified = withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(140)
                if (groupsFilterSelected()) return@withTimeoutOrNull true
                val after = currentSnapshot()
                val changed = before.isNotBlank() && after?.windowFingerprint?.isNotBlank() == true && after.windowFingerprint != before
                val stillHasGroupsControl = service.rootInActiveWindow?.let { findGroupsFilterNode(it, preferredIds) } != null
                // Profiles only prioritize a candidate; structural postcondition verification remains mandatory.
                if (changed && stillHasGroupsControl && visibleChatRowCandidates().isNotEmpty()) return@withTimeoutOrNull true
            }
        } ?: groupsFilterSelected()

        if (matchedViewId.isNotBlank()) {
            if (verified) runtimeProfiles.recordSuccess(
                profileKey, ProfileHintType.GROUP_FILTER_VIEW_ID, matchedViewId, verified = true, now = System.currentTimeMillis()
            ) else runtimeProfiles.recordFailure(
                profileKey, ProfileHintType.GROUP_FILTER_VIEW_ID, matchedViewId, System.currentTimeMillis()
            )
        }
        return GroupsFilterNavigationEvidence(verified, matchedViewId, usedProfileHint)
    }

    fun resetRuntimeProfile(targetPackage: String) {
        runtimeProfileStore.resetPackage(targetPackage)
    }

    fun captureR7DiagnosticArtifact(runId: String, reason: String): String? = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching null
        val now = System.currentTimeMillis()
        val nodes = r7NodeCollector.capture(root, now)
        val observation = ScreenClassifier().classify(nodes, now)
        val (eventCount, lastEventAt) = R7ShadowRuntime.snapshot()
        val (scrollCount, lastScrollAt) = R7ShadowRuntime.scrollTelemetrySnapshot()
        val bundle = DiagnosticCapture(maxNodes = 512).capture(
            runId = runId,
            reason = reason,
            nodes = nodes,
            classifierEvidence = observation.evidence + listOf(
                "screen:${observation.type}",
                "confidence:${observation.confidence}",
                "package:${observation.packageName}",
                "window:${observation.windowId}"
            ),
            rowDecisions = emptyList(),
            scrollTelemetry = "events=$eventCount,lastEvent=$lastEventAt,scrolls=$scrollCount,lastScroll=$lastScrollAt",
            transitions = emptyList(),
            actions = emptyList(),
            verification = emptyList(),
            profileDecisions = runtimeProfiles.recommendations(
                runtimeProfileKey(observation.packageName), ProfileHintType.GROUP_FILTER_VIEW_ID
            ).map { "${it.type}:${it.value}:${it.confidence}:${it.applicability}:verify=${it.requiresVerification}" }
        )
        val fixture = ReplayFixture(capturedAt = now, nodes = nodes)
        diagnosticArtifacts.save(bundle, fixture, now).replayFile.absolutePath
    }.getOrNull()

    private fun runtimeProfileKey(targetPackage: String): RuntimeProfileKey {
        val versionName = runCatching {
            service.packageManager.getPackageInfo(targetPackage, 0).versionName.orEmpty()
        }.getOrDefault("")
        val instance = runCatching { Process.myUserHandle().hashCode().toString() }.getOrDefault("unknown")
        return RuntimeProfileKey(
            packageName = targetPackage,
            appVersion = versionName,
            localeTag = Locale.getDefault().toLanguageTag(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            deviceClass = Build.MODEL.orEmpty(),
            instanceIdentity = instance
        )
    }

    fun groupsFilterSelected(): Boolean = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching false
        val target = findGroupsFilterNode(root) ?: return@runCatching false
        if (target.isSelected) return@runCatching true
        var current: AccessibilityNodeInfo? = target
        repeat(5) {
            val node = current ?: return@repeat
            if (node.isSelected) return@runCatching true
            val desc = if (android.os.Build.VERSION.SDK_INT >= 30) node.stateDescription?.toString()?.lowercase().orEmpty() else ""
            if (desc.contains("selected") || desc.contains("محدد") || desc.contains("تم التحديد")) return@runCatching true
            current = node.parent
        }
        false
    }.getOrDefault(false)

    suspend fun clickAnyText(tokens: List<String>, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            val root = service.rootInActiveWindow
            if (root != null) {
                val target = breadthFirst(root, 2500).firstOrNull { node ->
                    val t = node.text?.toString()?.trim().orEmpty()
                    val d = node.contentDescription?.toString()?.trim().orEmpty()
                    tokens.any { it.equals(t, true) || it.equals(d, true) }
                }
                if (target != null && clickNodeOrAncestor(target)) return@withTimeoutOrNull true
            }
            delay(120)
        }
    } ?: false

    suspend fun openChatByName(name: String, maxScrolls: Int = 600): Boolean {
        if (scanChatListForName(name, maxScrolls.coerceAtMost(800))) return true
        // WhatsApp can return to a virtualized list below the requested row after leaving a chat.
        // A single bounded rewind gives one deterministic second chance without an endless loop.
        resetListToTop(maxScrolls = 900)
        return scanChatListForName(name, maxScrolls.coerceAtMost(800))
    }

    private suspend fun scanChatListForName(name: String, maxScrolls: Int): Boolean {
        var stagnant = 0
        var previousFingerprint = currentSnapshot()?.windowFingerprint.orEmpty()
        repeat(maxScrolls) {
            val root = service.rootInActiveWindow ?: return false
            val found = breadthFirst(root, 3000).firstOrNull { node ->
                node.text?.toString()?.trim()?.equals(name, true) == true &&
                    (node.isClickable || hasClickableAncestor(node, 4))
            }
            if (found != null && clickNodeOrAncestor(found)) {
                return withTimeoutOrNull(5_000) {
                    while (true) {
                        delay(120)
                        val snapshot = currentSnapshot()
                        val expectedTitle = name.trim().replace(Regex("\\s+"), " ")
                        val observedTitle = snapshot?.chatTitle?.trim()?.replace(Regex("\\s+"), " ")
                        if (observedTitle?.equals(expectedTitle, ignoreCase = true) == true) {
                            return@withTimeoutOrNull true
                        }
                    }
                } ?: false
            }
            val scroll = scrollListForwardVerified(2_000)
            val currentFingerprint = scroll.afterFingerprint
            stagnant = if (!scroll.progressed || currentFingerprint == previousFingerprint) stagnant + 1 else 0
            previousFingerprint = currentFingerprint
            if (stagnant >= 3) return false
            delay(140)
        }
        return false
    }

    fun visibleChatRowCandidates(): List<String> = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching emptyList()
        val height = service.resources.displayMetrics.heightPixels
        val timeLike = Regex("^(\\d{1,2}:\\d{2}|yesterday|today|أمس|اليوم)$", RegexOption.IGNORE_CASE)
        breadthFirst(root, 3200)
            .filter { it.isClickable }
            .mapNotNull { row ->
                val rect = Rect().also(row::getBoundsInScreen)
                if (rect.top < height * 0.12 || rect.bottom > height * 0.98 || rect.height() < 36) return@mapNotNull null
                val texts = descendantTexts(row, 80)
                    .map { it.trim() }
                    .filter { it.length in 2..120 }
                    .filterNot { it.lowercase() in ROW_NOISE || timeLike.matches(it) }
                    .distinct()
                texts.firstOrNull()
            }
            .distinct()
            .take(100)
            .toList()
    }.getOrDefault(emptyList())


    /** Open only a currently visible chat row; never scrolls or rewinds the list. */
    suspend fun openVisibleChatByName(name: String, timeoutMs: Long = 4_000): Boolean {
        val before = currentSnapshot()?.windowFingerprint.orEmpty()
        val root = service.rootInActiveWindow ?: return false
        val found = breadthFirst(root, 3000).firstOrNull { node ->
            node.text?.toString()?.trim()?.equals(name, true) == true &&
                (node.isClickable || hasClickableAncestor(node, 4))
        } ?: return false
        if (!clickNodeOrAncestor(found)) return false
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(120)
                val snapshot = currentSnapshot() ?: continue
                val changed = before.isBlank() || (snapshot.windowFingerprint.isNotBlank() && snapshot.windowFingerprint != before)
                val expectedTitle = name.trim().replace(Regex("\\s+"), " ")
                val observedTitle = snapshot.chatTitle?.trim()?.replace(Regex("\\s+"), " ")
                val exactTitle = observedTitle?.equals(expectedTitle, true) == true
                if (changed && exactTitle) return@withTimeoutOrNull true
            }
        } ?: false
    }

    /**
     * Conservative fallback probe used only when the Groups filter cannot be verified. It opens the
     * current conversation info surface when possible, captures explicit group labels, then returns
     * to the conversation. No classification decision is made in the Android bridge.
     */
    suspend fun currentConversationProbeLabels(expectedTitle: String, timeoutMs: Long = 3_500): List<String> {
        val base = currentSnapshot()?.visibleTexts.orEmpty()
        val root = service.rootInActiveWindow ?: return base
        val height = service.resources.displayMetrics.heightPixels
        val titleNode = breadthFirst(root, 1800).firstOrNull { node ->
            val rect = Rect().also(node::getBoundsInScreen)
            node.text?.toString()?.trim()?.equals(expectedTitle.trim(), true) == true &&
                rect.top >= 0 && rect.bottom <= (height * 0.30f).toInt() &&
                (node.isClickable || hasClickableAncestor(node, 4))
        } ?: return base
        val before = currentSnapshot()?.windowFingerprint.orEmpty()
        if (!clickNodeOrAncestor(titleNode)) return base
        val details = withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(140)
                val snapshot = currentSnapshot() ?: continue
                if (snapshot.windowFingerprint.isNotBlank() && snapshot.windowFingerprint != before) {
                    return@withTimeoutOrNull snapshot.visibleTexts
                }
            }
        }.orEmpty()
        if (details.isNotEmpty()) {
            goBack()
            withTimeoutOrNull(2_500) {
                while (true) {
                    delay(120)
                    if (currentSnapshot()?.chatTitle?.trim()?.equals(expectedTitle.trim(), true) == true) return@withTimeoutOrNull true
                }
            }
        }
        return (base + details).distinct()
    }


    suspend fun launchPackage(targetPackage: String, timeoutMs: Long = 8_000): Boolean {
        if (currentSnapshot()?.packageName == targetPackage) return true
        val launch = service.packageManager.getLaunchIntentForPackage(targetPackage)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?: return false
        val launched = runCatching { service.startActivity(launch); true }.getOrDefault(false)
        if (!launched) return false
        return waitForPackage(targetPackage, timeoutMs)
    }

    suspend fun openInvite(targetPackage: String, inviteUrl: String, timeoutMs: Long = 10_000): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(inviteUrl))
            .setPackage(targetPackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val launched = runCatching { service.startActivity(intent); true }.getOrDefault(false)
        if (!launched) return false
        return waitForPackage(targetPackage, timeoutMs)
    }

    fun inviteState(): InviteUiState = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching InviteUiState.UNKNOWN
        InviteStateClassifier.classify(
            visibleTexts = currentSnapshot()?.visibleTexts.orEmpty(),
            clickableLabels = breadthFirst(root, 3000)
                .filter { it.isClickable || hasClickableAncestor(it, 3) }
                .map(::nodeLabel)
                .filter { it.isNotBlank() }
                .take(500)
                .toList()
        )
    }.getOrDefault(InviteUiState.UNKNOWN)

    suspend fun awaitInviteState(timeoutMs: Long = 8_000): InviteUiState = withTimeoutOrNull(timeoutMs) {
        while (true) {
            if (hasMessageComposer()) return@withTimeoutOrNull InviteUiState.ALREADY_MEMBER
            val state = inviteState()
            if (state != InviteUiState.UNKNOWN) return@withTimeoutOrNull state
            delay(160)
        }
    } ?: inviteState()

    suspend fun prepareInviteAction(timeoutMs: Long = 8_000): PreparedInviteAction =
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                val prepared = prepareInviteActionOnce()
                if (prepared.state == InviteUiState.ALREADY_MEMBER ||
                    prepared.state == InviteUiState.REQUEST_SENT ||
                    prepared.state == InviteUiState.INVALID ||
                    prepared.target != null
                ) {
                    return@withTimeoutOrNull prepared
                }
                delay(160)
            }
        } ?: prepareInviteActionOnce()

    private fun prepareInviteActionOnce(): PreparedInviteAction = runCatching {
        if (hasMessageComposer()) {
            return@runCatching PreparedInviteAction(
                state = InviteUiState.ALREADY_MEMBER,
                detail = "message-composer-visible",
                evidence = "state=ALREADY_MEMBER;composer=true"
            )
        }
        val root = service.rootInActiveWindow
            ?: return@runCatching PreparedInviteAction(InviteUiState.UNKNOWN, detail = "no-active-root")
        val height = service.resources.displayMetrics.heightPixels
        val snapshot = snapshotter.snapshot(root, height)
        val capturedAt = System.currentTimeMillis()
        data class Entry(val node: AccessibilityNodeInfo, val depth: Int, val parentSignature: String)
        val queue = ArrayDeque<Entry>()
        queue.add(Entry(root, 0, "ROOT"))
        val clickableLabels = ArrayList<String>()
        val actionCandidates = ArrayList<JoinTargetCandidate>()
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 3_200) {
            val entry = queue.removeFirst()
            val node = entry.node
            val snap = joinNodeSnapshotFactory.fromNode(node, entry.depth, entry.parentSignature, capturedAt)
            val label = nodeLabel(node)
            val actionable = node.isClickable || hasClickableAncestor(node, 4) || breadthFirst(node, 40).drop(1).any { it.isClickable }
            if (label.isNotBlank() && actionable) {
                clickableLabels.add(label)
                actionCandidates.add(JoinTargetCandidate(label, ActionTargetEvidence.from(snap)))
            }
            if (entry.depth < 35) {
                val parentSignature = SnapshotSignature.compute(snap)
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(Entry(it, entry.depth + 1, parentSignature)) }
                }
            }
        }
        val state = InviteStateClassifier.classify(snapshot.visibleTexts, clickableLabels)
        if (state == InviteUiState.ALREADY_MEMBER || state == InviteUiState.REQUEST_SENT || state == InviteUiState.INVALID) {
            return@runCatching PreparedInviteAction(
                state = state,
                detail = "terminal-pre-state:${state.name}",
                evidence = "state=${state.name}"
            )
        }
        val resolution = joinTargetResolver.resolve(state, actionCandidates)
        val target = resolution.target
        PreparedInviteAction(
            state = state,
            target = target,
            resolutionStatus = resolution.status,
            detail = resolution.reason,
            evidence = target?.let { joinTargetEvidence(it, resolution.status.name) }
                ?: "state=${state.name};resolution=${resolution.status.name};reason=${resolution.reason}"
        )
    }.getOrElse { error ->
        PreparedInviteAction(InviteUiState.UNKNOWN, detail = "prepare-error:${error::class.simpleName}")
    }

    suspend fun performInviteAction(prepared: PreparedInviteAction, timeoutMs: Long = 10_000): InviteActionOutcome {
        if (hasMessageComposer()) {
            return InviteActionOutcome(true, InviteUiState.ALREADY_MEMBER, "Verified ALREADY_MEMBER", prepared.evidence)
        }
        val initial = prepared.state
        if (initial == InviteUiState.ALREADY_MEMBER || initial == InviteUiState.REQUEST_SENT) {
            return InviteActionOutcome(true, initial, "Verified ${initial.name}", prepared.evidence)
        }
        if (initial == InviteUiState.INVALID) {
            return InviteActionOutcome(false, initial, "Invite is invalid or expired", prepared.evidence)
        }
        var target = prepared.target
            ?: return InviteActionOutcome(false, initial, "No verified invite action target: ${prepared.detail}", prepared.evidence)
        if (target.initialState != initial) {
            return InviteActionOutcome(false, initial, "Prepared target state mismatch", prepared.evidence)
        }

        var dispatch = dispatchPreparedJoinTarget(target)
        if (dispatch.status != ResolutionStatus.MATCH || !dispatch.dispatched) {
            return InviteActionOutcome(
                false,
                initial,
                "Prepared invite target was not dispatched: ${dispatch.status}/${dispatch.reason}",
                joinTargetEvidence(target, "${dispatch.status}:${dispatch.reason}")
            )
        }

        if (target.kind == JoinTargetKind.COMMUNITY_VIEW) {
            val secondStage = withTimeoutOrNull((timeoutMs / 2).coerceIn(1_500L, 4_000L)) {
                while (true) {
                    delay(160)
                    if (hasMessageComposer()) return@withTimeoutOrNull null
                    val stateNow = inviteState()
                    if (stateNow == InviteUiState.INVALID || stateNow == InviteUiState.ALREADY_MEMBER) return@withTimeoutOrNull null
                    val candidate = prepareInviteActionOnce()
                    if (candidate.target?.kind == JoinTargetKind.COMMUNITY_JOIN) return@withTimeoutOrNull candidate.target
                }
            }
            if (secondStage != null) {
                target = secondStage
                dispatch = dispatchPreparedJoinTarget(target)
                if (dispatch.status != ResolutionStatus.MATCH || !dispatch.dispatched) {
                    return InviteActionOutcome(
                        false,
                        inviteState(),
                        "Community join target was not dispatched: ${dispatch.status}/${dispatch.reason}",
                        joinTargetEvidence(target, "${dispatch.status}:${dispatch.reason}")
                    )
                }
            } else if (!hasMessageComposer() && inviteState() != InviteUiState.ALREADY_MEMBER) {
                return InviteActionOutcome(
                    false,
                    inviteState(),
                    "Community view transition did not expose a verified Join community target",
                    joinTargetEvidence(target, "community-second-stage-missing")
                )
            }
        }

        val verification = withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(180)
                val state = inviteState()
                val composer = hasMessageComposer()
                val result = joinPostconditionVerifier.verify(initial, state, composerVisible = composer, dispatchAccepted = true)
                if (result.status != com.alothmany.wa.r7.join.JoinVerificationStatus.UNVERIFIED) {
                    return@withTimeoutOrNull result
                }
            }
        } ?: joinPostconditionVerifier.verify(initial, inviteState(), hasMessageComposer(), dispatchAccepted = true)

        val success = verification.status == com.alothmany.wa.r7.join.JoinVerificationStatus.VERIFIED
        return InviteActionOutcome(
            success = success,
            finalState = verification.finalState,
            detail = if (success) "Verified ${verification.finalState.name}" else verification.reason,
            evidence = joinTargetEvidence(target, "post=${verification.status}:${verification.reason}")
        )
    }

    private fun dispatchPreparedJoinTarget(target: JoinActionTarget) =
        joinAccessibilityGateway.dispatchOnResolvedTarget(target.evidence, maxAgeMs = 2_500L) { node ->
            clickReacquiredJoinNode(node)
        }

    private fun clickReacquiredJoinNode(node: AccessibilityNodeInfo): Boolean {
        // The live node was reacquired from the exact evidence returned by classification.
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = node.parent
        repeat(6) {
            val p = parent ?: return@repeat
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = p.parent
        }
        val clickableChild = breadthFirst(node, 80).drop(1).firstOrNull { it.isClickable }
        if (clickableChild?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val freshBounds = Rect().also(node::getBoundsInScreen)
        if (freshBounds.isEmpty) return false
        return dispatchTap(freshBounds)
    }

    private fun joinTargetEvidence(target: JoinActionTarget, phase: String): String =
        "state=${target.initialState.name};kind=${target.kind.name};target=${target.evidence.signature.take(20)};" +
            "pkg=${target.evidence.packageName};window=${target.evidence.windowId};phase=$phase"

    suspend fun setMessageText(message: String, timeoutMs: Long = 4_000): Boolean {
        if (message.isBlank()) return false
        val node = findMessageInputNode() ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        return withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(100)
                val current = findMessageInputNode()?.text?.toString().orEmpty()
                if (current == message) return@withTimeoutOrNull true
            }
        } ?: false
    }

    suspend fun sendCurrentMessage(expectedText: String, timeoutMs: Long = 8_000): PublishOutcome {
        val inputBefore = findMessageInputNode()?.text?.toString().orEmpty()
        if (inputBefore != expectedText) {
            return PublishOutcome(SendVerification.NOT_SENT, "Message input verification failed")
        }
        val beforeCount = exactMessageCount(expectedText)
        val target = findActionTarget(SEND_TOKENS)
            ?: return PublishOutcome(SendVerification.NOT_SENT, "Send button not found")
        val beforeFingerprint = currentSnapshot()?.windowFingerprint.orEmpty()
        val beforeEventAt = AutomationRuntime.eventBuffer.lastEventAt()
        if (!clickExactTarget(target)) {
            return PublishOutcome(SendVerification.NOT_SENT, "Send click was not accepted")
        }

        var lastComposerCleared = false
        var lastCount = beforeCount
        var lastUiAcknowledged = false
        withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(160)
                val currentText = findMessageInputNode()?.text?.toString().orEmpty()
                lastComposerCleared = currentText.isBlank()
                lastCount = exactMessageCount(expectedText)
                val currentFingerprint = currentSnapshot()?.windowFingerprint.orEmpty()
                lastUiAcknowledged = currentFingerprint != beforeFingerprint || AutomationRuntime.eventBuffer.lastEventAt() > beforeEventAt || beforeFingerprint.isBlank()
                val verification = SendVerificationPolicy.evaluate(
                    composerCleared = lastComposerCleared,
                    beforeExactCount = beforeCount,
                    afterExactCount = lastCount,
                    uiAcknowledged = lastUiAcknowledged
                )
                if (verification == SendVerification.VERIFIED) return@withTimeoutOrNull verification
            }
        }
        val verification = SendVerificationPolicy.evaluate(
            composerCleared = lastComposerCleared,
            beforeExactCount = beforeCount,
            afterExactCount = lastCount,
            uiAcknowledged = lastUiAcknowledged
        )
        val evidence = "composerCleared=$lastComposerCleared beforeExact=$beforeCount afterExact=$lastCount uiAck=$lastUiAcknowledged"
        return when (verification) {
            SendVerification.VERIFIED -> PublishOutcome(verification, "Exact sent message verified", evidence)
            SendVerification.VERIFY_PENDING -> PublishOutcome(verification, "Send click acknowledged but exact message needs verification", evidence)
            SendVerification.NOT_SENT -> PublishOutcome(verification, "No verified post-send transition", evidence)
        }
    }

    fun exactMessageCount(expectedText: String): Int {
        if (expectedText.isBlank()) return 0
        val expected = expectedText.trim()
        return currentSnapshot()?.messages.orEmpty().count { message ->
            val text = message.text.trim()
            text == expected || text.contains(expected)
        }
    }

    fun hasMessageComposer(): Boolean = findMessageInputNode() != null

    fun messageVisibleInCurrentChat(expectedText: String): Boolean {
        if (expectedText.isBlank()) return false
        return currentSnapshot()?.messages.orEmpty().any { message ->
            message.text == expectedText || message.text.contains(expectedText)
        }
    }

    private fun findMessageInputNode(): AccessibilityNodeInfo? = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching null
        val height = service.resources.displayMetrics.heightPixels
        breadthFirst(root, 3000).firstOrNull { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            val editable = node.isEditable || node.className?.toString()?.contains("EditText", true) == true
            editable && bounds.bottom > (height * 0.62f) && bounds.height() > 24
        }
    }.getOrNull()

    private fun findActionTarget(tokens: List<String>): UiActionTarget? = runCatching {
        val root = service.rootInActiveWindow ?: return@runCatching null
        breadthFirst(root, 3200)
            .mapNotNull { node ->
                val label = nodeLabel(node)
                if (label.isBlank()) return@mapNotNull null
                if (tokens.none { token -> label.equals(token, true) || label.contains(token, true) }) return@mapNotNull null
                val actionable = node.isClickable || hasClickableAncestor(node, 4) || breadthFirst(node, 40).drop(1).any { it.isClickable }
                if (!actionable) return@mapNotNull null
                val bounds = Rect().also(node::getBoundsInScreen)
                if (bounds.isEmpty) return@mapNotNull null
                UiActionTarget(node, bounds, label)
            }
            .firstOrNull()
    }.getOrNull()

    private fun clickExactTarget(target: UiActionTarget): Boolean {
        // Preserve target identity: same node -> clickable parent -> clickable child -> gesture center.
        if (target.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        var parent = target.node.parent
        repeat(6) {
            val p = parent ?: return@repeat
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            parent = p.parent
        }
        val clickableChild = breadthFirst(target.node, 80).drop(1).firstOrNull { it.isClickable }
        if (clickableChild?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        return dispatchTap(target.bounds)
    }

    private fun dispatchTap(bounds: Rect): Boolean {
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        return dispatchTrackedGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 90)).build()
        )
    }

    private fun descendantTexts(root: AccessibilityNodeInfo, limit: Int): List<String> {
        val out = ArrayList<String>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count++ < limit) {
            val node = q.removeFirst()
            node.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            for (i in 0 until node.childCount) node.getChild(i)?.let(q::addLast)
        }
        return out
    }

    /**
     * R7-M3 semantic scroll. Each strategy is re-resolved from the current accessibility tree,
     * and dispatch success is not accepted until the viewport changes or TYPE_VIEW_SCROLLED telemetry advances.
     */
    suspend fun semanticScroll(
        direction: SemanticScrollDirection,
        controller: SmartScrollController,
        timeoutMs: Long = 3_000
    ): SemanticScrollResult {
        val cycleBefore = currentSnapshot()?.windowFingerprint.orEmpty()
        var directive = controller.begin(direction)
        var lastAfter = cycleBefore
        var acceptedAny = false
        var lastStrategy = ScrollStrategy.PRIMARY_NODE
        val perAttemptTimeout = (timeoutMs / 3).coerceIn(300L, 1_200L)

        while (true) {
            val strategy = when (directive.action) {
                ScrollDirectiveAction.TRY_PRIMARY_NODE -> ScrollStrategy.PRIMARY_NODE
                ScrollDirectiveAction.TRY_ALTERNATE_NODE -> ScrollStrategy.ALTERNATE_NODE
                ScrollDirectiveAction.TRY_SAFE_GESTURE -> ScrollStrategy.SAFE_GESTURE
                else -> lastStrategy
            }
            lastStrategy = strategy
            val before = currentSnapshot()?.windowFingerprint.orEmpty().ifBlank { lastAfter }
            val telemetryBefore = R7ShadowRuntime.scrollTelemetrySnapshot().first
            val dispatched = when (strategy) {
                ScrollStrategy.PRIMARY_NODE -> dispatchSemanticNodeScroll(direction, candidateIndex = 0)
                ScrollStrategy.ALTERNATE_NODE -> dispatchSemanticNodeScroll(direction, candidateIndex = 1)
                ScrollStrategy.SAFE_GESTURE -> dispatchSemanticGesture(direction)
            }
            acceptedAny = acceptedAny || dispatched

            var after = before
            var telemetryAdvanced = false
            if (dispatched) {
                val observed = withTimeoutOrNull(perAttemptTimeout) {
                    while (true) {
                        delay(100)
                        val nowFingerprint = currentSnapshot()?.windowFingerprint.orEmpty()
                        val nowTelemetry = R7ShadowRuntime.scrollTelemetrySnapshot().first
                        val viewportChanged = before.isNotBlank() && nowFingerprint.isNotBlank() && nowFingerprint != before
                        telemetryAdvanced = nowTelemetry > telemetryBefore
                        if (viewportChanged || telemetryAdvanced) return@withTimeoutOrNull nowFingerprint.ifBlank { before }
                    }
                }
                after = observed ?: currentSnapshot()?.windowFingerprint.orEmpty().ifBlank { before }
                telemetryAdvanced = telemetryAdvanced || R7ShadowRuntime.scrollTelemetrySnapshot().first > telemetryBefore
            }
            lastAfter = after

            directive = controller.observeAttempt(
                direction,
                strategy,
                ScrollAttemptEvidence(
                    dispatched = dispatched,
                    beforeViewportSignature = before,
                    afterViewportSignature = after,
                    telemetryAdvanced = telemetryAdvanced
                )
            )

            when (directive.action) {
                ScrollDirectiveAction.TRY_ALTERNATE_NODE,
                ScrollDirectiveAction.TRY_SAFE_GESTURE -> continue
                ScrollDirectiveAction.PROGRESSED -> return SemanticScrollResult(
                    actionAccepted = acceptedAny,
                    progressed = true,
                    boundaryConfirmed = false,
                    recoveryRequired = false,
                    beforeViewportSignature = cycleBefore,
                    afterViewportSignature = lastAfter,
                    terminalConfirmations = 0,
                    finalStrategy = strategy
                )
                ScrollDirectiveAction.CONFIRM_BOUNDARY -> return SemanticScrollResult(
                    actionAccepted = acceptedAny,
                    progressed = false,
                    boundaryConfirmed = false,
                    recoveryRequired = false,
                    beforeViewportSignature = cycleBefore,
                    afterViewportSignature = lastAfter,
                    terminalConfirmations = directive.terminalConfirmations,
                    finalStrategy = strategy
                )
                ScrollDirectiveAction.BOUNDARY -> return SemanticScrollResult(
                    actionAccepted = acceptedAny,
                    progressed = false,
                    boundaryConfirmed = true,
                    recoveryRequired = false,
                    beforeViewportSignature = cycleBefore,
                    afterViewportSignature = lastAfter,
                    terminalConfirmations = directive.terminalConfirmations,
                    finalStrategy = strategy
                )
                ScrollDirectiveAction.RECOVER -> return SemanticScrollResult(
                    actionAccepted = acceptedAny,
                    progressed = false,
                    boundaryConfirmed = false,
                    recoveryRequired = true,
                    beforeViewportSignature = cycleBefore,
                    afterViewportSignature = lastAfter,
                    terminalConfirmations = directive.terminalConfirmations,
                    finalStrategy = strategy
                )
                ScrollDirectiveAction.TRY_PRIMARY_NODE -> continue
            }
        }
    }

    private fun dispatchSemanticNodeScroll(direction: SemanticScrollDirection, candidateIndex: Int): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val metrics = service.resources.displayMetrics
        val minHeight = (metrics.heightPixels * 0.18f).toInt()
        val minWidth = (metrics.widthPixels * 0.30f).toInt()
        val candidates = breadthFirst(root, 3_200)
            .filter { it.isScrollable && it.isVisibleToUser }
            .map { node -> node to Rect().also(node::getBoundsInScreen) }
            .filter { (_, rect) -> !rect.isEmpty && rect.height() >= minHeight && rect.width() >= minWidth }
            .distinctBy { (node, rect) -> "${node.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}" }
            .sortedByDescending { (_, rect) -> rect.width().toLong() * rect.height().toLong() }
            .toList()
        val target = candidates.getOrNull(candidateIndex)?.first ?: return false
        val action = when (direction) {
            SemanticScrollDirection.OLDER, SemanticScrollDirection.LIST_BACKWARD -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            SemanticScrollDirection.NEWER, SemanticScrollDirection.LIST_FORWARD -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        return target.performAction(action)
    }

    private fun dispatchSemanticGesture(direction: SemanticScrollDirection): Boolean = when (direction) {
        SemanticScrollDirection.OLDER, SemanticScrollDirection.LIST_BACKWARD -> dispatchSwipeDown()
        SemanticScrollDirection.NEWER, SemanticScrollDirection.LIST_FORWARD -> dispatchSwipeUp()
    }

    suspend fun scrollOlder(timeoutMs: Long = 4_000): ScrollResult {
        val before = currentSnapshot()?.windowFingerprint.orEmpty()
        val root = service.rootInActiveWindow
        var accepted = false
        if (root != null) {
            val scrollable = breadthFirst(root, 3000).firstOrNull { it.isScrollable }
            accepted = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
        }
        if (!accepted) accepted = dispatchSwipeDown()
        val after = if (accepted) {
            withTimeoutOrNull(timeoutMs) {
                while (true) {
                    delay(140)
                    val fp = currentSnapshot()?.windowFingerprint.orEmpty()
                    if (fp.isNotBlank() && fp != before) return@withTimeoutOrNull fp
                }
            } ?: currentSnapshot()?.windowFingerprint.orEmpty()
        } else before
        return ScrollResult(accepted, before.isNotBlank() && after != before, before, after)
    }

    fun goBack(): Boolean = service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)

    suspend fun scrollListForward(): Boolean = scrollListForwardVerified().actionAccepted

    suspend fun scrollListForwardVerified(timeoutMs: Long = 2_000): ScrollResult {
        val before = currentSnapshot()?.windowFingerprint.orEmpty()
        val root = service.rootInActiveWindow
        val scrollable = root?.let { breadthFirst(it, 2500).firstOrNull { node -> node.isScrollable } }
        val accepted = scrollable?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
        if (!accepted) return ScrollResult(false, false, before, before)
        val after = withTimeoutOrNull(timeoutMs) {
            while (true) {
                delay(120)
                val fp = currentSnapshot()?.windowFingerprint.orEmpty()
                if (fp.isNotBlank() && fp != before) return@withTimeoutOrNull fp
            }
        } ?: currentSnapshot()?.windowFingerprint.orEmpty()
        return ScrollResult(true, before.isNotBlank() && after != before, before, after)
    }

    suspend fun resetListToTop(maxScrolls: Int = 1200) {
        var quiet = 0
        var previous = currentSnapshot()?.windowFingerprint.orEmpty()
        repeat(maxScrolls) {
            val root = service.rootInActiveWindow ?: return
            val scrollable = breadthFirst(root, 2500).firstOrNull { it.isScrollable } ?: return
            val accepted = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            delay(100)
            val now = currentSnapshot()?.windowFingerprint.orEmpty()
            quiet = if (!accepted || now == previous) quiet + 1 else 0
            previous = now
            if (quiet >= 3) return
        }
    }

    private fun dispatchSwipeDown(): Boolean {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.35f)
            lineTo(x, metrics.heightPixels * 0.80f)
        }
        return dispatchTrackedGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build()
        )
    }

    private fun dispatchSwipeUp(): Boolean {
        val metrics = service.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.80f)
            lineTo(x, metrics.heightPixels * 0.35f)
        }
        return dispatchTrackedGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 450)).build()
        )
    }

    private fun dispatchTrackedGesture(description: GestureDescription): Boolean {
        AutomationRuntime.markGestureStarted()
        val accepted = service.dispatchGesture(
            description,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    AutomationRuntime.markGestureFinished()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    AutomationRuntime.markGestureFinished()
                }
            },
            null
        )
        if (!accepted) AutomationRuntime.markGestureFinished()
        return accepted
    }

    private fun findGroupsFilterNode(
        root: AccessibilityNodeInfo,
        preferredViewIds: List<String> = emptyList()
    ): AccessibilityNodeInfo? {
        val nodes = breadthFirst(root, 2600).toList()
        if (preferredViewIds.isNotEmpty()) {
            nodes.firstOrNull { node ->
                val id = node.viewIdResourceName.orEmpty()
                id.isNotBlank() && preferredViewIds.any { it == id } && node.isVisibleToUser &&
                    (node.isClickable || hasClickableAncestor(node, 4))
            }?.let { return it }
        }
        val exact = nodes.firstOrNull { node ->
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val id = node.viewIdResourceName.orEmpty().lowercase()
            GROUP_FILTER_TOKENS.any { token -> token.equals(text, true) || token.equals(desc, true) } ||
                ("group" in id && "filter" in id)
        }
        if (exact != null) return exact

        // Guarded fallback for decorated filter labels (for example "Groups, 3 unread").
        // It only accepts a candidate near the top that has a clickable path and filter-chip peers.
        val height = service.resources.displayMetrics.heightPixels
        val peerTokens = listOf("All", "Unread", "Favorites", "الكل", "غير مقروءة", "المفضلة")
        val hasFilterPeer = nodes.any { node ->
            val label = nodeLabel(node)
            peerTokens.any { token -> label.equals(token, true) || label.startsWith("$token,", true) }
        }
        if (!hasFilterPeer) return null
        return nodes.firstOrNull { node ->
            val label = nodeLabel(node)
            val rect = Rect().also(node::getBoundsInScreen)
            val decoratedGroups = GROUP_FILTER_TOKENS.any { token ->
                label.startsWith("$token,", true) || label.startsWith("$token ", true)
            }
            decoratedGroups && rect.top >= 0 && rect.top < (height * 0.42f).toInt() &&
                (node.isClickable || hasClickableAncestor(node, 4))
        }
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val n = current ?: return@repeat
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = n.parent
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo, depth: Int): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(depth) {
            current = current?.parent
            if (current?.isClickable == true) return true
        }
        return node.isClickable
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String = listOfNotNull(
        node.text?.toString(),
        node.contentDescription?.toString()
    ).joinToString(" ").trim()

    private fun breadthFirst(root: AccessibilityNodeInfo, limit: Int): Sequence<AccessibilityNodeInfo> = sequence {
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        var count = 0
        while (q.isNotEmpty() && count++ < limit) {
            val node = q.removeFirst()
            yield(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(q::addLast)
        }
    }
}
