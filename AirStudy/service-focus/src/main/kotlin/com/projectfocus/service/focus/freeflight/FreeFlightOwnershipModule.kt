package com.projectfocus.service.focus.freeflight

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class FreeFlightOwnershipModule {
    @Binds abstract fun runtimeOwnership(adapter: SharedFlightRuntimeOwnership): FlightSessionOwnership
}
