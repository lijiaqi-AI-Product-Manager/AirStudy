package com.projectfocus.app.auth

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.home.HomeMapStyleSelection
import com.projectfocus.core.network.di.TokenStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

/** Real Activity, gateway, local HTTP, platform countdown and custom Toast; no original service/mail. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi",
    shadows = [RealHandlerCountDownTimerShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthRegisterActivityTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var app: ProjectFocusApp
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var directory: File
    private lateinit var previousPromo: AuthPromoImageLoader
    private lateinit var previousGateway: RegistrationGateway
    private val activities = mutableListOf<ActivityController<AuthRegisterActivity>>()
    private val releases = mutableListOf<CountDownLatch>()
    private val codes = ConcurrentLinkedQueue<RecordedRequest>()
    private val registrations = ConcurrentLinkedQueue<RecordedRequest>()
    private val canceled = ConcurrentLinkedQueue<String>()
    private var oldNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
    private var previousMapStyle: Int? = null
    @Volatile private var codeReply: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(503).body("relay unavailable").build() }
    @Volatile private var registerReply: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(409).body("ignored").build() }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        val mapPrefs = app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Activity.MODE_PRIVATE)
        previousMapStyle = if (mapPrefs.contains(HomeMapStyleSelection.KEY)) mapPrefs.getInt(HomeMapStyleSelection.KEY, 6) else null
        TokenStore.clear(); ShadowToast.reset()
        oldNight = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        previousPromo = app.authPromoImages
        previousGateway = app.registrationGateway
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "registration-ui-").toFile()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: IOException) {
                if (call.isCanceled()) canceled += call.request().url.encodedPath
            }
        }).build()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/auth/promo-image" -> MockResponse.Builder().code(503).body("unavailable").build()
                    "/auth/register/code" -> { codes += request; codeReply(request) }
                    "/auth/register" -> { registrations += request; registerReply(request) }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        app.authPromoImages = AuthPromoImageLoader(app, client, server.url("/auth/promo-image").toString(), cacheDirectory = directory)
        app.registrationGateway = OwnRegistrationGateway(client, server.url("/").toString())
    }

    @After fun tearDown() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        releases.forEach(CountDownLatch::countDown)
        app.authPromoImages = previousPromo
        app.registrationGateway = previousGateway
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        directory.walkBottomUp().forEach { it.delete() }
        app.getSharedPreferences(HomeMapStyleSelection.FILE_NAME, Activity.MODE_PRIVATE).edit().apply {
            previousMapStyle?.let { putInt(HomeMapStyleSelection.KEY, it) } ?: remove(HomeMapStyleSelection.KEY)
        }.commit()
        restoreAuthTestNightMode(oldNight)
        TokenStore.clear(); ShadowToast.reset()
    }

    @Test fun `real controls enforce source field validity and checkbox toggles without gating submit`() {
        val activity = screen().get()
        val button = register(activity)
        val send = send(activity)
        assertThat(button.isEnabled).isFalse()
        assertThat(button.alpha).isEqualTo(0.6f)
        assertThat(button.text.toString()).isEqualTo(activity.getString(R.string.pf_auth_fill_form_hint))
        assertThat(send.isEnabled).isFalse()
        assertThat(send.alpha).isEqualTo(0.5f)
        fill(activity, "\tuser@example.test \n", " 001234 \t", "        ")
        assertThat(button.isEnabled).isTrue() // Eight raw spaces pass the source client length predicate.
        assertThat(button.alpha).isEqualTo(1f)
        val checkbox = activity.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)
        assertThat(checkbox.isChecked()).isFalse()
        checkbox.performClick(); assertThat(checkbox.isChecked()).isTrue()
        checkbox.performClick(); assertThat(checkbox.isChecked()).isFalse()
        assertThat(button.isEnabled).isTrue()
        // A Kotlin default trim would wrongly turn this into a valid source email.
        fill(activity, "\u00a0user@example.test\u00a0", "001234", "12345678")
        assertThat(button.isEnabled).isFalse()
        assertThat(RegistrationInputs.read("\u00a0user@example.test\u00a0", " \t123\n", " raw ").email)
            .isEqualTo("\u00a0user@example.test\u00a0")
        // Exercise the actual click guard without faking an enabled invalid button.
        button.callOnClick()
        assertThat(toastTitle()).isEqualTo(activity.getString(R.string.pf_auth_form_incomplete_title))
        assertThat(toastMessage()).isEqualTo(activity.getString(R.string.pf_auth_form_incomplete_msg))
        assertThat(registrations).isEmpty()
        assertThat(ShadowDialog.getLatestDialog()?.isShowing ?: false).isFalse()
        assertNoAccountWrite(activity)
    }

    @Test fun `send cooldown begins before HTTP and keeps running through failure then rereads current email`() {
        val seen = CountDownLatch(1)
        val gate = CountDownLatch(1).also { releases += it }
        codeReply = {
            seen.countDown(); check(gate.await(5, TimeUnit.SECONDS))
            MockResponse.Builder().code(503).body("relay deliberately unavailable").build()
        }
        val activity = screen().get()
        fill(activity, " user@example.test\t", "", "")
        send(activity).performClick()
        assertThat(send(activity).isEnabled).isFalse()
        assertThat(send(activity).alpha).isEqualTo(0.6f)
        assertThat(send(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_send_code_countdown_fmt, 30))
        assertThat(seen.await(3, TimeUnit.SECONDS)).isTrue()
        assertThat(ShadowToast.getLatestToast()).isNull()
        shadowOf(Looper.getMainLooper()).idleFor(1_500, TimeUnit.MILLISECONDS)
        assertThat(send(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_send_code_countdown_fmt, 29L))
        fill(activity, "bad-email", "", "")
        gate.countDown()
        await("HTTP503 source failure notice") { toastTitle() == activity.getString(R.string.pf_auth_send_code_failed_title) }
        assertThat(toastMessage()).isEqualTo(activity.getString(R.string.pf_auth_common_service_return_code_fmt, 503))
        assertThat(send(activity).isEnabled).isFalse()
        assertThat(send(activity).text.toString()).contains("29")
        shadowOf(Looper.getMainLooper()).idleFor(30, TimeUnit.SECONDS)
        assertThat(send(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_send_code))
        assertThat(send(activity).isEnabled).isFalse() // Finish re-read the now-invalid email.
        assertThat(send(activity).alpha).isEqualTo(0.5f)
        fill(activity, "new@example.test", "", "")
        assertThat(send(activity).isEnabled).isTrue()
        val request = codes.single()
        assertThat(JSONObject(checkNotNull(request.body).utf8()).getString("email")).isEqualTo("user@example.test")
        assertNoAccountWrite(activity)
    }

    @Test fun `valid unchecked registration sends trimmed email and code raw password and only201 finishes without auth`() {
        registerReply = { MockResponse.Builder().code(201).body("""{"token":"must-not-be-stored","id":"ignored"}""").build() }
        val activity = screen().get()
        val identity = TokenStore.snapshot()
        val preferences = accountPreferences(activity).all.toMap()
        fill(activity, " \tuser@example.test \n", " 001234\t", "  raw-password  ")
        assertThat(activity.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement).isChecked()).isFalse()
        register(activity).performClick()
        await("201 closes original registration flow") { activity.isFinishing }
        val body = JSONObject(checkNotNull(registrations.single().body).utf8())
        assertThat(body.getString("email")).isEqualTo("user@example.test")
        assertThat(body.getString("code")).isEqualTo("001234")
        assertThat(body.getString("password")).isEqualTo("  raw-password  ")
        assertThat(body.keys().asSequence().toSet()).containsExactly("email", "code", "password")
        assertThat(toastTitle()).isEqualTo(activity.getString(R.string.pf_auth_register_create_success_title))
        assertThat(toastMessage()).isEqualTo(activity.getString(R.string.pf_auth_register_create_success_msg))
        assertThat(ShadowDialog.getLatestDialog()?.isShowing ?: false).isFalse()
        assertThat(shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
        assertThat(shadowOf(activity).resultIntent).isNull()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(accountPreferences(activity).all).isEqualTo(preferences)
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }

    @Test fun `register remains clickable during requests and each real click rereads its fields`() {
        val gate = CountDownLatch(1).also { releases += it }
        registerReply = { check(gate.await(5, TimeUnit.SECONDS)); MockResponse.Builder().code(409).body("ignored").build() }
        val activity = screen().get()
        fill(activity, "user@example.test", "111111", "12345678")
        register(activity).performClick()
        await("first source request queued") { registrations.size == 1 }
        assertThat(register(activity).isEnabled).isTrue()
        assertThat(register(activity).alpha).isEqualTo(1f)
        fill(activity, "new@example.test", "222222", "new-password")
        register(activity).performClick()
        await("second source request queued") { registrations.size == 2 }
        val bodies = registrations.map { JSONObject(checkNotNull(it.body).utf8()) }
        assertThat(bodies.map { it.getString("code") }).containsExactly("111111", "222222").inOrder()
        assertThat(bodies.map { it.getString("email") }).containsExactly("user@example.test", "new@example.test").inOrder()
        gate.countDown()
        await("source409 response rendered") { toastTitle() == activity.getString(R.string.pf_auth_register_email_exists_title) }
        assertThat(activity.isFinishing).isFalse()
        assertNoAccountWrite(activity)
    }

    @Test fun `real HTTP200400403409 map to source failure notices without closing or saving an account`() {
        val activity = screen().get()
        fill(activity, "user@example.test", "123456", "12345678")
        val cases = listOf(
            Triple(200, R.string.pf_auth_register_create_failed_title, R.string.pf_auth_common_service_return_code_fmt),
            Triple(400, R.string.pf_auth_invalid_params_or_code_title, R.string.pf_auth_invalid_params_or_code_msg),
            Triple(403, R.string.pf_auth_common_permission_denied_title, R.string.pf_auth_common_permission_denied_msg),
            Triple(409, R.string.pf_auth_register_email_exists_title, R.string.pf_auth_register_email_exists_msg),
        )
        for ((status, title, message) in cases) {
            ShadowToast.reset()
            registerReply = { MockResponse.Builder().code(status).body("no JSON parsing").build() }
            register(activity).performClick()
            await("status$status source notice") { toastTitle() == activity.getString(title) }
            val text = if (status == 200) activity.getString(message, status) else activity.getString(message)
            assertThat(toastMessage()).isEqualTo(text)
            assertThat(activity.isFinishing).isFalse()
            assertThat(register(activity).isEnabled).isTrue()
        }
        assertNoAccountWrite(activity)
    }

    @Test fun `destroy cancels only lifecycle request and rejects a late201 without auth or toast`() {
        val gate = CountDownLatch(1).also { releases += it }
        registerReply = { check(gate.await(5, TimeUnit.SECONDS)); MockResponse.Builder().code(201).body("ignored").build() }
        val controller = screen()
        val activity = controller.get()
        fill(activity, "user@example.test", "123456", "12345678")
        register(activity).performClick()
        await("real register request reached server") { registrations.size == 1 }
        controller.pause().stop().destroy(); activities.remove(controller)
        gate.countDown()
        await("real canceled request reported") { canceled.contains("/auth/register") }
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowToast.getLatestToast()).isNull()
        assertNoAccountWrite(activity)
    }

    @Test fun `native actual Activity frames use source map day night and retain global Toast rules`() {
        val output = File(System.getProperty("pf.auth.register.previewDir", "build/reports/r167-auth-register-runtime")).apply {
            check(isDirectory || mkdirs())
        }
        for (night in listOf(false, true)) {
            HomeMapStyleSelection.select(app, if (night) 2 else 4)
            AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            val controller = screen()
            val activity = controller.get()
            val root = activity.window.decorView
            shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 390, 844)
            // Window/decor/background are actual framework surfaces. No fragment
            // transparency fill, reference screenshot substitution or fake image.
            val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            Canvas(image).also { canvas ->
                root.background?.let { background ->
                    val old = Rect(background.bounds)
                    try { background.setBounds(0, 0, root.width, root.height); background.draw(canvas) }
                    finally { background.bounds = old }
                }
                root.draw(canvas)
            }
            val png = File(output, "register-${if (night) "night" else "day"}-390.png")
            png.outputStream().use { assertThat(image.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue() }
            assertThat(image.getPixel(0, 0) ushr 24).isEqualTo(255)
            assertThat(nodes(root).filterIsInstance<TextView>().any { it.text.toString() == activity.getString(R.string.pf_auth_register_screen_title) }).isTrue()
            val button = register(activity)
            val bounds = Rect(0, 0, button.width, button.height)
            (root as ViewGroup).offsetDescendantRectToMyCoords(button, bounds)
            assertThat(bounds.top).isAtLeast(0)
            assertThat(bounds.bottom).isAtMost(image.height)
            val background = image.getPixel(bounds.left + 32, bounds.centerY())
            var visibleGlyphPixels = 0
            for (y in bounds.centerY() - 10 until bounds.centerY() + 10) {
                for (x in bounds.centerX() - 80 until bounds.centerX() + 80) {
                    val pixel = image.getPixel(x, y)
                    if (kotlin.math.abs(Color.red(pixel) - Color.red(background)) > 24 ||
                        kotlin.math.abs(Color.green(pixel) - Color.green(background)) > 24 ||
                        kotlin.math.abs(Color.blue(pixel) - Color.blue(background)) > 24) visibleGlyphPixels++
                }
            }
            assertThat(visibleGlyphPixels).isGreaterThan(8) // Logical text alone cannot pass a blank native frame.
            register(activity).callOnClick()
            val toast = checkNotNull(ShadowToast.getLatestToast())
            @Suppress("DEPRECATION") val toastRoot = checkNotNull(toast.view)
            val title = toastRoot.findViewById<TextView>(R.id.pf_auth_toast_title)
            val message = toastRoot.findViewById<TextView>(R.id.pf_auth_toast_message)
            assertThat(title.currentTextColor).isEqualTo(if (night) Color.WHITE else Color.rgb(17, 24, 39))
            assertThat(Color.alpha(message.currentTextColor)).isEqualTo(if (night) 210 else 185)
            assertThat((toastRoot.background as GradientDrawable).cornerRadius).isEqualTo(22f)
            assertThat(SourceRegistrationFeedback.sourceNight(activity)).isEqualTo(night)
            assertNoAccountWrite(activity)
            image.recycle()
            controller.pause().stop().destroy(); activities.remove(controller)
            ShadowToast.reset()
        }
        File(output, "README.txt").writeText("Two actual AuthRegisterActivity NativeGraphics SDK35 window draws; metadata is own loopback503, not source image/server execution.\n" +
            "No source screenshot, SSIM, IME, system Toast overlay or original-device timing acceptance.\n")
    }

    private fun screen(): ActivityController<AuthRegisterActivity> =
        Robolectric.buildActivity(AuthRegisterActivity::class.java).setup().visible().also { activities += it }

    private fun register(activity: AuthRegisterActivity): MaterialButton = activity.findViewById(R.id.pf_auth_btn_register)
    private fun send(activity: AuthRegisterActivity): TextView = activity.findViewById(R.id.pf_auth_tv_send_code)
    private fun fill(activity: AuthRegisterActivity, email: String, code: String, password: String) {
        activity.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_email).getText().let { it.replace(0, it.length, email) }
        activity.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_password).getText().let { it.replace(0, it.length, password) }
        activity.findViewById<EditText>(R.id.pf_auth_et_code).setText(code)
    }
    @Suppress("DEPRECATION") private fun toastTitle(): String? = ShadowToast.getLatestToast()?.view
        ?.findViewById<TextView>(R.id.pf_auth_toast_title)?.text?.toString()
    @Suppress("DEPRECATION") private fun toastMessage(): String? = ShadowToast.getLatestToast()?.view
        ?.findViewById<TextView>(R.id.pf_auth_toast_message)?.text?.toString()
    private fun accountPreferences(activity: AuthRegisterActivity) = activity.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Activity.MODE_PRIVATE)
    private fun assertNoAccountWrite(activity: AuthRegisterActivity) {
        assertThat(TokenStore.peek()).isNull()
        assertThat(accountPreferences(activity).all).isEmpty()
        assertThat(shadowOf(app).nextStartedService).isNull()
    }
    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
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
}
