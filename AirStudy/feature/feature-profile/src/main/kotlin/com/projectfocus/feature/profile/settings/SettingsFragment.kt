package com.projectfocus.feature.profile.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.appcompat.content.res.AppCompatResources
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.projectfocus.core.datastore.PrefKey
import com.projectfocus.feature.profile.R
import com.projectfocus.feature.profile.databinding.FragmentSettingsBinding
import com.projectfocus.feature.profile.spec.PrefControl
import com.projectfocus.feature.profile.spec.PrefItemSpec
import com.projectfocus.feature.profile.spec.PrefSectionSpec
import com.projectfocus.feature.profile.spec.PrefSpecs
import com.projectfocus.feature.profile.ui.PrefRowView
import com.projectfocus.feature.profile.ui.PrefSectionView
import com.projectfocus.feature.profile.ui.SegmentedControlView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import timber.log.Timber



































@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val settingsContext get() = binding.root.context

    private val viewModel: SettingsViewModel by viewModels()

    /** 键 → 该行的控件引用。建视图时填一次，之后只读 */
    private val rowBinds = mutableMapOf<PrefKey<*>, RowBinding>()








    private sealed interface RowBinding {
        /** 把值表应用到控件上。`values` 已合并乐观更新。 */
        fun render(values: Map<String, Any?>, enabled: Boolean)

        class SwitchBinding(
            val row: PrefRowView,
            val switch: MaterialSwitch,
        ) : RowBinding {
            override fun render(values: Map<String, Any?>, enabled: Boolean) {
                val key = row.tag as PrefKey<Boolean>
                val on = (values[key.name] as? Boolean) ?: key.default

                //   每次都设会让开关在每次任意键变化时"抖一下"。
                if (switch.isChecked != on) switch.isChecked = on
                switch.isEnabled = enabled
                row.isEnabled = enabled
                row.alpha = if (enabled) 1f else DISABLED_ALPHA
            }
        }

        class SegmentedBinding(
            val row: PrefRowView,
            val control: SegmentedControlView,
        ) : RowBinding {
            override fun render(values: Map<String, Any?>, enabled: Boolean) {
                @Suppress("UNCHECKED_CAST")
                val key = row.tag as PrefKey<String>
                val current = (values[key.name] as? String) ?: key.default
                control.setSelectedValue(current, animate = true)
                control.isEnabled = enabled
                row.isEnabled = enabled
                row.alpha = if (enabled) 1f else DISABLED_ALPHA
            }
        }

        class SliderBinding(
            val row: PrefRowView,
            val seekBar: SeekBar,
            val valueLabel: TextView,
            val min: Float,
            val max: Float,
            val suffix: String,
            /** 值格式化：不同量纲（模糊半径 / 透明度）显示方式不同 */
            val format: (Float) -> String,
        ) : RowBinding {
            override fun render(values: Map<String, Any?>, enabled: Boolean) {

                val key = row.tag as PrefKey<*>
                val raw = values[key.name] ?: key.default
                val f = when (raw) {
                    is Float -> raw
                    is Int -> raw.toFloat()
                    else -> 0f
                }
                val progress = progressOf(f)
                if (seekBar.progress != progress) seekBar.progress = progress
                valueLabel.text = valueLabel.context.getString(
                    R.string.pref_value_with_unit, format(f), suffix,
                )
                seekBar.isEnabled = enabled
                row.isEnabled = enabled
                row.alpha = if (enabled) 1f else DISABLED_ALPHA
            }

            fun valueOfProgress(progress: Int): Float = min + (max - min) * (progress.toFloat() / PROGRESS_MAX)

            fun progressOf(value: Float): Int {
                if (max <= min) return 0
                val ratio = ((value - min) / (max - min)).coerceIn(0f, 1f)
                return (ratio * PROGRESS_MAX).toInt()
            }
        }

        class ValueBinding(
            val row: PrefRowView,
            val label: TextView,
            val format: (Any?) -> String,
        ) : RowBinding {
            override fun render(values: Map<String, Any?>, enabled: Boolean) {
                label.text = format(values[(row.tag as PrefKey<*>).name])
                row.isEnabled = enabled
                row.alpha = if (enabled) 1f else DISABLED_ALPHA
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val themed = android.view.ContextThemeWrapper(inflater.context, R.style.Theme_Pf_Settings)
        _binding = FragmentSettingsBinding.inflate(inflater.cloneInContext(themed), container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnBack.setOnClickListener { onBack() }
        binding.btnReset.setOnClickListener { confirmReset() }
        com.projectfocus.core.ui.sky.SkyPressFeedback.install(binding.btnBack)
        com.projectfocus.core.ui.sky.SkyPressFeedback.install(binding.btnReset)
        buildRows()
        observe()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rowBinds.clear()
        _binding = null
    }


    open fun onBack() {
        if (!findNavController().navigateUp()) requireActivity().onBackPressedDispatcher.onBackPressed()
    }

    // ==================================================================
    // 建视图
    // ==================================================================

    private fun buildRows() {
        val container = binding.settingsContainer
        container.removeAllViews()
        rowBinds.clear()

        PrefSpecs.sections.forEachIndexed { index, section ->
            val group = buildSection(section)
            if (index == 0) (group.layoutParams as LinearLayout.LayoutParams).topMargin = 0
            container.addView(group)
        }
    }

    private fun buildSection(spec: PrefSectionSpec): View =
        PrefSectionView(settingsContext).apply {
            setSectionTitle(getString(spec.titleRes))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = resources.getDimensionPixelSize(R.dimen.pref_section_gap)
            layoutParams = lp
            spec.items.forEach { item -> addRow(buildRow(item)) }
        }

    private fun buildRow(item: PrefItemSpec): PrefRowView {
        val row = PrefRowView(settingsContext)
        row.tag = item.key
        row.setTitle(buildTitle(item))

        when (val control = item.control) {
            PrefControl.Switch -> bindSwitch(row, item)
            PrefControl.Chevron -> bindChevron(row, item)
            is PrefControl.Segmented -> bindSegmented(row, item, control)
            is PrefControl.Slider -> bindSlider(row, item, control)
            PrefControl.Value -> bindValue(row, item)
        }
        return row
    }







    private fun buildTitle(item: PrefItemSpec): CharSequence {
        val base = getString(item.titleRes)
        if (!item.experimental) return base
        return android.text.SpannableString(base + " " + getString(R.string.pref_experimental_tag)).apply {
            val start = base.length
            val end = length
            setSpan(
                android.text.style.ForegroundColorSpan(
                    settingsContext.getColor(R.color.pref_source_primary),
                ),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.RelativeSizeSpan(0.8f),
                start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun bindSwitch(row: PrefRowView, item: PrefItemSpec) {
        @Suppress("UNCHECKED_CAST")
        val key = item.key as PrefKey<Boolean>
        val switch = MaterialSwitch(settingsContext).apply {

            //   开关自己能点的话，会先被 switch 切一次、再被 row 的监听切成反面 ——
            //   表现为"点开关没反应"或"点一下翻两次"。
            isClickable = false
            isFocusable = false
        }
        row.setTrailing(switch)
        row.setSubtitle(item.subtitleRes?.let { getString(it) })
        row.setRowClickable { toggle(key, !switch.isChecked) }
        rowBinds[key] = RowBinding.SwitchBinding(row, switch)
    }

    private fun bindChevron(row: PrefRowView, item: PrefItemSpec) {
        val label = TextView(settingsContext).apply {
            setTextAppearance(R.style.TextAppearance_Pf_SettingsValue)
            maxWidth = resources.getDimensionPixelSize(R.dimen.pref_inline_value_max_width)
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val holder = LinearLayout(settingsContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(ImageView(settingsContext).apply {
                setImageResource(R.drawable.ic_pref_chevron)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(resources.getDimensionPixelSize(R.dimen.pref_chevron_size), resources.getDimensionPixelSize(R.dimen.pref_chevron_size)).apply {
                marginStart = resources.getDimensionPixelSize(R.dimen.pref_control_gap)
            })
        }
        row.setTrailing(holder)
        row.setSubtitle(item.subtitleRes?.let { getString(it) })
        row.setRowClickable { openDetail(item) }
        rowBinds[item.key] = RowBinding.ValueBinding(row, label) { currentValueText(item) }
    }

    private fun bindSegmented(row: PrefRowView, item: PrefItemSpec, control: PrefControl.Segmented) {
        @Suppress("UNCHECKED_CAST")
        val key = item.key as PrefKey<String>
        val segments = control.options.map {
            SegmentedControlView.Segment(getString(it.labelRes), it.value)
        }
        val view = SegmentedControlView(settingsContext).apply {
            setSegments(segments, selectedValue = null, animate = false)
            onSegmentSelected = { value -> viewModel.set(key, value) }
        }
        row.setTrailing(null)
        row.setBelow(view, view.preferredHeight())
        row.setSubtitle(item.subtitleRes?.let { getString(it) })

        row.setRowClickable(null)
        rowBinds[key] = RowBinding.SegmentedBinding(row, view)
    }

    private fun bindSlider(row: PrefRowView, item: PrefItemSpec, control: PrefControl.Slider) {
        // 滑杆占满整行宽度（放在标题下面），而不是塞在右侧 ——
        // 右侧只有约 40% 屏宽，拖 0~40 的模糊半径时每像素 2 个单位，根本调不准。
        val label = TextView(settingsContext).apply {
            setTextAppearance(R.style.TextAppearance_Pf_SettingsValue)
            gravity = Gravity.END
            isSingleLine = true
            maxWidth = resources.getDimensionPixelSize(R.dimen.pref_inline_value_max_width)
        }
        val seek = SeekBar(settingsContext).apply {
            max = PROGRESS_MAX
            splitTrack = false
            contentDescription = getString(item.titleRes)
            progressTintList = AppCompatResources.getColorStateList(settingsContext, R.color.pref_slider_active)
            progressBackgroundTintList = AppCompatResources.getColorStateList(settingsContext, R.color.pref_slider_inactive)
            thumbTintList = AppCompatResources.getColorStateList(settingsContext, R.color.pref_slider_active)
        }
        row.setTrailing(label)
        row.setBelow(seek, resources.getDimensionPixelSize(R.dimen.pref_slider_height))
        row.setSubtitle(item.subtitleRes?.let { getString(it) })
        row.setRowClickable(null)

        val binding = RowBinding.SliderBinding(
            row = row,
            seekBar = seek,
            valueLabel = label,
            min = control.min,
            max = control.max,
            suffix = control.suffix,
            format = { formatSliderValue(item.key, it) },
        )
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = binding.valueOfProgress(progress)
                label.text = label.context.getString(
                    R.string.pref_value_with_unit, binding.format(v), control.suffix,
                )
                if (!fromUser) return

                //   吸附到 step 之后再写，仍然略多但可控；真正的节流由下面的 onStopTracking 兜底。
                val snapped = snap(v, control.min, control.step)
                bar?.tag = snapped
            }

            override fun onStartTrackingTouch(bar: SeekBar?) { bar?.tag = null }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                val snapped = (bar?.tag as? Float) ?: binding.valueOfProgress(bar?.progress ?: 0)
                writeSlider(item.key, snapped)
            }
        })
        rowBinds[item.key] = binding
    }

    private fun bindValue(row: PrefRowView, item: PrefItemSpec) {
        val label = TextView(settingsContext).apply {
            setTextAppearance(R.style.TextAppearance_Pf_SettingsValue)
            maxWidth = resources.getDimensionPixelSize(R.dimen.pref_inline_value_max_width)
            isSingleLine = true
        }
        row.setTrailing(label)
        row.setSubtitle(item.subtitleRes?.let { getString(it) })
        row.setRowClickable(null)
        rowBinds[item.key] = RowBinding.ValueBinding(row, label) { currentValueText(item) }
    }

    // ==================================================================
    // 写
    // ==================================================================

    private fun toggle(key: PrefKey<Boolean>, next: Boolean) {
        viewModel.set(key, next)

        //   用 dependentsOf 而不是全量 render：40 行里通常只有 5~9 行受影响。
        val dependents = PrefSpecs.dependentsOf(key)
        if (dependents.isNotEmpty()) {
            // 乐观值还没进 vm.values（launch 是异步的），这里直接按 next 算
            val optimistic = viewModel.values.value + (key.name to next)
            dependents.forEach { dependent ->
                rowBinds[dependent.key]?.render(optimistic, viewModel.isEnabled(dependent, optimistic))
            }
        }
    }

    private fun writeSlider(key: PrefKey<*>, value: Float) {
        when (key) {
            is PrefKey.MaterialAlpha -> viewModel.set(key, value)
            is PrefKey.CruiseZoom -> viewModel.set(key, value)
            is PrefKey.GlassBlurRadius -> viewModel.set(key, value)
            is PrefKey.GlassRefractionHeight -> viewModel.set(key, value)
            is PrefKey.GlassRefractionOffset -> viewModel.set(key, value)
            is PrefKey.GlassDispersion -> viewModel.set(key, value)
            is PrefKey.GlassLiquid -> viewModel.set(key, value)
            is PrefKey.GlassTintAlphaDay -> viewModel.set(key, value)
            is PrefKey.GlassTintAlphaNight -> viewModel.set(key, value)
            is PrefKey.WhiteNoiseEnvVolume -> viewModel.set(key, value)
            is PrefKey.WhiteNoisePlaneVolume -> viewModel.set(key, value)
            else -> Timber.w("滑杆绑到了非 Float 键，忽略：%s", key.id)
        }
    }

    /** 点带箭头的行 */
    private fun openDetail(item: PrefItemSpec) {
        when (item.key) {
            is PrefKey.RouteColor -> openRouteColor()
            is PrefKey.TtsLang1, is PrefKey.TtsLang2 -> openLanguagePicker(item.key)
            else -> Timber.w("没有为 %s 配置二级页", item.key.id)
        }
    }

    /** 二级页：航线颜色。M2 先用系统调色盘占位，M3 换成自绘 HSV 选择器 */
    protected open fun openRouteColor() {
        val key = PrefKey.RouteColor
        val current = viewModel.valueOf(key)
        val colors = ROUTE_COLOR_PRESETS
        val names = colors.map { String.format("#%06X", 0xFFFFFF and it) }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_color_title)
            .setItems(names) { _, which -> viewModel.set(key, colors[which]) }
            .setNegativeButton(R.string.pref_color_cancel, null)
            .show()
        Timber.v("当前航线颜色 #%06X", current)
    }

    /** 二级页：播报语言 */
    protected open fun openLanguagePicker(key: PrefKey<*>) {
        @Suppress("UNCHECKED_CAST")
        val k = key as PrefKey<String>
        val labels = TTS_LANGUAGES.map { getString(it.first) }.toTypedArray()
        val values = TTS_LANGUAGES.map { it.second }
        val checked = values.indexOf(viewModel.valueOf(k)).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_lang_page_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                viewModel.set(k, values[which])
                dialog.dismiss()
            }
            .setNegativeButton(R.string.pref_color_cancel, null)
            .show()
    }

    // ==================================================================
    // 观察
    // ==================================================================

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.values.collect { applyValues(it) }
                }
                launch {
                    viewModel.message.collect { msg ->
                        if (msg == null) return@collect
                        showMessage(msg)
                        viewModel.consumeMessage()
                    }
                }
            }
        }
    }









    private fun applyValues(values: Map<String, Any?>) {
        if (_binding == null) return
        rowBinds.forEach { (key, binding) ->
            val spec = PrefSpecs.specOf(key) ?: return@forEach
            binding.render(values, viewModel.isEnabled(spec, values))
        }
    }

    private fun showMessage(msg: SettingsMessage) {
        if (_binding == null) return
        val textRes = when (msg) {
            SettingsMessage.SaveFailed -> R.string.pref_save_failed
            SettingsMessage.ResetDone -> R.string.pref_reset_done
            SettingsMessage.ResetFailed -> R.string.pref_save_failed
        }
        android.widget.Toast.makeText(requireContext(), textRes, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.pref_reset_confirm_title)
            .setMessage(R.string.pref_reset_confirm_body)
            .setPositiveButton(R.string.pref_reset_confirm_ok) { _, _ -> viewModel.resetToDefaults() }
            .setNegativeButton(R.string.pref_reset_confirm_cancel, null)
            .show()
    }

    // ==================================================================
    // 值格式化
    // ==================================================================












    private fun formatSliderValue(key: PrefKey<*>, value: Float): String = when (key) {
        is PrefKey.MaterialAlpha,
        is PrefKey.GlassDispersion,
        is PrefKey.GlassLiquid,
        is PrefKey.GlassTintAlphaDay,
        is PrefKey.GlassTintAlphaNight,
        is PrefKey.WhiteNoiseEnvVolume,
        is PrefKey.WhiteNoisePlaneVolume,
        -> getString(R.string.pref_value_percent, (value * 100).toInt().toString())

        is PrefKey.GlassRefractionHeight,
        is PrefKey.GlassRefractionOffset,
        is PrefKey.CruiseZoom,
        -> String.format(java.util.Locale.ROOT, "%.1f", value)

        else -> String.format(java.util.Locale.ROOT, "%.0f", value)
    }

    /** 带箭头的行右侧显示的"当前值" */
    private fun currentValueText(item: PrefItemSpec): String = when (val key = item.key) {
        is PrefKey.RouteColor -> String.format(
            java.util.Locale.ROOT, "#%06X", 0xFFFFFF and viewModel.valueOf(key),
        )
        is PrefKey.TtsLang1, is PrefKey.TtsLang2 -> {
            @Suppress("UNCHECKED_CAST")
            val v = viewModel.valueOf(key as PrefKey<String>)
            TTS_LANGUAGES.firstOrNull { it.second == v }?.let { getString(it.first) }
                ?: getString(R.string.pref_lang_none)
        }
        else -> ""
    }

    companion object {
        /** SeekBar 的进度是 Int（0..1000）。用 1000 而不是 100 是为了 0.01 步长够用 */
        const val PROGRESS_MAX = 1000

        /** 置灰时的透明度。0.38 是 Material 的 disabled 标准值 */
        const val DISABLED_ALPHA = 0.38f








        fun snap(value: Float, min: Float, step: Float): Float {
            if (step <= 0f) return value
            val steps = Math.round((value - min) / step)
            return min + steps * step
        }


        val ROUTE_COLOR_PRESETS: IntArray = intArrayOf(
            0xFF0A59F7.toInt(), // 航空蓝（品牌色）
            0xFF34C77B.toInt(), // 抵达绿
            0xFFF5A623.toInt(), // 巡航橙
            0xFFE5484D.toInt(), // 警告红
            0xFF4A9EFF.toInt(), // 天蓝
            0xFFA855F7.toInt(), // 紫
        )

        /** TTS 语言选项：(显示名资源, 存进 PrefKey 的值) */
        val TTS_LANGUAGES: List<Pair<Int, String>> = listOf(
            R.string.pref_lang_cn to "cn",
            R.string.pref_lang_en to "en",
            R.string.pref_lang_jp to "jp",
            R.string.pref_lang_ko to "ko",
            R.string.pref_lang_ru to "ru",
            R.string.pref_lang_follow to "follow",
        )
    }
}
