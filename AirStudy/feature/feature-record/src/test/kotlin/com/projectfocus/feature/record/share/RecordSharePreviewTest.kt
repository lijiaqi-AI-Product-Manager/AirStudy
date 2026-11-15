package com.projectfocus.feature.record.share

import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.projectfocus.core.model.flight.PlannedAirport
import com.projectfocus.core.model.flight.freeflight.*
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.core.network.dto.FlightRecordSummary
import kotlinx.serialization.json.*
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
        content.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY))
        content.layout(0,0,432,936)
        val share=content.findViewById<ExtendedFloatingActionButton>(R.id.pf_record_page_share_button)
        val surface=requireNotNull(share.backgroundTintList).defaultColor
        assertEquals(255,Color.alpha(surface))
        assertTrue(ColorUtils.calculateContrast(share.currentTextColor,surface)>=4.5)
        assertTrue(share.width>=48 && share.height>=48)
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
        assertEquals(ShareArtwork.OUTPUT_WIDTH,bitmap.width)
        val displayed=content.findViewById<android.widget.ImageView>(R.id.pf_record_page_share_card_image)
        assertTrue(displayed.width>=120 && displayed.height>=120)
        assertTrue(bitmap.sameAs((displayed.drawable as BitmapDrawable).bitmap))
        assertTrue(bitmap.width>card.width)
        assertTrue(bitmap.height>card.height)
        bitmap.recycle()
        assertThrows(IllegalArgumentException::class.java) { ShareIntentBuilder.uriFor(activity,File(activity.cacheDir,"outside-share.png")) }
        val escaped = Uri.parse("content://${activity.packageName}.fileprovider/share_cards/../outside-share.png")
        assertThrows(SecurityException::class.java) { activity.contentResolver.openInputStream(escaped) }
        val artifact = File("build/reports/record-card/record-card-share.png")
        artifact.parentFile!!.mkdirs(); artifact.writeBytes(bytes)
    }

    @Test fun aSummaryWithTheSameLocalKeyCannotKeepAnOldFullRecordPreviewAlive() {
        preview.open(card,item,owner)
        pump { visible }
        preview.keepFor(owner, listOf(item.copy(flightNumber = "AS321")))
        assertFalse(visible)
        preview.open(card,item,owner)
        pump { visible }
        val summary = FlightRecordSummary.parse(buildJsonObject {
            put("flightRecordId", item.id); put("recordType", "up"); put("flightStatus", "arrived")
        })
        preview.keepFor(owner, listOf(item.copy(cloudSummary = summary)))
        assertFalse(visible)
        assertNull(content.findViewById<View>(R.id.pf_record_page_share_preview))
        assertNull(shadowOf(activity).nextStartedActivity)
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
        preview.keepFor(null,emptyList())
        pump { exported.none { it.exists() } }
        assertFalse(visible)
        assertNull(content.findViewById<View>(R.id.pf_record_page_share_preview))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun exportingDoesNotChangeTheEditableCardOrItsSceneAction() {
        var edited=0
        card.bind(item,onSceneEdit={_,_->edited++})
        content.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY))
        content.layout(0,0,432,936)
        val width=card.width;val height=card.height
        preview.open(card,item,owner);pump { visible }
        repeat(3) { content.measure(View.MeasureSpec.makeMeasureSpec(432,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY));content.layout(0,0,432,936);idle() }
        assertEquals(width,card.width);assertEquals(height,card.height)
        val edit=card.findViewById<View>(R.id.pf_record_card_img_focus_scene_edit)
        assertEquals(View.VISIBLE,edit.visibility);assertTrue(edit.isEnabled)
        val bitmap=(content.findViewById<android.widget.ImageView>(R.id.pf_record_page_share_card_image).drawable as BitmapDrawable).bitmap
        File("build/reports/record-card/record-share-editable-export.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        preview.close();edit.performClick();assertEquals(1,edited)
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h936dp-night-mdpi")
    fun largeTypeNightPreviewFitsItsWholeArtworkAndKeepsSharingReachable() {
        val config=android.content.res.Configuration(activity.resources.configuration).apply { fontScale=2f }
        val themed=android.view.ContextThemeWrapper(activity.createConfigurationContext(config),com.projectfocus.core.ui.R.style.Theme_Pf)
        val parent=card.parent as FrameLayout
        parent.removeView(card)
        item=item.copy(sceneTitle="一个需要完整保留的自定义阅读与学习场景",flightNumber="AS2645")
        card=RecordCardView(themed).apply { bind(item) }
        parent.addView(card,FrameLayout.LayoutParams(288,-2).apply { leftMargin=16;topMargin=60 })
        fun layout() {
            repeat(3) {
                content.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(936,View.MeasureSpec.EXACTLY))
                content.layout(0,0,320,936);idle()
            }
        }
        layout();preview.open(card,item,owner);pump { visible };layout()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));layout()
        val image=content.findViewById<android.widget.ImageView>(R.id.pf_record_page_share_card_image)
        val button=content.findViewById<View>(R.id.pf_record_page_share_button)
        val actual=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888)
        content.draw(Canvas(actual))
        File("build/reports/record-card/record-share-night-large-preview.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(actual.compress(Bitmap.CompressFormat.PNG,100,it)) }
        actual.recycle()
        assertEquals(item.spokenSummary,image.contentDescription)
        assertTrue("Artwork must be visible at a useful size: ${image.width} x ${image.height}",image.width>=120 && image.height>=120)
        assertTrue(image.left>=0 && image.right<=content.width && image.top>=0 && image.bottom<=content.height)
        val bottom=image.bottom+image.height*(image.scaleY-1f)/2f
        assertTrue("Artwork bottom=$bottom, button top=${button.top}, image=${image.top}..${image.bottom}, scale=${image.scaleY}, parent=${content.height}",bottom<=button.top)
        assertTrue(button.width>=48 && button.height>=48 && button.bottom<=content.height)
        val export=(image.drawable as BitmapDrawable).bitmap
        assertTrue(export.width<=ShareArtwork.OUTPUT_WIDTH)
        assertTrue(export.width.toLong()*export.height<=4_000_000L+ShareArtwork.OUTPUT_WIDTH)
        File("build/reports/record-card/record-share-night-large-export.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(export.compress(Bitmap.CompressFormat.PNG,100,it)) }
        val bitmap=Bitmap.createBitmap(content.width,content.height,Bitmap.Config.ARGB_8888)
        content.draw(Canvas(bitmap))
        File("build/reports/record-card/record-share-night-large-preview.png").outputStream()
            .use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        bitmap.recycle()
    }
}
