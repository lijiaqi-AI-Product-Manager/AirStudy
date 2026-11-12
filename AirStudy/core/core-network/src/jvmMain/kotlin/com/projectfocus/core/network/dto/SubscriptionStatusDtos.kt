package com.projectfocus.core.network.dto

import kotlinx.serialization.Serializable







@Serializable
data class SubscriptionStatusSnapshot(
    val isActive: Boolean,
    val isPremium: Boolean,
    val remainingDays: Int,
    val expiresAt: String?,
) {
    /** Source qualification, independent of the own HTTP/storage adapter. */
    val isEligible: Boolean get() = isActive && isPremium && remainingDays > 0
}
