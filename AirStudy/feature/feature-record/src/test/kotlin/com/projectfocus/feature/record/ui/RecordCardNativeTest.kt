package com.projectfocus.feature.record.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.entity.FlightHistoryEntity
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItemMapping
import com.projectfocus.service.focus.history.RecordedFlight
import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h960dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordCardNativeTest {
    private val wall = Instant.parse("2026-11-03T02:30:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val departure = PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0)
    private val destination = PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0)

    private fun archive(cancelled: Boolean = false): FreeFlightArchive {
        val engine = FreeFlightEngine()
        val now = FreeFlightClockReading(0,0,wall,"record-card-clock")
        val start = engine.start(FreeFlightStartRequest("shared-record-id","record-card-owner",departure,
            90.0,"01A",FreeFlightScene(3,"阅读","reading"),"AS42"),now)
        val at = now.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=wall+90_001)
        if (cancelled) return engine.cancel(start,at)
        val selection = engine.prepareLanding(start,at)
        val picked = engine.selectLanding(selection,destination,selection.landingSelectionGeneration)
        return engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(picked,picked.landingSelectionGeneration,at),at)
    }

    private fun countdown() = FlightHistoryEntity(
        "shared-record-id",null,"SOLO",null,"PEK","Beijing Airport","北京","CN",40.0,116.0,
        "TSN","Tianjin Airport","天津","CN",39.0,117.0,wall,wall+420_000,
        25,7,99.25,"COMPLETED",null,null,false,false,false,null,false,wall,wall,
        ownerScope="record-card-owner",
    )

    private fun context(): Context = ContextThemeWrapper(ApplicationProvider.getApplicationContext(), UiR.style.Theme_Pf)
    private fun text(card: View, id: Int): String = card.findViewById<TextView>(id).text.toString()

    @Test fun committedValuesAndCompositeIdentitySurviveNativeCardReuse() {
        val context = context()
        val card = RecordCardView(context)
        val up = RecordItemMapping.item(RecordedFlight.CountUp(archive()),zone)
        card.bind(up)
        assertEquals("AS42",text(card,R.id.pf_record_card_text_flight_number))
        assertEquals("01A",text(card,R.id.pf_record_card_text_seat))
        assertEquals("03NOV",text(card,R.id.pf_record_card_text_date))
        assertEquals("10:30",text(card,R.id.pf_record_card_text_departure_time))
        assertEquals("2 min",text(card,R.id.pf_record_card_text_focusTime))
        assertEquals("正计时",text(card,R.id.pf_record_card_text_flight_type_badge))
        assertEquals("阅读",text(card,R.id.pf_record_card_text_focus_scene))
        assertFalse(card.contentDescription.toString().contains("计划"))
        assertEquals(25f,card.findViewById<TextView>(R.id.pf_record_card_text_departure_code).textSize,0.01f)
        capture(card,"record-card-countup-day")

        val down = RecordItemMapping.item(RecordedFlight.Countdown(countdown()),zone)
        assertEquals(up.id,down.id)
        assertFalse(RecordListAdapter.DIFF.areItemsTheSame(RecordListAdapter.Row.Card(up,true),RecordListAdapter.Row.Card(down,true)))
        card.bind(down)
        val unknown = context.getString(R.string.pf_record_card_common_na)
        assertEquals(unknown,text(card,R.id.pf_record_card_text_flight_number))
        assertEquals(unknown,text(card,R.id.pf_record_card_text_seat))
        assertEquals("倒计时",text(card,R.id.pf_record_card_text_flight_type_badge))
        assertTrue(card.contentDescription.toString().contains("倒计时"))
        assertEquals(View.GONE,card.findViewById<View>(R.id.pf_record_card_text_focus_scene).visibility)
        assertEquals(View.GONE,card.findViewById<View>(R.id.pf_record_card_img_focus_icon).visibility)
        capture(card,"record-card-countdown-day")
        card.bind(null)
        assertEquals(View.INVISIBLE,card.visibility)
        assertEquals("",card.contentDescription)
        assertEquals(unknown,text(card,R.id.pf_record_card_text_departure_code))
    }

    @Test @Config(qualifiers="zh-rCN-w432dp-h960dp-night-mdpi")
    fun cancelledUnselectedFlightHasNoInventedDestinationInNightPresentation() {
        val card = RecordCardView(context())
        val value = archive(cancelled=true)
        assertNull(value.landingAirport)
        val item = RecordItemMapping.item(RecordedFlight.CountUp(value),zone)
        card.bind(item)
        assertEquals("--",text(card,R.id.pf_record_card_text_arrival_code))
        assertEquals("--",text(card,R.id.pf_record_card_text_arrival_city))
        assertEquals(Color.parseColor("#E62C2C2C"),card.cardBackgroundColor.defaultColor)
        assertFalse(item.arrivalSelected)
        capture(card,"record-card-countup-cancelled-night")
    }

    private fun capture(card: RecordCardView, name: String) {
        val parent = FrameLayout(card.context).apply {
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(card,
                com.google.android.material.R.attr.colorSurface))
            setPadding(16,0,16,0)
            addView(card)
        }
        try {
            parent.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500,View.MeasureSpec.AT_MOST))
            parent.layout(0,0,432,parent.measuredHeight)
            val bitmap = Bitmap.createBitmap(432,parent.measuredHeight,Bitmap.Config.ARGB_8888)
            try {
                parent.draw(Canvas(bitmap))
                val file = File("build/reports/record-card/$name.png")
                checkNotNull(file.parentFile).mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
            } finally { bitmap.recycle() }
        } finally { parent.removeView(card) }
    }
}
