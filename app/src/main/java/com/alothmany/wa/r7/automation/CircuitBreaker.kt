package com.alothmany.wa.r7.automation

data class BreakerKey(
    val operationId: String,
    val itemId: String?,
    val state: String,
    val category: String,
    val adapterProfile: String
)

enum class CircuitDecision { RETRY, ALTERNATE_STRATEGY, QUARANTINE_ITEM, DEGRADE_RUN }

class CircuitBreaker(
    private val alternateAt: Int = 3,
    private val quarantineAt: Int = 5,
    private val degradeRunAt: Int = 8
) {
    private val failures = HashMap<BreakerKey, Int>()
    private var runWideFailures = 0

    fun recordFailure(key: BreakerKey): CircuitDecision {
        val count = (failures[key] ?: 0) + 1
        failures[key] = count
        runWideFailures++
        if (runWideFailures >= degradeRunAt) return CircuitDecision.DEGRADE_RUN
        return when {
            count == alternateAt -> CircuitDecision.ALTERNATE_STRATEGY
            count >= quarantineAt -> CircuitDecision.QUARANTINE_ITEM
            else -> CircuitDecision.RETRY
        }
    }

    fun recordVerifiedProgress(key: BreakerKey) {
        failures.remove(key)
        if (runWideFailures > 0) runWideFailures--
    }

    fun resetRun() {
        failures.clear()
        runWideFailures = 0
    }
}
