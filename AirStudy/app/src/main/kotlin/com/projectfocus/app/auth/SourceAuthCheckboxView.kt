package com.projectfocus.app.auth

import android.content.Context
import android.util.AttributeSet
import android.widget.Checkable
import androidx.appcompat.widget.AppCompatImageView
import com.projectfocus.app.R
import kotlin.math.ceil

/** Source c(): background plus centered check image, never a canvas-drawn checkbox. */
class SourceAuthCheckboxView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                                      defStyleAttr: Int = 0) : AppCompatImageView(context, attrs, defStyleAttr), Checkable {
    private var checked = false
    init { updateImage() }
    override fun isChecked(): Boolean = checked
    override fun setChecked(value: Boolean) { checked = value; updateImage() }
    override fun toggle() { setChecked(!checked) }

    private fun updateImage() {
        setBackgroundResource(if (checked) R.drawable.pf_auth_shape_radio_on else R.drawable.pf_auth_shape_radio_off)
        if (checked) {
            setImageResource(R.drawable.pf_auth_custom_checkbox_checkmark)
            scaleType = ScaleType.CENTER_INSIDE
            val padding = ceil(resources.displayMetrics.density * 3f).toInt()
            setPadding(padding, padding, padding, padding)
        } else { setImageDrawable(null); setPadding(0, 0, 0, 0) }
    }
}
