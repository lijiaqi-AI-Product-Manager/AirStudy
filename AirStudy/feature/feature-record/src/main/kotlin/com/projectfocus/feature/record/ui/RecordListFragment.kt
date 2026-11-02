package com.projectfocus.feature.record.ui

import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.projectfocus.feature.record.R
import com.projectfocus.feature.record.databinding.FragmentRecordListBinding
import com.projectfocus.feature.record.list.RecordItem
import com.projectfocus.feature.record.list.RecordListState
import com.projectfocus.feature.record.list.RecordListViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch


@AndroidEntryPoint
open class RecordListFragment : Fragment() {

    private var _binding: FragmentRecordListBinding? = null









    protected val binding get() = requireNotNull(_binding) { "binding 在 onDestroyView 后被访问" }








    protected open fun createViewModel(): RecordListViewModel =
        androidx.lifecycle.ViewModelProvider(this)[RecordListViewModel::class.java]

    private val viewModel: RecordListViewModel by lazy { createViewModel() }

    private lateinit var adapter: RecordListAdapter

    // ─────────────────────────────────────────────────────────
    // 宿主回调
    // ─────────────────────────────────────────────────────────

    /** 点某条记录。默认什么也不做 —— 去哪由宿主决定（首页 / 详情 / 重飞）。 */
    open var onRecordOpen: (RecordItem) -> Unit = {}

    /** 点返回。默认 `popBackStack`。 */
    open var onBack: () -> Unit = {
        parentFragmentManager.takeIf { it.backStackEntryCount > 0 }?.popBackStack()
    }

    /** 删除。默认走系统 `AlertDialog`。测试里替换成"直接确认"以避开弹窗。 */
    open var onConfirmDelete: (item: RecordItem, confirm: () -> Unit) -> Unit =
        { item, confirm -> showDeleteDialog(item, confirm) }

    /** 删除成功后的提示。默认 Toast。测试里替换成记录调用次数。 */
    open var onDeleted: () -> Unit = {
        android.widget.Toast
            .makeText(requireContext(), R.string.record_deleted_toast, android.widget.Toast.LENGTH_SHORT)
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
        _binding = FragmentRecordListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupList()
        setupSearch()
        setupActions()
        setupBackHandling()
        observeState()
    }

    override fun onDestroyView() {

        //   RecyclerView 在 detach 过程中仍可能回调 onBindViewHolder，
        //   而此时 binding 里的 View 正在被销毁。
        binding.recordList.adapter = null
        super.onDestroyView()
        _binding = null
    }

    // ─────────────────────────────────────────────────────────
    // 装配
    // ─────────────────────────────────────────────────────────

    private fun setupList() {
        adapter = RecordListAdapter { item -> onRecordOpen(item) }
        binding.recordList.apply {
            layoutManager = LinearLayoutManager(requireContext())

            //   淡出+淡入看起来像闪屏。DiffUtil 的移动仍然生效。
            itemAnimator = null
            setHasFixedSize(false)
            adapter = this@RecordListFragment.adapter
        }
    }

    private fun setupSearch() {
        binding.searchInput.doAfterTextChanged { text ->
            viewModel.onSearch(text?.toString().orEmpty())

            //   放 render() 里会在查询期间闪一下。
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
    }

    private fun setupActions() {
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnClear.setOnClickListener { clearSearch() }
        binding.btnClearSearch.setOnClickListener { clearSearch() }
        binding.btnStats.setOnClickListener {
            findNavController().navigate(Uri.parse("projectfocus://stats"))
        }
    }

















    private fun setupBackHandling() {
        val dispatcher = requireActivity().onBackPressedDispatcher
        dispatcher.addCallback(viewLifecycleOwner, object : androidx.activity.OnBackPressedCallback(false) {

            init {

                isEnabled = false
            }

            override fun handleOnBackPressed() {
                clearSearch()
            }
        }.also { backCallback = it })
    }

    private var backCallback: androidx.activity.OnBackPressedCallback? = null

    private fun clearSearch() {

        //   VM 已经 Empty，而输入框还显示着旧关键词，用户会以为搜索没生效。
        binding.searchInput.setText("")
        binding.btnClear.isVisible = false
        viewModel.clearSearch()
        hideKeyboard()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    // 渲染
    // ─────────────────────────────────────────────────────────

    private fun render(state: RecordListState) {
        val isContent = state is RecordListState.Content
        binding.recordList.isVisible = isContent


        //   搜到 0 条时也是"在搜索中"，返回键仍然应该先清搜索。
        backCallback?.isEnabled = binding.searchInput.text?.isNotEmpty() == true

        when (state) {
            is RecordListState.Content -> renderContent(state)
            RecordListState.Loading -> renderMessage(
                title = getString(R.string.record_loading),
                body = "",
            )
            RecordListState.Empty -> renderMessage(
                title = getString(R.string.record_empty_title),
                body = getString(R.string.record_empty_body),
            )
            is RecordListState.NoResult -> renderMessage(

                title = getString(R.string.record_no_result_title, state.keyword.take(MAX_ECHO)),
                body = "",
                showClearSearch = true,
            )
        }
    }

    private fun renderContent(state: RecordListState.Content) {
        binding.statePanel.isVisible = false

        //

        //   ```
        //   XPROBE B vm=Content(flightCount=2, ...)     ← VM 已经回到列表
        //   XPROBE B input=                             ← 搜索框也清空了
        //   XPROBE B title=没有匹配「广州」的航班        ← 文案还挂着「广州」
        //   ```
        //   屏幕上看不出来（GONE 不绘制），但：
        //   ① **TalkBack**：`accessibilityLiveRegion` 附近的兄弟节点文本会被
        //      拼进朗读 —— 用户「清空搜索」后听到的是「没有匹配广州的航班」，
        //      而他明明看到了完整列表。
        //   ② **下次进入 NoResult**：如果哪天改成只切 VISIBLE 不重设文案，
        //      中间那一帧会闪出上一次的关键词。
        //


        //     可靠的是「**每次状态迁移都双向设**」：
        //     显示 → 设文案 + VISIBLE；隐藏 → **清文案** + GONE。
        //     所以这里显式清空，而不是指望 renderMessage 下次会覆盖。
        binding.stateTitle.text = ""
        binding.stateBody.text = ""
        binding.btnClearSearch.isVisible = false
        adapter.submit(state)
        binding.recordList.contentDescription =
            getString(R.string.record_list_desc, state.summary.flightCount)
    }


    private fun renderMessage(title: String, body: String, showClearSearch: Boolean = false) {
        binding.statePanel.isVisible = true
        binding.stateTitle.text = title
        binding.stateTitle.visibility = View.VISIBLE

        //   `layout_marginTop="@dimen/space_s"` 那 8dp，导致标题看起来偏上。
        binding.stateBody.text = body
        binding.stateBody.isVisible = body.isNotEmpty()
        binding.btnClearSearch.isVisible = showClearSearch
    }

    // ─────────────────────────────────────────────────────────
    // 删除
    // ─────────────────────────────────────────────────────────

    /** 请求删除某条记录（由宿主或列表长按调用）。 */
    fun requestDelete(item: RecordItem) {
        onConfirmDelete(item) { confirmDelete(item) }
    }

    private fun confirmDelete(item: RecordItem) {
        viewModel.delete(item.id)


        //     等它会让点击有可感知的延迟。而失败已在 ViewModel 里落日志。
        //     代价是"删了但失败"时用户会看到错误提示 —— 可接受（用户会重试）。
        onDeleted()
    }

    private fun showDeleteDialog(item: RecordItem, confirm: () -> Unit) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.record_delete_confirm_title)
            .setMessage(R.string.record_delete_confirm_body)
            .setNegativeButton(R.string.record_delete_confirm_cancel, null)
            .setPositiveButton(R.string.record_delete_confirm_ok) { _, _ -> confirm() }
            .show()
    }

    // ─────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(InputMethodManager::class.java) ?: return
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        binding.searchInput.clearFocus()
    }

    // ─────────────────────────────────────────────────────────
    // 测试钩子（必须在生产源集 —— 见 AirportSearchFragment 同名方法的长注释）
    // ─────────────────────────────────────────────────────────

    @androidx.annotation.VisibleForTesting
    internal fun requireViewForTest(): View = binding.root

    @androidx.annotation.VisibleForTesting
    internal fun stateForTest(): RecordListState = viewModel.state.value

    @androidx.annotation.VisibleForTesting
    internal fun adapterForTest(): RecordListAdapter = adapter

    private companion object {







        const val MAX_ECHO = 12
    }
}
