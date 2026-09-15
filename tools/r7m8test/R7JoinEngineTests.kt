import com.alothmany.wa.domain.InviteUiState
import com.alothmany.wa.r7.join.*
import com.alothmany.wa.r7.snapshot.ActionTargetEvidence

private fun checkThat(condition: Boolean, message: String) {
    if (!condition) error(message)
}

private fun evidence(sig: String, left: Int = 10) = ActionTargetEvidence(
    packageName = "com.whatsapp",
    windowId = 7,
    signature = sig,
    viewId = "com.whatsapp:id/invite_action",
    className = "android.widget.Button",
    left = left,
    top = 100,
    right = left + 180,
    bottom = 160,
    capturedAt = 1_000L
)

fun main() {
    val resolver = JoinTargetResolver()
    val approvalEvidence = evidence("approval")
    val approval = resolver.resolve(
        InviteUiState.APPROVAL,
        listOf(
            JoinTargetCandidate("Learn how to request to join", evidence("noise")),
            JoinTargetCandidate("Request to join", approvalEvidence)
        )
    )
    checkThat(approval.status == JoinTargetResolutionStatus.MATCH, "approval target must resolve")
    checkThat(approval.target?.kind == JoinTargetKind.APPROVAL_REQUEST, "approval target kind must be preserved")
    checkThat(approval.target?.evidence == approvalEvidence, "classifier must return evidence for the same classified target")

    val misleadingOnly = resolver.resolve(
        InviteUiState.APPROVAL,
        listOf(JoinTargetCandidate("Learn how to request to join", evidence("noise-only")))
    )
    checkThat(misleadingOnly.status == JoinTargetResolutionStatus.NOT_FOUND, "substring-only misleading labels must not be actionable")

    val direct = resolver.resolve(
        InviteUiState.DIRECT,
        listOf(JoinTargetCandidate("Join group", evidence("direct")))
    )
    checkThat(direct.target?.kind == JoinTargetKind.DIRECT_JOIN, "direct invite should resolve direct join")

    val community = resolver.resolve(
        InviteUiState.COMMUNITY,
        listOf(
            JoinTargetCandidate("View community", evidence("view")),
            JoinTargetCandidate("Join community", evidence("join"))
        )
    )
    checkThat(community.target?.kind == JoinTargetKind.COMMUNITY_JOIN, "community join must be preferred over view community")

    val communityView = resolver.resolve(
        InviteUiState.COMMUNITY,
        listOf(JoinTargetCandidate("View community", evidence("view-only")))
    )
    checkThat(communityView.target?.kind == JoinTargetKind.COMMUNITY_VIEW, "view-community is a navigation stage, not final join success")

    val verifier = JoinPostconditionVerifier()
    checkThat(
        verifier.verify(InviteUiState.APPROVAL, InviteUiState.REQUEST_SENT, composerVisible = false).status == JoinVerificationStatus.VERIFIED,
        "approval must verify REQUEST_SENT"
    )
    checkThat(
        verifier.verify(InviteUiState.APPROVAL, InviteUiState.APPROVAL, composerVisible = false, dispatchAccepted = true).status == JoinVerificationStatus.UNVERIFIED,
        "accepted click with unchanged approval state must not count as success"
    )
    checkThat(
        verifier.verify(InviteUiState.DIRECT, InviteUiState.DIRECT, composerVisible = false, dispatchAccepted = true).status == JoinVerificationStatus.UNVERIFIED,
        "accepted direct click without postcondition must remain unverified"
    )
    checkThat(
        verifier.verify(InviteUiState.DIRECT, InviteUiState.UNKNOWN, composerVisible = true).status == JoinVerificationStatus.VERIFIED,
        "composer after direct join proves membership"
    )
    checkThat(
        verifier.verify(InviteUiState.APPROVAL, InviteUiState.INVALID, composerVisible = false).status == JoinVerificationStatus.INVALID,
        "invalid post-state must be terminal invalid"
    )

    val guard = JoinTargetIdentityGuard(maxEvidenceAgeMs = 2_000)
    checkThat(guard.accept(approvalEvidence, approvalEvidence.copy(capturedAt = 1_900L), now = 2_000L), "same target identity should be accepted")
    checkThat(!guard.accept(approvalEvidence, approvalEvidence.copy(signature = "other", capturedAt = 1_900L), now = 2_000L), "changed signature must be rejected")
    checkThat(!guard.accept(approvalEvidence, approvalEvidence.copy(capturedAt = 4_100L), now = 4_100L), "stale original evidence must be rejected")

    println("R7 M8 VERIFIED JOIN TESTS PASS")
}
