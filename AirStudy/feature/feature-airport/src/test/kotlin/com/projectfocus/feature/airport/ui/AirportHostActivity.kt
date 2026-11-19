package com.projectfocus.feature.airport.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import dagger.hilt.android.AndroidEntryPoint

































@AndroidEntryPoint
class AirportHostActivity : FragmentActivity() {








    val container: FrameLayout by lazy {
        FrameLayout(this).apply { id = View.generateViewId() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            container,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    /** 测试用：把 Fragment 装进容器（同步完成） */
    fun install(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(container.id, fragment)
            .commitNow()
    }
}
