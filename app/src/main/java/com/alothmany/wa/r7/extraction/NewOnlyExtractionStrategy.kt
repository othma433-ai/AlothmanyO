package com.alothmany.wa.r7.extraction

import com.alothmany.wa.domain.UrlNormalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

data class NewOnlyMessageEvidence(
    val fingerprint: String,
    val text: String
)

data class NewOnlyViewportEvidence(
    val windowFingerprint: String,
    val messageFingerprints: List<String>,
    val timestampContextHashes: Set<String>,
    val urlFingerprints: Set<String>,
    val unreadBoundaryVisible: Boolean,
    val terminalNoProgressCount: Int
)

data class NewOnlyHighWaterCheckpoint(
    val version: Int,
    val messageAnchors: List<String>,
    val viewportSignature: String,
    val timestampContextHashes: List<String>,
    val urlFingerprints: List<String>,
    val legacy: Boolean = false
)

enum class NewOnlyStopReason {
    NONE,
    PRIOR_CHECKPOINT_MATCH,
    UNREAD_BOUNDARY,
    HISTORICAL_NO_PROGRESS
}

data class NewOnlyDecision(
    val shouldStop: Boolean,
    val reason: NewOnlyStopReason,
    val anchorMatches: Int = 0,
    val timestampMatches: Int = 0,
    val urlMatches: Int = 0,
    val viewportMatch: Boolean = false
)

/**
 * Evidence-based boundary policy for NEW_ONLY extraction.
 *
 * The durable high-water checkpoint intentionally contains hashes/signatures only.
 * It never stores raw message text, raw timestamps, or raw URLs.
 */
class NewOnlyExtractionStrategy(
    private val noProgressThreshold: Int = 3,
    private val maxAnchors: Int = 12,
    private val maxTimestampContexts: Int = 8,
    private val maxUrlFingerprints: Int = 12
) {
    init {
        require(noProgressThreshold >= 2) { "noProgressThreshold must be >= 2" }
        require(maxAnchors > 0) { "maxAnchors must be > 0" }
    }

    fun observe(
        windowFingerprint: String,
        messages: List<NewOnlyMessageEvidence>,
        visibleTexts: List<String>,
        terminalNoProgressCount: Int
    ): NewOnlyViewportEvidence {
        val messageFingerprints = messages
            .map { it.fingerprint.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val timestampHashes = visibleTexts.asSequence()
            .map { it.trim() }
            .filter(::looksLikeTimestampContext)
            .map(::sha256)
            .distinct()
            .take(maxTimestampContexts)
            .toSet()

        val urlFingerprints = messages.asSequence()
            .flatMap { UrlNormalizer.extract(it.text).asSequence() }
            .map(::sha256)
            .distinct()
            .take(maxUrlFingerprints)
            .toSet()

        return NewOnlyViewportEvidence(
            windowFingerprint = windowFingerprint,
            messageFingerprints = messageFingerprints,
            timestampContextHashes = timestampHashes,
            urlFingerprints = urlFingerprints,
            unreadBoundaryVisible = visibleTexts.any(::isUnreadBoundaryLabel),
            terminalNoProgressCount = terminalNoProgressCount.coerceAtLeast(0)
        )
    }

    fun buildCheckpoint(evidence: NewOnlyViewportEvidence): NewOnlyHighWaterCheckpoint =
        NewOnlyHighWaterCheckpoint(
            version = 2,
            messageAnchors = evidence.messageFingerprints.distinct().takeLast(maxAnchors),
            viewportSignature = sha256(evidence.windowFingerprint),
            timestampContextHashes = evidence.timestampContextHashes.sorted().take(maxTimestampContexts),
            urlFingerprints = evidence.urlFingerprints.sorted().take(maxUrlFingerprints),
            legacy = false
        )

    fun evaluate(
        current: NewOnlyViewportEvidence,
        previous: NewOnlyHighWaterCheckpoint?
    ): NewOnlyDecision {
        if (current.unreadBoundaryVisible) {
            return NewOnlyDecision(true, NewOnlyStopReason.UNREAD_BOUNDARY)
        }

        if (previous != null && previous.messageAnchors.isNotEmpty()) {
            val currentAnchors = current.messageFingerprints.toSet()
            val anchorMatches = previous.messageAnchors.count { it in currentAnchors }
            val timestampMatches = previous.timestampContextHashes.count { it in current.timestampContextHashes }
            val urlMatches = previous.urlFingerprints.count { it in current.urlFingerprints }
            val viewportMatch = previous.viewportSignature.isNotBlank() &&
                previous.viewportSignature == sha256(current.windowFingerprint)
            val requiredAnchors = if (previous.messageAnchors.size >= 2) 2 else 1
            val contextualSupport = viewportMatch || timestampMatches > 0 || urlMatches > 0
            val strongMatch = anchorMatches >= requiredAnchors && (previous.legacy || contextualSupport)

            if (strongMatch) {
                return NewOnlyDecision(
                    shouldStop = true,
                    reason = NewOnlyStopReason.PRIOR_CHECKPOINT_MATCH,
                    anchorMatches = anchorMatches,
                    timestampMatches = timestampMatches,
                    urlMatches = urlMatches,
                    viewportMatch = viewportMatch
                )
            }
        }

        if (current.terminalNoProgressCount >= noProgressThreshold) {
            return NewOnlyDecision(true, NewOnlyStopReason.HISTORICAL_NO_PROGRESS)
        }

        return NewOnlyDecision(false, NewOnlyStopReason.NONE)
    }

    fun encode(checkpoint: NewOnlyHighWaterCheckpoint): String {
        if (checkpoint.legacy) return checkpoint.messageAnchors.joinToString(";")
        return listOf(
            "v2",
            encodeField(checkpoint.viewportSignature),
            encodeField(checkpoint.messageAnchors.joinToString("\n")),
            encodeField(checkpoint.timestampContextHashes.joinToString("\n")),
            encodeField(checkpoint.urlFingerprints.joinToString("\n"))
        ).joinToString("|")
    }

    fun decode(value: String?): NewOnlyHighWaterCheckpoint? {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (!raw.startsWith("v2|")) {
            val anchors = raw.split(';').map { it.trim() }.filter { it.isNotBlank() }.distinct()
            if (anchors.isEmpty()) return null
            return NewOnlyHighWaterCheckpoint(
                version = 1,
                messageAnchors = anchors,
                viewportSignature = "",
                timestampContextHashes = emptyList(),
                urlFingerprints = emptyList(),
                legacy = true
            )
        }

        val parts = raw.split('|')
        if (parts.size != 5 || parts[0] != "v2") return null
        return runCatching {
            NewOnlyHighWaterCheckpoint(
                version = 2,
                messageAnchors = decodeList(parts[2]),
                viewportSignature = decodeField(parts[1]),
                timestampContextHashes = decodeList(parts[3]),
                urlFingerprints = decodeList(parts[4]),
                legacy = false
            )
        }.getOrNull()
    }

    private fun decodeList(encoded: String): List<String> = decodeField(encoded)
        .split('\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

    private fun encodeField(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodeField(value: String): String = String(
        Base64.getUrlDecoder().decode(value),
        StandardCharsets.UTF_8
    )

    private fun isUnreadBoundaryLabel(value: String): Boolean {
        val normalized = value.trim().lowercase()
        if (normalized.isBlank()) return false
        return normalized.contains("unread message") ||
            normalized.contains("unread messages") ||
            normalized.contains("رسالة غير مقروءة") ||
            normalized.contains("رسائل غير مقروءة") ||
            normalized == "غير مقروءة"
    }

    private fun looksLikeTimestampContext(value: String): Boolean {
        if (value.isBlank() || value.length > 48) return false
        val normalized = value.trim().lowercase()
        if (normalized in setOf("today", "yesterday", "اليوم", "أمس", "امس")) return true
        if (Regex(".*\\b\\d{1,2}:\\d{2}(?:\\s?[ap]m)?\\b.*", RegexOption.IGNORE_CASE).matches(value)) return true
        if (Regex(".*\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b.*").matches(value)) return true
        return false
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
