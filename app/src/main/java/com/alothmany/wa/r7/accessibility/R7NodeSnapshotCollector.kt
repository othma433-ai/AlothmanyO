package com.alothmany.wa.r7.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.snapshot.SnapshotSignature
import java.util.ArrayDeque

class R7NodeSnapshotCollector(
    private val factory: NodeSnapshotFactory = NodeSnapshotFactory(),
    private val maxNodes: Int = 512,
    private val maxDepth: Int = 24
) {
    private data class Pending(val node: AccessibilityNodeInfo, val depth: Int, val parentSignature: String)

    fun capture(root: AccessibilityNodeInfo, capturedAt: Long = System.currentTimeMillis()): List<NodeSnapshot> {
        val out = ArrayList<NodeSnapshot>(minOf(maxNodes, 256))
        val queue = ArrayDeque<Pending>()
        queue.add(Pending(root, 0, "ROOT"))
        while (queue.isNotEmpty() && out.size < maxNodes) {
            val pending = queue.removeFirst()
            val snapshot = factory.fromNode(pending.node, pending.depth, pending.parentSignature, capturedAt)
            out += snapshot
            if (pending.depth < maxDepth) {
                val parentSig = SnapshotSignature.compute(snapshot)
                for (i in 0 until pending.node.childCount) {
                    pending.node.getChild(i)?.let { queue.add(Pending(it, pending.depth + 1, parentSig)) }
                }
            }
        }
        return out
    }
}
