package com.projectfocus.feature.home.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.UiSettings
import com.huawei.hms.maps.model.Marker
import com.huawei.hms.maps.model.MarkerOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HmsMapRendererTest {
    @Test
    fun `typed SDK options use original Paris camera and source UI settings`() {
        val options = HmsMapSurfaceFactory.sourceOptions()
        assertThat(options.camera.target.latitude).isEqualTo(48.8566)
        assertThat(options.camera.target.longitude).isEqualTo(2.3522)
        assertThat(options.camera.zoom).isEqualTo(12f)
        assertThat(options.compassEnabled).isFalse()
        assertThat(options.zoomControlsEnabled).isFalse()
    }

    @Test
    fun `typed adapter submits real coordinates and restores four HMS gesture switches without invented camera movement`() {
        val map = mockk<HuaweiMap>(relaxed = true)
        val settings = mockk<UiSettings>(relaxed = true)
        every { map.uiSettings } returns settings
        val positions = mutableListOf<MarkerOptions>()
        val markers = mutableListOf<Marker>()
        every { map.addMarker(capture(positions)) } answers { mockk<Marker>(relaxed = true).also(markers::add) }
        val renderer = HmsMapRenderer(ApplicationProvider.getApplicationContext<Context>(), map)
        renderer.configureSourceControls()
        renderer.setWindowMode(true)
        verify { settings.isCompassEnabled = false; settings.isZoomControlsEnabled = false }
        verify { settings.isScrollGesturesEnabled = false; settings.isZoomGesturesEnabled = false }
        verify { settings.isRotateGesturesEnabled = false; settings.isTiltGesturesEnabled = false }
        renderer.setWindowMode(false)
        verify { settings.isScrollGesturesEnabled = true; settings.isZoomGesturesEnabled = true }
        verify { settings.isRotateGesturesEnabled = true; settings.isTiltGesturesEnabled = true }
        val origin = MapAirport("AAA", 48.8566, 2.3522)
        val destination = MapAirport("BBB", 51.5, -.1)
        renderer.setRoute(MapRouteInput(origin, destination, .25f))
        assertThat(positions).hasSize(2)
        assertThat(positions[0].position.latitude).isEqualTo(origin.latitude)
        assertThat(positions[0].position.longitude).isEqualTo(origin.longitude)
        assertThat(positions[1].position.latitude).isEqualTo(destination.latitude)
        renderer.setRoute(MapRouteInput(origin, destination, .5f))
        assertThat(positions).hasSize(2)
        renderer.setRoute(MapRouteInput(null, destination, .5f))
        verify(exactly = 1) { markers[0].remove() }
        verify(exactly = 0) { map.moveCamera(any()) }
        renderer.dispose()
        verify(exactly = 1) { markers[1].remove() }
    }
}
