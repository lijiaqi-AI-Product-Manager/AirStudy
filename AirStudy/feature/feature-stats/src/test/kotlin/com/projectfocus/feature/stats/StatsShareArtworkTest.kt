package com.projectfocus.feature.stats

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

internal val shareZone: ZoneId=ZoneId.of("Asia/Shanghai")
internal fun shareFixture(account: HistoryAccount?=null): StatsUiState {
    val day=LocalDate.of(2026,11,13)
    val dates=StatsDateSelection(day,StatsPeriod.DAY)
    return StatsUiState(flights=5,distanceKm=1234.25,focusMinutes=135,airports=3,countries=2,
        isLoading=false,account=account,dates=dates,dateRange=dates.range(shareZone),queryRevision=7,
        nowWallMs=day.atStartOfDay(shareZone).toInstant().toEpochMilli(),selectedSceneKey="reading")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="zh-rCN-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsShareArtworkTest {
    private val context: Context get()=ApplicationProvider.getApplicationContext()

    @Test fun selectedPeriodSceneAndAllFiveResultsRemainTogetherInTheArtwork() {
        val spec=StatsShareSpec.from(context,shareFixture(),shareZone)
        assertTrue(spec.period.contains("2026"));assertTrue(spec.period.contains("13"))
        assertEquals("阅读",spec.scene);assertEquals(135,spec.focusMinutes)
        assertEquals(listOf("5","1234.3","3","2"),spec.metrics.map { it.value })
        assertFalse(spec.partial);assertTrue(spec.summary.contains("135 分钟"))
        val view=measured(spec);assertComplete(view)
        val focus=view.findViewById<TextView>(R.id.statsShareArtworkFocus)
        assertTrue(focus.text.contains("2"));assertTrue(focus.text.contains("15"))
        save("statistics-share-artwork-day",StatsShareArtworkView.render(context,spec))
    }

    @Test fun unknownHistoryValuesRemainDistinctFromZeroAndKeepTheRecoveryNote() {
        val state=shareFixture().copy(coverage=StatsCoverage(periodFocus=false,periodDistance=false,periodTravel=false))
        val spec=StatsShareSpec.from(context,state,shareZone)
        assertNull(spec.focusMinutes);assertTrue(spec.partial)
        assertEquals(listOf("5","—","—","—"),spec.metrics.map { it.value })
        assertTrue(spec.metrics.drop(1).all { !it.available && it.unit.isEmpty() })
        assertTrue(spec.summary.contains("部分历史资料待恢复"))
        assertFalse(spec.summary.contains("1234.3"));assertFalse(spec.summary.contains("135 分钟"))
        assertComplete(measured(spec))
        save("statistics-share-artwork-partial",StatsShareArtworkView.render(context,spec))
    }

    @Test fun actualZeroIsExportedAsZeroForAnEmptyCompletedPeriod() {
        val spec=StatsShareSpec.from(context,shareFixture().copy(flights=0,distanceKm=0.0,focusMinutes=0,airports=0,countries=0,selectedSceneKey=""),shareZone)
        assertEquals(0,spec.focusMinutes);assertFalse(spec.partial)
        assertEquals(listOf("0","0.0","0","0"),spec.metrics.map { it.value })
        assertEquals(context.getString(R.string.stats_share_artwork_all_scenes),spec.scene)
        assertFalse(spec.summary.contains("—"))
        save("statistics-share-artwork-empty",StatsShareArtworkView.render(context,spec))
    }

    @Test fun anUnrelatedAnnualGapDoesNotMarkKnownSelectedPeriodResultsAsUnavailable() {
        val spec=StatsShareSpec.from(context,shareFixture().copy(coverage=StatsCoverage(annual=false,allCities=false)),shareZone)
        assertFalse(spec.partial);assertEquals(135,spec.focusMinutes)
        assertEquals(listOf("5","1234.3","3","2"),spec.metrics.map { it.value })
        assertEquals(context.getString(R.string.stats_history_source_note),spec.note)
    }

    @Test @Config(qualifiers="en-rUS-w360dp-h800dp-mdpi")
    fun theCallersLanguageIsUsedForLabelsDurationAndScene() {
        val spec=StatsShareSpec.from(context,shareFixture(),shareZone)
        assertEquals("Reading",spec.scene)
        assertEquals("Airports visited",spec.metrics[2].label)
        assertTrue(spec.summary.contains("135 min"));assertFalse(spec.summary.contains("分钟"))
        val single=StatsShareSpec.from(context,shareFixture().copy(flights=1,airports=1,countries=1),shareZone)
        assertEquals(listOf("flight","km","airport","region"),single.metrics.map { it.unit })
        assertEquals(listOf("flights","km","airports","regions"),spec.metrics.map { it.unit })
        assertComplete(measured(spec))
        save("statistics-share-artwork-en",StatsShareArtworkView.render(context,spec))
    }

    @Test fun fixedArtworkDoesNotChangeTheCallersFontPreference() {
        val spec=StatsShareSpec.from(context,shareFixture(),shareZone)
        val normal=StatsShareArtworkView.render(context,spec)
        try {
            for(scale in listOf(1.6f,2f)) {
                val large=context.createConfigurationContext(Configuration(context.resources.configuration).apply { fontScale=scale })
                val image=StatsShareArtworkView.render(large,spec)
                try {
                    assertEquals(scale,large.resources.configuration.fontScale,0f)
                    assertTrue("Export format must stay legible and independent of UI font scaling",normal.sameAs(image))
                } finally { image.recycle() }
            }
        } finally { normal.recycle() }
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h960dp-night-mdpi")
    fun nightArtworkKeepsTheFullCustomSceneAndDateRange() {
        val day=LocalDate.of(2026,11,13)
        val dates=StatsDateSelection(day,StatsPeriod.CUSTOM,day.minusDays(12),day)
        val scene="一个需要完整保留的自定义阅读与学习场景，以及这一段值得记录的专注旅程"
        val spec=StatsShareSpec.from(context,shareFixture().copy(dates=dates,dateRange=dates.range(shareZone),selectedSceneKey=scene,focusMinutes=3615),shareZone)
        assertEquals(scene,spec.scene);assertTrue(spec.summary.contains(scene))
        val view=measured(spec);assertComplete(view)
        val label=nodes(view).filterIsInstance<TextView>().single { it.text.toString()==scene }
        assertTrue(label.lineCount>1)
        save("statistics-share-artwork-night-long",StatsShareArtworkView.render(context,spec))
    }

    private fun measured(spec: StatsShareSpec): StatsShareArtworkView=StatsShareArtworkView(context,spec).apply {
        measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
        layout(0,0,measuredWidth,measuredHeight)
    }
    private fun nodes(view: View): List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun assertComplete(root: ViewGroup) {
        assertTrue(root.height>480)
        for(text in nodes(root).filterIsInstance<TextView>()) {
            val layout=requireNotNull(text.layout)
            val box=Rect(0,0,text.width,text.height);root.offsetDescendantRectToMyCoords(text,box)
            assertTrue("Text must remain inside the measured artwork: ${text.text}",box.left>=0 && box.right<=root.width && box.top>=0 && box.bottom<=root.height)
            assertTrue("Text must paint at its measured height: ${text.text}",text.height>=layout.height+text.compoundPaddingTop+text.compoundPaddingBottom)
            assertEquals(text.text.length,layout.getLineEnd(layout.lineCount-1))
            assertTrue((0 until layout.lineCount).all { layout.getEllipsisCount(it)==0 })
        }
    }
    private fun save(name: String,image: Bitmap) {
        try {
            assertEquals(1080,image.width);assertTrue(image.width.toLong()*image.height<=4_001_080L)
            File("build/reports/statistics/$name.png").apply { parentFile!!.mkdirs() }
                .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) }
        } finally { image.recycle() }
    }
}
