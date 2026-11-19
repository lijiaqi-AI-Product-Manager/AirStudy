package com.projectfocus.feature.home.map

import com.google.common.truth.Truth.assertThat
import com.projectfocus.feature.home.R
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceFreeFlightPlaneTest {
    @Test fun `source marker is reused for heading and position and removed once`() {
        val created = mutableListOf<SourceFreeFlightPlaneSpec>()
        val markers = mutableListOf<RecordingMarker>()
        val plane = SourceFreeFlightPlane { spec ->
            created += spec
            RecordingMarker().also(markers::add)
        }
        val initial = FreeFlightMapPose(31.0, 121.0, 0f)
        plane.render(initial)
        val spec = created.single()
        assertThat(spec.pose).isEqualTo(initial)
        assertThat(spec.iconResource).isEqualTo(R.drawable.pf_source_freeflight_plane)
        assertThat(spec.flat).isTrue()
        assertThat(spec.anchorU).isEqualTo(.5f)
        assertThat(spec.anchorV).isEqualTo(.5f)
        assertThat(spec.zIndex).isEqualTo(1000f)
        assertThat(spec.visible).isTrue()
        val updated = FreeFlightMapPose(31.001, 121.003, 275f)
        plane.render(updated)
        assertThat(created).hasSize(1)
        assertThat(markers.single().poses).containsExactly(updated)
        plane.clear()
        plane.clear()
        assertThat(markers.single().removals).isEqualTo(1)
        assertThat(plane.active).isFalse()
        plane.render(initial)
        assertThat(created).hasSize(2)
    }

    @Test fun `actual normalized double can round to source float360 without losing north pose`() {
        val rounded = 359.999999999.toFloat()
        assertThat(rounded).isEqualTo(360f)
        assertThat(FreeFlightMapPose(90.0, 180.0, rounded).headingDegrees).isEqualTo(360f)
        assertThrows(IllegalArgumentException::class.java) { FreeFlightMapPose(Double.NaN, 0.0, 0f) }
        assertThrows(IllegalArgumentException::class.java) { FreeFlightMapPose(0.0, 181.0, 0f) }
        assertThrows(IllegalArgumentException::class.java) { FreeFlightMapPose(0.0, 0.0, Float.NaN) }
    }

    private class RecordingMarker : SourceFreeFlightPlaneMarker {
        val poses = mutableListOf<FreeFlightMapPose>()
        var removals = 0
        override fun pose(value: FreeFlightMapPose) { poses += value }
        override fun remove() { removals++ }
    }
}
