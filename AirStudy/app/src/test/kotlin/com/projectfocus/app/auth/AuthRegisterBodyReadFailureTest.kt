package com.projectfocus.app.auth

import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.core.network.di.TokenStore
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Response
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
import org.robolectric.shadows.ShadowToast

/** EOF is produced by actual local HTTP framing, not by a fake Gateway throwing an exception. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi",
    shadows = [RealHandlerCountDownTimerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthRegisterBodyReadFailureTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private lateinit var app: ProjectFocusApp
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var directory: File
    private lateinit var previousPromo: AuthPromoImageLoader
    private lateinit var previousGateway: RegistrationGateway
    private var activity: ActivityController<AuthRegisterActivity>? = null
    private var previousNight = AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
    private val receivedHeaders = ConcurrentLinkedQueue<Triple<String, Int, String?>>()
    private val failedCalls = ConcurrentLinkedQueue<String>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        TokenStore.clear(); ShadowToast.reset()
        previousNight = AppCompatDelegate.getDefaultNightMode()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
        previousPromo = app.authPromoImages
        previousGateway = app.registrationGateway
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "registration-body-eof-").toFile()
        client = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun responseHeadersEnd(call: Call, response: Response) {
                if (call.request().url.encodedPath.startsWith("/auth/register")) {
                    receivedHeaders += Triple(call.request().url.encodedPath, response.code, response.header("Content-Length"))
                }
            }
            override fun callFailed(call: Call, ioe: IOException) { failedCalls += call.request().url.encodedPath }
        }).build()
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                    "/auth/promo-image" -> MockResponse.Builder().code(503).body("unavailable").build()
                    "/auth/register/code" -> truncated(200)
                    "/auth/register" -> truncated(201)
                    else -> MockResponse.Builder().code(404).build()
                }
            }
            start()
        }
        app.authPromoImages = AuthPromoImageLoader(app, client, server.url("/auth/promo-image").toString(), cacheDirectory = directory)
        app.registrationGateway = OwnRegistrationGateway(client, server.url("/").toString())
    }

    @After fun tearDown() {
        activity?.pause()?.stop()?.destroy()
        app.authPromoImages = previousPromo
        app.registrationGateway = previousGateway
        client.dispatcher.cancelAll()
        server.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
        check(directory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        directory.walkBottomUp().forEach { it.delete() }
        restoreAuthTestNightMode(previousNight)
        TokenStore.clear(); ShadowToast.reset()
    }

    @Test fun `actual truncated bodies are response read failures and neither send nor201 posts UI results`() = runBlocking {
        // Prove the actual gateway, framing and error phase, rather than merely
        // observing a temporarily empty Toast queue before a callback finishes.
        try {
            app.registrationGateway.sendCode("user@example.test")
            throw AssertionError("The short body cannot satisfy declared Content-Length4096")
        } catch (error: RegistrationBodyReadException) {
            assertThat(error.cause).isInstanceOf(IOException::class.java)
            assertThat(error.cause).isNotInstanceOf(InterruptedIOException::class.java)
        }
        assertThat(receivedHeaders).contains(Triple("/auth/register/code", 200, "4096"))
        assertThat(failedCalls).contains("/auth/register/code")
        val controller = Robolectric.buildActivity(AuthRegisterActivity::class.java).setup().visible()
        activity = controller
        val target = controller.get()
        val identity = TokenStore.snapshot()
        val preferences = target.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, 0).all.toMap()
        target.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_email).getText().append("user@example.test")
        target.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_password).getText().append("12345678")
        target.findViewById<EditText>(R.id.pf_auth_et_code).setText("123456")
        val send = target.findViewById<TextView>(R.id.pf_auth_tv_send_code)
        send.performClick()
        val sendJob = requestJob(target)
        await("send EOF callback and Activity coroutine completed") { sendJob.isCompleted }
        assertThat(ShadowToast.getLatestToast()).isNull()
        assertThat(target.isFinishing).isFalse()
        assertThat(send.isEnabled).isFalse()
        assertThat(send.text.toString()).isNotEqualTo(target.getString(R.string.pf_auth_send_code))
        assertThat(send.alpha).isEqualTo(0.6f)

        val register = target.findViewById<MaterialButton>(R.id.pf_auth_btn_register)
        assertThat(register.isEnabled).isTrue()
        register.performClick()
        val registerJob = requestJob(target)
        await("201 EOF callback and Activity coroutine completed") { registerJob.isCompleted }
        assertThat(receivedHeaders).contains(Triple("/auth/register", 201, "4096"))
        assertThat(failedCalls).contains("/auth/register")
        assertThat(ShadowToast.getLatestToast()).isNull()
        assertThat(target.isFinishing).isFalse() // No source success post exists after failed body.string.
        assertThat(register.isEnabled).isTrue()
        assertThat(TokenStore.snapshot()).isEqualTo(identity)
        assertThat(target.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, 0).all).isEqualTo(preferences)
        shadowOf(Looper.getMainLooper()).idleFor(31, TimeUnit.SECONDS)
        assertThat(send.text.toString()).isEqualTo(target.getString(R.string.pf_auth_send_code))
        assertThat(send.isEnabled).isTrue()
        assertThat(ShadowToast.getLatestToast()).isNull()
    }

    private fun requestJob(target: AuthRegisterActivity): Job =
        checkNotNull(target.lifecycleScope.coroutineContext[Job]).children.single()

    private fun truncated(status: Int): MockResponse = MockResponse.Builder().code(status).body("short")
        .setHeader("Content-Length", "4096").setHeader("Connection", "close")
        .onResponseEnd(SocketEffect.CloseSocket()).build()

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
