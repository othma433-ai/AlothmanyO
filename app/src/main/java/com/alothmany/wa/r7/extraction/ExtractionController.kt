package com.alothmany.wa.r7.extraction

import com.alothmany.wa.accessibility.MessageSnapshot
import com.alothmany.wa.data.CheckpointEntity
import com.alothmany.wa.data.ObservedUrl
import com.alothmany.wa.r7.persistence.CheckpointRepository
import com.alothmany.wa.r7.persistence.LinkRepository

data class ExtractionProgress(
    val scrollCount: Int,
    val terminalEvidenceCount: Int,
    val linksFound: Long,
    val occurrences: Long,
    val windowFingerprint: String
)

data class DurableViewportResult(
    val progress: ExtractionProgress,
    val capturedOccurrences: Int
)

class ExtractionController(
    private val links: LinkRepository,
    private val checkpoints: CheckpointRepository,
    private val strategy: DeepExtractionStrategy = DeepExtractionStrategy()
) {
    suspend fun persistVerifiedViewport(
        jobId: String,
        groupId: String,
        conversationKey: String,
        messages: List<MessageSnapshot>,
        progress: ExtractionProgress,
        phase: String = "SCROLL_OLDER"
    ): DurableViewportResult {
        val candidates = strategy.capture(
            conversationKey,
            messages.map { MessageObservation(it.fingerprint, it.text) }
        )

        var newLinks = 0
        var newOccurrences = 0
        for (chunk in candidates.chunked(128)) {
            val result = links.commitBatch(
                chunk.map { candidate ->
                    ObservedUrl(
                        rawUrl = candidate.rawUrl,
                        groupId = groupId,
                        jobId = jobId,
                        messageFingerprint = candidate.sourceMessageFingerprint
                    )
                }
            )
            newLinks += result.newLinks
            newOccurrences += result.newOccurrences
        }

        // Checkpoint is intentionally saved only after every durable batch above commits.
        val next = progress.copy(
            linksFound = progress.linksFound + newLinks,
            occurrences = progress.occurrences + newOccurrences
        )
        checkpoints.save(
            CheckpointEntity(
                jobId = jobId,
                groupId = groupId,
                phase = phase,
                scrollCount = next.scrollCount,
                lastWindowFingerprint = next.windowFingerprint,
                oldestEvidenceCount = next.terminalEvidenceCount,
                linksFound = next.linksFound,
                occurrences = next.occurrences,
                updatedAt = System.currentTimeMillis()
            )
        )
        return DurableViewportResult(next, candidates.size)
    }
}
