package com.alothmany.wa.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.alothmany.wa.r7.accessibility.AccessibilityEventRecord
import com.alothmany.wa.r7.accessibility.AccessibilityHostCoordinator
import com.alothmany.wa.r7.accessibility.AccessibilityHostRuntimePort
import com.alothmany.wa.r7.accessibility.AccessibilityHostTelemetryPort
import com.alothmany.wa.r7.diagnostics.R7ShadowRuntime

/** Android-only adapter around the pure R7 accessibility host coordinator. */
class AndroidAccessibilityServiceHost {
    private val coordinator = AccessibilityHostCoordinator(
        runtime = object : AccessibilityHostRuntimePort<AccessibilityService> {
            override fun attach(service: AccessibilityService) = AutomationRuntime.attach(service)
            override fun detach(service: AccessibilityService) = AutomationRuntime.detach(service)
            override fun record(event: AccessibilityEventRecord) {
                AutomationRuntime.eventBuffer.record(
                    timestamp = event.timestamp,
                    eventType = event.eventType,
                    packageName = event.packageName,
                    windowId = event.windowId
                )
            }
        },
        telemetry = object : AccessibilityHostTelemetryPort {
            override fun onEvent(timestamp: Long) = R7ShadowRuntime.onAccessibilityEvent(timestamp)
            override fun onScroll(timestamp: Long) = R7ShadowRuntime.onScrollEvent(timestamp)
        }
    )

    fun onConnected(service: AccessibilityService) {
        coordinator.onConnected(service)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val record = event?.let {
            AccessibilityEventRecord(
                timestamp = System.currentTimeMillis(),
                eventType = it.eventType,
                packageName = it.packageName?.toString(),
                windowId = it.windowId
            )
        }
        coordinator.onEvent(record)
    }

    fun onDestroyed(service: AccessibilityService) {
        coordinator.onDestroyed(service)
    }
}
