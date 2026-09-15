import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.snapshot.SnapshotSignature
import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import com.alothmany.wa.r7.accessibility.LiveNodeResolutionPolicy
import com.alothmany.wa.r7.accessibility.ResolutionStatus
import com.alothmany.wa.r7.observe.RiskLevel
import com.alothmany.wa.r7.observe.ScreenObservation
import com.alothmany.wa.r7.observe.ScreenStabilityDetector
import com.alothmany.wa.r7.observe.ScreenType
import com.alothmany.wa.r7.observe.StabilityStatus
import com.alothmany.wa.r7.adapter.BusinessWhatsAppAdapter
import com.alothmany.wa.r7.adapter.ConsumerWhatsAppAdapter
import com.alothmany.wa.r7.adapter.GenericWhatsAppAdapter
import com.alothmany.wa.r7.automation.ActionContext
import com.alothmany.wa.r7.automation.ActionExecutor
import com.alothmany.wa.r7.automation.BreakerKey
import com.alothmany.wa.r7.automation.CircuitBreaker
import com.alothmany.wa.r7.automation.CircuitDecision
import com.alothmany.wa.r7.automation.VerificationResult
import com.alothmany.wa.r7.diagnostics.MetricRecorder
import com.alothmany.wa.r7.diagnostics.RuntimeMetricSample
import com.alothmany.wa.r7.diagnostics.TraceEvent
import com.alothmany.wa.r7.diagnostics.TraceRecorder

private fun checkTrue(value: Boolean, message: String) { if (!value) error(message) }
private fun checkEq(expected: Any?, actual: Any?, message: String) { if (expected != actual) error("$message expected=$expected actual=$actual") }

private fun node(
    textHash: String = "t",
    packageName: String = "com.whatsapp",
    windowId: Int = 7,
    viewId: String = "row"
) = NodeSnapshot(
    viewId = viewId,
    className = "android.view.View",
    textHash = textHash,
    contentDescriptionHash = "",
    left = 0, top = 10, right = 100, bottom = 60,
    clickable = true, scrollable = false, selected = false,
    enabled = true, visibleToUser = true, depth = 2,
    parentSignature = "parent", childCount = 0,
    packageName = packageName, windowId = windowId, capturedAt = 1000L
)

fun testSnapshotSignatureDeterministic() {
    val a = node(textHash = "hash-A")
    val b = node(textHash = "hash-A")
    val c = node(textHash = "hash-B")
    checkEq(SnapshotSignature.compute(a), SnapshotSignature.compute(b), "same structure must sign identically")
    checkTrue(SnapshotSignature.compute(a) != SnapshotSignature.compute(c), "text hash must affect signature")
}

fun testLiveNodeResolutionPolicyRejectsWrongWindowAndStaleEvidence() {
    val candidate = node(textHash = "same", packageName = "com.whatsapp", windowId = 7)
    val evidence = ActionTargetEvidence.from(candidate)
    checkEq(ResolutionStatus.MATCH, LiveNodeResolutionPolicy.resolve(evidence, listOf(candidate), now = 1200, maxAgeMs = 1000).status, "matching target")
    checkEq(ResolutionStatus.WINDOW_MISMATCH, LiveNodeResolutionPolicy.resolve(evidence, listOf(candidate.copy(windowId = 8)), now = 1200, maxAgeMs = 1000).status, "wrong window")
    checkEq(ResolutionStatus.NOT_FOUND, LiveNodeResolutionPolicy.resolve(evidence, listOf(candidate.copy(textHash = "different")), now = 1200, maxAgeMs = 1000).status, "signature mismatch")
    checkEq(ResolutionStatus.STALE_EVIDENCE, LiveNodeResolutionPolicy.resolve(evidence, listOf(candidate), now = 5000, maxAgeMs = 1000).status, "stale target evidence")
    val duplicate = candidate.copy(left = 10, right = 110)
    val duplicateEvidence = evidence.copy(signature = SnapshotSignature.compute(duplicate), left = 10, right = 110)
    checkEq(ResolutionStatus.MATCH, LiveNodeResolutionPolicy.resolve(duplicateEvidence, listOf(candidate, duplicate), now = 1200, maxAgeMs = 1000).status, "bounds disambiguate exact candidate")
}

fun testStabilityNormalAndHighRisk() {
    val detector = ScreenStabilityDetector(normalRequired = 2, highRequired = 3, timeoutMs = 5_000)
    val o = ScreenObservation(ScreenType.CONVERSATION, .95, listOf("header", "message-list"), "com.whatsapp", 7, "vp1", 1000)
    checkEq(StabilityStatus.COLLECTING, detector.accept(o, RiskLevel.NORMAL, 1000).status, "first normal observation")
    checkEq(StabilityStatus.STABLE, detector.accept(o.copy(observedAt = 1100), RiskLevel.NORMAL, 1100).status, "second normal observation")
    detector.reset()
    checkEq(StabilityStatus.COLLECTING, detector.accept(o, RiskLevel.HIGH, 2000).status, "high observation 1")
    checkEq(StabilityStatus.COLLECTING, detector.accept(o.copy(observedAt = 2100), RiskLevel.HIGH, 2100).status, "high observation 2")
    checkEq(StabilityStatus.STABLE, detector.accept(o.copy(observedAt = 2200), RiskLevel.HIGH, 2200).status, "high observation 3")
}

fun testStabilityResetsOnContradiction() {
    val detector = ScreenStabilityDetector(normalRequired = 2, highRequired = 3, timeoutMs = 5_000)
    val chat = ScreenObservation(ScreenType.CONVERSATION, .9, emptyList(), "com.whatsapp", 1, "a", 1000)
    val list = ScreenObservation(ScreenType.CHAT_LIST, .9, emptyList(), "com.whatsapp", 1, "b", 1100)
    detector.accept(chat, RiskLevel.NORMAL, 1000)
    checkEq(StabilityStatus.RESET, detector.accept(list, RiskLevel.NORMAL, 1100).status, "contradiction must reset")
    checkEq(StabilityStatus.STABLE, detector.accept(list.copy(observedAt = 1200), RiskLevel.NORMAL, 1200).status, "second matching observation after reset")
}

fun testAdaptersRankExactPackages() {
    val consumer = ConsumerWhatsAppAdapter()
    val business = BusinessWhatsAppAdapter()
    val generic = GenericWhatsAppAdapter()
    checkTrue(consumer.matchPackage("com.whatsapp").exact, "consumer exact match")
    checkTrue(business.matchPackage("com.whatsapp.w4b").exact, "business exact match")
    checkTrue(!generic.matchPackage("com.whatsapp").exact, "generic must not be exact")
    checkTrue(consumer.matchPackage("com.whatsapp").confidence > generic.matchPackage("com.whatsapp").confidence, "exact adapter must outrank generic")
}

fun testGenerationGuardRejectsStaleCallbacks() {
    val executor = ActionExecutor()
    executor.startRun("run-1", 10)
    val current = ActionContext("run-1", "op", "item", 10, "action-1", 1, 1000)
    checkTrue(executor.register(current), "current action must register")
    checkTrue(executor.onVerification(current, VerificationResult.PASS), "current verified action may commit")

    val old = ActionContext("run-1", "op", "item", 9, "action-old", 1, 900)
    checkTrue(!executor.register(old), "old generation must not register")

    executor.startRun("run-2", 1)
    checkTrue(!executor.onVerification(current, VerificationResult.PASS), "old run callback must be rejected")
}

fun testCircuitBreakerThresholdsAndReset() {
    val breaker = CircuitBreaker(alternateAt = 3, quarantineAt = 5, degradeRunAt = 8)
    val key = BreakerKey("op", "item", "OPEN_GROUP", "NO_PROGRESS", "consumer-v1")
    checkEq(CircuitDecision.RETRY, breaker.recordFailure(key), "failure 1")
    checkEq(CircuitDecision.RETRY, breaker.recordFailure(key), "failure 2")
    checkEq(CircuitDecision.ALTERNATE_STRATEGY, breaker.recordFailure(key), "failure 3")
    checkEq(CircuitDecision.RETRY, breaker.recordFailure(key), "failure 4")
    checkEq(CircuitDecision.QUARANTINE_ITEM, breaker.recordFailure(key), "failure 5")
    breaker.recordVerifiedProgress(key)
    checkEq(CircuitDecision.RETRY, breaker.recordFailure(key), "verified progress must reset key")
}

fun testTraceAndMetricRetentionBounded() {
    val trace = TraceRecorder(capacity = 3)
    repeat(5) { i -> trace.record(TraceEvent("r", "op", "item", "S$i", "a$i", "b", "c", "PASS", null, "consumer", i.toLong())) }
    checkEq(3, trace.snapshot().size, "trace ring capacity")
    checkEq("S2", trace.snapshot().first().state, "old trace must evict first")

    val metrics = MetricRecorder(capacity = 2)
    repeat(3) { i -> metrics.sample(RuntimeMetricSample("r", i.toLong(), pssKb = i.toLong(), eventsPerMinute = i, snapshotsPerMinute = i, actionsPerMinute = i, verificationFailures = i, recoveries = i, fallbacks = i, dbBatches = i, staleCallbacks = i, circuitBreakerActivations = i)) }
    checkEq(2, metrics.snapshot().size, "metric ring capacity")
    checkEq(1L, metrics.snapshot().first().capturedAt, "old metric must evict first")
}

fun main() {
    testSnapshotSignatureDeterministic()
    testLiveNodeResolutionPolicyRejectsWrongWindowAndStaleEvidence()
    testStabilityNormalAndHighRisk()
    testStabilityResetsOnContradiction()
    testAdaptersRankExactPackages()
    testGenerationGuardRejectsStaleCallbacks()
    testCircuitBreakerThresholdsAndReset()
    testTraceAndMetricRetentionBounded()
    println("R7 M1 FOUNDATION TESTS PASS")
}
