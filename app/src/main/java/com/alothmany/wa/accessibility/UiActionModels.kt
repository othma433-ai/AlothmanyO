package com.alothmany.wa.accessibility

import com.alothmany.wa.domain.InviteUiState
import com.alothmany.wa.domain.SendVerification
import com.alothmany.wa.r7.join.JoinActionTarget
import com.alothmany.wa.r7.join.JoinTargetResolutionStatus


data class PreparedInviteAction(
    val state: InviteUiState,
    val target: JoinActionTarget? = null,
    val resolutionStatus: JoinTargetResolutionStatus? = null,
    val detail: String,
    val evidence: String? = null
)

data class InviteActionOutcome(
    val success: Boolean,
    val finalState: InviteUiState,
    val detail: String,
    val evidence: String? = null
)

data class PublishOutcome(
    val verification: SendVerification,
    val detail: String,
    val evidence: String? = null
) {
    val success: Boolean get() = verification == SendVerification.VERIFIED
}
