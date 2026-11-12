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

/** Actual reset Activity -> anonymous production gateway -> packaged own server -> relay -> SQL. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi",
    shadows = [RealHandlerCountDownTimerShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class AuthPasswordResetServerE2ETest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    @Test fun deliveredCodeChangesExistingPasswordAndReturnsWithoutSigningIn() {
        val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
        TokenStore.clear()
        val accounts = app.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE)
        accounts.edit().clear().commit()
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        shadowOf(Looper.getMainLooper()).idle()
        ShadowToast.reset()
        val client = OkHttpClient()
        val previous = app.passwordResetGateway
        var controller: ActivityController<AuthForgotPasswordActivity>? = null
        MockWebServer().apply {
            enqueue(MockResponse.Builder().code(202).build())
            enqueue(MockResponse.Builder().code(202).build())
            start()
        }.use { relay ->
            Backend(relay.url("/mail").toString()).use { server ->
                try {
                    val email = "reset-e2e@projectfocus.test"
                    val oldPassword = "original-password123"
                    val rawPassword = "  changed-password123  "
                    assertThat(server.post("/auth/register/code", JSONObject().put("email", email)).statusCode()).isEqualTo(200)
                    val registration = JSONObject(checkNotNull(relay.takeRequest(10, TimeUnit.SECONDS)?.body).utf8())
                    assertThat(registration.getString("purpose")).isEqualTo("register")
                    assertThat(server.post("/auth/register", JSONObject().put("email", email)
                        .put("code", registration.getString("code")).put("password", oldPassword)).statusCode()).isEqualTo(201)
                    assertThat(server.post("/auth/login", JSONObject().put("email", email)
                        .put("password", oldPassword)).statusCode()).isEqualTo(200)
                    app.passwordResetGateway = OwnPasswordResetGateway(client, server.baseUrl)
                    controller = Robolectric.buildActivity(AuthForgotPasswordActivity::class.java).setup()
                    val screen = checkNotNull(controller).get()
                    val emailInput = screen.findViewById<EditText>(R.id.pf_auth_et_email)
                    val passwordInput = screen.findViewById<EditText>(R.id.pf_auth_et_password)
                    val codeInput = screen.findViewById<EditText>(R.id.pf_auth_et_code)
                    val send = screen.findViewById<TextView>(R.id.pf_auth_tv_send_code)
                    val submit = screen.findViewById<android.view.View>(R.id.pf_auth_btn_register)
                    val identity = TokenStore.snapshot()
                    val before = accounts.all.toMap()
                    emailInput.setText("  RESET-e2e@projectfocus.test ")
                    passwordInput.setText(rawPassword)
                    assertThat(send.isEnabled).isTrue()
                    send.performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    val request = checkNotNull(relay.takeRequest(10, TimeUnit.SECONDS)) { "No reset delivery to local relay" }
                    assertThat(request.url.encodedPath).isEqualTo("/mail")
                    val reset = JSONObject(checkNotNull(request.body).utf8())
                    assertThat(reset.getString("purpose")).isEqualTo("password_reset")
                    assertThat(reset.getString("recipient")).isEqualTo(email)
                    val code = reset.getString("code")
                    assertThat(code).matches("[0-9]{6}")
                    await("actual reset code response") { toastTitle() == screen.getString(R.string.pf_auth_code_sent_title) }
                    assertThat(send.isEnabled).isFalse()
                    codeInput.setText(if (code == "000000") "999999" else "000000")
                    submit.performClick()
                    await("actual invalid-code response") { toastTitle() == screen.getString(R.string.pf_auth_invalid_params_or_code_title) }
                    assertThat(screen.isFinishing).isFalse()
                    codeInput.setText(" $code ")
                    assertThat(submit.isEnabled).isTrue()
                    submit.performClick()
                    await("actual200 closes reset page") { screen.isFinishing }
                    assertThat(toastTitle()).isEqualTo(screen.getString(R.string.pf_auth_forgot_password_update_success_title))
                    assertThat(shadowOf(screen).resultCode).isEqualTo(android.app.Activity.RESULT_CANCELED)
                    assertThat(shadowOf(screen).resultIntent).isNull()
                    assertThat(shadowOf(screen).nextStartedActivity).isNull()
                    assertThat(TokenStore.snapshot()).isEqualTo(identity)
                    assertThat(accounts.all).isEqualTo(before)
                    assertThat(server.get("/user/me").statusCode()).isEqualTo(401)
                    assertThat(server.post("/auth/login", JSONObject().put("email", email).put("password", oldPassword)).statusCode()).isEqualTo(401)
                    assertThat(server.post("/auth/login", JSONObject().put("email", email).put("password", rawPassword.trim())).statusCode()).isEqualTo(401)
                    val login = server.post("/auth/login", JSONObject().put("email", email).put("password", rawPassword))
                    assertThat(login.statusCode()).isEqualTo(200)
                    val token = JSONObject(login.body()).getJSONObject("data").getString("token")
                    assertThat(server.get("/user/me", token).statusCode()).isEqualTo(200)
                    assertThat(server.post("/auth/password-reset", JSONObject().put("email", email)
                        .put("code", code).put("new_password", "replay-password123")).statusCode()).isEqualTo(400)
                    assertThat(TokenStore.snapshot()).isEqualTo(identity)
                    assertThat(accounts.all).isEqualTo(before)
                } finally {
                    controller?.let { it.pause().stop().destroy() }
                    app.passwordResetGateway = previous
                    client.dispatcher.cancelAll()
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                    TokenStore.clear()
                    ShadowToast.reset()
                }
            }
        }
    }

    private fun toastTitle(): String? = ShadowToast.getLatestToast()?.view
        ?.findViewById<TextView>(R.id.pf_auth_toast_title)?.text?.toString()

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        check(condition()) { label }
    }

    private class Backend(relayUrl: String) : AutoCloseable {
        private val port = ServerSocket(0).use { it.localPort }
        val baseUrl = "http://127.0.0.1:$port"
        private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
        private val log = File("build/reports/r168-reset-e2e/backend.log").apply { parentFile.mkdirs() }
        private val process: Process

        init {
            val library = File(checkNotNull(System.getProperty("pf.registration.server.libDir")))
            check(library.isDirectory && library.listFiles().orEmpty().any { it.extension == "jar" })
            val java = File(System.getProperty("java.home"), "bin/java" +
                if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
            process = ProcessBuilder(java.absolutePath, "-Dfile.encoding=UTF-8", "-cp",
                File(library, "*").absolutePath, "com.projectfocus.server.ApplicationKt").apply {
                redirectErrorStream(true)
                redirectOutput(log)
                environment().putAll(mapOf(
                    "PF_ENV" to "dev", "PF_HOST" to "127.0.0.1", "PF_PORT" to port.toString(), "PORT" to port.toString(),
                    "PF_DB_URL" to "jdbc:h2:mem:reset_e2e_${UUID.randomUUID().toString().replace("-", "")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "PF_DB_USER" to "sa", "PF_DB_PASSWORD" to "", "PF_JWT_SECRET" to "reset-e2e-fixture-secret-at-least-32-bytes",
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
                check(ready) { "Packaged own reset backend did not become ready; inspect local backend.log" }
            } catch (error: Throwable) {
                close()
                throw error
            }
        }

        fun post(path: String, body: JSONObject): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI(baseUrl + path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
            HttpResponse.BodyHandlers.ofString())

        fun get(path: String, token: String? = null): HttpResponse<String> = http.send(
            HttpRequest.newBuilder(URI(baseUrl + path)).timeout(Duration.ofSeconds(1))
                .apply { if (token != null) header("Authorization", "Bearer $token") }.GET().build(),
            HttpResponse.BodyHandlers.ofString())

        override fun close() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }
}
