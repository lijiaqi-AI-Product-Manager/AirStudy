package com.projectfocus.service.focus.di

import com.projectfocus.service.focus.session.MonotonicSource
import com.projectfocus.service.focus.session.RealMonotonicSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent


@Module
@InstallIn(SingletonComponent::class)
object SessionModule {

    @Provides
    fun provideMonotonicSource(): MonotonicSource = RealMonotonicSource
}
