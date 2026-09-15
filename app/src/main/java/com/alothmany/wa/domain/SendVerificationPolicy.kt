package com.alothmany.wa.domain

enum class SendVerification {
    VERIFIED,
    VERIFY_PENDING,
    NOT_SENT
}

enum class PublishRecoveryAction {
    VERIFY_ONLY,
    MAY_SEND,
    DONE
}

object SendVerificationPolicy {
    fun evaluate(
        composerCleared: Boolean,
        beforeExactCount: Int,
        afterExactCount: Int,
        uiAcknowledged: Boolean
    ): SendVerification {
        if (!composerCleared) return SendVerification.NOT_SENT
        if (afterExactCount > beforeExactCount) return SendVerification.VERIFIED
        val transitionEvidence = composerCleared || uiAcknowledged
        return if (transitionEvidence) SendVerification.VERIFY_PENDING else SendVerification.NOT_SENT
    }
}

object ActionRecoveryPolicy {
    fun forPublish(phase: String): PublishRecoveryAction = when (phase.trim().uppercase()) {
        "SUCCESS", "SKIPPED" -> PublishRecoveryAction.DONE
        "SEND_FENCE_ARMED", "SEND_CLICKED", "VERIFY_PENDING" -> PublishRecoveryAction.VERIFY_ONLY
        else -> PublishRecoveryAction.MAY_SEND
    }
}
