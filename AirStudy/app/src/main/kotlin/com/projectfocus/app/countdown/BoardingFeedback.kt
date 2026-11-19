package com.projectfocus.app.countdown

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import com.projectfocus.app.R

/** Short feedback tied to the visible paper and the user's system settings. */
internal class BoardingFeedback(private val view: View) {
    var printHaptics = false
    var tearHaptics = false

    private var pool: SoundPool? = null
    private var feedId = 0
    private var tearId = 0
    private val loaded = mutableSetOf<Int>()
    private var pending = 0
    private var stream = 0

    fun attach() {
        if (pool != null) return
        val sound = try {
            SoundPool.Builder().setMaxStreams(1)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .build()
        } catch (_: RuntimeException) {
            return
        }
        pool = sound
        sound.setOnLoadCompleteListener { callback, id, status ->
            if (callback === pool) {
                if (status == 0) loaded.add(id)
                if (pending == id) {
                    pending = 0
                    if (status == 0) play(id)
                }
            }
        }
        try {
            feedId = sound.load(view.context, R.raw.boarding_paper_feed, 1)
            tearId = sound.load(view.context, R.raw.boarding_paper_tear, 1)
        } catch (_: RuntimeException) {
            release()
        }
    }

    fun printStarted() {
        if (!isVisible()) return
        if (printHaptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        play(feedId)
    }

    fun tearCompleted() {
        if (!isVisible()) return
        if (tearHaptics) view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CONTEXT_CLICK,
        )
        play(tearId)
    }

    fun stop() {
        pending = 0
        if (stream != 0) pool?.stop(stream)
        stream = 0
    }

    fun release() {
        stop()
        pool?.setOnLoadCompleteListener(null)
        pool?.release()
        pool = null
        loaded.clear()
        feedId = 0
        tearId = 0
    }

    private fun isVisible(): Boolean = view.isShown && view.windowVisibility == View.VISIBLE

    private fun play(id: Int) {
        if (id == 0 || !isVisible()) return
        val context = view.context
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (manager.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 0) == 0) return
        stop()
        if (id in loaded) stream = pool?.play(id, .32f, .32f, 1, 0, 1f) ?: 0
        else pending = id
    }
}
