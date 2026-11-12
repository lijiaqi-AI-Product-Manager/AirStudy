package com.projectfocus.feature.home.map

import android.content.Context
import android.os.Bundle
import android.view.View
import com.huawei.hms.maps.HuaweiMap
import com.huawei.hms.maps.CameraUpdateFactory
import com.huawei.hms.maps.HuaweiMapOptions
import com.huawei.hms.maps.MapsInitializer
import com.huawei.hms.maps.TextureMapView
import com.huawei.hms.maps.model.CameraPosition
import com.huawei.hms.maps.model.LatLng
import com.huawei.hms.maps.model.MapStyleOptions
import com.huawei.hms.maps.model.BitmapDescriptorFactory
import com.huawei.hms.maps.model.MarkerOptions
import com.projectfocus.feature.home.R

internal object HmsMapSurfaceFactory : MapSurfaceFactory {
    override fun create(context: Context, configuration: MapConfiguration): MapSurface {
        check(configuration.apiKey.isNotBlank())
        MapsInitializer.setApiKey(configuration.apiKey.trim())
        configuration.appId?.trim()?.takeIf { it.isNotEmpty() }?.let(MapsInitializer::setAppId)
        MapsInitializer.initialize(context.applicationContext)
        return HmsMapSurface(context)
    }

    internal fun sourceOptions(): HuaweiMapOptions = HuaweiMapOptions()
        .camera(CameraPosition.fromLatLngZoom(LatLng(48.8566, 2.3522), 12f))
        .compassEnabled(false)
        .zoomControlsEnabled(false)
}

internal class HmsMapSurface(context: Context) : MapSurface {
    private val textureMap = TextureMapView(context, HmsMapSurfaceFactory.sourceOptions())
    override val view: View get() = textureMap
    override fun onCreate(savedState: Bundle?) = textureMap.onCreate(savedState)
    override fun onStart() = textureMap.onStart()
    override fun onResume() = textureMap.onResume()
    override fun onPause() = textureMap.onPause()
    override fun onStop() = textureMap.onStop()
    override fun onDestroy() = textureMap.onDestroy()
    override fun onLowMemory() = textureMap.onLowMemory()
    override fun onSaveInstanceState(out: Bundle) = textureMap.onSaveInstanceState(out)
    override fun getMapAsync(callback: (MapRenderer) -> Unit) {
        textureMap.getMapAsync { map -> callback(HmsMapRenderer(textureMap.context, map)) }
    }
}

internal class HmsMapRenderer(private val context: Context, private val map: HuaweiMap) : MapRenderer, FreeFlightCameraRenderer, FreeFlightPlaneRenderer, FreeFlightDepartureCameraRenderer, FreeFlightLandingRenderer, FreeFlightCruiseReturnRenderer {
    private var lastFreeFlightPose:FreeFlightMapPose?=null
    private var returnFrameLease:((()->Unit)->Boolean)?=null
    private val cruiseReturn=SourceCruiseReturnCamera(object:SourceCruiseCameraPort {
        override fun current():SourceCruiseCameraFrame?=map.cameraPosition?.let { SourceCruiseCameraFrame(it.target.latitude,it.target.longitude,it.zoom,it.bearing,it.tilt) }
        override fun cursor():FreeFlightMapPose?=lastFreeFlightPose
        override fun gestures(enabled:Boolean) { map.uiSettings.setAllGesturesEnabled(enabled) }
        override fun move(frame:SourceCruiseCameraFrame) {
            returnFrameLease?.invoke {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.Builder().target(LatLng(frame.latitude,frame.longitude))
                    .zoom(frame.zoom).bearing(frame.bearing).tilt(frame.tilt).build()))
            }
        }
    })
    override fun animateCruiseReturn(pose:FreeFlightMapPose,elapsedMs:Long,zoom:Float,current:()->Boolean,frameLease:((()->Unit)->Boolean),result:()->Unit) {
        check(freeFlightPlane.active) { "Actual free-flight plane has not attached" }
        returnFrameLease=frameLease
        cruiseReturn.animate(pose,elapsedMs,zoom,current,result)
    }
    private val landing = SourceLandingMapOverlay(object : SourceLandingMapPort {
        override fun circle(center: com.projectfocus.core.model.flight.freeflight.FreeFlightPoint, radiusMetres: Double, strokeColor: Int, strokeWidth: Float, fillColor: Int): SourceLandingCircle {
            val actual = checkNotNull(map.addCircle(com.huawei.hms.maps.model.CircleOptions()
                .center(LatLng(center.latitude, center.longitude)).radius(radiusMetres)
                .strokeColor(strokeColor).strokeWidth(strokeWidth).fillColor(fillColor).clickable(false)))
            return object : SourceLandingCircle {
                override fun colors(stroke: Int, fill: Int) { actual.strokeColor = stroke; actual.fillColor = fill }
                override fun close() = actual.remove()
            }
        }
        override fun route(points: List<com.projectfocus.core.model.flight.freeflight.FreeFlightPoint>, color: Int, width: Float, geodesic: Boolean): java.io.Closeable {
            val actual = checkNotNull(map.addPolyline(com.huawei.hms.maps.model.PolylineOptions()
                .addAll(points.map { LatLng(it.latitude, it.longitude) }).color(color).width(width).geodesic(geodesic)))
            return java.io.Closeable { actual.remove() }
        }
        override fun fit(start: com.projectfocus.core.model.flight.freeflight.FreeFlightPoint, destination: MapAirport) {
            val bounds = com.huawei.hms.maps.model.LatLngBounds.Builder().include(LatLng(start.latitude, start.longitude))
                .include(LatLng(destination.latitude, destination.longitude)).build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (120f * context.resources.displayMetrics.density).toInt()), 1000, null)
        }
    })
    override fun renderLandingScene(scene: FreeFlightLandingMapScene?, routeColor: Int) = landing.render(scene, routeColor)
    override fun focusFreeFlightDeparture(departure: MapAirport) {
        val camera = CameraPosition.Builder().target(LatLng(departure.latitude, departure.longitude)).zoom(7f).build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), 2000, null)
    }

    private val freeFlightPlane = SourceFreeFlightPlane { spec ->
        val pose = spec.pose
        val marker = requireNotNull(map.addMarker(MarkerOptions()
            .position(LatLng(pose.latitude, pose.longitude))
            .icon(BitmapDescriptorFactory.fromResource(spec.iconResource))
            .flat(spec.flat).anchor(spec.anchorU, spec.anchorV).rotation(pose.headingDegrees)
            .zIndex(spec.zIndex).visible(spec.visible)))
        object : SourceFreeFlightPlaneMarker {
            override fun pose(value: FreeFlightMapPose) {
                marker.position = LatLng(value.latitude, value.longitude)
                marker.rotation = value.headingDegrees
            }
            override fun remove() = marker.remove()
        }
    }

    override fun renderFreeFlightPlane(pose: FreeFlightMapPose) {
        lastFreeFlightPose=pose
        airportOverlay.clear()
        freeFlightPlane.render(pose)
    }

    override fun clearFreeFlightPlane() { cruiseReturn.dispose();returnFrameLease=null;lastFreeFlightPose=null;freeFlightPlane.clear(); landing.close() }
    private val finalCamera = SourceFinalArrivalCamera(object : SourceFinalCameraPort {
        override fun current(): SourceFinalCameraFrame? = map.cameraPosition?.let {
            SourceFinalCameraFrame(it.zoom, it.bearing, it.tilt)
        }
        override fun animate(destination: MapAirport, frame: SourceFinalCameraFrame, durationMs: Int, finished: () -> Unit, cancelled: () -> Unit) {
            val camera = CameraPosition.Builder().target(LatLng(destination.latitude, destination.longitude))
                .zoom(frame.zoom).bearing(frame.bearing).tilt(frame.tilt).build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(camera), durationMs, object : HuaweiMap.CancelableCallback {
                override fun onFinish() = finished()
                override fun onCancel() = cancelled()
            })
        }
    })

    override fun animateFinalArrival(destination: MapAirport, cruiseZoom: Float, result: () -> Unit) =
        finalCamera.animate(destination, cruiseZoom, result)

    override fun configureSourceControls() {
        map.uiSettings.isCompassEnabled = false
        map.uiSettings.isZoomControlsEnabled = false
    }

    override fun setWindowMode(enabled: Boolean) {
        // HMS exposes one zoom-gesture switch for pinch/double-tap, rather than two source APIs.
        map.uiSettings.apply {
            isScrollGesturesEnabled = !enabled
            isZoomGesturesEnabled = !enabled
            isRotateGesturesEnabled = !enabled
            isTiltGesturesEnabled = !enabled
        }
    }

    override fun setRoute(input: MapRouteInput) {
        map.setContentDescription(listOfNotNull(input.origin, input.destination).joinToString(" → ") {
            "${it.code} (${it.latitude}, ${it.longitude})"
        } + " ${(input.progress * 100).toInt()}%")
        renderAirports(input)
    }

    private fun renderAirports(input: MapRouteInput) {
        if (!freeFlightPlane.active) airportOverlay.render(map, input)
    }

    private val airportOverlay = SourceAirportOverlay()

    override fun setStyle(index: Int): Boolean =
        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, MapStyles.resources[index]))

    override fun setOnLoaded(callback: (() -> Unit)?) {
        map.setOnMapLoadedCallback(if (callback == null) null else HuaweiMap.OnMapLoadedCallback { callback() })
    }

    override fun dispose() {
        cruiseReturn.dispose();returnFrameLease=null;lastFreeFlightPose=null
        landing.close()
        finalCamera.dispose()
        map.setOnMapLoadedCallback(null)
        freeFlightPlane.clear()
        airportOverlay.clear()
    }
}

internal object MapStyles {
    val resources = intArrayOf(R.raw.pf_mapstyle_forest, R.raw.pf_mapstyle_ocean, R.raw.pf_mapstyle_night,
        R.raw.pf_mapstyle_sunset, R.raw.pf_mapstyle_vintage, R.raw.pf_mapstyle_satellite,
        R.raw.pf_mapstyle_simple, R.raw.pf_mapstyle_violet)
}
