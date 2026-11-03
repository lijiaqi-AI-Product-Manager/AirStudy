package com.projectfocus.feature.flight.window

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.View
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.flight.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlightWindowViewTest {
    @Test
    fun `map mode creates no decoder and repeated state renders do not create duplicate players`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val activity = controller.get()
        val view = FlightWindowView(activity)
        val players = mutableListOf<Player>()
        view.playerFactory = { fakePlayer().also(players::add) }
        view.visibility = View.GONE
        activity.setContentView(view)
        view.setHostResumed(true)
        view.setSession(active = true, paused = false)
        assertThat(players).isEmpty()
        view.visibility = View.VISIBLE
        assertThat(players).hasSize(1)
        val first = players.single()
        repeat(3) { view.setSession(active = true, paused = false) }
        verify(exactly = 1) { first.prepare() }
        assertThat(players).hasSize(1)
        view.setSession(active = true, paused = true)
        verify { first.playWhenReady = false }
        verify(exactly = 0) { first.release() }
        view.setSession(active = true, paused = false)
        verify { first.playWhenReady = true }
        view.visibility = View.GONE
        verify(exactly = 1) { first.release() }
        assertThat(view.playbackPositionMs()).isEqualTo(4200L)
        view.visibility = View.VISIBLE
        assertThat(players).hasSize(2)
        verify { players[1].seekTo(4200L) }
        view.setHostResumed(false)
        verify(exactly = 1) { players[1].release() }
        controller.pause().stop().destroy()
        verify(exactly = 1) { first.release() }
    }

    @Test
    fun `saved position restores before playback and inactive session releases the player`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup().visible()
        val view = FlightWindowView(controller.get())
        val player = fakePlayer()
        view.playerFactory = { player }
        view.restorePosition(19000L)
        controller.get().setContentView(view)
        view.setSession(active = true, paused = true)
        verify(exactly = 0) { player.prepare() }
        view.setHostResumed(true)
        verify(exactly = 1) { player.prepare() }
        verify { player.seekTo(19000L) }
        verify { player.playWhenReady = false }
        view.setSession(active = false, paused = false)
        verify(exactly = 1) { player.release() }
        controller.pause().stop().destroy()
        verify(exactly = 1) { player.release() }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `source first frame and mask render without starting a decoder in native previews`() {
        val output = File("build/reports/flight-window-preview").apply { mkdirs() }
        for (night in listOf(false, true)) {
            val app = ApplicationProvider.getApplicationContext<Context>()
            val config = Configuration(app.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val view = FlightWindowView(ContextThemeWrapper(app.createConfigurationContext(config), com.projectfocus.core.ui.R.style.Theme_Pf))
            view.playerFactory = { throw AssertionError("A static unattached preview must not create a decoder") }
            view.setSession(active = true, paused = false)
            val width = (390 * view.resources.displayMetrics.density).toInt()
            val height = (844 * view.resources.displayMetrics.density).toInt()
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, width, height)
            assertThat(view.findViewById<View>(R.id.pf_flight_window_mask).width).isEqualTo((270 * view.resources.displayMetrics.density).toInt())
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            assertThat(bitmap.getPixel(width / 2, height / 2)).isNotEqualTo(bitmap.getPixel(0, 0))
            File(output, "window-loading-${if (night) "night" else "day"}.png").outputStream().use {
                assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
            }
            bitmap.recycle()
        }
        File(output, "README.txt").writeText("Native source-mask + same-clip first-frame loading/error-poster previews.\nThese do not demonstrate codec/video playback or original custom-view behavior.\n")
    }

    private fun fakePlayer(): Player = mockk<Player>(relaxed = true).also {
        every { it.applicationLooper } returns Looper.getMainLooper()
        every { it.availableCommands } returns Player.Commands.EMPTY
        every { it.currentTracks } returns Tracks.EMPTY
        every { it.currentTimeline } returns Timeline.EMPTY
        every { it.videoSize } returns VideoSize.UNKNOWN
        every { it.currentPosition } returns 4200L
    }
}
