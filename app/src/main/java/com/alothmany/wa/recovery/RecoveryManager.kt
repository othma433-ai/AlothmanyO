package com.alothmany.wa.recovery

import com.alothmany.wa.data.ActionJobEntity
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.JobEntity

sealed interface RecoverableWork {
    val updatedAt: Long
    data class Extraction(val job: JobEntity) : RecoverableWork { override val updatedAt: Long get() = job.updatedAt }
    data class Action(val job: ActionJobEntity) : RecoverableWork { override val updatedAt: Long get() = job.updatedAt }
}

class RecoveryManager(private val db: AppDatabase) {
    suspend fun latestRecoverable(): JobEntity? = db.jobDao().unfinished().firstOrNull()
    suspend fun latestActionRecoverable(): ActionJobEntity? = db.actionJobDao().unfinished().firstOrNull()

    suspend fun latestWork(): RecoverableWork? {
        val extraction = latestRecoverable()?.let(RecoverableWork::Extraction)
        val action = latestActionRecoverable()?.let(RecoverableWork::Action)
        return listOfNotNull(extraction, action).maxByOrNull { it.updatedAt }
    }
}
