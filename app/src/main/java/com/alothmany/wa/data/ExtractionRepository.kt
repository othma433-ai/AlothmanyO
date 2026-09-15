package com.alothmany.wa.data

import com.alothmany.wa.r7.persistence.CheckpointRepository
import com.alothmany.wa.r7.persistence.LinkRepository

private const val MAX_BATCH = 128

data class ObservedUrl(
    val rawUrl: String,
    val groupId: String,
    val jobId: String,
    val messageFingerprint: String,
    val observedAt: Long = System.currentTimeMillis()
)

data class CommitResult(val newLinks: Int, val newOccurrences: Int)

/**
 * Backward-compatible facade. R7-M4 production paths use the focused repositories directly.
 */
class ExtractionRepository(db: AppDatabase) {
    private val links = LinkRepository(db)
    private val checkpoints = CheckpointRepository(db)

    suspend fun commitBatch(observed: List<ObservedUrl>): CommitResult {
        require(observed.size <= MAX_BATCH) { "Batch exceeds $MAX_BATCH observations" }
        return links.commitBatch(observed)
    }

    suspend fun saveCheckpoint(checkpoint: CheckpointEntity) = checkpoints.save(checkpoint)
    suspend fun checkpoint(jobId: String, groupId: String) = checkpoints.get(jobId, groupId)
}
