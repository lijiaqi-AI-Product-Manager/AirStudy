package com.projectfocus.app.route

import android.net.Uri
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.NavGraph
import androidx.navigation.fragment.FragmentNavigator
import androidx.navigation.fragment.NavHostFragment
import androidx.test.core.app.ActivityScenario
import com.google.common.truth.Truth.assertThat
import com.projectfocus.app.MainActivity
import com.projectfocus.app.R
import com.projectfocus.feature.airport.ui.AirportSearchFragment
import com.projectfocus.feature.record.ui.RecordListFragment
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config





































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NavGraphTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()


    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        idle()
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun nav(): NavController {
        lateinit var c: NavController
        scenario!!.onActivity { act ->
            val host = act.supportFragmentManager
                .findFragmentById(R.id.nav_host_container) as NavHostFragment
            c = host.navController
        }
        return c
    }

    private fun graph(): NavGraph = nav().graph













    private fun NavGraph.classNameOf(id: Int): String? =
        (findNode(id) as? FragmentNavigator.Destination)?.className

    // ═══════════════════════════════════════════════════════════
    // 图被真的 inflate 了
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `导航图的起点是权限引导页`() {

        //   起点写成 home 的话，用户在系统设置里撤销权限后会直接进主页，
        //   然后在开始飞行时崩在 startForegroundService 上。
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.permissionGuideFragment)
    }

    @Test
    fun `导航图被真实 inflate 成 FragmentNavigator_Destination`() {

        //   `TestNavHostController`，这条立刻红 —— 而不是让
        //   "类名写错测不出来"的问题悄悄回来。
        assertThat(graph().findNode(R.id.homeFragment))
            .isInstanceOf(FragmentNavigator.Destination::class.java)
    }

    // ═══════════════════════════════════════════════════════════
    // 类名
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `机场搜索目的地指向宿主壳而不是真页面`() {

        //
        //   真页面需要在创建时装回调（`onAirportSelected`），
        //   而 `android:name` 由 NavController 反射 `newInstance()` ——
        //   拿不到那个时机。必须走壳。
        //

        //     编译通过、导航也能到，但 `onAirportSelected` 是默认空实现 ——
        //     用户选完机场，**点了没反应**，且没有任何报错。
        assertThat(graph().classNameOf(R.id.airportSearchFragment))
            .isEqualTo(AirportSearchHostFragment::class.java.name)
        assertThat(graph().classNameOf(R.id.airportSearchFragment))
            .isNotEqualTo(AirportSearchFragment::class.java.name)
    }

    @Test
    fun `飞行记录目的地指向宿主壳而不是真页面`() {
        assertThat(graph().classNameOf(R.id.recordListFragment))
            .isEqualTo(RecordListHostFragment::class.java.name)
        assertThat(graph().classNameOf(R.id.recordListFragment))
            .isNotEqualTo(RecordListFragment::class.java.name)
    }

    @Test
    fun `每个 fragment 目的地的类名都能真的被加载且有无参构造`() {

        //   导航图里 `android:name` 写错一个字母，会在用户第一次导航到那页时
        //   `ClassNotFoundException` —— 堆栈崩在 NavController 内部，

        val ids = listOf(
            R.id.permissionGuideFragment,
            R.id.homeFragment,
            R.id.airportSearchFragment,
            R.id.recordListFragment,
        )
        for (id in ids) {
            val name = graph().classNameOf(id)
            assertThat(name).isNotNull()
            val cls = Class.forName(name!!, false, javaClass.classLoader)
            assertThat(Fragment::class.java.isAssignableFrom(cls)).isTrue()

            //   InstantiationException，同样崩在 NavController 内部。
            assertThat(cls.getDeclaredConstructor()).isNotNull()
        }
    }

    // ═══════════════════════════════════════════════════════════
    // action 与导航
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `引导页有通往主页的 action`() {
        val a = graph().findNode(R.id.permissionGuideFragment)
            ?.getAction(R.id.action_permission_to_home)
        assertThat(a?.destinationId).isEqualTo(R.id.homeFragment)
    }

    @Test
    fun `主页有通往机场搜索的 action`() {
        val a = graph().findNode(R.id.homeFragment)?.getAction(R.id.action_home_to_airport)
        assertThat(a?.destinationId).isEqualTo(R.id.airportSearchFragment)
    }

    @Test
    fun `主页有通往飞行记录的 action`() {
        val a = graph().findNode(R.id.homeFragment)?.getAction(R.id.action_home_to_record)
        assertThat(a?.destinationId).isEqualTo(R.id.recordListFragment)
    }

    @Test
    fun `从主页能导航到机场搜索并且壳真的被创建`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportSearchFragment)
        assertThat(currentHostFragment()).isInstanceOf(AirportSearchHostFragment::class.java)
    }

    @Test
    fun `从主页能导航到飞行记录并且壳真的被创建`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.recordListFragment)
        assertThat(currentHostFragment()).isInstanceOf(RecordListHostFragment::class.java)
    }

    @Test
    fun `从机场搜索按返回回到主页而不是退出`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        nav().popBackStack()
        idle()


        //   真机上还依赖 MainActivity 的 `setPrimaryNavigationFragment`
        //   （没有它，返回事件根本不进 NavController，直接 finish）。
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.homeFragment)
    }

    @Test
    fun `从飞行记录按返回回到主页`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()

        nav().popBackStack()
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.homeFragment)
    }

    @Test
    fun `连点两次导航第二次失败且栈不加深`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        val thrown = runCatching { nav().navigate(R.id.action_home_to_airport) }
        assertThat(thrown.isFailure).isTrue()

        idle()
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportSearchFragment)

    }

    @Test
    fun `机场搜索页是叶子节点 从这里导航不到别处`() {

        //   `NavDestination.actions` 收成 private，而"用户跳不走"才是要的事实。
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        val thrown = runCatching { nav().navigate(R.id.action_permission_to_home) }
        assertThat(thrown.isFailure).isTrue()

        idle()
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportSearchFragment)
    }

    @Test
    fun `飞行记录页是叶子节点 从这里导航不到别处`() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()

        val thrown = runCatching { nav().navigate(R.id.action_home_to_airport) }
        assertThat(thrown.isFailure).isTrue()

        idle()
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.recordListFragment)
    }

    // ═══════════════════════════════════════════════════════════
    // 壳与真页面的装配
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `机场搜索壳会在自己的容器里装上真页面`() {

        //   壳不画界面，真页面必须被装进壳的 `route_container`。
        //   如果 `install()` 没跑（比如 commitNow 时机不对），
        //   用户看到的就是**一片空白** —— 不崩、不报错。
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        val host = currentHostFragment() as AirportSearchHostFragment
        assertThat(host.childFragmentManager.fragments.firstOrNull())
            .isInstanceOf(AirportSearchFragment::class.java)
    }

    @Test
    fun recordsHostInstallsTheRecordsAndStatisticsPager() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()
        val host = currentHostFragment() as RecordListHostFragment
        val pager = recordsPager(host)
        assertThat(pager.adapter?.itemCount).isEqualTo(2)
        assertThat(host.childFragmentManager.fragments.any { it is com.projectfocus.feature.record.ui.RecordPageFragment }).isTrue()
    }

    @Test
    fun `壳的容器用的是编译期常量 id`() {

        //

        //     竖屏 → 0x7f080001，子 Fragment 以它被 add
        //     旋转 → onCreateView 再执行 → 拿到 0x7f080002（必然不同）
        //     恢复 → FragmentManager 找的是**旧 id** → 找不到 → 白屏
        //   不崩、不报错、不打日志。
        //
        //   → 容器 id 必须是 `@+id/route_container` 这个编译期常量。
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        assertThat(currentHostFragment().view?.id).isEqualTo(R.id.route_container)
    }

    @Test
    fun `旋转后壳里仍然是真页面而不是空白`() {

        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_airport)
        idle()

        scenario!!.recreate()
        idle()

        // 重建后当前目的地应当还在机场搜索（NavHost 恢复返回栈）
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportSearchFragment)
        val host = currentHostFragment()
        assertThat(host).isInstanceOf(AirportSearchHostFragment::class.java)

        assertThat(host.childFragmentManager.fragments.firstOrNull())
            .isInstanceOf(AirportSearchFragment::class.java)
    }

    // ═══════════════════════════════════════════════════════════
    // 无障碍 label
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `每个目的地都有可读的无障碍标签`() {

        //   缺失时退化成"未命名"，TalkBack 用户听到一串空话。
        val ids = listOf(
            R.id.permissionGuideFragment,
            R.id.homeFragment,
            R.id.airportSearchFragment,
            R.id.recordListFragment,
        )
        for (id in ids) {
            val label = graph().findNode(id)?.label?.toString()
            assertThat(label).isNotNull()
            assertThat(label!!.trim()).isNotEmpty()

            assertThat(label).doesNotContain("nav_label")
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 参数键
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `路由参数键不占用 AndroidX 保留前缀`() {

        //   会出现"写进去取不出来"的诡异现象。
        assertThat(RouteArgs.ARG_MODE).doesNotContain("android:")
        assertThat(RouteArgs.ARG_MODE).doesNotContain("androidx:")
        assertThat(AirportSearchHostFragment.KEY_PICKED).doesNotContain("android:")
    }

    @Test
    fun `三个 mode 常量互不相同`() {

        //   用户在设置里改出发地，结果改的是目的地。
        val modes = setOf(
            RouteArgs.MODE_PICK_DESTINATION,
            RouteArgs.MODE_PICK_ORIGIN,
            RouteArgs.MODE_HISTORY,
        )
        assertThat(modes).hasSize(3)
    }

    // ═══════════════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════════════

    private fun currentHostFragment(): Fragment {
        lateinit var f: Fragment
        scenario!!.onActivity { act ->
            val host = act.supportFragmentManager
                .findFragmentById(R.id.nav_host_container) as NavHostFragment
            f = host.childFragmentManager.fragments.first()
        }
        return f
    }

    // ═══════════════════════════════════════════════════════════
    // 记录列表 → 记录详情（壳内下钻）
    // ═══════════════════════════════════════════════════════════
    //

    //   详情页挂在 `RecordListHostFragment.childFragmentManager` 上，
    //   用 `addToBackStack` 管理，NavController 完全不知道它的存在。
    //
    //   这正是最容易漏测的地方：导航图测试全绿，但详情页根本打不开
    //   （`onRecordOpen` 是个空实现），用户点卡片没反应 —— 而无一条测试会红。


    @Test
    fun switchingRecordTabsKeepsTheRouteAndCreatesTheStatisticsPage() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()
        val host = currentHostFragment() as RecordListHostFragment
        recordsPager(host).setCurrentItem(1, false)
        idle()
        recordsPager(host)
        assertThat(host.childFragmentManager.fragments.any { it is com.projectfocus.feature.stats.StatsFragment }).isTrue()
        assertThat(host.childFragmentManager.backStackEntryCount).isEqualTo(0)
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.recordListFragment)
    }

    @Test
    fun recordHeaderBackReturnsToItsHomeRouteFromTheStatisticsTab() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()
        val host = currentHostFragment() as RecordListHostFragment
        recordsPager(host).setCurrentItem(1, false)
        idle()
        host.requireView().findViewById<android.view.View>(com.projectfocus.feature.record.R.id.pf_record_page_btn_back).performClick()
        idle()
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.homeFragment)
    }

    @Test
    fun recordPagerRestoresTheSelectedTabAfterRecreation() {
        nav().navigate(R.id.action_permission_to_home)
        idle()
        nav().navigate(R.id.action_home_to_record)
        idle()
        recordsPager(currentHostFragment() as RecordListHostFragment).setCurrentItem(1, false)
        idle()
        scenario!!.recreate()
        idle()
        val pager = recordsPager(currentHostFragment() as RecordListHostFragment)
        assertThat(pager.currentItem).isEqualTo(1)
        assertThat(nav().currentDestination?.id).isEqualTo(R.id.recordListFragment)
    }

    private fun recordsPager(host: RecordListHostFragment): androidx.viewpager2.widget.ViewPager2 {
        val root = host.requireView()
        root.measure(android.view.View.MeasureSpec.makeMeasureSpec(432, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(936, android.view.View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 432, 936)
        idle()
        return root.findViewById(com.projectfocus.feature.record.R.id.pf_record_page_main_view_pager)
    }

    @Test
    fun `小组件动作常量的值与文档一致`() {

        //   如果确实要改，必须同时改：
        //     · widget 里所有 actionStartActivity 的 parameters
        //     · 主界面读取 EXTRA_ACTION 的那一处
        //     · 这张断言（改的时候会看到这段注释）
        assertThat(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ACTION)
            .isEqualTo("com.projectfocus.extra.WIDGET_ACTION")
        assertThat(com.projectfocus.widget.WidgetEntryActivity.ACTION_QUICK_START)
            .isEqualTo("quick_start")
        assertThat(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ENTRY_SOURCE)
            .isEqualTo("com.projectfocus.extra.ENTRY_SOURCE")
        assertThat(com.projectfocus.widget.WidgetEntryActivity.SOURCE_WIDGET).isEqualTo("widget")
    }

    @Test
    fun `EXTRA_ACTION 与 EXTRA_ENTRY_SOURCE 不是同一个键`() {

        //   而这正是快捷开始小组件需要同时知道的两件事。
        assertThat(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ACTION)
            .isNotEqualTo(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ENTRY_SOURCE)
    }












    @Test
    fun `中转页把 action 转发给主界面`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(ctx, com.projectfocus.widget.WidgetEntryActivity::class.java)
            .putExtra(
                com.projectfocus.widget.WidgetEntryActivity.EXTRA_ACTION,
                com.projectfocus.widget.WidgetEntryActivity.ACTION_QUICK_START,
            )

        val activity = org.robolectric.Robolectric
            .buildActivity(com.projectfocus.widget.WidgetEntryActivity::class.java, intent)
            .setup()
            .get()

        val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()
        assertThat(started.getStringExtra(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ACTION))
            .isEqualTo(com.projectfocus.widget.WidgetEntryActivity.ACTION_QUICK_START)
        assertThat(started.getStringExtra(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ENTRY_SOURCE))
            .isEqualTo(com.projectfocus.widget.WidgetEntryActivity.SOURCE_WIDGET)

        //   而中转页自己也是 Activity —— 但它启动的是**另一个任务栈**里的主界面，
        //   少了这个 flag 在某些 ROM 上会抛 AndroidRuntimeException。
        assertThat(started.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            .isNotEqualTo(0)
        assertThat(started.flags and android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .isNotEqualTo(0)

        assertThat(started.component?.packageName).isEqualTo(ctx.packageName)
        assertThat(started.component?.className)
            .isNotEqualTo(com.projectfocus.widget.WidgetEntryActivity::class.java.name)
    }

    @Test
    fun `中转页在没有 action 时不塞空串`() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val intent = android.content.Intent(ctx, com.projectfocus.widget.WidgetEntryActivity::class.java)

        val activity = org.robolectric.Robolectric
            .buildActivity(com.projectfocus.widget.WidgetEntryActivity::class.java, intent)
            .setup()
            .get()

        val started = org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
        assertThat(started).isNotNull()

        assertThat(started.hasExtra(com.projectfocus.widget.WidgetEntryActivity.EXTRA_ACTION))
            .isFalse()
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    @Test
    fun `airport 深链落到机场详情并带 code`() {
        nav().navigate(R.id.homeFragment)
        nav().navigate(Uri.parse("projectfocus://airport/PEK"))
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportDetailFragment)
        assertThat(nav().currentBackStackEntry?.arguments?.getString("code"))
            .isEqualTo("PEK")
    }

    @Test
    fun `airport report 深链把 report 解析为 true`() {
        nav().navigate(R.id.homeFragment)
        nav().navigate(Uri.parse("projectfocus://airport/PEK?report=true"))
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.airportDetailFragment)
        assertThat(nav().currentBackStackEntry?.arguments?.getBoolean("report"))
            .isTrue()
    }

    @Test
    fun `airport 深链返回后回到来源页`() {
        nav().navigate(R.id.homeFragment)
        nav().navigate(Uri.parse("projectfocus://airport/PEK"))
        idle()

        nav().popBackStack()
        idle()

        assertThat(nav().currentDestination?.id).isEqualTo(R.id.homeFragment)
    }
}
