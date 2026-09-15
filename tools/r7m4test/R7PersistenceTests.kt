import com.alothmany.wa.r7.persistence.ExtractionQueueStatus
import com.alothmany.wa.r7.persistence.ImmutableQueuePlanner
import com.alothmany.wa.r7.persistence.QueueGroupSnapshot
import com.alothmany.wa.r7.extraction.DeepExtractionStrategy
import com.alothmany.wa.r7.extraction.MessageObservation

private fun checkTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun checkEq(expected: Any?, actual: Any?, message: String) { if (expected != actual) error("$message expected=$expected actual=$actual") }

fun testQueuePlannerFreezesMembershipOrderAndIdentity() {
    val selected = mutableListOf(
        QueueGroupSnapshot("g-1", "com.whatsapp", " Alpha ", "sig-a", "GROUPS_FILTER", "boundary-a"),
        QueueGroupSnapshot("g-2", "com.whatsapp", "Beta", "sig-b", "CHAT_GRABBER", null),
        QueueGroupSnapshot("g-1", "com.whatsapp", "Alpha duplicate", "changed", "CHAT_GRABBER", "changed")
    )
    val queue = ImmutableQueuePlanner().freeze("job-1", "DEEP", selected)
    checkEq(2, queue.size, "duplicate stable group key must not change membership")
    checkEq(listOf(0, 1), queue.map { it.ordinal }, "queue order must be deterministic and immutable")
    checkEq(listOf("g-1", "g-2"), queue.map { it.groupId }, "first selected identity wins")
    checkEq("Alpha", queue[0].expectedTitle, "title snapshot must be normalized only for whitespace")
    checkEq("sig-a", queue[0].expectedIdentitySignature, "identity evidence must be frozen at run start")
    checkEq(ExtractionQueueStatus.PENDING, queue[0].status, "new items start pending")

    selected.clear()
    selected += QueueGroupSnapshot("g-9", "com.whatsapp", "Later Sync", null, "GROUPS_FILTER", null)
    checkEq(listOf("g-1", "g-2"), queue.map { it.groupId }, "later sync changes cannot mutate active queue membership")
}

fun testDeepExtractionProducesStableIdempotencyKeys() {
    val strategy = DeepExtractionStrategy()
    val messages = listOf(
        MessageObservation("m-1", "Visit https://Example.com/path/?utm_source=x and https://example.com/path"),
        MessageObservation("m-2", "Again https://example.com/path plus https://example.org/a")
    )
    val first = strategy.capture("group:g-1", messages)
    val replay = strategy.capture("group:g-1", messages)

    checkEq(first.map { it.occurrenceKey }, replay.map { it.occurrenceKey }, "restart replay must produce identical occurrence keys")
    checkEq(3, first.size, "same normalized URL in same message must collapse, different messages remain distinct occurrences")
    checkEq(2, first.map { it.normalizedUrl }.toSet().size, "normalized URLs are canonicalized")
    checkTrue(first.all { it.contextualFingerprint.isNotBlank() }, "every occurrence needs contextual fingerprint")
    checkTrue(first.all { it.conversationKey == "group:g-1" }, "conversation identity participates in deduplication")
}

fun testDeepExtractionSeparatesSameMessageAcrossConversations() {
    val strategy = DeepExtractionStrategy()
    val message = listOf(MessageObservation("same-fingerprint", "https://example.com/x"))
    val a = strategy.capture("group:a", message).single()
    val b = strategy.capture("group:b", message).single()
    checkTrue(a.occurrenceKey != b.occurrenceKey, "same visible message in different conversations must not collide")
}

fun main() {
    testQueuePlannerFreezesMembershipOrderAndIdentity()
    testDeepExtractionProducesStableIdempotencyKeys()
    testDeepExtractionSeparatesSameMessageAcrossConversations()
    println("R7 M4 PERSISTENCE TESTS PASS")
}
