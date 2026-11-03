package com.projectfocus.feature.record.ui

import android.graphics.Rect
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.projectfocus.core.network.di.TokenStore
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.list.RecordPageState
import com.projectfocus.feature.record.list.RecordPageViewModel
import com.projectfocus.feature.record.list.RecordPeriod
import com.projectfocus.feature.record.list.RecordStatusFilter
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.FlightStatus
import com.projectfocus.feature.record.list.RecordSceneSaveState
import com.projectfocus.feature.record.list.RecordSceneFailure
import com.projectfocus.service.focus.history.HistoryAccount
import com.projectfocus.feature.record.share.RecordSharePreview
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.Job
import kotlinx.coroutines.yield

@AndroidEntryPoint
class RecordPageFragment : Fragment(R.layout.pf_record_page) {
    private val viewModel: RecordPageViewModel by viewModels(ownerProducer = { parentFragment ?: this })
    private var adapter: RecordPageAdapter? = null
    private var preview: RecordSharePreview? = null
    private var rendered: RecordPageState? = null
    private var renderRevision = 0L
    private var bindingFilters = false
    private var scenePicker: AutoCloseable? = null
    private var sceneTarget: Pair<HistoryAccount, RecordItem>? = null
    private var sceneOpenRevision = 0L
    private var sceneOpenJob: Job? = null
    private var sceneNoticeJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { preview?.close() }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        val content = requireActivity().findViewById<ViewGroup>(android.R.id.content)
        preview = RecordSharePreview(requireContext(), content, viewLifecycleOwner.lifecycleScope,
            canShow = { view === this.view && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
            visibilityChanged = { back.isEnabled = it })
        adapter = RecordPageAdapter(onOpen = { card, item ->
            val state = rendered?.takeIf { it.ready && it.items.any { row -> row == item } } ?: return@RecordPageAdapter
            preview?.open(card, item, requireNotNull(state.account))
        }, onSceneEdit = if (parentFragment is RecordScenePickerHost) ::openScene else null,
            canEdit = { !viewModel.sceneSave.value.saving })
        val list = view.findViewById<RecyclerView>(R.id.pf_record_page_flight_records_list)
        list.layoutManager = GridLayoutManager(requireContext(), maxOf(1, resources.configuration.screenWidthDp / 360))
        list.isNestedScrollingEnabled = false
        list.itemAnimator = null
        list.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(outRect: Rect, child: View, parent: RecyclerView, state: RecyclerView.State) {
                val spacing = (16 * resources.displayMetrics.density + 0.5f).toInt()
                val position = parent.getChildAdapterPosition(child)
                val span = (parent.layoutManager as GridLayoutManager).spanCount
                if (position == RecyclerView.NO_POSITION) { outRect.setEmpty(); return }
                val column = position % span
                outRect.set(spacing - column * spacing / span, if (position < span) spacing else 0,
                    (column + 1) * spacing / span, spacing)
            }
        })
        list.adapter = adapter
        val status = view.findViewById<MaterialAutoCompleteTextView>(R.id.pf_record_page_spinner_status)
        val labels = listOf(R.string.pf_record_page_status_all, R.string.pf_record_page_status_arrived,
            R.string.pf_record_page_status_in_progress, R.string.pf_record_page_status_cancelled).map(::getString)
        status.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels))
        status.setOnItemClickListener { _, _, position, _ ->
            RecordStatusFilter.entries.getOrNull(position)?.let(viewModel::selectStatus)
        }
        view.findViewById<TabLayout>(R.id.pf_record_page_record_tabs).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (!bindingFilters && rendered?.ready == true) RecordPeriod.entries.getOrNull(tab.position)?.let(viewModel::selectPeriod)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        view.findViewById<View>(R.id.pf_record_page_btn_prev_page).setOnClickListener { viewModel.previous() }
        view.findViewById<View>(R.id.pf_record_page_btn_next_page).setOnClickListener { viewModel.next() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.state, viewModel.sceneSave, TokenStore.identities) { state, _, identity -> state to identity }
                    .collect { (state, identity) ->
                        if (identity != TokenStore.snapshot()) return@collect
                        if (rendered?.account?.isCurrent() != true) {
                            preview?.close()
                            closeScene()
                        }
                        render(state)
                    }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.sceneSave.collect(::showSceneFailure)
            }
        }
    }

    override fun onStart() { super.onStart(); viewModel.onVisible() }
    override fun onPause() {
        closeScene()
        sceneNoticeJob?.cancel()
        preview?.close()
        super.onPause()
    }
    override fun onStop() {
        ++renderRevision
        closeScene()
        preview?.close()
        rendered = null
        adapter?.replace(emptyList())
        super.onStop()
    }

    private fun render(state: RecordPageState) {
        val target = view ?: return
        val revision = ++renderRevision
        val expected = TokenStore.snapshot()
        val current = state.account?.let { it.generation == expected.generation && it.scope == TokenStore.storageScope(expected) } == true
        val actual = if (current) state else RecordPageState(query = state.query)
        viewLifecycleOwner.lifecycleScope.launch {
            yield()
            while (isActive && view === target && renderRevision == revision) {
                when (TokenStore.tryWithSnapshotLease(expected) { renderCurrent(target, actual) }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun renderCurrent(root: View, state: RecordPageState) {
        rendered = state
        sceneTarget?.let { (account, item) ->
            if (!state.ready || state.account != account || state.items.none { row ->
                row.key == item.key && row.sceneRevision == item.sceneRevision &&
                    row.recordedAtWallMs == item.recordedAtWallMs && row.status == FlightStatus.COMPLETED
            }) closeScene()
        }
        preview?.keepFor(state.account, state.items)
        adapter?.replace(if (state.ready) state.items else emptyList())
        bindingFilters = true
        try {
            val labels = listOf(R.string.pf_record_page_status_all, R.string.pf_record_page_status_arrived,
                R.string.pf_record_page_status_in_progress, R.string.pf_record_page_status_cancelled)
            root.findViewById<MaterialAutoCompleteTextView>(R.id.pf_record_page_spinner_status).apply {
                setText(getString(labels[state.query.status.ordinal]), false)
                isEnabled = state.ready
            }
            root.findViewById<TabLayout>(R.id.pf_record_page_record_tabs).apply {
                if (selectedTabPosition != state.query.period.ordinal) getTabAt(state.query.period.ordinal)?.select()
                isEnabled = state.ready
            }
        } finally { bindingFilters = false }
        val hint = root.findViewById<TextView>(R.id.pf_record_page_empty_records_hint)
        hint.visibility = if (!state.ready || state.items.isEmpty()) View.VISIBLE else View.GONE
        hint.setText(when {
            state.failed -> R.string.pf_record_page_read_failed
            !state.ready -> R.string.pf_record_page_loading
            else -> R.string.pf_record_page_no_records
        })
        hint.setOnClickListener(if (state.failed) View.OnClickListener { viewModel.retry() } else null)
        root.findViewById<View>(R.id.pf_record_page_stats_progress_container).visibility = View.GONE
        root.findViewById<View>(R.id.pf_record_page_page_indicator_container).visibility = if (state.ready && state.matchedCount > 0) View.VISIBLE else View.GONE
        root.findViewById<TextView>(R.id.pf_record_page_text_page_indicator).text = getString(R.string.pf_record_page_page_format, state.page)
        root.findViewById<View>(R.id.pf_record_page_btn_prev_page).isEnabled = state.ready && state.page > 1
        root.findViewById<View>(R.id.pf_record_page_btn_next_page).isEnabled = state.ready && state.page < state.pageCount
    }

    private fun openScene(anchor: View, item: RecordItem) {
        val root = view ?: return
        val host = parentFragment as? RecordScenePickerHost ?: return
        val state = rendered?.takeIf { it.ready && it.items.any { row -> row == item } } ?: return
        if (item.status != FlightStatus.COMPLETED || item.recordedAtWallMs == null || viewModel.sceneSave.value.saving) return
        val account = requireNotNull(state.account)
        closeScene()
        preview?.close()
        sceneTarget = account to item
        val revision = sceneOpenRevision
        val identity = TokenStore.snapshot()
        fun current(): Boolean = view === root && account.isCurrent() &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            sceneOpenRevision == revision && rendered?.let { displayed ->
                displayed.ready && displayed.account == account && displayed.items.any { row ->
                    row.key == item.key && row.sceneRevision == item.sceneRevision &&
                        row.recordedAtWallMs == item.recordedAtWallMs && row.status == FlightStatus.COMPLETED
                }
            } == true
        sceneOpenJob = viewLifecycleOwner.lifecycleScope.launch {
            yield()
            while (isActive && current()) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    if (current() && anchor.isAttachedToWindow) {
                        scenePicker = host.showRecordScenePicker(anchor, item, ::current) { selected ->
                            if (current()) viewModel.changeScene(item, selected)
                        }
                    }
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> { closeScene(); return@launch }
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    private fun closeScene() {
        ++sceneOpenRevision
        sceneOpenJob?.cancel()
        sceneOpenJob = null
        val previous = scenePicker
        scenePicker = null
        sceneTarget = null
        previous?.close()
    }

    private fun showSceneFailure(save: RecordSceneSaveState) {
        sceneNoticeJob?.cancel()
        val failure = save.failure ?: return
        val account = save.target?.account ?: return
        val root = view ?: return
        val identity = TokenStore.snapshot()
        sceneNoticeJob = viewLifecycleOwner.lifecycleScope.launch {
            yield()
            while (isActive && view === root && viewModel.sceneSave.value == save && account.isCurrent() &&
                viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                when (TokenStore.tryWithSnapshotLease(identity) {
                    viewModel.acknowledgeSceneFailure(save.attempt)
                    android.widget.Toast.makeText(requireContext(), when (failure) {
                        RecordSceneFailure.WRITE -> R.string.pf_record_page_scene_save_failed
                        RecordSceneFailure.CHANGED -> R.string.pf_record_page_scene_changed
                        RecordSceneFailure.NOT_EDITABLE -> R.string.pf_record_page_scene_not_editable
                    }, android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    override fun onDestroyView() {
        ++renderRevision
        closeScene()
        sceneNoticeJob?.cancel()
        preview?.close()
        preview = null
        view?.findViewById<RecyclerView>(R.id.pf_record_page_flight_records_list)?.adapter = null
        adapter = null
        rendered = null
        super.onDestroyView()
    }
}
