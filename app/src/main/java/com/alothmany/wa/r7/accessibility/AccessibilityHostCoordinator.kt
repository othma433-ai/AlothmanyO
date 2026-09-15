package com.alothmany.wa.r7.accessibility

interface AccessibilityHostRuntimePort<S> {
    fun attach(service: S)
    fun detach(service: S)
    fun record(event: AccessibilityEventRecord)
}

interface AccessibilityHostTelemetryPort {
    fun onEvent(timestamp: Long)
    fun onScroll(timestamp: Long)
}

/**
 * Pure host coordinator. It owns only lifecycle/event fan-out and deliberately contains no
 * WhatsApp workflow decisions, navigation, extraction, join, or recovery policy.
 */
class AccessibilityHostCoordinator<S>(
    private val runtime: AccessibilityHostRuntimePort<S>,
    private val telemetry: AccessibilityHostTelemetryPort,
    private val scrollEventType: Int = AccessibilityEventBuffer.TYPE_VIEW_SCROLLED
) {
    fun onConnected(service: S) {
        runtime.attach(service)
    }

    fun onEvent(event: AccessibilityEventRecord?) {
        if (event == null) return
        runtime.record(event)
        telemetry.onEvent(event.timestamp)
        if (event.eventType == scrollEventType) telemetry.onScroll(event.timestamp)
    }

    fun onDestroyed(service: S) {
        runtime.detach(service)
    }
}
