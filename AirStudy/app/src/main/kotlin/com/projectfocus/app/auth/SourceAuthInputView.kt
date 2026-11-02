package com.projectfocus.app.auth

import android.content.Context
import android.text.Editable
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.projectfocus.app.R


class SourceAuthInputView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null,
                                                   defStyleAttr: Int = 0) : LinearLayout(context, attrs, defStyleAttr) {
    private val input: EditText

    init {
        LayoutInflater.from(context).inflate(R.layout.pf_auth_view_custom_input, this, true)
        val label = findViewById<TextView>(R.id.pf_auth_tv_label)
        input = findViewById(R.id.pf_auth_et_input)
        val values = context.obtainStyledAttributes(attrs, R.styleable.PfAuthInputView, defStyleAttr, 0)
        try {
            val labelText = values.getString(R.styleable.PfAuthInputView_pf_auth_inputLabel)
            val hintText = values.getString(R.styleable.PfAuthInputView_pf_auth_inputHint)
            val type = values.getInt(R.styleable.PfAuthInputView_android_inputType, 0)
            label.text = labelText
            input.hint = hintText
            input.inputType = type
        } finally { values.recycle() }
    }

    fun getText(): Editable = input.text
}
