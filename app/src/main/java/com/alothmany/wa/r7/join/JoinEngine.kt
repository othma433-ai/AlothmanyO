package com.alothmany.wa.r7.join

import com.alothmany.wa.domain.InviteUiState
import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import java.security.MessageDigest

enum class JoinTargetKind {
    DIRECT_JOIN,
    APPROVAL_REQUEST,
    COMMUNITY_JOIN,
    COMMUNITY_VIEW
}

data class JoinTargetCandidate(
    val label: String,
    val evidence: ActionTargetEvidence
)

data class JoinActionTarget(
    val initialState: InviteUiState,
    val kind: JoinTargetKind,
    val evidence: ActionTargetEvidence,
    val labelHash: String
)

enum class JoinTargetResolutionStatus { MATCH, NOT_FOUND, AMBIGUOUS, UNSUPPORTED_STATE }

data class JoinTargetResolution(
    val status: JoinTargetResolutionStatus,
    val target: JoinActionTarget? = null,
    val reason: String
)

class JoinTargetResolver {
    private val direct = setOf(
        "join group", "join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام"
    )
    private val approval = setOf("request to join", "طلب الانضمام", "إرسال طلب انضمام")
    private val communityJoin = setOf("join community", "انضم إلى المجتمع")
    private val communityView = setOf("view community", "عرض المجتمع")

    fun resolve(initialState: InviteUiState, candidates: List<JoinTargetCandidate>): JoinTargetResolution {
        val prioritized: List<Pair<JoinTargetKind, Set<String>>> = when (initialState) {
            InviteUiState.DIRECT -> listOf(JoinTargetKind.DIRECT_JOIN to direct)
            InviteUiState.APPROVAL -> listOf(JoinTargetKind.APPROVAL_REQUEST to approval)
            InviteUiState.COMMUNITY -> listOf(
                JoinTargetKind.COMMUNITY_JOIN to communityJoin,
                JoinTargetKind.COMMUNITY_VIEW to communityView
            )
            else -> return JoinTargetResolution(
                JoinTargetResolutionStatus.UNSUPPORTED_STATE,
                reason = "state:${initialState.name}"
            )
        }

        for ((kind, labels) in prioritized) {
            val matches = candidates.filter { candidate -> norm(candidate.label) in labels.map(::norm).toSet() }
            if (matches.size > 1) {
                return JoinTargetResolution(JoinTargetResolutionStatus.AMBIGUOUS, reason = "multiple-${kind.name.lowercase()}")
            }
            if (matches.size == 1) {
                val chosen = matches.single()
                return JoinTargetResolution(
                    JoinTargetResolutionStatus.MATCH,
                    JoinActionTarget(
                        initialState = initialState,
                        kind = kind,
                        evidence = chosen.evidence,
                        labelHash = hash(chosen.label)
                    ),
                    reason = "exact-label:${kind.name.lowercase()}"
                )
            }
        }
        return JoinTargetResolution(JoinTargetResolutionStatus.NOT_FOUND, reason = "no-exact-action-label")
    }

    private fun norm(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(norm(value).toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }
}

enum class JoinVerificationStatus { VERIFIED, INVALID, UNVERIFIED }

data class JoinVerificationResult(
    val status: JoinVerificationStatus,
    val finalState: InviteUiState,
    val reason: String
)

class JoinPostconditionVerifier {
    fun verify(
        initialState: InviteUiState,
        finalState: InviteUiState,
        composerVisible: Boolean,
        dispatchAccepted: Boolean = true
    ): JoinVerificationResult {
        if (finalState == InviteUiState.INVALID) {
            return JoinVerificationResult(JoinVerificationStatus.INVALID, finalState, "invite-invalid")
        }
        if (!dispatchAccepted && !composerVisible) {
            return JoinVerificationResult(JoinVerificationStatus.UNVERIFIED, finalState, "dispatch-rejected")
        }
        if (composerVisible) {
            return JoinVerificationResult(JoinVerificationStatus.VERIFIED, InviteUiState.ALREADY_MEMBER, "message-composer-visible")
        }
        val verified = when (initialState) {
            InviteUiState.APPROVAL -> finalState == InviteUiState.REQUEST_SENT || finalState == InviteUiState.ALREADY_MEMBER
            InviteUiState.DIRECT, InviteUiState.COMMUNITY -> finalState == InviteUiState.ALREADY_MEMBER
            else -> false
        }
        return if (verified) {
            JoinVerificationResult(JoinVerificationStatus.VERIFIED, finalState, "verified-postcondition:${finalState.name}")
        } else {
            JoinVerificationResult(JoinVerificationStatus.UNVERIFIED, finalState, "no-verified-postcondition")
        }
    }
}

class JoinTargetIdentityGuard(private val maxEvidenceAgeMs: Long = 2_000) {
    fun accept(original: ActionTargetEvidence, reacquired: ActionTargetEvidence, now: Long): Boolean {
        if (now - original.capturedAt > maxEvidenceAgeMs) return false
        return original.packageName == reacquired.packageName &&
            original.windowId == reacquired.windowId &&
            original.signature == reacquired.signature &&
            original.viewId == reacquired.viewId &&
            original.className == reacquired.className &&
            original.left == reacquired.left && original.top == reacquired.top &&
            original.right == reacquired.right && original.bottom == reacquired.bottom
    }
}
