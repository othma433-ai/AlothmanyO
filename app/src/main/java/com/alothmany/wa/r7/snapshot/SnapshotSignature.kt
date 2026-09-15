package com.alothmany.wa.r7.snapshot

import java.security.MessageDigest

object SnapshotSignature {
    fun compute(node: NodeSnapshot): String {
        val payload = listOf(
            node.viewId, node.className, node.textHash, node.contentDescriptionHash,
            node.left, node.top, node.right, node.bottom,
            node.clickable, node.scrollable, node.selected, node.enabled, node.visibleToUser,
            node.depth, node.parentSignature, node.childCount,
            node.packageName, node.windowId
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
