package com.projectfocus.feature.home.map

import android.os.Handler
import android.os.Looper

internal data class SourceFinalCameraFrame(val zoom: Float, val bearing: Float, val tilt: Float)
internal interface SourceFinalCameraPort {
    fun current(): SourceFinalCameraFrame?
    fun animate(destination: MapAirport, frame: SourceFinalCameraFrame, durationMs: Int, finished: () -> Unit, cancelled: () -> Unit)
}

/** Source e4.p + d4: both callbacks post, with a guarded 520ms fallback after real dispatch. */
internal class SourceFinalArrivalCamera(private val port: SourceFinalCameraPort) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0L
    private var pending: Runnable? = null

    fun animate(destination: MapAirport, cruiseZoom: Float, result: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(cruiseZoom.isFinite())
        val token = ++generation
        pending?.let(main::removeCallbacks)
        val current = try { port.current() } catch (_: Exception) { null }
        val target = if (current == null) SourceFinalCameraFrame(cruiseZoom, 0f, 35f)
            else SourceFinalCameraFrame(maxOf(cruiseZoom, current.zoom) + .35f, current.bearing, current.tilt.coerceIn(25f, 55f))
        var delivered = false
        lateinit var once: Runnable
        once = Runnable {
            if (token == generation && !delivered) {
                delivered = true
                main.removeCallbacks(once)
                pending = null
                result()
            }
        }
        pending = once
        try {
            port.animate(destination, target, 420, { main.post(once) }, { main.post(once) })
            main.postDelayed(once, 520L)
        } catch (_: Exception) { main.post(once) }
        catch (_: LinkageError) { main.post(once) }
    }

    fun dispose() {
        check(Looper.myLooper() == Looper.getMainLooper())
        ++generation
        pending?.let(main::removeCallbacks)
        pending = null
    }
}
