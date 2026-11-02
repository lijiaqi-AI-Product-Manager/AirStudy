package com.projectfocus.app.route

import android.content.Intent




































sealed interface EntryAction {

    /** 什么都不做，正常打开首页 */
    data object None : EntryAction











    data class QuickStart(val minutes: Int) : EntryAction

    /**
     * 收到了 action 但本版本不认识。
     *
     * @param raw 原始值，只用于日志。不要拿它做业务判断。
     */
    data class Unknown(val raw: String) : EntryAction
}

object EntryActionParser {

    /** 快捷开始的默认时长。与 `QuickStartWidget` 里显示的 "25 MIN" 必须一致 */
    const val QUICK_START_MINUTES: Int = 25

    /** 与 `WidgetEntryActivity.EXTRA_ACTION` 的值保持一致 */
    const val ACTION_QUICK_START: String = "quick_start"









    fun parse(intent: Intent?): EntryAction {
        val raw = intent?.getStringExtra(KEY_ACTION)?.trim()
        if (raw.isNullOrEmpty()) return EntryAction.None
        return when (raw) {
            ACTION_QUICK_START -> EntryAction.QuickStart(QUICK_START_MINUTES)
            else -> EntryAction.Unknown(raw)
        }
    }

    /** 是否来自小组件（用于埋点区分渠道，不参与业务判断） */
    fun isFromWidget(intent: Intent?): Boolean =
        intent?.getStringExtra(KEY_ENTRY_SOURCE) == SOURCE_WIDGET


    const val KEY_ACTION: String = "com.projectfocus.extra.WIDGET_ACTION"
    const val KEY_ENTRY_SOURCE: String = "com.projectfocus.extra.ENTRY_SOURCE"
    const val SOURCE_WIDGET: String = "widget"
}
