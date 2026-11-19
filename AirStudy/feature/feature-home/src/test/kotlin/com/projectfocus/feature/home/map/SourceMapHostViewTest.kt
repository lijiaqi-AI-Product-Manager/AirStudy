package com.projectfocus.feature.home.map

import android.content.Context
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.home.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SourceMapHostViewTest {
    private val valid = MapConfiguration("test-api-key", "test-app-id")

    @Test
    fun `missing key and retry never construct SDK and credentials never enter state`() {
        val factory = FakeFactory()
        val view = host(factory)
        val owner = Owner(Lifecycle.State.RESUMED)
        view.bindLifecycle(owner, MapConfiguration(" ", "test-app-id"))
        view.retry()
        assertThat(factory.surfaces).isEmpty()
        assertThat(view.state.value.phase).isEqualTo(MapPhase.MISSING_CONFIGURATION)
        assertThat(view.findViewById<TextView>(R.id.pf_map_message).text.toString()).isEqualTo("地图暂时无法显示")
        assertThat(view.findViewById<View>(R.id.pf_map_retry).visibility).isEqualTo(View.VISIBLE)
        val saved = Bundle()
        view.saveMapState(saved)
        assertThat(saved.toString()).doesNotContain("test-app-id")
        assertThat(valid.toString()).doesNotContain("test-api-key")
        assertThat(valid.toString()).doesNotContain("test-app-id")
    }

    @Test
    fun `lifecycle pairs are exact and hiding retains one surface and its camera`() {
        val factory = FakeFactory()
        val view = host(factory)
        val owner = Owner(Lifecycle.State.CREATED)
        view.bindLifecycle(owner, valid)
        val surface = factory.surfaces.single()
        assertThat(surface.life).containsExactly("create").inOrder()
        owner.registry.currentState = Lifecycle.State.RESUMED
        view.bindLifecycle(owner, valid)
        assertThat(surface.life).containsExactly("create", "start", "resume").inOrder()
        view.setMapVisible(false)
        view.setMapVisible(false)
        assertThat(surface.life).containsExactly("create", "start", "resume", "pause", "stop").inOrder()
        view.setMapVisible(true)
        assertThat(factory.surfaces).hasSize(1)
        assertThat(surface.life).containsExactly("create", "start", "resume", "pause", "stop", "start", "resume").inOrder()
        view.onLowMemory()
        assertThat(surface.lowMemory).isEqualTo(1)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        assertThat(surface.life).containsExactly("create", "start", "resume", "pause", "stop", "start", "resume", "pause", "stop", "destroy").inOrder()
        assertThat(view.state.value.phase).isEqualTo(MapPhase.DESTROYED)
    }

    @Test
    fun `destroy captures SDK state before Fragment cleanup and new view restores camera without credentials`() {
        val factory = FakeFactory()
        val first = host(factory)
        val firstOwner = Owner(Lifecycle.State.RESUMED)
        first.bindLifecycle(firstOwner, valid)
        first.setMapStyle(5)
        factory.surfaces.single().savedCamera = "user-camera"
        firstOwner.registry.currentState = Lifecycle.State.DESTROYED
        val savedAfterOwnerDestroyed = Bundle()
        first.saveMapState(savedAfterOwnerDestroyed)
        assertThat(savedAfterOwnerDestroyed.toString()).doesNotContain(valid.apiKey)
        val second = host(factory)
        second.bindLifecycle(Owner(Lifecycle.State.RESUMED), valid, savedAfterOwnerDestroyed)
        val restored = factory.surfaces.last().receivedState!!
        assertThat(restored.getString("camera")).isEqualTo("user-camera")
        assertThat(restored.containsKey("apiKey")).isFalse()
        assertThat(restored.getBundle("nested")!!.containsKey("opaque-secret")).isFalse()
        assertThat(second.state.value.requestedStyleIndex).isEqualTo(5)
    }

    @Test
    fun `ready consumes only latest atomic airport pair style and progress and window does not stop SDK`() {
        val factory = FakeFactory()
        val view = host(factory)
        val a = MapAirport("A", 1.0, 2.0)
        val b = MapAirport("B", 3.0, 4.0)
        val c = MapAirport("C", 5.0, 6.0)
        view.setAirports(a, b)
        view.setAirports(c, a)
        view.setFlightProgress(.75f)
        view.setMapStyle(7)
        view.setMapStyle(2)
        view.setWindowMode(true)
        view.bindLifecycle(Owner(Lifecycle.State.RESUMED), valid)
        val surface = factory.surfaces.single()
        val renderer = FakeRenderer()
        surface.ready(renderer)
        assertThat(renderer.routes).containsExactly(MapRouteInput(c, a, .75f))
        assertThat(renderer.styles).containsExactly(2)
        assertThat(renderer.windowModes).containsExactly(true)
        assertThat(view.state.value.phase).isEqualTo(MapPhase.READY)
        view.setMapStyle(2)
        assertThat(renderer.styles).hasSize(1)
        view.setWindowMode(false)
        assertThat(surface.life).containsExactly("create", "start", "resume").inOrder()
        view.setAirports(null, b)
        assertThat(renderer.routes.last()).isEqualTo(MapRouteInput(null, b, .75f))
        view.setFlightProgress(Float.NaN)
        assertThat(renderer.routes.last().progress).isEqualTo(0f)
    }

    @Test
    fun `loaded and rejected style stay distinct and late callbacks cannot revive a destroyed owner`() {
        val factory = FakeFactory()
        val view = host(factory)
        val owner = Owner(Lifecycle.State.RESUMED)
        view.bindLifecycle(owner, valid)
        val first = factory.surfaces.single()
        val renderer = FakeRenderer(styleAccepted = false)
        first.ready(renderer)
        assertThat(view.state.value.phase).isEqualTo(MapPhase.READY)
        assertThat(view.state.value.styleStatus).isEqualTo(MapStyleStatus.REJECTED)
        assertThat(view.findViewById<View>(R.id.pf_map_status_panel).visibility).isEqualTo(View.VISIBLE)
        val lateLoaded = renderer.loaded!!
        renderer.loaded!!.invoke()
        assertThat(view.state.value.phase).isEqualTo(MapPhase.LOADED)
        assertThat(view.state.value.styleStatus).isEqualTo(MapStyleStatus.REJECTED)
        owner.registry.currentState = Lifecycle.State.DESTROYED
        lateLoaded()
        first.ready(FakeRenderer())
        assertThat(view.state.value.phase).isEqualTo(MapPhase.DESTROYED)
        assertThat(factory.surfaces).hasSize(1)
    }

    @Test
    fun `preparation hides diagnostics across actual host callbacks without changing map state or lifecycle`() {
        val factory = FakeFactory()
        val view = host(factory)
        val owner = Owner(Lifecycle.State.RESUMED)
        view.setStatusControlsVisible(false)
        view.bindLifecycle(owner, valid)
        val surface = factory.surfaces.single()
        assertThat(view.state.value.phase).isEqualTo(MapPhase.CREATED)
        assertThat(view.findViewById<View>(R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        val renderer = FakeRenderer(styleAccepted = false)
        surface.ready(renderer)
        assertThat(view.state.value.phase).isEqualTo(MapPhase.READY)
        assertThat(view.state.value.styleStatus).isEqualTo(MapStyleStatus.REJECTED)
        assertThat(view.findViewById<View>(R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        assertThat(view.findViewById<View>(R.id.pf_map_style_notice).visibility).isEqualTo(View.GONE)
        view.setStatusControlsVisible(true)
        assertThat(view.findViewById<View>(R.id.pf_map_status_panel).visibility).isEqualTo(View.VISIBLE)
        assertThat(view.findViewById<View>(R.id.pf_map_style_notice).visibility).isEqualTo(View.VISIBLE)
        view.setStatusControlsVisible(false)
        renderer.loaded!!.invoke()
        assertThat(view.state.value.phase).isEqualTo(MapPhase.LOADED)
        view.setStatusControlsVisible(true)
        assertThat(view.findViewById<View>(R.id.pf_map_status_panel).visibility).isEqualTo(View.GONE)
        assertThat(view.findViewById<View>(R.id.pf_map_style_notice).visibility).isEqualTo(View.VISIBLE)
        assertThat(factory.surfaces).hasSize(1)
        assertThat(surface.life).containsExactly("create", "start", "resume").inOrder()
        owner.registry.currentState = Lifecycle.State.DESTROYED
    }

    @Test
    fun `retry invalidates old ready callback and initialization failure remains honest`() {
        val factory = FakeFactory()
        val view = host(factory)
        view.bindLifecycle(Owner(Lifecycle.State.RESUMED), valid)
        val obsolete = factory.surfaces.single()
        view.retry()
        val current = factory.surfaces.last()
        obsolete.ready(FakeRenderer())
        assertThat(view.state.value.phase).isEqualTo(MapPhase.CREATED)
        current.ready(FakeRenderer())
        assertThat(view.state.value.phase).isEqualTo(MapPhase.READY)
        assertThat(obsolete.life.last()).isEqualTo("destroy")
        val broken = host(MapSurfaceFactory { _, _ -> error("SDK unavailable") })
        broken.bindLifecycle(Owner(Lifecycle.State.RESUMED), valid)
        assertThat(broken.state.value.phase).isEqualTo(MapPhase.FAILED)
        assertThat(broken.findViewById<TextView>(R.id.pf_map_message).text.toString()).isEqualTo("地图暂时无法显示")
    }

    private fun host(factory: MapSurfaceFactory): SourceMapHostView {
        val app = ApplicationProvider.getApplicationContext<Context>()
        return SourceMapHostView(ContextThemeWrapper(app, com.projectfocus.core.ui.R.style.Theme_Pf)).also { it.surfaceFactory = factory }
    }

    private class Owner(initial: Lifecycle.State) : LifecycleOwner {
        val registry = LifecycleRegistry(this).also { it.currentState = initial }
        override val lifecycle: Lifecycle get() = registry
    }

    private class FakeFactory : MapSurfaceFactory {
        val surfaces = mutableListOf<FakeSurface>()
        override fun create(context: Context, configuration: MapConfiguration): MapSurface =
            FakeSurface(View(context), configuration).also(surfaces::add)
    }

    private class FakeSurface(override val view: View, private val credentials: MapConfiguration) : MapSurface {
        val life = mutableListOf<String>()
        var receivedState: Bundle? = null
        var savedCamera = "camera"
        var lowMemory = 0
        lateinit var ready: (MapRenderer) -> Unit
        override fun onCreate(savedState: Bundle?) { life += "create"; receivedState = savedState }
        override fun onStart() { life += "start" }
        override fun onResume() { life += "resume" }
        override fun onPause() { life += "pause" }
        override fun onStop() { life += "stop" }
        override fun onDestroy() { life += "destroy" }
        override fun onLowMemory() { lowMemory++ }
        override fun onSaveInstanceState(out: Bundle) {
            out.putString("camera", savedCamera)
            out.putString("apiKey", credentials.apiKey)
            out.putBundle("nested", Bundle().apply { putString("opaque-secret", credentials.appId) })
        }
        override fun getMapAsync(callback: (MapRenderer) -> Unit) { ready = callback }
    }

    private class FakeRenderer(private val styleAccepted: Boolean = true) : MapRenderer {
        val routes = mutableListOf<MapRouteInput>()
        val styles = mutableListOf<Int>()
        val windowModes = mutableListOf<Boolean>()
        var loaded: (() -> Unit)? = null
        override fun configureSourceControls() = Unit
        override fun setWindowMode(enabled: Boolean) { windowModes += enabled }
        override fun setRoute(input: MapRouteInput) { routes += input }
        override fun setStyle(index: Int): Boolean { styles += index; return styleAccepted }
        override fun setOnLoaded(callback: (() -> Unit)?) { loaded = callback }
        override fun dispose() = Unit
    }
}
