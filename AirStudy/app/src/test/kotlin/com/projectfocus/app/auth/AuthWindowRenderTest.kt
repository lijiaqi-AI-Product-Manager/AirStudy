package com.projectfocus.app.auth

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.core.network.di.TokenStore
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.xmlpull.v1.XmlPullParser

/**
 * Own real Activities/Application/Hilt/AppCompat/window, not a substitute host.
 * Real image binding uses a synthetic daily cache or an actual local HTTP503
 * peer. The synthetic cache is not original remote-image content or a golden.
 * No credential submit, original service, device or membership action occurs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthWindowRenderTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val activities = mutableListOf<ActivityController<out SourceAuthActivity>>()
    private lateinit var app: ProjectFocusApp
    private var previousNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
    private var previousMapStyle: Int? = null
    private val drawClockSamples = JSONArray()
    private var lastDrawFrameMillis = -1L
    private lateinit var promoDirectory: File
    private lateinit var promoClient: OkHttpClient
    private lateinit var promoServer: MockWebServer
    private lateinit var previousPromoImages: AuthPromoImageLoader
    private val metadataFinished = CountDownLatch(1)
    private val fixtureNow = System.currentTimeMillis()

    @Before fun prepare() {
        previousNight = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        TokenStore.clear()
        app = ApplicationProvider.getApplicationContext()
        val mapPrefs = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
        previousMapStyle = if (mapPrefs.contains(HomeMapStyleSelection.KEY)) mapPrefs.getInt(HomeMapStyleSelection.KEY, 6) else null
        assertNotNull(app.database) // Real Hilt Application injection, never assigned by this fixture.
        app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        // Drain the one actual startup task. Never create a second airport import
        // and never fake its bootstrap/Hilt result to obtain a screenshot.
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        shadowOf(Looper.getMainLooper()).idle()
        promoDirectory = Files.createTempDirectory(app.cacheDir.toPath(), "auth-window-promo-").toFile()
        promoClient = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { metadataFinished.countDown() }
        }).build()
        promoServer = MockWebServer().apply {
            start()
            enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        }
        previousPromoImages = app.authPromoImages
        app.authPromoImages = AuthPromoImageLoader(app, promoClient,
            promoServer.url("/auth/promo-image").toString(), now = { fixtureNow }, cacheDirectory = promoDirectory)
    }

    @After fun cleanup() {
        activities.reversed().forEach { it.pause().stop().destroy() }
        activities.clear()
        if (::previousPromoImages.isInitialized) app.authPromoImages = previousPromoImages
        if (::promoServer.isInitialized) promoServer.close()
        if (::promoClient.isInitialized) {
            promoClient.connectionPool.evictAll()
            promoClient.dispatcher.executorService.shutdownNow()
        }
        if (::promoDirectory.isInitialized) {
            check(promoDirectory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            promoDirectory.walkBottomUp().forEach { it.delete() }
        }
        if (::app.isInitialized) app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        TokenStore.clear()
        if (::app.isInitialized) app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE).edit().apply {
            previousMapStyle?.let { putInt(HomeMapStyleSelection.KEY, it) } ?: remove(HomeMapStyleSelection.KEY)
        }.commit()
        restoreAuthTestNightMode(previousNight)
    }

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    fun hubDay() = capture(AuthLoginActivity::class.java, "hub-day", false)

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    fun hubNight() = capture(AuthLoginActivity::class.java, "hub-night", true)

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    fun emailDay() = capture(AuthEmailLoginActivity::class.java, "email-day", false)

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    fun emailNight() = capture(AuthEmailLoginActivity::class.java, "email-night", true)

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
    fun hubUnavailableDay() = capture(AuthLoginActivity::class.java, "hub-unavailable-day", false, cached = false)

    @Test @Config(qualifiers = "zh-rCN-w390dp-h844dp-night-mdpi")
    fun emailUnavailableNight() = capture(AuthEmailLoginActivity::class.java, "email-unavailable-night", true, cached = false)

    private fun <T : SourceAuthActivity> capture(type: Class<T>, name: String, night: Boolean, cached: Boolean = true) {
        val cache = File(promoDirectory, AuthPromoImageLoader.CACHE_FILE_NAME)
        if (cached) {
            val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
            try {
                // Four visibly artificial color fields, never a guessed source photo.
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    bitmap.setPixel(x, y, if (x < 60) (if (y < 40) 0xff506b73.toInt() else 0xffb3b5a9.toInt())
                        else (if (y < 40) 0xffa8bec2.toInt() else 0xff54615f.toInt()))
                }
                cache.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                assertTrue(cache.setLastModified(fixtureNow))
            } finally { bitmap.recycle() }
        }
        HomeMapStyleSelection.select(app, if (night) 2 else 4)
        val controller = Robolectric.buildActivity(type).setup()
        activities += controller
        val activity = controller.get()
        assertSame(app, activity.application)
        assertFalse(activity.isFinishing)
        assertEquals(1f, activity.resources.displayMetrics.density, 0.001f)
        assertEquals(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertNotNull(activity.layoutInflater.factory2)
        val decor = activity.window.decorView as ViewGroup
        assertTrue("setup must create a real attached Window", decor.isAttachedToWindow)
        if (!cached) {
            assertTrue("The actual local metadata request must finish before its failure frame", metadataFinished.await(5, TimeUnit.SECONDS))
            assertEquals(1, promoServer.requestCount)
            val request = checkNotNull(promoServer.takeRequest(1, TimeUnit.SECONDS))
            assertEquals("/auth/promo-image", request.url.encodedPath)
            assertNull(request.headers["Authorization"])
        }
        settleWindow(decor)
        val image = drawDecor(decor)
        val output = File(System.getProperty("pf.auth.windowPreviewDir", "build/reports/r166-auth-window")).apply {
            check(isDirectory || mkdirs())
        }
        try {
            val png = File(output, "$name-390.png")
            png.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            assertEquals("androidx.coordinatorlayout.widget.CoordinatorLayout", content.getChildAt(0).javaClass.name)
            val back = activity.findViewById<View>(R.id.pf_auth_btn_back)
            val backImages = nodes(back).filterIsInstance<ImageView>()
            assertEquals(1, backImages.size)

            assertTrue("AppCompat must inflate the source ImageView tag", backImages.single() is AppCompatImageView)
            val titleId = if (activity is AuthLoginActivity) R.string.pf_auth_welcome_back_title else R.string.pf_auth_login_email_button
            val titles = nodes(content).filterIsInstance<TextView>().filter { it.text.toString() == activity.getString(titleId) }
            assertEquals(1, titles.size)
            val title = titles.single()
            val promo = activity.findViewById<ImageView>(R.id.pf_auth_promo_image)
            if (cached) {
                assertTrue(promo.drawable is BitmapDrawable)
                assertEquals(120, (promo.drawable as BitmapDrawable).bitmap.width)
                assertEquals(80, (promo.drawable as BitmapDrawable).bitmap.height)
                assertEquals(1f, promo.alpha, 0.001f)
                assertEquals(0, promoServer.requestCount)
            } else {
                assertNull("Unconfigured metadata must not leave the XML map or invent a photo", promo.drawable)
                assertEquals(0f, promo.alpha, 0.001f)
                assertFalse(cache.exists())
            }
            val inputs = nodes(content).filterIsInstance<SourceAuthInputView>()
            inputs.forEach { assertTrue("No credential input is entered by this fixture", it.getText().isEmpty()) }
            assertTrue("Window diagnostics must remain anonymous", TokenStore.peek() == null)
            val model = if (activity is AuthEmailLoginActivity) ViewModelProvider(activity)[EmailLoginViewModel::class.java] else null
            model?.state?.value?.let { assertFalse(it.busy); assertFalse(it.loggedIn); assertNull(it.error) }
            val agreement = activity.findViewById<TextView>(R.id.pf_auth_tv_agreement)
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())
            val wechat = activity.findViewById<MaterialButton>(R.id.pf_auth_btn_wechat_login)
            val login = activity.findViewById<MaterialButton>(R.id.pf_auth_btn_login)
            val info = JSONObject()
                .put("kind", "real_own_activity_window_native_diagnostic_not_original_runtime")
                .put("activity", activity.javaClass.name).put("application", app.javaClass.name)
                .put("databaseInjectedClass", app.database.javaClass.name)
                .put("bootstrapResultClass", value(app.databaseBootstrapResult?.javaClass?.name))
                .put("factory2Class", value(activity.layoutInflater.factory2?.javaClass?.name))
                .put("factoryClass", value(activity.layoutInflater.factory?.javaClass?.name))
                .put("appCompatDelegateClass", activity.delegate.javaClass.name)
                .put("themeFromManifest", "pf_auth_source_theme")
                .put("night", night).put("uiMode", activity.resources.configuration.uiMode)
                .put("locales", activity.resources.configuration.locales.toLanguageTags())
                .put("width", image.width).put("height", image.height).put("pngSha256", sha(png.readBytes()))
                .put("decor", viewInfo(decor, decor))
                .put("windowFlags", activity.window.attributes.flags)
                .put("softInputMode", activity.window.attributes.softInputMode)
                .put("systemUiVisibility", decor.systemUiVisibility)
                .put("systemBarInsets", if (insets == null) JSONObject.NULL else JSONObject()
                    .put("left", insets.left).put("top", insets.top).put("right", insets.right).put("bottom", insets.bottom))
                .put("focus", value(activity.currentFocus?.javaClass?.name))
                .put("header", textInfo(title, decor, image))
                .put("back", viewInfo(back, decor).put("image", imageInfo(backImages.single(), decor)))
                .put("loginButton", buttonInfo(login, decor, image))
                .put("wechatButton", if (wechat == null) JSONObject.NULL else buttonInfo(wechat, decor, image))
                .put("agreement", textInfo(agreement, decor, image).put("clickableSpanCount",
                    (agreement.text as? Spanned)?.getSpans(0, agreement.length(), ClickableSpan::class.java)?.size ?: 0))
                .put("promo", imageInfo(promo, decor)
                    .put("state", if (cached) "OWN_SYNTHETIC_DAILY_CACHE_RENDERED" else "OWN_METADATA_503_IMAGE_CLEARED")
                    .put("ownDynamicChainConnected", true)
                    .put("originalDynamicRuntimeVerified", false)
                    .put("originalRemoteImageContentKnown", false)
                    .put("fixtureSyntheticDailyCache", cached)
                    .put("fixtureCacheSha256", if (cached) sha(cache.readBytes()) else JSONObject.NULL)
                    .put("bitmapDimensions", (promo.drawable as? BitmapDrawable)?.bitmap?.let { JSONArray(listOf(it.width, it.height)) } ?: JSONObject.NULL))
                .put("whiteVectorPathCountValidated", assertWhiteVector(activity))
                .put("fixtureSubmitCalls", 0).put("fixturePictureRequests", promoServer.requestCount)
                .put("trafficObservationScope", "Actual local image-peer count only; synthetic daily cache hit0 or metadata503 request1. Not a total-app network counter or original image content.")
                .put("loginOrMembershipSuccessClaim", false).put("originalPixelAcceptance", false)
                .put("animationSettle", "actual Choreographer frame callback + test-only ViewRoot drawing-clock bridge; original1000+250ms idle retained")
                .put("drawingClockReflection", "View.mAttachInfo -> AttachInfo.mDrawingTime only, copied from actual frameTimeNanos/1e6; no animation/alpha/transform changes")
                .put("drawClockSamples", drawClockSamples)
                .put("activeLegacyAnimationsAtCapture", activeLegacyAnimations(decor))
            info.put("renderedControls", renderedControls(activity, decor, image, info))
            File(output, "$name-390.json").writeText(info.toString(2) + "\n")
            println("R166_AUTH_WINDOW " + info.toString())
            assertEquals("Legacy child animations must actually finish at capture", 0, activeLegacyAnimations(decor))
            assertRenderedControls(info.getJSONArray("renderedControls"))
        } finally { image.recycle() }
    }

    private fun settleWindow(decor: ViewGroup) {
        repeat(2) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            decor.layout(0, 0, 390, 844)
            shadowOf(Looper.getMainLooper()).idle()
        }
        drawDecor(decor).recycle() // Start actual layout animations/pre-draw state.
        //220ms per child + the source15% stagger (up to418ms on Email).
        shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.SECONDS)
        decor.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, 390, 844)
        drawDecor(decor).recycle()
        shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun drawDecor(decor: View): Bitmap {
        val choreographer = Choreographer.getInstance()
        var result: Bitmap? = null
        var failure: Throwable? = null
        val callback = Choreographer.FrameCallback { frameNanos ->
            try {
                val frameMillis = TimeUnit.NANOSECONDS.toMillis(frameNanos)
                val sample = JSONObject().put("frameNanos", frameNanos)
                    .put("uptimeBefore", SystemClock.uptimeMillis())
                    .put("animationClockBefore", AnimationUtils.currentAnimationTimeMillis())
                    .put("drawingClockBefore", decor.drawingTime)
                    .put("activeLegacyBefore", activeLegacyAnimations(decor))
                assertTrue("Actual frame time must advance", frameMillis > lastDrawFrameMillis)
                assertTrue("Frame time must come from the current monotonic clock", frameMillis <= SystemClock.uptimeMillis())
                // Android15 ViewRootImpl.draw writes this same shared AttachInfo
                // value from Choreographer before dispatchDraw. Manual Bitmap
                // draw bypasses that Surface path in Robolectric. Bridge TIME
                // only; leave every original Animation/start/end/transform intact.
                val attachInfo = ReflectionHelpers.getField<Any>(decor, "mAttachInfo")
                ReflectionHelpers.setField(attachInfo, "mDrawingTime", frameMillis)
                assertEquals(frameMillis, decor.drawingTime)
                lastDrawFrameMillis = frameMillis
                val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    decor.background?.let { background ->
                        val old = Rect(background.bounds)
                        try { background.setBounds(0, 0, 390, 844); background.draw(canvas) }
                        finally { background.bounds = old }
                    }
                    decor.draw(canvas)
                    result = bitmap
                } catch (error: Throwable) { bitmap.recycle(); throw error }
                drawClockSamples.put(sample.put("uptimeAfter", SystemClock.uptimeMillis())
                    .put("animationClockAfter", AnimationUtils.currentAnimationTimeMillis())
                    .put("drawingClockAfter", decor.drawingTime)
                    .put("activeLegacyAfter", activeLegacyAnimations(decor)))
            } catch (error: Throwable) { failure = error }
        }
        choreographer.postFrameCallback(callback)
        try { shadowOf(Looper.getMainLooper()).idleFor(16, TimeUnit.MILLISECONDS) }
        finally { choreographer.removeFrameCallback(callback) }
        failure?.let { throw it }
        return checkNotNull(result) { "No real Choreographer frame delivered within16ms; clock bridge did not fabricate a frame" }
    }

    private fun activeLegacyAnimations(root: View): Int = nodes(root).count {
        it.animation?.let { animation -> animation.hasStarted() && !animation.hasEnded() } == true
    }

    private fun renderedControls(activity: SourceAuthActivity, decor: ViewGroup, image: Bitmap, info: JSONObject): JSONArray {
        val rows = JSONArray()
        fun collect(button: MaterialButton, role: String, backgroundId: Int, foregroundId: Int, needsIcon: Boolean) {
            val frame = bounds(button, decor)
            val expectedBackground = activity.getColor(backgroundId)
            val expectedForeground = activity.getColor(foregroundId)
            val resolved = checkNotNull(button.backgroundTintList).getColorForState(button.drawableState, expectedBackground)
            val sampleX = (frame.right - 24).coerceIn(0, image.width - 1)
            val sampleY = frame.centerY().coerceIn(0, image.height - 1)
            val layout = checkNotNull(button.layout)
            val lineTop = frame.top + button.baseline - layout.getLineBaseline(0)
            val label = Rect(frame.left + button.compoundPaddingLeft + layout.getLineLeft(0).toInt(), lineTop,
                frame.left + button.compoundPaddingLeft + kotlin.math.ceil(layout.getLineRight(0).toDouble()).toInt(),
                lineTop + layout.getLineBottom(0) - layout.getLineTop(0))
            val clipped = Rect(label); var labelInk = 0
            if (clipped.intersect(frame) && clipped.intersect(0, 0, image.width, image.height)) {
                for (y in clipped.top until clipped.bottom) for (x in clipped.left until clipped.right) {
                    val pixel = image.getPixel(x, y)
                    if (difference(pixel, expectedForeground) < 45 && difference(pixel, expectedBackground) > 45) labelInk++
                }
            }
            var iconInk = 0; var iconNonBackground = 0
            if (needsIcon) {
                val physical = info.getJSONObject("wechatButton").getJSONArray("compoundPhysical")
                for (i in 0 until physical.length()) {
                    val slot = physical.getJSONObject(i)
                    if (slot.optBoolean("sameInstanceAsIcon")) {
                        iconInk += slot.getInt("whitePixels")
                        iconNonBackground += slot.getInt("nonBackgroundPixels")
                    }
                }
            }
            rows.put(JSONObject().put("role", role).put("expectedSourceBackground", color(expectedBackground))
                .put("resolvedBackground", color(resolved)).put("sampledBackground", color(image.getPixel(sampleX, sampleY)))
                .put("backgroundDelta", difference(image.getPixel(sampleX, sampleY), expectedBackground))
                .put("foregroundMatchesSource", button.currentTextColor == expectedForeground)
                .put("labelRegion", rect(label)).put("labelSourceInkPixels", labelInk)
                .put("needsWhiteIcon", needsIcon).put("iconWhitePixels", iconInk).put("iconNonBackgroundPixels", iconNonBackground))
        }
        collect(activity.findViewById(R.id.pf_auth_btn_login), "email-action",
            if (activity is AuthLoginActivity) R.color.pf_auth_btn_entry_secondary_bg else R.color.pf_auth_btn_solid_bg,
            if (activity is AuthLoginActivity) R.color.pf_auth_btn_entry_secondary_text else R.color.pf_auth_btn_solid_text, false)
        activity.findViewById<MaterialButton>(R.id.pf_auth_btn_wechat_login)?.let {
            collect(it, "wechat-action", R.color.pf_auth_btn_wechat_bg, R.color.pf_auth_btn_wechat_text, true)
        }
        return rows
    }

    private fun assertRenderedControls(rows: JSONArray) {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i); val details = row.toString()
            assertEquals(details, row.getString("expectedSourceBackground"), row.getString("resolvedBackground"))
            assertTrue("Resolved source background must really paint: $details", row.getInt("backgroundDelta") <= 12)
            assertTrue(details, row.getBoolean("foregroundMatchesSource"))
            assertTrue("Source label ink must paint inside its actual line region: $details", row.getInt("labelSourceInkPixels") > 8)
            if (row.getBoolean("needsWhiteIcon")) {
                assertEquals(details, "#FF07C160", row.getString("resolvedBackground"))
                assertTrue("White path pixels must be on rendered green, not a blank white frame: $details", row.getInt("iconWhitePixels") > 8)
                assertTrue("Actual icon region needs non-background pixels: $details", row.getInt("iconNonBackgroundPixels") > 8)
            }
        }
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }

    private fun bounds(view: View, decor: ViewGroup): Rect = Rect(0, 0, view.width, view.height).apply {
        if (view !== decor) decor.offsetDescendantRectToMyCoords(view, this)
    }
    private fun rect(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    private fun value(value: Any?): Any = value ?: JSONObject.NULL
    private fun color(color: Int) = "#%08X".format(color)
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun viewInfo(view: View, decor: ViewGroup): JSONObject {
        val visible = Rect(); val globalVisible = view.getGlobalVisibleRect(visible)
        val ancestorAnimations = JSONArray()
        var effectiveAlpha = view.alpha; var parent = view.parent
        while (parent is View) {
            val ancestor = parent
            effectiveAlpha *= ancestor.alpha
            if (ancestor is ViewGroup) ancestor.layoutAnimation?.let { animation ->
                ancestorAnimations.put(JSONObject().put("class", ancestor.javaClass.name).put("layoutAnimationDone", animation.isDone()))
            }
            parent = ancestor.parent
        }
        return JSONObject().put("class", view.javaClass.name).put("visibility", view.visibility)
            .put("shown", view.isShown).put("attached", view.isAttachedToWindow).put("windowVisibility", view.windowVisibility)
            .put("alpha", view.alpha).put("ancestorAlphaProduct", effectiveAlpha)
            .put("width", view.width).put("height", view.height).put("decorBounds", rect(bounds(view, decor)))
            .put("globalVisible", globalVisible).put("globalVisibleRect", rect(visible))
            .put("enabled", view.isEnabled).put("focused", view.isFocused).put("pressed", view.isPressed)
            .put("animation", view.animation?.let { JSONObject().put("class", it.javaClass.name).put("started", it.hasStarted())
                .put("ended", it.hasEnded()).put("startTime", it.startTime).put("duration", it.duration).put("startOffset", it.startOffset) } ?: JSONObject.NULL)
            .put("ancestorLayoutAnimations", ancestorAnimations)
    }

    private fun drawable(drawable: Drawable?): Any = if (drawable == null) JSONObject.NULL else JSONObject()
        .put("class", drawable.javaClass.name).put("intrinsicWidth", drawable.intrinsicWidth).put("intrinsicHeight", drawable.intrinsicHeight)
        .put("bounds", rect(Rect(drawable.bounds))).put("alpha", drawable.alpha).put("visible", drawable.isVisible)
        .put("state", JSONArray(drawable.state.toList())).put("level", drawable.level).put("colorFilterClass", value(drawable.colorFilter?.javaClass?.name))

    private fun tint(list: ColorStateList?, view: View): Any = if (list == null) JSONObject.NULL else JSONObject()
        .put("default", color(list.defaultColor)).put("forState", color(list.getColorForState(view.drawableState, list.defaultColor))).put("stateful", list.isStateful)

    private fun imageInfo(view: ImageView, decor: ViewGroup): JSONObject = viewInfo(view, decor)
        .put("drawable", drawable(view.drawable)).put("scaleType", view.scaleType.name)
        .put("platformImageTint", tint(view.imageTintList, view))
        .put("supportImageTint", if (view is AppCompatImageView) tint(view.supportImageTintList, view) else JSONObject.NULL)

    private fun textInfo(view: TextView, decor: ViewGroup, image: Bitmap): JSONObject {
        val area = bounds(view, decor); val clipped = Rect(area)
        var matching = 0
        if (clipped.intersect(0, 0, image.width, image.height)) for (y in clipped.top until clipped.bottom) for (x in clipped.left until clipped.right) {
            if (difference(image.getPixel(x, y), view.currentTextColor) < 36) matching++
        }
        return viewInfo(view, decor).put("text", view.text.toString()).put("textColor", color(view.currentTextColor))
            .put("textSizePx", view.textSize).put("baseline", view.baseline).put("paintAlpha", view.paint.alpha)
            .put("typefaceStyle", view.typeface?.style ?: -1).put("typefaceWeight", view.typeface?.weight ?: -1)
            .put("lineCount", view.layout?.lineCount ?: -1).put("matchedTextColorPixels", matching)
    }

    private fun buttonInfo(button: MaterialButton, decor: ViewGroup, image: Bitmap): JSONObject {
        val physicalNames = listOf("left", "top", "right", "bottom")
        val relativeNames = listOf("start", "top", "end", "bottom")
        val slots = JSONArray(); val relative = JSONArray()
        button.compoundDrawablesRelative.forEachIndexed { index, item -> relative.put(JSONObject().put("slot", relativeNames[index]).put("drawable", drawable(item))) }
        val buttonBounds = bounds(button, decor)
        val sampleX = (buttonBounds.right - 24).coerceIn(0, image.width - 1)
        val sampleY = buttonBounds.centerY().coerceIn(0, image.height - 1)
        val sampledBackground = image.getPixel(sampleX, sampleY)
        button.compoundDrawables.forEachIndexed { index, item ->
            val region = compoundRegion(button, index, item, buttonBounds)
            var differing = 0; var white = 0
            if (region != null) {
                val clipped = Rect(region)
                if (clipped.intersect(buttonBounds) && clipped.intersect(0, 0, image.width, image.height)) {
                    for (y in clipped.top until clipped.bottom) for (x in clipped.left until clipped.right) {
                        val pixel = image.getPixel(x, y)
                        if (difference(pixel, sampledBackground) > 24) differing++
                        if (Color.red(pixel) > 230 && Color.green(pixel) > 230 && Color.blue(pixel) > 230) white++
                    }
                }
            }
            slots.put(JSONObject().put("slot", physicalNames[index]).put("drawable", drawable(item))
                .put("sameInstanceAsIcon", item != null && item === button.icon)
                .put("decorRegionFromPublicGeometry", if (region == null) JSONObject.NULL else rect(region))
                .put("nonBackgroundPixels", differing).put("whitePixels", white))
        }
        return textInfo(button, decor, image).put("icon", drawable(button.icon)).put("iconSize", button.iconSize)
            .put("iconPadding", button.iconPadding).put("iconGravity", button.iconGravity).put("iconTint", tint(button.iconTint, button))
            .put("iconTintMode", value(button.iconTintMode?.name)).put("compoundPhysical", slots).put("compoundRelative", relative)
            .put("compoundPadding", JSONArray(listOf(button.compoundPaddingLeft, button.compoundPaddingTop, button.compoundPaddingRight, button.compoundPaddingBottom)))
            .put("padding", JSONArray(listOf(button.paddingLeft, button.paddingTop, button.paddingRight, button.paddingBottom)))
            .put("compoundDrawablePadding", button.compoundDrawablePadding).put("layoutDirection", button.layoutDirection)
            .put("sampledBackground", color(sampledBackground)).put("backgroundTint", tint(button.backgroundTintList, button))
            .put("regionScope", "TextView public slot/bounds/padding estimate, diagnostic only; not a private Material-field or source-pixel acceptance")
    }

    private fun compoundRegion(button: TextView, slot: Int, item: Drawable?, frame: Rect): Rect? {
        if (item == null || item.bounds.isEmpty) return null
        val b = item.bounds; val width = b.width(); val height = b.height()
        val horizontal = button.width - button.compoundPaddingLeft - button.compoundPaddingRight
        val vertical = button.height - button.compoundPaddingTop - button.compoundPaddingBottom
        val x = when (slot) { 0 -> button.paddingLeft; 2 -> button.width - button.paddingRight - width
            else -> button.compoundPaddingLeft + (horizontal - width) / 2 }
        val y = when (slot) { 1 -> button.paddingTop; 3 -> button.height - button.paddingBottom - height
            else -> button.compoundPaddingTop + (vertical - height) / 2 }
        return Rect(frame.left + x + b.left, frame.top + y + b.top,
            frame.left + x + b.left + width, frame.top + y + b.top + height)
    }

    private fun difference(a: Int, b: Int): Int = abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

    private fun assertWhiteVector(context: Context): Int {
        val parser = context.resources.getXml(R.drawable.pf_auth_ic_wechat_login_white)
        var count = 0
        try {
            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType == XmlPullParser.START_TAG && parser.name == "path") {
                    val values = context.resources.obtainAttributes(parser, intArrayOf(android.R.attr.fillColor))
                    try { assertEquals(Color.WHITE, values.getColor(0, Color.TRANSPARENT)) }
                    finally { values.recycle() }
                    assertFalse(parser.getAttributeValue("http://schemas.android.com/apk/res/android", "pathData").isNullOrEmpty())
                    count++
                }
                parser.next()
            }
        } finally { parser.close() }
        assertTrue("source white vector path must exist", count > 0)
        return count
    }
}
