package com.projectfocus.feature.airport.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.projectfocus.core.ui.nav.AirportNavigator
import com.projectfocus.feature.airport.R
import com.projectfocus.feature.airport.databinding.FragmentAirportSearchBinding
import com.projectfocus.feature.airport.search.AirportSearchState
import com.projectfocus.feature.airport.search.AirportSearchViewModel
import com.projectfocus.feature.airport.search.IdleRecommendation
import com.projectfocus.feature.airport.search.IdleRecommender
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


























































@AndroidEntryPoint
open class AirportSearchFragment : Fragment() {

    private var _binding: FragmentAirportSearchBinding? = null







    protected val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 后被访问" }








    protected open fun createViewModel(): AirportSearchViewModel =
        androidx.lifecycle.ViewModelProvider(this)[AirportSearchViewModel::class.java]

    private val viewModel: AirportSearchViewModel by lazy { createViewModel() }

    private lateinit var adapter: AirportSearchAdapter

    /** 用户选中机场。默认什么也不做 —— 由宿主替换。 */
    open var onAirportSelected: (com.projectfocus.core.model.airport.Airport) -> Unit = {}

    /** 点返回。默认 `popBackStack` —— 直接放进 Activity 里也能用。 */
    open var onBack: () -> Unit = {
        parentFragmentManager.takeIf { it.backStackEntryCount > 0 }?.popBackStack()
    }

    /** 点重试（Empty 态）。默认重新建索引。 */
    open var onRetry: () -> Unit = {
        viewLifecycleOwner.lifecycleScope.launch { viewModel.ensureIndex() }
    }

    // ─────────────────────────────────────────────────────────
    // 生命周期
    // ─────────────────────────────────────────────────────────

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentAirportSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupSearchInput()
        setupActions()
        applyModeHint()
        com.projectfocus.core.ui.sky.SkySurfaces.card(binding.skyAirportSearchField, radius = 24f, elevation = 3f)
        com.projectfocus.core.ui.sky.SkySurfaces.action(binding.btnRetry)
        binding.btnRetry.setTextColor(requireContext().getColor(android.R.color.white))
        listOf(binding.btnBack, binding.btnClear, binding.btnRetry).forEach {
            com.projectfocus.core.ui.sky.SkyPressFeedback.install(it)
        }

        //   见 onChipSelected 的注释（漏了收键盘）。
        binding.idlePanel.onAirportClick = ::onChipSelected
        observeState()
        // Loading must correspond to work that has actually started. Waiting for focus
        // left the initial screen in Loading indefinitely, including its recommendations.
        viewLifecycleOwner.lifecycleScope.launch { viewModel.ensureIndex() }
    }

    override fun onDestroyView() {
        super.onDestroyView()

        //   还回调 onBindViewHolder 去 bind 正在销毁的 View。
        binding.resultList.adapter = null
        _binding = null
    }

    // ─────────────────────────────────────────────────────────
    // 装配
    // ─────────────────────────────────────────────────────────

    private fun applyModeHint() {
        // 宿主通过 arg_mode 传 "pick_origin"/"pick_destination"；feature 模块
        // 不能依赖 app 的 RouteArgs，所以这里用同一组字符串常量（有测试守住）。
        val hintRes = when (arguments?.getString("arg_mode")) {
            "pick_origin" -> R.string.airport_search_hint_origin
            "pick_destination" -> R.string.airport_search_hint_destination
            else -> R.string.airport_search_hint
        }
        binding.searchInput.setHint(hintRes)
        val mode = arguments?.getString("arg_mode")
        binding.airportPageTitle.setText(when (mode) {
            "pick_origin" -> R.string.sky_airport_origin_title
            "pick_destination" -> R.string.sky_airport_destination_title
            else -> R.string.sky_airport_explore_title
        })
        binding.airportPageSubtitle.setText(when (mode) {
            "pick_origin" -> R.string.sky_airport_origin_intro
            "pick_destination" -> R.string.sky_airport_destination_intro
            else -> R.string.sky_airport_explore_intro
        })
    }

    private fun setupList() {
        adapter = AirportSearchAdapter(
            onClick = { hit ->

                //   会顶着一个键盘打开，看起来像"点错了"。
                hideKeyboard()
                onAirportSelected(hit.airport)
            },
            onLongClick = { hit ->
                // 长按 = 看详情，不改变“单击=选中”的主路径。
                // iataCode / icaoCode 在领域模型里都可空（Airport.kt:23-24）。
                openAirportDetail(
                    hit.airport.iataCode?.takeIf { it.isNotBlank() }
                        ?: hit.airport.icaoCode.orEmpty(),
                )
            },
            referencePoint = AirportSearchReferenceArgs.readFrom(arguments),
        )
        binding.resultList.apply {
            layoutManager = LinearLayoutManager(requireContext())

            //   搜索场景里每次按键都整表变，动画看起来像闪屏。
            //   关掉动画后，DiffUtil 的移动仍然生效（只是不插值）。
            itemAnimator = null
            setHasFixedSize(false)
            adapter = this@AirportSearchFragment.adapter
        }
    }

    /**
     * 用导航深链打开机场详情；不依赖 app 模块的 R.id，也不依赖宿主。
     *
     * nav_main.xml 的 airportDetailFragment 声明了
     * `projectfocus://airport/{code}`，app 的 nav-graph 会合并 intent-filter。
     */
    private fun openAirportDetail(code: String) {
        if (!AirportNavigator.open(this, code)) {
            Toast.makeText(
                requireContext(),
                R.string.airport_search_detail_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun setupSearchInput() {
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.onKeywordChange(text?.toString().orEmpty())

            //   它是输入框的附属品，跟着「框里有没有字」变，
            //   而不是跟着「搜索结果是什么」变。
            //   放 render() 里会在搜索耗时期间闪一下。
            binding.btnClear.isVisible = !text.isNullOrEmpty()
        }

        binding.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {

                hideKeyboard()
                true
            } else {
                false
            }
        }


        //   `setOnFocusChangeListener` 在 `requestFocus()` 时也会触发，
        //   而输入框在 XML 里没有 `requestFocus`，所以只有用户真的点进来
        //   或宿主调 `focusSearch()` 才会走这里。
        binding.searchInput.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                viewLifecycleOwner.lifecycleScope.launch { viewModel.ensureIndex() }
            }
        }


        //   否则用户再点回来时 `OnFocusChange(true)` 不触发（一直是焦点态），
        //   索引就不会建（如果第一次进页面从未获得过焦点）。
        @Suppress("ClickableViewAccessibility")
        binding.resultList.setOnTouchListener { _, _ ->
            hideKeyboard()
            false
        }

        //   所以它不会挡住列表项自己的点击。但 lint 的 ClickableViewAccessibility
        //   仍会报两条 warning：它无法证明"点击"这条路径还能走到 performClick。
        //   → 用注解显式说明：点击由 RecyclerView 的 item 点击器处理，本监听器不参与。
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnClear.setOnClickListener {
            binding.searchInput.setText("")
            viewModel.clearKeyword()
        }
        binding.btnRetry.setOnClickListener { onRetry() }
    }

















    private fun onChipSelected(airport: com.projectfocus.core.model.airport.Airport) {
        hideKeyboard()
        recordRecent(airport)
        onAirportSelected(airport)
    }


































    private fun recordRecent(airport: com.projectfocus.core.model.airport.Airport) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recordRecent(airport.id)
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 渲染 —— 纯函数式：同一个 state 一定画成同一个界面
    // ─────────────────────────────────────────────────────────








    private fun render(state: AirportSearchState) {
        val isContent = state is AirportSearchState.Content
        binding.stateIllustration.isVisible = !isContent && !(state is AirportSearchState.Idle && !state.isBare)
        binding.stateIllustration.setLoading(state === AirportSearchState.Loading)
        binding.resultList.isVisible = isContent
        binding.resultCount.isVisible = isContent

        when (state) {
            is AirportSearchState.Content -> renderContent(state)
            is AirportSearchState.Idle -> renderIdle(state)
            is AirportSearchState.TooShort -> renderMessage(
                title = getString(R.string.airport_too_short_title, state.needMore),
                body = getString(R.string.airport_too_short_body),
            )
            is AirportSearchState.NoResult -> renderMessage(

                //   用户粘一段文章进来时，"没找到「整篇文章」"会把标题撑成三行。
                title = getString(
                    R.string.airport_no_result_title,
                    state.keyword.take(MAX_ECHO),
                ),
                body = getString(R.string.airport_no_result_body),
            )
            AirportSearchState.Empty -> renderMessage(
                title = getString(R.string.airport_empty_title),
                body = getString(R.string.airport_empty_body),
                showRetry = true,
            )
            AirportSearchState.Loading -> renderMessage(
                title = getString(R.string.airport_loading_title),
                body = getString(R.string.airport_loading_body),
            )
        }
    }

    private fun renderContent(state: AirportSearchState.Content) {
        binding.statePanel.isVisible = false

        //   Content 态只可能是"有关键词且搜到了"，而进入这个态**必然**
        //   经过 renderMessage/dle 之外的路径，而 idlePanel 在
        //   renderIdle 之外的所有分支里都是 GONE。

        //     测试「搜到结果后推荐区收起来」正是钉住这个不变量的。

        //     因为 statePanel 是 overlay 而 idlePanel **在** statePanel 里面，
        //     隐藏父容器时子容器也看不见 —— 于是"看起来对"，但
        //     `idlePanel.visibility` 仍是 VISIBLE，下一帧若有人把
        //     statePanel 打开（比如 Loading），12 个 chip 会一起冒出来。
        binding.idlePanel.render(IdleRecommendation.EMPTY, show = false)
        adapter.submit(state)


        //   用户搜 "a" 看到 25 条不知道还有 4000 条 —— 会以为就这些。
        binding.resultCount.text = if (state.truncated) {
            getString(R.string.airport_content_total, state.total) +
                " · " + getString(R.string.airport_content_truncated)
        } else {
            getString(R.string.airport_content_total, state.total)
        }

        //   只能一条条数下去，不知道有多少。
        binding.resultList.contentDescription =
            getString(R.string.airport_result_count_desc, state.total)
    }










    private fun renderIdle(state: AirportSearchState.Idle) {
        binding.resultList.isVisible = false
        binding.resultCount.isVisible = false
        adapter.submit(state)

        if (state.isBare) {
            renderMessage(
                title = getString(R.string.airport_idle_bare_title),
                body = getString(R.string.airport_idle_bare_body),
            )
            return
        }

        binding.statePanel.isVisible = true

        //

        //   `expected 想去哪里 but was 正在准备机场数据` 而界面看起来"正常"。
        //   探针才揭穿：
        //   ```
        //   P-RENDER in=Idle titleViewId=1359302261
        //   P-A collected=[Loading, Idle] state=Idle title=正在准备机场数据
        //   ```
        //   `render(Idle)` 确实跑了，但它把标题藏起来时**留下了 Loading 的文案**。
        //
        //   这在屏幕上看不出来（GONE 就是不画），但有两个真实后果：
        //   ① **无障碍**：TalkBack 会把 `GONE` 之外的兄弟节点的文本
        //      拼进朗读内容（尤其配合 `accessibilityLiveRegion`），
        //      用户会听到"正在准备机场数据"再听到推荐项。
        //   ② **状态切换的残留**：Idle → TooShort 时如果只改回 VISIBLE，
        //      中间那一帧会闪出上一次的文案。
        //

        //     只要它有文案，就必须想清楚"它被显示回来时会是什么字"。
        binding.stateTitle.text = ""
        binding.stateBody.text = ""
        binding.stateTitle.visibility = View.GONE
        binding.stateBody.visibility = View.GONE
        binding.btnRetry.isVisible = false


        //
        //   `state.recent` / `state.popular` 是投影层算好的（`AirportSearchProjector`），
        //   `IdleRecommender` 负责把它们变成"可点的 chip 文案"。
        //   两层分开的理由见 IdleRecommender 类注释。
        //

        //     [IdleRecommender.build] 里做，而不是在这里。Fragment 里再做一次

        val rec = IdleRecommender.build(
            recents = state.recent,
            all = state.popular,
            known = null,
        )
        binding.idlePanel.render(rec, show = true)
    }

    private fun renderMessage(title: String, body: String, showRetry: Boolean = false) {
        binding.statePanel.isVisible = true

        //   漏了这一句的表现：输入 1 个字 → 显示"再输入 1 个字"，
        //   但下面还挂着 12 个可点的推荐 chip —— 用户点了会直接跳走，
        //   而他本来是在打字。
        //   （这是第 14 条"隐藏≠清空"的另一面：**显示也要管住兄弟节点**。）
        binding.idlePanel.render(IdleRecommendation.EMPTY, show = false)
        binding.stateTitle.visibility = View.VISIBLE
        binding.stateBody.visibility = View.VISIBLE
        binding.stateTitle.text = title
        binding.stateBody.text = body
        binding.btnRetry.isVisible = showRetry
    }

    // ─────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────









    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }



































    @androidx.annotation.VisibleForTesting
    internal fun requireViewForTest(): android.view.View = binding.root

    @androidx.annotation.VisibleForTesting
    internal fun stateForTest(): Any? = viewModel.state.value

    @androidx.annotation.VisibleForTesting
    internal fun forceEnsureIndexForTest() {
        kotlinx.coroutines.runBlocking { viewModel.ensureIndex() }
    }

    private companion object {








        const val MAX_ECHO = 16
    }
}
