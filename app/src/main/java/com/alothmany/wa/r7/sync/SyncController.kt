package com.alothmany.wa.r7.sync

class SyncController(requiredTerminalConfirmations: Int = 3) {
    private val terminalConsensus = TerminalConsensus(requiredTerminalConfirmations)
    private val seen = linkedMapOf<String, GroupCandidate>()

    private var lastNewKeys: Set<String> = emptySet()
    private var firstStableKey: String? = null
    private var lastStableKey: String? = null
    private var captureViewport: String = ""

    fun captureRows(
        rows: List<String>,
        provenance: DiscoveryProvenance,
        viewportSignature: String,
        identityHints: Map<String, String> = emptyMap()
    ): CaptureResult {
        val normalizedRows = rows
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter { it.isNotBlank() }
            .distinctBy(::stableGroupKey)

        firstStableKey = normalizedRows.firstOrNull()?.let(::stableGroupKey)
        lastStableKey = normalizedRows.lastOrNull()?.let(::stableGroupKey)
        captureViewport = viewportSignature

        val added = ArrayList<GroupCandidate>()
        val keys = linkedSetOf<String>()
        for (name in normalizedRows) {
            val key = stableGroupKey(name)
            if (key in seen) continue
            val hint = identityHints[name].orEmpty().ifBlank { identityHints[key].orEmpty() }
            val candidate = GroupCandidate(key, name, provenance, hint)
            seen[key] = candidate
            added += candidate
            keys += key
        }
        lastNewKeys = keys
        return CaptureResult(added, seen.size, SyncAction.SCROLL_FORWARD, viewportSignature)
    }

    fun onScroll(
        actionAccepted: Boolean,
        progressed: Boolean,
        telemetryAdvanced: Boolean,
        afterViewportSignature: String
    ): SyncDirective {
        val evidence = TerminalEvidence(
            viewportSignature = afterViewportSignature.ifBlank { captureViewport },
            firstStableKey = firstStableKey,
            lastStableKey = lastStableKey,
            newStableKeys = lastNewKeys,
            scrollAccepted = actionAccepted,
            scrollProgressed = progressed,
            telemetryAdvanced = telemetryAdvanced
        )
        val decision = terminalConsensus.observe(evidence)
        lastNewKeys = emptySet()
        return when (decision.status) {
            TerminalStatus.TERMINAL -> SyncDirective(
                SyncState.COMPLETE,
                SyncAction.COMPLETE,
                decision.reason,
                decision.confirmations
            )
            TerminalStatus.INSUFFICIENT -> SyncDirective(
                SyncState.RECOVERING,
                SyncAction.RECOVER,
                decision.reason,
                decision.confirmations
            )
            TerminalStatus.PROGRESS -> SyncDirective(
                SyncState.VERIFY_LIST_PROGRESS,
                SyncAction.SCROLL_FORWARD,
                decision.reason,
                decision.confirmations
            )
            TerminalStatus.CONFIRMING -> SyncDirective(
                SyncState.VERIFY_TERMINAL_CONSENSUS,
                SyncAction.SCROLL_FORWARD,
                decision.reason,
                decision.confirmations
            )
        }
    }

    fun totalSeen(): Int = seen.size
    fun snapshotSeen(): List<GroupCandidate> = seen.values.toList()
    fun reset() {
        terminalConsensus.reset()
        seen.clear()
        lastNewKeys = emptySet()
        firstStableKey = null
        lastStableKey = null
        captureViewport = ""
    }
}
