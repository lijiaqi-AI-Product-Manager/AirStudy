package com.projectfocus.feature.home.map

import com.projectfocus.feature.home.R

/** A paint-only pose. It carries neither admission nor account/runtime authority. */
data class FreeFlightMapPose(val latitude: Double, val longitude: Double, val headingDegrees: Float) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        // The original casts a normalized Double to Float;359.99999... can round to360f.
        require(headingDegrees.isFinite() && headingDegrees in 0f..360f)
    }
}

/** Exact original l4.l.L marker operands, and l4.l.G -> s4.x.L0 position/rotation updates. */
internal data class SourceFreeFlightPlaneSpec(
    val pose: FreeFlightMapPose,
    val iconResource: Int = R.drawable.pf_source_freeflight_plane,
    val flat: Boolean = true,
    val anchorU: Float = .5f,
    val anchorV: Float = .5f,
    val zIndex: Float = 1000f,
    val visible: Boolean = true,
)

internal interface SourceFreeFlightPlaneMarker {
    fun pose(value: FreeFlightMapPose)
    fun remove()
}

internal fun interface SourceFreeFlightPlaneMap {
    fun add(spec: SourceFreeFlightPlaneSpec): SourceFreeFlightPlaneMarker
}

internal class SourceFreeFlightPlane(private val map: SourceFreeFlightPlaneMap) {
    private var marker: SourceFreeFlightPlaneMarker? = null
    val active: Boolean get() = marker != null

    fun render(pose: FreeFlightMapPose) {
        val current = marker
        if (current == null) marker = map.add(SourceFreeFlightPlaneSpec(pose))
        else current.pose(pose)
    }

    fun clear() {
        val current = marker
        marker = null
        current?.remove()
    }
}

/** Owned by one actual View/renderer generation; closing an old lease cannot clear a new plane. */
interface FreeFlightMapVisual : java.io.Closeable {
    val isCurrent: Boolean
    fun render(pose: FreeFlightMapPose): Boolean
}
