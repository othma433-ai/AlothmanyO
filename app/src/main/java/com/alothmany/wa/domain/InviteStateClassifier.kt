package com.alothmany.wa.domain

enum class InviteUiState {
    DIRECT,
    APPROVAL,
    COMMUNITY,
    ALREADY_MEMBER,
    REQUEST_SENT,
    INVALID,
    UNKNOWN
}

object InviteStateClassifier {
    private val invalidTokens = listOf(
        "invite link has been reset", "invite link is invalid", "invite link is no longer valid",
        "couldn't get group info", "could not get group info", "تمت إعادة تعيين رابط الدعوة",
        "رابط الدعوة غير صالح", "لم يعد رابط الدعوة صالحًا", "تعذر الحصول على معلومات المجموعة"
    )
    private val requestSentTokens = listOf(
        "request sent", "requested to join", "request to join sent", "تم إرسال الطلب",
        "تم إرسال طلب الانضمام", "طلب الانضمام قيد المراجعة"
    )
    private val alreadyMemberTokens = listOf(
        "already a member", "you're in this group", "you are in this group", "أنت عضو بالفعل",
        "أنت في هذه المجموعة"
    )
    private val approvalActions = listOf("request to join", "طلب الانضمام", "إرسال طلب انضمام")
    private val communityActions = listOf("join community", "view community", "انضم إلى المجتمع", "عرض المجتمع")
    private val directActions = listOf("join group", "join chat", "انضمام إلى المجموعة", "الانضمام إلى المجموعة", "انضمام")

    fun classify(visibleTexts: List<String>, clickableLabels: List<String>): InviteUiState {
        val visible = visibleTexts.map(::norm)
        val clickable = clickableLabels.map(::norm)
        if (containsAny(visible, invalidTokens)) return InviteUiState.INVALID
        if (containsAny(visible, requestSentTokens)) return InviteUiState.REQUEST_SENT
        if (containsAny(visible, alreadyMemberTokens)) return InviteUiState.ALREADY_MEMBER
        if (matchesAny(clickable, approvalActions)) return InviteUiState.APPROVAL
        if (matchesAny(clickable, communityActions)) return InviteUiState.COMMUNITY
        if (matchesAny(clickable, directActions)) return InviteUiState.DIRECT
        return InviteUiState.UNKNOWN
    }

    private fun containsAny(values: List<String>, tokens: List<String>): Boolean =
        values.any { value -> tokens.any { token -> value.contains(norm(token)) } }

    private fun matchesAny(values: List<String>, tokens: List<String>): Boolean =
        values.any { value -> tokens.any { token -> value == norm(token) || value.contains(norm(token)) } }

    private fun norm(value: String): String = value.trim().lowercase().replace(Regex("\\s+"), " ")
}
