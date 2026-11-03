package com.projectfocus.app.auth

import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.auth.SourceRegistrationFeedback.Tone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Source reset UI, with Own scoped-theme, lifecycle and anonymous-service adapters. */
class AuthForgotPasswordActivity : AppCompatActivity() {
    private lateinit var email: TextInputEditText
    private lateinit var password: TextInputEditText
    private lateinit var code: TextInputEditText
    private lateinit var reset: MaterialButton
    private lateinit var sendCode: TextView
    private lateinit var headerTitle: TextView
    private lateinit var headerSubtitle: TextView
    private var countdown: CountDownTimer? = null
    private var counting = false
    private var sendAccent = 0
    private val sourceWindow by lazy { SourceAuthWindow(this) }
    private val gateway: PasswordResetGateway get() = (application as ProjectFocusApp).passwordResetGateway

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        sourceWindow.beforeCreate()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pf_auth_forgot_password)
        sourceWindow.bindContent()
        findViewById<View>(R.id.pf_auth_btn_back).setOnClickListener { finish() }
        email = findViewById(R.id.pf_auth_et_email)
        password = findViewById(R.id.pf_auth_et_password)
        code = findViewById(R.id.pf_auth_et_code)
        reset = findViewById(R.id.pf_auth_btn_register)
        sendCode = findViewById(R.id.pf_auth_tv_send_code)
        headerTitle = findViewById(R.id.pf_auth_tv_title)
        headerSubtitle = findViewById(R.id.pf_auth_tv_subtitle)
        // Source c1.b's accent is represented by the existing Own scoped theme.
        sendAccent = MaterialColors.getColor(sendCode, androidx.appcompat.R.attr.colorPrimary)
        fun watcher(emailChanged: Boolean) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val input = fields()
                updateHeader(input)
                updateReset(input)
                if (emailChanged) updateSend(input.validEmail)
            }
        }
        email.addTextChangedListener(watcher(true))
        password.addTextChangedListener(watcher(false))
        code.addTextChangedListener(watcher(false))
        sendCode.setOnClickListener { requestCode() }
        reset.setOnClickListener { submit() }
        updateHeader(PasswordResetInputs.read("", "", ""))
        updateSend(false)
        updateReset(fields())
    }

    private fun fields(): PasswordResetInputs = PasswordResetInputs.read(email.text?.toString().orEmpty(),
        code.text?.toString().orEmpty(), password.text?.toString().orEmpty())

    private fun updateHeader(input: PasswordResetInputs) {
        val pair = when {
            input.email.isEmpty() -> R.string.pf_auth_forgot_password_prompt_enter_email_title to R.string.pf_auth_forgot_password_prompt_enter_email_subtitle
            !input.validEmail -> R.string.pf_auth_forgot_password_prompt_email_invalid_title to R.string.pf_auth_forgot_password_prompt_email_invalid_subtitle
            input.newPassword.isEmpty() -> R.string.pf_auth_forgot_password_prompt_enter_new_password_title to R.string.pf_auth_register_prompt_password_min_length_subtitle
            input.newPassword.length < 8 -> R.string.pf_auth_forgot_password_prompt_password_too_short_title to R.string.pf_auth_register_prompt_password_min_length_subtitle
            input.code.isEmpty() -> R.string.pf_auth_forgot_password_prompt_enter_code_title to R.string.pf_auth_register_prompt_enter_code_subtitle
            else -> R.string.pf_auth_forgot_password_prompt_ready_title to R.string.pf_auth_forgot_password_prompt_ready_subtitle
        }
        headerTitle.setText(pair.first)
        headerSubtitle.setText(pair.second)
        animateHeader()
    }

    private fun animateHeader() {
        for (view in listOf(headerTitle, headerSubtitle)) {
            view.alpha = 0.6f
            view.scaleX = 0.98f
            view.scaleY = 0.98f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start()
        }
    }

    private fun updateReset(input: PasswordResetInputs = fields()) {
        reset.isEnabled = input.canSubmit
        reset.alpha = if (input.canSubmit) 1f else 0.6f
        reset.setText(if (input.canSubmit) R.string.pf_auth_forgot_password_action_update else R.string.pf_auth_fill_form_hint)
    }

    private fun updateSend(valid: Boolean = fields().validEmail) {
        sendCode.isEnabled = !counting && valid
        sendCode.alpha = if (counting) 0.6f else if (valid) 1f else 0.5f
        sendCode.setTextColor(if (!counting && valid) sendAccent else getColor(R.color.pf_auth_toast_message_color))
    }

    private fun requestCode() {
        val input = fields()
        if (!input.validEmail) {
            notice(R.string.pf_auth_email_format_error_title, R.string.pf_auth_email_format_error_msg, Tone.WARNING)
            animateHeader()
            return
        }
        countdown?.cancel()
        counting = true
        updateSend(input.validEmail)
        sendCode.text = getString(R.string.pf_auth_send_code_countdown_fmt, 30)
        countdown = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                if (!isDestroyed) sendCode.text = getString(R.string.pf_auth_send_code_countdown_fmt, millisUntilFinished / 1_000L)
            }
            override fun onFinish() {
                counting = false
                if (!isDestroyed) {
                    sendCode.setText(R.string.pf_auth_send_code)
                    updateSend()
                }
            }
        }.start()
        lifecycleScope.launch {
            try {
                when (val status = gateway.sendCode(input.email)) {
                    200 -> notice(R.string.pf_auth_code_sent_title, R.string.pf_auth_check_email_msg, Tone.SUCCESS)
                    429 -> notice(R.string.pf_auth_common_request_too_frequent_title, R.string.pf_auth_common_try_later_msg, Tone.WARNING)
                    else -> notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_service_return_code_fmt, Tone.ERROR, status)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: PasswordResetRequestBuildException) {
                notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_request_build_error, Tone.ERROR)
            } catch (_: IOException) {
                notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_network_error_try_later, Tone.ERROR)
            }
        }
    }

    private fun submit() {
        val input = fields()
        if (!input.canSubmit) {
            notice(R.string.pf_auth_form_incomplete_title, R.string.pf_auth_form_incomplete_msg, Tone.WARNING)
            animateHeader()
            return
        }
        // Preserve source's repeatable valid clicks; each re-reads live fields.
        lifecycleScope.launch {
            try {
                when (val status = gateway.reset(input.email, input.code, input.newPassword)) {
                    200 -> {
                        notice(R.string.pf_auth_forgot_password_update_success_title, R.string.pf_auth_forgot_password_update_success_msg, Tone.SUCCESS)
                        if (!isDestroyed) finish()
                    }
                    400 -> notice(R.string.pf_auth_invalid_params_or_code_title, R.string.pf_auth_invalid_params_or_code_msg, Tone.WARNING)
                    404 -> notice(R.string.pf_auth_forgot_password_user_not_found_title, R.string.pf_auth_forgot_password_user_not_found_msg, Tone.WARNING)
                    else -> notice(R.string.pf_auth_forgot_password_update_failed_title, R.string.pf_auth_common_service_return_code_fmt, Tone.ERROR, status)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: PasswordResetRequestBuildException) {
                notice(R.string.pf_auth_forgot_password_update_failed_title, R.string.pf_auth_common_request_build_error, Tone.ERROR)
            } catch (_: IOException) {
                notice(R.string.pf_auth_forgot_password_update_failed_title, R.string.pf_auth_common_network_error_try_later, Tone.ERROR)
            }
        }
    }

    private fun notice(title: Int, message: Int, tone: Tone, vararg args: Any) =
        SourceRegistrationFeedback.show(this, title, message, tone, *args)

    override fun onResume() {
        super.onResume()
        sourceWindow.onResume()
    }

    override fun onDestroy() {
        countdown?.cancel()
        countdown = null
        super.onDestroy() // Own lifecycleScope cancellation also cancels this screen's pending HTTP.
    }
}
