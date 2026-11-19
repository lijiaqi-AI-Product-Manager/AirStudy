package com.projectfocus.feature.home.map

import android.animation.ValueAnimator
import android.graphics.Color
import android.view.animation.DecelerateInterpolator
import com.projectfocus.core.model.flight.freeflight.FreeFlightNavigation
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import java.io.Closeable

data class FreeFlightLandingMapScene(
    val center: FreeFlightPoint,
    val rangeMinutes: Int?,
    val destination: MapAirport?,
    val fitSelected: Boolean,
)

internal interface SourceLandingCircle : Closeable { fun colors(stroke: Int, fill: Int) }
internal interface SourceLandingMapPort {
    fun circle(center: FreeFlightPoint, radiusMetres: Double, strokeColor: Int, strokeWidth: Float, fillColor: Int): SourceLandingCircle
    fun route(points: List<FreeFlightPoint>, color: Int, width: Float, geodesic: Boolean): Closeable
    fun fit(start: FreeFlightPoint, destination: MapAirport)
}

/** Source s4.i0 range circle + s4.x.o0 route geometry and e4.B/h0.e selected bounds. */
internal class SourceLandingMapOverlay(private val port: SourceLandingMapPort) : Closeable {
    private var scene: FreeFlightLandingMapScene? = null
    private var color: Int? = null
    private var circle: SourceLandingCircle? = null
    private var fade: ValueAnimator? = null
    private val routes = ArrayList<Closeable>()

    fun render(value: FreeFlightLandingMapScene?, routeColor: Int) {
        if (value == scene && routeColor == color) return
        val previous = scene
        if (value?.center != previous?.center || value?.rangeMinutes != previous?.rangeMinutes) {
            fade?.cancel(); fade = null; circle?.close(); circle = null
            value?.rangeMinutes?.let { minutes ->
                val actual = port.circle(value.center, minutes / 60.0 * 800.0 * 1_000.0, Color.WHITE, 2f, Color.argb(50,255,255,255))
                circle = actual
                if (ValueAnimator.areAnimatorsEnabled()) {
                    fade = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 300L; interpolator = DecelerateInterpolator()
                        addUpdateListener {
                            if (circle === actual) {
                                val fraction = it.animatedValue as Float
                                actual.colors(Color.argb(Math.round(fraction * 255),255,255,255), Color.argb(Math.round(fraction * 50),255,255,255))
                            }
                        }; start()
                    }
                }
            }
        }
        if (value?.center != previous?.center || value?.destination != previous?.destination || routeColor != color) {
            routes.forEach { it.close() }; routes.clear()
            value?.destination?.let { destination ->
                FreeFlightNavigation.approachSegments(value.center, FreeFlightPoint(destination.latitude, destination.longitude)).forEach {
                    routes += port.route(it, routeColor, 1f, false)
                }
                if (value.fitSelected) port.fit(value.center, destination)
            }
        }
        scene = value; color = routeColor
    }

    override fun close() {
        fade?.cancel(); fade = null; circle?.close(); circle = null
        routes.forEach { it.close() }; routes.clear(); scene = null; color = null
    }
}
