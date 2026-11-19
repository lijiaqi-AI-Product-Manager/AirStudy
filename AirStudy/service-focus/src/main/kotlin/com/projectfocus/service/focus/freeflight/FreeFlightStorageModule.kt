package com.projectfocus.service.focus.freeflight

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FreeFlightStorageModule {
    @Binds @Singleton
    abstract fun archiveStore(implementation: RoomFreeFlightArchiveStore): FreeFlightArchiveStore

    @Binds @Singleton
    abstract fun timeSource(implementation: AndroidFreeFlightTimeSource): FreeFlightTimeSource
}
