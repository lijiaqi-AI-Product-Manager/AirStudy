package com.projectfocus.feature.profile.settings

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWriteDispatchTest {
    @get:Rule val files = TemporaryFolder()
    private lateinit var main: TestDispatcher
    private val models = mutableListOf<ViewModelStore>()
    private val scopes = mutableListOf<CoroutineScope>()

    @Before fun holdMainQueue() {
        main = StandardTestDispatcher()
        Dispatchers.setMain(main)
    }

    @After fun closeOwners() {
        try {
            models.forEach { it.clear() }
            scopes.forEach { it.cancel() }
            main.scheduler.runCurrent()
            runBlocking(Dispatchers.IO) {
                withTimeout(5000) { scopes.forEach { it.coroutineContext[Job]?.join() } }
            }
        } finally { Dispatchers.resetMain() }
    }

    @Test fun selectedOrientationPersistsBeforeMainQueueIsReleased() {
        val repository = realRepository()
        val model = model(repository)
        val mainExecutions = AtomicInteger()
        model.viewModelScope.launch { mainExecutions.incrementAndGet() }
        model.set(PrefKey.ScreenOrientation, "portrait")
        val actual = runBlocking(Dispatchers.IO) {
            withTimeout(5000) { repository.observe(PrefKey.ScreenOrientation).first { it == "portrait" } }
        }
        assertEquals("portrait", actual)
        assertEquals("The Main queue was held throughout the durable write", 0, mainExecutions.get())
    }

    @Test fun resetPersistsBeforeMainQueueIsReleased() {
        val repository = realRepository()
        runBlocking(Dispatchers.IO) { repository.write(PrefKey.ThemeMode, "night") }
        val model = model(repository)
        val mainExecutions = AtomicInteger()
        model.viewModelScope.launch { mainExecutions.incrementAndGet() }
        model.resetToDefaults()
        runBlocking(Dispatchers.IO) {
            withTimeout(5000) { repository.observe(PrefKey.ThemeMode).first { it == PrefKey.ThemeMode.default } }
            withTimeout(5000) { model.message.first { it == SettingsMessage.ResetDone } }
        }
        assertEquals(0, mainExecutions.get())
    }

    @Test fun olderWriteCompletionKeepsTheNewerVisibleChoiceUntilItsOwnAcknowledgement() {
        val data = ControlledStore()
        val repository = SettingsRepository(data)
        val model = model(repository)
        val collector = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(main.scheduler)).also { scopes += it }
        collector.launch { model.values.collect {} }
        main.scheduler.runCurrent()
        model.set(PrefKey.CruiseZoom, 5f)
        model.set(PrefKey.CruiseZoom, 12f)
        main.scheduler.runCurrent()
        assertEquals(12f, model.valueOf(PrefKey.CruiseZoom))
        runBlocking(Dispatchers.IO) { data.acknowledgeNext() }
        awaitChildren(model, 2)
        main.scheduler.runCurrent()
        assertEquals(5f, runBlocking(Dispatchers.IO) { repository.read(PrefKey.CruiseZoom) })
        assertEquals("An older completion must not clear the newer visible choice", 12f, model.valueOf(PrefKey.CruiseZoom))
        runBlocking(Dispatchers.IO) { data.acknowledgeNext() }
        awaitChildren(model, 1)
        main.scheduler.runCurrent()
        assertEquals(12f, model.valueOf(PrefKey.CruiseZoom))
        assertEquals(12f, runBlocking(Dispatchers.IO) { repository.read(PrefKey.CruiseZoom) })
    }

    private fun awaitChildren(model: SettingsViewModel, count: Int) = runBlocking(Dispatchers.IO) {
        withTimeout(5000) {
            while (model.viewModelScope.coroutineContext[Job]!!.children.count() != count) {
                main.scheduler.runCurrent()
                delay(1)
            }
        }
    }

    private fun realRepository(): SettingsRepository {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope,
            produceFile = { File(files.root, "settings.preferences_pb") }))
    }

    private fun model(repository: SettingsRepository): SettingsViewModel {
        val store = ViewModelStore().also { models += it }
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
        }
        return ViewModelProvider(store, factory)[SettingsViewModel::class.java]
    }

    /** Acknowledgements are held separately to reproduce overlapping user choices. */
    private class ControlledStore : DataStore<Preferences> {
        override val data = MutableStateFlow<Preferences>(emptyPreferences())
        private data class Request(val transform: suspend (Preferences) -> Preferences,
            val completed: CompletableDeferred<Preferences>)
        private val requests = Channel<Request>(Channel.UNLIMITED)
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            val result = CompletableDeferred<Preferences>()
            requests.send(Request(transform, result))
            return result.await()
        }
        suspend fun acknowledgeNext() {
            val request = withTimeout(5000) { requests.receive() }
            val updated = request.transform(data.value)
            data.value = updated
            request.completed.complete(updated)
        }
    }
}
