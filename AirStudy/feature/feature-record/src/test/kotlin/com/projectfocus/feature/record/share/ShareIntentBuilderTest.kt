package com.projectfocus.feature.record.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config





















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareIntentBuilderTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // 一个合法的 content:// URI（不需要真实存在 —— 本文件只断言字符串结构）
    private val uri: Uri = Uri.parse(
        "content://${context.packageName}.fileprovider/share_cards/share/1761436800000/boarding-pass-20261026-PF2645.png",
    )

    private fun build() = ShareIntentBuilder.build(
        uri = uri,
        text = "我完成了一趟 北京 → 大阪 的专注飞行",
        subject = "北京-大阪",
        imageDescription = "北京-大阪-PF2645",
    )

    // ── action / type / extras ─────────────────────────────

    @Test
    fun `action 是 ACTION_SEND`() {
        assertThat(build().action).isEqualTo(Intent.ACTION_SEND)
    }

    @Test
    fun `type 是 image png 而不是通配符`() {

        assertThat(build().type).isEqualTo("image/png")
    }

    @Test
    fun `EXTRA_STREAM 是那个 content URI`() {
        val intent = build()
        @Suppress("DEPRECATION")
        val stream = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertThat(stream).isEqualTo(uri)
    }

    @Test
    fun `EXTRA_TEXT 与 EXTRA_SUBJECT 都带上了`() {
        val intent = build()
        assertThat(intent.getStringExtra(Intent.EXTRA_TEXT)).contains("北京 → 大阪")
        assertThat(intent.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("北京-大阪")
    }

    // ── 三处授权 ───────────────────────────────────────────

    @Test
    fun `原 intent 带 FLAG_GRANT_READ_URI_PERMISSION`() {
        assertThat(build().flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `chooser 自己也带 FLAG_GRANT_READ_URI_PERMISSION`() {

        //   flags 不会从被包着的那个继承。漏了的表现是"分享面板刚弹出就崩
        //   （SecurityException）"，或者面板里所有目标都是灰的。
        val chooser = ShareIntentBuilder.chooser(build(), "分享这趟飞行")
        assertThat(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `clipData 里带着同一个 URI`() {

        val clip = build().clipData
        assertThat(clip).isNotNull()
        assertThat(clip!!.itemCount).isEqualTo(1)
        assertThat(clip.getItemAt(0).uri).isEqualTo(uri)
    }

    @Test
    fun `clipData 的 label 是无障碍描述而不是文件名`() {

        //   （boarding-pass-...）比给"北京-大阪-PF2645"更难懂。
        val clip = build().clipData as ClipData
        assertThat(clip.description.label.toString()).isEqualTo("北京-大阪-PF2645")
    }

    // ── file:// 防御 ────────────────────────────────────────

    @Test
    fun `传 file URI 时立刻抛而不是等到 startActivity`() {

        //   FileUriExposedException 只在真机 API 24+ 抛，Robolectric 不模拟；
        //   这里的 require 让同样的错误在任何环境下都变成一条明确的断言。
        val fileUri = Uri.parse("file:///data/cache/share/x.png")
        val thrown = runCatching {
            ShareIntentBuilder.build(fileUri, "t", "s", "d")
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(thrown).hasMessageThat().contains("content://")
    }

    // ── authority ──────────────────────────────────────────

    @Test
    fun `authority 是 包名加 fileprovider`() {
        assertThat(ShareIntentBuilder.authority(context))
            .isEqualTo("${context.packageName}.fileprovider")
    }

    @Test
    fun `authority 不含硬编码的第三方包名`() {

        //   在 debug 包（applicationIdSuffix=".debug"）上直接崩。
        assertThat(ShareIntentBuilder.authority(context))
            .startsWith(context.packageName)
    }
}
