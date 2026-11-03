package com.projectfocus.app.freeflight

import android.content.Context
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.core.datastore.SettingsRepository
import com.projectfocus.feature.home.map.HmsFreeFlightLandingDistance
import com.projectfocus.service.focus.freeflight.*
import com.projectfocus.service.focus.freeflight.runtime.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.EntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FreeFlightApplicationScope

@EntryPoint
@InstallIn(SingletonComponent::class)
interface FreeFlightRuntimeEntryPoint {
    fun repository(): FreeFlightSessionRepository
    fun registry(): FreeFlightRuntimeRegistry
    fun maps(): FreeFlightMapRuntime
    fun presence(): FreeFlightMapPresence
    fun arrival(): FreeFlightArrivalCoordinator
    fun owners(): SharedFlightRuntimeOwnership
    fun operations(): FreeFlightServiceOperations
    fun preparations(): RetainedFreeFlightPreparations
    fun preparationRuntime(): FreeFlightPreparationRuntime
    fun preparationOperations(): FreeFlightPreparationServiceOperations
}

/** Every mode1 runtime dependency is a real production adapter, owned by this Application. */
@Module
@InstallIn(SingletonComponent::class)
object FreeFlightRuntimeModule {
    @Provides @Singleton @FreeFlightApplicationScope
    fun scope(@ApplicationContext context: Context): CoroutineScope =
        (context.applicationContext as ProjectFocusApp).admissionApplicationScope

    @Provides @Singleton
    fun presence(real: FreeFlightMapRuntime): FreeFlightMapPresence = real

    @Provides @Singleton
    fun landingDistance(real: HmsFreeFlightLandingDistance): FreeFlightLandingDistance = real

    @Provides @Singleton
    fun arrival(real: FreeFlightMapRuntime) = FreeFlightArrivalCoordinator(real)

    @Provides @Singleton
    fun registry(@FreeFlightApplicationScope scope: CoroutineScope, owners: FreeFlightRuntimeOwners) = FreeFlightRuntimeRegistry(scope, owners)

    @Provides @Singleton
    fun preparationRuntime(@FreeFlightApplicationScope scope: CoroutineScope) = FreeFlightPreparationRuntime(scope)

    @Provides @Singleton
    fun operations(@ApplicationContext context: Context, settings: SettingsRepository, owners: SharedFlightRuntimeOwnership,
                   registry: FreeFlightRuntimeRegistry, repository: Provider<FreeFlightSessionRepository>, arrival: FreeFlightArrivalCoordinator) =
        FreeFlightServiceOperations(context, settings, owners, registry, repository, arrival)

    @Provides @Singleton
    fun effects(operations: FreeFlightServiceOperations): FreeFlightRuntimeEffects = LeaseBoundFreeFlightRuntimeEffects(operations)

    @Provides @Singleton
    fun repository(store: FreeFlightArchiveStore, time: AndroidFreeFlightTimeSource, admission: FreeFlightAdmission,
                   owners: SharedFlightRuntimeOwnership, distance: FreeFlightLandingDistance,
                   effects: FreeFlightRuntimeEffects, maps: FreeFlightMapPresence, @FreeFlightApplicationScope scope: CoroutineScope) =
        FreeFlightSessionRepository(store, time, admission, owners, distance, effects, maps, scope)
}
