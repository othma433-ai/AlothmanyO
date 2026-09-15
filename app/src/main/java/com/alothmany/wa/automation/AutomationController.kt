package com.alothmany.wa.automation

import com.alothmany.wa.r7.automation.RunLifecycle
import com.alothmany.wa.r7.automation.RunLifecycleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ControlState { RUNNING, PAUSE_REQUESTED, PAUSED, STOPPED }

class AutomationController {
    private val lifecycle = RunLifecycle()
    private val _state = MutableStateFlow(ControlState.RUNNING)
    val state = _state.asStateFlow()

    fun pause() {
        if (_state.value == ControlState.STOPPED) return
        lifecycle.requestPause()
        _state.value = ControlState.PAUSE_REQUESTED
    }

    fun resume() {
        if (_state.value == ControlState.STOPPED) return
        lifecycle.resume()
        _state.value = ControlState.RUNNING
    }

    fun stop() {
        lifecycle.stop()
        _state.value = ControlState.STOPPED
    }

    fun currentGeneration(): Long = lifecycle.currentGeneration()
    fun acceptsCallback(generation: Long): Boolean = lifecycle.acceptsCallback(generation)

    suspend fun awaitRunnable(onPausedAtBoundary: suspend () -> Unit = {}): Boolean {
        if (_state.value == ControlState.PAUSE_REQUESTED) {
            val boundary = lifecycle.onAtomicBoundary()
            if (boundary.persistPause) {
                _state.value = ControlState.PAUSED
                onPausedAtBoundary()
            }
        }
        while (_state.value == ControlState.PAUSED) delay(200)
        return _state.value != ControlState.STOPPED && lifecycle.state() != RunLifecycleState.STOPPED
    }
}
