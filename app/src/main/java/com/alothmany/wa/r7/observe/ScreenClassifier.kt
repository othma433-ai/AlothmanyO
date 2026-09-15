package com.alothmany.wa.r7.observe

import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.snapshot.SnapshotSignature

class ScreenClassifier {
    fun classify(nodes: List<NodeSnapshot>, observedAt: Long = System.currentTimeMillis()): ScreenObservation {
        if (nodes.isEmpty()) return ScreenObservation(ScreenType.UNKNOWN, 0.0, listOf("empty-tree"), "", -1, "", observedAt)
        val packageName = nodes.first().packageName
        val windowId = nodes.first().windowId
        val labels = nodes.map { it.viewId.lowercase() + " " + it.className.lowercase() }
        val selected = nodes.filter { it.selected }
        val type = when {
            labels.any { "search" in it } -> ScreenType.SEARCH
            selected.any { "group" in it.viewId.lowercase() } -> ScreenType.GROUPS_LIST
            labels.any { "conversation" in it || "message" in it } -> ScreenType.CONVERSATION
            labels.any { "chat" in it || "recycler" in it } -> ScreenType.CHAT_LIST
            else -> ScreenType.UNKNOWN
        }
        val confidence = if (type == ScreenType.UNKNOWN) 0.35 else 0.75
        val viewport = nodes.take(64).joinToString(":") { SnapshotSignature.compute(it).take(16) }
        return ScreenObservation(type, confidence, listOf("structural-classification"), packageName, windowId, viewport, observedAt)
    }
}
