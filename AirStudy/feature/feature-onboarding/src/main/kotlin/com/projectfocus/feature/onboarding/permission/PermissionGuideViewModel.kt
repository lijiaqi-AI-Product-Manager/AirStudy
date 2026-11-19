package com.projectfocus.feature.onboarding.permission

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject


@HiltViewModel
class PermissionGuideViewModel @Inject constructor(
    private val platform: PermissionPlatform,
) : ViewModel() {

    private val _state = MutableStateFlow(PermissionGuideState.EMPTY)
    val state: StateFlow<PermissionGuideState> = _state.asStateFlow()


    private var pendingItem: PermissionItem? = null







    private val requestedRuntime = mutableSetOf<PermissionItem>()

    /** 进入引导页（或进程重启后首次进入）：读状态并初始化 */
    fun onEnter() {
        refreshInternal(fromSettingsReturn = false)
    }

    /** 宿主 `onResume` 调用。区分「从设置页回来」与「普通切回」 */
    fun onResume() {
        val cameBack = pendingItem
        pendingItem = null
        refreshInternal(fromSettingsReturn = cameBack != null)
    }

    /**
     * 用户点了某个权限的「开启」。
     *
     * @return 需要启动的 Intent；null 表示走运行时权限请求（Android 13+ 通知）
     */
    fun onItemClicked(item: PermissionItem): Intent? {

        pendingItem = item
        if (platform.needsRuntimeRequest(item)) {
            requestedRuntime += item
            return null
        }
        return platform.settingsIntent(item).also {
            if (it == null) {

                //   此时必须清掉 pendingItem，否则下次 onResume 会误判成
                //   「从设置页回来但没变化」，给用户一个莫名其妙的提示。
                Timber.w("权限 %s 既无跳转 Intent 也无需运行时请求", item)
                pendingItem = null
            }
        }
    }

    /**
     * 运行时权限请求结果回调（仅 Android 13+ 通知）。
     *
     * @param granted 用户是否授予
     */
    fun onRuntimeResult(item: PermissionItem, granted: Boolean) {

        //   运行时请求走的是系统弹窗，不经过设置页，
        //   onResume 会紧接着触发，此时不能误报「没找到」。
        pendingItem = null
        if (!granted) {
            // 保留在 requestedRuntime 里，让下一次 refresh 判定为永久拒绝
            Timber.i("运行时权限 %s 被拒绝（本会话已请求过）", item)
        }
        refreshInternal(fromSettingsReturn = false)
    }

    /** 用户点「先看看」，进入预览模式 */
    fun onEnterPreview() {
        _state.value = PermissionGuideReducer.reduce(
            _state.value,
            PermissionGuideReducer.Event.EnteredPreview,
        )
    }

    /** 用户点了「没找到？看图示」 */
    fun onDismissHint() {
        _state.value = _state.value.copy(returnedWithoutChange = false)
    }

    // ─────────────────────────────────────────────────────────
    // 内部
    // ─────────────────────────────────────────────────────────

    private fun refreshInternal(fromSettingsReturn: Boolean) {
        val statuses = PermissionItem.entries.associateWith { readStatus(it) }
        val before = _state.value.coreGrantedCount

        val reduced = PermissionGuideReducer.reduce(
            _state.value,
            PermissionGuideReducer.Event.Refreshed(statuses),
        )


        //   但**只有从设置页回来时**这个提示才有意义。
        //   普通的 onResume（切了下 App 回来）也会"没有进展"，
        //   那时弹「没找到开关？」是冒犯的。
        //   → reducer 保持纯函数（它不知道来源），由 ViewModel 按来源修正。
        val showHint = fromSettingsReturn &&
            reduced.coreGrantedCount == before &&
            !reduced.coreGranted

        _state.value = reduced.copy(returnedWithoutChange = showHint)

        Timber.d(
            "权限刷新 fromSettings=%b core=%d/%d hint=%b",
            fromSettingsReturn, reduced.coreGrantedCount, PermissionItem.CORE.size, showHint,
        )
    }

    private fun readStatus(item: PermissionItem): PermissionStatus {
        val raw = runCatching { platform.status(item) }
            .onFailure { Timber.w(it, "读权限 %s 状态失败，按未授予处理", item) }
            .getOrDefault(PermissionStatus.DENIED)


        //   平台层只说「现在没授予」，本类结合「本会话是否请求过」才能判断
        //   「系统弹窗还会不会再出现」。
        if (raw == PermissionStatus.GRANTED) return PermissionStatus.GRANTED
        if (item in requestedRuntime && platform.isPermanentlyDenied(item)) {
            return PermissionStatus.PERMANENTLY_DENIED
        }

        //   但「一次被拒后系统弹窗是否还能再出现」在 ROM 间不一致，
        //   保守取「一次即改文案」—— 文案改错代价只是看起来更啰嗦，
        //   而文案不改的代价是用户点了没反应）
        if (item in requestedRuntime && raw != PermissionStatus.GRANTED) {
            return PermissionStatus.PERMANENTLY_DENIED
        }
        return raw
    }

    /** 供 UI 在 Fragment 里直接读（避免每次都要 collect 才知道要点哪个） */
    fun nextRequired(): PermissionItem? = _state.value.nextRequired







    fun onEnterAsync() {
        viewModelScope.launch { onEnter() }
    }
}
