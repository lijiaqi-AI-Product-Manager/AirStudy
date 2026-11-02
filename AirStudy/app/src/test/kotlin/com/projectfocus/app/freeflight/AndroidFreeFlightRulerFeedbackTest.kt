package com.projectfocus.app.freeflight

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real persisted preferences/DataStore; owner strings here are explicit component fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AndroidFreeFlightRulerFeedbackTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `volume is captured per account and intensity follows the real global master`() = fixture { f ->
        f.preferences.edit().putFloat("timeline_tick_sound_volume_user:A", .72f)
            .putInt("timeline_haptic_intensity", 87).commit()
        val first = f.bind("user:A")
        val second = f.bind("user:B")
        assertThat(first.tickVolume()).isEqualTo(.72f)
        assertThat(second.tickVolume()).isEqualTo(.3f)
        assertThat(first.hapticIntensity()).isEqualTo(87)
        runBlocking(Dispatchers.IO) { f.settings.write(PrefKey.HapticMaster, false) }
        f.await { first.hapticIntensity() == 0 && second.hapticIntensity() == 0 }
        runBlocking(Dispatchers.IO) { f.settings.write(PrefKey.HapticMaster, true) }
        f.await { first.hapticIntensity() == 87 }
        assertThat(first.tickVolume()).isEqualTo(.72f)
    }

    @Test fun `source boundaries and corrupted saved types use the actual source fallback`() = fixture { f ->
        val value = f.bind("user:A")
        assertThat(value.hapticIntensity()).isEqualTo(50)
        f.preferences.edit().putInt("timeline_haptic_intensity", -9)
            .putFloat("timeline_tick_sound_volume_user:A", 3f).commit()
        assertThat(value.hapticIntensity()).isEqualTo(0)
        assertThat(value.tickVolume()).isEqualTo(1f)
        f.preferences.edit().putInt("timeline_haptic_intensity", 900)
            .putFloat("timeline_tick_sound_volume_user:A", -2f).commit()
        assertThat(value.hapticIntensity()).isEqualTo(100)
        assertThat(value.tickVolume()).isEqualTo(0f)
        f.preferences.edit().putString("timeline_haptic_intensity", "invalid")
            .putString("timeline_tick_sound_volume_user:A", "invalid").commit()
        assertThat(value.hapticIntensity()).isEqualTo(50)
        assertThat(value.tickVolume()).isEqualTo(.3f)
    }

    private fun fixture(action: (Fixture) -> Unit) {
        val f = Fixture()
        try { action(f) } finally { f.close() }
    }

    private inner class Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(AndroidFreeFlightRulerFeedbackFactory.FILE, Context.MODE_PRIVATE)
        private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val file = File(temporary.newFolder(), "ruler.preferences_pb")
        val settings = SettingsRepository(PreferenceDataStoreFactory.create(scope = storeScope, produceFile = { file }))
        private val bindings = mutableListOf<AndroidFreeFlightRulerFeedback>()
        fun bind(owner: String): AndroidFreeFlightRulerFeedback = runBlocking(Dispatchers.IO) {
            AndroidFreeFlightRulerFeedbackFactory(context, settings).bind(owner, uiScope)
        }.also { bindings += it; shadowOf(Looper.getMainLooper()).idle() }
        fun await(condition: () -> Boolean) {
            val end = System.nanoTime() + 5_000_000_000L
            while (!condition() && System.nanoTime() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertThat(condition()).isTrue()
        }
        fun close() {
            bindings.forEach { it.close() }
            uiScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
            runBlocking(Dispatchers.IO) { withTimeout(5_000) {
                uiScope.coroutineContext.job.cancelAndJoin()
                storeScope.coroutineContext.job.cancelAndJoin()
            } }
        }
    }
}
