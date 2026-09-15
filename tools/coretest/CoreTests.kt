package coretest

import com.alothmany.wa.domain.BoundaryDetector
import com.alothmany.wa.domain.ExtractionPhase
import com.alothmany.wa.domain.JobSignal
import com.alothmany.wa.domain.JobState
import com.alothmany.wa.domain.StateReducer
import com.alothmany.wa.domain.UrlNormalizer

private fun assertThat(condition: Boolean, message: String) {
    if (!condition) error(message)
}

fun main() {
    val normalized = UrlNormalizer.normalize("HTTPS://Example.COM:443/a/../b/?utm_source=x&q=1#frag")
    assertThat(normalized == "https://example.com/b?q=1", "URL normalization failed: $normalized")

    val urls = UrlNormalizer.extract("x https://a.example/x?utm_medium=z y https://b.example/q.")
    assertThat(urls == listOf("https://a.example/x", "https://b.example/q"), "URL extraction failed: $urls")

    val detector = BoundaryDetector(requiredStableWindows = 3)
    assertThat(!detector.observe("A", newItems = 4), "New items must reset oldest evidence")
    assertThat(!detector.observe("B", newItems = 0), "First quiet window cannot complete")
    assertThat(!detector.observe("B", newItems = 0), "Second quiet window cannot complete")
    assertThat(detector.observe("B", newItems = 0), "Third stable quiet window should prove oldest boundary")

    val reducer = StateReducer()
    var state = JobState.initial("job-1")
    state = reducer.reduce(state, JobSignal.Start)
    assertThat(state.phase == ExtractionPhase.VERIFY_CHAT, "Start must enter VERIFY_CHAT")
    state = reducer.reduce(state, JobSignal.ChatVerified)
    assertThat(state.phase == ExtractionPhase.CAPTURE, "Verified chat must enter CAPTURE")
    state = reducer.reduce(state, JobSignal.BatchCommitted(newLinks = 3))
    assertThat(state.phase == ExtractionPhase.SCROLL_OLDER && state.linksFound == 3L, "Commit transition failed")
    state = reducer.reduce(state, JobSignal.Pause)
    assertThat(state.phase == ExtractionPhase.PAUSED, "Pause failed")
    state = reducer.reduce(state, JobSignal.Resume)
    assertThat(state.phase == ExtractionPhase.VERIFY_CHAT, "Resume must re-verify chat")

    testInviteClassificationAndActionTargetParsing()
    testSendVerificationAndRecoveryPolicy()

    println("CORE TESTS PASS")
}

// v2.1 regression tests
private fun testInviteClassificationAndActionTargetParsing() {
    val approval = com.alothmany.wa.domain.InviteStateClassifier.classify(
        visibleTexts = listOf("Group invite", "Admins must approve your request"),
        clickableLabels = listOf("Request to join")
    )
    assertThat(approval.name == "APPROVAL", "Approval invite classification failed: $approval")

    val direct = com.alothmany.wa.domain.InviteStateClassifier.classify(
        visibleTexts = listOf("Group invite"),
        clickableLabels = listOf("Join group")
    )
    assertThat(direct.name == "DIRECT", "Direct invite classification failed: $direct")

    val invalid = com.alothmany.wa.domain.InviteStateClassifier.classify(
        visibleTexts = listOf("This group invite link has been reset"),
        clickableLabels = emptyList()
    )
    assertThat(invalid.name == "INVALID", "Invalid invite classification failed: $invalid")

    val parsed = com.alothmany.wa.domain.ActionTargetParser.lines("  Group A\nGroup A\n\n Group B \n")
    assertThat(parsed == listOf("Group A", "Group B"), "Explicit target parsing failed: $parsed")

    val invites = com.alothmany.wa.domain.ActionTargetParser.invites(
        "https://chat.whatsapp.com/AAA\nhttps://example.com/nope\nhttps://chat.whatsapp.com/AAA\nhttps://chat.whatsapp.com/BBB"
    )
    assertThat(invites == listOf("https://chat.whatsapp.com/AAA", "https://chat.whatsapp.com/BBB"), "Invite parsing failed: $invites")
}


private fun testSendVerificationAndRecoveryPolicy() {
    val verified = com.alothmany.wa.domain.SendVerificationPolicy.evaluate(
        composerCleared = true,
        beforeExactCount = 0,
        afterExactCount = 1,
        uiAcknowledged = true
    )
    assertThat(verified.name == "VERIFIED", "New exact message should verify send: $verified")

    val ambiguous = com.alothmany.wa.domain.SendVerificationPolicy.evaluate(
        composerCleared = true,
        beforeExactCount = 1,
        afterExactCount = 1,
        uiAcknowledged = true
    )
    assertThat(ambiguous.name == "VERIFY_PENDING", "Ambiguous send must not be treated as success: $ambiguous")

    val clearedWithoutEvent = com.alothmany.wa.domain.SendVerificationPolicy.evaluate(
        composerCleared = true,
        beforeExactCount = 0,
        afterExactCount = 0,
        uiAcknowledged = false
    )
    assertThat(clearedWithoutEvent.name == "VERIFY_PENDING", "Cleared composer must fence against duplicate resend: $clearedWithoutEvent")

    val recovery = com.alothmany.wa.domain.ActionRecoveryPolicy.forPublish("VERIFY_PENDING")
    assertThat(recovery.name == "VERIFY_ONLY", "VERIFY_PENDING recovery must never auto-resend: $recovery")
    val fenced = com.alothmany.wa.domain.ActionRecoveryPolicy.forPublish("SEND_FENCE_ARMED")
    assertThat(fenced.name == "VERIFY_ONLY", "Send fence recovery must never auto-resend: $fenced")
    val retry = com.alothmany.wa.domain.ActionRecoveryPolicy.forPublish("PREPARE")
    assertThat(retry.name == "MAY_SEND", "PREPARE may send after normal validation: $retry")
}
