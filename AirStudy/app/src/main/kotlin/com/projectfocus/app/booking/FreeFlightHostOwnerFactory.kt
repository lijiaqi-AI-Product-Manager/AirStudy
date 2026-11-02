package com.projectfocus.app.booking

import android.content.Context
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Application-owned preparation identity; never reconstructed from Bundle or screen state. */
@Singleton
class FreeFlightHostOwnerFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ownership: SharedFlightRuntimeOwnership,
) {
    val invalidations get() = ownership.invalidations
    val currentHosts get() = ownership.currentHosts
    suspend fun open(): FreeFlightOwnerToken {
        val app = context.applicationContext as? ProjectFocusApp
            ?: throw IllegalStateException("The real account bootstrap owner is unavailable")
        app.awaitInitialAccountRestore()
        return ownership.openHost()
    }

    /** Call for explicit preparation cancellation; not rotation/onStop or detached Pending UI. */
    suspend fun invalidate(owner: FreeFlightOwnerToken) = ownership.invalidateHost(owner)

    suspend fun isCurrent(owner: FreeFlightOwnerToken): Boolean = ownership.isHostCurrent(owner)
}
