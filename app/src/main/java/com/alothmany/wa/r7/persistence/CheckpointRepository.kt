package com.alothmany.wa.r7.persistence

import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.CheckpointEntity

class CheckpointRepository(private val db: AppDatabase) {
    suspend fun save(checkpoint: CheckpointEntity) = db.checkpointDao().upsert(checkpoint)
    suspend fun get(jobId: String, groupId: String): CheckpointEntity? = db.checkpointDao().get(jobId, groupId)
    suspend fun delete(jobId: String, groupId: String) = db.checkpointDao().delete(jobId, groupId)
}
