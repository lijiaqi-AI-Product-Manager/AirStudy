package com.projectfocus.app.auth

import android.content.Context
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.core.network.di.TokenStore
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast

/** Actual Activity -> own OkHttp -> packaged Netty server -> local mail relay -> real SQL registration. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi",
    shadows = [RealHandlerCountDownTimerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthRegistrationServerE2ETest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    @Test fun actualPageVerifiesTheDeliveredCodeCreatesAnAccountAndReturnsWithoutLoggingIn() {
        val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
        TokenStore.clear()
        app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        val client = OkHttpClient()
        val previousGateway = app.registrationGateway
        val previousImages = app.authPromoImages
        val cache = Files.createTempDirectory(app.cacheDir.toPath(), "registration-e2e-").toFile()
        var activity: ActivityController<AuthRegisterActivity>? = null
        MockWebServer().apply {
            enqueue(MockResponse.Builder().code(202).build())
            start()
        }.use { relay ->
            Backend(relay.url("/mail").toString()).use { server ->
                try {
                    app.registrationGateway = OwnRegistrationGateway(client, server.baseUrl)
                    app.authPromoImages = AuthPromoImageLoader(app, client, server.baseUrl + "/auth/promo-image", cacheDirectory = cache)
                    activity = Robolectric.buildActivity(AuthRegisterActivity::class.java).setup()
                    val screen = checkNotNull(activity).get()
                    val email = screen.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_email)
                    val password = screen.findViewById<SourceAuthInputView>(R.id.pf_auth_civ_password)
                    val send = screen.findViewById<TextView>(R.id.pf_auth_tv_send_code)
                    val code = screen.findViewById<EditText>(R.id.pf_auth_et_code)
                    email.getText().append(" Pilot-e2e@ProjectFocus.test ")
                    val rawPassword = "  e2e-password123  "
                    password.getText().append(rawPassword)
                    assertThat(send.isEnabled).isTrue()
                    send.performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    val delivered = checkNotNull(relay.takeRequest(10, TimeUnit.SECONDS)) { "Own server did not deliver to the local relay" }
                    assertThat(delivered.method).isEqualTo("POST")
                    assertThat(delivered.url.encodedPath).isEqualTo("/mail")
                    assertThat(delivered.headers["Authorization"]).isNull()
                    val message = JSONObject(checkNotNull(delivered.body).utf8())
                    assertThat(message.getString("recipient")).isEqualTo("pilot-e2e@projectfocus.test")
                    assertThat(message.getString("purpose")).isEqualTo("register")
                    assertThat(message.getLong("validForSeconds")).isEqualTo(600L)
                    val actualCode = message.getString("code")
                    assertThat(actualCode).matches("[0-9]{6}")
                    await("actual send-code response reached the page") {
                        ShadowToast.getLatestToast()?.view?.findViewById<TextView>(R.id.pf_auth_toast_title)?.text?.toString() ==
                            screen.getString(R.string.pf_auth_code_sent_title)
                    }
                    assertThat(send.isEnabled).isFalse()
                    code.setText(actualCode)
                    val checkbox = screen.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)
                    assertThat(checkbox.isChecked()).isFalse()
                    val submit = screen.findViewById<android.view.View>(R.id.pf_auth_btn_register)
                    assertThat(submit.isEnabled).isTrue()
                    ShadowToast.reset()
                    submit.performClick()
                    await("real201 finished the actual registration page") { screen.isFinishing }
                    assertThat(TokenStore.peek()).isNull()
                    assertThat(app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).all).isEmpty()
                    assertThat(shadowOf(screen).resultCode).isNotEqualTo(android.app.Activity.RESULT_OK)
                    assertThat(ShadowToast.getLatestToast()?.view?.findViewById<TextView>(R.id.pf_auth_toast_title)?.text?.toString())
                        .isEqualTo(screen.getString(R.string.pf_auth_register_create_success_title))
                    assertThat(server.get("/user/me").statusCode()).isEqualTo(401)
                    val wrong = server.post("/auth/login", JSONObject().put("email", "pilot-e2e@projectfocus.test")
                        .put("password", rawPassword.trim()).toString())
                    assertThat(wrong.statusCode()).isEqualTo(401)
                    val login = server.post("/auth/login", JSONObject().put("email", "pilot-e2e@projectfocus.test")
                        .put("password", rawPassword).toString())
                    assertThat(login.statusCode()).isEqualTo(200)
                    val token = JSONObject(login.body()).getJSONObject("data").getString("token")
                    assertThat(server.get("/user/me", token).statusCode()).isEqualTo(200)
                    // This separate verifier login is not published into Android's account state.
                    assertThat(TokenStore.peek()).isNull()
                } finally {
                    activity?.let { it.pause().stop().destroy() }
                    app.registrationGateway = previousGateway
                    app.authPromoImages = previousImages
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                    ShadowToast.reset()
                    TokenStore.clear()
                }
            }
        }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        check(condition()) { label }
    }

    private class Backend(relayUrl: String) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        private val log = File("build/reports/r167-registration-e2e/backend.log").apply { parentFile.mkdirs() }
        private val process: Process
        init {
            val library = File(checkNotNull(System.getProperty("pf.registration.server.libDir")) { "Server installDist path is required" })
            check(library.isDirectory && library.listFiles().orEmpty().any { it.extension == "jar" })
            val java = File(System.getProperty("java.home"), "bin/java" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            process = ProcessBuilder(java.absolutePath, "-Dfile.encoding=UTF-8", "-cp", File(library, "*").absolutePath,
                "com.projectfocus.server.ApplicationKt").apply {
                redirectErrorStream(true)
                redirectOutput(log)
                environment().putAll(mapOf(
                    "PF_ENV" to "dev", "PF_HOST" to "127.0.0.1", "PF_PORT" to port.toString(), "PORT" to port.toString(),
                    "PF_DB_URL" to "jdbc:h2:mem:e2e_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "PF_DB_USER" to "sa", "PF_DB_PASSWORD" to "", "PF_JWT_SECRET" to "e2e-fixture-only-secret-at-least-32-bytes",
                    "PF_RL_GENERAL" to "100000", "PF_RL_JOIN" to "100000", "PF_RL_CHAT" to "100000",
                    "PF_REGISTRATION_MAIL_RELAY_URL" to relayUrl, "PF_REGISTRATION_MAIL_RELAY_TOKEN" to ""))
            }.start()
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
                var ready = false
                while (!ready && process.isAlive && System.nanoTime() < deadline) {
                    ready = try { get("/health").statusCode() == 200 } catch (_: Exception) { false }
                    if (!ready) Thread.sleep(50)
                }
                check(ready) { "Packaged own backend did not become ready; see the local backend.log" }
            } catch (error: Throwable) { close(); throw error }
        }
        fun post(path: String, body: String): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI(baseUrl + path))
            .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
        fun get(path: String, token: String? = null): HttpResponse<String> = http.send(HttpRequest.newBuilder(URI(baseUrl + path))
            .timeout(Duration.ofSeconds(1)).apply { token?.let { header("Authorization", "Bearer $it") } }.GET().build(),
            HttpResponse.BodyHandlers.ofString())
        override fun close() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }
}
