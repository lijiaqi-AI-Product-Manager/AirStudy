package com.projectfocus.app.auth

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.app.auth.SourceRegistrationFeedback.Tone
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Source Register screen; deliberately does not inherit EmailLogin agreement gate/bindChrome. */
class AuthRegisterActivity : AppCompatActivity() {
    private lateinit var email: SourceAuthInputView
    private lateinit var password: SourceAuthInputView
    private lateinit var code: EditText
    private lateinit var register: MaterialButton
    private lateinit var sendCode: TextView
    private var promo: AutoCloseable? = null
    private var countdown: CountDownTimer? = null
    private var counting = false
    private var sendAccent = 0
    private val sourceWindow by lazy { SourceAuthWindow(this) }
    private val gateway: RegistrationGateway get() = (application as ProjectFocusApp).registrationGateway

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        sourceWindow.beforeCreate()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pf_auth_register)
        sourceWindow.bindContent()
        promo = (application as ProjectFocusApp).authPromoImages.bind(findViewById<ImageView>(R.id.pf_auth_promo_image))
        findViewById<View>(R.id.pf_auth_btn_back).setOnClickListener { finish() }
        email = findViewById(R.id.pf_auth_civ_email)
        password = findViewById(R.id.pf_auth_civ_password)
        code = findViewById(R.id.pf_auth_et_code)
        register = findViewById(R.id.pf_auth_btn_register)
        sendCode = findViewById(R.id.pf_auth_tv_send_code)
        // XML ?colorPrimary is the project's scoped theme bridge for c1.b.
        sendAccent = sendCode.currentTextColor
        findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)?.let { checkbox ->
            // Original widget toggles itself. The existing owned display adapter
            // asks its host to toggle; the checkbox does not gate registration.
            checkbox.setOnClickListener { checkbox.toggle() }
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) { updateFields() }
        }
        email.findViewById<EditText>(R.id.pf_auth_et_input).addTextChangedListener(watcher)
        password.findViewById<EditText>(R.id.pf_auth_et_input).addTextChangedListener(watcher)
        code.addTextChangedListener(watcher)
        sendCode.setOnClickListener { requestCode() }
        register.setOnClickListener { submit() }
        updateFields()
    }

    private fun fields(): RegistrationInputs = RegistrationInputs.read(email.getText().toString(),
        code.text?.toString().orEmpty(), password.getText().toString())

    private fun updateFields() {
        val input = fields()
        register.isEnabled = input.canSubmit
        register.alpha = if (input.canSubmit) 1f else 0.6f
        register.setText(if (input.canSubmit) R.string.pf_auth_register_action_register else R.string.pf_auth_fill_form_hint)
        updateSend(input.validEmail)
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
                    updateSend() // Source re-reads current email when countdown finishes.
                }
            }
        }.start()
        // Source starts cooldown before enqueue, and failure does not shorten it.
        lifecycleScope.launch {
            try {
                when (val status = gateway.sendCode(input.email)) {
                    200 -> notice(R.string.pf_auth_code_sent_title, R.string.pf_auth_check_email_msg, Tone.SUCCESS)
                    429 -> notice(R.string.pf_auth_common_request_too_frequent_title, R.string.pf_auth_common_try_later_msg, Tone.WARNING)
                    else -> notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_service_return_code_fmt, Tone.ERROR, status)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: RegistrationBodyReadException) {
                // Source r2 body.string threw before posting either status or error UI.
                // Keep the form and any already-running countdown unchanged.
            } catch (_: RegistrationRequestBuildException) {
                notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_request_build_error, Tone.ERROR)
            } catch (_: IOException) {
                notice(R.string.pf_auth_send_code_failed_title, R.string.pf_auth_common_network_error_try_later, Tone.ERROR)
            }
        }
    }

    private fun submit() {
        val input = fields() // Re-read every actual click; no extra agreement gate or frozen stale fields.
        if (!input.canSubmit) {
            notice(R.string.pf_auth_form_incomplete_title, R.string.pf_auth_form_incomplete_msg, Tone.WARNING)
            return
        }
        // No source in-flight busy-disable: each valid click owns its request.
        lifecycleScope.launch {
            try {
                when (val status = gateway.register(input.email, input.code, input.password)) {
                    201 -> {
                        notice(R.string.pf_auth_register_create_success_title, R.string.pf_auth_register_create_success_msg, Tone.SUCCESS)
                        if (!isDestroyed) finish() // No setResult/login-success extra/account persistence.
                    }
                    409 -> notice(R.string.pf_auth_register_email_exists_title, R.string.pf_auth_register_email_exists_msg, Tone.WARNING)
                    400 -> notice(R.string.pf_auth_invalid_params_or_code_title, R.string.pf_auth_invalid_params_or_code_msg, Tone.WARNING)
                    403 -> notice(R.string.pf_auth_common_permission_denied_title, R.string.pf_auth_common_permission_denied_msg, Tone.WARNING)
                    else -> notice(R.string.pf_auth_register_create_failed_title, R.string.pf_auth_common_service_return_code_fmt, Tone.ERROR, status)
                }
            } catch (error: CancellationException) { throw error }
            catch (_: RegistrationBodyReadException) {
                // Source r2 body.string threw before posting either status or error UI.
                // Keep the form and any already-running countdown unchanged.
            } catch (_: RegistrationRequestBuildException) {
                notice(R.string.pf_auth_register_create_failed_title, R.string.pf_auth_common_request_build_error, Tone.ERROR)
            } catch (_: IOException) {
                notice(R.string.pf_auth_register_create_failed_title, R.string.pf_auth_common_network_error_try_later, Tone.ERROR)
            }
        }
    }

    private fun notice(title: Int, message: Int, tone: Tone, vararg args: Any) =
        SourceRegistrationFeedback.show(this, title, message, tone, *args)

    // Real original XML actions call separate legal APIs; remote documents remain
    // unresolved. Reuse the project's explicit capability boundary, never a fake
    // accepted agreement record or an original service address.
    @Suppress("UNUSED_PARAMETER") fun openTerms(view: View) = openLegal("terms")
    @Suppress("UNUSED_PARAMETER") fun openPrivacy(view: View) = openLegal("privacy")
    private fun openLegal(name: String) {
        startActivity(Intent(this, AuthCapabilityActivity::class.java).putExtra("capability", name))
    }

    override fun onResume() {
        super.onResume()
        sourceWindow.onResume()
    }

    override fun onDestroy() {
        countdown?.cancel(); countdown = null
        promo?.close(); promo = null
        super.onDestroy() // Own lifecycleScope cancellation also cancels this screen's HTTP.
    }
}
