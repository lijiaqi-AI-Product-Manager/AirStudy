package com.projectfocus.app.countdown

import android.app.Activity
import android.media.AudioManager
import android.media.SoundPool
import android.os.Looper
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.projectfocus.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class BoardingFeedbackTest {
    @Test fun printingAndTearingUseDifferentCuesWhenEnabled() = withFeedback { activity, view, feedback, pool ->
        Settings.System.putInt(activity.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
        val audio = shadowOf(pool)
        feedback.printHaptics = true
        feedback.tearHaptics = true
        feedback.printStarted()
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, shadowOf(view).lastHapticFeedbackPerformed())
        assertTrue(ReflectionHelpers.getField<Int>(feedback, "pending") > 0)
        assertFalse(audio.wasResourcePlayed(R.raw.boarding_paper_feed))
        audio.notifyResourceLoaded(R.raw.boarding_paper_feed, true)
        assertEquals(1, audio.getResourcePlaybacks(R.raw.boarding_paper_feed).size)

        audio.notifyResourceLoaded(R.raw.boarding_paper_tear, true)
        feedback.tearCompleted()
        assertEquals(1, audio.getResourcePlaybacks(R.raw.boarding_paper_tear).size)
        assertEquals(HapticFeedbackConstants.CONFIRM, shadowOf(view).lastHapticFeedbackPerformed())
    }

    @Test fun stoppedCuesNeverReplayAndSilentSettingsSuppressAudio() = withFeedback { activity, view, feedback, pool ->
        val resolver = activity.contentResolver
        Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 1)
        val audio = shadowOf(pool)
        val initialHaptic = shadowOf(view).lastHapticFeedbackPerformed()
        feedback.printStarted()
        feedback.stop()
        audio.notifyResourceLoaded(R.raw.boarding_paper_feed, true)
        assertFalse(audio.wasResourcePlayed(R.raw.boarding_paper_feed))

        audio.notifyResourceLoaded(R.raw.boarding_paper_tear, true)
        val manager = activity.getSystemService(AudioManager::class.java)
        manager.ringerMode = AudioManager.RINGER_MODE_SILENT
        feedback.tearCompleted()
        manager.ringerMode = AudioManager.RINGER_MODE_NORMAL
        Settings.System.putInt(resolver, Settings.System.SOUND_EFFECTS_ENABLED, 0)
        feedback.tearCompleted()
        assertFalse(audio.wasResourcePlayed(R.raw.boarding_paper_tear))
        assertEquals(initialHaptic, shadowOf(view).lastHapticFeedbackPerformed())
    }

    private fun withFeedback(action: (Activity, View, BoardingFeedback, SoundPool) -> Unit) {
        val controller = Robolectric.buildActivity(Activity::class.java).create()
        val activity = controller.get()
        val view = object : View(activity) {
            override fun getWindowVisibility(): Int = VISIBLE
        }
        activity.setContentView(view)
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(view.isShown)
        assertEquals(View.VISIBLE, view.windowVisibility)
        activity.getSystemService(AudioManager::class.java).ringerMode = AudioManager.RINGER_MODE_NORMAL
        val feedback = BoardingFeedback(view)
        try {
            feedback.attach()
            action(activity, view, feedback, ReflectionHelpers.getField(feedback, "pool"))
        } finally {
            feedback.release()
            controller.pause().stop().destroy()
        }
    }
}
