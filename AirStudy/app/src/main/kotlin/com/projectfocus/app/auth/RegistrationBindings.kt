package com.projectfocus.app.auth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class RegistrationBindings {
    @Binds abstract fun gateway(implementation: OwnRegistrationGateway): RegistrationGateway
}
