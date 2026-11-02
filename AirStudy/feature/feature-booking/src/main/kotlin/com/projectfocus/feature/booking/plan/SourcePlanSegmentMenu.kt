package com.projectfocus.feature.booking.plan

import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.projectfocus.feature.booking.R
import kotlin.math.roundToInt
import com.projectfocus.core.ui.R as CoreUiR


internal class SourcePlanSegmentMenu(
    private val anchor: View,
    editable: Boolean,
    removable: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    batchDeleteCanceled: Boolean = false,
    onBatchDelete: () -> Unit = {},
) {
    private val context = anchor.context
    private val night = context.resources.configuration.uiMode and
        Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val content = LinearLayout(context).apply {
        id = R.id.pf_tp_segment_menu
        orientation = LinearLayout.VERTICAL
        setPadding(dp(5f), dp(5f), dp(5f), dp(5f))
        background = GradientDrawable().apply {
            setColor(if (night) context.getColor(R.color.pf_tp_segment_menu_bg_night) else Color.WHITE)
            cornerRadius = dp(10f).toFloat()
            setStroke(dp(1f), context.getColor(if (night) R.color.pf_tp_segment_menu_stroke_night
                else R.color.pf_tp_segment_menu_stroke_day))
        }
    }
    val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        isOutsideTouchable = true
    }

    init {
        addRow(R.id.pf_tp_segment_menu_edit, R.string.pf_tp_segment_context_edit, editable, false, onEdit)
        addRow(R.id.pf_tp_segment_menu_delete, R.string.pf_tp_segment_context_delete, removable, true, onDelete)
        if (batchDeleteCanceled) addRow(R.id.pf_tp_segment_menu_batch_delete,
            R.string.pf_tp_segment_context_batch_delete_canceled, true, true, onBatchDelete)
    }

    fun show(rawX: Float, rawY: Float) {
        val position = IntArray(2)
        anchor.getLocationOnScreen(position)
        val x = if (rawX > 0f) rawX.toInt() else position[0] + anchor.width / 2
        val y = (if (rawY > 0f) rawY.toInt() else position[1] + anchor.height / 2) + dp(4f)
        popup.showAtLocation(anchor, Gravity.TOP or Gravity.START, x, y)
        val popupPosition = IntArray(2)
        content.getLocationOnScreen(popupPosition)
        val width = content.width.toFloat().coerceAtLeast(1f)
        val height = content.height.toFloat().coerceAtLeast(1f)
        content.pivotX = (if (rawX > 0f) rawX - popupPosition[0] else content.width * .5f).coerceIn(0f, width)
        content.pivotY = (if (rawY > 0f) rawY - popupPosition[1] else content.height * .5f).coerceIn(0f, height)
        content.alpha = 0f
        content.scaleX = .86f
        content.scaleY = .86f
        content.translationY = -dp(10f).toFloat()
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).translationY(0f)
            .setDuration(260L).setInterpolator(OvershootInterpolator(.7f)).start()
    }

    private fun addRow(viewId: Int, label: Int, allowed: Boolean, destructive: Boolean, action: () -> Unit) {
        val row = LinearLayout(context).apply {
            id = viewId
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(5f), dp(8f), dp(5f))
            isEnabled = allowed
            alpha = if (allowed) 1f else .75f
        }
        row.addView(TextView(context).apply {
            setText(label)
            textSize = 12f
            typeface = ResourcesCompat.getFont(context, CoreUiR.font.pf_apk2_harmonyos_sans_sc_medium)
            val color = when {
                !allowed -> context.getColor(if (night) R.color.pf_tp_segment_menu_disabled_night
                    else R.color.pf_tp_segment_menu_disabled_day)
                destructive -> context.getColor(R.color.pf_tp_segment_menu_danger)
                night -> Color.WHITE
                else -> context.getColor(R.color.pf_tp_segment_menu_text_day)
            }
            setTextColor(color)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        row.setOnClickListener {
            if (row.isEnabled && popup.isShowing) {
                popup.dismiss()
                action()
            }
        }
        content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun dp(value: Float) = (value * context.resources.displayMetrics.density).roundToInt()
}
