package com.alothmany.wa.r7.automation

data class WatchdogConfig(
    val eventSilenceMs: Long = 15_000,
    val staleWindowMs: Long = 10_000,
    val gestureTimeoutMs: Long = 5_000,
    val sameStateLoopLimit: Int = 8
) {
    init {
        require(eventSilenceMs > 0)
        require(staleWindowMs > 0)
        require(gestureTimeoutMs > 0)
        require(sameStateLoopLimit >= 2)
    }
}

enum class WatchdogReason {
    EVENT_SILENCE,
    STUCK_STATE_LOOP,
    STALE_WINDOW,
    GESTURE_TIMEOUT,
    SERVICE_RECREATED,
    PACKAGE_CHANGED
}

data class WatchdogPulse(
    val now: Long,
    val lastAccessibilityEventAt: Long,
    val lastProgressAt: Long,
    val state: String,
    val observationSignature: String,
    val windowUpdatedAt: Long,
    val gestureStartedAt: Long?,
    val expectedPackage: String,
    val observedPackage: String?,
    val expectedServiceGeneration: Long,
    val observedServiceGeneration: Long
)

data class WatchdogResult(
    val healthy: Boolean,
    val reasons: Set<WatchdogReason>,
    val sameStateCount: Int
)

class Watchdog(private val config: WatchdogConfig = WatchdogConfig()) {
    private var lastLoopKey: String? = null
    private var sameStateCount = 0

    @Synchronized
    fun observe(pulse: WatchdogPulse): WatchdogResult {
        val reasons = linkedSetOf<WatchdogReason>()
        if (pulse.lastAccessibilityEventAt > 0 && pulse.now - pulse.lastAccessibilityEventAt >= config.eventSilenceMs) {
            reasons += WatchdogReason.EVENT_SILENCE
        }

        val loopKey = "${pulse.state}|${pulse.observationSignature}"
        if (loopKey == lastLoopKey) sameStateCount++ else {
            lastLoopKey = loopKey
            sameStateCount = 1
        }
        if (sameStateCount >= config.sameStateLoopLimit && pulse.now >= pulse.lastProgressAt) {
            reasons += WatchdogReason.STUCK_STATE_LOOP
        }

        if (pulse.windowUpdatedAt > 0 && pulse.now - pulse.windowUpdatedAt >= config.staleWindowMs) {
            reasons += WatchdogReason.STALE_WINDOW
        }
        pulse.gestureStartedAt?.let {
            if (pulse.now - it >= config.gestureTimeoutMs) reasons += WatchdogReason.GESTURE_TIMEOUT
        }
        if (pulse.expectedServiceGeneration != pulse.observedServiceGeneration) {
            reasons += WatchdogReason.SERVICE_RECREATED
        }
        if (!pulse.observedPackage.isNullOrBlank() && pulse.observedPackage != pulse.expectedPackage) {
            reasons += WatchdogReason.PACKAGE_CHANGED
        }
        return WatchdogResult(reasons.isEmpty(), reasons, sameStateCount)
    }

    @Synchronized
    fun verifiedProgress() {
        lastLoopKey = null
        sameStateCount = 0
    }

    @Synchronized
    fun reset() {
        lastLoopKey = null
        sameStateCount = 0
    }
}
