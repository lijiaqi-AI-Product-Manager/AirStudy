package com.projectfocus.server

import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.domain.RegistrationEmail
import com.projectfocus.server.domain.RegistrationEmailDelivery
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap























abstract class ServerTestBase {

    /** 测试用配置：H2 内存库，每个用例独立库名，避免用例间污染 */
    protected fun testConfig(dbSuffix: String): ServerConfig {
        val base = ServerConfig.fromEnv { key ->
            when (key) {
                "PF_ENV" -> "dev"
                "PF_DB_URL" -> "jdbc:h2:mem:pf_$dbSuffix;DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
                "PF_DB_USER" -> "sa"
                "PF_DB_PASSWORD" -> ""
                "PF_JWT_SECRET" -> TEST_SECRET
                "PF_RL_GENERAL" -> "100000"   // 测试里把限流调高，否则连续请求会被 429
                "PF_RL_JOIN" -> "100000"
                "PF_RL_CHAT" -> "100000"
                else -> null
            }
        }
        return base
    }

    protected fun serverTest(
        dbSuffix: String,
        block: suspend ApplicationTestBuilder.(TestClient) -> Unit,
    ) = testApplication {
        val mailbox = FixtureRegistrationMailbox()
        application { module(testConfig(dbSuffix), registrationEmailDelivery = mailbox) }
        block(TestClient(client, mailbox))
    }

    companion object {
        const val TEST_SECRET = "test-secret-at-least-32-bytes-long-for-hs256!!"
    }
}







class TestClient(
    internal val http: HttpClient,
    private val registrationMailbox: FixtureRegistrationMailbox? = null,
) {

    internal var token: String? = null
        private set

    fun withToken(t: String?): TestClient = apply { token = t }

    /** SSE 用：需要拿到内部 token 来手工构造流式请求 */
    internal val currentToken: String? get() = token

    suspend fun get(path: String): HttpResponse = send(HttpMethod.Get, path)
    suspend fun post(path: String, body: String = "{}"): HttpResponse = send(HttpMethod.Post, path, body)
    suspend fun patch(path: String, body: String = "{}"): HttpResponse = send(HttpMethod.Patch, path, body)
    suspend fun put(path: String, body: String = "{}"): HttpResponse = send(HttpMethod.Put, path, body)
    suspend fun delete(path: String): HttpResponse = send(HttpMethod.Delete, path)

    /** Real send-code HTTP followed only by reading this application's test delivery port. */
    suspend fun requestRegistrationCode(email: String): String {
        val sent = post("/auth/register/code", json("email" to email))
        check(sent.status.value == 200) { "Fixture send-code did not return 200" }
        return deliveredRegistrationCode(email)
    }

    internal fun deliveredRegistrationCode(email: String): String =
        checkNotNull(registrationMailbox) { "This TestClient has no registration mailbox" }.codeFor(email)

    /** Returns the actual registration response. It never logs in or changes this client's token. */
    suspend fun registerWithCode(email: String, password: String = "hunter2!!"): HttpResponse {
        val code = requestRegistrationCode(email)
        return post("/auth/register", json("email" to email, "code" to code, "password" to password))
    }

    /** Returns the actual, separate login response, with no implicit withToken mutation. */
    suspend fun registerAndLogin(email: String, password: String = "hunter2!!"): HttpResponse {
        val registered = registerWithCode(email, password)
        check(registered.status.value == 201) { "Fixture register did not return 201" }
        val login = post("/auth/login", json("email" to email, "password" to password))
        check(login.status.value == 200) { "Fixture login did not return 200" }
        check(!str(login, "token").isNullOrBlank()) { "Fixture login did not return a token" }
        return login
    }

    private suspend fun send(method: HttpMethod, path: String, body: String? = null): HttpResponse =
        http.request(path) {
            this.method = method
            token?.let { header("Authorization", "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    // ── 解析辅助 ─────────────────────────────────────────

    suspend fun jsonOf(resp: HttpResponse): JsonObject =
        Json.parseToJsonElement(resp.bodyAsText()).jsonObject

    /** 取 `data` 对象；若 `data` 为 null 返回 null */
    suspend fun data(resp: HttpResponse): JsonObject? =
        jsonOf(resp)["data"]?.takeIf { it is JsonObject }?.jsonObject

    /** 取 `error.code`；成功时为 null */
    suspend fun errorCode(resp: HttpResponse): Int? =
        jsonOf(resp)["error"]?.takeIf { it is JsonObject }
            ?.jsonObject?.get("code")?.jsonPrimitive?.content?.toIntOrNull()

    suspend fun str(resp: HttpResponse, field: String): String? =
        data(resp)?.get(field)?.jsonPrimitive?.content

    suspend fun element(resp: HttpResponse, field: String): JsonElement? = data(resp)?.get(field)
}

/** Per-server test delivery only. No relay, global mailbox, logged code or account bypass. */
class FixtureRegistrationMailbox : RegistrationEmailDelivery {
    private val codes = ConcurrentHashMap<String, String>()

    override fun deliver(message: RegistrationEmail): Boolean {
        codes[canonicalEmail(message.recipient)] = message.code
        return true
    }

    fun codeFor(email: String): String =
        checkNotNull(codes[canonicalEmail(email)]) { "No registration message was delivered to this fixture" }

    private fun canonicalEmail(email: String): String =
        email.trim { it <= '\u0020' }.lowercase(Locale.ROOT)
}

/** Netty fixtures use the same real HTTP sequence and their own injected mailbox. */
internal fun registerAndLoginOverHttp(
    http: java.net.http.HttpClient,
    baseUrl: String,
    mailbox: FixtureRegistrationMailbox,
    email: String,
    password: String = "hunter2!!",
    nickname: String? = null,
): JsonObject {
    fun request(path: String, body: String, token: String? = null, method: String = "POST"): java.net.http.HttpResponse<String> {
        val builder = java.net.http.HttpRequest.newBuilder(java.net.URI("$baseUrl$path"))
            .timeout(java.time.Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .method(method, java.net.http.HttpRequest.BodyPublishers.ofString(body))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return http.send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    }

    val sent = request("/auth/register/code", json("email" to email))
    check(sent.statusCode() == 200) { "Fixture send-code did not return 200" }
    val registered = request("/auth/register", json("email" to email,
        "code" to mailbox.codeFor(email), "password" to password))
    check(registered.statusCode() == 201) { "Fixture register did not return 201" }
    val login = request("/auth/login", json("email" to email, "password" to password))
    check(login.statusCode() == 200) { "Fixture login did not return 200" }
    val data = Json.parseToJsonElement(login.body()).jsonObject["data"]!!.jsonObject
    val token = data["token"]!!.jsonPrimitive.content
    check(token.isNotBlank()) { "Fixture login did not return a token" }
    if (nickname != null) {
        val profile = request("/user/update", json("username" to nickname), token, "PATCH")
        check(profile.statusCode() == 200) { "Fixture profile update did not return 200" }
        check(Json.parseToJsonElement(profile.body()).jsonObject["data"]!!.jsonObject["username"]!!.jsonPrimitive.content == nickname)
    }
    return data
}

/** 便捷：构造 JSON 文本（测试里手写 JSON 比构造 DTO 更能验证字段名） */
fun json(vararg pairs: Pair<String, Any?>): String = buildString {
    append('{')
    pairs.forEachIndexed { i, (k, v) ->
        if (i > 0) append(',')
        append('"').append(k).append("\":")
        append(
            when (v) {
                null -> "null"
                is String -> "\"" + v.replace("\"", "\\\"") + "\""
                is Boolean, is Int, is Long, is Double -> v.toString()
                else -> v.toString()
            },
        )
    }
    append('}')
}
