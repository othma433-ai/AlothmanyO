package com.alothmany.wa.r7.navigation

enum class SemanticScrollDirection { OLDER, NEWER, LIST_FORWARD, LIST_BACKWARD }
enum class ScrollStrategy { PRIMARY_NODE, ALTERNATE_NODE, SAFE_GESTURE }
enum class ScrollDirectiveAction {
    TRY_PRIMARY_NODE,
    TRY_ALTERNATE_NODE,
    TRY_SAFE_GESTURE,
    PROGRESSED,
    CONFIRM_BOUNDARY,
    BOUNDARY,
    RECOVER
}

data class ScrollAttemptEvidence(
    val dispatched: Boolean,
    val beforeViewportSignature: String,
    val afterViewportSignature: String,
    val telemetryAdvanced: Boolean
)



data class SemanticScrollResult(
    val actionAccepted: Boolean,
    val progressed: Boolean,
    val boundaryConfirmed: Boolean,
    val recoveryRequired: Boolean,
    val beforeViewportSignature: String,
    val afterViewportSignature: String,
    val terminalConfirmations: Int,
    val finalStrategy: ScrollStrategy
)

data class SmartScrollDirective(
    val action: ScrollDirectiveAction,
    val direction: SemanticScrollDirection,
    val terminalConfirmations: Int,
    val reason: String
)

class SmartScrollController(private val requiredTerminalConfirmations: Int = 3) {
    private var expectedStrategy = ScrollStrategy.PRIMARY_NODE
    private var terminalSignature = ""
    private var terminalConfirmations = 0

    init {
        require(requiredTerminalConfirmations >= 2) { "requiredTerminalConfirmations must be >= 2" }
    }

    fun begin(direction: SemanticScrollDirection): SmartScrollDirective {
        expectedStrategy = ScrollStrategy.PRIMARY_NODE
        return SmartScrollDirective(
            ScrollDirectiveAction.TRY_PRIMARY_NODE,
            direction,
            terminalConfirmations,
            "begin-semantic-scroll"
        )
    }

    fun observeAttempt(
        direction: SemanticScrollDirection,
        strategy: ScrollStrategy,
        evidence: ScrollAttemptEvidence
    ): SmartScrollDirective {
        if (strategy != expectedStrategy) {
            resetTerminalEvidence()
            expectedStrategy = ScrollStrategy.PRIMARY_NODE
            return SmartScrollDirective(
                ScrollDirectiveAction.RECOVER,
                direction,
                0,
                "strategy-order-violation"
            )
        }

        val viewportChanged = evidence.beforeViewportSignature.isNotBlank() &&
            evidence.afterViewportSignature.isNotBlank() &&
            evidence.beforeViewportSignature != evidence.afterViewportSignature
        val progressed = evidence.dispatched && (viewportChanged || evidence.telemetryAdvanced)
        if (progressed) {
            resetTerminalEvidence()
            expectedStrategy = ScrollStrategy.PRIMARY_NODE
            return SmartScrollDirective(
                ScrollDirectiveAction.PROGRESSED,
                direction,
                0,
                if (viewportChanged) "viewport-progress" else "scroll-telemetry-progress"
            )
        }

        return when (strategy) {
            ScrollStrategy.PRIMARY_NODE -> {
                expectedStrategy = ScrollStrategy.ALTERNATE_NODE
                SmartScrollDirective(
                    ScrollDirectiveAction.TRY_ALTERNATE_NODE,
                    direction,
                    terminalConfirmations,
                    "primary-no-progress"
                )
            }
            ScrollStrategy.ALTERNATE_NODE -> {
                expectedStrategy = ScrollStrategy.SAFE_GESTURE
                SmartScrollDirective(
                    ScrollDirectiveAction.TRY_SAFE_GESTURE,
                    direction,
                    terminalConfirmations,
                    "alternate-no-progress"
                )
            }
            ScrollStrategy.SAFE_GESTURE -> {
                expectedStrategy = ScrollStrategy.PRIMARY_NODE
                if (!evidence.dispatched) {
                    resetTerminalEvidence()
                    return SmartScrollDirective(
                        ScrollDirectiveAction.RECOVER,
                        direction,
                        0,
                        "gesture-dispatch-rejected"
                    )
                }
                val signature = evidence.afterViewportSignature.ifBlank { evidence.beforeViewportSignature }
                if (signature.isBlank()) {
                    resetTerminalEvidence()
                    return SmartScrollDirective(
                        ScrollDirectiveAction.RECOVER,
                        direction,
                        0,
                        "missing-viewport-evidence"
                    )
                }
                if (terminalSignature == signature) {
                    terminalConfirmations++
                } else {
                    terminalSignature = signature
                    terminalConfirmations = 1
                }
                if (terminalConfirmations >= requiredTerminalConfirmations) {
                    SmartScrollDirective(
                        ScrollDirectiveAction.BOUNDARY,
                        direction,
                        terminalConfirmations,
                        "terminal-consensus"
                    )
                } else {
                    SmartScrollDirective(
                        ScrollDirectiveAction.CONFIRM_BOUNDARY,
                        direction,
                        terminalConfirmations,
                        "terminal-confirming"
                    )
                }
            }
        }
    }

    fun reset() {
        expectedStrategy = ScrollStrategy.PRIMARY_NODE
        resetTerminalEvidence()
    }

    private fun resetTerminalEvidence() {
        terminalSignature = ""
        terminalConfirmations = 0
    }
}
