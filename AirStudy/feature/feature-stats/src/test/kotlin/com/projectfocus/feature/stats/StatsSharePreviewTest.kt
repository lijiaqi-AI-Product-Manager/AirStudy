package com.projectfocus.feature.stats

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ProviderInfo
import android.content.pm.ResolveInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.graphics.ColorUtils
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.service.focus.history.HistoryAccount
import java.io.File
import java.time.Duration
import java.util.concurrent.FutureTask
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w432dp-h936dp-mdpi",shadows=[StatsShareProviderHostShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StatsSharePreviewTest {
    private lateinit var controller: ActivityController<StatsShareHostActivity>
    private lateinit var activity: StatsShareHostActivity
    private lateinit var content: ViewGroup
    private lateinit var current: StatsUiState
    private lateinit var preview: StatsSharePreview
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val io=StandardTestDispatcher()
    private var visible=false
    private var queryCurrent=true
    private var foreground=true
    private var width=432
    private var height=936
    private lateinit var directory: File

    @Before fun prepare() {
        TokenStore.set("statistics-preview-owner")
        val owner=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation)
        current=shareFixture(owner)
        controller=Robolectric.buildActivity(StatsShareHostActivity::class.java).setup()
        activity=controller.get();content=activity.findViewById(android.R.id.content)
        directory=File(activity.cacheDir,"stats-share")
        cleanFiles()
        layout()
        preview=StatsSharePreview(activity,content,scope,shareZone,currentState={ current },
            isCurrentQuery={ account,revision -> queryCurrent && account==current.account && revision==current.queryRevision },
            canShow={ foreground && !activity.isFinishing },visibilityChanged={ visible=it },io=io)
        val cache=androidx.core.content.FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        (cache.get(null) as MutableMap<Any?,Any?>).clear()
        shadowOf(activity.packageManager).addOrUpdateProvider(ProviderInfo().apply {
            name=StatsArtworkFileProvider::class.java.name;authority="${activity.packageName}.statsfileprovider"
            exported=false;grantUriPermissions=true;packageName=activity.packageName;applicationInfo=activity.applicationInfo
            metaData=Bundle().apply { putInt("android.support.FILE_PROVIDER_PATHS",R.xml.stats_share_paths) }
        })
        shadowOf(activity.packageManager).addResolveInfoForIntent(receiverIntent(),ResolveInfo().apply {
            isDefault=true
            activityInfo=ActivityInfo().apply {
                name="ShareReceiver";packageName="statistics.test.receiver";exported=true;enabled=true
                applicationInfo=android.content.pm.ApplicationInfo().apply { packageName="statistics.test.receiver";enabled=true }
            }
        })
    }

    @After fun finish() {
        if(::preview.isInitialized)preview.close()
        scope.cancel();repeat(8) { io.scheduler.runCurrent();idle() }
        assertTrue(scope.coroutineContext[Job]!!.isCompleted)
        if(::directory.isInitialized)cleanFiles()
        if(::controller.isInitialized)controller.pause().stop().destroy()
        TokenStore.clear()
    }

    @Test fun exportedImageMatchesTheWholePreviewAndCarriesItsOriginalPeriodAndScene() {
        open()
        val button=button();val image=image()
        val surface=button.backgroundTintList!!.defaultColor
        assertTrue(ColorUtils.calculateContrast(button.currentTextColor,surface)>=4.5)
        assertTrue(image.width>=120 && image.height>=120)
        assertTrue(button.width>=48 && button.height>=48)
        assertTrue(content.findViewById<View>(R.id.statsSharePreviewClose).width>=48)
        val description=image.contentDescription.toString()
        assertTrue(description.contains("阅读"));assertTrue(description.contains("2026"));assertTrue(description.contains("1234.3"))
        save("statistics-share-preview-day",draw())
        button.performClick();idle()
        assertFalse(button.isEnabled);assertEquals(activity.getString(R.string.stats_share_artwork_preparing),button.text.toString())
        button.performClick()
        var chooser: Intent?=null;pump { chooser=shadowOf(activity).nextStartedActivity;chooser!=null }
        assertEquals(Intent.ACTION_CHOOSER,chooser!!.action)
        assertTrue(chooser!!.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        @Suppress("DEPRECATION")
        val send=chooser!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        @Suppress("DEPRECATION")
        val uri=send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("image/png",send.type);assertEquals("${activity.packageName}.statsfileprovider",uri.authority)
        assertEquals(description,send.getStringExtra(Intent.EXTRA_TEXT));assertEquals(uri,send.clipData!!.getItemAt(0).uri)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        val bytes=activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val exported=BitmapFactory.decodeByteArray(bytes,0,bytes.size)
        try { assertTrue(exported.sameAs((image.drawable as BitmapDrawable).bitmap));assertEquals(1080,exported.width) }
        finally { exported.recycle() }
        File("build/reports/statistics/statistics-share-export-day.png").apply { parentFile!!.mkdirs();writeBytes(bytes) }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(activity,"${activity.packageName}.statsfileprovider",File(activity.cacheDir,"outside-statistics.png"))
        }
        assertThrows(SecurityException::class.java) {
            activity.contentResolver.openInputStream(Uri.parse("content://${activity.packageName}.statsfileprovider/statistics_artwork/../outside-statistics.png"))
        }
    }

    @Test fun aNewQueryClosesThePreviewAndItsOldControlsCannotLaunchSharing() {
        open();val staleButton=button();val oldImage=image()
        current=current.copy(queryRevision=current.queryRevision+1,focusMinutes=180)
        preview.keepFor(current)
        assertFalse(visible);assertNull(content.findViewById<View>(R.id.statsSharePreview));assertNull(oldImage.drawable)
        staleButton.performClick();idle();assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue((0 until content.childCount).all { content.getChildAt(it).importantForAccessibility!=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS })
        open();assertTrue(image().contentDescription.toString().contains("180 分钟"))
        content.findViewById<View>(R.id.statsSharePreviewClose).performClick()
        assertFalse(visible);assertNull(content.findViewById<View>(R.id.statsSharePreview))
    }

    @Test fun anAccountChangeAfterEncodingDiscardsTheFileBeforeAnyHandoff() {
        open();button().performClick();idle();encodeWithoutResumingTheWindow()
        val files=files();assertEquals(1,files.size)
        TokenStore.set("statistics-preview-other-owner")
        current=current.copy(account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation))
        preview.keepFor(current)
        pump { files.none { it.exists() } }
        assertFalse(visible);assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun aQueryChangeBeforeItsNextRenderCannotSendThePreviousPreview() {
        open();button().performClick();idle();encodeWithoutResumingTheWindow()
        val files=files();assertEquals(1,files.size)
        queryCurrent=false
        pump { files.none { it.exists() } }
        assertFalse(visible);assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun closingWhileEncodingIsQueuedCancelsTheExportAndRestoresThePage() {
        open();button().performClick();idle();preview.close()
        repeat(8) { io.scheduler.runCurrent();idle() }
        assertFalse(visible);assertTrue(files().isEmpty());assertNull(shadowOf(activity).nextStartedActivity)
        assertNull(content.findViewById<View>(R.id.statsSharePreview))
    }

    @Test fun noImageReceiverProducesFeedbackWithoutLeavingAnExportFile() {
        shadowOf(activity.packageManager).removeResolveInfosForIntent(receiverIntent(),"statistics.test.receiver")
        open();val button=button();button.performClick();idle();assertFalse(button.isEnabled)
        pump { button.isEnabled && files().isEmpty() }
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.stats_share_artwork_no_target),ShadowToast.getTextOfLatestToast().toString())
        assertEquals(activity.getString(R.string.stats_share_artwork_action),button.text.toString())
    }

    @Test fun anActualCacheWriteFailureKeepsThePreviewUsableForRetry() {
        open();if(directory.isDirectory)assertTrue(directory.delete())
        assertFalse(directory.exists());directory.writeText("occupied")
        val button=button();button.performClick();idle();assertFalse(button.isEnabled)
        pump { button.isEnabled }
        assertTrue(visible);assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals(activity.getString(R.string.stats_share_artwork_failed),ShadowToast.getTextOfLatestToast().toString())
        assertTrue(directory.delete())
        button.performClick();var chooser: Intent?=null;pump { chooser=shadowOf(activity).nextStartedActivity;chooser!=null }
        assertEquals(Intent.ACTION_CHOOSER,chooser!!.action)
    }

    @Test fun aStaleAccountOrAnUnfinishedReadCannotOpenASharePreview() {
        TokenStore.set("statistics-preview-changed-owner")
        preview.open(current);idle()
        assertFalse(visible);assertNull(content.findViewById<View>(R.id.statsSharePreview))
        current=current.copy(account=HistoryAccount(TokenStore.storageScope(),TokenStore.snapshot().generation),isLoading=true)
        preview.open(current);idle()
        assertFalse(visible);assertTrue(files().isEmpty());assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anUnrelatedRefreshWhileTheAccountLeaseIsBusyStillOpensTheSameStatistics() {
        val captured=current;val identity=TokenStore.snapshot()
        content.layout(0, 0, 0, 0)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val held=FutureTask {
            runBlocking { TokenStore.withSnapshotLease(identity) {
                entered.countDown();check(release.await(8,TimeUnit.SECONDS))
            } }
        }
        Thread(held,"statistics-preview-identity-lease").apply { isDaemon=true;start() }
        try {
            assertTrue(entered.await(8,TimeUnit.SECONDS))
            preview.open(captured);idle();assertFalse(visible)
            current=captured.copy(heatmapRevision=captured.heatmapRevision+1)
            assertNotSame(captured,current)
            assertEquals(StatsShareSpec.from(activity,captured,shareZone),StatsShareSpec.from(activity,current,shareZone))
        } finally { release.countDown();held.get(8,TimeUnit.SECONDS) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        assertFalse(visible)
        layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
        pump { visible };layout()
        assertEquals(StatsShareSpec.from(activity,captured,shareZone).summary,image().contentDescription.toString())
        assertTrue(button().isEnabled)
        assertEquals(identity,TokenStore.snapshot())
    }

    @Test fun disabledAnimationsShowTheCompletePreviewImmediately() {
        val original=android.animation.ValueAnimator.getDurationScale()
        org.robolectric.util.ReflectionHelpers.callStaticMethod<Unit>(android.animation.ValueAnimator::class.java,"setDurationScale",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType,0f))
        try {
            layout();preview.open(current);idle();layout()
            assertTrue(visible)
            assertEquals(1f,content.findViewById<View>(R.id.statsSharePreview).alpha,0f)
            assertEquals(1.02f,image().scaleX,0f);assertEquals(1.02f,image().scaleY,0f)
            assertTrue(button().isEnabled)
            save("statistics-share-preview-reduced-motion",draw())
        } finally {
            org.robolectric.util.ReflectionHelpers.callStaticMethod<Unit>(android.animation.ValueAnimator::class.java,"setDurationScale",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType,original))
        }
    }

    @Test @Config(qualifiers="zh-rCN-w320dp-h960dp-night-mdpi")
    @Suppress("DEPRECATION")
    fun nightLargeTypePreviewKeepsTheFullImageCloseAndShareControlsVisible() {
        width=320;height=960
        val configuration=Configuration(activity.resources.configuration).apply { fontScale=2f }
        activity.resources.updateConfiguration(configuration,activity.resources.displayMetrics)
        current=current.copy(selectedSceneKey="一个需要完整保留的自定义阅读与学习场景，以及一段值得记录的专注旅程")
        open()
        val image=image();val button=button();val close=content.findViewById<View>(R.id.statsSharePreviewClose)
        val extra=image.height*(image.scaleY-1f)/2f
        assertTrue(image.width>=120 && image.height>=120)
        assertTrue(image.top-extra>=close.bottom);assertTrue(image.bottom+extra<=button.top)
        assertTrue(button.width>=48 && button.height>=48 && button.bottom<=height)
        assertTrue(close.width>=48 && close.height>=48)
        assertEquals(2f,activity.resources.configuration.fontScale,0f)
        assertTrue(image.contentDescription.toString().contains(current.selectedSceneKey))
        save("statistics-share-preview-night-large",draw())
        val bitmap=(image.drawable as BitmapDrawable).bitmap
        File("build/reports/statistics/statistics-share-export-night-large.png").apply { parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }

    private fun receiverIntent()=Intent(Intent.ACTION_SEND).setType("image/png")
    private fun button()=content.findViewById<ExtendedFloatingActionButton>(R.id.statsSharePreviewAction)
    private fun image()=content.findViewById<ImageView>(R.id.statsSharePreviewImage)
    private fun idle()=shadowOf(Looper.getMainLooper()).idle()
    private fun layout() {
        content.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
        content.layout(0,0,width,height)
    }
    private fun open() {
        layout();preview.open(current);pump { visible };layout()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));layout()
    }
    private fun pump(condition: () -> Boolean) {
        val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
        var done=condition()
        while(!done && System.nanoTime()<end) { io.scheduler.runCurrent();idle();Thread.sleep(5);done=condition() }
        if(!done)throw AssertionError("Preview visible=$visible, query current=$queryCurrent, latest feedback=${ShadowToast.getTextOfLatestToast()}",
            org.robolectric.shadows.ShadowLog.getLogsForTag("AirStudyShare").lastOrNull()?.throwable)
    }
    private fun encodeWithoutResumingTheWindow() {
        val task=FutureTask { io.scheduler.runCurrent() };Thread(task,"statistics-preview-encode").start();task.get(8,TimeUnit.SECONDS)
    }
    private fun files()=directory.listFiles().orEmpty().filter { it.name.startsWith("stats-") && it.extension=="png" }
    private fun cleanFiles() {
        if(directory.isFile)assertTrue(directory.delete())
        else directory.listFiles().orEmpty().filter { it.isFile && it.name.startsWith("stats-") && it.extension=="png" }.forEach { assertTrue(it.delete()) }
    }
    private fun draw()=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also { content.draw(Canvas(it)) }
    private fun save(name: String,image: Bitmap) {
        try { File("build/reports/statistics/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,it)) } }
        finally { image.recycle() }
    }
}
