package com.alothmany.wa.r7.persistence

enum class ExtractionQueueStatus {
    PENDING,
    OPENING,
    VERIFYING,
    EXTRACTING,
    PAUSED,
    COMPLETED,
    FAILED
}

data class QueueGroupSnapshot(
    val groupId: String,
    val targetPackage: String,
    val expectedTitle: String,
    val expectedIdentitySignature: String?,
    val discoveryProvenance: String,
    val newOnlyBoundaryAtStart: String?
)

data class ImmutableQueueItem(
    val jobId: String,
    val groupId: String,
    val ordinal: Int,
    val targetPackage: String,
    val expectedTitle: String,
    val expectedIdentitySignature: String?,
    val discoveryProvenance: String,
    val newOnlyBoundaryAtStart: String?,
    val mode: String,
    val status: ExtractionQueueStatus = ExtractionQueueStatus.PENDING,
    val retryCount: Int = 0,
    val lastState: String = ExtractionQueueStatus.PENDING.name
)

class ImmutableQueuePlanner {
    fun freeze(jobId: String, mode: String, selected: List<QueueGroupSnapshot>): List<ImmutableQueueItem> {
        require(jobId.isNotBlank()) { "jobId must not be blank" }
        require(mode == "DEEP" || mode == "NEW_ONLY") { "unsupported extraction mode" }
        val seen = LinkedHashSet<String>()
        val frozen = ArrayList<ImmutableQueueItem>()
        for (group in selected) {
            val id = group.groupId.trim()
            val title = group.expectedTitle.trim().replace(Regex("\\s+"), " ")
            if (id.isBlank() || title.isBlank() || !seen.add(id)) continue
            frozen += ImmutableQueueItem(
                jobId = jobId,
                groupId = id,
                ordinal = frozen.size,
                targetPackage = group.targetPackage.trim(),
                expectedTitle = title,
                expectedIdentitySignature = group.expectedIdentitySignature?.trim()?.takeIf { it.isNotBlank() },
                discoveryProvenance = group.discoveryProvenance.trim(),
                newOnlyBoundaryAtStart = group.newOnlyBoundaryAtStart,
                mode = mode
            )
        }
        return frozen.toList()
    }
}

object QueueTransitionPolicy {
    private val allowed = mapOf(
        ExtractionQueueStatus.PENDING to setOf(ExtractionQueueStatus.OPENING, ExtractionQueueStatus.PAUSED, ExtractionQueueStatus.FAILED),
        ExtractionQueueStatus.OPENING to setOf(ExtractionQueueStatus.VERIFYING, ExtractionQueueStatus.PAUSED, ExtractionQueueStatus.FAILED),
        ExtractionQueueStatus.VERIFYING to setOf(ExtractionQueueStatus.EXTRACTING, ExtractionQueueStatus.OPENING, ExtractionQueueStatus.PAUSED, ExtractionQueueStatus.FAILED),
        ExtractionQueueStatus.EXTRACTING to setOf(ExtractionQueueStatus.OPENING, ExtractionQueueStatus.COMPLETED, ExtractionQueueStatus.PAUSED, ExtractionQueueStatus.FAILED),
        ExtractionQueueStatus.PAUSED to setOf(ExtractionQueueStatus.OPENING, ExtractionQueueStatus.VERIFYING, ExtractionQueueStatus.EXTRACTING, ExtractionQueueStatus.FAILED),
        ExtractionQueueStatus.COMPLETED to emptySet(),
        ExtractionQueueStatus.FAILED to setOf(ExtractionQueueStatus.OPENING)
    )

    fun canTransition(from: ExtractionQueueStatus, to: ExtractionQueueStatus): Boolean =
        from == to || to in allowed.getValue(from)
}
