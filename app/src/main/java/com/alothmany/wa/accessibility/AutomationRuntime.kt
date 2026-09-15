package com.alothmany.wa.accessibility

import android.accessibilityservice.AccessibilityService
import com.alothmany.wa.r7.accessibility.AccessibilityEventBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

object AutomationRuntime {
    @Volatile var accessibility: AccessibilityService? = null
        private set

    val eventBuffer = AccessibilityEventBuffer(capacity = 256)

    private val generationCounter = AtomicLong(0)
    private val gestureStartedAt = AtomicLong(0)
    @Volatile private var attachedGeneration: Long = 0

    private val _connection = MutableStateFlow(false)
    val connection = _connection.asStateFlow()

    fun attach(service: AccessibilityService) {
        accessibility = service
        attachedGeneration = generationCounter.incrementAndGet()
        eventBuffer.clear()
        _connection.value = true
    }

    fun detach(service: AccessibilityService) {
        if (accessibility === service) accessibility = null
        _connection.value = false
    }

    fun currentServiceGeneration(): Long = attachedGeneration

    fun markGestureStarted(now: Long = System.currentTimeMillis()) { gestureStartedAt.set(now) }
    fun markGestureFinished() { gestureStartedAt.set(0) }
    fun activeGestureStartedAt(): Long? = gestureStartedAt.get().takeIf { it > 0 }
}
