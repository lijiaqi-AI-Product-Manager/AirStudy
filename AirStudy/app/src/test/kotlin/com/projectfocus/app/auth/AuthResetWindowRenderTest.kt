package com.projectfocus.app.auth

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.ProjectFocusApp
import com.projectfocus.app.R
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Native own windows. No original screenshot, SSIM, hardware/IME or live email claim. */
@RunWith(RobolectricTestRunner::class)
@Config(application = ProjectFocusApp::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class AuthResetWindowRenderTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()

    @Test fun actualDayAndNightEmptyAndReadyWindowsFollowMapStyle() {
        val app = ApplicationProvider.getApplicationContext<ProjectFocusApp>()
        runBlocking(Dispatchers.IO) { withTimeout(15_000) { app.awaitInitialAirportSeed() } }
        shadowOf(Looper.getMainLooper()).idle()
        val preferences = app.getSharedPreferences("projectfocus_home", Context.MODE_PRIVATE)
        val previousStyle = preferences.all["map_style"]
        val previousNight = AppCompatDelegate.getDefaultNightMode()
        val output = File("build/reports/r168-auth-reset-windows").apply { check(isDirectory || mkdirs()) }
        val records = JSONArray()
        var controller: ActivityController<AuthForgotPasswordActivity>? = null
        try {
            for (night in listOf(false, true)) {
                // Source policy uses selected map style; test opposite global mode.
                preferences.edit().putInt("map_style", if (night) 2 else 4).commit()
                AppCompatDelegate.setDefaultNightMode(if (night) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES)
                controller = Robolectric.buildActivity(AuthForgotPasswordActivity::class.java).setup().visible()
                val activity = checkNotNull(controller).get()
                assertThat(activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    .isEqualTo(if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
                assertThat(activity.findViewById<View>(R.id.pf_auth_promo_image)).isNull()
                assertThat(activity.findViewById<View>(R.id.pf_auth_cb_agreement)).isNull()
                for (ready in listOf(false, true)) {
                    if (ready) {
                        activity.findViewById<EditText>(R.id.pf_auth_et_email).setText("traveler@example.test")
                        activity.findViewById<EditText>(R.id.pf_auth_et_password).setText("study-password123")
                        activity.findViewById<EditText>(R.id.pf_auth_et_code).setText("123456")
                    }
                    shadowOf(Looper.getMainLooper()).idleFor(250, TimeUnit.MILLISECONDS)
                    val decor = activity.window.decorView as ViewGroup
                    decor.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(844, View.MeasureSpec.EXACTLY))
                    decor.layout(0, 0, 390, 844)
                    val button = activity.findViewById<TextView>(R.id.pf_auth_btn_register)
                    val title = activity.findViewById<TextView>(R.id.pf_auth_tv_title)
                    assertThat(button.isEnabled).isEqualTo(ready)
                    assertThat(title.text.toString()).isEqualTo(activity.getString(if (ready)
                        R.string.pf_auth_forgot_password_prompt_ready_title else R.string.pf_auth_forgot_password_prompt_enter_email_title))
                    assertThat(title.textSize).isEqualTo(25f)
                    // Original XML has60dp height and50dp corner radius.
                    assertThat(button.height).isEqualTo(60)
                    val image = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
                    try {
                        val canvas = Canvas(image)
                        decor.background?.let { background ->
                            val bounds = Rect(background.bounds)
                            try {
                                background.setBounds(0, 0, decor.width, decor.height)
                                background.draw(canvas)
                            } finally { background.bounds = bounds }
                        }
                        decor.draw(canvas)
                        assertThat(Color.alpha(image.getPixel(0, 0))).isEqualTo(255)
                        val bounds = Rect(0, 0, button.width, button.height)
                        decor.offsetDescendantRectToMyCoords(button, bounds)
                        assertThat(bounds.top).isAtLeast(0)
                        assertThat(bounds.bottom).isAtMost(image.height)
                        val background = image.getPixel(bounds.left + 20, bounds.centerY())
                        var glyphPixels = 0
                        for (y in bounds.centerY() - 10 until bounds.centerY() + 10) {
                            for (x in bounds.centerX() - 80 until bounds.centerX() + 80) {
                                val pixel = image.getPixel(x, y)
                                if (kotlin.math.abs(Color.red(pixel) - Color.red(background)) > 24 ||
                                    kotlin.math.abs(Color.green(pixel) - Color.green(background)) > 24 ||
                                    kotlin.math.abs(Color.blue(pixel) - Color.blue(background)) > 24) glyphPixels++
                            }
                        }
                        assertThat(glyphPixels).isGreaterThan(8)
                        val name = "reset-${if (night) "night" else "day"}-${if (ready) "ready" else "empty"}-390.png"
                        val file = File(output, name)
                        file.outputStream().use { assertThat(image.compress(Bitmap.CompressFormat.PNG, 100, it)).isTrue() }
                        records.put(JSONObject().put("file", name).put("night", night).put("ready", ready)
                            .put("width", image.width).put("height", image.height).put("buttonGlyphPixels", glyphPixels)
                            .put("activity", activity.javaClass.name).put("windowAttached", decor.isAttachedToWindow)
                            .put("pngSha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                                .joinToString("") { "%02x".format(it.toInt() and 255) }))
                    } finally { image.recycle() }
                }
                checkNotNull(controller).pause().stop().destroy()
                controller = null
            }
            File(output, "windows.json").writeText(JSONObject()
                .put("kind", "actual_own_reset_windows_native_not_original_fidelity_or_device_acceptance")
                .put("sourceMapStyles", "VINTAGE/day and NIGHT/night, opposite global mode")
                .put("records", records).toString(2))
        } finally {
            controller?.pause()?.stop()?.destroy()
            preferences.edit().apply {
                if (previousStyle is Int) putInt("map_style", previousStyle) else remove("map_style")
            }.commit()
            restoreAuthTestNightMode(previousNight)
        }
    }
}
