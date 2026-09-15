package com.alothmany.wa.r7.observe

enum class ScreenType { CHAT_LIST, GROUPS_LIST, CONVERSATION, SEARCH, INVITE, COMMUNITY, UNKNOWN }
enum class RiskLevel { NORMAL, HIGH }
enum class StabilityStatus { COLLECTING, STABLE, RESET, UNSTABLE_TIMEOUT }

data class ScreenObservation(
    val type: ScreenType,
    val confidence: Double,
    val evidence: List<String>,
    val packageName: String,
    val windowId: Int,
    val viewportSignature: String,
    val observedAt: Long
)

data class StabilityResult(
    val status: StabilityStatus,
    val compatibleCount: Int,
    val requiredCount: Int,
    val reason: String
)
