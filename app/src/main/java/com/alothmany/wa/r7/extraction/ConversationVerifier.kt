package com.alothmany.wa.r7.extraction

enum class ConversationVerificationStatus { PASS, HARD_REJECT, WEAK_MISMATCH }

data class ConversationEvidence(
    val expectedPackage: String,
    val observedPackage: String,
    val expectedTitle: String,
    val observedTitle: String?,
    val expectedIdentitySignature: String? = null,
    val observedIdentitySignature: String? = null
)

data class VerifiedConversation(
    val packageName: String,
    val normalizedTitle: String,
    val identitySignature: String?
)

data class ConversationVerificationResult(
    val status: ConversationVerificationStatus,
    val reason: String,
    val verifiedConversation: VerifiedConversation? = null
)

class ConversationVerifier {
    fun verify(evidence: ConversationEvidence): ConversationVerificationResult {
        val expectedPackage = evidence.expectedPackage.trim()
        val observedPackage = evidence.observedPackage.trim()
        if (expectedPackage.isBlank() || observedPackage != expectedPackage) {
            return ConversationVerificationResult(
                ConversationVerificationStatus.HARD_REJECT,
                "package-mismatch"
            )
        }

        val expectedTitle = normalizeTitle(evidence.expectedTitle)
        val observedTitle = normalizeTitle(evidence.observedTitle.orEmpty())
        if (expectedTitle.isBlank() || observedTitle.isBlank() || observedTitle != expectedTitle) {
            return ConversationVerificationResult(
                ConversationVerificationStatus.HARD_REJECT,
                "exact-title-mismatch"
            )
        }

        val expectedIdentity = evidence.expectedIdentitySignature?.trim().orEmpty()
        if (expectedIdentity.isNotBlank()) {
            val observedIdentity = evidence.observedIdentitySignature?.trim().orEmpty()
            if (observedIdentity.isBlank() || observedIdentity != expectedIdentity) {
                return ConversationVerificationResult(
                    ConversationVerificationStatus.WEAK_MISMATCH,
                    "identity-hint-mismatch"
                )
            }
        }

        return ConversationVerificationResult(
            ConversationVerificationStatus.PASS,
            "exact-package-title-verified",
            VerifiedConversation(observedPackage, observedTitle, evidence.observedIdentitySignature?.trim()?.takeIf { it.isNotBlank() })
        )
    }

    companion object {
        fun normalizeTitle(value: String): String = value
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }
}
