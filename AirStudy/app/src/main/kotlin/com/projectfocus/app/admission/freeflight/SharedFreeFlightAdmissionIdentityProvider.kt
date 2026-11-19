package com.projectfocus.app.admission.freeflight

import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import javax.inject.Inject
import javax.inject.Singleton

/** The real capture/lease is suspend; no blocking Main bridge or snapshot-only check. */
@Singleton
class SharedFreeFlightAdmissionIdentityProvider @Inject constructor(
    private val ownership: SharedFlightRuntimeOwnership,
) : FreeFlightAdmissionIdentityProvider {
    override suspend fun capture(owner: FreeFlightOwnerToken): CapturedFreeFlightAdmissionIdentity? =
        ownership.captureHostAccount(owner)?.let { CapturedFreeFlightAdmissionIdentity(it.owner, it.account.token) }

    override suspend fun current(): CapturedFreeFlightAdmissionIdentity? =
        ownership.captureHostAccount()?.let { CapturedFreeFlightAdmissionIdentity(it.owner, it.account.token) }

    override suspend fun isCurrent(identity: CapturedFreeFlightAdmissionIdentity): Boolean =
        ownership.captureHostAccount(identity.owner)?.let { it.account.token == identity.bearer } == true
}
