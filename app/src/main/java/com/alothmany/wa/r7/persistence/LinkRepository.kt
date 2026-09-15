package com.alothmany.wa.r7.persistence

import androidx.room.withTransaction
import com.alothmany.wa.data.AppDatabase
import com.alothmany.wa.data.CommitResult
import com.alothmany.wa.data.JobLinkEntity
import com.alothmany.wa.data.LinkEntity
import com.alothmany.wa.data.ObservedUrl
import com.alothmany.wa.data.OccurrenceEntity
import com.alothmany.wa.domain.UrlNormalizer
import java.net.URI
import java.security.MessageDigest

class LinkRepository(private val db: AppDatabase) {
    suspend fun commitBatch(observed: List<ObservedUrl>): CommitResult {
        require(observed.size <= 128) { "Batch exceeds 128 observations" }
        if (observed.isEmpty()) return CommitResult(0, 0)
        var newLinks = 0
        var newOccurrences = 0
        db.withTransaction {
            for (item in observed) {
                val normalized = UrlNormalizer.normalize(item.rawUrl) ?: continue
                val occurrenceId = sha256("${item.jobId}|${item.groupId}|${item.messageFingerprint}|$normalized")
                val insertedOccurrence = db.occurrenceDao().insertIgnore(
                    OccurrenceEntity(
                        id = occurrenceId,
                        normalizedUrl = normalized,
                        groupId = item.groupId,
                        jobId = item.jobId,
                        messageFingerprint = item.messageFingerprint,
                        observedAt = item.observedAt
                    )
                )
                if (insertedOccurrence == -1L) continue
                val domain = runCatching { URI(normalized).host.orEmpty() }.getOrDefault("")
                db.linkDao().insertIgnore(
                    LinkEntity(
                        normalizedUrl = normalized,
                        rawRepresentative = item.rawUrl,
                        domain = domain,
                        firstSeenAt = item.observedAt,
                        lastSeenAt = item.observedAt,
                        occurrenceCount = 0
                    )
                )
                if (db.jobLinkDao().insertIgnore(JobLinkEntity(item.jobId, normalized, item.observedAt)) != -1L) {
                    newLinks++
                }
                db.linkDao().increment(normalized, item.observedAt)
                newOccurrences++
            }
        }
        return CommitResult(newLinks, newOccurrences)
    }

    suspend fun countLinksForJob(jobId: String): Long = db.jobLinkDao().countForJob(jobId)
    suspend fun countOccurrencesForJob(jobId: String): Long = db.occurrenceDao().countForJob(jobId)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
