package com.alothmany.wa.r7.persistence

import androidx.room.withTransaction
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.ExtractionQueueItemEntity
import com.alothmany.wa.data.GroupEntity

class OperationRepository(private val db: AppDatabase) {
    suspend fun ensureImmutableQueue(jobId: String, mode: String, groups: List<GroupEntity>): List<ExtractionQueueItemEntity> =
        db.withTransaction {
            val existing = db.extractionQueueDao().byJob(jobId)
            if (existing.isNotEmpty()) {
                require(existing.all { it.mode == mode }) { "existing immutable queue mode mismatch" }
                return@withTransaction existing
            }

            val now = System.currentTimeMillis()
            val planned = ImmutableQueuePlanner().freeze(
                jobId,
                mode,
                groups.map { group ->
                    QueueGroupSnapshot(
                        groupId = group.id,
                        targetPackage = group.targetPackage,
                        expectedTitle = group.displayName,
                        expectedIdentitySignature = group.accessibilitySignature,
                        discoveryProvenance = group.classification,
                        newOnlyBoundaryAtStart = group.lastNewOnlyBoundary
                    )
                }
            )
            val entities = planned.map { item ->
                ExtractionQueueItemEntity(
                    jobId = item.jobId,
                    groupId = item.groupId,
                    ordinal = item.ordinal,
                    targetPackage = item.targetPackage,
                    expectedTitle = item.expectedTitle,
                    expectedIdentitySignature = item.expectedIdentitySignature,
                    discoveryProvenance = item.discoveryProvenance,
                    newOnlyBoundaryAtStart = item.newOnlyBoundaryAtStart,
                    mode = item.mode,
                    status = item.status.name,
                    retryCount = item.retryCount,
                    lastState = item.lastState,
                    checkpointId = null,
                    createdAt = now,
                    updatedAt = now
                )
            }
            if (entities.isNotEmpty()) db.extractionQueueDao().insertAllIgnore(entities)
            db.extractionQueueDao().byJob(jobId)
        }

    suspend fun queue(jobId: String): List<ExtractionQueueItemEntity> = db.extractionQueueDao().byJob(jobId)

    suspend fun transition(
        jobId: String,
        groupId: String,
        to: ExtractionQueueStatus,
        lastState: String = to.name,
        checkpointId: String? = null,
        incrementRetry: Boolean = false
    ): Boolean = db.withTransaction {
        val current = db.extractionQueueDao().get(jobId, groupId) ?: return@withTransaction false
        val from = runCatching { ExtractionQueueStatus.valueOf(current.status) }.getOrNull() ?: return@withTransaction false
        if (!QueueTransitionPolicy.canTransition(from, to)) return@withTransaction false
        db.extractionQueueDao().updateMutableState(
            jobId = jobId,
            groupId = groupId,
            status = to.name,
            retryCount = current.retryCount + if (incrementRetry) 1 else 0,
            lastState = lastState,
            checkpointId = checkpointId ?: current.checkpointId,
            updatedAt = System.currentTimeMillis()
        )
        true
    }

    suspend fun reconcileCompletedCheckpoint(jobId: String, groupId: String): Boolean = db.withTransaction {
        val checkpoint = db.checkpointDao().get(jobId, groupId) ?: return@withTransaction false
        if (checkpoint.phase != "COMPLETE") return@withTransaction false
        val current = db.extractionQueueDao().get(jobId, groupId) ?: return@withTransaction false
        db.extractionQueueDao().updateMutableState(
            jobId,
            groupId,
            ExtractionQueueStatus.COMPLETED.name,
            current.retryCount,
            "COMPLETE_CHECKPOINT_RECONCILED",
            "$jobId:$groupId",
            System.currentTimeMillis()
        )
        true
    }
}
