package com.projectfocus.feature.record.ui

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.projectfocus.core.common.nav.SeatDraftRequests
import com.projectfocus.core.common.nav.SeatSelectionResult
import com.projectfocus.core.ui.nav.AirportNavigator
import com.projectfocus.core.ui.sky.SkyPressFeedback
import com.projectfocus.core.ui.sky.SkySurfaces
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.completion.pass.BoardingPassDialogFragment
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.databinding.FragmentRecordDetailBinding
import com.projectfocus.feature.record.detail.RecordDetail
import com.projectfocus.feature.record.detail.RecordDetailBuilder
import com.projectfocus.feature.record.detail.RecordDetailState
import com.projectfocus.feature.record.detail.RecordDetailViewModel
import com.projectfocus.feature.record.detail.RecordReplaySource
import com.projectfocus.feature.record.detail.ReplayIntent
import com.projectfocus.feature.record.detail.ReplayIntentBuilder
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.share.ShareCardMapper
import com.projectfocus.feature.record.share.ShareLauncher
import com.projectfocus.feature.record.share.shareTemplates
import com.projectfocus.service.focus.history.FlightRecordKind
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.projectfocus.core.ui.R as UiR

@AndroidEntryPoint
open class RecordDetailFragment : Fragment() {
    private var _binding: FragmentRecordDetailBinding? = null
    protected val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 后被访问" }
    protected open fun createViewModel(): RecordDetailViewModel = ViewModelProvider(this)[RecordDetailViewModel::class.java]
    private val viewModel: RecordDetailViewModel by lazy { createViewModel() }

    @Inject internal lateinit var shareLauncher: ShareLauncher

    private var removalRevision = 0L
    private var removalDialog: AlertDialog? = null
    private var shareJob: Job? = null
    private var boardingPassJob: Job? = null
    private var replayNavigation: Job? = null

    open var onBack: () -> Unit = {
        val nav = runCatching { findNavController() }.getOrNull()
        if (nav != null) nav.popBackStack()
        else if (parentFragmentManager.backStackEntryCount > 0) parentFragmentManager.popBackStack()
    }
    open var onReplayIntent: (ReplayIntent) -> Unit = ::openReplay
    open var onShare: (RecordDetail) -> Unit = ::performShare
    open var onOpenBoardingPass: ((BoardingPassData) -> Unit)? = null
    open var onConfirmDelete: (() -> Unit) -> Unit = ::showDeleteDialog
    open var onDeleted: () -> Unit = {
        notice(R.string.record_deleted_toast)
        onBack()
    }
    open var onDeleteFailed: () -> Unit = { notice(R.string.record_detail_delete_failed) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentRecordDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bindArguments()
        style()
        setupActions()
        render(RecordDetailState.Loading)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch {
                    viewModel.deleteResult.collect { result ->
                        if (result == null) return@collect
                        val current = viewModel.isDeleteResultCurrent()
                        viewModel.consumeDeleteResult()
                        if (current) {
                            if (result) onDeleted() else onDeleteFailed()
                        }
                    }
                }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.replayIntent.collect { intent ->
                    if (intent == null) return@collect
                    viewModel.consumeReplayIntent()
                    if (intent.source?.let(::isViewing) == true) onReplayIntent(intent)
                }
            }
        }
    }

    override fun onPause() {
        ++removalRevision
        removalDialog?.dismiss()
        removalDialog = null
        viewModel.cancelReplay()
        replayNavigation?.cancel()
        replayNavigation = null
        boardingPassJob?.cancel()
        boardingPassJob = null
        shareJob?.cancel()
        shareJob = null
        showSharePreparing(false)
        super.onPause()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun bindArguments() {
        val args = arguments
        val mode = args?.getString(ARG_RECORD_KIND)
        val kind = if (mode == null) FlightRecordKind.COUNTDOWN else FlightRecordKind.entries.firstOrNull { it.name == mode }
        val id = args?.getString(ARG_RECORD_ID).orEmpty()
        val startedAt = args?.takeIf { it.containsKey(ARG_RECORD_START) }?.getLong(ARG_RECORD_START)
        val owner = args?.getString(ARG_RECORD_OWNER)
        viewModel.start(
            if (kind == null) "" else id,
            kind ?: FlightRecordKind.COUNTDOWN,
            owner ?: com.projectfocus.core.network.di.TokenStore.storageScope(),
            startedAt,
        )
        if (kind != FlightRecordKind.COUNTDOWN || id.isBlank()) return
        parentFragmentManager.setFragmentResultListener(SeatDraftRequests.SELECTION, viewLifecycleOwner) { _, bundle ->
            val result = bundle.getSerializable(SeatDraftRequests.RESULT_DATA) as? SeatSelectionResult
                ?: return@setFragmentResultListener
            val current = viewModel.currentContent()?.takeIf { it.source.key.id == result.recordId }
                ?: return@setFragmentResultListener
            if (current.detail.kind == FlightRecordKind.COUNTDOWN) {
                binding.seatText.isVisible = true
                binding.seatText.text = getString(R.string.record_detail_seat, result.seat)
            }
        }
    }

    private fun style() {
        val context = requireContext()
        SkySurfaces.card(binding.routeCard, 26f, 6f)
        SkySurfaces.secondary(binding.btnBack, 20f)
        binding.btnBack.imageTintList = ColorStateList.valueOf(context.getColor(UiR.color.sky_ink))
        binding.modeText.background = SkySurfaces.shape(context, 14f)
        listOf(binding.btnBoardingPass, binding.btnShare, binding.btnDelete, binding.btnStateBack).forEach {
            SkySurfaces.secondary(it, 18f)
            SkyPressFeedback.install(it)
        }
        for (button in listOf(binding.btnReplay, binding.btnRetry)) {
            SkySurfaces.action(button)
            SkyPressFeedback.install(button)
        }
        SkyPressFeedback.install(binding.btnBack)
        binding.btnReplay.setText(R.string.record_detail_prepare_again)
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnStateBack.setOnClickListener { onBack() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
        binding.btnReplay.setOnClickListener { if (replayNavigation?.isActive != true) viewModel.replay() }
        binding.btnShare.setOnClickListener { viewModel.currentContent()?.let { onShare(it.detail) } }
        binding.btnBoardingPass.setOnClickListener { openBoardingPass() }
        binding.btnDelete.setOnClickListener { askToDelete() }
        binding.fromCode.setOnClickListener { openAirportDetail(viewModel.currentContent()?.detail?.fromCode) }
        binding.toCode.setOnClickListener { openAirportDetail(viewModel.currentContent()?.detail?.toCode) }
        binding.seatText.setOnClickListener { openSeatChoice() }
    }

    private fun openReplay(intent: ReplayIntent) {
        val source = intent.source ?: return
        if (replayNavigation?.isActive == true || !isViewing(source)) return
        val host = activity as? RecordReplayHost ?: return notice(R.string.record_detail_replay_unavailable)
        binding.btnReplay.isEnabled = false
        replayNavigation = viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!host.prepareRecordReplay(intent) && isViewing(source)) notice(R.string.record_detail_replay_unavailable)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isViewing(source)) notice(R.string.record_detail_replay_unavailable)
            } finally {
                if (isViewing(source)) binding.btnReplay.isEnabled = viewModel.currentContent()?.detail?.canReplay == true
            }
        }
    }

    private fun performShare(detail: RecordDetail) {
        if (!detail.canShare || shareJob?.isActive == true) return
        if (!::shareLauncher.isInitialized) return notice(R.string.record_share_failed)
        showSharePreparing(true)
        shareJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = viewModel.prepareShare(detail) ?: return@launch
                if (!isViewing(result.source)) return@launch
                val spec = ShareCardMapper.spec(result.pass, result.detail, result.record, result.zone,
                    requireContext().shareTemplates())
                val outcome = shareLauncher.share(spec, result.pass, startActivity = { intent ->
                    if (!isViewing(result.source)) throw IllegalStateException("Record is no longer visible")
                    startActivity(intent)
                })
                if (isViewing(result.source)) ShareLauncher.toastFor(requireContext(), outcome, shareLauncher)
            } finally {
                showSharePreparing(false)
            }
        }
    }

    private fun showSharePreparing(preparing: Boolean) {
        val button = _binding?.btnShare ?: return
        button.isEnabled = !preparing && viewModel.currentContent()?.detail?.canShare == true
        button.setText(if (preparing) R.string.record_share_preparing else R.string.record_detail_share)
    }

    private fun openBoardingPass() {
        val detail = viewModel.currentContent()?.detail?.takeIf { it.canShare } ?: return
        if (boardingPassJob?.isActive == true ||
            parentFragmentManager.findFragmentByTag(BoardingPassDialogFragment.TAG) != null) return
        boardingPassJob = viewLifecycleOwner.lifecycleScope.launch {
            val payload = viewModel.prepareShare(detail) ?: return@launch
            if (!isViewing(payload.source) || parentFragmentManager.isStateSaved) return@launch
            val host = onOpenBoardingPass
            if (host != null) host(payload.pass) else
                BoardingPassDialogFragment.newInstance(payload.pass).show(parentFragmentManager, BoardingPassDialogFragment.TAG)
        }
    }

    private fun askToDelete() {
        val root = view ?: return
        val request = ++removalRevision
        viewLifecycleOwner.lifecycleScope.launch {
            val record = viewModel.prepareDelete() ?: return@launch
            val source = RecordReplaySource(record.account, record.record.key, record.record.recordedAtWallMs)
            fun current() = view === root && removalRevision == request && isViewing(source)
            if (current()) onConfirmDelete { if (current()) viewModel.delete(record) }
        }
    }

    private fun openAirportDetail(code: String?) {
        if (!AirportNavigator.open(this, code)) notice(R.string.record_detail_airport_failed)
    }

    private fun openSeatChoice() {
        val detail = viewModel.currentContent()?.detail ?: return
        if (detail.kind != FlightRecordKind.COUNTDOWN || detail.seat == null) return
        val uri = Uri.Builder().scheme("projectfocus").authority("seat")
            .appendQueryParameter("flightNo", detail.flightNumber.orEmpty())
            .appendQueryParameter("from", detail.fromCode).appendQueryParameter("to", detail.toCode)
            .appendQueryParameter("recordId", detail.id).appendQueryParameter("seat", detail.seat)
            .appendQueryParameter("draftKey", detail.id).build()
        runCatching { findNavController().navigate(uri) }.onFailure { notice(R.string.record_detail_airport_failed) }
    }

    private fun render(state: RecordDetailState) {
        val content = (state as? RecordDetailState.Content)?.takeIf { it.source.account.isCurrent() }
        binding.detailScroll.isVisible = content != null
        binding.actionBlock.isVisible = content != null
        binding.statePanel.isVisible = content == null
        binding.btnRetry.isVisible = state is RecordDetailState.Error
        if (content != null) {
            binding.stateTitle.text = ""
            binding.stateBody.text = ""
            binding.stateBody.isVisible = false
            renderContent(content.detail)
            return
        }
        val (title, body) = when (state) {
            RecordDetailState.Loading -> R.string.record_detail_loading to ""
            is RecordDetailState.Error -> R.string.record_detail_error_title to state.message
            else -> R.string.record_detail_not_found_title to getString(R.string.record_detail_not_found_body)
        }
        binding.stateTitle.setText(title)
        binding.stateTitle.visibility = View.VISIBLE
        binding.stateBody.text = body
        binding.stateBody.isVisible = body.isNotEmpty()
        binding.btnStateBack.isVisible = state != RecordDetailState.Loading
    }

    private fun renderContent(detail: RecordDetail) {
        with(binding) {
            val countUp = detail.kind == FlightRecordKind.COUNT_UP
            modeText.setText(if (countUp) R.string.pf_record_card_archive_badge_countup else R.string.pf_record_card_archive_badge_countdown)
            flightNumberText.text = detail.flightNumber.orEmpty()
            flightNumberText.isVisible = detail.flightNumber != null
            fromCode.text = detail.fromCode
            toCode.text = detail.toCode
            fromCode.isClickable = detail.fromCode != ReplayIntentBuilder.UNKNOWN_CODE
            toCode.isClickable = detail.toCode != ReplayIntentBuilder.UNKNOWN_CODE
            fromCity.text = detail.fromCity
            toCity.text = detail.toCity
            airportNames.text = listOf(detail.fromAirportName, detail.toAirportName).filter(String::isNotBlank).joinToString(" → ")
            airportNames.isVisible = airportNames.text.isNotEmpty()
            dateText.text = detail.dateText
            timeRangeText.text = detail.timeRangeText
            statusText.text = detail.statusText
            statusText.setTextColor(requireContext().getColor(when {
                detail.statusText.contains("异常") -> UiR.color.semantic_error
                detail.status == FlightStatus.CANCELLED -> UiR.color.text_tertiary
                detail.status == FlightStatus.ONGOING -> UiR.color.accent_primary
                else -> UiR.color.semantic_success
            }))
            plannedRow.isVisible = !countUp
            plannedText.text = detail.plannedText
            actualText.text = detail.actualText
            diffText.isVisible = !countUp
            diffText.text = detail.diffText
            diffText.setTextColor(requireContext().getColor(if (detail.diffText == RecordDetailBuilder.DIFF_EXACT)
                UiR.color.text_secondary else UiR.color.semantic_warning))
            percentRow.isVisible = detail.completionPercent != null
            percentText.text = detail.completionPercent?.let { getString(R.string.record_detail_percent, it) }.orEmpty()
            distanceText.text = getString(R.string.record_detail_distance_value, detail.distanceText)
            cruiseEquivalentText.text = detail.cruiseEquivalentText
            cruiseEquivalentText.isVisible = detail.cruiseEquivalentText != "—"
            routeEquivalentText.text = detail.routeEquivalentText
            routeEquivalentText.isVisible = detail.routeEquivalentText != "—"
            sceneText.text = detail.sceneTitle?.let { getString(R.string.record_detail_scene, it) }.orEmpty()
            sceneText.isVisible = detail.sceneTitle != null
            seatText.text = detail.seat?.let { getString(R.string.record_detail_seat, it) }.orEmpty()
            seatText.isVisible = detail.seat != null
            seatText.isClickable = !countUp && detail.seat != null
            participantText.text = detail.participantCount?.let { getString(R.string.record_detail_participants, it) }.orEmpty()
            participantText.isVisible = detail.participantCount != null
            quoteBlock.isVisible = detail.quote != null
            quoteText.text = detail.quote.orEmpty()
            quoteAuthorText.isVisible = detail.quoteAuthor != null
            quoteAuthorText.text = detail.quoteAuthor?.let { "—— $it" }.orEmpty()
            btnBoardingPass.isEnabled = detail.canShare
            btnReplay.isEnabled = detail.canReplay && replayNavigation?.isActive != true
            btnReplay.alpha = if (detail.canReplay) 1f else .56f
            btnShare.isEnabled = detail.canShare && shareJob?.isActive != true
            btnDelete.isEnabled = detail.status == FlightStatus.COMPLETED || detail.status == FlightStatus.CANCELLED
            detailScroll.contentDescription = detail.spokenHeader
        }
    }

    private fun isViewing(source: RecordReplaySource): Boolean = view != null &&
        viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        viewModel.currentContent()?.source == source

    private fun notice(label: Int) { context?.let { Toast.makeText(it, label, Toast.LENGTH_SHORT).show() } }

    private fun showDeleteDialog(confirm: () -> Unit) {
        removalDialog?.dismiss()
        removalDialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.record_delete_confirm_title)
            .setMessage(R.string.record_delete_confirm_body)
            .setPositiveButton(R.string.record_delete_confirm_ok) { _, _ -> confirm() }
            .setNegativeButton(R.string.record_delete_confirm_cancel, null)
            .show()
    }

    internal fun requireViewForTest(): View = binding.root
    internal fun stateForTest(): RecordDetailState = viewModel.state.value
    internal fun detailViewModelForTest(): RecordDetailViewModel = viewModel

    companion object {
        const val ARG_RECORD_ID = "arg_record_id"
        const val ARG_RECORD_KIND = "arg_record_kind"
        const val ARG_RECORD_OWNER = "arg_record_owner"
        const val ARG_RECORD_START = "arg_record_start"
    }
}
