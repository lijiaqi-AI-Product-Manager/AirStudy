package com.projectfocus.app.auth

import android.util.Patterns

/** Source Register input normalization; password and UTF-16 length are preserved. */
internal data class RegistrationInputs(val email: String, val code: String, val password: String) {
    val validEmail: Boolean get() = email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val canSubmit: Boolean get() = validEmail && code.isNotEmpty() && password.isNotEmpty() && password.length >= 8

    companion object {
        fun read(email: String, code: String, password: String): RegistrationInputs =
            RegistrationInputs(javaTrim(email), javaTrim(code), password)

        // Java String.trim, not Kotlin's Unicode-whitespace trim().
        private fun javaTrim(value: String): String = value.trim { it.code <= 0x20 }
    }
}
