package com.projectfocus.app.auth

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast


@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthPromoImageLoaderTest {
    private lateinit var application: Application
    private lateinit var activity: ActivityController<Activity>
    private lateinit var container: FrameLayout
    private lateinit var directory: File
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private val handles = mutableListOf<AutoCloseable>()
    private val releases = mutableListOf<CountDownLatch>()
    private val finished = AtomicInteger()
    private val canceledPaths = ConcurrentLinkedQueue<String>()
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val now = epoch("2026-10-31T10:00:00+08:00[Asia/Shanghai]")
    private val cache: File get() = File(directory, AuthPromoImageLoader.CACHE_FILE_NAME)

    @Before fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        directory = Files.createTempDirectory(application.cacheDir.toPath(), "promo-http-").toFile()
        activity = Robolectric.buildActivity(Activity::class.java).setup().visible()
        container = FrameLayout(activity.get())
        activity.get().setContentView(container)
        ShadowToast.reset()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { finished.incrementAndGet() }
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceledPaths += call.request().url.encodedPath
                finished.incrementAndGet()
            }
        }).build()
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() {
        handles.asReversed().forEach(AutoCloseable::close)
        releases.forEach(CountDownLatch::countDown)
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        activity.pause().stop().destroy()
        // Only this test's verified temporary child, never a computed user path.
        check(directory.canonicalFile.parentFile == application.cacheDir.canonicalFile)
        directory.walkBottomUp().forEach { it.delete() }
        ShadowToast.reset()
    }

    @Test fun `same device calendar day uses real cache synchronously and keeps source outline and fade`() {
        writeBitmap(cache, Bitmap.CompressFormat.JPEG, 90)
        check(cache.setLastModified(epoch("2026-10-31T00:01:00+08:00[Asia/Shanghai]")))
        val view = view()
        bind(loader(), view)
        assertThat(view.bitmapAssignments).isEqualTo(1)
        assertThat(view.lastBitmap?.width).isEqualTo(12)
        assertThat(server.requestCount).isEqualTo(0)
        assertThat(view.clipToOutline).isTrue()
        val outline = Outline().also { view.outlineProvider.getOutline(view, it) }
        val rect = Rect()
        assertThat(outline.getRect(rect)).isTrue()
        assertThat(rect).isEqualTo(Rect(0, 0, 160, 120))
        assertThat(outline.radius).isEqualTo(24f * view.resources.displayMetrics.density)
        assertThat(view.animate().duration).isEqualTo(600L)
        assertThat(view.animate().interpolator).isInstanceOf(AccelerateDecelerateInterpolator::class.java)
        shadowOf(Looper.getMainLooper()).idleFor(650, TimeUnit.MILLISECONDS)
        assertThat(view.alpha).isWithin(0.001f).of(1f)
        noToast()
    }

    @Test fun `crossing local midnight and same day of year in another year invalidate rather than using rolling 24h`() {
        val current = epoch("2026-01-01T00:02:00+08:00[Asia/Shanghai]")
        val staleTimes = listOf(
            epoch("2025-12-31T23:59:00+08:00[Asia/Shanghai]"), // only three minutes ago
            epoch("2025-01-01T00:01:00+08:00[Asia/Shanghai]"), // same DAY_OF_YEAR, wrong YEAR
        )
        for ((index, stale) in staleTimes.withIndex()) {
            writeBitmap(cache, Bitmap.CompressFormat.JPEG, 90)
            check(cache.setLastModified(stale))
            server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
            val view = view()
            view.setImageDrawable(ColorDrawable(Color.MAGENTA))
            bind(loader(clock = { current }), view)
            assertThat(view.drawable).isNull()
            assertThat(view.alpha).isEqualTo(0f)
            assertThat(cache.exists()).isFalse()
            assertThat(server.takeRequest(3, TimeUnit.SECONDS)?.url?.encodedPath).isEqualTo("/auth/promo-image")
            await("metadata failure finished") { finished.get() >= index + 1 }
            assertThat(view.bitmapAssignments).isEqualTo(0)
            noToast()
        }
    }

    @Test fun `same day corrupt cache fetches PNG then writes exact JPEG90 and a reconstructed loader decodes it`() {
        cache.writeText("this is not an image")
        check(cache.setLastModified(now))
        val encodedPng = png()
        val decoded = BitmapFactory.decodeByteArray(encodedPng, 0, encodedPng.size)!!
        val expected = ByteArrayOutputStream().also { check(decoded.compress(Bitmap.CompressFormat.JPEG, 90, it)) }.toByteArray()
        respondImage()
        val view = view()
        bind(loader(), view)
        await("PNG render and complete JPEG90 bytes") {
            view.lastBitmap != null && cache.isFile && cache.readBytes().contentEquals(expected)
        }
        assertThat(server.requestCount).isEqualTo(2)
        assertThat(view.lastBitmap?.width).isEqualTo(12)
        val actual = cache.readBytes()
        assertThat(actual).isEqualTo(expected)
        assertThat(actual.take(2)).containsExactly(0xff.toByte(), 0xd8.toByte()).inOrder()
        assertThat(BitmapFactory.decodeFile(cache.absolutePath)?.height).isEqualTo(8)
        awaitBackground("cache timestamp can be set after write") { cache.setLastModified(now) }
        val recreated = view()
        bind(loader(), recreated)
        assertThat(recreated.bitmapAssignments).isEqualTo(1)
        assertThat(recreated.lastBitmap?.width).isEqualTo(12)
        assertThat(server.requestCount).isEqualTo(2)
        decoded.recycle()
        noToast()
    }

    @Test fun `source org json coercion keeps returned URL raw and both actual GETs stay anonymous`() {
        val inheritedInterceptorCalls = AtomicInteger()
        val withAccountInterceptor = client.newBuilder().addInterceptor { chain ->
            inheritedInterceptorCalls.incrementAndGet()
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer forbidden-own-test-token").build())
        }.build()
        val rawUrl = "  ${server.url("/raw%20image.png?label=a%2Bb")}  "
        val text = JSONObject().put("success", "true").put("data", JSONObject().put("url", rawUrl)).toString()
        assertThat(AuthPromoImageLoader.imageUrlFromMetadata(text)).isEqualTo(rawUrl)
        assertThat(AuthPromoImageLoader.imageUrlFromMetadata("""{"success":"true","data":{"url":123}}""")).isEqualTo("123")
        assertThat(AuthPromoImageLoader.imageUrlFromMetadata("""{"success":true,"data":"{\"url\":\"not-an-object\"}"}""")).isNull()
        server.enqueue(MockResponse.Builder().body(text).build())
        server.enqueue(imageResponse())
        val view = view()
        bind(loader(http = withAccountInterceptor), view)
        await("lenient metadata reached image render") { view.lastBitmap != null }
        val first = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        val second = checkNotNull(server.takeRequest(3, TimeUnit.SECONDS))
        assertThat(first.method).isEqualTo("GET")
        assertThat(first.url.encodedPath).isEqualTo("/auth/promo-image")
        assertThat(first.headers["Accept"]).isEqualTo("application/json")
        assertThat(second.method).isEqualTo("GET")
        assertThat(second.url.encodedPath).isEqualTo("/raw%20image.png")
        assertThat(second.url.encodedQuery).isEqualTo("label=a%2Bb")
        assertThat(second.headers["Accept"]).isEqualTo("image/*")
        for (request in listOf(first, second)) {
            assertThat(request.headers["Authorization"]).isNull()
            assertThat(request.headers["Cookie"]).isNull()
            assertThat(request.bodySize).isEqualTo(0L)
        }
        assertThat(inheritedInterceptorCalls.get()).isEqualTo(0)
        noToast()
    }

    @Test fun `metadata non2xx false missing URL malformed object and timeout remain cleared without a visible error`() {
        val valid = metadata(server.url("/must-not-be-requested").toString())
        val cases = listOf(
            MockResponse.Builder().code(503).body(valid).build(),
            MockResponse.Builder().body(JSONObject(valid).put("success", false).toString()).build(),
            MockResponse.Builder().body("""{"success":true,"data":{}}""").build(),
            MockResponse.Builder().body("""{"success":true,"data":{"url":""}}""").build(),
            MockResponse.Builder().body("not JSON").build(),
            MockResponse.Builder().body(valid).headersDelay(1, TimeUnit.SECONDS).build(),
        )
        for ((index, response) in cases.withIndex()) {
            val previousRequests = server.requestCount
            val previousFinished = finished.get()
            server.enqueue(response)
            val view = view()
            view.setImageDrawable(ColorDrawable(Color.MAGENTA))
            bind(loader(timeout = if (index == cases.lastIndex) 120L else 3_000L), view)
            assertThat(server.takeRequest(3, TimeUnit.SECONDS)).isNotNull()
            await("silent metadata rejection") { finished.get() > previousFinished }
            assertThat(server.takeRequest(150, TimeUnit.MILLISECONDS)).isNull()
            assertThat(server.requestCount).isEqualTo(previousRequests + 1)
            assertThat(view.bitmapAssignments).isEqualTo(0)
            assertThat(view.drawable).isNull()
            assertThat(view.alpha).isEqualTo(0f)
            assertThat(cache.exists()).isFalse()
            noToast()
        }
    }

    @Test fun `non2xx image response still decodes a real PNG and caches its JPEG`() {
        respondImage(imageStatus = 503)
        val view = view()
        bind(loader(), view)
        await("503 image body decoded") { view.lastBitmap != null && cache.length() > 0L }
        assertThat(view.lastBitmap?.getPixel(0, 0)).isEqualTo(Color.RED)
        assertThat(BitmapFactory.decodeFile(cache.absolutePath)?.width).isEqualTo(12)
        assertThat(server.requestCount).isEqualTo(2)
        noToast()
    }

    @Test fun `cache write failure cannot retract the already posted bitmap`() {
        check(cache.mkdir())
        File(cache, "not-removable-child").writeText("fixture")
        respondImage()
        val view = view()
        bind(loader(), view)
        await("render survives failed FileOutputStream") { view.lastBitmap != null && finished.get() >= 2 }
        assertThat(view.lastBitmap?.width).isEqualTo(12)
        assertThat(cache.isDirectory).isTrue()
        assertThat(File(cache, "not-removable-child").readText()).isEqualTo("fixture")
        assertThat(server.requestCount).isEqualTo(2)
        noToast()
    }

    @Test fun `undecodable fresh body posts null and can leave an empty cache instead of inventing a photo`() {
        server.enqueue(MockResponse.Builder().body(metadata(server.url("/broken-image").toString())).build())
        server.enqueue(MockResponse.Builder().body("not a PNG or JPEG").build())
        val view = view()
        bind(loader(), view)
        await("null decode posted and cache open attempted") { view.bitmapAssignments == 1 && cache.isFile }
        assertThat(view.lastBitmap).isNull()
        // ImageView.setImageBitmap(null) installs an empty BitmapDrawable on
        // SDK35. The source render still posted null; it did not install a photo.
        assertThat(view.drawable).isInstanceOf(BitmapDrawable::class.java)
        assertThat((view.drawable as BitmapDrawable).bitmap).isNull()
        assertThat(cache.length()).isEqualTo(0L)
        assertThat(view.animate().duration).isEqualTo(600L)
        assertThat(BitmapFactory.decodeFile(cache.absolutePath)).isNull()
        noToast()
    }

    @Test fun `close cancels only its real image request and removes a late queued render`() {
        val metadataCount = AtomicInteger()
        val seenA = CountDownLatch(1)
        val seenB = CountDownLatch(1)
        val releaseA = CountDownLatch(1).also { releases += it }
        val releaseB = CountDownLatch(1).also { releases += it }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/auth/promo-image" -> {
                    val target = when (metadataCount.getAndIncrement()) { 0 -> "/image-a"; 1 -> "/image-b"; else -> "/image-c" }
                    MockResponse.Builder().body(metadata(server.url(target).toString())).build()
                }
                "/image-a" -> { seenA.countDown(); check(releaseA.await(5, TimeUnit.SECONDS)); imageResponse() }
                "/image-b" -> { seenB.countDown(); check(releaseB.await(5, TimeUnit.SECONDS)); imageResponse() }
                "/image-c" -> imageResponse()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        val loader = loader()
        val first = view()
        val second = view()
        val firstHandle = bind(loader, first)
        assertThat(seenA.await(3, TimeUnit.SECONDS)).isTrue()
        bind(loader, second)
        assertThat(seenB.await(3, TimeUnit.SECONDS)).isTrue()
        firstHandle.close(); firstHandle.close()
        releaseA.countDown(); releaseB.countDown()
        await("other page still receives its own bitmap") { second.lastBitmap != null && cache.length() > 0L }
        await("owned first request canceled") { canceledPaths.contains("/image-a") }
        assertThat(canceledPaths).doesNotContain("/image-b")
        assertThat(first.bitmapAssignments).isEqualTo(0)
        assertThat(first.drawable).isNull()
        awaitBackground("completed second cache can be removed") { cache.delete() }

        // Do not idle the UI yet: real HTTP writes cache after queuing the UI
        // Runnable. Closing now must remove/refuse that queued source renderer.
        val queued = view()
        val queuedHandle = bind(loader, queued)
        awaitBackground("third real image callback began its cache write after posting") { cache.isFile && cache.length() > 0L }
        assertThat(queued.bitmapAssignments).isEqualTo(0)
        queuedHandle.close()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(queued.bitmapAssignments).isEqualTo(0)
        assertThat(queued.drawable).isNull()
        assertThat(second.lastBitmap).isNotNull()
        noToast()
    }

    private fun loader(
        http: OkHttpClient = client,
        clock: () -> Long = { now },
        timeout: Long = 12_000L,
    ) = AuthPromoImageLoader(application, http, server.url("/auth/promo-image").toString(),
        now = clock, timezone = { zone }, cacheDirectory = directory, timeoutMillis = timeout)

    private fun bind(loader: AuthPromoImageLoader, view: ImageView): AutoCloseable =
        loader.bind(view).also { handles += it }

    private fun view(): RecordingImageView = RecordingImageView(activity.get()).also {
        container.addView(it, FrameLayout.LayoutParams(160, 120))
        it.layout(0, 0, 160, 120)
    }

    private fun respondImage(imageStatus: Int = 200) {
        server.enqueue(MockResponse.Builder().body(metadata(server.url("/image.png").toString())).build())
        server.enqueue(imageResponse(imageStatus))
    }

    private fun metadata(url: String): String = JSONObject().put("success", true)
        .put("data", JSONObject().put("url", url)).toString()

    private fun imageResponse(status: Int = 200): MockResponse = MockResponse.Builder().code(status)
        .body(Buffer().write(png())).build()

    private fun png(): ByteArray = ByteArrayOutputStream().also { output ->
        val bitmap = fixtureBitmap()
        try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        finally { bitmap.recycle() }
    }.toByteArray()

    private fun fixtureBitmap(): Bitmap = Bitmap.createBitmap(12, 8, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.RED)
        for (y in 0 until height) for (x in 4 until width) setPixel(x, y, if (x < 8) Color.GREEN else Color.BLUE)
    }

    private fun writeBitmap(file: File, format: Bitmap.CompressFormat, quality: Int) {
        val bitmap = fixtureBitmap()
        try { file.outputStream().use { check(bitmap.compress(format, quality, it)) } }
        finally { bitmap.recycle() }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    private fun awaitBackground(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError(message)
    }

    private fun noToast() { assertThat(ShadowToast.getTextOfLatestToast()).isNull() }

    private class RecordingImageView(activity: Activity) : ImageView(activity) {
        var bitmapAssignments = 0
        var lastBitmap: Bitmap? = null
        override fun setImageBitmap(bitmap: Bitmap?) {
            super.setImageBitmap(bitmap)
            bitmapAssignments++
            lastBitmap = bitmap
        }
    }

    private companion object {
        fun epoch(text: String): Long = ZonedDateTime.parse(text).toInstant().toEpochMilli()
    }
}
