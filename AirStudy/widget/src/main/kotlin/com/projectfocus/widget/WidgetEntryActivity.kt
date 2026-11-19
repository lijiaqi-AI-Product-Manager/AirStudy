package com.projectfocus.widget

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import timber.log.Timber





































class WidgetEntryActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        route()
        finish()

        //   不覆盖的话用户会看到桌面轻微一抖（Activity 转场把桌面也带着动了一下）。
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun route() {
        val launch = runCatching {
            packageManager.getLaunchIntentForPackage(packageName)
        }.getOrNull()

        if (launch == null) {

            //   （比如 App 正在被卸载），至少不要抛异常把用户留在一个空页面。
            Timber.e("无法获取主界面 Intent，小组件点击无效")
            return
        }

        launch.addFlags(

            //   不新建实例，而是把它之上的页面全部弹掉、并把新的 Intent
            //   通过 onNewIntent 交给它 —— 主界面收到来源标记后会自己刷新状态。
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_NEW_TASK,
        )
        launch.putExtra(EXTRA_ENTRY_SOURCE, SOURCE_WIDGET)

        //   本页是唯一知道"哪个小组件被点了"的地方 —— 一旦 finish 这个信息就没了。

        //     有的是 actionStartActivity<T>()（无参数），此时 Intent 里没有这个 extra，
        //     塞一个空串会让主界面收到"有个空动作"，比没有更糟。
        intent.getStringExtra(EXTRA_ACTION)?.takeIf { it.isNotBlank() }?.let {
            launch.putExtra(EXTRA_ACTION, it)
        }

        runCatching { startActivity(launch) }
            .onFailure { Timber.e(it, "拉起主界面失败") }
    }

    companion object {
        /** 来源标记的键。主界面用 `intent.getStringExtra(EXTRA_ENTRY_SOURCE)` 读取 */
        const val EXTRA_ENTRY_SOURCE = "com.projectfocus.extra.ENTRY_SOURCE"









        const val SOURCE_WIDGET = "widget"












        const val EXTRA_ACTION = "com.projectfocus.extra.WIDGET_ACTION"











        const val ACTION_QUICK_START = "quick_start"
    }
}
