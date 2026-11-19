package com.projectfocus.core.ui.scene

import android.app.Dialog
import android.content.Context
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
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import com.google.android.flexbox.FlexboxLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.projectfocus.core.ui.R

/** The caller owns catalog identity; the picker owns only source card presentation. */
data class ScenePickerOption(
    val id: String,
    val label: String,
    @DrawableRes val iconRes: Int,
    @ColorInt val color: Int,
)


class AnchoredScenePickerDialog(
    context: Context,
    private val anchor: View?,
    private val seatCaption: String?,
    options: List<ScenePickerOption>,
    private val onSelected: (ScenePickerOption) -> Unit,
    private val selectedId: String? = null,
    private val onAdd: ((onChanged: () -> Unit) -> Unit)? = null,
    private val onRemove: ((ScenePickerOption, () -> Unit) -> Unit)? = null,
    private val reloadOptions: (() -> List<ScenePickerOption>)? = null,
) : Dialog(context) {
    private val main = Handler(Looper.getMainLooper())
    private var currentOptions = options
    private var delivered = false
    private var deleteMode = false
    private var mutationPending = false
    private var closed = false
    private var confirmation: AlertDialog? = null
    private val dark: Boolean
        get() = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setCancelable(true)
        setCanceledOnTouchOutside(true)
        setContentView(R.layout.pf_scene_picker_card)
        findViewById<View>(R.id.pf_scene_card_root).background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (dark) 0xFF121212.toInt() else 0xFFFAFAFA.toInt())
            setStroke(dp(1), if (dark) 0xFF2A2A2A.toInt() else ColorUtils.setAlphaComponent(Color.BLACK, 36))
        }
        findViewById<TextView>(R.id.pf_scene_title).setTextColor(if (dark) Color.WHITE else Color.BLACK)
        findViewById<TextView>(R.id.pf_scene_seat).apply {
            text = seatCaption
            visibility = if (seatCaption == null) View.GONE else View.VISIBLE
            setTextColor(if (dark) 0xFF9E9E9E.toInt() else 0xFF5F6368.toInt())
        }
        findViewById<ImageView>(R.id.pf_scene_edit).apply {
            visibility = if (onRemove == null) View.GONE else View.VISIBLE
            setColorFilter(0xFF8E8E93.toInt())
            setOnClickListener {
                if (!mutationPending) { deleteMode = !deleteMode; renderOptions() }
            }
        }
        renderOptions()
        positionWindow()
    }

    private fun renderOptions() {
        val tags = findViewById<FlexboxLayout>(R.id.pf_scene_tags)
        tags.removeAllViews()
        if (currentOptions.isEmpty()) {
            tags.addView(TextView(context).apply {
                setText(R.string.pf_scene_picker_empty)
                setTextColor(if (dark) 0xFF9E9E9E.toInt() else 0xFF5F6368.toInt())
            })
        }
        currentOptions.forEach { option ->
            tags.addView(MaterialButton(context, null).apply {
                text = option.label
                tag = option.id
                setIconResource(if (deleteMode) R.drawable.pf_scene_ic_close else option.iconRes)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = dp(8)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(70).toFloat()
                    setColor(ColorUtils.setAlphaComponent(if (deleteMode) Color.RED else option.color, if (deleteMode) 30 else 50))
                    setStroke(dp(1), if (deleteMode) Color.RED else if (dark) 0x40FFFFFF else ColorUtils.setAlphaComponent(Color.BLACK, 50))
                }
                setTextColor(option.color)
                iconTint = ColorStateList.valueOf(if (deleteMode) Color.RED else option.color)
                rippleColor = ColorStateList.valueOf(ColorUtils.setAlphaComponent(option.color, 100))
                elevation = 0f
                isSelected = option.id == selectedId
                layoutParams = FlexboxLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(6), dp(6)) }
                setOnClickListener {
                    if (delivered || mutationPending) return@setOnClickListener
                    if (deleteMode) confirmRemoval(option) else {
                        delivered = true
                        onSelected(option)
                        dismiss()
                    }
                }
            })
        }
        if (onAdd != null) {
            tags.addView(MaterialButton(context, null).apply {
                id = R.id.pf_scene_add
                text = ""
                setIconResource(R.drawable.pf_scene_ic_add)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                iconPadding = 0
                iconTint = ColorStateList.valueOf(if (dark) Color.WHITE else Color.BLACK)
                backgroundTintList = null
                background = GradientDrawable().apply {
                    cornerRadius = dp(70).toFloat()
                    setColor(if (dark) 0x22FFFFFF else Color.WHITE)
                    setStroke(dp(1), if (dark) 0x40FFFFFF else ColorUtils.setAlphaComponent(Color.BLACK, 60))
                }
                setTextColor(if (dark) Color.WHITE else Color.BLACK)
                layoutParams = FlexboxLayout.LayoutParams(dp(60), -2).apply { setMargins(0, 0, dp(6), dp(6)) }
                setOnClickListener { if (!mutationPending && !closed) onAdd.invoke(::catalogChanged) }
            })
        }
    }

    private fun confirmRemoval(option: ScenePickerOption) {
        val remove = onRemove ?: return
        if (confirmation?.isShowing == true) return
        confirmation = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.pf_scene_dialog_delete_title)
            .setMessage(context.getString(R.string.pf_scene_dialog_delete_message, option.label))
            .setNegativeButton(R.string.pf_scene_cancel, null)
            .setPositiveButton(R.string.pf_scene_dialog_delete_confirm) { _, _ ->
                if (!closed && isShowing) {
                    mutationPending = true
                    try { remove(option, ::catalogChanged) }
                    catch (error: Exception) { mutationPending = false; throw error }
                }
            }
            .create()
        confirmation?.show()
    }

    private fun catalogChanged() {
        main.post {
            if (closed || !isShowing) return@post
            mutationPending = false
            currentOptions = reloadOptions?.invoke() ?: currentOptions
            renderOptions()
        }
    }

    private fun closeChildren() {
        if (closed) return
        closed = true
        confirmation?.dismiss()
        confirmation = null
    }

    // The Slot owns setOnDismissListener; do not replace it with an internal listener.
    override fun dismiss() { closeChildren(); super.dismiss() }
    override fun cancel() { closeChildren(); super.cancel() }

    private fun positionWindow() {
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                val source = anchor
                if (source == null) gravity = Gravity.CENTER else {
                    val location = IntArray(2)
                    source.getLocationOnScreen(location)
                    val metrics = context.resources.displayMetrics
                    val estimateWidth = dp(290)
                    val estimateHeight = dp(300)
                    val right = location[0] + source.width
                    x = when {
                        right + estimateWidth <= metrics.widthPixels -> right + dp(8)
                        location[0] - estimateWidth >= 0 -> location[0] - estimateWidth - dp(8)
                        else -> (metrics.widthPixels - estimateWidth) / 2
                    }
                    y = location[1]
                    if (y + estimateHeight > metrics.heightPixels) y = metrics.heightPixels - estimateHeight - dp(16)
                    if (y < 0) y = dp(16)
                    gravity = Gravity.TOP or Gravity.LEFT
                }
            }
        }
    }
}
