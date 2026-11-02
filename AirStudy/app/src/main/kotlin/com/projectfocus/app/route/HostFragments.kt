package com.projectfocus.app.route

import android.os.Bundle
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










class RecordListHostFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = FrameLayout(requireContext()).apply { id = R.id.route_container }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        install()
    }

    private fun install() {
        val container = view as? FrameLayout ?: return
        val nav = runCatching { findNavController() }.getOrNull()


        //   而不是做成导航图的另一个目的地。
        //
        //   理由：**它是记录列表的下级，不是并列的一个页面。**
        //   如果做成 nav 目的地，返回栈会变成
        //     首页 → 记录列表 → 记录详情
        //   而两个页面都在壳里，壳会经历 创建→销毁→创建 ——
        //   列表的滚动位置、搜索关键词全部丢失，用户从详情返回后
        //   发现自己回到了列表顶部、搜索条件没了。
        //
        //   用 `childFragmentManager` + `addToBackStack`：
        //     壳不动 → 列表 Fragment 保留在它自己的容器里（只是被 replace 掉 View）
        //     → 返回时恢复，View 重建但 ViewModel 还活着（list 的 keyword 还在）
        //

        //     所以**详情页必须自己处理返回键** —— 见下面的 installDetail()。
        FragmentRoutes.installRecordList(
            fm = childFragmentManager,
            containerId = container.id,
            onOpen = { item -> installDetail(nav, container.id, item.id) },
            onBack = { nav?.popBackStack() },
        )
    }



















    private fun installDetail(
        nav: androidx.navigation.NavController?,
        containerId: Int,
        recordId: String,
    ) {
        val fragment = com.projectfocus.feature.record.ui.RecordDetailFragment()
        fragment.apply {
            arguments = Bundle().apply {
                putString(
                    com.projectfocus.feature.record.ui.RecordDetailFragment.ARG_RECORD_ID,
                    recordId,
                )
            }


            //     用户会直接回到首页。这是本文件最容易写错的一行。
            onBack = { childFragmentManager.popBackStack() }
            // 重飞 / 分享现在是"明确说明"，素材到位后在这里接飞行页。

        }

        //
        //   原因：`replayIntent` 是 ViewModel 的 StateFlow，而 Fragment 的
        //   `viewModel` 是 `by lazy` —— 它在第一次被访问时才创建。
        //   在 fragment 实例上设一个"收到意图就导航"的回调，等于在
        //   一个还没有 ViewModel 的对象上装监听器。等 Fragment 真的
        //   `onViewCreated` 时再订阅才是安全的。
        //
        //   → `onReplay` 存成 Fragment 的属性，由它在 `onViewCreated` 里
        //     订阅 `viewModel.replayIntent` 后自己回调。
        fragment.onReplayIntent = { intent -> navigateToFlight(intent, nav) }

        // R6：能把详情页的登机牌推进导航图里的正式目的地。
        //   数据已经由详情页 prepareShare() 算好，这里只负责搬运。
        val navController = nav
        if (navController != null) {
            fragment.onOpenBoardingPass = { pass ->
                val args = Bundle().apply {
                    putSerializable(BoardingPassFragment.ARG_PASS, pass)
                }
                navController.navigate(R.id.action_recordList_to_boardingPass, args)
            }
        }
        childFragmentManager.commit {
            setReorderingAllowed(true)

            //
            //   `setCustomAnimations(enter, exit)` 的参数是资源 id，
            //   而 `TRANSIT_FRAGMENT_FADE` 的值恰好是 0x1003 —— 一个
            //   看起来很像资源 id 的整数。传进去编译通过、commit 通过，
            //   直到真正执行事务时才抛：
            //     Resources$NotFoundException: Unable to find resource ID #0x1003
            //   表现是"点记录卡片就闪退"。
            //
            //   要表达"淡入淡出"有两条正确路径：
            //     ① 传 anim 资源（本文件）—— 可控时长与曲线
            //     ② 用 `setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)`

            //     系统默认时长（约 300ms），且无法配置。
            setCustomAnimations(R.anim.pf_fade_in, R.anim.pf_fade_out)
            replace(containerId, fragment)
            addToBackStack("record_detail")
        }
    }































    private fun navigateToFlight(
        intent: com.projectfocus.feature.record.detail.ReplayIntent,
        nav: androidx.navigation.NavController?,
    ) {

        //   这是唯一能证明"传进去的到底是什么"的证据。
        Timber.i(
            "重飞请求：%s → %s，%d 分钟，分类=%s",
            intent.departureCode ?: "(自由)",
            intent.destinationCode ?: "(自由)",
            intent.durationMinutes,
            intent.categoryId?.toString() ?: "(默认)",
        )
        if (nav == null) {
            // 理论上不会（壳一定在 NavHost 里）。留一句日志而不是崩溃。
            Timber.w("NavController 不可用，重飞被忽略")
            return
        }
        android.widget.Toast.makeText(
            requireContext(),
            R.string.route_replay_pending,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
