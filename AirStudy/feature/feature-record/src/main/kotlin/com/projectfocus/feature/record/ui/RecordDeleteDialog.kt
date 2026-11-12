package com.projectfocus.feature.record.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import com.projectfocus.feature.record.R
import com.projectfocus.core.ui.R as UiR

class RecordDeleteDialog(
    context: Context,
    canConfirm: () -> Boolean,
    onConfirm: () -> Unit,
) : Dialog(context) {
    private var guard: (() -> Boolean)? = canConfirm
    private var confirm: (() -> Unit)? = onConfirm
    private var pending = false
    private lateinit var primary: TextView
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(context, id)
    private fun active() = guard?.invoke() == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = object : CardView(context) {
            override fun dispatchDraw(canvas: Canvas) {
                if (!active()) {
                    visibility = View.INVISIBLE
                    post { dismiss() }
                    return
                }
                super.dispatchDraw(canvas)
            }
        }.apply {
            id = R.id.pf_record_delete_dialog
            setCardBackgroundColor(color(R.color.pf_record_delete_surface))
            radius = 28f * resources.displayMetrics.density
            cardElevation = 0f
        }
        val column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maximum = minOf(dp(800), (resources.displayMetrics.heightPixels * .9f).toInt())
                val permitted = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maximum
                    else minOf(maximum, MeasureSpec.getSize(heightMeasureSpec))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(permitted, MeasureSpec.AT_MOST))
            }
        }.apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        card.addView(column, ViewGroup.LayoutParams(-1, -2))
        val header = FrameLayout(context)
        header.addView(text(R.string.pf_record_delete_title, 20f, R.color.pf_record_delete_primary, bold = true).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL).apply { marginEnd = dp(40) })
        val close = CardView(context).apply {
            radius = 16f * resources.displayMetrics.density; cardElevation = 0f
            setCardBackgroundColor(color(R.color.pf_record_delete_close_surface))
            contentDescription = context.getString(R.string.pf_record_delete_cancel)
            id = R.id.pf_record_delete_close
            isFocusable = true
            addView(ImageView(context).apply {
                setImageResource(R.drawable.pf_record_delete_close)
                scaleType = ImageView.ScaleType.CENTER
                scaleX = .9f; scaleY = .9f
                setColorFilter(color(R.color.pf_record_delete_button_text))
            }, ViewGroup.LayoutParams(-1, -1))
            setOnClickListener { dismiss() }
        }
        header.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END))
        column.addView(header, LinearLayout.LayoutParams(-1, -2))
        column.addView(space(16))
        val body = text(R.string.pf_record_delete_message, 16f, R.color.pf_record_delete_secondary).apply {
            id = R.id.pf_record_delete_message
            gravity = Gravity.START
            setLineSpacing(0f, 1.4f)
        }
        val scroll = NestedScrollView(context).apply { addView(body, ViewGroup.LayoutParams(-1, -2)) }
        column.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(space(24))
        val tablet = context.resources.configuration.smallestScreenWidthDp >= 600
        val actions = LinearLayout(context).apply { orientation = if (tablet) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        val accent = color(UiR.color.stats_accent)
        val onAccent = if (ColorUtils.calculateContrast(Color.WHITE, accent) >= ColorUtils.calculateContrast(Color.BLACK, accent)) Color.WHITE else Color.BLACK
        primary = button(R.string.pf_record_delete_confirm, accent, onAccent).apply {
            id = R.id.pf_record_delete_confirm
            setOnClickListener {
                if (!active()) { dismiss(); return@setOnClickListener }
                if (!pending) { setPending(true); confirm?.invoke() }
            }
        }
        val cancel = button(R.string.pf_record_delete_cancel, color(R.color.pf_record_delete_button_surface),
            color(R.color.pf_record_delete_button_text)).apply {
            id = R.id.pf_record_delete_cancel
            setOnClickListener { dismiss() }
        }
        if (tablet) {
            actions.addView(cancel, LinearLayout.LayoutParams(0, -2, 1f))
            actions.addView(View(context), LinearLayout.LayoutParams(dp(12), -1))
            actions.addView(primary, LinearLayout.LayoutParams(0, -2, 1f))
        } else {
            actions.addView(primary, LinearLayout.LayoutParams(-1, -2))
            actions.addView(space(12))
            actions.addView(cancel, LinearLayout.LayoutParams(-1, -2))
        }
        column.addView(actions)
        setContentView(card)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setCancelable(true)
        setCanceledOnTouchOutside(true)
    }

    override fun onStart() {
        super.onStart()
        if (!active()) { dismiss(); return }
        val metrics = context.resources.displayMetrics
        val width = minOf(if (context.resources.configuration.smallestScreenWidthDp >= 600) dp(600)
            else minOf(metrics.widthPixels, metrics.heightPixels) - dp(40), dp(800))
        window?.apply {
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.CENTER)
            if (Build.VERSION.SDK_INT >= 31) {
                addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes = attributes.apply { blurBehindRadius = 30 }
            }
            decorView.apply {
                alpha = 0f; scaleX = .92f; scaleY = .92f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(250)
                    .setInterpolator(PathInterpolator(.2f, 0f, 0f, 1f)).start()
            }
        }
    }

    fun setPending(value: Boolean) {
        pending = value
        if (::primary.isInitialized) primary.isEnabled = !value
    }

    override fun dismiss() {
        guard = null
        confirm = null
        window?.decorView?.animate()?.cancel()
        super.dismiss()
    }

    private fun text(label: Int, size: Float, ink: Int, bold: Boolean = false) = TextView(context).apply {
        setText(label); textSize = size; setTextColor(color(ink))
        typeface = ResourcesCompat.getFont(context, if (bold) UiR.font.harmonyos_sans_sc_bold else UiR.font.harmonyos_sans_sc_medium)
    }

    private fun button(label: Int, fill: Int, ink: Int) = text(label, 16f, R.color.pf_record_delete_primary).apply {
        setTextColor(ink); gravity = Gravity.CENTER; isFocusable = true
        setPadding(0, dp(14), 0, dp(14))
        val surface = GradientDrawable().apply { setColor(fill); cornerRadius = 24f * resources.displayMetrics.density }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 24f * resources.displayMetrics.density }
        background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(ink, 31)), surface, mask)
    }

    private fun space(height: Int) = View(context).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(height)) }
}
