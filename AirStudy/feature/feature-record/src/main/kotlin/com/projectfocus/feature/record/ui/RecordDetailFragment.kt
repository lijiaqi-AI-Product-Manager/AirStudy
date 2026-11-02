package com.projectfocus.feature.record.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.core.ui.nav.AirportNavigator
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.completion.pass.BoardingPassDialogFragment
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.databinding.FragmentRecordDetailBinding
import com.projectfocus.feature.record.detail.RecordDetail
import com.projectfocus.feature.record.detail.ReplayIntent
import com.projectfocus.feature.record.detail.RecordDetailBuilder
import com.projectfocus.feature.record.detail.RecordDetailState
import com.projectfocus.feature.record.detail.RecordDetailViewModel
import com.projectfocus.feature.record.share.ShareLauncher
import com.projectfocus.feature.record.share.ShareCardMapper
import com.projectfocus.feature.record.share.shareTemplates
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject





















































@AndroidEntryPoint
open class RecordDetailFragment : Fragment() {









    private var _binding: FragmentRecordDetailBinding? = null
    protected val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 后被访问" }









    protected open fun createViewModel(): RecordDetailViewModel =
        androidx.lifecycle.ViewModelProvider(this)[RecordDetailViewModel::class.java]

    private val viewModel: RecordDetailViewModel by lazy { createViewModel() }










    @Inject
    internal lateinit var shareLauncher: ShareLauncher












    private fun shareLauncherOrNull(): ShareLauncher? {
        if (::shareLauncher.isInitialized) return shareLauncher
        Timber.w("ShareLauncher 未注入（宿主未启用 Hilt），分享不可用")
        return null
    }

    /** 当前分享任务。界面销毁时取消，避免"退回列表页后突然弹分享面板"。 */
    private var shareJob: Job? = null
    private var boardingPassJob: Job? = null

    // ─────────────────────────────────────────────────────────
    // 宿主回调
    // ─────────────────────────────────────────────────────────

    /** 点返回。默认 `popBackStack`。 */
    open var onBack: () -> Unit = {
        parentFragmentManager.takeIf { it.backStackEntryCount > 0 }?.popBackStack()
    }














    open var onReplayIntent: (ReplayIntent) -> Unit = { _ ->
        android.widget.Toast
            .makeText(
                requireContext(),
                R.string.record_detail_replay_pending,
                android.widget.Toast.LENGTH_SHORT,
            )
            .show()
    }

    /** 分享。默认同上。 */
    open var onShare: (RecordDetail) -> Unit = { _ ->
        performShare()
    }







    open var onOpenBoardingPass: ((BoardingPassData) -> Unit)? = null
























    private fun performShare() {
        val detail = (viewModel.state.value as? RecordDetailState.Content)?.detail ?: return
        if (!detail.canShare) return
        val launcher = shareLauncherOrNull()
        if (launcher == null) {

            //   而不是静默不响应 —— 点了没反应比一句错误提示更让人困惑。
            android.widget.Toast
                .makeText(requireContext(), R.string.record_share_failed, android.widget.Toast.LENGTH_SHORT)
                .show()
            return
        }

        showSharePreparing(true)

        //   界面销毁就该取消。ViewModel 活得比界面久，放那边会出现
        //   "已经退到列表页了，突然弹出一个系统分享面板"。
        shareJob?.cancel()
        shareJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = viewModel.prepareShare(detail)
            showSharePreparing(false)
            if (result == null) {

                //   "记录在点击与查库之间被删了"，此时详情页会自动切到
                //   「这条记录不在了」，再弹一个失败 Toast 是双重噪音。
                Timber.w("分享：记录已不存在，跳过")
                return@launch
            }

            //   ShareLauncher 已经把它翻译成 NoTarget（=「没有可接收图片的应用」）。
            //   在这里拦一道只会多一条永远不会执行的 catch。

            val spec = ShareCardMapper.spec(
                data = result.pass,
                detail = result.detail,
                entity = result.entity,
                zone = result.zone,
                templates = requireContext().shareTemplates(),
            )
            val outcome = launcher.share(
                spec = spec,
                data = result.pass,
                startActivity = ::startActivity,
            )
            ShareLauncher.toastFor(requireContext(), outcome, launcher)
        }
    }










    private fun showSharePreparing(preparing: Boolean) {
        if (_binding == null) return
        binding.btnShare.isEnabled = !preparing
        binding.btnShare.setText(
            if (preparing) R.string.record_share_preparing else R.string.record_detail_share,
        )
    }

    /** 删除确认。默认走系统 `AlertDialog`；测试里替换成"直接确认"以避开弹窗。 */
    open var onConfirmDelete: (confirm: () -> Unit) -> Unit = { confirm ->
        showDeleteDialog(confirm)
    }

    /** 删除成功后的提示 + 返回。默认 Toast 后返回。 */
    open var onDeleted: () -> Unit = {
        android.widget.Toast
            .makeText(requireContext(), R.string.record_deleted_toast, android.widget.Toast.LENGTH_SHORT)
            .show()
        onBack()
    }

    /** 删除失败时的提示。 */
    open var onDeleteFailed: () -> Unit = {
        android.widget.Toast
            .makeText(requireContext(), R.string.record_detail_delete_failed, android.widget.Toast.LENGTH_SHORT)
            .show()
    }

    // ─────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentRecordDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindArguments()
        setupActions()
        observeState()
        observeReplayIntent()
        observeDeleteResult()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        boardingPassJob?.cancel()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────
    // 装配
    // ─────────────────────────────────────────────────────────














    private fun bindArguments() {
        val id = arguments?.getString(ARG_RECORD_ID)
        val recordId = id.orEmpty()
        viewModel.start(recordId)

        if (recordId.isNotBlank()) {
            parentFragmentManager.setFragmentResultListener(
                SeatDraftRequests.SELECTION,
                viewLifecycleOwner,
            ) { _, bundle ->
                val result = bundle.getSerializable(SeatDraftRequests.RESULT_DATA)
                    as? SeatSelectionResult ?: return@setFragmentResultListener
                if (result.recordId == recordId) {
                    // 选座页已经写库；先立即回显，Room Flow 随后会再发一次权威值。
                    binding.seatText.isVisible = true
                    binding.seatText.text = getString(R.string.record_detail_seat, result.seat)
                }
            }
        }
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnStateBack.setOnClickListener { onBack() }
        binding.btnBoardingPass.setOnClickListener {
            val detail = (viewModel.state.value as? RecordDetailState.Content)?.detail
                ?: return@setOnClickListener
            // 与分享同一道门：只有真正完成的航班才有登机牌。
            if (!detail.canShare) return@setOnClickListener
            if (parentFragmentManager.findFragmentByTag(BoardingPassDialogFragment.TAG) != null) {
                return@setOnClickListener
            }
            boardingPassJob?.cancel()
            boardingPassJob = viewLifecycleOwner.lifecycleScope.launch {
                val payload = viewModel.prepareShare(detail) ?: return@launch
                if (!isAdded || parentFragmentManager.isStateSaved) return@launch
                val host = onOpenBoardingPass
                if (host != null) {
                    // R6：宿主已接导航图，走正式目的地，不叠浮层。
                    host(payload.pass)
                } else {
                    BoardingPassDialogFragment.newInstance(payload.pass)
                        .show(parentFragmentManager, BoardingPassDialogFragment.TAG)
                }
            }
        }
        binding.btnReplay.setOnClickListener {

            //   算的活归 ViewModel（它才有原始数值），见 `replay()` 的注释。
            viewModel.replay()
        }
        binding.btnShare.setOnClickListener {
            (viewModel.state.value as? RecordDetailState.Content)?.let { onShare(it.detail) }
        }
        binding.btnDelete.setOnClickListener {
            onConfirmDelete { viewModel.delete() }
        }
        // 出发/到达三字码 = 机场详情入口。只对已完成记录有 airport 数据，
        // 未完成记录 fromCode/toCode 可能为空，openAirportDetail 会直接返回。
        binding.fromCode.setOnClickListener {
            openAirportDetail((viewModel.state.value as? RecordDetailState.Content)?.detail?.fromCode)
        }
        binding.toCode.setOnClickListener {
            openAirportDetail((viewModel.state.value as? RecordDetailState.Content)?.detail?.toCode)
        }
        // 点“座位 12A”= 改座位；只有已有座位的记录可见，未完成的记录不显示。
        binding.seatText.setOnClickListener {
            val detail = (viewModel.state.value as? RecordDetailState.Content)?.detail
                ?: return@setOnClickListener
            if (detail.seat == null) return@setOnClickListener
            val uri = Uri.parse(
                "projectfocus://seat?flightNo=&from=${Uri.encode(detail.fromCode)}" +
                    "&to=${Uri.encode(detail.toCode)}&recordId=${Uri.encode(detail.id)}" +
                    "&seat=${Uri.encode(detail.seat.orEmpty())}" +
                    "&draftKey=${Uri.encode(detail.id)}",
            )
            runCatching { findNavController().navigate(uri) }
                .onFailure {
                    Toast.makeText(
                        requireContext(),
                        R.string.record_detail_airport_failed,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
        }
    }

    private fun openAirportDetail(code: String?) {
        if (!AirportNavigator.open(this, code)) {
            Toast.makeText(
                requireContext(),
                R.string.record_detail_airport_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }













    private fun observeReplayIntent() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.replayIntent.collect { intent ->
                    if (intent == null) return@collect
                    onReplayIntent(intent)

                    //   consume 不执行 → 意图保留。用户再点一次仍然有效。
                    viewModel.consumeReplayIntent()
                }
            }
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {

            //   Fragment 的 viewLifecycle 在 `onStop` 之后仍可能短暂存活，
            //   裸 collect 会在后台继续更新 View（浪费 CPU，且可能在
            //   View 已 detach 的瞬间触发一次绘制）。
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun observeDeleteResult() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.deleteResult.collect { result ->
                    when (result) {
                        null -> Unit
                        true -> {

                            //   顺序反了的话：onDeleted() 里 pop 掉本页 →
                            //   viewLifecycleOwner 销毁 → 收集器取消 →
                            //   `consumeDeleteResult()` 永远不会执行 →
                            //   旋转屏幕时 StateFlow 重放 true → 再 pop 一次。

                            viewModel.consumeDeleteResult()
                            onDeleted()
                        }
                        false -> {
                            viewModel.consumeDeleteResult()
                            onDeleteFailed()
                        }
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 渲染
    // ─────────────────────────────────────────────────────────

    private fun render(state: RecordDetailState) {
        val isContent = state is RecordDetailState.Content
        binding.detailScroll.isVisible = isContent
        binding.actionBlock.isVisible = isContent

        when (state) {
            is RecordDetailState.Content -> {


                //   这里不做的话，TalkBack 会在内容页上念出上一次的"这条记录不在了"。
                binding.statePanel.isVisible = false
                binding.stateTitle.text = ""
                binding.stateBody.text = ""
                binding.stateBody.isVisible = false
                renderContent(state.detail)
            }

            RecordDetailState.Loading -> renderMessage(
                title = getString(R.string.record_detail_loading),
                body = "",
            )

            RecordDetailState.NotFound -> renderMessage(
                title = getString(R.string.record_detail_not_found_title),
                body = getString(R.string.record_detail_not_found_body),
            )

            is RecordDetailState.Error -> renderMessage(
                title = getString(R.string.record_detail_error_title),
                body = state.message,
            )
        }
    }








    private fun renderMessage(title: String, body: String) {
        binding.statePanel.isVisible = true
        binding.stateTitle.text = title
        binding.stateTitle.visibility = View.VISIBLE

        //   `layout_marginTop` 那 8dp，标题看起来会偏上。
        binding.stateBody.text = body
        binding.stateBody.isVisible = body.isNotEmpty()
        binding.btnStateBack.isVisible = true
    }

    private fun renderContent(d: RecordDetail) {
        binding.fromCode.text = d.fromCode
        binding.toCode.text = d.toCode
        binding.fromCity.text = d.fromCity
        binding.toCity.text = d.toCity

        // 机场全名：两个都为空时整行 GONE（不能显示 " → " 这种空箭头）
        val names = listOf(d.fromAirportName, d.toAirportName).filter { it.isNotBlank() }
        binding.airportNames.isVisible = names.isNotEmpty()
        binding.airportNames.text = names.joinToString(" → ")

        binding.dateText.text = d.dateText
        binding.timeRangeText.text = d.timeRangeText
        binding.statusText.text = d.statusText


        //     （values-night 的覆盖会失效）。必须走 ContextCompat。
        binding.statusText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                requireContext(),
                statusColorRes(d),
            ),
        )

        binding.plannedText.text = d.plannedText
        binding.actualText.text = d.actualText
        binding.diffText.text = d.diffText


        //    字面量散落两处，改文案时颜色判断会**静默失效**（不报错，只是变黄）。
        binding.diffText.setTextColor(
            androidx.core.content.ContextCompat.getColor(
                requireContext(),
                if (d.diffText == RecordDetailBuilder.DIFF_EXACT) UiR.color.text_secondary
                else UiR.color.semantic_warning,
            ),
        )

        val percent = d.completionPercent
        binding.percentRow.isVisible = percent != null

        //   （法语是「93 %」带不换行空格），且 lint 的 SetTextI18n 会持续报。
        binding.percentText.text = percent?.let { getString(R.string.record_detail_percent, it) }.orEmpty()


        binding.distanceText.text = getString(R.string.record_detail_distance_value, d.distanceText)
        binding.cruiseEquivalentText.text = d.cruiseEquivalentText

        binding.cruiseEquivalentText.isVisible = d.cruiseEquivalentText != "—"
        binding.routeEquivalentText.text = d.routeEquivalentText
        binding.routeEquivalentText.isVisible = d.routeEquivalentText != "—"

        binding.seatText.isVisible = d.seat != null
        binding.seatText.text = d.seat?.let { getString(R.string.record_detail_seat, it) }.orEmpty()

        binding.participantText.isVisible = d.participantCount != null
        binding.participantText.text = d.participantCount
            ?.let { getString(R.string.record_detail_participants, it) }
            .orEmpty()

        val hasQuote = d.quote != null
        binding.quoteBlock.isVisible = hasQuote
        binding.quoteText.text = d.quote.orEmpty()

        binding.quoteAuthorText.isVisible = d.quoteAuthor != null
        binding.quoteAuthorText.text = d.quoteAuthor?.let { "—— $it" }.orEmpty()

        // 操作可用性
        binding.btnBoardingPass.isEnabled = d.canShare
        binding.btnReplay.isEnabled = d.canReplay
        binding.btnShare.isEnabled = d.canShare

        //   而重飞/分享依赖"这趟真的完成了"，不能给。

        // 整页无障碍：让 TalkBack 一次读完头信息，而不是逐行读
        binding.detailScroll.contentDescription = d.spokenHeader
    }

    private fun statusColorRes(d: RecordDetail): Int = when {
        d.statusText.contains("异常") -> UiR.color.semantic_error
        d.status == com.projectfocus.feature.record.list.FlightStatus.CANCELLED -> UiR.color.text_tertiary
        d.status == com.projectfocus.feature.record.list.FlightStatus.ONGOING -> UiR.color.accent_primary
        else -> UiR.color.semantic_success
    }

    private fun showDeleteDialog(confirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.record_delete_confirm_title)
            .setMessage(R.string.record_delete_confirm_body)
            .setPositiveButton(R.string.record_delete_confirm_ok) { _, _ -> confirm() }
            .setNegativeButton(R.string.record_delete_confirm_cancel, null)
            .show()
    }

    // ─────────────────────────────────────────────────────────
    // 测试钩子
    // ─────────────────────────────────────────────────────────

    @androidx.annotation.VisibleForTesting
    internal fun requireViewForTest(): View = binding.root

    @androidx.annotation.VisibleForTesting
    internal fun stateForTest(): RecordDetailState = viewModel.state.value

    @androidx.annotation.VisibleForTesting
    internal fun detailViewModelForTest(): RecordDetailViewModel = viewModel

    companion object {
        /** 记录 id 的参数键。写在 feature 模块内，宿主壳直接引用。 */
        const val ARG_RECORD_ID = "arg_record_id"
    }
}
