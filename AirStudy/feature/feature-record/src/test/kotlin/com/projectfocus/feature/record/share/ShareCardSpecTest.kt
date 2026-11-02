package com.projectfocus.feature.record.share

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.ZoneId


class ShareCardSpecTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    /** 2026-10-26 00:00 UTC = 2026-10-26 08:00 CST */
    private val startMs = 1792972800_000L

    private object FakeTemplates : ShareCardSpec.Templates {
        override fun text(from: String, to: String, distance: String, focus: String): String =
            "$from>$to|$distance|$focus"

        override fun subject(from: String, to: String): String = "subj:$from-$to"

        override fun cardDesc(from: String, to: String, flightNo: String): String =
            "desc:$from-$to-$flightNo"

        override val unknownPlace: String = "未知地点"
    }

    private fun spec(
        fromCity: String = "北京",
        toCity: String = "大阪",
        fromCode: String = "PEK",
        toCode: String = "KIX",
        startWallMs: Long = startMs,
        flightNo: String = "PF2645",
    ) = ShareCardSpec(
        detail = ShareDetail(
            fromCity = fromCity,
            toCity = toCity,
            fromCode = fromCode,
            toCode = toCode,
            distanceText = "600.0",
            actualText = "45 分钟",
        ),
        flightNo = flightNo,
        startWallMs = startWallMs,
        zone = zone,
        templates = FakeTemplates,
    )

    // ── 文件名 ─────────────────────────────────────────────

    @Test
    fun `文件名是 ASCII 安全字符`() {

        //   这个集合在 Windows / macOS / Linux / Android 文件系统里都合法，
        //   而且不需要 URL 编码（FileProvider 生成的 content:// 会把它原样带上）。
        assertThat(spec().fileName).matches("[A-Za-z0-9._-]+")
    }

    @Test
    fun `文件名包含日期与航班号`() {
        assertThat(spec().fileName).isEqualTo("boarding-pass-20261026-PF2645.png")
    }

    @Test
    fun `城市名带中文或空格不影响文件名`() {

        //   中文文件名在部分接收方会被重新编码成乱码甚至保存失败。
        val s = spec(fromCity = "北 京·市", toCity = "大 阪")
        assertThat(s.fileName).matches("[A-Za-z0-9._-]+")
    }

    @Test
    fun `航班号含非法字符时被清洗`() {

        //   永远是 PF+数字。但这里的防御是"入口不可信"原则 ——
        //   一旦有人从别的来源传进航班号，文件名不能因此变成非法路径。
        val s = spec(flightNo = "PF/26:45")
        assertThat(s.fileName).isEqualTo("boarding-pass-20261026-PF2645.png")
    }

    @Test
    fun `航班号全非法时退化成默认值而不是空串`() {

        //   —— 部分接收方对"点号结尾"的文件名处理异常。
        val s = spec(flightNo = "///")
        assertThat(s.fileName).isEqualTo("boarding-pass-20261026-PF0000.png")
    }

    @Test
    fun `文件名日期用传入时区而不是 UTC`() {
        // 2026-10-26 23:30 UTC 在 UTC 是 26 日，在东八区是 27 日
        val ms = startMs + 23 * 3600_000L + 30 * 60_000L
        val s = spec(startWallMs = ms)
        assertThat(s.fileName).contains("20261027")
    }

    // ── 文本 ───────────────────────────────────────────────

    @Test
    fun `正文包含城市与里程与专注时长`() {
        assertThat(spec().text).isEqualTo("北京>大阪|600.0|45 分钟")
    }

    @Test
    fun `城市为空时用三字码兜底`() {
        val s = spec(fromCity = "", toCity = "  ")
        assertThat(s.text).isEqualTo("PEK>KIX|600.0|45 分钟")
    }

    @Test
    fun `城市与三字码都缺失时用未知地点而不是占位码`() {

        val s = spec(fromCity = "", toCity = "", fromCode = "---", toCode = "---")
        assertThat(s.text).isEqualTo("未知地点>未知地点|600.0|45 分钟")
        assertThat(s.text).doesNotContain("---")
    }

    @Test
    fun `主题与图片描述都用同一份城市兜底`() {
        val s = spec(fromCity = "", toCity = "")
        assertThat(s.subject).isEqualTo("subj:PEK-KIX")
        assertThat(s.imageDescription).isEqualTo("desc:PEK-KIX-PF2645")
    }

    @Test
    fun `距离与时长原样透传不做二次格式化`() {

        //   图上的 "600.0" 与文字里的 "600" 就会不一致 —— 而且没有测试能发现。
        assertThat(spec().text).contains("|600.0|")
        assertThat(spec().text).contains("|45 分钟")
    }

    @Test
    fun `常量 UNKNOWN_CODE 与重飞口径一致`() {

        //   （SessionRepository / ReplayIntentBuilder / 这里）。
        //   哪天有人改成 "???" 只改一处，这条测试会红。
        assertThat(ShareCardSpec.UNKNOWN_CODE)
            .isEqualTo(com.projectfocus.feature.record.detail.ReplayIntentBuilder.UNKNOWN_CODE)
    }
}
