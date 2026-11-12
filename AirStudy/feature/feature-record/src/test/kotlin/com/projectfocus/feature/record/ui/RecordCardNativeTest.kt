package com.projectfocus.feature.record.ui

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import android.graphics.Rect
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
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
import com.projectfocus.core.model.flight.PlannedScene
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItemMapping
import com.projectfocus.service.focus.history.RecordedFlight
import com.projectfocus.service.focus.history.RecordedScene
import com.projectfocus.service.focus.history.FlightRecordKey
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
        assertEquals(card.context.getColor(UiR.color.sky_card_solid),card.cardBackgroundColor.defaultColor)
        assertFalse(item.arrivalSelected)
        capture(card,"record-card-countup-cancelled-night")
    }

    @Test fun sceneActionKeepsTheModeIdentityAndDisappearsForNonEditableOrRecycledRows() {
        val card = RecordCardView(context())
        val chosen = PlannedScene("work-option", "", "work", 0xFF2B71C2.toInt(), false, displayName = "工作")
        val up = RecordItemMapping.item(RecordedFlight.CountUp(archive(), RecordedScene(chosen, 2)), zone)
        val down = RecordItemMapping.item(RecordedFlight.Countdown(countdown()), zone)
        val calls = mutableListOf<FlightRecordKey>()
        val action: (View, com.projectfocus.feature.record.list.RecordItem) -> Unit = { _, item -> calls += item.key }
        val edit = card.findViewById<View>(R.id.pf_record_card_img_focus_scene_edit)
        card.bind(up, action)
        assertEquals(View.VISIBLE, edit.visibility)
        assertEquals("工作", text(card, R.id.pf_record_card_text_focus_scene))
        assertTrue(card.contentDescription.toString().contains("场景 工作"))
        assertEquals(2L, up.sceneRevision)
        edit.performClick()
        assertEquals(listOf(up.key), calls)
        capture(card, "record-card-scene-edit")
        card.bind(down, action)
        edit.performClick()
        assertEquals(listOf(up.key, down.key), calls)
        assertNotEquals(up.key, down.key)
        card.bind(up, action, sceneEditingEnabled = false)
        assertFalse(edit.isEnabled)
        edit.performClick()
        assertEquals(2, calls.size)
        val active = RecordItemMapping.item(RecordedFlight.Countdown(countdown().copy(status = "ONGOING", endTimeMillis = null)), zone)
        val cancelled = RecordItemMapping.item(RecordedFlight.CountUp(archive(cancelled = true)), zone)
        listOf(active, cancelled, null).forEach { item ->
            card.bind(item, action)
            assertEquals(View.GONE, edit.visibility)
            edit.performClick()
            assertEquals(2, calls.size)
        }
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h568dp-notnight-mdpi")
    fun enlargedTicketKeepsActualValuesSceneTargetAndReadableLabels() {
        for(scale in listOf(1.6f,2f)) {
            val base=context()
            val configuration=Configuration(base.resources.configuration).apply { fontScale=scale }
            val context=ContextThemeWrapper(base.createConfigurationContext(configuration),UiR.style.Theme_Pf)
            val card=RecordCardView(context)
            card.bind(RecordItemMapping.item(RecordedFlight.CountUp(archive()),zone), { _, _ -> })
            val renderedSurface=capture(card,"record-ticket-small-$scale",320)
            val edit=card.findViewById<View>(R.id.pf_record_card_img_focus_scene_edit)
            assertTrue(edit.width>=48 && edit.height>=48)
            val title=card.findViewById<TextView>(R.id.pf_record_card_text_departure_code)
            assertEquals(scale,title.resources.configuration.fontScale,.01f)
            assertEquals(Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,25f,title.resources.displayMetrics)).toFloat(),title.textSize,.01f)
            assertTrue(title.textSize>25f)
            val ids=listOf(R.id.pf_record_card_text_flight_number,R.id.pf_record_card_text_departure_time,
                R.id.pf_record_card_text_date,R.id.pf_record_card_text_focus_scene,
                R.id.pf_record_card_text_focusTime,R.id.pf_record_card_text_seat)
            val rectangles=ids.map { id ->
                val value=card.findViewById<TextView>(id)
                assertTrue(value.width>0 && value.height>0)
                for(line in 0 until value.layout.lineCount)assertEquals(0,value.layout.getEllipsisCount(line))
                Rect(0,0,value.width,value.height).also { card.offsetDescendantRectToMyCoords(value,it) }
            }
            for(rect in rectangles)assertTrue(rect.left>=0 && rect.right<=card.width && rect.bottom<=card.height)
            for(index in rectangles.indices)for(other in index+1 until rectangles.size)
                assertFalse(Rect.intersects(rectangles[index],rectangles[other]))
            fun textViews(view: View): List<TextView> = when(view) {
                is TextView -> listOf(view)
                is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
                else -> emptyList()
            }
            for(label in textViews(card).filter { it.visibility==View.VISIBLE && it.text.isNotBlank() }) {
                val background=if(label.id==R.id.pf_record_card_text_flight_type_badge)
                    context.getColor(UiR.color.sky_selected) else renderedSurface
                assertTrue("Unreadable ticket label: ${label.text}",ColorUtils.calculateContrast(label.currentTextColor,background)>=4.5)
            }
        }
    }

    private fun capture(card: RecordCardView, name: String, width: Int = 432): Int {
        val parent = FrameLayout(card.context).apply {
            setBackgroundColor(card.context.getColor(UiR.color.sky_canvas))
            setPadding(16,0,16,0)
            addView(card)
        }
        try {
            parent.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000,View.MeasureSpec.AT_MOST))
            parent.layout(0,0,width,parent.measuredHeight)
            val bitmap = Bitmap.createBitmap(width,parent.measuredHeight,Bitmap.Config.ARGB_8888)
            try {
                parent.draw(Canvas(bitmap))
                val file = File("build/reports/record-card/$name.png")
                checkNotNull(file.parentFile).mkdirs()
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
                return bitmap.getPixel(width/2,20)
            } finally { bitmap.recycle() }
        } finally { parent.removeView(card) }
    }
}
