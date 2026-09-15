package com.alothmany.wa.r7.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.snapshot.SnapshotSignature
import java.util.ArrayDeque

interface AccessibilityGateway {
    fun captureVisibleTree(): List<NodeSnapshot>
    fun dispatchOnResolvedTarget(
        evidence: ActionTargetEvidence,
        maxAgeMs: Long = 2_000,
        action: (AccessibilityNodeInfo) -> Boolean
    ): ResolvedDispatch
}

data class ResolvedDispatch(
    val status: ResolutionStatus,
    val dispatched: Boolean,
    val reason: String
)

class AndroidAccessibilityGateway(
    private val service: AccessibilityService,
    private val factory: NodeSnapshotFactory = NodeSnapshotFactory(),
    private val maxNodes: Int = 3_500,
    private val maxDepth: Int = 35
) : AccessibilityGateway {

    private data class Entry(val node: AccessibilityNodeInfo, val depth: Int, val parentSignature: String)

    override fun captureVisibleTree(): List<NodeSnapshot> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val capturedAt = System.currentTimeMillis()
        val result = ArrayList<NodeSnapshot>(minOf(maxNodes, 512))
        val queue = ArrayDeque<Entry>()
        queue.add(Entry(root, 0, "ROOT"))
        var visited = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val entry = queue.removeFirst()
            visited++
            val snap = factory.fromNode(entry.node, entry.depth, entry.parentSignature, capturedAt)
            result.add(snap)
            if (entry.depth < maxDepth) {
                val parentSig = SnapshotSignature.compute(snap)
                for (i in 0 until entry.node.childCount) {
                    entry.node.getChild(i)?.let { queue.add(Entry(it, entry.depth + 1, parentSig)) }
                }
            }
        }
        return result
    }

    override fun dispatchOnResolvedTarget(
        evidence: ActionTargetEvidence,
        maxAgeMs: Long,
        action: (AccessibilityNodeInfo) -> Boolean
    ): ResolvedDispatch {
        val now = System.currentTimeMillis()
        if (now - evidence.capturedAt > maxAgeMs) {
            return ResolvedDispatch(ResolutionStatus.STALE_EVIDENCE, false, "evidence-age")
        }
        val root = service.rootInActiveWindow
            ?: return ResolvedDispatch(ResolutionStatus.NOT_FOUND, false, "no-active-root")
        if (root.packageName?.toString().orEmpty() != evidence.packageName) {
            return ResolvedDispatch(ResolutionStatus.NOT_FOUND, false, "package")
        }
        if (root.windowId != evidence.windowId) {
            return ResolvedDispatch(ResolutionStatus.WINDOW_MISMATCH, false, "window")
        }

        val queue = ArrayDeque<Entry>()
        queue.add(Entry(root, 0, "ROOT"))
        var visited = 0
        var match: AccessibilityNodeInfo? = null
        var matchCount = 0
        while (queue.isNotEmpty() && visited < maxNodes) {
            val entry = queue.removeFirst()
            visited++
            val snap = factory.fromNode(entry.node, entry.depth, entry.parentSignature, now)
            if (sameTarget(snap, evidence)) {
                match = entry.node
                matchCount++
                if (matchCount > 1) break
            }
            if (entry.depth < maxDepth) {
                val parentSig = SnapshotSignature.compute(snap)
                for (i in 0 until entry.node.childCount) {
                    entry.node.getChild(i)?.let { queue.add(Entry(it, entry.depth + 1, parentSig)) }
                }
            }
        }
        if (matchCount == 0 || match == null) return ResolvedDispatch(ResolutionStatus.NOT_FOUND, false, "signature-or-bounds")
        if (matchCount > 1) return ResolvedDispatch(ResolutionStatus.AMBIGUOUS, false, "multiple-exact-candidates")
        val resolved = match ?: return ResolvedDispatch(ResolutionStatus.NOT_FOUND, false, "lost-candidate")
        val dispatched = runCatching { action(resolved) }.getOrDefault(false)
        return ResolvedDispatch(ResolutionStatus.MATCH, dispatched, if (dispatched) "dispatched-not-verified" else "dispatch-rejected")
    }

    private fun sameTarget(node: NodeSnapshot, evidence: ActionTargetEvidence): Boolean =
        node.packageName == evidence.packageName &&
            node.windowId == evidence.windowId &&
            node.viewId == evidence.viewId &&
            node.className == evidence.className &&
            node.left == evidence.left && node.top == evidence.top &&
            node.right == evidence.right && node.bottom == evidence.bottom &&
            SnapshotSignature.compute(node) == evidence.signature
}
