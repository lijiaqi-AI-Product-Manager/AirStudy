package com.projectfocus.app.auth

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.view.ViewGroup
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import android.os.LocaleList
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.testing.shutdownRealApplicationAfterTest
import com.projectfocus.app.home.HomeMapStyleSelection
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Actual four Activities, Home store, framework locales and Insets; no original/device/network service. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class SourceAuthWindowPolicyTest {
    private lateinit var app: ProjectFocusApp
    private lateinit var client: OkHttpClient
    private lateinit var server: MockWebServer
    private lateinit var directory: File
    private lateinit var previousPromo: AuthPromoImageLoader
    private lateinit var previousLocales: LocaleList
    private var previousNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
    private var previousMap: Int? = null
    private val screens = listOf(AuthLoginActivity::class.java, AuthEmailLoginActivity::class.java,
        AuthRegisterActivity::class.java, AuthForgotPasswordActivity::class.java)
    private val controllers = mutableListOf<ActivityController<out AppCompatActivity>>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        previousNight = AppCompatDelegate.getDefaultNightMode()
        val prefs = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
        previousMap = if (prefs.contains(HomeMapStyleSelection.KEY)) prefs.getInt(HomeMapStyleSelection.KEY, 6) else null
        previousLocales = checkNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales
        checkNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales = LocaleList.getEmptyLocaleList()
        previousPromo = app.authPromoImages
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "auth-source-window-").toFile()
        client = OkHttpClient()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) =
                    MockResponse.Builder().code(503).body("owned unavailable image").build()
            }
            start()
        }
        app.authPromoImages = AuthPromoImageLoader(app, client, server.url("/auth/promo-image").toString(),
            cacheDirectory = directory)
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @After fun cleanup() {
        controllers.asReversed().forEach { it.pause().stop().destroy() }
        controllers.clear()
        if (::previousPromo.isInitialized) app.authPromoImages = previousPromo
        if (::server.isInitialized) server.close()
        if (::client.isInitialized) {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        if (::directory.isInitialized) {
            check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
            directory.walkBottomUp().forEach { it.delete() }
        }
        if (::app.isInitialized) {
            app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE).edit().apply {
                previousMap?.let { putInt(HomeMapStyleSelection.KEY, it) } ?: remove(HomeMapStyleSelection.KEY)
            }.commit()
            if (::previousLocales.isInitialized) checkNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales = previousLocales
        }
        restoreAuthTestNightMode(previousNight)
        if (::app.isInitialized) shutdownRealApplicationAfterTest(app)
    }

    @Test fun allSourceStylesOverrideOppositeGlobalThemeInEveryAuthActivity() {
        // Independent source enum/constructor oracle, not the production mapping array.
        val nightIndices = setOf(0, 1, 2, 3, 5, 7)
        for (index in 0..7) {
            writeMap(index)
            val night = index in nightIndices
            AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
            for (screen in screens) {
                val controller = launch(screen)
                val activity = controller.get()
                assertThat(activity.delegate.localNightMode).isEqualTo(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                assertThat(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    .isEqualTo(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                assertThat(AppCompatDelegate.getDefaultNightMode())
                    .isEqualTo(if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                close(controller)
            }
        }
    }

    @Test fun freshAndUnknownStoredSelectionUseTheRecoveredDayDefault() {
        val prefs = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(HomeMapStyleSelection.KEY).commit()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        val fresh = launch(AuthForgotPasswordActivity::class.java)
        assertThat(fresh.get().delegate.localNightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
        assertThat(HomeMapStyleSelection.read(app)).isEqualTo(6)
        close(fresh)
        for (invalid in listOf(-1, 99)) {
            writeMap(invalid)
            val controller = launch(AuthForgotPasswordActivity::class.java)
            assertThat(controller.get().delegate.localNightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
            // Reading an unknown value does not silently overwrite stored user data.
            assertThat(prefs.getInt(HomeMapStyleSelection.KEY, -2)).isEqualTo(invalid)
            close(controller)
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun allFourWindowsUseSourceBackgroundAndAbsoluteSystemBarInsetsWithoutIme() {
        assertThat(Build.VERSION.SDK_INT).isEqualTo(35)
        assertSourceBackgroundAndInsets(enforced = true)
    }

    @Test
    @Config(sdk = [34])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun sdk34RetainsTheSourceRequestedBarColorsAndBackground() {
        assertThat(Build.VERSION.SDK_INT).isEqualTo(34)
        assertSourceBackgroundAndInsets(enforced = false)
    }

    @Suppress("DEPRECATION")
    private fun assertSourceBackgroundAndInsets(enforced: Boolean) {
        val records = JSONArray()
        val gateRecords = JSONArray()
        val output = File(System.getProperty("pf.auth.systemBars.previewDir",
            "build/reports/r168-auth-system-bars")).apply { check(isDirectory || mkdirs()) }
        for (night in listOf(false, true)) {
            writeMap(if (night) 2 else 4)
            for (screen in screens) {
                val controller = launch(screen)
                val activity = controller.get()
                val content = activity.findViewById<View>(android.R.id.content)
                val color = activity.getColor(R.color.pf_auth_background)
                assertThat(activity.applicationInfo.targetSdkVersion).isEqualTo(37)
                if (enforced) {
                    // Actual SDK35 PhoneWindow.generateLayout sets this flag and status color0.
                    // setStatusBarColor returns immediately; getNavigationBarColor returns0.
                    val attributes = activity.obtainStyledAttributes(intArrayOf(android.R.attr.windowOptOutEdgeToEdgeEnforcement))
                    val optOut = try { attributes.getBoolean(0, false) } finally { attributes.recycle() }
                    val windowType = activity.window.javaClass
                    val threshold = ReflectionHelpers.getStaticField<Int>(windowType, "ENFORCE_EDGE_TO_EDGE_SDK_VERSION")
                    // The actual SDK35 initializer defaults this debug property to MAX, not35.
                    val propertyValue = ReflectionHelpers.callStaticMethod<Int>(Class.forName("android.os.SystemProperties"),
                        "getInt", ReflectionHelpers.ClassParameter.from(String::class.java, "persist.wm.debug.default_e2e_since_sdk"),
                        ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, Int.MAX_VALUE))
                    val flagsType = Class.forName("com.android.window.flags.Flags")
                    val enabledFlag = ReflectionHelpers.callStaticMethod<Boolean>(flagsType, "enforceEdgeToEdge")
                    val featureFlagsType = ReflectionHelpers.getStaticField<Any>(flagsType, "FEATURE_FLAGS").javaClass.name
                    // Actual Robolectric4.14.1 ShadowCompatibility maps this change to target35.
                    val enabledChange = ReflectionHelpers.callStaticMethod<Boolean>(Class.forName("android.app.compat.CompatChanges"),
                        "isChangeEnabled", ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, 309578419L))
                    val actualEnforced = ReflectionHelpers.getField<Boolean>(activity.window, "mEdgeToEdgeEnforced")
                    val target = activity.applicationInfo.targetSdkVersion
                    val expectedEnforced = !optOut && (target >= threshold || enabledFlag && enabledChange)
                    gateRecords.put(JSONObject().put("activity", screen.name).put("night", night)
                        .put("sdk", Build.VERSION.SDK_INT).put("targetSdk", target).put("optOut", optOut)
                        .put("memoryThreshold", threshold).put("initializerPropertyValue", propertyValue)
                        .put("initializerDefault", Int.MAX_VALUE).put("featureFlagsType", featureFlagsType)
                        .put("enforceEdgeToEdgeFlag", enabledFlag).put("compatChange", 309578419L)
                        .put("compatEnabled", enabledChange).put("computedEnforced", expectedEnforced)
                        .put("actualEnforced", actualEnforced).put("statusBarColor", activity.window.statusBarColor)
                        .put("navigationBarColor", activity.window.navigationBarColor).put("sourceBackground", color)
                        .put("thresholdStaticFieldWritten", false).put("enforcementFieldWritten", false))
                    // Emit observed state before assertions, preserving diagnosis on an unexpected gate.
                    File(output, "gates-sdk${Build.VERSION.SDK_INT}.json").writeText(JSONObject()
                        .put("kind", "actual_framework_and_robolectric_gate_observation_not_original_device_acceptance")
                        .put("records", gateRecords).toString(2))
                    assertThat(threshold).isEqualTo(propertyValue)
                    assertThat(optOut).isFalse()
                    assertThat(enabledFlag).isTrue()
                    assertThat(enabledChange).isEqualTo(target >= 35)
                    assertThat(actualEnforced).isEqualTo(expectedEnforced)
                    assertThat(expectedEnforced).isTrue()
                    assertThat(activity.window.statusBarColor).isEqualTo(Color.TRANSPARENT)
                    assertThat(activity.window.navigationBarColor).isEqualTo(Color.TRANSPARENT)
                } else {
                    // Actual SDK34 PhoneWindow setters store the original requested values.
                    // Preserve both original color assertions on the platform that honors them.
                    assertThat(activity.window.statusBarColor).isEqualTo(color)
                    assertThat(activity.window.navigationBarColor).isEqualTo(color)
                }
                assertThat((content.background as ColorDrawable).color).isEqualTo(color)
                content.setPadding(100, 100, 100, 100)
                val input = WindowInsetsCompat.Builder()
                    .setInsets(7, Insets.of(11, 23, 17, 31))
                    .setVisible(7, true)
                    // Independent mask oracle: source7 must ignore the newer systemOverlays512.
                    .setInsets(512, Insets.of(83, 97, 89, 101))
                    .setVisible(512, true)
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 247))
                    .setVisible(WindowInsetsCompat.Type.ime(), true).build()
                assertThat(input.getInsets(7)).isEqualTo(Insets.of(11, 23, 17, 31))
                assertThat(input.getInsets(512)).isEqualTo(Insets.of(83, 97, 89, 101))
                val outgoing = ViewCompat.dispatchApplyWindowInsets(content, input)
                assertThat(listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
                    .containsExactly(11, 23, 17, 31).inOrder()
                assertThat(outgoing.isConsumed).isFalse()
                assertThat(outgoing.getInsets(WindowInsetsCompat.Type.ime()).bottom).isEqualTo(247)
                assertThat(outgoing.getInsets(512)).isEqualTo(Insets.of(83, 97, 89, 101))
                shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
                val decor = activity.window.decorView as ViewGroup
                decor.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, 390, 844)
                assertThat(decor.isAttachedToWindow).isTrue()
                val contentBounds = Rect(0, 0, content.width, content.height)
                decor.offsetDescendantRectToMyCoords(content, contentBounds)
                assertThat(contentBounds).isEqualTo(Rect(0, 0, 390, 844))
                assertThat(listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
                    .containsExactly(11, 23, 17, 31).inOrder()
                val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                try {
                    // Draw the actual attached production Window. No expected-color canvas fill.
                    decor.draw(Canvas(image))
                    for (x in 0 until image.width) {
                        assertThat(image.getPixel(x, 0)).isEqualTo(color)
                        assertThat(image.getPixel(x, 22)).isEqualTo(color)
                        assertThat(image.getPixel(x, image.height - 1)).isEqualTo(color)
                        assertThat(image.getPixel(x, image.height - 31)).isEqualTo(color)
                    }
                    assertThat(Color.alpha(image.getPixel(0, 0))).isEqualTo(255)
                    val name = "sdk${Build.VERSION.SDK_INT}-${screen.simpleName}-${if (night) "night" else "day"}.png"
                    val file = File(output, name)
                    file.outputStream().use { assertThat(image.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue() }
                    records.put(JSONObject().put("file", name).put("sdk", Build.VERSION.SDK_INT)
                        .put("targetSdk", activity.applicationInfo.targetSdkVersion).put("night", night)
                        .put("actualStatusBarColor", activity.window.statusBarColor)
                        .put("actualNavigationBarColor", activity.window.navigationBarColor)
                        .put("sourceBackgroundColor", color).put("barStripPixel", image.getPixel(0, 0))
                        .put("contentBounds", contentBounds.toShortString()).put("windowAttached", decor.isAttachedToWindow)
                        .put("pngSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                            .joinToString("") { "%02x".format(it.toInt() and 255) }))
                } finally { image.recycle() }
                val replacement = WindowInsetsCompat.Builder()
                    .setInsets(7, Insets.of(7, 13, 5, 19))
                    .setVisible(7, true)
                    .setInsets(512, Insets.of(71, 73, 79, 83)).setVisible(512, true).build()
                ViewCompat.dispatchApplyWindowInsets(content, replacement)
                assertThat(listOf(content.paddingLeft, content.paddingTop, content.paddingRight, content.paddingBottom))
                    .containsExactly(7, 13, 5, 19).inOrder()
                close(controller)
            }
        }
        File(output, "sdk${Build.VERSION.SDK_INT}.json").writeText(JSONObject()
            .put("kind", "actual_own_window_native_background_and_insets_not_original_or_systemUI_hardware_acceptance")
            .put("enforcedEdgeToEdge", enforced).put("records", records).toString(2))
    }

    @Test fun mapAndGlobalThemeChangesDoNotPretendToBeLanguageChangesOnResume() {
        writeMap(4)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        val controller = launch(AuthForgotPasswordActivity::class.java)
        val original = controller.get()
        controller.pause().stop()
        writeMap(2)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        controller.restart().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(controller.get()).isSameInstanceAs(original)
        assertThat(original.isDestroyed).isFalse()
        assertThat(original.delegate.localNightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_NO)
        close(controller)
        val next = launch(AuthForgotPasswordActivity::class.java)
        assertThat(next.get().delegate.localNightMode).isEqualTo(AppCompatDelegate.MODE_NIGHT_YES)
    }

    @Test fun actualFrameworkApplicationLanguageChangeRecreatesEachRealAuthActivity() {
        writeMap(4)
        for (screen in screens) {
            checkNotNull(app.getSystemService(LocaleManager::class.java)).applicationLocales = LocaleList.getEmptyLocaleList()
            val controller = launch(screen)
            val original = controller.get()
            controller.pause().stop()
            checkNotNull(original.getSystemService(LocaleManager::class.java)).applicationLocales = LocaleList.forLanguageTags("ja")
            assertThat(AppCompatDelegate.getApplicationLocales().toLanguageTags()).isEqualTo("ja")
            controller.restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(controller.get()).isNotSameInstanceAs(original)
            assertThat(original.isDestroyed).isTrue()
            val current = controller.get()
            controller.pause().stop().restart().start().resume()
            shadowOf(Looper.getMainLooper()).idle()
            assertThat(controller.get()).isSameInstanceAs(current)
            close(controller)
        }
    }

    @Test fun sourceDaySelectionContainsTheActualOriginalRawStyleRatherThanOldSimple() {
        val raw = app.resources.openRawResource(com.projectfocus.feature.home.R.raw.pf_mapstyle_simple).use { it.readBytes() }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it.toInt() and 255) }
        assertThat(raw.size).isEqualTo(986)
        assertThat(digest).isEqualTo("beded3051730768c076d6c22e62688eb271b897d8e7d72fa45ee2a8efe3278f4")
        assertThat(app.getString(R.string.pf_source_map_style_day)).isNotEmpty()
    }

    private fun writeMap(index: Int) {
        check(app.getSharedPreferences("projectfocus_home", Context.MODE_PRIVATE).edit().putInt("map_style", index).commit())
    }

    private fun <T : AppCompatActivity> launch(type: Class<T>): ActivityController<T> =
        Robolectric.buildActivity(type).setup().visible().also { controllers += it }

    private fun close(controller: ActivityController<out AppCompatActivity>) {
        controller.pause().stop().destroy()
        controllers.remove(controller)
    }
}
