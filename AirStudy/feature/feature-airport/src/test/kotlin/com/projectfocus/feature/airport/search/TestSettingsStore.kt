package com.projectfocus.feature.airport.search

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.projectfocus.core.datastore.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File




































class TestSettingsStore(folder: File) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val file: File = File(folder, "test_settings.preferences_pb")

    private val store: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })

    val repository: SettingsRepository = SettingsRepository(store)

    fun close() {
        scope.cancel()
    }
}
