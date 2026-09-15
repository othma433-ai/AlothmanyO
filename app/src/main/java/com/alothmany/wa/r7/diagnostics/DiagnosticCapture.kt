package com.alothmany.wa.r7.diagnostics

import com.alothmany.wa.r7.snapshot.NodeSnapshot
import java.security.MessageDigest

/** Sanitized structural bundle. No raw Accessibility text is accepted by this API. */
data class DiagnosticBundle(
    val runIdHash: String,
    val reason: String,
    val nodes: List<NodeSnapshot>,
    val classifierEvidenceHashes: List<String>,
    val rowDecisionHashes: List<String>,
    val scrollTelemetryHash: String,
    val transitionHashes: List<String>,
    val actionHashes: List<String>,
    val verificationHashes: List<String>,
    val profileDecisionHashes: List<String>
) {
    fun toCanonicalText(): String = buildString {
        append("R7DIAG|1\n")
        append("RUN|").append(runIdHash).append('\n')
        append("REASON|").append(reason).append('\n')
        nodes.forEach { node ->
            append("NODE|")
                .append(node.viewId).append('|')
                .append(node.className).append('|')
                .append(node.textHash).append('|')
                .append(node.contentDescriptionHash).append('|')
                .append(node.left).append(',').append(node.top).append(',').append(node.right).append(',').append(node.bottom).append('|')
                .append(node.clickable).append('|').append(node.scrollable).append('|').append(node.selected).append('|')
                .append(node.enabled).append('|').append(node.visibleToUser).append('|')
                .append(node.packageName).append('|').append(node.windowId).append('\n')
        }
        appendHashes("CLASSIFIER", classifierEvidenceHashes)
        appendHashes("ROW", rowDecisionHashes)
        append("SCROLL|").append(scrollTelemetryHash).append('\n')
        appendHashes("TRANSITION", transitionHashes)
        appendHashes("ACTION", actionHashes)
        appendHashes("VERIFY", verificationHashes)
        appendHashes("PROFILE", profileDecisionHashes)
    }

    private fun StringBuilder.appendHashes(prefix: String, values: List<String>) {
        values.forEach { append(prefix).append('|').append(it).append('\n') }
    }
}

class DiagnosticCapture(private val maxNodes: Int = 512) {
    init { require(maxNodes > 0) }

    fun capture(
        runId: String,
        reason: String,
        nodes: List<NodeSnapshot>,
        classifierEvidence: List<String>,
        rowDecisions: List<String>,
        scrollTelemetry: String,
        transitions: List<String>,
        actions: List<String>,
        verification: List<String>,
        profileDecisions: List<String>
    ): DiagnosticBundle = DiagnosticBundle(
        runIdHash = hash(runId),
        reason = reason.take(96).replace(Regex("[^A-Za-z0-9_.:-]"), "_"),
        nodes = nodes.take(maxNodes),
        classifierEvidenceHashes = classifierEvidence.map(::hash),
        rowDecisionHashes = rowDecisions.map(::hash),
        scrollTelemetryHash = hash(scrollTelemetry),
        transitionHashes = transitions.map(::hash),
        actionHashes = actions.map(::hash),
        verificationHashes = verification.map(::hash),
        profileDecisionHashes = profileDecisions.map(::hash)
    )

    private fun hash(value: String): String {
        if (value.isBlank()) return ""
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }
}
