package com.alothmany.wa.r7.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import java.security.MessageDigest

class NodeSnapshotFactory {
    fun fromNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        parentSignature: String,
        capturedAt: Long
    ): NodeSnapshot {
        val bounds = Rect().also(node::getBoundsInScreen)
        return NodeSnapshot(
            viewId = node.viewIdResourceName.orEmpty(),
            className = node.className?.toString().orEmpty(),
            textHash = hashSensitive(node.text),
            contentDescriptionHash = hashSensitive(node.contentDescription),
            left = bounds.left,
            top = bounds.top,
            right = bounds.right,
            bottom = bounds.bottom,
            clickable = node.isClickable,
            scrollable = node.isScrollable,
            selected = node.isSelected,
            enabled = node.isEnabled,
            visibleToUser = node.isVisibleToUser,
            depth = depth,
            parentSignature = parentSignature,
            childCount = node.childCount,
            packageName = node.packageName?.toString().orEmpty(),
            windowId = node.windowId,
            capturedAt = capturedAt
        )
    }

    private fun hashSensitive(value: CharSequence?): String {
        val text = value?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }
}
