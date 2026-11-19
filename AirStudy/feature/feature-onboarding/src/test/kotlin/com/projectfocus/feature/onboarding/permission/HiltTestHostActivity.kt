package com.projectfocus.feature.onboarding.permission

import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import dagger.hilt.android.AndroidEntryPoint
import org.robolectric.Shadows.shadowOf


@AndroidEntryPoint
class HiltTestHostActivity : FragmentActivity() {








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

        //   而 Activity 的 onCreate 由 ActivityScenario 触发，时序在测试手里之外。
    }

    /** 测试用：往容器里装 Fragment */
    fun install(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(container.id, fragment)
            .commitNow()
    }
}
















class HostedFragment<F : Fragment>(
    val fragmentClass: Class<F>,
    private val platform: PermissionPlatform,
    val activity: ActivityScenario<HiltTestHostActivity>,
) {
    var fragment: F? = null
        private set

    /** 装 Fragment 并推到 RESUMED */
    fun resume(): HostedFragment<F> {
        activity.onActivity { act ->

            //   原因：Hilt 会做**字节码改写**，`class X : Fragment()` 编译后
            //   实际继承 `Hilt_X`。此时 `getDeclaredConstructor(...)` 拿到的

            //   实测报 `NoSuchMethodException`。
            //   无参构造在所有情况下都存在，最稳。

            //   曾经在这里写 `finally { unstage() }`，结果 11 条测试全红：
            //   ```
            //   IllegalArgumentException: TestFragmentFactory.stage()
            //   没被调用就构造了 TestableFragment
            //       at PermissionGuideFragment.onResume(PermissionGuideFragment.kt:118)
            //   ```
            //   原因：`viewModel` 是 `by lazy`，**第一次访问在 onResume()**
            //   （`viewModel.onResume()`），而不是在 onCreate/onViewCreated。
            //   构造完立刻 unstage，等到 onResume 时值早就空了。
            //
            //   → 正确策略：stage 后**保持到下一次 stage 覆盖**。
            //     泄漏风险由"每条测试开头都重新 stage"覆盖掉 ——
            //     `launch()` 每次都会调 stage，不存在读到上一条的窗口。
            TestFragmentFactory.stage(platform)
            @Suppress("UNCHECKED_CAST")
            val f = fragmentClass.getDeclaredConstructor().newInstance() as F
            act.install(f)
            fragment = f
        }
        idle()
        activity.moveToState(Lifecycle.State.RESUMED)
        idle()
        return this
    }


    fun <V : View> view(id: Int): V {
        var v: V? = null
        activity.onActivity {
            @Suppress("UNCHECKED_CAST")
            v = requireNotNull(fragment).requireView().findViewById(id) as V
        }
        return requireNotNull(v) { "找不到 id=$id 的控件（布局里没了？）" }
    }

    fun text(id: Int): String = view<android.widget.TextView>(id).text.toString()

    fun close() {
        activity.close()
        idle()
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }
}

/**
 * 启动一个 Fragment 渲染测试场景。
 *
 * @param platform 假平台 —— 所有状态都由测试控制，不读真实系统权限。
 */
fun <F : Fragment> launchHiltFragment(
    fragmentClass: Class<F>,
    platform: PermissionPlatform,
): HostedFragment<F> {
    val scenario = ActivityScenario.launch(HiltTestHostActivity::class.java)
    return HostedFragment(fragmentClass, platform, scenario).resume()
}
