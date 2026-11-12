package com.projectfocus.feature.onboarding.permission

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.onboarding.R
import com.projectfocus.feature.onboarding.databinding.FragmentPermissionGuideBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber































@AndroidEntryPoint
open class PermissionGuideFragment : Fragment() {

    private var _binding: FragmentPermissionGuideBinding? = null











    protected val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 后被访问" }












    protected open fun createViewModel(): PermissionGuideViewModel =
        androidx.lifecycle.ViewModelProvider(this)[PermissionGuideViewModel::class.java]

    private val viewModel: PermissionGuideViewModel by lazy { createViewModel() }







    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onRuntimeResult(PermissionItem.NOTIFICATION, granted)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPermissionGuideBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        bindClickListeners()
        observeState()
    }

    override fun onResume() {
        super.onResume()

        viewModel.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────
    // 点击
    // ─────────────────────────────────────────────────────────

    private fun bindClickListeners() {
        binding.btnUsageStats.setOnClickListener { open(PermissionItem.USAGE_STATS) }
        binding.btnOverlay.setOnClickListener { open(PermissionItem.OVERLAY) }
        binding.btnNotification.setOnClickListener { open(PermissionItem.NOTIFICATION) }
        binding.btnBattery.setOnClickListener { open(PermissionItem.BATTERY_OPTIMIZATION) }

        binding.btnPreview.setOnClickListener { viewModel.onEnterPreview() }
        binding.btnDismissHint.setOnClickListener { viewModel.onDismissHint() }
    }









    private fun open(item: PermissionItem) {
        val intent = viewModel.onItemClicked(item)
        when {
            intent != null -> {
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {

                    //   此时不能崩 —— 给用户一个可理解的反馈，让他自己去设置里找。
                    Timber.w(e, "权限 %s 的设置页不存在", item)
                    showFallbackHint()
                } catch (e: SecurityException) {

                    Timber.w(e, "打开权限 %s 设置页被拒绝", item)
                    showFallbackHint()
                }
            }

            viewModel.state.value.actionLabelFor(item) == getString(R.string.permission_action_settings) ||
                item == PermissionItem.NOTIFICATION -> {
                // 需要运行时请求（或已被永久拒绝但仍点了）
                requestNotificationIfNeeded(item)
            }

            else -> Timber.w("权限 %s 无法跳转也无法申请", item)
        }
    }

    private fun requestNotificationIfNeeded(item: PermissionItem) {
        if (item == PermissionItem.NOTIFICATION) {

            //   （lint InlinedApi 抓到）
            //
            //   `Manifest.permission.POST_NOTIFICATIONS` 这个**常量**是 API 33 才加进
            //   android.jar 的。虽然它是一个 `static final String`、编译期会被
            //   **内联成字面量**（所以运行期其实不会崩），但 lint 只看符号引用，
            //   于是报 "Field requires API level 33 (current min is 26)"。
            //

            //     如果哪天有人把这个常量改成从 `PackageManager` 读的非常量，
            //     内联就没了，调用点会在 API 26 上抛 NoSuchFieldError。
            //   → 写死字符串，语义完全等价，且**不依赖任何 API level**。
            //     反正它本来就是个不会变的协议常量。
            @Suppress("InlinedApi")
            notificationLauncher.launch(PERMISSION_POST_NOTIFICATIONS)
        }
    }







    private fun showFallbackHint() {
        binding.hintCard.isVisible = true
        binding.permissionScroll.smoothScrollTo(0, binding.hintCard.top)
    }

    // ─────────────────────────────────────────────────────────
    // 渲染
    // ─────────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }







    private fun render(state: PermissionGuideState) {
        renderCopy(state)
        renderItem(binding.btnUsageStats, binding.cardUsageStats, state, PermissionItem.USAGE_STATS)
        renderItem(binding.btnOverlay, binding.cardOverlay, state, PermissionItem.OVERLAY)
        renderItem(binding.btnNotification, binding.cardNotification, state, PermissionItem.NOTIFICATION)
        renderItem(binding.btnBattery, binding.cardBattery, state, PermissionItem.BATTERY_OPTIMIZATION)
        renderHint(state)
        renderPreview(state)
    }

    /** 标题、副标题、进度三处文案 */
    private fun renderCopy(state: PermissionGuideState) {
        val total = PermissionItem.CORE.size
        val done = state.coreGrantedCount

        when {
            state.coreGranted -> {
                binding.titleText.setText(R.string.permission_title_done)
                binding.subtitleText.setText(R.string.permission_subtitle_done)
            }

            done == 0 -> {
                binding.titleText.setText(R.string.permission_title)
                binding.subtitleText.setText(R.string.permission_subtitle)
            }

            else -> {
                binding.titleText.text =
                    getString(R.string.permission_title_partial, total - done)
                binding.subtitleText.setText(R.string.permission_subtitle)
            }
        }

        binding.progressText.text = getString(R.string.permission_progress_format, done, total)
    }












    private fun renderItem(
        btn: MaterialButton,
        card: MaterialCardView,
        state: PermissionGuideState,
        item: PermissionItem,
    ) {
        val granted = state.isGranted(item)
        val isNext = !granted && state.nextRequired == item

        btn.text = state.actionLabelFor(item)
        // 已开启的按钮不可点 —— 但仍保留在布局里（不留空洞，避免布局跳动）
        btn.isEnabled = !granted

        val numberView = when (item) {
            PermissionItem.USAGE_STATS -> binding.stepUsageStats
            PermissionItem.OVERLAY -> binding.stepOverlay
            PermissionItem.NOTIFICATION -> binding.stepNotification
            // 电池优化没有序号列
            PermissionItem.BATTERY_OPTIMIZATION -> null
        }
        numberView?.text = if (granted) "✓" else item.displayIndex()
        numberView?.setTextColor(
            requireContext().getColor(
                if (granted || isNext) UiR.color.accent_primary else UiR.color.text_tertiary,
            ),
        )


        //   不用背景色区别 —— 背景色变化会让人觉得"整体风格变了"，
        //   而描边是局部的、可撤的强调。
        val ctx = card.context
        card.strokeColor = ctx.getColor(
            if (isNext) UiR.color.accent_primary else UiR.color.divider,
        )
        card.strokeWidth = if (isNext) {
            resources.getDimensionPixelSize(UiR.dimen.stroke_emphasis)
        } else {
            resources.getDimensionPixelSize(UiR.dimen.stroke_hairline)
        }
    }

    private fun renderHint(state: PermissionGuideState) {
        binding.hintCard.isVisible = state.returnedWithoutChange
    }

    private fun renderPreview(state: PermissionGuideState) {
        // 预览模式下按钮文案改成「返回设置权限」，且显示警告
        binding.btnPreview.setText(
            if (state.previewMode) {
                R.string.permission_action_back_to_settings
            } else {
                R.string.permission_action_preview
            },
        )
        binding.previewWarning.isVisible = state.previewMode
    }

    private companion object {








        const val PERMISSION_POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}









private fun PermissionItem.displayIndex(): String = when (this) {
    PermissionItem.USAGE_STATS -> "1"
    PermissionItem.OVERLAY -> "2"
    PermissionItem.NOTIFICATION -> "3"
    PermissionItem.BATTERY_OPTIMIZATION -> ""
}
