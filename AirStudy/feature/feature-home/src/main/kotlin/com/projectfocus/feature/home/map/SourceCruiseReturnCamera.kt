package com.projectfocus.feature.home.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import kotlin.math.cos
import kotlin.math.min

internal data class SourceCruiseCameraFrame(val latitude:Double,val longitude:Double,val zoom:Float,val bearing:Float,val tilt:Float)
internal interface SourceCruiseCameraPort {
    fun current():SourceCruiseCameraFrame?
    fun cursor():FreeFlightMapPose?
    fun gestures(enabled:Boolean)
    fun move(frame:SourceCruiseCameraFrame)
}

/** Actual l4.l.u(Float.NaN,1500), source o3 case4, s4.p case2 and l4.a once fence. */
internal class SourceCruiseReturnCamera(private val port:SourceCruiseCameraPort) {
    private val main=Handler(Looper.getMainLooper())
    private class Attempt(val current:()->Boolean,val result:()->Unit) {
        var armed=false;var delivered=false;var animator:ValueAnimator?=null;var fallback:Runnable?=null
    }
    private var attempt:Attempt?=null

    fun animate(pose:FreeFlightMapPose,elapsedMs:Long,cruiseZoom:Float,current:()->Boolean,result:()->Unit) {
        check(Looper.myLooper()==Looper.getMainLooper());require(elapsedMs>=0 && cruiseZoom.isFinite())
        dispose()
        val next=Attempt(current,result);attempt=next
        val zoom=if(elapsedMs<=60_000) 15.5f+(cruiseZoom-15.5f)*(elapsedMs/60_000f) else cruiseZoom
        val tilt=min(45f,tiltForZoom(zoom))
        try {
            val start=port.current() ?: SourceCruiseCameraFrame(pose.latitude,pose.longitude,zoom,-pose.headingDegrees,tilt)
            var cursor=pose
            val bearingDelta=((((-pose.headingDegrees-start.bearing)%360f)+540f)%360f)-180f
            port.gestures(false)
            val animator=ValueAnimator.ofFloat(0f,1f).apply {
            duration=1500L
            setInterpolator { fraction -> ((1.0-cos(fraction.toDouble()*Math.PI))*.5).toFloat() }
            addUpdateListener {
                if(attempt!==next || !next.current()) return@addUpdateListener
                cursor=port.cursor() ?: cursor
                val value=it.animatedValue as Float
                val interpolatedZoom=start.zoom+(zoom-start.zoom)*value
                port.move(SourceCruiseCameraFrame(
                    start.latitude+(cursor.latitude-start.latitude)*value,
                    start.longitude+(cursor.longitude-start.longitude)*value,
                    interpolatedZoom,start.bearing+bearingDelta*value,
                    min(start.tilt+(tilt-start.tilt)*value,tiltForZoom(interpolatedZoom))))
            }
            addListener(object:AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation:Animator) { finish(next) }
                // Source onAnimationCancel case2 is empty; Android's end callback/fallback owns delivery.
            })
            }
            next.animator=animator
            animator.start()
        } catch (_:Exception) { /* Source u still installs its real timed fallback after SDK errors. */ }
        next.armed=true
        val fallback=Runnable { finish(next) };next.fallback=fallback;main.postDelayed(fallback,1550L)
    }
    private fun finish(saved:Attempt) {
        if(attempt!==saved || !saved.armed || saved.delivered) return
        saved.delivered=true;saved.fallback?.let(main::removeCallbacks)
        runCatching { port.gestures(true) }
        if(saved.current()) saved.result()
    }
    fun dispose() {
        check(Looper.myLooper()==Looper.getMainLooper())
        val old=attempt ?: return
        attempt=null;old.fallback?.let(main::removeCallbacks);old.animator?.cancel()
        runCatching { port.gestures(true) }
    }
    companion object {
        internal fun tiltForZoom(zoom:Float):Float = when { zoom>=15.5f->45f;zoom<=14f->30f;else->((zoom-14f)/1.5f)*15f+30f }
    }
}
