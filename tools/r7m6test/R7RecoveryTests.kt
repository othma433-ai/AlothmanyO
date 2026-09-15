import com.alothmany.wa.r7.accessibility.AccessibilityEventBuffer
import com.alothmany.wa.r7.automation.RunLifecycle
import com.alothmany.wa.r7.automation.RunLifecycleState
import com.alothmany.wa.r7.automation.DurableRunState
import com.alothmany.wa.r7.automation.LifecycleReconstructionPlanner
import com.alothmany.wa.r7.automation.ReconstructionAction
import com.alothmany.wa.r7.automation.Watchdog
import com.alothmany.wa.r7.automation.WatchdogConfig
import com.alothmany.wa.r7.automation.WatchdogPulse
import com.alothmany.wa.r7.automation.WatchdogReason
import com.alothmany.wa.r7.navigation.RecoveryAction
import com.alothmany.wa.r7.navigation.RecoveryController
import com.alothmany.wa.r7.navigation.RecoverySignal

private fun check(condition: Boolean, message: String) {
    if (!condition) error(message)
}

fun main() {
    recoveryEscalatesThroughAllTiersBeforeQuarantine()
    verifiedProgressResetsRecoveryEscalation()
    quarantinedItemAllowsNextQueueItem()
    watchdogDetectsAllRequiredFailureClasses()
    accessibilityEventBufferIsBoundedAndTracksLatestEvents()
    stopAdvancesGenerationAndRejectsStaleCallbacks()
    pauseBecomesDurableOnlyAtAtomicBoundary()
    lifecycleReconstructionRequiresReverification()
    println("R7 M6 RECOVERY TESTS PASS")
}

private fun recoveryEscalatesThroughAllTiersBeforeQuarantine() {
    val controller = RecoveryController()
    val signal = RecoverySignal("run-1", "group-a", "EXTRACTING", "NO_PROGRESS", "consumer")
    val expected = listOf(
        RecoveryAction.REOBSERVE,
        RecoveryAction.RECLASSIFY,
        RecoveryAction.RETRY_ACTION,
        RecoveryAction.BACK_TO_KNOWN_SCREEN,
        RecoveryAction.RELAUNCH_PACKAGE,
        RecoveryAction.RESTORE_DURABLE_ITEM,
        RecoveryAction.FAIL_ITEM_CONTINUE
    )
    val actual = expected.indices.map { controller.onFailure(signal).action }
    check(actual == expected, "recovery escalation mismatch: $actual")
}

private fun verifiedProgressResetsRecoveryEscalation() {
    val controller = RecoveryController()
    val signal = RecoverySignal("run-2", "group-a", "OPENING", "WINDOW_DRIFT", "business")
    check(controller.onFailure(signal).action == RecoveryAction.REOBSERVE, "first failure should reobserve")
    check(controller.onFailure(signal).action == RecoveryAction.RECLASSIFY, "second failure should reclassify")
    controller.onVerifiedProgress(signal)
    check(controller.onFailure(signal).action == RecoveryAction.REOBSERVE, "verified progress must reset item escalation")
}

private fun quarantinedItemAllowsNextQueueItem() {
    val controller = RecoveryController()
    val bad = RecoverySignal("run-3", "bad-group", "EXTRACTING", "STALE_WINDOW", "consumer")
    repeat(6) { controller.onFailure(bad) }
    val terminal = controller.onFailure(bad)
    check(terminal.action == RecoveryAction.FAIL_ITEM_CONTINUE, "seventh failure should quarantine current item")
    check(terminal.continueQueue, "quarantine must explicitly allow next queue item")

    val healthy = RecoverySignal("run-3", "healthy-group", "OPENING", "TRANSIENT", "consumer")
    check(controller.onFailure(healthy).action == RecoveryAction.REOBSERVE, "next healthy item must start with fresh recovery budget")
}

private fun watchdogDetectsAllRequiredFailureClasses() {
    val config = WatchdogConfig(
        eventSilenceMs = 10_000,
        staleWindowMs = 8_000,
        gestureTimeoutMs = 4_000,
        sameStateLoopLimit = 3
    )
    val watchdog = Watchdog(config)
    val now = 100_000L

    var result = watchdog.observe(WatchdogPulse(now, 80_000, 99_000, "EXTRACTING", "sig-a", 99_500, null, "com.whatsapp", "com.whatsapp", 1, 1))
    check(WatchdogReason.EVENT_SILENCE in result.reasons, "event silence not detected")

    watchdog.reset()
    repeat(2) {
        result = watchdog.observe(WatchdogPulse(now + it, now, now, "EXTRACTING", "same", now, null, "com.whatsapp", "com.whatsapp", 1, 1))
    }
    result = watchdog.observe(WatchdogPulse(now + 3, now, now, "EXTRACTING", "same", now, null, "com.whatsapp", "com.whatsapp", 1, 1))
    check(WatchdogReason.STUCK_STATE_LOOP in result.reasons, "same-state loop not detected")

    watchdog.reset()
    result = watchdog.observe(WatchdogPulse(now, now, now, "OPENING", "sig", 80_000, null, "com.whatsapp", "com.whatsapp", 1, 1))
    check(WatchdogReason.STALE_WINDOW in result.reasons, "stale window not detected")

    watchdog.reset()
    result = watchdog.observe(WatchdogPulse(now, now, now, "EXTRACTING", "sig", now, 90_000, "com.whatsapp", "com.whatsapp", 1, 1))
    check(WatchdogReason.GESTURE_TIMEOUT in result.reasons, "gesture timeout not detected")

    watchdog.reset()
    result = watchdog.observe(WatchdogPulse(now, now, now, "EXTRACTING", "sig", now, null, "com.whatsapp", "com.whatsapp", 1, 2))
    check(WatchdogReason.SERVICE_RECREATED in result.reasons, "service recreation not detected")

    watchdog.reset()
    result = watchdog.observe(WatchdogPulse(now, now, now, "EXTRACTING", "sig", now, null, "com.whatsapp", "com.whatsapp.w4b", 1, 1))
    check(WatchdogReason.PACKAGE_CHANGED in result.reasons, "unexpected package change not detected")
}

private fun accessibilityEventBufferIsBoundedAndTracksLatestEvents() {
    val buffer = AccessibilityEventBuffer(capacity = 3)
    buffer.record(10, 1, "com.whatsapp", 1)
    buffer.record(20, 2, "com.whatsapp", 1)
    buffer.record(30, 4096, "com.whatsapp", 1)
    buffer.record(40, 3, "com.whatsapp", 2)
    check(buffer.snapshot().map { it.timestamp } == listOf(20L, 30L, 40L), "event buffer must evict oldest record")
    check(buffer.lastEventAt() == 40L, "last event timestamp mismatch")
    check(buffer.lastScrollAt() == 30L, "last scroll timestamp mismatch")
}

private fun stopAdvancesGenerationAndRejectsStaleCallbacks() {
    val lifecycle = RunLifecycle(initialGeneration = 7)
    val generation = lifecycle.currentGeneration()
    check(lifecycle.acceptsCallback(generation), "current generation should be accepted")
    lifecycle.stop()
    check(lifecycle.currentGeneration() == 8L, "stop must advance generation")
    check(!lifecycle.acceptsCallback(generation), "callback from stopped generation must be rejected")
    check(lifecycle.state() == RunLifecycleState.STOPPED, "stop state mismatch")
}

private fun pauseBecomesDurableOnlyAtAtomicBoundary() {
    val lifecycle = RunLifecycle()
    lifecycle.requestPause()
    check(lifecycle.state() == RunLifecycleState.PAUSE_REQUESTED, "pause should first be a request")
    check(!lifecycle.isDurablyPaused(), "pause must not be durable before atomic boundary")
    val directive = lifecycle.onAtomicBoundary()
    check(directive.persistPause, "atomic boundary should request durable pause persistence")
    check(lifecycle.state() == RunLifecycleState.PAUSED, "lifecycle should become paused at atomic boundary")
    lifecycle.resume()
    check(lifecycle.state() == RunLifecycleState.RUNNING, "resume should return to running")
}

private fun lifecycleReconstructionRequiresReverification() {
    check(
        LifecycleReconstructionPlanner.plan(DurableRunState("PAUSED", "PAUSED", "PAUSED")) == ReconstructionAction.REVERIFY_AND_RESUME,
        "paused durable item must reverify before resume"
    )
    check(
        LifecycleReconstructionPlanner.plan(DurableRunState("RECOVERING", "EXTRACTING", "PARTIAL")) == ReconstructionAction.REVERIFY_AND_RESUME,
        "recovering partial item must reverify before resume"
    )
    check(
        LifecycleReconstructionPlanner.plan(DurableRunState("RUNNING", "COMPLETED", "COMPLETE")) == ReconstructionAction.SKIP_COMPLETED,
        "completed checkpoint must be skipped"
    )
    check(
        LifecycleReconstructionPlanner.plan(DurableRunState("STOPPED", "PAUSED", "PAUSED")) == ReconstructionAction.DO_NOT_RESUME,
        "stopped job must never reconstruct automatically"
    )
}
