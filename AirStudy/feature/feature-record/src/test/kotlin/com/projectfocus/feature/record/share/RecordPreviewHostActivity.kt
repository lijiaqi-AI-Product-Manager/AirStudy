package com.projectfocus.feature.record.share

import android.app.Activity
import android.os.Bundle
import android.widget.FrameLayout
import com.projectfocus.core.ui.R

class RecordPreviewHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Pf)
        super.onCreate(savedInstanceState)
        setContentView(FrameLayout(this))
    }
}
