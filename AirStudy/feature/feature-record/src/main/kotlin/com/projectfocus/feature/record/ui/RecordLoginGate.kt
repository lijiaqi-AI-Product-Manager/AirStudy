package com.projectfocus.feature.record.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import com.projectfocus.feature.record.R
import com.projectfocus.core.ui.R as UiR

class RecordLoginGate(
    private val context: Context,
    private val content: ViewGroup,
    private val visibilityChanged: (Boolean) -> Unit,
) {
    private var entryView: FrameLayout? = null
    private var background: Bitmap? = null
    val isOpen get() = entryView != null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    fun open(canShow: () -> Boolean, onLogin: () -> Unit) {
        close()
        if (!canShow() || content.width <= 0 || content.height <= 0) return
        val bitmap = Bitmap.createBitmap(content.width, content.height, Bitmap.Config.ARGB_8888)
        try { content.draw(Canvas(bitmap)) } catch (error: Exception) { bitmap.recycle(); throw error }
        background = bitmap
        val frame = object : FrameLayout(context) {
            override fun dispatchDraw(canvas: Canvas) {
                if (!canShow()) { visibility = View.INVISIBLE; post { if (entryView === this) close() }; return }
                super.dispatchDraw(canvas)
            }
        }.apply {
            id = R.id.pf_record_login_gate
            isClickable = true
            isFocusableInTouchMode = true
        }
        val image = ImageView(context).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            if (Build.VERSION.SDK_INT >= 31)
                setRenderEffect(RenderEffect.createBlurEffect(30f, 30f, Shader.TileMode.MIRROR))
            setColorFilter(Color.argb(160, 0, 0, 0))
            setOnClickListener { if (entryView === frame) close() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        column.addView(TextView(context).apply {
            setText(R.string.pf_record_login_title); textSize = 18f; setTextColor(Color.WHITE)
            typeface = ResourcesCompat.getFont(context, UiR.font.harmonyos_sans_sc_bold)
        })
        column.addView(MaterialButton(context).apply {
            id = R.id.pf_record_login_button
            setText(R.string.pf_record_login_button); textSize = 14f
            setTextColor(Color.BLACK); backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            cornerRadius = dp(20)
            setOnClickListener {
                if (entryView === frame) {
                    if (canShow()) { close(); onLogin() } else close()
                }
            }
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(12) })
        frame.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        entryView = frame
        content.addView(frame, ViewGroup.LayoutParams(-1, -1))
        frame.alpha = 0f
        frame.animate().alpha(1f).setDuration(180).start()
        frame.requestFocus()
        visibilityChanged(true)
    }

    fun close() {
        val previous = entryView
        entryView = null
        previous?.animate()?.cancel()
        previous?.let { content.removeView(it) }
        background?.recycle()
        background = null
        if (previous != null) visibilityChanged(false)
    }
}
