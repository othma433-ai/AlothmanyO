package com.alothmany.wa.r7.automation

enum class RunLifecycleState { RUNNING, PAUSE_REQUESTED, PAUSED, STOPPED }

data class AtomicBoundaryDirective(
    val persistPause: Boolean,
    val stopRequested: Boolean,
    val generation: Long
)

class RunLifecycle(initialGeneration: Long = 0) {
    private var lifecycleState = RunLifecycleState.RUNNING
    private var generation = initialGeneration

    @Synchronized
    fun requestPause() {
        if (lifecycleState == RunLifecycleState.RUNNING) lifecycleState = RunLifecycleState.PAUSE_REQUESTED
    }

    @Synchronized
    fun resume() {
        if (lifecycleState != RunLifecycleState.STOPPED) lifecycleState = RunLifecycleState.RUNNING
    }

    @Synchronized
    fun stop(): Long {
        if (lifecycleState != RunLifecycleState.STOPPED) generation++
        lifecycleState = RunLifecycleState.STOPPED
        return generation
    }

    @Synchronized
    fun onAtomicBoundary(): AtomicBoundaryDirective {
        val persistPause = lifecycleState == RunLifecycleState.PAUSE_REQUESTED
        if (persistPause) lifecycleState = RunLifecycleState.PAUSED
        return AtomicBoundaryDirective(
            persistPause = persistPause,
            stopRequested = lifecycleState == RunLifecycleState.STOPPED,
            generation = generation
        )
    }

    @Synchronized
    fun acceptsCallback(callbackGeneration: Long): Boolean =
        lifecycleState != RunLifecycleState.STOPPED && callbackGeneration == generation

    @Synchronized
    fun currentGeneration(): Long = generation

    @Synchronized
    fun state(): RunLifecycleState = lifecycleState

    @Synchronized
    fun isDurablyPaused(): Boolean = lifecycleState == RunLifecycleState.PAUSED
}


enum class ReconstructionAction {
    REVERIFY_AND_RESUME,
    SKIP_COMPLETED,
    DO_NOT_RESUME
}

data class DurableRunState(
    val jobStatus: String,
    val itemStatus: String?,
    val checkpointPhase: String?
)

object LifecycleReconstructionPlanner {
    fun plan(state: DurableRunState): ReconstructionAction {
        if (state.jobStatus == "STOPPED" || state.jobStatus == "FAILED" || state.jobStatus == "COMPLETE") {
            return ReconstructionAction.DO_NOT_RESUME
        }
        if (state.checkpointPhase == "COMPLETE" || state.itemStatus == "COMPLETED") {
            return ReconstructionAction.SKIP_COMPLETED
        }
        return if (state.jobStatus in setOf("RUNNING", "PAUSED", "RECOVERING") &&
            state.itemStatus !in setOf("FAILED", "COMPLETED")) {
            ReconstructionAction.REVERIFY_AND_RESUME
        } else {
            ReconstructionAction.DO_NOT_RESUME
        }
    }
}
