package com.projectfocus.feature.home.map

import android.content.Context
import android.os.Bundle
import android.view.View

internal fun interface MapSurfaceFactory {
    fun create(context: Context, configuration: MapConfiguration): MapSurface
}

internal interface MapSurface {
    val view: View
    fun onCreate(savedState: Bundle?)
    fun onStart()
    fun onResume()
    fun onPause()
    fun onStop()
    fun onDestroy()
    fun onLowMemory()
    fun onSaveInstanceState(out: Bundle)
    fun getMapAsync(callback: (MapRenderer) -> Unit)
}

internal interface MapRenderer {
    fun configureSourceControls()
    fun setWindowMode(enabled: Boolean)
    fun setRoute(input: MapRouteInput)
    fun setStyle(index: Int): Boolean
    fun setOnLoaded(callback: (() -> Unit)?)
    fun dispose()
}

/** Required actual camera capability; ordinary test renderers do not silently accept arrival. */
internal interface FreeFlightCameraRenderer {
    fun animateFinalArrival(destination: MapAirport, cruiseZoom: Float, result: () -> Unit)
}

/** Optional for test/other renderers; actual free-flight preparation requires this capability. */
internal interface FreeFlightPlaneRenderer {
    fun renderFreeFlightPlane(pose: FreeFlightMapPose)
    fun clearFreeFlightPlane()
}

/** Source Main.a0 -> s4.h0.b/d. An ordinary renderer cannot claim this camera effect. */
internal interface FreeFlightDepartureCameraRenderer {
    fun focusFreeFlightDeparture(departure: MapAirport)
}

internal interface FreeFlightLandingRenderer {
    fun renderLandingScene(scene: FreeFlightLandingMapScene?, routeColor: Int)
}

/** Every future SDK frame must pass the actual captured host/account/Core lease. */
internal interface FreeFlightCruiseReturnRenderer {
    fun animateCruiseReturn(pose:FreeFlightMapPose,elapsedMs:Long,zoom:Float,current:()->Boolean,
                            frameLease:((()->Unit)->Boolean),result:()->Unit)
}
