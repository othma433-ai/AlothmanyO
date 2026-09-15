package com.alothmany.wa.r7.snapshot

data class NodeSnapshot(
    val viewId: String,
    val className: String,
    val textHash: String,
    val contentDescriptionHash: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    val scrollable: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
    val visibleToUser: Boolean,
    val depth: Int,
    val parentSignature: String,
    val childCount: Int,
    val packageName: String,
    val windowId: Int,
    val capturedAt: Long
)
