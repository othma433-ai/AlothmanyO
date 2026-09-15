package com.alothmany.wa.r7.sync

enum class DiscoveryProvenance { GROUPS_FILTER, CHAT_GRABBER }
enum class SyncAction { SCROLL_FORWARD, COMPLETE, RECOVER }
enum class SyncState { CAPTURE_VISIBLE_GROUPS, VERIFY_LIST_PROGRESS, VERIFY_TERMINAL_CONSENSUS, COMPLETE, RECOVERING }

data class GroupCandidate(
    val stableKey: String,
    val displayName: String,
    val provenance: DiscoveryProvenance,
    val identityHint: String = ""
)

data class CaptureResult(
    val newGroups: List<GroupCandidate>,
    val totalSeen: Int,
    val nextAction: SyncAction,
    val viewportSignature: String
)

data class SyncDirective(
    val state: SyncState,
    val action: SyncAction,
    val reason: String,
    val terminalConfirmations: Int
)

internal fun stableGroupKey(displayName: String): String = displayName
    .trim()
    .replace(Regex("\\s+"), " ")
    .lowercase()
