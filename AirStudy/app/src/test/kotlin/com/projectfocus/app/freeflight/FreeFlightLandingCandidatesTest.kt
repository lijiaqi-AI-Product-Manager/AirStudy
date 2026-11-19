package com.projectfocus.app.freeflight

import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import com.projectfocus.core.network.dto.*
import com.projectfocus.core.network.freeflight.AirportLocationGateway
import com.projectfocus.service.focus.freeflight.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FreeFlightLandingCandidatesTest {
    @Test fun ceilRadiusGoesToTheApiButRawRadiusAndDepartureFilterPreserveReturnedOrder() = runTest {
        var actual:AirportLocationRequest?=null
        val gateway=AirportLocationGateway { request -> actual=request
            listOf(airport(1,"ORIGIN",0.0),airport(3,"THIRD",1.0),airport(2,"SECOND",2.0),airport(4,"OUTSIDE",3.0)) }
        val measured=mutableListOf<Double>()
        val distance=FreeFlightLandingDistance { _,point -> measured+=point.longitude
            when(point.longitude) { 1.0 ->133.0;2.0->1.0;3.0->133.34;else->error("origin must be excluded") } }
        val values=FreeFlightLandingCandidates(gateway,distance).query(FreeFlightLandingSearch("flight",4,FreeFlightPoint(0.0,0.0),10,134),"origin")
        assertEquals(AirportLocationRequest(0.0,0.0,134),actual)
        assertEquals(listOf("THIRD","SECOND"),values.map { it.airport.code })
        assertEquals(listOf(1.0,2.0,3.0),measured)
        assertEquals(listOf(10,0),values.map { it.minutes })
    }
    @Test fun unavailableActualDistanceCannotPublishZeroDistanceOrPartialCandidates() = runTest {
        val gateway=AirportLocationGateway { listOf(airport(2,"B",1.0),airport(3,"C",2.0)) }
        var calls=0
        val distance=FreeFlightLandingDistance { _,_ -> if (++calls==1) 1.0 else throw IllegalStateException("provider unavailable") }
        var failed=false
        try { FreeFlightLandingCandidates(gateway,distance).query(FreeFlightLandingSearch("flight",1,FreeFlightPoint(0.0,0.0),10,134),"A") }
        catch (_:IllegalStateException) { failed=true }
        assertTrue(failed)
    }
    private fun airport(id:Long,code:String,longitude:Double)=NearbyAirportDto(id,code,code,"City","ZZ",0.0,longitude)
}
