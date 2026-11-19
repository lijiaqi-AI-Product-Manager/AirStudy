package com.projectfocus.feature.airport.di

import com.projectfocus.feature.airport.search.PinyinProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object AirportSearchModule {












    @Provides
    @AirportCompute
    fun provideAirportComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    fun providePinyinProvider(): PinyinProvider = PinyinProvider.NONE
















    @Provides
    @AirportFrameDebounceMs
    fun provideFrameDebounceMs(): Long = 16L
}








@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AirportCompute

/** 限定「关键词帧对齐时长」——见 [AirportSearchModule.provideFrameDebounceMs] */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AirportFrameDebounceMs
