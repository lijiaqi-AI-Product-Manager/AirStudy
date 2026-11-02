package com.projectfocus.app.auth

import android.content.Context
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import com.projectfocus.core.network.di.TokenStore
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import androidx.test.core.app.ApplicationProvider
import java.nio.file.Files
import java.io.File
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer

/** Real Activities/Hilt and clicks, stopping before nonempty credential submission. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
class AuthActivityNavigationTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    private val activities = mutableListOf<ActivityController<out SourceAuthActivity>>()
    private lateinit var app: ProjectFocusApp
    private lateinit var promoDirectory: File
    private lateinit var promoServer: MockWebServer
    private lateinit var promoClient: OkHttpClient
    private lateinit var previousPromoImages: AuthPromoImageLoader

    @Before fun setup() {
        TokenStore.clear(); ShadowToast.reset()
        app = ApplicationProvider.getApplicationContext()
        previousPromoImages = app.authPromoImages
        promoDirectory = Files.createTempDirectory(app.cacheDir.toPath(), "auth-navigation-promo-").toFile()
        promoClient = OkHttpClient()
        promoServer = MockWebServer().apply {
            start()
            enqueue(MockResponse.Builder().code(503).body("unavailable").build())
        }
        // Navigation/validation uses the real loader with an owned local peer;
        // never send automatic image requests to the emulator/default API host.
        app.authPromoImages = AuthPromoImageLoader(app, promoClient, promoServer.url("/auth/promo-image").toString(),
            cacheDirectory = promoDirectory)
    }
    @After fun cleanup() {
        activities.reversed().forEach { it.pause().stop().destroy() }
        activities.clear(); TokenStore.clear()
        app.authPromoImages = previousPromoImages
        promoServer.close()
        promoClient.connectionPool.evictAll()
        promoClient.dispatcher.executorService.shutdownNow()
        check(promoDirectory.canonicalFile.parentFile == app.cacheDir.canonicalFile)
        promoDirectory.walkBottomUp().forEach { it.delete() }
    }

    @Test fun loginHubEmailButtonOpensTheActualPasswordActivity() {
        val controller = Robolectric.buildActivity(AuthLoginActivity::class.java).setup()
        activities += controller
        val activity = controller.get()
        activity.findViewById<android.view.View>(R.id.pf_auth_btn_login).performClick()
        val intent = shadowOf(activity).nextStartedActivity
        assertThat(intent.component?.className).isEqualTo(AuthEmailLoginActivity::class.java.name)
        assertThat(TokenStore.peek()).isNull()
    }

    @Test fun uncheckedLoginAsksAgreementThenReadsCurrentFieldsAfterAgree() {
        val activity = emailActivity()
        val checkbox = activity.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement)
        val email = activity.findViewById<SourceAuthInputView>(R.id.pf_auth_et_login_email)
        email.getText().append("test@example.invalid")
        activity.findViewById<android.view.View>(R.id.pf_auth_btn_login).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        assertThat(dialog.isShowing).isTrue()
        assertThat(checkbox.isChecked()).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
        // The action is not an immutable snapshot captured at the first click.
        email.getText().clear()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(dialog.isShowing).isFalse()
        assertThat(checkbox.isChecked()).isTrue()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(activity.getString(R.string.pf_auth_email_required_title))
        assertNoSavedAccount(activity)
        assertThat(activity.isFinishing).isFalse()
    }

    @Test fun agreementCancelLeavesCheckboxAndAuthenticationUntouched() {
        val activity = emailActivity()
        activity.findViewById<android.view.View>(R.id.pf_auth_btn_login).performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(dialog.isShowing).isFalse()
        assertThat(activity.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement).isChecked()).isFalse()
        assertThat(ShadowToast.getTextOfLatestToast()).isNull()
        assertNoSavedAccount(activity)
    }

    @Test fun checkedAgreementRunsPasswordValidationWithoutAnotherDialog() {
        val activity = emailActivity()
        activity.findViewById<SourceAuthCheckboxView>(R.id.pf_auth_cb_agreement).setChecked(true)
        activity.findViewById<SourceAuthInputView>(R.id.pf_auth_et_login_email).getText().append("test@example.invalid")
        activity.findViewById<android.view.View>(R.id.pf_auth_btn_login).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertThat(ShadowToast.getTextOfLatestToast()).isEqualTo(activity.getString(R.string.pf_auth_password_required_title))
        assertThat(ShadowDialog.getLatestDialog()?.isShowing ?: false).isFalse()
        assertNoSavedAccount(activity)
    }

    private fun emailActivity(): AuthEmailLoginActivity {
        val controller = Robolectric.buildActivity(AuthEmailLoginActivity::class.java).setup()
        activities += controller
        return controller.get().also {
            it.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    private fun assertNoSavedAccount(context: Context) {
        assertThat(TokenStore.peek()).isNull()
        assertThat(context.getSharedPreferences(EmailAccountSessionStore.FILE_NAME, Context.MODE_PRIVATE).all).isEmpty()
    }
}
