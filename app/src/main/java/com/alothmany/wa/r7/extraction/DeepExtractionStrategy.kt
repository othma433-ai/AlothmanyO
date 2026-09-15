package com.alothmany.wa.r7.extraction

import com.alothmany.wa.domain.UrlNormalizer
import java.security.MessageDigest

data class MessageObservation(
    val fingerprint: String,
    val text: String
)

data class DeepExtractionCandidate(
    val rawUrl: String,
    val normalizedUrl: String,
    val conversationKey: String,
    val sourceMessageFingerprint: String,
    val contextualFingerprint: String,
    val occurrenceKey: String
)

class DeepExtractionStrategy {
    fun capture(conversationKey: String, messages: List<MessageObservation>): List<DeepExtractionCandidate> {
        require(conversationKey.isNotBlank()) { "conversationKey must not be blank" }
        val result = LinkedHashMap<String, DeepExtractionCandidate>()
        for (message in messages) {
            val messageFingerprint = message.fingerprint.trim()
            if (messageFingerprint.isBlank()) continue
            for (raw in extractRawCandidates(message.text)) {
                val normalized = UrlNormalizer.normalize(raw) ?: continue
                val contextual = sha256("$messageFingerprint|$normalized")
                val occurrenceKey = sha256("$conversationKey|$contextual|$normalized")
                result.putIfAbsent(
                    occurrenceKey,
                    DeepExtractionCandidate(
                        rawUrl = raw,
                        normalizedUrl = normalized,
                        conversationKey = conversationKey,
                        sourceMessageFingerprint = messageFingerprint,
                        contextualFingerprint = contextual,
                        occurrenceKey = occurrenceKey
                    )
                )
            }
        }
        return result.values.toList()
    }

    private fun extractRawCandidates(text: String): List<String> {
        val regex = Regex("""https?://[^\s<>"'\[\]{}]+""", RegexOption.IGNORE_CASE)
        val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
        return regex.findAll(text).map { it.value.trimEnd(*trailing) }.toList()
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
