package com.projectfocus.service.focus.freeflight.runtime

import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/** Small shell seam; the production binding delegates every lease to the real shared owner. */
interface FreeFlightRuntimeOwners {
    val invalidations: Flow<FreeFlightOwnerToken>
    val currentHosts: Flow<FreeFlightOwnerToken?>
    suspend fun <T : Any> withPreparationOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T?
    suspend fun <T : Any> withCurrentOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T?
    suspend fun retireStopped(proof: FreeFlightStoppedDriver): Boolean
}
@Singleton
class SharedFreeFlightRuntimeOwners @Inject constructor(private val shared: SharedFlightRuntimeOwnership) : FreeFlightRuntimeOwners {
    override val invalidations get() = shared.invalidations
    override val currentHosts get() = shared.currentHosts
    override suspend fun <T : Any> withPreparationOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T? =
        shared.withPreparationOwner(sessionId, owner, action)
    override suspend fun <T : Any> withCurrentOwner(sessionId: String, owner: FreeFlightOwnerToken, action: suspend () -> T): T? =
        shared.withCurrentOwner(sessionId, owner, action)
    override suspend fun retireStopped(proof: FreeFlightStoppedDriver): Boolean = shared.retireStopped(proof)
}
@Module
@InstallIn(SingletonComponent::class)
abstract class FreeFlightRuntimeOwnersModule {
    @Binds abstract fun owners(implementation: SharedFreeFlightRuntimeOwners): FreeFlightRuntimeOwners
}
