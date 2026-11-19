package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.network.dto.SceneContentCheckRequest
import com.projectfocus.server.domain.RuleBasedSceneContentChecker
import com.projectfocus.server.domain.SceneContentChecker
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SceneContentCheckRoutesTest : ServerTestBase() {
    @Test
    fun `ordinary local scene names get an actual JSON boolean without requiring login`() = contentTest("scene_content_plain") { c ->
        for (name in listOf("深度阅读", "学习 🧑‍🚀", "  Study time  ")) {
            val response = c.post(PATH, request(name))
            assertAllowed(c, response, true)
        }
        // Making this endpoint anonymous must not remove the existing profile authentication.
        assertThat(c.get("/user/me").status).isEqualTo(HttpStatusCode.Unauthorized)
    }

    @Test
    fun `real baseline terms and their normalized forms are rejected by the routed production engine`() = contentTest("scene_content_terms") { c ->
        for (name in listOf("傻逼", "操你妈", "FUCK", "ＦＵＣＫ", "shit")) {
            assertAllowed(c, c.post(PATH, request(name)), false)
        }
    }

    @Test
    fun `raw control characters are rejected even when a trailing newline would be trimmed`() = contentTest("scene_content_control") { c ->
        for (name in listOf("阅读\u0000", "\n阅读", "阅读\n", "读\t书", "读\r\n书")) {
            assertAllowed(c, c.post(PATH, request(name)), false)
        }
    }

    @Test
    fun `existing project nickname rule rejects blank and 21 code points while 20 astral characters pass`() = contentTest("scene_content_names") { c ->
        for (name in listOf("", "   ", "书".repeat(21))) {
            assertAllowed(c, c.post(PATH, request(name)), false)
        }
        assertAllowed(c, c.post(PATH, request("📚".repeat(20))), true)
    }

    @Test
    fun `missing non string and malformed request values get errors rather than allowed`() = contentTest("scene_content_bad_body") { c ->
        val bodies = listOf(
            "{}", "{\"text\":true}", "{\"text\":123}", "{\"text\":null}",
            "{\"text\":[]}", "{\"text\":{}}", "[]", "null", "\"reading\"", "{",
        )
        for (body in bodies) {
            val response = c.post(PATH, body)
            assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
            assertThat(c.errorCode(response)).isEqualTo(4001)
            assertThat(c.data(response)).isNull()
        }
    }

    @Test
    fun `non JSON media type is a request error`() = contentTest("scene_content_media") { c ->
        val response = c.http.post(PATH) {
            contentType(ContentType.Text.Plain)
            setBody(request("阅读"))
        }
        assertThat(response.status).isEqualTo(HttpStatusCode.BadRequest)
        assertThat(c.errorCode(response)).isEqualTo(4001)
        assertThat(c.data(response)).isNull()
    }

    @Test
    fun `operator configured terms change real route decisions and never remove the baseline`() {
        val configured = RuleBasedSceneContentChecker.fromEnv { key ->
            if (key == RuleBasedSceneContentChecker.EXTRA_TERMS_ENV) "[\"spoiler\",\"刷单\"]" else null
        }
        assertThat(defaultChecker().isAllowed("spoiler reading")).isTrue()
        contentTest("scene_content_configured", configured) { c ->
            for (name in listOf("ＳＰＯＩＬＥＲ reading", "刷单", "fuck")) {
                assertAllowed(c, c.post(PATH, request(name)), false)
            }
            assertAllowed(c, c.post(PATH, request("quiet reading")), true)
        }
    }

    @Test
    fun `invalid operator rule configuration fails instead of installing an allow all fallback`() {
        for (value in listOf("", "{", "{}", "null", "[true]", "[7]", "[null]", "[\"\"]", "[\"   \"]", "[\"line\\nrule\"]", "[\"rule\\n\"]")) {
            assertThrows(IllegalArgumentException::class.java) {
                RuleBasedSceneContentChecker.fromEnv { key ->
                    if (key == RuleBasedSceneContentChecker.EXTRA_TERMS_ENV) value else null
                }
            }
        }
        val noExtras = RuleBasedSceneContentChecker.fromEnv { "[]" }
        assertThat(noExtras.isAllowed("fuck")).isFalse()
    }

    @Test
    fun `unknown checker failure stays an HTTP error without any successful allowed payload`() = contentTest(
        "scene_content_failed_service",
        SceneContentChecker { throw IllegalStateException("checker dependency unavailable") },
    ) { c ->
        val response = c.post(PATH, request("学习"))
        assertThat(response.status).isEqualTo(HttpStatusCode.InternalServerError)
        assertThat(c.errorCode(response)).isEqualTo(5000)
        assertThat(c.data(response)).isNull()
        assertThat(response.bodyAsText()).doesNotContain("checker dependency unavailable")
    }

    private fun contentTest(
        suffix: String,
        checker: SceneContentChecker = defaultChecker(),
        block: suspend ApplicationTestBuilder.(TestClient) -> Unit,
    ) = testApplication {
        application { module(testConfig(suffix), sceneContentChecker = checker) }
        block(TestClient(client))
    }

    private suspend fun assertAllowed(c: TestClient, response: HttpResponse, expected: Boolean) {
        assertThat(response.status).isEqualTo(HttpStatusCode.OK)
        val envelope = c.jsonOf(response)
        assertThat(envelope["error"] == null || envelope["error"] == JsonNull).isTrue()
        val flag = c.element(response, "allowed") as? JsonPrimitive
        assertThat(flag).isNotNull()
        assertThat(flag!!.isString).isFalse()
        assertThat(flag.booleanOrNull).isEqualTo(expected)
    }

    private fun request(text: String): String = Json.encodeToString(SceneContentCheckRequest(text))

    private fun defaultChecker(): RuleBasedSceneContentChecker = RuleBasedSceneContentChecker.fromEnv { null }

    companion object {
        private const val PATH = "/user/content/check"
    }
}
