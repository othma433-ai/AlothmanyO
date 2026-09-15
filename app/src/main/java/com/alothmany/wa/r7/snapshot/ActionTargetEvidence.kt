package com.alothmany.wa.r7.snapshot

data class ActionTargetEvidence(
    val packageName: String,
    val windowId: Int,
    val signature: String,
    val viewId: String,
    val className: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val capturedAt: Long
) {
    companion object {
        fun from(node: NodeSnapshot): ActionTargetEvidence = ActionTargetEvidence(
            packageName = node.packageName,
            windowId = node.windowId,
            signature = SnapshotSignature.compute(node),
            viewId = node.viewId,
            className = node.className,
            left = node.left,
            top = node.top,
            right = node.right,
            bottom = node.bottom,
            capturedAt = node.capturedAt
        )
    }
}
