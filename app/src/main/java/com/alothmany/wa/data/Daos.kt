package com.alothmany.wa.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface JobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE id = :id LIMIT 1")
    suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE status IN ('RUNNING','PAUSED','RECOVERING') ORDER BY updatedAt DESC")
    suspend fun unfinished(): List<JobEntity>

    @Query("SELECT * FROM jobs ORDER BY updatedAt DESC LIMIT 1")
    fun latestFlow(): Flow<JobEntity?>
}

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(group: GroupEntity)

    @Query("SELECT * FROM groups WHERE targetPackage = :targetPackage ORDER BY discoveryOrder ASC")
    suspend fun byPackage(targetPackage: String): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :id LIMIT 1")
    suspend fun get(id: String): GroupEntity?

    @Query("SELECT COUNT(*) FROM groups WHERE targetPackage = :targetPackage")
    suspend fun countByPackage(targetPackage: String): Long

    @Query("UPDATE groups SET lastNewOnlyBoundary = :boundary, lastSeenAt = :timestamp WHERE id = :id")
    suspend fun updateNewOnlyBoundary(id: String, boundary: String?, timestamp: Long)
}

@Dao
interface LinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(link: LinkEntity): Long

    @Query("UPDATE links SET lastSeenAt = :lastSeenAt, occurrenceCount = occurrenceCount + 1 WHERE normalizedUrl = :url")
    suspend fun increment(url: String, lastSeenAt: Long)

    @Query("SELECT COUNT(*) FROM links")
    suspend fun count(): Long

    @Query("SELECT * FROM links ORDER BY lastSeenAt DESC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<LinkEntity>
}

@Dao
interface JobLinkDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(link: JobLinkEntity): Long

    @Query("SELECT COUNT(*) FROM job_links WHERE jobId = :jobId")
    suspend fun countForJob(jobId: String): Long
}

@Dao
interface OccurrenceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(occurrence: OccurrenceEntity): Long

    @Query("SELECT COUNT(*) FROM occurrences")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM occurrences WHERE jobId = :jobId")
    suspend fun countForJob(jobId: String): Long

    @Query("SELECT * FROM occurrences ORDER BY observedAt ASC LIMIT :limit OFFSET :offset")
    suspend fun page(limit: Int, offset: Int): List<OccurrenceEntity>
}

@Dao
interface CheckpointDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(checkpoint: CheckpointEntity)

    @Query("SELECT * FROM checkpoints WHERE jobId = :jobId AND groupId = :groupId LIMIT 1")
    suspend fun get(jobId: String, groupId: String): CheckpointEntity?

    @Query("DELETE FROM checkpoints WHERE jobId = :jobId AND groupId = :groupId")
    suspend fun delete(jobId: String, groupId: String)
}

@Dao
interface DiagnosticDao {
    @Insert
    suspend fun insert(event: DiagnosticEventEntity)

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC LIMIT :limit")
    suspend fun latest(limit: Int = 250): List<DiagnosticEventEntity>

    @Query("SELECT * FROM diagnostics ORDER BY timestamp DESC LIMIT :limit")
    fun latestFlow(limit: Int): Flow<List<DiagnosticEventEntity>>
}

@Dao
interface ActionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(job: ActionJobEntity)

    @Query("SELECT * FROM action_jobs WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ActionJobEntity?

    @Query("SELECT * FROM action_jobs WHERE status IN ('RUNNING','PAUSED','RECOVERING') ORDER BY updatedAt DESC")
    suspend fun unfinished(): List<ActionJobEntity>

    @Query("SELECT * FROM action_jobs ORDER BY updatedAt DESC LIMIT 1")
    fun latestFlow(): Flow<ActionJobEntity?>
}

@Dao
interface ActionItemDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(item: ActionItemEntity): Long

    @Query("SELECT * FROM action_items WHERE jobId = :jobId ORDER BY ordinal ASC")
    suspend fun byJob(jobId: String): List<ActionItemEntity>

    @Query("SELECT * FROM action_items WHERE jobId = :jobId ORDER BY ordinal ASC LIMIT :limit OFFSET :offset")
    suspend fun pageForJob(jobId: String, limit: Int, offset: Int): List<ActionItemEntity>

    @Query("SELECT * FROM action_items WHERE jobId = :jobId AND status NOT IN ('SUCCESS','SKIPPED') ORDER BY ordinal ASC")
    suspend fun pendingForJob(jobId: String): List<ActionItemEntity>

    @Query("UPDATE action_items SET status = :status, detail = :detail, phase = :phase, resultState = :resultState, lastEvidence = :lastEvidence, attempts = :attempts, verificationAttempts = :verificationAttempts, updatedAt = :updatedAt WHERE jobId = :jobId AND itemId = :itemId")
    suspend fun updateState(
        jobId: String,
        itemId: String,
        status: String,
        detail: String?,
        phase: String,
        resultState: String?,
        lastEvidence: String?,
        attempts: Int,
        verificationAttempts: Int,
        updatedAt: Long
    )

    @Query("SELECT COUNT(*) FROM action_items WHERE jobId = :jobId AND status = 'SUCCESS'")
    suspend fun successCount(jobId: String): Int

    @Query("SELECT COUNT(*) FROM action_items WHERE jobId = :jobId AND status = 'FAILED'")
    suspend fun failedCount(jobId: String): Int

    @Query("SELECT COUNT(*) FROM action_items WHERE jobId = :jobId AND status = 'VERIFY_PENDING'")
    suspend fun verifyPendingCount(jobId: String): Int
}


@Dao
interface ExtractionQueueDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnore(items: List<ExtractionQueueItemEntity>): List<Long>

    @Query("SELECT * FROM extraction_queue_items WHERE jobId = :jobId ORDER BY ordinal ASC")
    suspend fun byJob(jobId: String): List<ExtractionQueueItemEntity>

    @Query("SELECT * FROM extraction_queue_items WHERE jobId = :jobId AND groupId = :groupId LIMIT 1")
    suspend fun get(jobId: String, groupId: String): ExtractionQueueItemEntity?

    @Query("SELECT COUNT(*) FROM extraction_queue_items WHERE jobId = :jobId")
    suspend fun countForJob(jobId: String): Int

    @Query("UPDATE extraction_queue_items SET status = :status, retryCount = :retryCount, lastState = :lastState, checkpointId = :checkpointId, updatedAt = :updatedAt WHERE jobId = :jobId AND groupId = :groupId")
    suspend fun updateMutableState(
        jobId: String,
        groupId: String,
        status: String,
        retryCount: Int,
        lastState: String,
        checkpointId: String?,
        updatedAt: Long
    )
}
