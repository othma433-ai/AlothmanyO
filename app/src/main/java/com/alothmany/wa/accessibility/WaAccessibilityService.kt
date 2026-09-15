package com.alothmany.wa.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Thin Android host only. All runtime/telemetry fan-out lives in AndroidAccessibilityServiceHost;
 * workflow decisions stay in automation/controllers.
 */
class WaAccessibilityService : AccessibilityService() {
    private val host = AndroidAccessibilityServiceHost()

    override fun onServiceConnected() {
        super.onServiceConnected()
        host.onConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        host.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        host.onDestroyed(this)
        super.onDestroy()
    }
}
