package com.projectfocus.app.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.projectfocus.app.R
import com.projectfocus.app.ProjectFocusApp
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** The original layouts are scoped to their own source theme in the manifest. */
abstract class SourceAuthActivity : AppCompatActivity() {
    private var agreementDialog: AlertDialog? = null
    private var promoImageBinding: AutoCloseable? = null
    private val sourceWindow by lazy { SourceAuthWindow(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        sourceWindow.beforeCreate()
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        sourceWindow.onResume()
    }

    protected fun bindChrome() {
        sourceWindow.bindContent()
        promoImageBinding?.close()
        promoImageBinding = (application as ProjectFocusApp).authPromoImages.bind(findViewById(R.id.pf_auth_promo_image))
        findViewById<View>(R.id.pf_auth_btn_back).setOnClickListener { finish() }
        val checkbox = findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)
        checkbox.setOnClickListener { checkbox.toggle() }
        findViewById<TextView>(R.id.pf_auth_tv_agreement).apply {
            text = agreementText(getString(R.string.pf_auth_agreement_full_text_fmt,
                getString(R.string.pf_auth_terms_label), getString(R.string.pf_auth_privacy_label)))
            movementMethod = LinkMovementMethod.getInstance()
        }
    }

    protected fun withAgreement(action: Runnable) {
        val checkbox = findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)
        if (checkbox.isChecked()) { action.run(); return }
        if (agreementDialog?.isShowing == true) return
        agreementDialog = AlertDialog.Builder(this)
            .setTitle(R.string.pf_auth_agreement_required_title)
            .setMessage(agreementText(getString(R.string.pf_auth_agreement_required_msg)))
            .setNegativeButton(R.string.pf_auth_common_cancel) { dialog, _ -> dialog.dismiss() }
            .setPositiveButton(R.string.pf_auth_privacy_dialog_agree) { dialog, _ ->
                dialog.dismiss()
                checkbox.setChecked(true)
                // Re-read inputs in the same runnable after confirmation.
                action.run()
            }.create().also { dialog ->
                dialog.setOnDismissListener { if (agreementDialog === dialog) agreementDialog = null }
                dialog.show()
                dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
            }
    }

    private fun agreementText(value: String): SpannableString = SpannableString(value).apply {
        listOf(R.string.pf_auth_terms_label to "terms", R.string.pf_auth_privacy_label to "privacy").forEach { (label, destination) ->
            val text = getString(label)
            val start = value.indexOf(text)
            if (start >= 0) setSpan(object : ClickableSpan() {
                override fun onClick(widget: View) { openCapability(destination) }
            }, start, start + text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    @Suppress("UNUSED_PARAMETER") fun openRegister(view: View) {
        startActivity(Intent(this, AuthRegisterActivity::class.java))
    }
    // Exact source reset entry: explicit Activity, no extras and no login-result contract.
    @Suppress("UNUSED_PARAMETER") fun openForgotPassword(view: View) {
        startActivity(Intent(this, AuthForgotPasswordActivity::class.java))
    }
    protected fun openCapability(name: String) { startActivity(Intent(this, AuthCapabilityActivity::class.java).putExtra("capability", name)) }

    override fun onDestroy() {
        promoImageBinding?.close(); promoImageBinding = null
        agreementDialog?.dismiss(); agreementDialog = null
        super.onDestroy()
    }
}

@AndroidEntryPoint
class AuthLoginActivity : SourceAuthActivity() {
    private val emailLogin = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data?.getBooleanExtra(LOGIN_SUCCESS, false) == true) {
            setResult(Activity.RESULT_OK, Intent().putExtra(LOGIN_SUCCESS, true)); finish()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pf_auth_login)
        bindChrome()
        findViewById<View>(R.id.pf_auth_btn_login).setOnClickListener {
            emailLogin.launch(Intent(this, AuthEmailLoginActivity::class.java))
        }
        findViewById<View>(R.id.pf_auth_btn_wechat_login).setOnClickListener {
            withAgreement(Runnable { openCapability("wechat") })
        }
    }
    companion object { const val LOGIN_SUCCESS = "login_success" }
}

@AndroidEntryPoint
class AuthEmailLoginActivity : SourceAuthActivity() {
    private val model by lazy { ViewModelProvider(this)[EmailLoginViewModel::class.java] }
    private var shownError: EmailLoginError? = null
    private var returned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.pf_auth_email_login)
        bindChrome()
        findViewById<View>(R.id.pf_auth_btn_login).setOnClickListener {
            withAgreement(Runnable {
                model.submit(findViewById<SourceAuthInputView>(R.id.pf_auth_et_login_email).getText().toString(),
                    findViewById<SourceAuthInputView>(R.id.pf_auth_et_login_password).getText().toString())
            })
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    findViewById<View>(R.id.pf_auth_btn_login).isEnabled = !state.busy && !state.loggedIn
                    if (state.error == null) shownError = null
                    else if (shownError != state.error) {
                        shownError = state.error
                        Toast.makeText(this@AuthEmailLoginActivity, when (state.error) {
                            EmailLoginError.EMAIL_REQUIRED -> R.string.pf_auth_email_required_title
                            EmailLoginError.PASSWORD_REQUIRED -> R.string.pf_auth_password_required_title
                            EmailLoginError.REJECTED -> R.string.pf_auth_login_failed_title
                            EmailLoginError.NETWORK -> R.string.pf_auth_network_error_title
                            EmailLoginError.STORAGE -> R.string.pf_auth_common_unknown_error
                        }, Toast.LENGTH_SHORT).show()
                    }
                    if (state.loggedIn && !returned) {
                        returned = true
                        Toast.makeText(this@AuthEmailLoginActivity, R.string.pf_auth_login_success_title, Toast.LENGTH_SHORT).show()
                        setResult(Activity.RESULT_OK, Intent().putExtra(AuthLoginActivity.LOGIN_SUCCESS, true))
                        finish()
                    }
                }
            }
        }
    }
}

/** Own integration boundary page, not a recreation claim for the original pages. */
class AuthCapabilityActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val capability = intent.getStringExtra("capability")
        val message = when (capability) {
            "wechat" -> R.string.auth_own_wechat_unavailable
            "register" -> R.string.auth_own_register_unavailable
            "reset" -> R.string.auth_own_reset_unavailable
            "terms", "privacy" -> R.string.auth_own_legal_unavailable
            else -> R.string.auth_own_subscription_unavailable
        }
        AlertDialog.Builder(this).setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
    }
}
