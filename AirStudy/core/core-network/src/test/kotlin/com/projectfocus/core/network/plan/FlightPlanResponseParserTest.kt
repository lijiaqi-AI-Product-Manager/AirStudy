package com.projectfocus.core.network.plan

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FlightPlanResponseParserTest {
    @Test fun nestedPlanAndLegacyRemainingLegsHaveDifferentShapes() {
        val nested = FlightPlanResponseParser.daily(JSONObject("""{"deleted_count":2,"legs":{
            "plan_date":"2026-10-30","local_plan_date":"2026-10-31","timezone":"Asia/Shanghai","legs":[]}}"""))!!
        assertThat(nested.localPlanDate).isEqualTo("2026-10-31")
        assertThat(nested.planDate).isEqualTo("2026-10-30")
        assertThat(nested.deletedCount).isEqualTo(2)
        val legacy = FlightPlanResponseParser.daily(JSONObject("""{"targetLocalPlanDate":"2026-11-01",
            "local_plan_date":"wrong","timezone":"UTC","remaining_legs":[{},false,{}]}"""))!!
        assertThat(legacy.localPlanDate).isEqualTo("2026-11-01")
        assertThat(legacy.legs.map { it.legIndex }).containsExactly(1, 3).inOrder()
        assertThat(FlightPlanResponseParser.daily(JSONObject("""{"legs":[]}"""))).isNull()
        assertThat(FlightPlanResponseParser.daily(null)).isNull()
    }

    @Test fun legAliasesTrimTextButDoNotClampIncomingSceneCode() {
        val plan = FlightPlanResponseParser.daily(JSONObject("""{"legs":{"plan_date":"2026-10-31","legs":[{
            "legIndex":"7","legStatus":" canceled ","focusIconCode":99,"focusSceneText":"  自定义  ",
            "scheduled_departure_at":" ","scheduledDepartureAt":"2026-10-31T04:00:00Z",
            "actualArrivalAt":" ","completed_at":"2026-10-31T05:00:00Z",
            "scheduledArrivalAt":"2026-10-31T06:00:00Z","arrivalDiffMinutes":5,"distanceKm":"1099.5"
        }]}}"""))!!.legs.single()
        assertThat(plan.legIndex).isEqualTo(7)
        assertThat(plan.legStatus).isEqualTo("canceled")
        assertThat(plan.focusIconCode).isEqualTo(99)
        assertThat(plan.focusSceneText).isEqualTo("自定义")
        assertThat(plan.scheduledDepartureAt).isEqualTo("2026-10-31T04:00:00Z")
        assertThat(plan.actualArrivalAt).isEqualTo("2026-10-31T05:00:00Z")
        assertThat(plan.expectedArrivalAt).isEqualTo(plan.scheduledArrivalAt)
        assertThat(plan.distanceKm).isEqualTo(1099.5)
        assertThat(plan.arrivalDiffMinutes).isEqualTo(Int.MIN_VALUE) // No camel-case alias in the source.
    }

    @Test fun explicitNullUsesAndroidOptStringSemanticsRatherThanJvmJsonFallback() {
        val plan = FlightPlanResponseParser.daily(JSONObject("""{"legs":{"legs":[{
            "focus_scene_text":null,"focusSceneText":"fallback","departure_airport":{
            "latitude":null,"longitude":12.5,"name_i18n":" {\"zh\":\"机场\"} ","city_i18n":"bad",
            "cityI18n":{"en":"City"}}}]}}"""))!!.legs.single()
        assertThat(plan.focusSceneText).isEqualTo("null")
        assertThat(plan.departureAirport!!.latitude).isEqualTo("null")
        assertThat(plan.departureAirport!!.longitude).isEqualTo("12.5")
        assertThat(plan.departureAirport!!.nameI18n.toString()).isEqualTo("{\"zh\":\"机场\"}")
        assertThat(plan.departureAirport!!.cityI18n.toString()).isEqualTo("{\"en\":\"City\"}")
        assertThat(plan.arrivalAirport).isNull()
    }

    @Test fun historyDefaultsAndBooleanAliasesPreserveReadOnlyFlags() {
        val history = FlightPlanResponseParser.history(JSONObject("""{"timezone":"UTC","plans":[false,
            {"localPlanDate":" 2026-10-01 ","legs":[{}, {"archived":0.9,"readonly":"??","readOnly":"0"},
            {"archived":false,"archived_at":"stamp","readonly":" TrUe "}]},
            {"planDate":"2026-10-02","timezone":"Asia/Tokyo","archived":false,"readOnly":false,"legs":[{}]}]}"""))
        assertThat(history.map { it.localPlanDate }).containsExactly("2026-10-01", "2026-10-02").inOrder()
        assertThat(history[0].timezone).isEqualTo("UTC")
        assertThat(history[0].legs[0].archived).isTrue()
        assertThat(history[0].legs[0].readOnly).isTrue()
        assertThat(history[0].legs[1].archived).isFalse()
        assertThat(history[0].legs[1].readOnly).isFalse()
        assertThat(history[0].legs[2].archived).isFalse() // Explicit false overrides archived_at.
        assertThat(history[0].legs[2].readOnly).isTrue()
        assertThat(history[1].legs[0].archived).isFalse()
        assertThat(history[1].legs[0].readOnly).isFalse()
    }

    @Test fun snakeNumericPresenceWinsEvenWhenMalformed() {
        val leg = FlightPlanResponseParser.daily(JSONObject("""{"legs":{"legs":[{
            "leg_index":"bad","legIndex":9,"distance_km":"bad","distanceKm":22,
            "focus_icon_code":"bad","focusIconCode":7}]}}"""))!!.legs.single()
        assertThat(leg.legIndex).isEqualTo(1)
        assertThat(leg.distanceKm).isEqualTo(0.0)
        assertThat(leg.focusIconCode).isEqualTo(0)
    }
}
