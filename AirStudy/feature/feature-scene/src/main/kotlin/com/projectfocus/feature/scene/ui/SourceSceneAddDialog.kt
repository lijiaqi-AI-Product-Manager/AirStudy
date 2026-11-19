package com.projectfocus.feature.scene.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import androidx.core.graphics.ColorUtils
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.projectfocus.feature.scene.R
import com.projectfocus.feature.scene.source.SourceSceneCatalog
import com.projectfocus.feature.scene.source.SourceSceneEnableChoices
import com.projectfocus.feature.scene.source.SourceSceneOption
import com.projectfocus.feature.scene.source.SourceSceneRecord
import java.util.concurrent.CancellationException


class SourceSceneAddDialog(
    context: Context,
    private val availableChoices: () -> SourceSceneEnableChoices,
    private val nameChecker: SourceSceneNameChecker,
    private val onSave: (SourceSceneRecord, (SourceSceneSaveResult) -> Unit) -> AutoCloseable?,
    private val onChanged: () -> Unit = {},
) : Dialog(context) {
    private val main = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pending = false
    private var closed = false
    private var lease: AutoCloseable? = null
    private var layoutHost: View? = null
    private val layoutListener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applySourceSizing()
    }
    private var icon = SourceSceneCatalog.builtins.first()
    private val dark: Boolean
        get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(R.layout.pf_source_scene_add_dialog)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setGravity(Gravity.CENTER) }
        applySourceColors()
        showPage(false)
        renderChoices()
        val icons = findViewById<LinearLayout>(R.id.pf_scene_icon_selector)
        SourceSceneCatalog.builtins.forEach { builtin ->
            icons.addView(ImageView(context).apply {
                setImageResource(builtin.iconRes)
                imageTintList = ColorStateList.valueOf(builtin.color)
                contentDescription = context.getString(builtin.labelRes)
                layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { rightMargin = dp(8) }
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { if (!pending) { icon = builtin; renderPreview() } }
            })
        }
        renderPreview()
        findViewById<View>(R.id.pf_scene_go_custom).setOnClickListener { if (!pending) showPage(true) }
        findViewById<View>(R.id.pf_scene_cancel_custom).setOnClickListener {
            if (pending) dismiss() else showPage(false)
        }
        findViewById<View>(R.id.pf_scene_add_custom).setOnClickListener { trigger ->
            // java.lang.String.trim(), as used by the recovered original, preserves NBSP.
            val name = findViewById<EditText>(R.id.pf_scene_custom_name).text.toString().trim { it <= ' ' }
            if (name.isEmpty()) message(R.string.pf_scene_enter_name)
            else checkAndSave(SourceSceneRecord(name, icon.iconKey, icon.color, isCustom = true), trigger)
        }
    }

    override fun onStart() {
        super.onStart()
        applySourceSizing()
        var host: Context? = context
        while (host is ContextWrapper && host !is Activity) {
            val next = host.baseContext
            if (next === host) break
            host = next
        }
        layoutHost = (host as? Activity)?.window?.decorView
        layoutHost?.addOnLayoutChangeListener(layoutListener)
    }

    override fun onStop() {
        layoutHost?.removeOnLayoutChangeListener(layoutListener)
        layoutHost = null
        super.onStop()
    }

    private fun applySourceSizing() {
        val metrics = context.resources.displayMetrics
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val width = minOf(metrics.widthPixels - dp(40), dp(if (landscape) 720 else 600))
        findViewById<SourceSceneMaxHeightScrollView>(R.id.pf_scene_available_scroll)
            .setMaxHeight(maxOf(dp(160), metrics.heightPixels - dp(210)))
        findViewById<SourceSceneMaxHeightScrollView>(R.id.pf_scene_page2_scroll)
            .setMaxHeight(maxOf(dp(240), metrics.heightPixels - dp(96)))
        window?.apply {
            if (attributes.width != width || attributes.height != WindowManager.LayoutParams.WRAP_CONTENT) {
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
            setGravity(Gravity.CENTER)
        }
    }

    private fun renderChoices() {
        val container = findViewById<FlexboxLayout>(R.id.pf_scene_available_options)
        container.removeAllViews()
        val choices = try { availableChoices() } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { message(R.string.pf_scene_load_failed); return }
        choices.builtins.forEach { option -> container.addView(optionButton(option, history = false, alreadyEnabled = false)) }
        if (choices.customHistory.isNotEmpty()) {
            // x4.h.c: plain context TextView, 13sp, inherited typeface; 12dp/6dp vertical margins.
            container.addView(TextView(context).apply {
                setText(R.string.pf_scene_custom_title)
                textSize = 13f
                setTextColor(if (dark) 0x80FFFFFF.toInt() else 0x99000000.toInt())
                layoutParams = FlexboxLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, dp(6)) }
            })
        }
        choices.customHistory.forEach { candidate ->
            container.addView(optionButton(candidate.option, history = true, alreadyEnabled = candidate.alreadyEnabled))
        }
    }

    private fun optionButton(option: SourceSceneOption, history: Boolean, alreadyEnabled: Boolean) =
        MaterialButton(context, null).apply {
            val record = option.record
            text = SourceSceneCatalog.label(context, record)
            tag = option.optionId
            setIconResource(SourceSceneCatalog.icon(record.iconKey))
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            backgroundTintList = null
            background = GradientDrawable().apply {
                cornerRadius = dp(70).toFloat()
                setColor(ColorUtils.setAlphaComponent(record.color, 50))
                setStroke(dp(1), if (dark) 0x40FFFFFF else ColorUtils.setAlphaComponent(Color.BLACK, 50))
            }
            setTextColor(record.color)
            iconTint = ColorStateList.valueOf(record.color)
            rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(record.color, 100))
            alpha = if (alreadyEnabled) .5f else 1f
            layoutParams = FlexboxLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), dp(6)) }
            setOnClickListener { trigger ->
                if (!pending && !alreadyEnabled) {
                    if (history) checkAndSave(record, trigger) else save(record, trigger)
                }
            }
        }

    private fun checkAndSave(record: SourceSceneRecord, trigger: View) {
        if (pending || closed || !isShowing) return
        val trimmed = record.name.trim { it <= ' ' }
        if (trimmed.isEmpty()) return
        val token = begin(trigger)
        try {
            val next = nameChecker.check(trimmed) { result ->
                main.post {
                    if (!active(token)) return@post
                    finish(trigger)
                    if (result == SourceSceneNameCheckResult.ALLOWED) save(record, trigger)
                    else message(R.string.pf_scene_name_check_failed_message, R.string.pf_scene_name_check_failed_title)
                }
            }
            retain(token, next)
        } catch (cancel: CancellationException) { finish(trigger); throw cancel }
        catch (_: Exception) {
            if (active(token)) { finish(trigger); message(R.string.pf_scene_name_check_failed_message, R.string.pf_scene_name_check_failed_title) }
        }
    }

    private fun save(record: SourceSceneRecord, trigger: View) {
        if (pending || closed || !isShowing) return
        val token = begin(trigger)
        try {
            val next = onSave(record) { result ->
                main.post {
                    if (!active(token)) return@post
                    finish(trigger)
                    if (result == SourceSceneSaveResult.SAVED) {
                        onChanged()
                        dismiss()
                    } else message(R.string.pf_scene_save_failed)
                }
            }
            retain(token, next)
        } catch (cancel: CancellationException) { finish(trigger); throw cancel }
        catch (_: Exception) { if (active(token)) { finish(trigger); message(R.string.pf_scene_save_failed) } }
    }

    private fun begin(trigger: View): Int {
        generation++
        pending = true
        trigger.isEnabled = false
        return generation
    }

    private fun active(token: Int) = token == generation && pending && !closed && isShowing

    private fun retain(token: Int, next: AutoCloseable?) {
        if (active(token)) lease = next else next?.close()
    }

    private fun finish(trigger: View) {
        pending = false
        val previous = lease
        lease = null
        previous?.close()
        trigger.isEnabled = true
    }

    private fun showPage(custom: Boolean) {
        findViewById<View>(R.id.pf_scene_page1).visibility = if (custom) View.GONE else View.VISIBLE
        findViewById<View>(R.id.pf_scene_page2_scroll).visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun renderPreview() {
        findViewById<ImageView>(R.id.pf_scene_custom_preview).apply {
            setImageResource(icon.iconRes)
            imageTintList = ColorStateList.valueOf(icon.color)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ColorUtils.setAlphaComponent(icon.color, 50))
                setStroke(dp(1), if (dark) 0x40FFFFFF else ColorUtils.setAlphaComponent(Color.BLACK, 45))
            }
        }
    }

    private fun applySourceColors() {
        val body = if (dark) 0xFF1C1C1E.toInt() else 0xFFFAFAFA.toInt()
        val text = if (dark) Color.WHITE else Color.BLACK
        findViewById<CardView>(R.id.pf_scene_add_card).setCardBackgroundColor(body)
        findViewById<View>(R.id.pf_scene_page1).setBackgroundColor(body)
        findViewById<View>(R.id.pf_scene_page2).setBackgroundColor(body)
        findViewById<TextView>(R.id.pf_scene_page1_title).setTextColor(text)
        findViewById<TextView>(R.id.pf_scene_page2_title).setTextColor(text)
        findViewById<TextView>(R.id.pf_scene_page1_subtitle).setTextColor(if (dark) 0x80FFFFFF.toInt() else 0x99000000.toInt())
        val hint = if (dark) 0xFF8E8E93.toInt() else 0x73000000
        findViewById<EditText>(R.id.pf_scene_custom_name).apply { setTextColor(text); setHintTextColor(hint) }
        findViewById<ImageView>(R.id.pf_scene_custom_input_icon).imageTintList = ColorStateList.valueOf(hint)
        findViewById<View>(R.id.pf_scene_custom_input).background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(if (dark) 0xFF2C2C2E.toInt() else Color.WHITE)
            setStroke(dp(1), if (dark) Color.TRANSPARENT else 0x1F000000)
        }
        fun button(id: Int, fill: Int, foreground: Int, outlined: Boolean) {
            findViewById<MaterialButton>(id).apply {
                backgroundTintList = ColorStateList.valueOf(fill)
                setTextColor(foreground)
                strokeWidth = if (outlined) dp(1) else 0
                strokeColor = ColorStateList.valueOf(0x33000000)
            }
        }
        button(R.id.pf_scene_go_custom, if (dark) 0x33FFFFFF else 0xFFF3F4F6.toInt(), text, !dark)
        button(R.id.pf_scene_cancel_custom, if (dark) 0xFF3A3A3C.toInt() else 0xFFF3F4F6.toInt(), text, !dark)
        button(R.id.pf_scene_add_custom, text, if (dark) Color.BLACK else Color.WHITE, false)
    }

    private fun message(message: Int, title: Int? = null) {
        val copy = if (title == null) context.getString(message) else context.getString(title) + "\n" + context.getString(message)
        Toast.makeText(context, copy, Toast.LENGTH_LONG).show()
    }

    private fun closeWork() {
        if (closed) return
        closed = true
        generation++
        pending = false
        val previous = lease
        lease = null
        previous?.close()
    }

    override fun dismiss() { closeWork(); super.dismiss() }
    override fun cancel() { closeWork(); super.cancel() }
}
