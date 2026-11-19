package com.projectfocus.app.freeflight

import android.content.Context
import android.content.SharedPreferences
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.core.datastore.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Actual preference-backed source feedback. The captured own account scope is the UID suffix;
 * no credential/generation/Bundle field is used as preference authority. Existing own Settings
 * controls the global master; the source intensity and volume remain distinct persisted values.
 */
class AndroidFreeFlightRulerFeedbackFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) {
    suspend fun bind(ownerScope: String, uiScope: CoroutineScope): AndroidFreeFlightRulerFeedback {
        require(ownerScope.isNotBlank())
        val preferences = withContext(Dispatchers.IO) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { it.all }
        }
        val master = settings.read(PrefKey.HapticMaster)
        val feedback = AndroidFreeFlightRulerFeedback(preferences, ownerScope, master)
        feedback.masterObservation = uiScope.launch {
            settings.observe(PrefKey.HapticMaster).collect { feedback.masterEnabled = it }
        }
        return feedback
    }

    companion object {
        const val FILE = "flight_settings_prefs"
        const val TICK_VOLUME = "timeline_tick_sound_volume"
        const val INTENSITY = "timeline_haptic_intensity"
    }
}

class AndroidFreeFlightRulerFeedback internal constructor(
    private val preferences: SharedPreferences,
    private val ownerScope: String,
    master: Boolean,
) : FreeFlightRulerFeedback, java.io.Closeable {
    @Volatile internal var masterEnabled = master
    internal var masterObservation: Job? = null

    override fun tickVolume(): Float = try {
        preferences.getFloat(AndroidFreeFlightRulerFeedbackFactory.TICK_VOLUME + "_" + ownerScope, .3f).coerceIn(0f, 1f)
    } catch (_: ClassCastException) { .3f }

    override fun hapticIntensity(): Int {
        if (!masterEnabled) return 0
        return try { preferences.getInt(AndroidFreeFlightRulerFeedbackFactory.INTENSITY, 50).coerceIn(0, 100) }
        catch (_: ClassCastException) { 50 }
    }

    override fun close() { masterObservation?.cancel(); masterObservation = null }
}
