package com.projectfocus.feature.record

import com.projectfocus.service.focus.session.NoOpWidgetPush
import com.projectfocus.service.focus.session.WidgetPush
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers


@Module
@InstallIn(SingletonComponent::class)
object RecordTestBindings {

    @Provides
    @javax.inject.Singleton
    fun provideRecordRuntime(owners: com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeOwners) =
        com.projectfocus.service.focus.freeflight.runtime.FreeFlightRuntimeRegistry(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate), owners)

    @Provides
    @javax.inject.Singleton
    fun provideRecordPreparationRuntime() =
        com.projectfocus.service.focus.freeflight.runtime.FreeFlightPreparationRuntime(
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate))

    @Provides
    fun provideWidgetPush(): WidgetPush = NoOpWidgetPush

    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
