package com.projectfocus.feature.record.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import com.projectfocus.core.ui.R as UiR
import com.projectfocus.feature.completion.pass.BoardingPassData
import com.projectfocus.feature.completion.pass.BoardingPassView
import com.projectfocus.feature.record.R
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
































@Singleton
class ShareCardComposer @Inject constructor(
    @ApplicationContext private val context: Context,
) {












    fun interface CardFactory {

        @MainThread
        fun create(themeWrapped: Context): BoardingPassView
    }

    /** 生产用工厂。 */
    fun defaultFactory(): CardFactory = CardFactory { themed -> BoardingPassView(themed) }

    /**
     * ① 主线程：创建带主题的卡片 View 并绑数据。
     *
     * @return 中间产物，交给 [renderToUri]。
     */
    @MainThread
    fun prepareView(
        spec: ShareCardSpec,
        data: BoardingPassData,
        factory: CardFactory = defaultFactory(),
    ): Prepared {

        //   直接拿 application context 创建的 View 读不到 `Theme.Pf` 里的颜色，
        //   `getColor(bg_elevated)` 得到 framework 默认值（白），
        //   而文字也被解析成同一个白 —— 分享出去的是一张**纯白图**。

        //     天然带主题），所以肉眼在 App 里看一切都是好的。
        val themed = android.view.ContextThemeWrapper(context, UiR.style.Theme_Pf)
        val view = factory.create(themed)
        view.data = data
        return Prepared(view = view, context = themed)
    }

    /**
     * ② IO 线程：渲染 + 写盘 + 生成 `content://`。
     *
     * @throws NoShareTargetException 本机没有能接收图片的 App
     * @throws java.io.IOException 写盘失败
     */
    @WorkerThread
    fun renderToUri(prepared: Prepared, spec: ShareCardSpec): Uri {
        val source = ViewCardSource(prepared.view)
        val file = BoardingPassRenderer.renderToFile(
            context = context,
            source = source,
            fileName = spec.fileName,
            backgroundColor = ContextCompat.getColor(prepared.context, UiR.color.bg_elevated),
        )
        Timber.d("分享卡片已生成：%s（%d 字节）", file.name, file.length())

        val uri = ShareIntentBuilder.uriFor(context, file)


        //   这一步在 IO 线程上，`queryIntentActivities` 是 binder 调用，
        //   放主线程上会贡献一次 binder 往返；而且判断结果要赶在拼 Intent 之前。
        val probe = ShareIntentBuilder.build(
            uri = uri,
            text = spec.text,
            subject = spec.subject,
            imageDescription = spec.imageDescription,
        )
        if (!ShareIntentBuilder.hasImageReceiver(context, probe)) {

            //   而是一个需要不同文案的结果。见 `ShareLauncher.Outcome.NoTarget`。
            throw NoShareTargetException()
        }
        return uri
    }











    @MainThread
    fun chooserFor(prepared: Prepared, spec: ShareCardSpec, uri: Uri): Intent {
        val send = ShareIntentBuilder.build(
            uri = uri,
            text = spec.text,
            subject = spec.subject,
            imageDescription = spec.imageDescription,
        )
        return ShareIntentBuilder.chooser(
            send,
            prepared.context.getString(R.string.record_share_chooser_title),
        )
    }

    /** 设备上没有能接收图片的 App。 */
    class NoShareTargetException : IllegalStateException("没有可以接收图片的应用")








    class Prepared internal constructor(
        internal val view: BoardingPassView,
        internal val context: Context,
    )







    private class ViewCardSource(private val view: View) : BoardingPassRenderer.CardSource {

        override fun measureForExport(widthPx: Int, heightPx: Int) {
            view.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, widthPx, heightPx)
        }

        override fun drawTo(canvas: android.graphics.Canvas) {
            view.draw(canvas)
        }










        override fun aspectRatio(): Float {
            if (view.measuredWidth > 0 && view.measuredHeight > 0) {
                return view.measuredWidth.toFloat() / view.measuredHeight
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val w = view.measuredWidth
            val h = view.measuredHeight
            return if (w > 0 && h > 0) w.toFloat() / h else DEFAULT_RATIO
        }

        private companion object {







            const val DEFAULT_RATIO = 340f / 350f
        }
    }
}
