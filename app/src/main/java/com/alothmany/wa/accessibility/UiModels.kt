package com.alothmany.wa.accessibility

data class MessageSnapshot(
    val text: String,
    val fingerprint: String
)

data class WindowSnapshot(
    val packageName: String,
    val chatTitle: String?,
    val windowFingerprint: String,
    val messages: List<MessageSnapshot>,
    val visibleTexts: List<String>
)

data class ScrollResult(
    val actionAccepted: Boolean,
    val progressed: Boolean,
    val beforeFingerprint: String,
    val afterFingerprint: String
)
