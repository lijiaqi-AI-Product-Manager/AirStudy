package com.projectfocus.app.auth

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.LocaleList
import android.text.Spanned
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.button.MaterialButton
import com.projectfocus.feature.booking.plan.TakeoffPlanCallbacks
import com.projectfocus.feature.booking.plan.TakeoffPlanPanel
import com.projectfocus.feature.booking.plan.TakeoffPlanUiState
import com.projectfocus.feature.booking.plan.TakeoffPlanView
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.projectfocus.feature.booking.R as BookingR
import com.projectfocus.core.ui.R as CoreUiR

/** Real render(state) integration. The280dp standalone minimum is an own host adapter. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w390dp-h844dp-notnight-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourcePlanGateLayoutTest {
    private fun context(night: Boolean): Context {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val config = Configuration(app.resources.configuration).apply {
            setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE))
            screenWidthDp = 390; screenHeightDp = 844; smallestScreenWidthDp = 390
            densityDpi = 160; fontScale = 1f
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(config), CoreUiR.style.Theme_Pf)
    }

    private fun nodes(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(nodes(view.getChildAt(i)))
    }

    private fun gate(view: View): LinearLayout = nodes(view).filterIsInstance<LinearLayout>()
        .single { it.javaClass.name == "com.projectfocus.feature.booking.plan.PlanEntitlementGateView" }

    private fun layout(root: View, height: Int = 844) {
        nodes(root).forEach { it.clearAnimation(); if (it is ViewGroup) it.layoutAnimation = null }
        repeat(2) {
            root.measure(View.MeasureSpec.makeMeasureSpec(390, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 390, height)
        }
    }

    private fun withConfigNight(block: () -> Unit) {
        val before = AppCompatDelegate.getDefaultNightMode()
        try {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            block()
        } finally { restoreAuthTestNightMode(before) }
    }

    @Test fun gateNativeFrames() = withConfigNight {
        val output = File(System.getProperty("pf.auth.previewDir", "build/reports/r165-auth-ui")).apply {
            check(isDirectory || mkdirs())
        }
        val receipts = JSONArray()
        for (night in listOf(false, true)) {
            val context = context(night)
            val component = TakeoffPlanView(context)
            val expanded = component.findViewById<View>(BookingR.id.pf_tp_expanded_panel)
            val originalMinimum = expanded.minimumHeight
            component.render(TakeoffPlanUiState(panel = TakeoffPlanPanel.EXPANDED, entitlementGateVisible = true))
            // Fixed neutral stand-in, not the unknown original map parent/background.
            val host = FrameLayout(context).apply {
                setBackgroundColor(Color.rgb(128, 128, 128))
                addView(component, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
            layout(host)
            val overlay = gate(component)
            assertEquals(LinearLayout.VERTICAL, overlay.orientation)
            assertEquals(Gravity.CENTER, overlay.gravity)
            assertTrue(overlay.isClickable); assertTrue(overlay.isFocusable)
            assertEquals(listOf(16, 16, 16, 16), listOf(overlay.paddingLeft, overlay.paddingTop, overlay.paddingRight, overlay.paddingBottom))
            val params = overlay.layoutParams as FrameLayout.LayoutParams
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.height)
            assertEquals(44, params.topMargin)
            assertEquals(maxOf(originalMinimum, 280), expanded.minimumHeight) // own standalone sizing only
            assertEquals(if (night) 0xA0000000.toInt() else 0x8CFFFFFF.toInt(), (overlay.background as ColorDrawable).color)
            val message = overlay.getChildAt(0) as TextView
            val action = overlay.getChildAt(1) as MaterialButton
            val ink = if (night) Color.WHITE else Color.BLACK
            assertEquals(context.getString(BookingR.string.pf_pg_premium_flight_plan_gate_message), message.text.toString())
            assertTrue(message.text.contains('\n')); assertFalse(message.text.contains('\r'))
            assertEquals(2, message.lineCount)
            if (message.text is Spanned) assertEquals(0, (message.text as Spanned).getSpans(0, message.length(), Any::class.java).size)
            assertEquals(14f, message.textSize, 0.01f); assertEquals(ink, message.currentTextColor)
            assertEquals(12, (message.layoutParams as LinearLayout.LayoutParams).bottomMargin)
            assertEquals(12f, action.textSize, 0.01f); assertEquals(ink, action.currentTextColor)
            assertEquals(context.getString(BookingR.string.pf_pg_rank_login_action), action.text.toString())
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, action.layoutParams.width)
            assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, action.layoutParams.height)
            assertEquals(0, action.insetTop); assertEquals(0, action.insetBottom)
            assertEquals(0, action.minHeight); assertEquals(0, action.minimumHeight)
            assertEquals(if (night) 0x1AFFFFFF else 0xFFE0E0E0.toInt(), action.backgroundTintList!!.defaultColor)
            val image = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(image))
            val file = File(output, "plan-denial-${if (night) "night" else "day"}-390.png")
            file.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            receipts.put(JSONObject().put("file", file.name).put("width", 390).put("height", 844)
                .put("sha256", sha(file.readBytes())).put("messageLineCount", message.lineCount)
                .put("standaloneMinHeightDpOwnAdapter", 280))
            image.recycle()
        }
        File(output, "gate-render-receipt.json").writeText(JSONObject().put("renderer", "Robolectric NativeGraphics SDK35")
            .put("frames", receipts).put("originalMainMapParentAcceptance", false).put("originalRuntimeOrPixelAcceptance", false).toString(2))
        File(output, "gate-README.txt").writeText(
            "Real TakeoffPlanView.render expanded denial day/night; fixed gray standalone host.\n" +
                "Minimum height280dp is own wrap-content host adaptation after hiding scroll/footer.\n" +
                "No original Main map-parent/runtime/pixel/auth/navigation acceptance. Source message is plain text with one LF/no style spans.\n",
        )
    }

    @Test fun gateUpdatesAndDismiss() = withConfigNight {
        val context = context(false)
        val view = TakeoffPlanView(context)
        var accountActions = 0
        val planChanges = mutableListOf<Boolean>()
        view.callbacks = TakeoffPlanCallbacks(onAccountAction = { accountActions++ }, onPlanEnabled = { planChanges += it })
        val base = TakeoffPlanUiState(panel = TakeoffPlanPanel.EXPANDED, planEnabled = false)
        view.render(base)
        val expanded = view.findViewById<View>(BookingR.id.pf_tp_expanded_panel)
        val scroll = view.findViewById<View>(BookingR.id.pf_tp_content_scroll)
        val footer = view.findViewById<View>(BookingR.id.pf_tp_footer_container)
        val beforeMinimum = expanded.minimumHeight
        view.render(base.copy(entitlementGateVisible = true))
        layout(view)
        assertEquals(View.GONE, scroll.visibility); assertEquals(View.GONE, footer.visibility)
        val originalGate = gate(view)
        val action = originalGate.getChildAt(1) as MaterialButton
        assertEquals(context.getString(BookingR.string.pf_pg_rank_login_action), action.text.toString())
        assertTrue(action.performClick()); assertEquals(1, accountActions)
        view.render(base.copy(entitlementGateVisible = true, entitlementGateAuthenticated = true))
        assertSame(originalGate, gate(view))
        assertEquals(context.getString(BookingR.string.pf_pg_flight_settings_white_noise_gate_subscribe), action.text.toString())
        assertTrue(action.performClick()); assertEquals(2, accountActions)
        assertTrue(planChanges.isEmpty())
        view.render(base.copy(entitlementGateVisible = false, entitlementGateAuthenticated = true))
        layout(view)
        assertFalse(nodes(view).any { it.javaClass.name.endsWith(".PlanEntitlementGateView") })
        assertNull(originalGate.parent)
        assertEquals(View.VISIBLE, scroll.visibility); assertEquals(View.VISIBLE, footer.visibility)
        assertEquals(beforeMinimum, expanded.minimumHeight)
        assertFalse(view.findViewById<SwitchCompat>(BookingR.id.pf_tp_switch).isChecked)
        assertTrue(planChanges.isEmpty()) // Source watcher dismiss does not reopen On.
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
