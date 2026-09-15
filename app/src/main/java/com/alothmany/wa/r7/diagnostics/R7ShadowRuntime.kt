package com.alothmany.wa.r7.diagnostics

import java.util.concurrent.atomic.AtomicLong

object R7ShadowRuntime {
    private val eventCount = AtomicLong(0)
    private val lastEventAt = AtomicLong(0)
    private val scrollEventCount = AtomicLong(0)
    private val lastScrollEventAt = AtomicLong(0)

    fun onAccessibilityEvent(now: Long = System.currentTimeMillis()) {
        eventCount.incrementAndGet()
        lastEventAt.set(now)
    }

    fun onScrollEvent(now: Long = System.currentTimeMillis()) {
        scrollEventCount.incrementAndGet()
        lastScrollEventAt.set(now)
    }

    fun snapshot(): Pair<Long, Long> = eventCount.get() to lastEventAt.get()

    fun scrollTelemetrySnapshot(): Pair<Long, Long> = scrollEventCount.get() to lastScrollEventAt.get()
}
