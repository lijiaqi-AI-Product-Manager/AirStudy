package com.projectfocus.server.domain

import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Project-owned text policy. No original service, credentials or cloud moderation call. */
fun interface SceneContentChecker {
    fun isAllowed(text: String): Boolean
}

/**
 * A small real baseline policy plus operator-configured literal deny terms.
 * This is not the unobserved original service's policy or comprehensive moderation.
 * Names use this project's existing nickname bound; it is not an original scene limit.
 */
class RuleBasedSceneContentChecker private constructor(private val blockedTerms: Set<String>) : SceneContentChecker {
    override fun isAllowed(text: String): Boolean {
        // Validate raw input first: trim in the shared nickname rule must not hide a newline.
        if (text.any { it.isISOControl() }) return false
        val name = try {
            AuthService.validateNickname(text)
        } catch (_: NicknameInvalidException) {
            return false
        }
        val normalized = normalize(name)
        return blockedTerms.none { normalized.contains(it) }
    }

    companion object {
        const val EXTRA_TERMS_ENV = "PF_SCENE_CONTENT_DENY_TERMS_JSON"

        // Project-authored starting vocabulary, intentionally small and reviewable.
        // These are literal substring rules after NFKC/case normalization, not source evidence.
        private val BASE_TERMS = listOf("傻逼", "操你妈", "fuck", "shit")

        fun fromEnv(getenv: (String) -> String? = System::getenv): RuleBasedSceneContentChecker {
            val additional = getenv(EXTRA_TERMS_ENV)?.let(::readAdditionalTerms).orEmpty()
            return RuleBasedSceneContentChecker((BASE_TERMS + additional).map(::normalize).toSet())
        }

        private fun readAdditionalTerms(raw: String): List<String> {
            val array = try {
                Json.parseToJsonElement(raw)
            } catch (error: SerializationException) {
                throw IllegalArgumentException("$EXTRA_TERMS_ENV must be a JSON array of nonempty strings", error)
            }
            require(array is JsonArray) { "$EXTRA_TERMS_ENV must be a JSON array of nonempty strings" }
            return array.map { item ->
                require(item is JsonPrimitive && item.isString) {
                    "$EXTRA_TERMS_ENV must contain strings only"
                }
                require(item.content.none { it.isISOControl() }) {
                    "$EXTRA_TERMS_ENV terms must contain no control characters"
                }
                item.content.trim().also { term ->
                    require(term.isNotEmpty()) {
                        "$EXTRA_TERMS_ENV terms must be nonempty"
                    }
                }
            }
        }

        private fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    }
}
