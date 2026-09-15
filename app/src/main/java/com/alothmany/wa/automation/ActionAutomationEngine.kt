package com.alothmany.wa.automation

import com.alothmany.wa.accessibility.PublishOutcome
import com.alothmany.wa.accessibility.WhatsAppUiBridge
import com.alothmany.wa.data.ActionItemEntity
import com.alothmany.wa.data.ActionRepository
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.DiagnosticEventEntity
import com.alothmany.wa.domain.ActionRecoveryPolicy
import com.alothmany.wa.domain.InviteUiState
import com.alothmany.wa.domain.PublishRecoveryAction
import com.alothmany.wa.domain.SendVerification
import kotlinx.coroutines.delay

class ActionAutomationEngine(
    private val db: AppDatabase,
    private val bridge: WhatsAppUiBridge,
    val controller: AutomationController = AutomationController()
) {
    private val repo = ActionRepository(db)

    suspend fun runInviteScan(jobId: String): String {
        val job = db.actionJobDao().get(jobId) ?: return "FAILED"
        repo.setJobStatus(jobId, "RUNNING")
        for (item in db.actionItemDao().pendingForJob(jobId)) {
            if (!awaitActionBoundary(jobId)) return stopJob(jobId)
            val success = processInviteScanItem(job.targetPackage, item)
            if (controller.state.value == ControlState.STOPPED) return stopJob(jobId)
            if (!success) diagnostic("WARN", "invite-scan", "SCAN_ITEM_UNRESOLVED", item.target)
            delay(if (success) 650 else 450)
        }
        return finalizeJob(jobId)
    }

    suspend fun runJoin(jobId: String): String {
        val job = db.actionJobDao().get(jobId) ?: return "FAILED"
        repo.setJobStatus(jobId, "RUNNING")
        for (item in db.actionItemDao().pendingForJob(jobId)) {
            if (!awaitActionBoundary(jobId)) return stopJob(jobId)
            val success = processJoinItem(job.targetPackage, item)
            if (controller.state.value == ControlState.STOPPED) return stopJob(jobId)
            if (!success) diagnostic("WARN", "join", "JOIN_ITEM_FAILED", item.target)
            delay(if (success) 1_200 else 700)
        }
        return finalizeJob(jobId)
    }

    suspend fun runPublish(jobId: String): String {
        val job = db.actionJobDao().get(jobId) ?: return "FAILED"
        val message = job.payloadText.orEmpty()
        if (message.isBlank()) {
            repo.setJobStatus(jobId, "FAILED", "Publish message is blank")
            return "FAILED"
        }
        repo.setJobStatus(jobId, "RUNNING")
        if (!ensurePackage(job.targetPackage)) {
            repo.setJobStatus(jobId, "RECOVERING", "WhatsApp package unavailable")
            return "RECOVERING"
        }
        for (item in db.actionItemDao().pendingForJob(jobId)) {
            if (!awaitActionBoundary(jobId)) return stopJob(jobId)
            val recoveryAction = ActionRecoveryPolicy.forPublish(item.phase)
            val success = when (recoveryAction) {
                PublishRecoveryAction.DONE -> true
                PublishRecoveryAction.VERIFY_ONLY -> verifyPublishWithoutResend(job.targetPackage, item, message)
                PublishRecoveryAction.MAY_SEND -> processPublishItem(job.targetPackage, item, message)
            }
            if (controller.state.value == ControlState.STOPPED) return stopJob(jobId)
            if (!success) diagnostic("WARN", "publish", "PUBLISH_ITEM_UNRESOLVED", item.target)
            delay(if (success) 2_200 else 900)
        }
        return finalizeJob(jobId)
    }

    private suspend fun processInviteScanItem(targetPackage: String, original: ActionItemEntity): Boolean {
        var current = original
        while (current.attempts < 2) {
            if (!awaitActionBoundary(original.jobId)) return false
            current = repo.markAttemptStarted(current, "OPEN_INVITE")
            if (!bridge.openInvite(targetPackage, current.target)) {
                if (current.attempts >= 2) repo.markFailure(current, "OPEN_FAILED", "Invite could not be opened")
                delay(350)
                continue
            }
            val state = bridge.awaitInviteState(8_000)
            val evidence = inviteEvidence(state)
            if (state != InviteUiState.UNKNOWN) {
                repo.markSuccess(
                    item = current,
                    phase = "CLASSIFIED",
                    resultState = state.name,
                    detail = "Invite classified as ${state.name}",
                    evidence = evidence
                )
                return true
            }
            if (current.attempts >= 2) {
                repo.markFailure(current, "CLASSIFICATION_UNKNOWN", "Invite state remained unknown", InviteUiState.UNKNOWN.name, evidence)
            }
            delay(450)
        }
        return false
    }

    private suspend fun processJoinItem(targetPackage: String, original: ActionItemEntity): Boolean {
        var current = original
        while (current.attempts < 3) {
            if (!awaitActionBoundary(original.jobId)) return false
            current = repo.markAttemptStarted(current, "OPEN_INVITE")
            if (!bridge.openInvite(targetPackage, current.target)) {
                if (current.attempts >= 3) repo.markFailure(current, "OPEN_FAILED", "Invite could not be opened")
                delay(500)
                continue
            }
            val prepared = bridge.prepareInviteAction(8_000)
            val state = prepared.state
            val evidence = prepared.evidence ?: inviteEvidence(state)
            when (state) {
                InviteUiState.ALREADY_MEMBER -> {
                    repo.markSuccess(current, "VERIFIED", state.name, "Already a member", evidence)
                    return true
                }
                InviteUiState.REQUEST_SENT -> {
                    repo.markSuccess(current, "VERIFIED", state.name, "Join request already sent", evidence)
                    return true
                }
                InviteUiState.INVALID -> {
                    repo.markFailure(current, "VERIFIED_INVALID", "Invite invalid or expired", state.name, evidence)
                    return false
                }
                InviteUiState.UNKNOWN -> {
                    if (current.attempts >= 3) repo.markFailure(current, "CLASSIFICATION_UNKNOWN", "Invite state remained unknown", state.name, evidence)
                    delay(500)
                    continue
                }
                else -> {
                    if (prepared.target == null) {
                        if (current.attempts >= 3) {
                            repo.markFailure(current, "ACTION_TARGET_UNRESOLVED", prepared.detail, state.name, evidence)
                            bridge.captureR7DiagnosticArtifact("join:${original.jobId}", "action-target-unresolved")
                        }
                        delay(500)
                        continue
                    }
                    current = repo.markProgress(
                        current,
                        "ACTION_TARGET_VERIFIED",
                        resultState = state.name,
                        detail = prepared.detail,
                        evidence = evidence
                    )
                }
            }

            val outcome = bridge.performInviteAction(prepared)
            if (outcome.success) {
                repo.markSuccess(current, "VERIFIED", outcome.finalState.name, outcome.detail, outcome.evidence ?: evidence)
                return true
            }
            if (outcome.finalState == InviteUiState.INVALID) {
                repo.markFailure(current, "VERIFIED_INVALID", outcome.detail, outcome.finalState.name, outcome.evidence ?: evidence)
                return false
            }
            if (current.attempts >= 3) {
                repo.markFailure(current, "UNVERIFIED", outcome.detail, outcome.finalState.name, outcome.evidence ?: evidence)
                bridge.captureR7DiagnosticArtifact("join:${original.jobId}", "postcondition-unverified")
            }
            delay(700)
        }
        return false
    }

    private suspend fun processPublishItem(targetPackage: String, original: ActionItemEntity, message: String): Boolean {
        var current = original
        while (current.attempts < 2) {
            if (!awaitActionBoundary(original.jobId)) return false
            current = repo.markAttemptStarted(current, "PREPARE")
            if (!preparePublishTarget(targetPackage, current.target)) {
                if (current.attempts >= 2) repo.markFailure(current, "TARGET_UNAVAILABLE", "Group could not be opened and verified")
                continue
            }
            if (!bridge.setMessageText(message)) {
                bridge.goBack()
                if (current.attempts >= 2) repo.markFailure(current, "COMPOSER_FAILED", "Message input could not be verified")
                continue
            }

            val beforeCount = bridge.exactMessageCount(message)
            // Persist a fence BEFORE the risky click. Recovery of this phase is verify-only,
            // preventing a duplicate send if the process dies between click and persistence.
            current = repo.markProgress(
                current,
                phase = "SEND_FENCE_ARMED",
                detail = "Send fenced; automatic resend disabled until verification",
                evidence = "beforeExact=$beforeCount"
            )

            val outcome: PublishOutcome = bridge.sendCurrentMessage(message)
            when (outcome.verification) {
                SendVerification.VERIFIED -> {
                    repo.markSuccess(current, "VERIFIED", "SENT", outcome.detail, outcome.evidence)
                    bridge.goBack()
                    return true
                }
                SendVerification.VERIFY_PENDING -> {
                    repo.markVerifyPending(current, outcome.detail, outcome.evidence)
                    bridge.goBack()
                    return false
                }
                SendVerification.NOT_SENT -> {
                    // Composer remained populated / click was rejected: this path is safe to retry.
                    bridge.goBack()
                    if (current.attempts >= 2) repo.markFailure(current, "NOT_SENT", outcome.detail, "NOT_SENT", outcome.evidence)
                }
            }
            delay(600)
        }
        return false
    }

    private suspend fun verifyPublishWithoutResend(targetPackage: String, original: ActionItemEntity, message: String): Boolean {
        if (!preparePublishTarget(targetPackage, original.target)) {
            val checked = repo.recordVerificationAttempt(original, "Target unavailable during verify-only recovery")
            if (checked.verificationAttempts >= 3) {
                repo.markFailure(
                    checked,
                    "UNVERIFIED_AFTER_SEND",
                    "Potential prior send could not be verified; it was NOT resent",
                    "UNVERIFIED_NO_RESEND",
                    checked.lastEvidence
                )
            }
            return false
        }

        if (bridge.messageVisibleInCurrentChat(message)) {
            repo.markSuccess(
                original,
                "VERIFIED_RECOVERY",
                "SENT",
                "Recovered: exact message verified in chat",
                "verifyOnly exactMessageVisible=true"
            )
            bridge.goBack()
            return true
        }

        val checked = repo.recordVerificationAttempt(original, "verifyOnly exactMessageVisible=false")
        bridge.goBack()
        if (checked.verificationAttempts >= 3) {
            repo.markFailure(
                checked,
                "UNVERIFIED_AFTER_SEND",
                "Potential prior send could not be verified after 3 checks; it was NOT resent",
                "UNVERIFIED_NO_RESEND",
                checked.lastEvidence
            )
        }
        return false
    }

    private suspend fun preparePublishTarget(targetPackage: String, groupName: String): Boolean {
        if (!ensurePackage(targetPackage)) return false
        if (!bridge.ensureChatList()) return false
        if (!bridge.navigateToGroupsFilter()) return false
        if (!bridge.openChatByName(groupName)) return false
        val snapshot = bridge.currentSnapshot() ?: return false
        return snapshot.packageName == targetPackage &&
            (snapshot.chatTitle?.contains(groupName, true) == true || snapshot.visibleTexts.any { it.equals(groupName, true) })
    }

    private fun inviteEvidence(state: InviteUiState): String {
        val snapshot = bridge.currentSnapshot()
        return "state=${state.name} package=${snapshot?.packageName.orEmpty()} fingerprint=${snapshot?.windowFingerprint.orEmpty()}"
    }

    private suspend fun ensurePackage(targetPackage: String): Boolean = bridge.launchPackage(targetPackage, 8_000)

    private suspend fun awaitActionBoundary(jobId: String): Boolean {
        val runnable = controller.awaitRunnable {
            repo.setJobStatus(jobId, "PAUSED")
            diagnostic("INFO", "lifecycle", "ACTION_PAUSED_ATOMIC_BOUNDARY", jobId)
        }
        if (runnable && db.actionJobDao().get(jobId)?.status == "PAUSED") {
            repo.setJobStatus(jobId, "RUNNING")
            diagnostic("INFO", "lifecycle", "ACTION_RESUMED_DURABLE_JOB", jobId)
        }
        return runnable
    }

    private suspend fun finalizeJob(jobId: String): String {
        repo.refreshJobCounts(jobId)
        val job = db.actionJobDao().get(jobId) ?: return "FAILED"
        val verifyPending = repo.verifyPendingCount(jobId)
        val finalStatus = when {
            verifyPending > 0 -> "RECOVERING"
            job.failedItems > 0 -> "PARTIAL"
            job.completedItems >= job.totalItems -> "COMPLETE"
            else -> "PARTIAL"
        }
        repo.setJobStatus(jobId, finalStatus)
        return finalStatus
    }

    private suspend fun stopJob(jobId: String): String {
        repo.setJobStatus(jobId, "STOPPED")
        return "STOPPED"
    }

    private suspend fun diagnostic(severity: String, component: String, code: String, message: String) {
        db.diagnosticDao().insert(
            DiagnosticEventEntity(
                timestamp = System.currentTimeMillis(),
                severity = severity,
                component = component,
                code = code,
                message = message
            )
        )
    }
}
