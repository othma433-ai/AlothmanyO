package com.alothmany.wa.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDomainTest {
    @Test
    fun normalizesAndDeduplicatesTrackingNoise() {
        assertEquals(
            "https://example.com/b?q=1",
            UrlNormalizer.normalize("HTTPS://Example.COM:443/a/../b/?utm_source=x&q=1#frag")
        )
        assertEquals(
            listOf("https://a.example/x", "https://b.example/q"),
            UrlNormalizer.extract("x https://a.example/x?utm_medium=z y https://b.example/q.")
        )
    }

    @Test
    fun oldestBoundaryRequiresRepeatedStableQuietWindows() {
        val detector = BoundaryDetector(3)
        assertFalse(detector.observe("A", 4))
        assertFalse(detector.observe("B", 0))
        assertFalse(detector.observe("B", 0))
        assertTrue(detector.observe("B", 0))
    }

    @Test
    fun pauseResumeAlwaysReverifiesChat() {
        val reducer = StateReducer()
        var state = JobState.initial("job")
        state = reducer.reduce(state, JobSignal.Start)
        state = reducer.reduce(state, JobSignal.ChatVerified)
        state = reducer.reduce(state, JobSignal.BatchCommitted(3))
        assertEquals(ExtractionPhase.SCROLL_OLDER, state.phase)
        state = reducer.reduce(state, JobSignal.Pause)
        assertEquals(ExtractionPhase.PAUSED, state.phase)
        state = reducer.reduce(state, JobSignal.Resume)
        assertEquals(ExtractionPhase.VERIFY_CHAT, state.phase)
    }
    @Test
    fun classifiesInviteStatesAndParsesExplicitTargets() {
        assertEquals(InviteUiState.APPROVAL, InviteStateClassifier.classify(listOf("Admins must approve your request"), listOf("Request to join")))
        assertEquals(InviteUiState.DIRECT, InviteStateClassifier.classify(listOf("Group invite"), listOf("Join group")))
        assertEquals(InviteUiState.INVALID, InviteStateClassifier.classify(listOf("This group invite link has been reset"), emptyList()))
        assertEquals(listOf("Group A", "Group B"), ActionTargetParser.lines("Group A\nGroup A\nGroup B"))
    }

    @Test
    fun ambiguousSendBecomesVerificationPendingAndNeverAutoResends() {
        assertEquals(
            SendVerification.VERIFIED,
            SendVerificationPolicy.evaluate(true, 0, 1, true)
        )
        assertEquals(
            SendVerification.VERIFY_PENDING,
            SendVerificationPolicy.evaluate(true, 1, 1, true)
        )
        assertEquals(PublishRecoveryAction.VERIFY_ONLY, ActionRecoveryPolicy.forPublish("VERIFY_PENDING"))
        assertEquals(PublishRecoveryAction.MAY_SEND, ActionRecoveryPolicy.forPublish("PREPARE"))
    }

}
