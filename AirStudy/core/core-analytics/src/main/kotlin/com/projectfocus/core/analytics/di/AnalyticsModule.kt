package com.projectfocus.core.analytics.di

import com.projectfocus.core.analytics.Analytics
import com.projectfocus.core.analytics.DebugAnalytics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

























@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyticsModule {

    @Binds
    @Singleton
    abstract fun bindAnalytics(impl: DebugAnalytics): Analytics
}
