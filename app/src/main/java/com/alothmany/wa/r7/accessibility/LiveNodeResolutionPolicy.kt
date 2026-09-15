package com.alothmany.wa.r7.accessibility

import com.alothmany.wa.r7.snapshot.ActionTargetEvidence
import com.alothmany.wa.r7.snapshot.NodeSnapshot
import com.alothmany.wa.r7.snapshot.SnapshotSignature

enum class ResolutionStatus { MATCH, NOT_FOUND, AMBIGUOUS, WINDOW_MISMATCH, STALE_EVIDENCE }

data class ResolutionResult(
    val status: ResolutionStatus,
    val candidate: NodeSnapshot? = null,
    val reason: String
)

object LiveNodeResolutionPolicy {
    fun resolve(
        evidence: ActionTargetEvidence,
        candidates: List<NodeSnapshot>,
        now: Long,
        maxAgeMs: Long = 2_000
    ): ResolutionResult {
        if (now - evidence.capturedAt > maxAgeMs) {
            return ResolutionResult(ResolutionStatus.STALE_EVIDENCE, reason = "evidence-age")
        }
        val samePackage = candidates.filter { it.packageName == evidence.packageName }
        if (samePackage.isEmpty()) return ResolutionResult(ResolutionStatus.NOT_FOUND, reason = "package")
        val sameWindow = samePackage.filter { it.windowId == evidence.windowId }
        if (sameWindow.isEmpty()) return ResolutionResult(ResolutionStatus.WINDOW_MISMATCH, reason = "window")
        val exact = sameWindow.filter { node ->
            SnapshotSignature.compute(node) == evidence.signature &&
                node.viewId == evidence.viewId &&
                node.className == evidence.className &&
                node.left == evidence.left && node.top == evidence.top &&
                node.right == evidence.right && node.bottom == evidence.bottom
        }
        return when (exact.size) {
            1 -> ResolutionResult(ResolutionStatus.MATCH, exact.first(), "exact-structural-match")
            0 -> ResolutionResult(ResolutionStatus.NOT_FOUND, reason = "signature-or-bounds")
            else -> ResolutionResult(ResolutionStatus.AMBIGUOUS, reason = "multiple-exact-candidates")
        }
    }
}
