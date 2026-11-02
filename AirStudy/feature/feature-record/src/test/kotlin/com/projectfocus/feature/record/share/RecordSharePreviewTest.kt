package com.projectfocus.feature.record.share

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordItemMapping
import com.projectfocus.feature.record.ui.RecordCardView
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.service.focus.history.RecordedFlight
import java.io.File
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34], qualifiers = "zh-rCN-w432dp-h936dp-mdpi",
    shadows = [HostFileProviderStrategyShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordSharePreviewTest {
    private lateinit var scenario: ActivityScenario<RecordPreviewHostActivity>
    private lateinit var activity: RecordPreviewHostActivity
    private lateinit var content: ViewGroup
    private lateinit var card: RecordCardView
    private lateinit var item: RecordItem
    private lateinit var preview: RecordSharePreview
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val io = StandardTestDispatcher()
    private var visible = false
    private lateinit var owner: HistoryAccount

    @Before fun open() {
        TokenStore.set("record-preview-account")
        owner = HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        scenario = ActivityScenario.launch(RecordPreviewHostActivity::class.java)
        scenario.onActivity { actual ->
            activity = actual
            content = actual.findViewById(android.R.id.content)
            val now = FreeFlightClockReading(0,0,1_793_665_800_000,"preview-clock")
            val engine = FreeFlightEngine()
            val start = engine.start(FreeFlightStartRequest("preview-record",owner.scope,
                PlannedAirport(1,"PEK","Beijing Airport","北京","CN",40.0,116.0),90.0,"01A",
                FreeFlightScene(3,"阅读","reading"),"AS42"),now)
            val at = now.copy(elapsedRealtimeMs=90_001,uptimeMillis=90_001,wallMs=now.wallMs+90_001)
            val select = engine.prepareLanding(start,at)
            val pick = engine.selectLanding(select,PlannedAirport(2,"TSN","Tianjin Airport","天津","CN",39.0,117.0),select.landingSelectionGeneration)
            val completed = engine.completeLandingWithoutMap(engine.beginLandingWithoutMap(pick,pick.landingSelectionGeneration,at),at)
            item = RecordItemMapping.item(RecordedFlight.CountUp(completed),ZoneId.of("Asia/Shanghai"))
            card = RecordCardView(actual).apply { bind(item) }
            (content.getChildAt(0) as FrameLayout).addView(card,FrameLayout.LayoutParams(400,-2).apply { leftMargin=16; topMargin=120 })
            content.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY))
            content.layout(0,0,432,936)
            preview = RecordSharePreview(actual,content,scope,canShow={ !actual.isFinishing },visibilityChanged={ visible=it },io=io)
        }
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableMap<Any?,Any?>).clear()
        val info = ProviderInfo().apply {
            name="androidx.core.content.FileProvider"; authority="${activity.packageName}.fileprovider"
            exported=false; grantUriPermissions=true; packageName=activity.packageName; applicationInfo=activity.applicationInfo
            metaData=Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS",R.xml.record_file_paths) }
        }
        shadowOf(activity.packageManager).addOrUpdateProvider(info)
    }

    @After fun close() {
        preview.close()
        scope.cancel()
        repeat(8) { io.scheduler.runCurrent(); idle() }
        assertTrue(scope.coroutineContext[Job]!!.isCompleted)
        scenario.close()
        TokenStore.clear()
    }
    private fun idle() { shadowOf(Looper.getMainLooper()).idle() }
    private fun pump(condition: () -> Boolean) {
        val end = System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        var done = condition()
        while (!done && System.nanoTime()<end) { io.scheduler.runCurrent(); idle(); Thread.sleep(5); done=condition() }
        assertTrue(done)
    }

    @Test fun actualCardPreviewExportsItsOwnPixelsWithReadPermission() {
        preview.open(card,item,owner)
        pump { visible }
        assertNotNull(content.findViewById<View>(R.id.pf_record_page_share_preview))
        assertEquals(item.spokenSummary,content.findViewById<View>(R.id.pf_record_page_share_card_image).contentDescription)
        content.findViewById<View>(R.id.pf_record_page_share_button).performClick()
        var chooser: Intent? = null
        pump { chooser = shadowOf(activity).nextStartedActivity; chooser!=null }
        assertEquals(Intent.ACTION_CHOOSER,chooser!!.action)
        assertTrue(chooser!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        @Suppress("DEPRECATION")
        val send = chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("image/png",send.type)
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.startsWith("AirStudy\n"))
        assertTrue(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("正计时"))
        assertFalse(send.getStringExtra(Intent.EXTRA_TEXT)!!.contains("计划"))
        assertEquals(uri,send.clipData!!.getItemAt(0).uri)
        val bytes = activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bitmap = BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        assertEquals(card.width,bitmap.width)
        assertEquals(card.height,bitmap.height)
        bitmap.recycle()
        assertThrows(IllegalArgumentException::class.java) { ShareIntentBuilder.uriFor(activity,File(activity.cacheDir,"outside-share.png")) }
        val escaped = Uri.parse("content://${activity.packageName}.fileprovider/share_cards/../outside-share.png")
        assertThrows(SecurityException::class.java) { activity.contentResolver.openInputStream(escaped) }
        val artifact = File("build/reports/record-card/record-card-share.png")
        artifact.parentFile!!.mkdirs(); artifact.writeBytes(bytes)
    }

    @Test fun identityChangeAfterEncodingDiscardsTheImageBeforeAnyShareIsLaunched() {
        preview.open(card,item,owner)
        pump { visible }
        content.findViewById<View>(R.id.pf_record_page_share_button).performClick()
        idle()
        val encoded = java.util.concurrent.FutureTask { io.scheduler.runCurrent() }
        Thread(encoded,"record-preview-encode").start()
        encoded.get(8,TimeUnit.SECONDS)
        val exported = File(activity.cacheDir,"share").listFiles().orEmpty().filter { it.name.startsWith("record-") }
        assertEquals(1,exported.size)
        TokenStore.set("record-preview-other-account")
        preview.keepFor(null,emptySet())
        pump { exported.none { it.exists() } }
        assertFalse(visible)
        assertNull(content.findViewById<View>(R.id.pf_record_page_share_preview))
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
