package com.projectfocus.feature.flight.window

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Path
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.projectfocus.feature.flight.R
import timber.log.Timber


@OptIn(UnstableApi::class)
class FlightWindowView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    private val playerView: PlayerView
    private val poster: ImageView
    private var initialized = false
    private var active = false
    private var paused = false
    private var hostResumed = false
    private var player: Player? = null
    private var positionMs = 0L
    internal var playerFactory: (Context) -> Player = { ExoPlayer.Builder(it).build() }

    init {
        LayoutInflater.from(context).inflate(R.layout.pf_flight_window, this, true)
        playerView = findViewById(R.id.pf_flight_player)
        poster = findViewById(R.id.pf_flight_scene_poster)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        poster.setImageResource(if (night) R.drawable.pf_flight_scene_night else R.drawable.pf_flight_scene_day)
        findViewById<ImageView>(R.id.pf_flight_window_mask).setImageResource(
            if (night) R.drawable.pf_flight_mask_night else R.drawable.pf_flight_mask_day,
        )
        initialized = true
    }

    fun setSession(active: Boolean, paused: Boolean) {
        this.active = active
        this.paused = paused
        syncPlayer()
    }

    fun setHostResumed(resumed: Boolean) {
        hostResumed = resumed
        syncPlayer()
    }

    fun playbackPositionMs(): Long = player?.currentPosition ?: positionMs

    fun restorePosition(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
        player?.seekTo(this.positionMs)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPlayer()
    }

    override fun onVisibilityChanged(changedView: android.view.View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) syncPlayer()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }

    private fun syncPlayer() {
        if (!initialized) return
        if (!active || !hostResumed || !isAttachedToWindow || !isShown) {
            releasePlayer()
            return
        }
        val current = player ?: playerFactory(context).also { created ->
            player = created
            poster.visibility = VISIBLE
            created.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { poster.visibility = GONE }
                override fun onPlayerError(error: PlaybackException) {
                    poster.visibility = VISIBLE
                    Timber.w(error, "Local flight-window media could not be decoded")
                }
            })
            playerView.player = created
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            val raw = if (night) R.raw.pf_flight_scene_night else R.raw.pf_flight_scene_day
            created.repeatMode = Player.REPEAT_MODE_ONE
            created.volume = 0f // AmbientSoundPlayer remains the owner of the user's audio controls.
            created.setMediaItem(MediaItem.fromUri("android.resource://${context.packageName}/$raw"))
            created.seekTo(positionMs)
            created.prepare()
        }
        current.playWhenReady = !paused
    }

    private fun releasePlayer() {
        val current = player ?: return
        positionMs = current.currentPosition.coerceAtLeast(0L)
        playerView.player = null
        current.release()
        player = null
        poster.visibility = VISIBLE
    }
}

/** Clip follows the inner path of the source 270x390 mask (29..241,29..361). */
class FlightSceneLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private val aperture = Path()
    override fun dispatchDraw(canvas: Canvas) {
        val scale = width / 240f
        aperture.reset()
        aperture.addRoundRect(14 * scale, 14 * scale, width - 14 * scale, height - 14 * scale,
            106 * scale, 106 * scale, Path.Direction.CW)
        val save = canvas.save()
        canvas.clipPath(aperture)
        super.dispatchDraw(canvas)
        canvas.restoreToCount(save)
    }
}
