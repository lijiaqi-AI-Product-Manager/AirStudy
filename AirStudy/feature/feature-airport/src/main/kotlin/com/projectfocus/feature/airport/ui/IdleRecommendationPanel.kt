package com.projectfocus.feature.airport.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isEmpty
import androidx.core.view.isVisible
import com.projectfocus.core.model.airport.Airport
import com.projectfocus.feature.airport.R
import com.projectfocus.feature.airport.search.IdleRecommendation
import com.projectfocus.feature.airport.search.IdleRecommender











































class IdleRecommendationPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    init {
        orientation = VERTICAL
    }

    /** 点了某个 chip。由宿主决定是"填进目的地"还是"直接开始规划" */
    var onAirportClick: (Airport) -> Unit = {}

    /** 当前显示的推荐 —— 供测试断言 */
    var current: IdleRecommendation = IdleRecommendation.EMPTY
        private set























    val chips: List<AirportChipView>
        get() = buildList { collectChips(this@IdleRecommendationPanel, this) }

    private fun collectChips(v: View, out: MutableList<AirportChipView>) {
        if (v is AirportChipView) {
            out += v
            return
        }
        if (v is android.view.ViewGroup) {
            for (i in 0 until v.childCount) collectChips(v.getChildAt(i), out)
        }
    }









    fun render(rec: IdleRecommendation, show: Boolean = true) {
        removeAllViews()
        current = rec

        if (!show || rec.isBare) {

            //   不需要这里再留一个空壳（否则 marginTop 会撑出 24dp 空白）。
            isVisible = false
            return
        }
        isVisible = true

        if (rec.recent.isNotEmpty()) {
            addSection(R.string.airport_idle_recent, rec.recent, isRecent = true)
        }
        if (rec.popular.isNotEmpty()) {
            addSection(R.string.airport_idle_popular, rec.popular, isRecent = false)
        }

        //   不加的话 TalkBack 用户要点 11 次才走完，中途不知道还有多少。
        contentDescription = rec.spokenOverview
    }

    // ─────────────────────────────────────────────────────────

    private fun addSection(titleRes: Int, airports: List<Airport>, isRecent: Boolean) {
        val title = TextView(context).apply {
            setText(titleRes)

            //
            //   `R.style.Xxx_Yyy` 这个常量是 AGP **为当前模块**生成的。
            //   `TextAppearance.Pf.Caption` 定义在 `core-ui` 里，于是它在
            //   `feature-airport` 的 `R.style` 里**不存在** —— 编译报
            //   `Unresolved reference 'style'`。
            //   （XML 里 `@style/TextAppearance.Pf.Caption` 能过，是因为
            //    资源合并发生在**打包阶段**，那时所有模块的 style 已经合到一张表；
            //    而 Kotlin 代码里的 `R` 是**模块内**的符号。）
            //
            //   三条可选路线，选第三条：
            //   | 方案 | 问题 |
            //   |---|---|
            //   | 在 fragment 布局里为每种字号写一个 TextView | 标题是动态加的，没法提前写 |
            //   | 把 core-ui 的 style 复制一份到本模块 | 两份定义必然走岔 |
            //   | **用 `setTextAppearance(styleRes)` 从 core-ui 的 R 取** | ✅ |
            //

            //   是 **library 的私有资源 id** —— 跨模块引用它需要 core-ui 关闭
            //   `nonTransitiveRClass`，或者把该 style 标成 public。

            //
            // → 所以最终选择：**在 XML 侧定义本模块自己的标题样式**，
            //   值等于 core-ui 的 Caption（见 values/styles_airport.xml 的说明）。
            //   这一段和 chip 里的代码注释是同一条教训的两面：
            //   **XML 能有跨模块引用，Kotlin 的 R 不能。**
            setTextAppearance(R.style.AirportSectionTitle)

            //   然后直接进 chip。设成 HEADING 让用户能按标题跳段。
            androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
        }

        //   第二段加，形成 16dp 的段间距。
        val lp = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        if (!isRecent) lp.topMargin = (16 * resources.displayMetrics.density).toInt()
        addView(title, lp)

        val row = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        addView(
            row,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10 * resources.displayMetrics.density).toInt()
            },
        )

        var line: LinearLayout? = null
        var usedWidth = 0
        val availWidth = availableWidth()
        val gapPx = (8 * resources.displayMetrics.density).toInt()

        for (a in airports) {
            val label = IdleRecommender.chipLabel(a) ?: continue
            val chip = AirportChipView(context).apply {
                bind(a.id, label)
                setOnClickListener { onAirportClick(a) }
            }

            // 先粗测：`AirportChipView` 自己会在 `onMeasure` 里夹到 40% 宽，
            // 这里只需要"大概多宽"来决定换不换行。
            val wantW = chip.measureForLayout(availWidth)
            if (line == null || (usedWidth > 0 && usedWidth + gapPx + wantW > availWidth)) {
                line = LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    clipToPadding = false
                }
                row.addView(
                    line,
                    LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = if (row.isEmpty()) 0 else (8 * resources.displayMetrics.density).toInt() },
                )
                usedWidth = 0
            }
            val lp = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
            if (usedWidth > 0) lp.marginStart = gapPx
            line!!.addView(chip, lp)
            usedWidth += wantW + if (usedWidth > 0) gapPx else 0
        }
    }











    private fun availableWidth(): Int {
        val self = width
        if (self > 0) return self - paddingLeft - paddingRight
        val parentW = (parent as? View)?.width ?: 0
        if (parentW > 0) return parentW - paddingLeft - paddingRight
        return resources.displayMetrics.widthPixels - paddingLeft - paddingRight
    }










    private fun AirportChipView.measureForLayout(avail: Int): Int {
        measure(
            MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return measuredWidth
    }
}
