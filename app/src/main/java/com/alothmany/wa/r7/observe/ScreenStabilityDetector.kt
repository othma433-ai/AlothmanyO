package com.alothmany.wa.r7.observe

class ScreenStabilityDetector(
    private val normalRequired: Int = 2,
    private val highRequired: Int = 3,
    private val timeoutMs: Long = 5_000
) {
    private var last: ScreenObservation? = null
    private var count = 0
    private var sequenceStartedAt = 0L

    fun reset() {
        last = null
        count = 0
        sequenceStartedAt = 0L
    }

    fun accept(observation: ScreenObservation, riskLevel: RiskLevel, now: Long = observation.observedAt): StabilityResult {
        val required = if (riskLevel == RiskLevel.HIGH) highRequired else normalRequired
        val previous = last
        if (previous == null) {
            last = observation
            count = 1
            sequenceStartedAt = now
            return result(StabilityStatus.COLLECTING, required, "first-observation")
        }
        if (now - sequenceStartedAt > timeoutMs) {
            last = observation
            count = 1
            sequenceStartedAt = now
            return result(StabilityStatus.UNSTABLE_TIMEOUT, required, "deadline-exceeded")
        }
        val compatible = previous.type == observation.type &&
            previous.packageName == observation.packageName &&
            previous.windowId == observation.windowId
        if (!compatible) {
            last = observation
            count = 1
            sequenceStartedAt = now
            return result(StabilityStatus.RESET, required, "screen-package-window-changed")
        }
        count++
        last = observation
        return if (count >= required) result(StabilityStatus.STABLE, required, "compatible-observations")
        else result(StabilityStatus.COLLECTING, required, "need-more-observations")
    }

    private fun result(status: StabilityStatus, required: Int, reason: String) =
        StabilityResult(status, count, required, reason)
}
