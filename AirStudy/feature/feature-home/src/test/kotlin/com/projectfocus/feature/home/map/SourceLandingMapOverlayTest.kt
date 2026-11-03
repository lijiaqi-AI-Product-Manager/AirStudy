package com.projectfocus.feature.home.map

import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import java.io.Closeable
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SourceLandingMapOverlayTest {
    private class Port:SourceLandingMapPort {
        var circles=0;var circleCloses=0;var routeCloses=0;var fits=0
        var radius=0.0;val paths=mutableListOf<List<FreeFlightPoint>>()
        override fun circle(center:FreeFlightPoint,radiusMetres:Double,strokeColor:Int,strokeWidth:Float,fillColor:Int):SourceLandingCircle {
            circles++;radius=radiusMetres;assertEquals(2f,strokeWidth);assertEquals(0xffffffff.toInt(),strokeColor);assertEquals(0x32ffffff,fillColor)
            return object:SourceLandingCircle { override fun colors(stroke:Int,fill:Int) {};override fun close() { circleCloses++ } }
        }
        override fun route(points:List<FreeFlightPoint>,color:Int,width:Float,geodesic:Boolean):Closeable {
            assertEquals(1f,width);assertFalse(geodesic);paths+=points;return Closeable { routeCloses++ }
        }
        override fun fit(start:FreeFlightPoint,destination:MapAirport) { fits++ }
    }
    @Test fun rangeChangesClearOldLayersAndRepeatedPaintsDoNotRestartAnimationsOrRefitTheCamera() {
        val port=Port();val overlay=SourceLandingMapOverlay(port)
        val circle=FreeFlightLandingMapScene(FreeFlightPoint(0.0,0.0),10,null,true)
        overlay.render(circle,0xff123456.toInt());overlay.render(circle,0xff123456.toInt())
        assertEquals(1,port.circles);assertEquals(133333.33333333334,port.radius,.00001)
        val selected=circle.copy(destination=MapAirport("A",0.1,0.1))
        overlay.render(selected,0xff123456.toInt());overlay.render(selected,0xff123456.toInt())
        assertEquals(1,port.circles);assertEquals(1,port.paths.size);assertEquals(1,port.fits)
        overlay.render(circle.copy(rangeMinutes=30),0xff123456.toInt())
        assertEquals(2,port.circles);assertEquals(1,port.circleCloses);assertEquals(1,port.routeCloses)
        overlay.close();assertEquals(2,port.circleCloses)
    }
    @Test fun selectedDateLineRouteUsesActualSourceSeamAndApproachDoesNotRefitSelectionCamera() {
        val port=Port();val overlay=SourceLandingMapOverlay(port)
        val scene=FreeFlightLandingMapScene(FreeFlightPoint(20.0,179.0),null,MapAirport("W",22.0,-179.0),false)
        overlay.render(scene,0xff123456.toInt())
        assertEquals(2,port.paths.size)
        assertEquals(179.9999,port.paths[0].last().longitude,0.0)
        assertEquals(-179.9999,port.paths[1].first().longitude,0.0)
        assertEquals(port.paths[0].last().latitude,port.paths[1].first().latitude,0.0)
        assertEquals(0,port.fits);overlay.close();assertEquals(2,port.routeCloses)
    }
}
