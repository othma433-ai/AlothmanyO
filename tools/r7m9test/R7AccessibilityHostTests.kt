package r7m9test

import com.alothmany.wa.r7.accessibility.AccessibilityEventBuffer
import com.alothmany.wa.r7.accessibility.AccessibilityEventRecord
import com.alothmany.wa.r7.accessibility.AccessibilityHostCoordinator
import com.alothmany.wa.r7.accessibility.AccessibilityHostRuntimePort
import com.alothmany.wa.r7.accessibility.AccessibilityHostTelemetryPort

private class RuntimeProbe : AccessibilityHostRuntimePort<String> {
    val attached = mutableListOf<String>()
    val detached = mutableListOf<String>()
    val events = mutableListOf<AccessibilityEventRecord>()
    override fun attach(service: String) { attached += service }
    override fun detach(service: String) { detached += service }
    override fun record(event: AccessibilityEventRecord) { events += event }
}

private class TelemetryProbe : AccessibilityHostTelemetryPort {
    val events = mutableListOf<Long>()
    val scrolls = mutableListOf<Long>()
    override fun onEvent(timestamp: Long) { events += timestamp }
    override fun onScroll(timestamp: Long) { scrolls += timestamp }
}

private fun checkThat(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val runtime = RuntimeProbe()
    val telemetry = TelemetryProbe()
    val coordinator = AccessibilityHostCoordinator(runtime, telemetry)

    coordinator.onConnected("service-1")
    checkThat(runtime.attached == listOf("service-1"), "host must attach runtime exactly once")

    coordinator.onEvent(null)
    checkThat(runtime.events.isEmpty(), "null accessibility events must be ignored")

    val ordinary = AccessibilityEventRecord(100L, 1, "com.whatsapp", 4)
    coordinator.onEvent(ordinary)
    checkThat(runtime.events == listOf(ordinary), "ordinary event must be forwarded to runtime")
    checkThat(telemetry.events == listOf(100L), "ordinary event must update event telemetry")
    checkThat(telemetry.scrolls.isEmpty(), "ordinary event must not update scroll telemetry")

    val scroll = AccessibilityEventRecord(200L, AccessibilityEventBuffer.TYPE_VIEW_SCROLLED, "com.whatsapp", 4)
    coordinator.onEvent(scroll)
    checkThat(runtime.events.last() == scroll, "scroll event must be forwarded to runtime")
    checkThat(telemetry.events.last() == 200L, "scroll event must update event telemetry")
    checkThat(telemetry.scrolls == listOf(200L), "scroll event must update scroll telemetry")

    coordinator.onDestroyed("service-1")
    checkThat(runtime.detached == listOf("service-1"), "host must detach runtime")

    println("R7 M9 ACCESSIBILITY HOST TESTS PASS")
}
