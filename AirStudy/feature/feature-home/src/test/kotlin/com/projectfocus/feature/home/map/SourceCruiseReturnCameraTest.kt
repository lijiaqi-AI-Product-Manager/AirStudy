package com.projectfocus.feature.home.map

import android.animation.ValueAnimator
import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SourceCruiseReturnCameraTest {
    private class Port:SourceCruiseCameraPort {
        var frame:SourceCruiseCameraFrame?=SourceCruiseCameraFrame(10.0,179.0,11f,350f,50f)
        var point=FreeFlightMapPose(20.0,-179.0,90f)
        val moves=mutableListOf<SourceCruiseCameraFrame>();val gestureChanges=mutableListOf<Boolean>()
        var failCurrent=false
        override fun current():SourceCruiseCameraFrame? { if(failCurrent) error("actual SDK read failed");return frame }
        override fun cursor():FreeFlightMapPose?=point
        override fun gestures(enabled:Boolean) { gestureChanges+=enabled }
        override fun move(frame:SourceCruiseCameraFrame) { moves+=frame }
    }
    @Test fun actualValueAnimatorUsesSourceCosineRawCoordinateInterpolationAndShortestBearing() {
        val port=Port();val camera=SourceCruiseReturnCamera(port);var results=0
        camera.animate(port.point,30_000L,12f,{true}) { results++ }
        val pending:Any=ReflectionHelpers.getField(camera,"attempt")
        val animator:ValueAnimator=ReflectionHelpers.getField(pending,"animator")
        assertEquals(1500L,animator.duration)
        animator.currentPlayTime=750L
        val half=port.moves.last()
        assertEquals(15.0,half.latitude,1e-6);assertEquals(0.0,half.longitude,1e-5)
        assertEquals(12.375f,half.zoom,.0001f);assertEquals(310f,half.bearing,.0001f);assertEquals(30f,half.tilt,.0001f)
        assertEquals(0,results)
        animator.end();assertEquals(1,results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(1,results)
        assertEquals(false,port.gestureChanges.first());assertEquals(true,port.gestureChanges.last());camera.dispose()
    }
    @Test fun sourceSdkReadFailureStillUsesTheReal1550msFallbackAndNeverFinishesEarly() {
        val port=Port().apply { failCurrent=true };val camera=SourceCruiseReturnCamera(port);var results=0
        camera.animate(port.point,120_000L,12f,{true}) { results++ }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1549));assertEquals(0,results)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));assertEquals(1,results)
        assertTrue(port.moves.isEmpty());camera.dispose()
    }
    @Test fun obsoleteOrDisposedCameraNeverResumesAnotherFlight() {
        val port=Port();val camera=SourceCruiseReturnCamera(port);var current=true;var results=0
        camera.animate(port.point,30_000L,12f,{current}) { results++ }
        current=false;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(0,results)
        camera.animate(port.point,30_000L,12f,{true}) { results++ };camera.dispose()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(0,results)
    }
}
