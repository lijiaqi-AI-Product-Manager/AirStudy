package com.projectfocus.feature.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer


enum class AmbientSound(val rawRes: Int) {
    RAIN(R.raw.whitenoise_rain),
    BONFIRE(R.raw.whitenoise_bonfire),
    FOREST(R.raw.whitenoise_forest),
    OCEAN(R.raw.whitenoise_ocean),
}

class AmbientSoundPlayer(private val context: Context) : AmbientPlaybackHandle {

    private companion object {
        /** LOSS_DUCK 时保留的原音量比例；不直接静音，符合系统“压低”语义。 */
        const val DUCK_FACTOR = 0.2f
    }

    private var player: MediaPlayer? = null

    /** 当前正在播放的音源；null 表示关闭。 */
    var current: AmbientSound? = null
        private set

    /**
     * 播放器因音频焦点丢失而停止时回调。
     *
     * 页面必须在这里把按钮/索引恢复到「关」，否则 UI 会显示正在播放，
     * 实际已经没有声音。页面销毁前应置 null，避免回调持有已销毁的 View。
     */
    var onStopped: (() -> Unit)? = null

    private var volume: Float = 1f


    private var ducked: Boolean = false

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var focusRequested: Boolean = false

    private val focusChangeListener =
        AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    ducked = true
                    applyVolume()
                }

                AudioManager.AUDIOFOCUS_GAIN -> {
                    ducked = false
                    applyVolume()
                }

                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                -> stop()
            }
        }

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusChangeListener)
            .setWillPauseWhenDucked(false)
            .build()
    }

    val isPlaying: Boolean
        get() = player?.isPlaying == true

    /**
     * 设置音量（0f..1f）。播放前设置会记住，下一次 [play] 自动应用。
     * 不做 AudioManager 系统音量，只调 MediaPlayer 的轨道增益。
     */
    fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        applyVolume()
    }

    private fun applyVolume() {
        val effective = volume * if (ducked) DUCK_FACTOR else 1f
        player?.setVolume(effective, effective)
    }

    /**
     * 播放指定音源；切源时先释放旧播放器，避免两条同时响。
     *
     * @return 是否成功开始播放；创建失败时 current 保持 null。
     */
    fun play(sound: AmbientSound): Boolean {
        stop()
        if (!requestFocus()) return false
        val created = MediaPlayer.create(context, sound.rawRes)
        if (created == null) {
            abandonFocus()
            return false
        }
        created.isLooping = true
        val effective = volume * if (ducked) DUCK_FACTOR else 1f
        created.setVolume(effective, effective)
        created.start()
        player = created
        current = sound
        return true
    }

    override fun stop() {
        val existing = player
        player = null
        current = null
        ducked = false
        if (existing != null) {
            runCatching { existing.stop() }
            existing.release()
            onStopped?.invoke()
        }
        abandonFocus()
    }

    private fun requestFocus(): Boolean {
        val manager = audioManager ?: return true
        val result = manager.requestAudioFocus(focusRequest)
        focusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusRequested
    }

    private fun abandonFocus() {
        if (!focusRequested) return
        focusRequested = false
        audioManager?.abandonAudioFocusRequest(focusRequest)
    }

    override fun release() {
        stop()
    }
}
