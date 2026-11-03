package com.projectfocus.app.route

import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.projectfocus.core.network.di.TokenStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import com.projectfocus.feature.record.R as RecordR
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.isNotEmpty
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.navigation.fragment.findNavController
import com.projectfocus.app.R
import com.projectfocus.feature.airport.ui.AirportSearchFragment
import com.projectfocus.feature.airport.ui.AirportSearchReferenceArgs
import com.projectfocus.feature.completion.pass.BoardingPassFragment
import com.projectfocus.feature.record.ui.RecordListFragment
import timber.log.Timber






























class AirportSearchHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply {

        //   旋转屏幕后 `generateViewId()` 会给出**另一个** id，
        //   而 FragmentManager 恢复子 Fragment 时找的是**旧 id** ——
        //   子 Fragment 会"恢复失败但也不报错"，屏幕一片空白。
        id = R.id.route_container
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        //   非空 savedInstanceState 时，子 Fragment 会被 FragmentManager
        //   自动恢复（连同回调？不，回调是实例字段，恢复后是默认值）。
        //

        //     旋转屏幕后恢复出来的 AirportSearchFragment 是**框架重建的新实例**，
        //     `onAirportSelected` 回到了默认的"什么都不做"。
        //     用户旋转后再选机场 → 点了没反应。
        //
        //   → 所以**每次**都重装（不是只在首次）。代价是旋转后搜索关键词丢失
        //     （新实例的 ViewModel 里 keyword 也是空的），但"点了没反应"更糟。

        //       那是 M3 的活，这里先选"可用"而不是"完美"。
        install(force = true)
    }

    private fun install(force: Boolean) {
        val container = view as? FrameLayout ?: return
        if (!force && container.isNotEmpty()) return

        val nav = runCatching { findNavController() }.getOrNull()

        FragmentRoutes.installAirportSearch(

            //   真页面要挂进壳的 FrameLayout，而 Activity 的 FM 只认识
            //   `nav_host_container` 那一层。用错了 → 屏幕一片空白，不报错。
            fm = childFragmentManager,
            containerId = container.id,

            //   这是唯一一个"默认行为有意义"的情况（首页主要用途是选目的地）。
            mode = arguments?.getString(RouteArgs.ARG_MODE)
                ?: RouteArgs.MODE_PICK_DESTINATION,
            referencePoint = AirportSearchReferenceArgs.readFrom(arguments),
            onPicked = { code, origin ->



                nav?.previousBackStackEntry?.savedStateHandle?.apply {
                    set(KEY_PICKED, true)
                    set(KEY_AIRPORT_CODE, code)
                    set(KEY_PICKED_ORIGIN, origin)
                }
                nav?.popBackStack()
            },
            onBack = { nav?.popBackStack() },
        )
    }

    companion object {
        /** 结果回传键。首页 `currentBackStackEntry.savedStateHandle` 里取 */
        const val KEY_PICKED = "airport_picked"


        const val KEY_AIRPORT_CODE = "airport_picked_code"


        const val KEY_PICKED_ORIGIN = "airport_picked_origin"
    }
}










@dagger.hilt.android.AndroidEntryPoint
class RecordListHostFragment : Fragment(com.projectfocus.feature.record.R.layout.pf_record_screen),
    com.projectfocus.feature.record.ui.RecordScenePickerHost {
    @javax.inject.Inject lateinit var sceneAccess: com.projectfocus.core.network.content.SceneAccessResolver
    @javax.inject.Inject lateinit var sceneContent: com.projectfocus.core.network.content.SceneContentChecker
    private val viewModel: com.projectfocus.feature.record.list.RecordPageViewModel by viewModels()
    private var tabs: com.google.android.material.tabs.TabLayoutMediator? = null
    private var renderRevision = 0L

    override fun showRecordScenePicker(anchor: View, item: com.projectfocus.feature.record.list.RecordItem,
        canShow: () -> Boolean, onSelected: (com.projectfocus.core.model.flight.PlannedScene) -> Unit): AutoCloseable? {
        val activity = requireActivity() as? androidx.appcompat.app.AppCompatActivity ?: return null
        return com.projectfocus.app.scene.RecordScenePickerSession.open(activity, anchor, item, canShow,
            onSelected, sceneAccess, sceneContent)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(RecordR.id.pf_record_page_btn_back).setOnClickListener { findNavController().popBackStack() }
        val pager = view.findViewById<ViewPager2>(RecordR.id.pf_record_page_main_view_pager)
        pager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> com.projectfocus.feature.record.ui.RecordPageFragment()
                else -> com.projectfocus.feature.stats.StatsFragment().apply {
                    arguments = Bundle().apply { putBoolean(com.projectfocus.feature.stats.StatsFragment.ARG_EMBEDDED, true) }
                }
            }
        }
        val tabLayout = view.findViewById<com.google.android.material.tabs.TabLayout>(RecordR.id.pf_record_page_main_tabs)
        tabLayout.setTabTextColors(ContextCompat.getColor(requireContext(), RecordR.color.pf_record_page_record_main_tab_unselected),
            ContextCompat.getColor(requireContext(), RecordR.color.pf_record_page_record_main_tab_selected))
        tabLayout.setSelectedTabIndicatorColor(ContextCompat.getColor(requireContext(), RecordR.color.pf_record_page_record_main_tab_indicator))
        tabs = com.google.android.material.tabs.TabLayoutMediator(tabLayout, pager) { tab, position ->
            tab.setText(if (position == 0) RecordR.string.pf_record_page_record_tab_records else RecordR.string.pf_record_page_record_tab_statistics)
        }.also { it.attach() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::renderTotal) }
                launch { TokenStore.identities.collect { if (it == TokenStore.snapshot()) renderTotal(viewModel.state.value) } }
            }
        }
    }

    private fun renderTotal(state: com.projectfocus.feature.record.list.RecordPageState) {
        val root = view ?: return
        val revision = ++renderRevision
        val expected = TokenStore.snapshot()
        val account = state.account
        val distance = if (state.ready && account != null && account.generation == expected.generation &&
            account.scope == TokenStore.storageScope(expected)) state.completedDistanceKm else null
        viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && view === root && revision == renderRevision) {
                when (TokenStore.tryWithSnapshotLease(expected) {
                    root.findViewById<android.widget.TextView>(RecordR.id.pf_record_page_text_subtitle).text = distance?.let {
                        getString(RecordR.string.pf_record_page_total_flown_format, com.projectfocus.feature.record.list.RecordListBuilder.km(it))
                    } ?: getString(RecordR.string.pf_record_page_total_loading)
                }) {
                    is TokenStore.ImmediateSnapshot.Value -> return@launch
                    TokenStore.ImmediateSnapshot.Obsolete -> return@launch
                    TokenStore.ImmediateSnapshot.Busy -> delay(16)
                }
            }
        }
    }

    override fun onStop() {
        ++renderRevision
        view?.findViewById<android.widget.TextView>(RecordR.id.pf_record_page_text_subtitle)
            ?.setText(RecordR.string.pf_record_page_total_loading)
        super.onStop()
    }

    override fun onDestroyView() {
        ++renderRevision
        tabs?.detach()
        tabs = null
        view?.findViewById<ViewPager2>(RecordR.id.pf_record_page_main_view_pager)?.adapter = null
        super.onDestroyView()
    }
}
