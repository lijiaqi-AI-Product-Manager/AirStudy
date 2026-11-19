package com.projectfocus.app.auth

import android.content.Context
import android.graphics.drawable.InsetDrawable
import android.util.AttributeSet
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Checkable
import androidx.appcompat.widget.AppCompatImageView
import com.projectfocus.app.R
import com.projectfocus.core.ui.sky.SkySurfaces

/** A small check mark with a full touch target and a checked state for accessibility. */
class SourceAuthCheckboxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                                      defStyleAttr: Int = 0) : AppCompatImageView(context, attrs, defStyleAttr), Checkable {
    private var checked = false
    init {
        isFocusable = true
        contentDescription = context.getString(R.string.auth_sky_agreement_toggle)
        updateImage()
    }
    override fun isChecked(): Boolean = checked
    override fun setChecked(value: Boolean) { checked = value; updateImage() }
    override fun toggle() { setChecked(!checked) }

    override fun getAccessibilityClassName(): CharSequence = android.widget.CheckBox::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = checked
    }

    private fun updateImage() {
        val square = context.getDrawable(if (checked) R.drawable.pf_auth_shape_radio_on else R.drawable.pf_auth_shape_radio_off)
        val inset = SkySurfaces.dp(context, 14f)
        background = InsetDrawable(square, inset)
        if (checked) {
            setImageResource(R.drawable.pf_auth_custom_checkbox_checkmark)
            scaleType = ScaleType.CENTER_INSIDE
            val padding = inset + SkySurfaces.dp(context, 3f)
            setPadding(padding, padding, padding, padding)
        } else { setImageDrawable(null); setPadding(0, 0, 0, 0) }
    }
}
