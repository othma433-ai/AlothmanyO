package com.alothmany.wa.domain

enum class ExtractionPhase {
    IDLE,
    VERIFY_CHAT,
    CAPTURE,
    COMMIT,
    SCROLL_OLDER,
    VERIFY_PROGRESS,
    VERIFY_OLDEST,
    FINAL_FLUSH,
    RECOVERING,
    PAUSED,
    PARTIAL,
    COMPLETE,
    FAILED,
    STOPPED
}

data class JobState(
    val jobId: String,
    val phase: ExtractionPhase,
    val linksFound: Long = 0,
    val occurrences: Long = 0,
    val scrollCount: Int = 0,
    val lastError: String? = null
) {
    companion object {
        fun initial(jobId: String) = JobState(jobId, ExtractionPhase.IDLE)
    }
}

sealed interface JobSignal {
    data object Start : JobSignal
    data object ChatVerified : JobSignal
    data class BatchCaptured(val occurrences: Int) : JobSignal
    data class BatchCommitted(val newLinks: Int, val occurrences: Int = newLinks) : JobSignal
    data object ScrollIssued : JobSignal
    data class ProgressObserved(val hasNewContent: Boolean) : JobSignal
    data object OldestBoundaryProven : JobSignal
    data object FinalFlushComplete : JobSignal
    data class RecoverableError(val message: String) : JobSignal
    data class FatalError(val message: String) : JobSignal
    data object Pause : JobSignal
    data object Resume : JobSignal
    data object Stop : JobSignal
}

class StateReducer {
    fun reduce(state: JobState, signal: JobSignal): JobState = when (signal) {
        JobSignal.Start -> state.copy(phase = ExtractionPhase.VERIFY_CHAT, lastError = null)
        JobSignal.ChatVerified -> state.copy(phase = ExtractionPhase.CAPTURE)
        is JobSignal.BatchCaptured -> state.copy(phase = ExtractionPhase.COMMIT)
        is JobSignal.BatchCommitted -> state.copy(
            phase = ExtractionPhase.SCROLL_OLDER,
            linksFound = state.linksFound + signal.newLinks,
            occurrences = state.occurrences + signal.occurrences
        )
        JobSignal.ScrollIssued -> state.copy(phase = ExtractionPhase.VERIFY_PROGRESS, scrollCount = state.scrollCount + 1)
        is JobSignal.ProgressObserved -> state.copy(
            phase = if (signal.hasNewContent) ExtractionPhase.CAPTURE else ExtractionPhase.VERIFY_OLDEST
        )
        JobSignal.OldestBoundaryProven -> state.copy(phase = ExtractionPhase.FINAL_FLUSH)
        JobSignal.FinalFlushComplete -> state.copy(phase = ExtractionPhase.COMPLETE)
        is JobSignal.RecoverableError -> state.copy(phase = ExtractionPhase.RECOVERING, lastError = signal.message)
        is JobSignal.FatalError -> state.copy(phase = ExtractionPhase.FAILED, lastError = signal.message)
        JobSignal.Pause -> state.copy(phase = ExtractionPhase.PAUSED)
        JobSignal.Resume -> state.copy(phase = ExtractionPhase.VERIFY_CHAT, lastError = null)
        JobSignal.Stop -> state.copy(phase = ExtractionPhase.STOPPED)
    }
}
