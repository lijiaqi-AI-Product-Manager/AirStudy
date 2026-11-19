package com.projectfocus.feature.onboarding.di

import com.projectfocus.feature.onboarding.permission.AndroidPermissionPlatform
import com.projectfocus.feature.onboarding.permission.PermissionPlatform
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


































@Module
@InstallIn(SingletonComponent::class)
abstract class PermissionModule {

    @Binds
    @Singleton
    abstract fun bindPermissionPlatform(
        impl: AndroidPermissionPlatform,
    ): PermissionPlatform
}
