package com.alothmany.wa.r7.m5test

import com.alothmany.wa.r7.extraction.NewOnlyExtractionStrategy
import com.alothmany.wa.r7.extraction.NewOnlyMessageEvidence
import com.alothmany.wa.r7.extraction.NewOnlyStopReason

private fun assertTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun assertFalse(value: Boolean, message: String) = assertTrue(!value, message)
private fun assertEquals(expected: Any?, actual: Any?, message: String) { if (expected != actual) error("$message expected=$expected actual=$actual") }

fun main() {
    val strategy = NewOnlyExtractionStrategy(noProgressThreshold = 3)

    val originalEvidence = strategy.observe(
        windowFingerprint = "vp-newest",
        messages = listOf(
            NewOnlyMessageEvidence("m1", "09:41 https://example.com/a"),
            NewOnlyMessageEvidence("m2", "09:42 https://example.com/b"),
            NewOnlyMessageEvidence("m3", "09:43 no link")
        ),
        visibleTexts = listOf("Today", "09:41", "09:42"),
        terminalNoProgressCount = 0
    )
    val checkpoint = strategy.buildCheckpoint(originalEvidence)
    val encoded = strategy.encode(checkpoint)
    val decoded = strategy.decode(encoded) ?: error("v2 checkpoint did not decode")
    assertEquals(checkpoint, decoded, "checkpoint round-trip must be deterministic")
    assertFalse(encoded.contains("09:41"), "serialized checkpoint must not expose raw timestamp context")
    assertFalse(encoded.contains("example.com"), "serialized checkpoint must not expose raw URLs")

    val matchingEvidence = strategy.observe(
        windowFingerprint = "different-vp",
        messages = listOf(
            NewOnlyMessageEvidence("m0", "09:40 older"),
            NewOnlyMessageEvidence("m1", "09:41 https://example.com/a"),
            NewOnlyMessageEvidence("m2", "09:42 https://example.com/b")
        ),
        visibleTexts = listOf("Today", "09:41", "09:42"),
        terminalNoProgressCount = 0
    )
    val strong = strategy.evaluate(matchingEvidence, checkpoint)
    assertTrue(strong.shouldStop, "two anchors plus contextual evidence must stop New-only")
    assertEquals(NewOnlyStopReason.PRIOR_CHECKPOINT_MATCH, strong.reason, "strong checkpoint match reason")


    val sparseEvidence = strategy.observe(
        windowFingerprint = "vp-sparse",
        messages = listOf(NewOnlyMessageEvidence("s1", "plain message")),
        visibleTexts = emptyList(),
        terminalNoProgressCount = 0
    )
    val sparseCheckpoint = strategy.buildCheckpoint(sparseEvidence)
    val sparseRoundTrip = strategy.decode(strategy.encode(sparseCheckpoint))
    assertEquals(sparseCheckpoint, sparseRoundTrip, "checkpoint with empty optional evidence must round-trip")

    val history = listOf(
        matchingEvidence,
        strategy.observe("older-1", listOf(NewOnlyMessageEvidence("z1", "old")), emptyList(), 0),
        strategy.observe("older-2", listOf(NewOnlyMessageEvidence("z2", "older")), emptyList(), 0)
    )
    val stopIndex = history.indexOfFirst { strategy.evaluate(it, checkpoint).shouldStop }
    assertEquals(0, stopIndex, "resume must stop at the prior high-water boundary instead of replaying full history")

    val weakEvidence = strategy.observe(
        windowFingerprint = "unrelated-vp",
        messages = listOf(NewOnlyMessageEvidence("m1", "completely unrelated text")),
        visibleTexts = emptyList(),
        terminalNoProgressCount = 0
    )
    val weak = strategy.evaluate(weakEvidence, checkpoint)
    assertFalse(weak.shouldStop, "one anchor without supporting evidence must not stop")

    val unread = strategy.observe(
        windowFingerprint = "vp-unread",
        messages = listOf(NewOnlyMessageEvidence("x1", "new message")),
        visibleTexts = listOf("2 unread messages"),
        terminalNoProgressCount = 0
    )
    val unreadDecision = strategy.evaluate(unread, checkpoint)
    assertTrue(unreadDecision.shouldStop, "explicit unread boundary must stop")
    assertEquals(NewOnlyStopReason.UNREAD_BOUNDARY, unreadDecision.reason, "unread boundary reason")

    val terminal = strategy.observe(
        windowFingerprint = "vp-oldest",
        messages = listOf(NewOnlyMessageEvidence("old1", "old message")),
        visibleTexts = emptyList(),
        terminalNoProgressCount = 3
    )
    val terminalDecision = strategy.evaluate(terminal, checkpoint)
    assertTrue(terminalDecision.shouldStop, "bounded historical no-progress must stop")
    assertEquals(NewOnlyStopReason.HISTORICAL_NO_PROGRESS, terminalDecision.reason, "terminal reason")

    val legacy = strategy.decode("m1;m2;m3") ?: error("legacy boundary should decode")
    val legacyDecision = strategy.evaluate(matchingEvidence, legacy)
    assertTrue(legacyDecision.shouldStop, "legacy two-anchor checkpoint must remain compatible")
    assertEquals(NewOnlyStopReason.PRIOR_CHECKPOINT_MATCH, legacyDecision.reason, "legacy match reason")

    println("R7 M5 NEW-ONLY TESTS PASS")
}
