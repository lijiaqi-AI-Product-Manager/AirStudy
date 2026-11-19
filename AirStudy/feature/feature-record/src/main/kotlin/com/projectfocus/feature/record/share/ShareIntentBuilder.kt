package com.projectfocus.feature.record.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import timber.log.Timber
import java.io.File


































































object ShareIntentBuilder {

    /** 分享卡片的 MIME type。固定 PNG —— 见 `BoardingPassRenderer` 的格式选择理由 */
    const val MIME_TYPE: String = "image/png"











    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * 把缓存文件包装成 `content://` URI。
     *
     * @throws IllegalArgumentException 文件不在 `file_paths.xml` 声明的目录内
     *   —— 这是**故意让它抛**：静默降级成 `file://` 会触发 `FileUriExposedException`。
     */
    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, authority(context), file)

    /**
     * 构造分享 Intent。
     *
     * @param uri 必须是 [uriFor] 产出的 `content://`
     * @param text 正文（已在 `ShareCardSpec` 里按同一份数据拼好）
     * @param subject 邮件主题
     * @param imageDescription 图片的无障碍描述
     */
    fun build(
        uri: Uri,
        text: String,
        subject: String,
        imageDescription: String,
    ): Intent {

        //    宁可在构造 Intent 的这一刻抛，也不要等 startActivity 时
        //    在用户面前崩。异常信息比 FileUriExposedException 直白得多。
        require(uri.scheme == "content") {
            "分享 URI 必须是 content://（收到 ${uri.scheme}://）。" +
                "file:// 在 API 24+ 会抛 FileUriExposedException —— 必须经 FileProvider。"
        }

        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)

            putExtra(Intent.EXTRA_SUBJECT, subject)

            putExtra(Intent.EXTRA_TITLE, imageDescription)
            // 三处授权之一：原 intent
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            clipData = ClipData.newRawUri(imageDescription, uri)
        }
        return send
    }












    fun chooser(send: Intent, title: CharSequence): Intent =
        Intent.createChooser(send, title).apply {
            // 三处授权之二：chooser 自己
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }













    fun hasImageReceiver(context: Context, send: Intent): Boolean = runCatching {
        context.packageManager
            .queryIntentActivities(send, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()
    }.onFailure {


        Timber.w(it, "查询分享目标失败，按「有」处理")
    }.getOrDefault(true)
}
