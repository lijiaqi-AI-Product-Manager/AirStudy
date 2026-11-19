package com.projectfocus.feature.onboarding.permission


enum class PermissionItem(





    val core: Boolean,
) {
    /** 使用情况访问（AppOps OPSTR_GET_USAGE_STATS）—— 屏蔽引擎的唯一数据源 */
    USAGE_STATS(core = true),

    /** 悬浮窗（SYSTEM_ALERT_WINDOW）—— 遮挡页与浮层 */
    OVERLAY(core = true),








    NOTIFICATION(core = true),









    BATTERY_OPTIMIZATION(core = false),
    ;

    companion object {
        val CORE: List<PermissionItem> = entries.filter { it.core }
        val OPTIONAL: List<PermissionItem> = entries.filter { !it.core }
    }
}

/** 单个权限的当前状态 */
enum class PermissionStatus {
    /** 已授权 */
    GRANTED,

    /** 未授权，且用户从未被问过（可以弹系统弹窗） */
    DENIED,










    PERMANENTLY_DENIED,
    ;

    val granted: Boolean get() = this == GRANTED
}








data class PermissionGuideState(
    /** 每个权限的当前状态（顺序按 [PermissionItem.entries]） */
    val statuses: Map<PermissionItem, PermissionStatus>,


    val previewMode: Boolean = false,


    val returnedWithoutChange: Boolean = false,
) {






    val coreGranted: Boolean
        get() = PermissionItem.CORE.all { statuses[it]?.granted == true }

    /** 已经开启的核心权限数（用于「2/3」这种进度显示） */
    val coreGrantedCount: Int
        get() = PermissionItem.CORE.count { statuses[it]?.granted == true }










    val nextRequired: PermissionItem?
        get() = PermissionItem.CORE.firstOrNull { statuses[it]?.granted != true }








    val isReadyToFly: Boolean get() = coreGranted

    /** 是否应该显示引导页（进 App 的第一屏） */
    val shouldShowGuide: Boolean get() = !coreGranted && !previewMode

    /** 该权限的按钮文案 —— 依赖它的状态，不是固定字符串 */
    fun actionLabelFor(item: PermissionItem): String = when (statuses[item]) {
        PermissionStatus.GRANTED -> "已开启"
        PermissionStatus.DENIED -> "开启"

        PermissionStatus.PERMANENTLY_DENIED -> "前往设置"
        null -> "开启"
    }

    fun isGranted(item: PermissionItem): Boolean = statuses[item]?.granted == true

    companion object {










        val EMPTY: PermissionGuideState = PermissionGuideState(
            statuses = PermissionItem.entries.associateWith { PermissionStatus.DENIED },
            previewMode = false,
        )
    }
}









object PermissionGuideReducer {

    /** 引导页能收到的全部事件 */
    sealed interface Event {
        /** 页面恢复时刷新（含从系统设置页返回）。`hasRequested` 用于区分首次/拒绝过 */
        data class Refreshed(
            val statuses: Map<PermissionItem, PermissionStatus>,
        ) : Event

        /** 用户点了「先看看」 */
        data object EnteredPreview : Event

        /** 用户在预览模式下点了「开始飞行」→ 弹权限提示 */
        data object RequestedFlyInPreview : Event
    }










    fun reduce(prev: PermissionGuideState, event: Event): PermissionGuideState = when (event) {
        is Event.Refreshed -> {
            val changed = prev.coreGrantedCount != countCore(event.statuses)
            prev.copy(
                statuses = event.statuses,

                //   否则用户下次启动会因为 previewMode=true 而跳过引导页，
                //   即使他后来撤销了某个权限（撤销后再次启动应该重新引导）。
                previewMode = prev.previewMode && countCore(event.statuses) < PermissionItem.CORE.size,
                returnedWithoutChange = !changed && !prev.coreGranted,
            )
        }

        Event.EnteredPreview -> prev.copy(previewMode = true)


        //   这里刻意不改 previewMode —— 让用户留在预览里自己决定，
        //   而不是"点了飞行就强制跳回引导页"（那会打断他刚看的东西）。
        Event.RequestedFlyInPreview -> prev
    }

    private fun countCore(statuses: Map<PermissionItem, PermissionStatus>): Int =
        PermissionItem.CORE.count { statuses[it]?.granted == true }

    /** 冷启动时的初始状态：全部未知（等同于 DENIED），未进入预览 */
    fun initial(): PermissionGuideState = PermissionGuideState(
        statuses = PermissionItem.entries.associateWith { PermissionStatus.DENIED },
        previewMode = false,
    )
}
