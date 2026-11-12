package com.projectfocus.app.auth

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
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
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
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
import org.robolectric.util.ReflectionHelpers

/** Real Hilt Application, Activity, native controls, Handler timer and local HTTP. No fake Gateway. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi",
    shadows = [RealHandlerCountDownTimerShadow::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthForgotPasswordTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var app: ProjectFocusApp
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var directory: File
    private lateinit var previousGateway: PasswordResetGateway
    private lateinit var previousPromo: AuthPromoImageLoader
    private var previousNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
    private val activities = mutableListOf<ActivityController<out AppCompatActivity>>()
    private val releases = mutableListOf<CountDownLatch>()
    private val codes = ConcurrentLinkedQueue<RecordedRequest>()
    private val resets = ConcurrentLinkedQueue<RecordedRequest>()
    private val canceled = ConcurrentLinkedQueue<String>()
    private val headers = ConcurrentLinkedQueue<Triple<String, Int, String?>>()
    @Volatile private var codeReply: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(503).body("ignored").build() }
    @Volatile private var resetReply: (RecordedRequest) -> MockResponse = { MockResponse.Builder().code(400).body("ignored").build() }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TokenStore.clear(); ShadowToast.reset()
        app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Activity.MODE_PRIVATE).edit().clear().commit()
        previousNight = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        previousGateway = app.passwordResetGateway
        previousPromo = app.authPromoImages
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "reset-").toFile()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                headers += Triple(call.request().url.encodedPath, response.code, response.header("Content-Length"))
            }
            override fun callFailed(call: Call, ioe: IOException) { if (call.isCanceled()) canceled += call.request().url.encodedPath }
        }).build()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/auth/password-reset/code" -> { codes += request; codeReply(request) }
                    "/auth/password-reset" -> { resets += request; resetReply(request) }
                    "/auth/promo-image" -> MockResponse.Builder().code(503).body("unavailable").build()
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        app.passwordResetGateway = OwnPasswordResetGateway(client, server.url("/").toString())
        // The actual EmailLogin navigation test also uses a real, owned local promo loader.
        app.authPromoImages = AuthPromoImageLoader(app, client, server.url("/auth/promo-image").toString(), cacheDirectory = directory)
    }

    @After fun tearDown() {
        activities.asReversed().forEach { it.pause().stop().destroy() }
        releases.forEach(CountDownLatch::countDown)
        app.passwordResetGateway = previousGateway
        app.authPromoImages = previousPromo
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        directory.walkBottomUp().forEach { it.delete() }
        restoreAuthTestNightMode(previousNight)
        TokenStore.clear(); ShadowToast.reset()
    }

    @Test fun fieldsAndHeaderPriority() {
        val activity = screen().get()
        assertThat(activity.javaClass.superclass).isEqualTo(AppCompatActivity::class.java)
        assertThat(title(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_prompt_enter_email_title))
        assertThat(title(activity).textSize).isEqualTo(25f)
        assertThat(title(activity).animate().duration).isEqualTo(180L)
        assertThat(subtitle(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_prompt_enter_email_subtitle))
        assertThat(reset(activity).isEnabled).isFalse()
        assertThat(send(activity).alpha).isEqualTo(0.5f)
        val code = input(activity, R.id.pf_auth_et_code)
        assertThat(code.inputType and InputType.TYPE_MASK_CLASS).isEqualTo(InputType.TYPE_CLASS_TEXT)
        assertThat(code.inputType and InputType.TYPE_MASK_VARIATION).isEqualTo(InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertThat(nodes(activity.window.decorView).filterIsInstance<TextInputLayout>().all { it.endIconMode == TextInputLayout.END_ICON_NONE }).isTrue()
        assertThat(activity.findViewById<View>(R.id.pf_auth_promo_image)).isNull()
        assertThat(activity.findViewById<View>(R.id.pf_auth_cb_agreement)).isNull()
        fill(activity, "bad-email", "", "")
        assertHeader(activity, R.string.pf_auth_forgot_password_prompt_email_invalid_title)
        fill(activity, "user@example.test", "", "")
        assertHeader(activity, R.string.pf_auth_forgot_password_prompt_enter_new_password_title)
        assertThat(send(activity).isEnabled).isTrue()
        fill(activity, "user@example.test", "", "1234567")
        assertHeader(activity, R.string.pf_auth_forgot_password_prompt_password_too_short_title)
        fill(activity, "user@example.test", "", "12345678")
        assertHeader(activity, R.string.pf_auth_forgot_password_prompt_enter_code_title)
        fill(activity, "user@example.test", "alpha-code-not-digits", "        ")
        assertHeader(activity, R.string.pf_auth_forgot_password_prompt_ready_title)
        assertThat(reset(activity).isEnabled).isTrue() // Source checks raw nonempty/UTF-16 length, not isBlank.
        assertThat(reset(activity).alpha).isEqualTo(1f)
        assertThat(reset(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_action_update))
        fill(activity, "\u00a0user@example.test\u00a0", "abc", "12345678")
        assertThat(reset(activity).isEnabled).isFalse() // Java trim does not remove NBSP.
        assertThat(codes).isEmpty(); assertThat(resets).isEmpty()
    }

    @Test fun raw200FinishNoLogin() {
        resetReply = { MockResponse.Builder().code(200).body("""{"token":"not-an-auth-result"}""").build() }
        val activity = screen().get()
        val identity = TokenStore.snapshot()
        val preferences = accountPreferences().all.toMap()
        fill(activity, " \tuser@example.test ", " \talpha-code ", "  raw-password  ")
        reset(activity).performClick()
        await("source200 success and finish") { activity.isFinishing }
        val body = JSONObject(checkNotNull(resets.single().body).utf8())
        assertThat(body.keys().asSequence().toSet()).containsExactly("email", "code", "new_password")
        assertThat(body.getString("email")).isEqualTo("user@example.test")
        assertThat(body.getString("code")).isEqualTo("alpha-code")
        assertThat(body.getString("new_password")).isEqualTo("  raw-password  ")
        assertThat(toastTitle()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_update_success_title))
        assertThat(toastMessage()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_update_success_msg))
        assertThat(shadowOf(activity).resultCode).isEqualTo(Activity.RESULT_CANCELED)
        assertThat(shadowOf(activity).resultIntent).isNull()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(accountPreferences().all).isEqualTo(preferences)
    }

    @Test fun countdownBeforeHttp() {
        val gate = CountDownLatch(1).also { releases += it }
        codeReply = { check(gate.await(5, TimeUnit.SECONDS)); MockResponse.Builder().code(503).body("ignored").build() }
        val activity = screen().get()
        input(activity, R.id.pf_auth_et_email).setText(" user@example.test ")
        timerTrace("before-click", activity)
        val clickedAt = SystemClock.elapsedRealtime()
        send(activity).performClick()
        timerTrace("after-click", activity)
        val timer = checkNotNull(ReflectionHelpers.getField<android.os.CountDownTimer?>(activity, "countdown"))
        assertThat(ReflectionHelpers.getField<Long>(timer, "mStopTimeInFuture") - clickedAt).isEqualTo(30_000L)
        assertThat(send(activity).isEnabled).isFalse()
        assertThat(send(activity).alpha).isEqualTo(0.6f)
        assertThat(send(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_send_code_countdown_fmt, 30))
        await("request only after actual click cooldown") { codes.size == 1 }
        timerTrace("after-request-seen", activity)
        val body = JSONObject(checkNotNull(codes.single().body).utf8())
        assertThat(body.getString("email")).isEqualTo("user@example.test")
        gate.countDown()
        await("503 actual custom Toast") { toastTitle() == activity.getString(R.string.pf_auth_send_code_failed_title) }
        timerTrace("after-http-notice", activity)
        assertThat(send(activity).isEnabled).isFalse() // Failure cannot unlock early.
        // Native frame/request pumping already advanced the real framework
        // clock in the observed failed run (605->1006ms). Measure cooldown
        // from the actual click, not from the later HTTP response.
        val until29 = 29_000L - (SystemClock.elapsedRealtime() - clickedAt)
        assertThat(until29).isAtLeast(0L)
        shadowOf(Looper.getMainLooper()).idleFor(until29, TimeUnit.MILLISECONDS)
        timerTrace("at-click-plus-29000", activity)
        assertThat(SystemClock.elapsedRealtime() - clickedAt).isEqualTo(29_000L)
        assertThat(send(activity).isEnabled).isFalse()
        assertThat(send(activity).text.toString()).isNotEqualTo(activity.getString(R.string.pf_auth_send_code))
        input(activity, R.id.pf_auth_et_email).setText("invalid")
        val until30 = 30_000L - (SystemClock.elapsedRealtime() - clickedAt)
        assertThat(until30).isAtLeast(0L)
        shadowOf(Looper.getMainLooper()).idleFor(until30, TimeUnit.MILLISECONDS)
        timerTrace("at-click-plus-30000", activity)
        // Dispatching onFinish itself advanced SDK35's native test clock by1ms
        // in the retained run. The actual timer deadline above stays exactly
        //30s; completion must appear within the next source one-second interval.
        assertThat(SystemClock.elapsedRealtime() - clickedAt).isAtLeast(30_000L)
        assertThat(SystemClock.elapsedRealtime() - clickedAt).isAtMost(31_000L)
        assertThat(send(activity).text.toString()).isEqualTo(activity.getString(R.string.pf_auth_send_code))
        assertThat(send(activity).isEnabled).isFalse() // Finish re-reads current email.
        assertThat(send(activity).alpha).isEqualTo(0.5f)
        input(activity, R.id.pf_auth_et_email).setText("new@example.test")
        assertThat(send(activity).isEnabled).isTrue()
        assertNoAccountWrite()
    }

    @Test fun actualStatusNotices() {
        val activity = screen().get()
        fill(activity, "user@example.test", "abc", "12345678")
        for ((status, expectedTitle, expectedMessage) in listOf(
            Triple(400, R.string.pf_auth_invalid_params_or_code_title, R.string.pf_auth_invalid_params_or_code_msg),
            Triple(404, R.string.pf_auth_forgot_password_user_not_found_title, R.string.pf_auth_forgot_password_user_not_found_msg),
            Triple(201, R.string.pf_auth_forgot_password_update_failed_title, R.string.pf_auth_common_service_return_code_fmt),
            Triple(429, R.string.pf_auth_forgot_password_update_failed_title, R.string.pf_auth_common_service_return_code_fmt),
        )) {
            ShadowToast.reset()
            resetReply = { MockResponse.Builder().code(status).body("no JSON parser").build() }
            reset(activity).performClick()
            await("reset$status source notice") { toastTitle() == activity.getString(expectedTitle) }
            val message = if (status == 201 || status == 429) activity.getString(expectedMessage, status) else activity.getString(expectedMessage)
            assertThat(toastMessage()).isEqualTo(message)
            assertThat(activity.isFinishing).isFalse()
            assertThat(reset(activity).isEnabled).isTrue()
        }
        for ((status, expectedTitle) in listOf(200 to R.string.pf_auth_code_sent_title, 429 to R.string.pf_auth_common_request_too_frequent_title)) {
            ShadowToast.reset()
            codeReply = { MockResponse.Builder().code(status).body("ignored").build() }
            val controller = screen()
            val current = controller.get()
            input(current, R.id.pf_auth_et_email).setText("user@example.test")
            send(current).performClick()
            await("send$status source notice") { toastTitle() == current.getString(expectedTitle) }
            assertThat(send(current).isEnabled).isFalse()
            assertThat(current.isFinishing).isFalse()
            controller.pause().stop().destroy(); activities.remove(controller)
        }
        assertNoAccountWrite()
    }

    @Test fun eachValidClickRereads() {
        val gate = CountDownLatch(1).also { releases += it }
        resetReply = { check(gate.await(5, TimeUnit.SECONDS)); MockResponse.Builder().code(400).body("ignored").build() }
        val activity = screen().get()
        fill(activity, "first@example.test", "first", "12345678")
        reset(activity).performClick()
        await("first real request") { resets.size == 1 }
        assertThat(reset(activity).isEnabled).isTrue()
        fill(activity, "second@example.test", "second", "new-password")
        reset(activity).performClick()
        await("second real request while first pending") { resets.size == 2 }
        val bodies = resets.map { JSONObject(checkNotNull(it.body).utf8()) }
        assertThat(bodies.map { it.getString("email") }).containsExactly("first@example.test", "second@example.test").inOrder()
        assertThat(bodies.map { it.getString("code") }).containsExactly("first", "second").inOrder()
        assertThat(bodies.map { it.getString("new_password") }).containsExactly("12345678", "new-password").inOrder()
        gate.countDown()
        await("400 callback") { toastTitle() == activity.getString(R.string.pf_auth_invalid_params_or_code_title) }
        assertNoAccountWrite()
    }

    @Test fun truncated200ShowsSuccess() {
        fun truncated() = MockResponse.Builder().code(200).body("short").setHeader("Content-Length", "4096")
            .setHeader("Connection", "close").onResponseEnd(SocketEffect.CloseSocket()).build()
        codeReply = { truncated() }; resetReply = { truncated() }
        val activity = screen().get()
        val identity = TokenStore.snapshot()
        fill(activity, "user@example.test", "abc", "12345678")
        send(activity).performClick()
        await("truncated send200 must post success") { toastTitle() == activity.getString(R.string.pf_auth_code_sent_title) }
        assertThat(headers).contains(Triple("/auth/password-reset/code", 200, "4096"))
        assertThat(send(activity).isEnabled).isFalse()
        ShadowToast.reset()
        reset(activity).performClick()
        await("truncated reset200 must finish") { activity.isFinishing }
        assertThat(headers).contains(Triple("/auth/password-reset", 200, "4096"))
        assertThat(toastTitle()).isEqualTo(activity.getString(R.string.pf_auth_forgot_password_update_success_title))
        assertThat(shadowOf(activity).resultIntent).isNull()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(accountPreferences().all).isEmpty()
    }

    @Test fun destroyOwnCancellation() {
        val gate = CountDownLatch(1).also { releases += it }
        codeReply = { check(gate.await(5, TimeUnit.SECONDS)); MockResponse.Builder().code(200).body("ignored").build() }
        val controller = screen()
        val activity = controller.get()
        input(activity, R.id.pf_auth_et_email).setText("user@example.test")
        send(activity).performClick()
        await("real send reached server") { codes.size == 1 }
        val label = send(activity).text.toString()
        controller.pause().stop().destroy(); activities.remove(controller)
        gate.countDown()
        await("Own lifecycle canceled HTTP") { canceled.contains("/auth/password-reset/code") }
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertThat(ShadowToast.getLatestToast()).isNull()
        assertThat(send(activity).text.toString()).isEqualTo(label)
        assertNoAccountWrite()
    }

    @Test fun actualEmailXmlEntry() {
        val emailController = Robolectric.buildActivity(AuthEmailLoginActivity::class.java).setup().visible()
        activities += emailController
        val email = emailController.get()
        val link = nodes(email.window.decorView).filterIsInstance<TextView>().single {
            it.text.toString() == email.getString(R.string.pf_auth_forgot_password)
        }
        link.performClick() // Actual android:onClick, not a direct helper invocation.
        val intent = checkNotNull(shadowOf(email).nextStartedActivity)
        assertThat(intent.component?.className).isEqualTo(AuthForgotPasswordActivity::class.java.name)
        assertThat(intent.extras?.keySet().orEmpty()).isEmpty()
        val controller = Robolectric.buildActivity(AuthForgotPasswordActivity::class.java, intent).setup().visible()
        activities += controller
        val reset = controller.get()
        assertThat(reset.findViewById<TextInputEditText>(R.id.pf_auth_et_email).text?.toString()).isEmpty()
        reset.findViewById<View>(R.id.pf_auth_btn_back).performClick()
        assertThat(reset.isFinishing).isTrue()
        assertThat(shadowOf(reset).resultCode).isEqualTo(Activity.RESULT_CANCELED)
        assertThat(shadowOf(reset).resultIntent).isNull()
        assertThat(shadowOf(reset).nextStartedActivity).isNull()
        assertThat(ShadowDialog.getLatestDialog()?.isShowing ?: false).isFalse()
        assertThat(codes).isEmpty(); assertThat(resets).isEmpty()
        assertNoAccountWrite()
    }

    private fun screen(): ActivityController<AuthForgotPasswordActivity> =
        Robolectric.buildActivity(AuthForgotPasswordActivity::class.java).setup().visible().also { activities += it }
    private fun input(activity: AuthForgotPasswordActivity, id: Int): TextInputEditText = activity.findViewById(id)
    private fun reset(activity: AuthForgotPasswordActivity): MaterialButton = activity.findViewById(R.id.pf_auth_btn_register)
    private fun send(activity: AuthForgotPasswordActivity): TextView = activity.findViewById(R.id.pf_auth_tv_send_code)
    private fun title(activity: AuthForgotPasswordActivity): TextView = activity.findViewById(R.id.pf_auth_tv_title)
    private fun subtitle(activity: AuthForgotPasswordActivity): TextView = activity.findViewById(R.id.pf_auth_tv_subtitle)
    private fun fill(activity: AuthForgotPasswordActivity, email: String, code: String, password: String) {
        input(activity, R.id.pf_auth_et_email).setText(email)
        input(activity, R.id.pf_auth_et_password).setText(password)
        input(activity, R.id.pf_auth_et_code).setText(code)
    }
    private fun assertHeader(activity: AuthForgotPasswordActivity, id: Int) =
        assertThat(title(activity).text.toString()).isEqualTo(activity.getString(id))
    @Suppress("DEPRECATION") private fun toastTitle(): String? = ShadowToast.getLatestToast()?.view
        ?.findViewById<TextView>(R.id.pf_auth_toast_title)?.text?.toString()
    @Suppress("DEPRECATION") private fun toastMessage(): String? = ShadowToast.getLatestToast()?.view
        ?.findViewById<TextView>(R.id.pf_auth_toast_message)?.text?.toString()
    private fun accountPreferences() = app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Activity.MODE_PRIVATE)
    private fun assertNoAccountWrite() {
        assertThat(TokenStore.peek()).isNull()
        assertThat(accountPreferences().all).isEmpty()
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

    private fun timerTrace(label: String, activity: AuthForgotPasswordActivity) {
        val timer = ReflectionHelpers.getField<android.os.CountDownTimer?>(activity, "countdown")
        val stop = timer?.let { ReflectionHelpers.getField<Long>(it, "mStopTimeInFuture") }
        println("R168CLOCK $label elapsed=${SystemClock.elapsedRealtime()} uptime=${SystemClock.uptimeMillis()} stop=$stop text=${send(activity).text}")
    }
}
