package com.projectfocus.app.auth

import android.util.Patterns

/** Source reset normalization: Java trim email/code; preserve raw UTF-16 password. */
internal data class PasswordResetInputs(val email: String, val code: String, val newPassword: String) {
    val validEmail: Boolean get() = email.isNotEmpty() && Patterns.EMAIL_ADDRESS.matcher(email).matches()
    val canSubmit: Boolean get() = validEmail && code.isNotEmpty() && newPassword.isNotEmpty() && newPassword.length >= 8

    companion object {
        fun read(email: String, code: String, newPassword: String): PasswordResetInputs =
            PasswordResetInputs(email.trim { it.code <= 0x20 }, code.trim { it.code <= 0x20 }, newPassword)
    }
}
