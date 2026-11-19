package com.projectfocus.server.domain

import java.nio.charset.StandardCharsets.UTF_8
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Own protocol domains; not a claim about the original server's OTP policy. */
enum class EmailChallengePurpose(val wireValue: String, internal val hmacContext: String) {
    REGISTER("register", "projectfocus-register"),
    PASSWORD_RESET("password_reset", "projectfocus-password-reset"),
}


internal class EmailChallengeDigests(secret: String) {
    private val key = SecretKeySpec(secret.toByteArray(UTF_8), "HmacSHA256")

    fun digest(purpose: EmailChallengePurpose, subject: String, generation: String, code: String): String =
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal("${purpose.hmacContext}\u0000$subject\u0000$generation\u0000$code".toByteArray(UTF_8))
                .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        }
}
