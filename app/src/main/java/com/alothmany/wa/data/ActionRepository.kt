package com.alothmany.wa.data

import androidx.room.withTransaction
import java.security.MessageDigest
import java.util.UUID

class ActionRepository(private val db: AppDatabase) {
    suspend fun createJob(
        type: String,
        targetPackage: String,
        targets: List<String>,
        payloadText: String? = null,
        jobId: String = UUID.randomUUID().toString()
    ): String {
        require(type in setOf("SCAN_INVITES", "JOIN", "PUBLISH"))
        require(targets.isNotEmpty())
        val now = System.currentTimeMillis()
        db.withTransaction {
            db.actionJobDao().upsert(
                ActionJobEntity(
                    id = jobId,
                    type = type,
                    targetPackage = targetPackage,
                    status = "RUNNING",
                    payloadText = payloadText,
                    createdAt = now,
                    updatedAt = now,
                    totalItems = targets.size,
                    lastProgressAt = now
                )
            )
            targets.forEachIndexed { index, target ->
                db.actionItemDao().insertIgnore(
                    ActionItemEntity(
                        jobId = jobId,
                        itemId = sha256("$type|$targetPackage|$target"),
                        ordinal = index,
                        target = target,
                        phase = "PREPARE",
                        updatedAt = now
                    )
                )
            }
        }
        return jobId
    }

    suspend fun markAttemptStarted(item: ActionItemEntity, phase: String): ActionItemEntity {
        val updated = item.copy(
            status = "RUNNING",
            phase = phase,
            attempts = item.attempts + 1,
            updatedAt = System.currentTimeMillis()
        )
        persist(updated)
        return updated
    }

    suspend fun markSuccess(
        item: ActionItemEntity,
        phase: String,
        resultState: String,
        detail: String,
        evidence: String? = null
    ) {
        persist(
            item.copy(
                status = "SUCCESS",
                phase = phase,
                resultState = resultState,
                detail = detail,
                lastEvidence = evidence,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshJobCounts(item.jobId)
    }

    suspend fun markFailure(
        item: ActionItemEntity,
        phase: String,
        detail: String,
        resultState: String? = null,
        evidence: String? = null
    ) {
        persist(
            item.copy(
                status = "FAILED",
                phase = phase,
                resultState = resultState,
                detail = detail,
                lastEvidence = evidence,
                updatedAt = System.currentTimeMillis()
            )
        )
        refreshJobCounts(item.jobId)
    }

    suspend fun markProgress(
        item: ActionItemEntity,
        phase: String,
        detail: String? = item.detail,
        resultState: String? = item.resultState,
        evidence: String? = item.lastEvidence
    ): ActionItemEntity {
        val updated = item.copy(
            status = "RUNNING",
            phase = phase,
            detail = detail,
            resultState = resultState,
            lastEvidence = evidence,
            updatedAt = System.currentTimeMillis()
        )
        persist(updated)
        return updated
    }

    suspend fun markVerifyPending(
        item: ActionItemEntity,
        detail: String,
        evidence: String?
    ) {
        persist(
            item.copy(
                status = "VERIFY_PENDING",
                phase = "VERIFY_PENDING",
                detail = detail,
                lastEvidence = evidence,
                verificationAttempts = item.verificationAttempts,
                updatedAt = System.currentTimeMillis()
            )
        )
        touchJob(item.jobId)
    }

    suspend fun recordVerificationAttempt(item: ActionItemEntity, evidence: String?): ActionItemEntity {
        val updated = item.copy(
            status = "VERIFY_PENDING",
            phase = "VERIFY_PENDING",
            lastEvidence = evidence,
            verificationAttempts = item.verificationAttempts + 1,
            updatedAt = System.currentTimeMillis()
        )
        persist(updated)
        return updated
    }

    suspend fun setJobStatus(jobId: String, status: String, error: String? = null) {
        val job = db.actionJobDao().get(jobId) ?: return
        val now = System.currentTimeMillis()
        db.actionJobDao().upsert(
            job.copy(status = status, updatedAt = now, lastError = error, lastProgressAt = now)
        )
    }

    suspend fun refreshJobCounts(jobId: String) {
        val job = db.actionJobDao().get(jobId) ?: return
        val completed = db.actionItemDao().successCount(jobId)
        val failed = db.actionItemDao().failedCount(jobId)
        val now = System.currentTimeMillis()
        db.actionJobDao().upsert(
            job.copy(
                completedItems = completed,
                failedItems = failed,
                updatedAt = now,
                lastProgressAt = now
            )
        )
    }

    suspend fun verifyPendingCount(jobId: String): Int = db.actionItemDao().verifyPendingCount(jobId)

    private suspend fun persist(item: ActionItemEntity) {
        db.actionItemDao().updateState(
            jobId = item.jobId,
            itemId = item.itemId,
            status = item.status,
            detail = item.detail,
            phase = item.phase,
            resultState = item.resultState,
            lastEvidence = item.lastEvidence,
            attempts = item.attempts,
            verificationAttempts = item.verificationAttempts,
            updatedAt = item.updatedAt
        )
        touchJob(item.jobId)
    }

    private suspend fun touchJob(jobId: String) {
        val job = db.actionJobDao().get(jobId) ?: return
        val now = System.currentTimeMillis()
        db.actionJobDao().upsert(job.copy(updatedAt = now, lastProgressAt = now))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
