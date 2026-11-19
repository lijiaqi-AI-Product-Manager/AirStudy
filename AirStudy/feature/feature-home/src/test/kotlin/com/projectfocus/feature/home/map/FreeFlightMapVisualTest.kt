package com.projectfocus.feature.home.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.home.R
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Explicit recording SDK fixtures below; these do not assert real configured map acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-mdpi")
class FreeFlightMapVisualTest {
    private val pose = FreeFlightMapPose(31.0, 121.0, 0f)

    @Test fun `departure camera requires a current plane and actual camera capability`() {
        val f = Fixture()
        val departure = MapAirport("AAA", 31.0, 121.0)
        assertThat(f.host.focusFreeFlightDeparture(departure)).isFalse()
        f.host.bindLifecycle(f.owner, MapConfiguration("fixture-only"))
        val renderer = CameraPlaneRenderer()
        f.ready(renderer)
        assertThat(f.host.freeFlightPreparationReady).isTrue()
        assertThat(f.host.focusFreeFlightDeparture(departure)).isFalse()
        val plane = checkNotNull(f.host.openFreeFlightVisual(pose))
        assertThat(f.host.focusFreeFlightDeparture(departure)).isTrue()
        assertThat(renderer.departures).containsExactly(departure)
        plane.close()
        assertThat(f.host.focusFreeFlightDeparture(departure)).isFalse()
        f.host.retry()
        f.ready(PlaneRenderer())
        assertThat(f.host.freeFlightPreparationReady).isFalse()
        checkNotNull(f.host.openFreeFlightVisual(pose))
        assertThat(f.host.focusFreeFlightDeparture(departure)).isFalse()
        f.destroy()
    }

    @Test fun `missing unready and ordinary renderers cannot supply a successful visual`() {
        val f = Fixture()
        assertThat(f.host.openFreeFlightVisual(pose)).isNull()
        f.host.bindLifecycle(f.owner, MapConfiguration("fixture-only"))
        assertThat(f.host.state.value.phase).isEqualTo(MapPhase.CREATED)
        assertThat(f.host.openFreeFlightVisual(pose)).isNull()
        f.ready(OrdinaryRenderer())
        assertThat(f.host.state.value.phase).isEqualTo(MapPhase.READY)
        assertThat(f.host.openFreeFlightVisual(pose)).isNull()
        f.destroy()
    }

    @Test fun `replacing the visual invalidates old renders and old close preserves the new plane`() {
        val f = Fixture()
        f.host.bindLifecycle(f.owner, MapConfiguration("fixture-only"))
        val renderer = PlaneRenderer()
        f.ready(renderer)
        val first = checkNotNull(f.host.openFreeFlightVisual(pose))
        val next = pose.copy(headingDegrees = 240f)
        val second = checkNotNull(f.host.openFreeFlightVisual(next))
        val cleared = renderer.clears
        first.close()
        assertThat(first.render(pose)).isFalse()
        assertThat(first.isCurrent).isFalse()
        assertThat(second.isCurrent).isTrue()
        assertThat(renderer.clears).isEqualTo(cleared)
        assertThat(renderer.poses.last()).isEqualTo(next)
        second.close()
        second.close()
        assertThat(renderer.clears).isEqualTo(cleared + 1)
        f.destroy()
    }

    @Test fun `retry invalidates the old renderer and restores only cached real airports`() {
        val f = Fixture()
        val origin = MapAirport("AAA", 1.0, 2.0)
        val destination = MapAirport("BBB", 3.0, 4.0)
        f.host.setAirports(origin, destination)
        f.host.bindLifecycle(f.owner, MapConfiguration("fixture-only"))
        val firstRenderer = PlaneRenderer()
        f.ready(firstRenderer)
        val old = checkNotNull(f.host.openFreeFlightVisual(pose))
        old.close()
        assertThat(firstRenderer.routes.last()).isEqualTo(MapRouteInput(origin, destination, 0f))
        val beforeRetry = checkNotNull(f.host.openFreeFlightVisual(pose))
        f.host.retry()
        assertThat(beforeRetry.isCurrent).isFalse()
        assertThat(beforeRetry.render(pose)).isFalse()
        val replacement = PlaneRenderer()
        f.ready(replacement)
        assertThat(replacement.poses).isEmpty()
        val current = checkNotNull(f.host.openFreeFlightVisual(pose.copy(headingDegrees = 90f)))
        beforeRetry.close()
        assertThat(current.isCurrent).isTrue()
        assertThat(replacement.poses).hasSize(1)
        f.destroy()
        assertThat(current.render(pose)).isFalse()
    }

    @Test fun `actual renderer failure is failed map state rather than successful paint`() {
        val f = Fixture()
        f.host.bindLifecycle(f.owner, MapConfiguration("fixture-only"))
        val renderer = PlaneRenderer().apply { broken = true }
        f.ready(renderer)
        assertThat(f.host.openFreeFlightVisual(pose)).isNull()
        assertThat(f.host.state.value.phase).isEqualTo(MapPhase.FAILED)
        assertThat(renderer.disposed).isEqualTo(1)
        f.destroy()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `original imported drawable has actual visible white plane pixels`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val drawable = checkNotNull(context.getDrawable(R.drawable.pf_source_freeflight_plane))
        assertThat(drawable.intrinsicWidth).isEqualTo(40)
        assertThat(drawable.intrinsicHeight).isEqualTo(40)
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(27, 48, 68))
            drawable.setBounds(0, 0, 40, 40)
            drawable.draw(canvas)
            val ink = (0 until 40).sumOf { x -> (0 until 40).count { y ->
                val c = bitmap.getPixel(x, y)
                Color.red(c) > 230 && Color.green(c) > 230 && Color.blue(c) > 230
            } }
            assertThat(ink).isGreaterThan(50)
            val output = File("build/reports/freeflight-plane").apply { mkdirs() }
            File(output, "original-plane-native.png").outputStream().use {
                assertThat(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue()
            }
        } finally { bitmap.recycle() }
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this).also { it.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }
    private class Fixture {
        val owner = Owner()
        private val context = ContextThemeWrapper(ApplicationProvider.getApplicationContext<Context>(), com.projectfocus.core.ui.R.style.Theme_Pf)
        private var callback: ((MapRenderer) -> Unit)? = null
        val host = SourceMapHostView(context).also { host ->
            host.surfaceFactory = MapSurfaceFactory { _, _ -> object : MapSurface {
                override val view = View(context)
                override fun onCreate(savedState: Bundle?) = Unit
                override fun onStart() = Unit
                override fun onResume() = Unit
                override fun onPause() = Unit
                override fun onStop() = Unit
                override fun onDestroy() = Unit
                override fun onLowMemory() = Unit
                override fun onSaveInstanceState(out: Bundle) = Unit
                override fun getMapAsync(callback: (MapRenderer) -> Unit) { this@Fixture.callback = callback }
            } }
        }
        fun ready(renderer: MapRenderer) = checkNotNull(callback).invoke(renderer)
        fun destroy() { owner.registry.currentState = Lifecycle.State.DESTROYED }
    }
    private open class OrdinaryRenderer : MapRenderer {
        val routes = mutableListOf<MapRouteInput>()
        var disposed = 0
        override fun configureSourceControls() = Unit
        override fun setWindowMode(enabled: Boolean) = Unit
        override fun setRoute(input: MapRouteInput) { routes += input }
        override fun setStyle(index: Int) = true
        override fun setOnLoaded(callback: (() -> Unit)?) = Unit
        override fun dispose() { disposed++ }
    }
    private open class PlaneRenderer : OrdinaryRenderer(), FreeFlightPlaneRenderer {
        val poses = mutableListOf<FreeFlightMapPose>()
        var clears = 0
        var broken = false
        override fun renderFreeFlightPlane(pose: FreeFlightMapPose) { check(!broken); poses += pose }
        override fun clearFreeFlightPlane() { clears++ }
    }
    private class CameraPlaneRenderer : PlaneRenderer(), FreeFlightDepartureCameraRenderer {
        val departures = mutableListOf<MapAirport>()
        override fun focusFreeFlightDeparture(departure: MapAirport) { departures += departure }
    }
}
