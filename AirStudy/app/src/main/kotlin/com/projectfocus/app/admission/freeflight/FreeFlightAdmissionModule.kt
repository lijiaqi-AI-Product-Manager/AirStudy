package com.projectfocus.app.admission.freeflight

import android.content.Context
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.core.network.di.NetworkCore
import com.projectfocus.core.network.freeflight.FreeFlightAdmissionGateway
import com.projectfocus.core.network.freeflight.HttpFreeFlightAdmissionGateway
import com.projectfocus.service.focus.freeflight.FreeFlightAdmission
import com.projectfocus.service.focus.freeflight.FreeFlightPreparationAdmission
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Real graph access for process-owned hosts and graph identity verification. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface FreeFlightAdmissionEntryPoint {
    fun actual(): OwnFreeFlightAdmission
    fun preparation(): FreeFlightPreparationAdmission
    fun formal(): FreeFlightAdmission
    fun identities(): FreeFlightAdmissionIdentityProvider
    fun ownership(): com.projectfocus.service.focus.freeflight.SharedFlightRuntimeOwnership
}

@Module
@InstallIn(SingletonComponent::class)
object FreeFlightAdmissionModule {
    @Provides @Singleton
    fun identities(real: SharedFreeFlightAdmissionIdentityProvider): FreeFlightAdmissionIdentityProvider = real

    @Provides @Singleton
    fun devices(@ApplicationContext context: Context): FreeFlightDeviceIdentityProvider = AndroidFreeFlightDeviceIdentity(context.applicationContext)

    @Provides @Singleton
    fun journal(@ApplicationContext context: Context): FreeFlightAdmissionJournal = FreeFlightAdmissionJournal(
        context.applicationContext.getSharedPreferences("freeflight_admission_journal_v1", Context.MODE_PRIVATE),
    )

    @Provides @Singleton
    fun gateway(network: NetworkCore): FreeFlightAdmissionGateway = HttpFreeFlightAdmissionGateway(network.baseClient, network.baseUrl)

    @Provides @Singleton
    fun actual(
        @ApplicationContext context: Context,
        gateway: FreeFlightAdmissionGateway,
        identities: FreeFlightAdmissionIdentityProvider,
        devices: FreeFlightDeviceIdentityProvider,
        journal: FreeFlightAdmissionJournal,
    ): OwnFreeFlightAdmission {
        val app = context.applicationContext as? ProjectFocusApp
            ?: error("The real Application admission scope is unavailable")
        return OwnFreeFlightAdmission(gateway, identities, devices, journal, app.admissionApplicationScope)
    }

    @Provides @Singleton
    fun preparation(real: OwnFreeFlightAdmission): FreeFlightPreparationAdmission = real

    @Provides @Singleton
    fun formal(real: OwnFreeFlightAdmission): FreeFlightAdmission = real
}
