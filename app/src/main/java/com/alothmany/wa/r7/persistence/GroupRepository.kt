package com.alothmany.wa.r7.persistence

import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.GroupEntity

class GroupRepository(private val db: AppDatabase) {
    suspend fun byPackage(targetPackage: String): List<GroupEntity> = db.groupDao().byPackage(targetPackage)
    suspend fun get(groupId: String): GroupEntity? = db.groupDao().get(groupId)
    suspend fun updateNewOnlyBoundary(groupId: String, boundary: String?, timestamp: Long) =
        db.groupDao().updateNewOnlyBoundary(groupId, boundary, timestamp)
}
