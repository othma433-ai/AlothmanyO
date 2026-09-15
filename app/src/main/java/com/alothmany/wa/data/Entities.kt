package com.alothmany.wa.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val targetPackage: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val linksFound: Long = 0,
    val occurrences: Long = 0,
    val lastError: String? = null,
    val lastProgressAt: Long = 0
)

@Entity(
    tableName = "groups",
    indices = [Index(value = ["targetPackage", "displayName"], unique = true)]
)
data class GroupEntity(
    @PrimaryKey val id: String,
    val targetPackage: String,
    val displayName: String,
    val accessibilitySignature: String,
    val lastSeenAt: Long,
    val discoveryOrder: Long = 0,
    val classification: String = "UNKNOWN",
    val lastNewOnlyBoundary: String? = null
)

@Entity(tableName = "links")
data class LinkEntity(
    @PrimaryKey val normalizedUrl: String,
    val rawRepresentative: String,
    val domain: String,
    val firstSeenAt: Long,
    val lastSeenAt: Long,
    val occurrenceCount: Long = 0
)

@Entity(tableName = "job_links", primaryKeys = ["jobId", "normalizedUrl"], indices = [Index(value = ["normalizedUrl"])])
data class JobLinkEntity(
    val jobId: String,
    val normalizedUrl: String,
    val firstSeenAt: Long
)

@Entity(
    tableName = "occurrences",
    indices = [Index(value = ["groupId"]), Index(value = ["jobId"]), Index(value = ["normalizedUrl"])]
)
data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val normalizedUrl: String,
    val groupId: String,
    val jobId: String,
    val messageFingerprint: String,
    val observedAt: Long
)

@Entity(tableName = "checkpoints", primaryKeys = ["jobId", "groupId"])
data class CheckpointEntity(
    val jobId: String,
    val groupId: String,
    val phase: String,
    val scrollCount: Int,
    val lastWindowFingerprint: String?,
    val oldestEvidenceCount: Int,
    val linksFound: Long,
    val occurrences: Long,
    val updatedAt: Long
)

@Entity(tableName = "diagnostics", indices = [Index(value = ["timestamp"])])
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val severity: String,
    val component: String,
    val code: String,
    val message: String,
    val contextJson: String? = null
)

@Entity(tableName = "action_jobs", indices = [Index(value = ["updatedAt"])])
data class ActionJobEntity(
    @PrimaryKey val id: String,
    val type: String,
    val targetPackage: String,
    val status: String,
    val payloadText: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val totalItems: Int = 0,
    val completedItems: Int = 0,
    val failedItems: Int = 0,
    val lastError: String? = null,
    val lastProgressAt: Long = 0
)

@Entity(
    tableName = "action_items",
    primaryKeys = ["jobId", "itemId"],
    indices = [Index(value = ["jobId", "status"]), Index(value = ["updatedAt"])]
)
data class ActionItemEntity(
    val jobId: String,
    val itemId: String,
    val ordinal: Int,
    val target: String,
    val status: String = "PENDING",
    val detail: String? = null,
    val phase: String = "PREPARE",
    val resultState: String? = null,
    val lastEvidence: String? = null,
    val attempts: Int = 0,
    val verificationAttempts: Int = 0,
    val updatedAt: Long
)

@Entity(
    tableName = "extraction_queue_items",
    primaryKeys = ["jobId", "groupId"],
    indices = [
        Index(value = ["jobId", "status"]),
        Index(value = ["jobId", "ordinal"], unique = true)
    ]
)
data class ExtractionQueueItemEntity(
    val jobId: String,
    val groupId: String,
    val ordinal: Int,
    val targetPackage: String,
    val expectedTitle: String,
    val expectedIdentitySignature: String?,
    val discoveryProvenance: String,
    val newOnlyBoundaryAtStart: String?,
    val mode: String,
    val status: String = "PENDING",
    val retryCount: Int = 0,
    val lastState: String = "PENDING",
    val checkpointId: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
