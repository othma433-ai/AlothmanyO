package com.alothmany.wa.r7.sync

enum class TerminalStatus { INSUFFICIENT, PROGRESS, CONFIRMING, TERMINAL }

data class TerminalEvidence(
    val viewportSignature: String,
    val firstStableKey: String?,
    val lastStableKey: String?,
    val newStableKeys: Set<String>,
    val scrollAccepted: Boolean,
    val scrollProgressed: Boolean,
    val telemetryAdvanced: Boolean
)

data class TerminalDecision(
    val status: TerminalStatus,
    val confirmations: Int,
    val reason: String
)

class TerminalConsensus(private val requiredConfirmations: Int = 3) {
    init { require(requiredConfirmations >= 2) { "requiredConfirmations must be >= 2" } }

    private var confirmations = 0
    private var lastEvidence: TerminalEvidence? = null

    fun observe(evidence: TerminalEvidence): TerminalDecision {
        val hasStableViewport = evidence.viewportSignature.isNotBlank() ||
            evidence.firstStableKey != null || evidence.lastStableKey != null
        if (!hasStableViewport) {
            resetInternal(evidence)
            return TerminalDecision(TerminalStatus.INSUFFICIENT, 0, "missing-stable-viewport-evidence")
        }

        if (evidence.newStableKeys.isNotEmpty()) {
            resetInternal(evidence)
            return TerminalDecision(TerminalStatus.PROGRESS, 0, "new-stable-group-keys")
        }
        if (evidence.scrollProgressed || evidence.telemetryAdvanced) {
            resetInternal(evidence)
            return TerminalDecision(TerminalStatus.PROGRESS, 0, "verified-list-progress")
        }

        val previous = lastEvidence
        if (previous != null && !sameTerminalShape(previous, evidence)) {
            resetInternal(evidence)
            return TerminalDecision(TerminalStatus.PROGRESS, 0, "viewport-or-edge-rows-changed")
        }

        lastEvidence = evidence
        confirmations += 1
        return if (confirmations >= requiredConfirmations) {
            TerminalDecision(TerminalStatus.TERMINAL, confirmations, "terminal-consensus-$confirmations")
        } else {
            TerminalDecision(TerminalStatus.CONFIRMING, confirmations, "terminal-evidence-$confirmations-of-$requiredConfirmations")
        }
    }

    fun reset() {
        confirmations = 0
        lastEvidence = null
    }

    fun confirmationCount(): Int = confirmations

    private fun resetInternal(evidence: TerminalEvidence) {
        confirmations = 0
        lastEvidence = evidence
    }

    private fun sameTerminalShape(a: TerminalEvidence, b: TerminalEvidence): Boolean =
        a.viewportSignature == b.viewportSignature &&
            a.firstStableKey == b.firstStableKey &&
            a.lastStableKey == b.lastStableKey
}
