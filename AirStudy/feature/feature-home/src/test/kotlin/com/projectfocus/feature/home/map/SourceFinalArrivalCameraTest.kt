package com.projectfocus.feature.home.map

import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SourceFinalArrivalCameraTest {
    private class Port : SourceFinalCameraPort {
        var camera: SourceFinalCameraFrame? = SourceFinalCameraFrame(13f, 123f, 70f)
        var sent: SourceFinalCameraFrame? = null
        var destination: MapAirport? = null
        var duration: Int? = null
        var finished: (() -> Unit)? = null
        var cancelled: (() -> Unit)? = null
        var fail = false
        var dispatches = 0
        override fun current() = camera
        override fun animate(destination: MapAirport, frame: SourceFinalCameraFrame, durationMs: Int, finished: () -> Unit, cancelled: () -> Unit) {
            dispatches++; this.destination = destination; sent = frame; duration = durationMs
            this.finished = finished; this.cancelled = cancelled
            if (fail) error("Injected actual dispatch failure")
        }
    }
    private val airport = MapAirport("AAA", 20.0, 120.0)

    @Test fun actualDispatchUsesSourceCameraAndFallbackCannotRunBefore520ms() {
        val port = Port(); val camera = SourceFinalArrivalCamera(port); var results = 0
        camera.animate(airport, 12f) { results++ }
        assertEquals(1, port.dispatches); assertEquals(420, port.duration)
        assertEquals(airport, port.destination)
        assertEquals(SourceFinalCameraFrame(13.35f, 123f, 55f), port.sent)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(519))
        assertEquals(0, results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(1, results)
    }

    @Test fun sourceFinishPostsInsteadOfDeliveringInlineAndFallbackCannotDuplicateIt() {
        val port = Port(); val camera = SourceFinalArrivalCamera(port); var results = 0
        camera.animate(airport, 12f) { results++ }
        requireNotNull(port.finished).invoke()
        assertEquals(0, results)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, results)
        requireNotNull(port.cancelled).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(1, results)
    }

    @Test fun sourceCancelAlsoPostsTheSameGuardedResult() {
        val port = Port(); val camera = SourceFinalArrivalCamera(port); var results = 0
        camera.animate(airport, 12f) { results++ }
        requireNotNull(port.cancelled).invoke(); assertEquals(0, results)
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, results)
    }

    @Test fun missingCameraUsesSourceCruiseZoomWithoutInventedExtraZoom() {
        val port = Port().apply { camera = null }
        SourceFinalArrivalCamera(port).animate(airport, 12f) {}
        assertEquals(SourceFinalCameraFrame(12f, 0f, 35f), port.sent)
    }

    @Test fun dispatchExceptionPostsTheOriginalFallbackAndNeverRunsInline() {
        val port = Port().apply { fail = true }; var results = 0
        SourceFinalArrivalCamera(port).animate(airport, 12f) { results++ }
        assertEquals(0, results)
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(1, results)
    }

    @Test fun disposedOrReplacedRendererCannotDeliverAnOldResult() {
        val port = Port(); val camera = SourceFinalArrivalCamera(port); var old = 0; var current = 0
        camera.animate(airport, 12f) { old++ }
        val stale = requireNotNull(port.finished)
        camera.animate(airport, 12f) { current++ }
        stale()
        shadowOf(Looper.getMainLooper()).idle(); assertEquals(0, old); assertEquals(0, current)
        camera.dispose(); requireNotNull(port.finished).invoke()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, old); assertEquals(0, current)
    }
}
