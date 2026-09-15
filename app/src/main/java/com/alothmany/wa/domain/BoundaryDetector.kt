package com.alothmany.wa.domain

class BoundaryDetector(private val requiredStableWindows: Int = 3) {
    init { require(requiredStableWindows >= 2) }

    private var lastFingerprint: String? = null
    private var stableQuietWindows: Int = 0

    fun observe(windowFingerprint: String, newItems: Int): Boolean {
        if (newItems > 0) {
            lastFingerprint = windowFingerprint
            stableQuietWindows = 0
            return false
        }
        stableQuietWindows = if (lastFingerprint == windowFingerprint) {
            stableQuietWindows + 1
        } else {
            1
        }
        lastFingerprint = windowFingerprint
        return stableQuietWindows >= requiredStableWindows
    }

    fun reset() {
        lastFingerprint = null
        stableQuietWindows = 0
    }

    fun evidenceCount(): Int = stableQuietWindows
}
