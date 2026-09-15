package com.alothmany.wa.r7.accessibility

import java.util.ArrayDeque

data class AccessibilityEventRecord(
    val timestamp: Long,
    val eventType: Int,
    val packageName: String?,
    val windowId: Int
)

class AccessibilityEventBuffer(private val capacity: Int = 128) {
    init { require(capacity > 0) { "capacity must be positive" } }

    private val records = ArrayDeque<AccessibilityEventRecord>(capacity)
    private var latestEventAt: Long = 0
    private var latestScrollAt: Long = 0

    @Synchronized
    fun record(timestamp: Long, eventType: Int, packageName: String?, windowId: Int) {
        if (records.size == capacity) records.removeFirst()
        records.addLast(AccessibilityEventRecord(timestamp, eventType, packageName, windowId))
        if (timestamp > latestEventAt) latestEventAt = timestamp
        if (eventType == TYPE_VIEW_SCROLLED && timestamp > latestScrollAt) latestScrollAt = timestamp
    }

    @Synchronized
    fun snapshot(): List<AccessibilityEventRecord> = records.toList()

    @Synchronized
    fun lastEventAt(): Long = latestEventAt

    @Synchronized
    fun lastScrollAt(): Long = latestScrollAt

    @Synchronized
    fun clear() {
        records.clear()
        latestEventAt = 0
        latestScrollAt = 0
    }

    companion object {
        // Mirrors AccessibilityEvent.TYPE_VIEW_SCROLLED without pulling Android into pure JVM tests.
        const val TYPE_VIEW_SCROLLED: Int = 4096
    }
}
