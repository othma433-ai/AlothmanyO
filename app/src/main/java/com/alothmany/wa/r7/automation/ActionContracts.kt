package com.alothmany.wa.r7.automation

data class ActionContext(
    val runId: String,
    val operationId: String,
    val queueItemId: String?,
    val stateGeneration: Long,
    val actionId: String,
    val attemptNo: Int,
    val requestedAt: Long
)

enum class VerificationResult { PASS, FAIL, TIMEOUT, STALE }

class ActionExecutor {
    private var currentRunId: String? = null
    private var currentGeneration: Long = -1
    private val pending = LinkedHashMap<String, ActionContext>()

    fun startRun(runId: String, generation: Long = 0) {
        currentRunId = runId
        currentGeneration = generation
        pending.clear()
    }

    fun advanceGeneration(): Long {
        currentGeneration++
        pending.clear()
        return currentGeneration
    }

    fun isCurrent(context: ActionContext): Boolean =
        context.runId == currentRunId && context.stateGeneration == currentGeneration

    fun register(context: ActionContext): Boolean {
        if (!isCurrent(context)) return false
        pending[context.actionId] = context
        return true
    }

    fun onVerification(context: ActionContext, result: VerificationResult): Boolean {
        if (!isCurrent(context)) return false
        val registered = pending[context.actionId] ?: return false
        if (registered != context) return false
        pending.remove(context.actionId)
        return result == VerificationResult.PASS
    }

    fun pendingCount(): Int = pending.size
}
