package com.alothmany.wa.r7.sync

import java.security.MessageDigest

enum class GrabberClassification { GROUP, NOT_PROVEN, REJECTED }

data class GroupProbeEvidence(
    val title: String,
    val visibleLabels: List<String>,
    val packageName: String = ""
)

data class GrabberDecision(
    val classification: GrabberClassification,
    val candidate: GroupCandidate?,
    val identityHint: String,
    val reason: String
)

class GroupGrabberController {
    private val noiseTitles = setOf(
        "archived", "communities", "search", "settings", "new group", "new chat",
        "المؤرشفة", "المجتمعات", "بحث", "الإعدادات", "مجموعة جديدة", "دردشة جديدة"
    )

    private val strongMarkers = setOf(
        "group info", "group permissions", "exit group", "add members", "invite via link",
        "معلومات المجموعة", "أذونات المجموعة", "مغادرة المجموعة", "إضافة أعضاء", "الدعوة عبر رابط"
    )

    fun classify(evidence: GroupProbeEvidence): GrabberDecision {
        val title = evidence.title.trim()
        val normalizedTitle = stableGroupKey(title)
        if (normalizedTitle.isBlank() || normalizedTitle in noiseTitles) {
            return GrabberDecision(GrabberClassification.REJECTED, null, "", "navigation-noise-or-empty-title")
        }

        val normalizedLabels = evidence.visibleLabels
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        val matched = strongMarkers.filter { marker -> normalizedLabels.any { label -> label == marker || marker in label } }
        if (matched.isEmpty()) {
            return GrabberDecision(GrabberClassification.NOT_PROVEN, null, "", "no-explicit-group-marker")
        }

        val identityHint = sha256(
            listOf(evidence.packageName, normalizedTitle, matched.sorted().joinToString("|")).joinToString("|")
        )
        val candidate = GroupCandidate(
            stableKey = normalizedTitle,
            displayName = title,
            provenance = DiscoveryProvenance.CHAT_GRABBER,
            identityHint = identityHint
        )
        return GrabberDecision(GrabberClassification.GROUP, candidate, identityHint, "explicit-group-marker:${matched.joinToString(",")}")
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }
}
