package com.projectfocus.feature.record.ui

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedDispatcher
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint























@AndroidEntryPoint
class RecordHostActivity : FragmentActivity() {








    val container: FrameLayout by lazy {
        FrameLayout(this).apply { id = View.generateViewId() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(container)
    }























    fun install(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .replace(container.id, fragment)
            .commitNow()
    }

    /** 供测试直接驱动返回键 */
    val dispatcher: OnBackPressedDispatcher get() = onBackPressedDispatcher
}
