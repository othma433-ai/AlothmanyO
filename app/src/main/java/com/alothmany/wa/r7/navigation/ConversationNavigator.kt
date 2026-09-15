package com.alothmany.wa.r7.navigation

import com.alothmany.wa.r7.extraction.ConversationVerificationResult
import com.alothmany.wa.r7.extraction.ConversationVerificationStatus

enum class NavigationAction { ACCEPT, REOPEN, RECOVER, REJECT }

data class NavigationDirective(
    val action: NavigationAction,
    val weakReopenCount: Int,
    val recoveryUsed: Boolean,
    val reason: String
)

class ConversationNavigator(private val maxWeakReopens: Int = 2) {
    private var weakReopens = 0
    private var recoveryUsed = false

    init {
        require(maxWeakReopens >= 0) { "maxWeakReopens must be >= 0" }
    }

    fun onVerification(result: ConversationVerificationResult): NavigationDirective = when (result.status) {
        ConversationVerificationStatus.PASS -> {
            val directive = NavigationDirective(NavigationAction.ACCEPT, weakReopens, recoveryUsed, result.reason)
            reset()
            directive
        }
        ConversationVerificationStatus.HARD_REJECT -> {
            val directive = NavigationDirective(NavigationAction.REJECT, weakReopens, recoveryUsed, result.reason)
            reset()
            directive
        }
        ConversationVerificationStatus.WEAK_MISMATCH -> {
            when {
                weakReopens < maxWeakReopens -> {
                    weakReopens++
                    NavigationDirective(NavigationAction.REOPEN, weakReopens, recoveryUsed, result.reason)
                }
                !recoveryUsed -> {
                    recoveryUsed = true
                    NavigationDirective(NavigationAction.RECOVER, weakReopens, true, result.reason)
                }
                else -> {
                    NavigationDirective(NavigationAction.REJECT, weakReopens, true, result.reason)
                }
            }
        }
    }

    fun reset() {
        weakReopens = 0
        recoveryUsed = false
    }
}
