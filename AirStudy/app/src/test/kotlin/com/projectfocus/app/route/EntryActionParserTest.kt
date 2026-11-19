package com.projectfocus.app.route

import android.content.Intent
import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.projectfocus.widget.WidgetEntryActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





























































@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EntryActionParserTest {
    @get:org.junit.Rule
    val realApplicationLifetime = com.projectfocus.app.testing.RealApplicationLifetimeRule()


    private fun intentOf(vararg pairs: Pair<String, String?>): Intent =
        Intent().apply { pairs.forEach { (k, v) -> if (v != null) putExtra(k, v) } }

    // ==================================================================
    // 一、无动作：应该"正常打开"，不预填任何东西
    // ==================================================================

    @Test
    fun `null 的 Intent 解析为 None`() {
        assertThat(EntryActionParser.parse(null)).isEqualTo(EntryAction.None)
    }

    @Test
    fun `只有来源没有动作时解析为 None`() {

        //   不带任何 extra 里的 action —— 用户就是想打开 App 看看。
        val i = intentOf(EntryActionParser.KEY_ENTRY_SOURCE to EntryActionParser.SOURCE_WIDGET)
        assertThat(EntryActionParser.parse(i)).isEqualTo(EntryAction.None)
    }

    @Test
    fun `动作是空串时解析为 None`() {
        val i = intentOf(EntryActionParser.KEY_ACTION to "")
        assertThat(EntryActionParser.parse(i)).isEqualTo(EntryAction.None)
    }

    @Test
    fun `动作全是空白时解析为 None`() {

        //   `WidgetEntryActivity` 用 `takeIf { it.isNotBlank() }` 过滤后才转发，
        //   但**直连的 deep link / 通知** 不经过它。只要有一个入口漏了过滤，
        //   用户就会看到「首页进入了待开始状态、但时长为 0」—— 一个按了没反应的界面。
        //   把空白当"无动作"是最便宜也最正确的兜底。
        assertThat(EntryActionParser.parse(intentOf(EntryActionParser.KEY_ACTION to " ")))
            .isEqualTo(EntryAction.None)
        assertThat(EntryActionParser.parse(intentOf(EntryActionParser.KEY_ACTION to " \t\n ")))
            .isEqualTo(EntryAction.None)
    }

    // ==================================================================
    // 二、已知动作
    // ==================================================================

    @Test
    fun `quick_start 解析为 25 分钟快捷开始`() {
        val i = intentOf(
            EntryActionParser.KEY_ACTION to EntryActionParser.ACTION_QUICK_START,
            EntryActionParser.KEY_ENTRY_SOURCE to EntryActionParser.SOURCE_WIDGET,
        )
        assertThat(EntryActionParser.parse(i))
            .isEqualTo(EntryAction.QuickStart(EntryActionParser.QUICK_START_MINUTES))
        assertThat(EntryActionParser.QUICK_START_MINUTES).isEqualTo(25)
    }

    @Test
    fun `动作前后带空白仍然能识别`() {
        // 某些系统的 Intent 拼接会带空白；trim 之后应正常识别，
        // 而不是掉进 Unknown 分支（Unknown 只打日志、不预填 —— 功能失效但没报错）
        val i = intentOf(EntryActionParser.KEY_ACTION to "  quick_start  ")
        assertThat(EntryActionParser.parse(i))
            .isEqualTo(EntryAction.QuickStart(EntryActionParser.QUICK_START_MINUTES))
    }

    // ==================================================================
    // 三、未知动作：版本错位
    // ==================================================================

    @Test
    fun `不认识的动作解析为 Unknown 并保留原值`() {

        //   而 Launcher 上还挂着用户 v1 时添加的旧组件实例。
        //   解析成 Unknown 而不是 None，是为了**日志能区分**这两种情况 ——

        val action = EntryActionParser.parse(intentOf(EntryActionParser.KEY_ACTION to "quick_start_v2"))
        assertThat(action).isInstanceOf(EntryAction.Unknown::class.java)
        assertThat((action as EntryAction.Unknown).raw).isEqualTo("quick_start_v2")
    }

    @Test
    fun `Unknown 的 raw 是 trim 之后的值`() {

        //   日志里 `[     quick_start_v2 ]` 会让 grep 失效。
        val action = EntryActionParser.parse(intentOf(EntryActionParser.KEY_ACTION to "  quick_start_v2 "))
        assertThat((action as EntryAction.Unknown).raw).isEqualTo("quick_start_v2")
    }

    // ==================================================================
    // 四、跨模块字符串契约（本文件最值钱的一条）
    // ==================================================================

    @Test
    fun `常量与 widget 模块逐字一致`() {

        //   两个模块各自抄了一份字符串，抄错一个字符不编译失败、测试也发现不了，
        //   表现是"小组件点了没反应"。这里把它钉住。
        assertThat(EntryActionParser.KEY_ACTION).isEqualTo(WidgetEntryActivity.EXTRA_ACTION)
        assertThat(EntryActionParser.KEY_ENTRY_SOURCE).isEqualTo(WidgetEntryActivity.EXTRA_ENTRY_SOURCE)
        assertThat(EntryActionParser.SOURCE_WIDGET).isEqualTo(WidgetEntryActivity.SOURCE_WIDGET)
        assertThat(EntryActionParser.ACTION_QUICK_START).isEqualTo(WidgetEntryActivity.ACTION_QUICK_START)
    }

    @Test
    fun `isFromWidget 只认 widget 这一个来源`() {
        assertThat(
            EntryActionParser.isFromWidget(
                intentOf(EntryActionParser.KEY_ENTRY_SOURCE to EntryActionParser.SOURCE_WIDGET),
            ),
        ).isTrue()
        assertThat(
            EntryActionParser.isFromWidget(
                intentOf(EntryActionParser.KEY_ENTRY_SOURCE to "notification"),
            ),
        ).isFalse()
        assertThat(EntryActionParser.isFromWidget(null)).isFalse()

        //   不可能会大小写漂移；如果真收到大写的，那说明是别人伪造的 Intent。
        assertThat(
            EntryActionParser.isFromWidget(intentOf(EntryActionParser.KEY_ENTRY_SOURCE to "WIDGET")),
        ).isFalse()
    }

    @Test
    fun `带 uri 的深链当前解析为 None 且不抛异常`() {

        //   外部 App 发来 ACTION_VIEW 时不能崩溃。将来实现深链时，

        val i = Intent(Intent.ACTION_VIEW, Uri.parse("projectfocus://flight?minutes=50"))
        assertThat(EntryActionParser.parse(i)).isEqualTo(EntryAction.None)
    }
}
