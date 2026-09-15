package com.alothmany.wa.r7.navigation

import com.alothmany.wa.r7.automation.BreakerKey
import com.alothmany.wa.r7.automation.CircuitBreaker
import com.alothmany.wa.r7.automation.CircuitDecision

enum class RecoveryAction {
    REOBSERVE,
    RECLASSIFY,
    RETRY_ACTION,
    BACK_TO_KNOWN_SCREEN,
    RELAUNCH_PACKAGE,
    RESTORE_DURABLE_ITEM,
    FAIL_ITEM_CONTINUE,
    DEGRADE_RUN
}

data class RecoverySignal(
    val operationId: String,
    val itemId: String?,
    val state: String,
    val category: String,
    val adapterProfile: String
)

data class RecoveryDirective(
    val action: RecoveryAction,
    val attempt: Int,
    val circuitDecision: CircuitDecision,
    val continueQueue: Boolean = false,
    val terminalForItem: Boolean = false
)

class RecoveryController(
    private val circuitBreaker: CircuitBreaker = CircuitBreaker(
        alternateAt = 4,
        quarantineAt = 7,
        degradeRunAt = 20
    )
) {
    private val attempts = HashMap<BreakerKey, Int>()

    @Synchronized
    fun onFailure(signal: RecoverySignal): RecoveryDirective {
        val key = signal.toBreakerKey()
        val attempt = (attempts[key] ?: 0) + 1
        attempts[key] = attempt
        val decision = circuitBreaker.recordFailure(key)
        if (decision == CircuitDecision.DEGRADE_RUN) {
            return RecoveryDirective(RecoveryAction.DEGRADE_RUN, attempt, decision, terminalForItem = true)
        }
        if (decision == CircuitDecision.QUARANTINE_ITEM || attempt >= 7) {
            return RecoveryDirective(
                RecoveryAction.FAIL_ITEM_CONTINUE,
                attempt,
                decision,
                continueQueue = true,
                terminalForItem = true
            )
        }
        val action = when (attempt) {
            1 -> RecoveryAction.REOBSERVE
            2 -> RecoveryAction.RECLASSIFY
            3 -> RecoveryAction.RETRY_ACTION
            4 -> RecoveryAction.BACK_TO_KNOWN_SCREEN
            5 -> RecoveryAction.RELAUNCH_PACKAGE
            else -> RecoveryAction.RESTORE_DURABLE_ITEM
        }
        return RecoveryDirective(action, attempt, decision)
    }

    @Synchronized
    fun onVerifiedProgress(signal: RecoverySignal) {
        val key = signal.toBreakerKey()
        attempts.remove(key)
        circuitBreaker.recordVerifiedProgress(key)
    }

    @Synchronized
    fun resetRun() {
        attempts.clear()
        circuitBreaker.resetRun()
    }

    private fun RecoverySignal.toBreakerKey() = BreakerKey(
        operationId = operationId,
        itemId = itemId,
        state = state,
        category = category,
        adapterProfile = adapterProfile
    )
}
