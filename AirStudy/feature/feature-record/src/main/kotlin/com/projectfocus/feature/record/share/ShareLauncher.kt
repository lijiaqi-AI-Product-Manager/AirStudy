package com.projectfocus.feature.record.share

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.annotation.MainThread
import dagger.hilt.android.qualifiers.ApplicationContext
import com.projectfocus.feature.record.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
























@Singleton
class ShareLauncher @Inject constructor(
    private val composer: ShareCardComposer,










    @ApplicationContext private val context: Context,
) {

    /** 启动 Intent 的方式。测试可替换（生产实现是 `context.startActivity`）。 */
    fun interface StartActivity {
        fun start(intent: Intent)
    }










    sealed interface Outcome {
        data object Launched : Outcome
        data object NoTarget : Outcome









        data class Failed(val error: Throwable?) : Outcome
    }













    suspend fun share(
        spec: ShareCardSpec,
        data: com.projectfocus.feature.completion.pass.BoardingPassData,
        startActivity: StartActivity,
        mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
        io: CoroutineDispatcher = Dispatchers.IO,
    ): Outcome {
        return try {
            // ① 主线程：创建带主题的 View、读资源、绑数据
            val prepared = withContext(mainDispatcher) {
                composer.prepareView(spec, data)
            }
            // ② IO 线程：measure / layout / draw / 编码 PNG / FileProvider
            //

            //   调用方是 `viewLifecycleOwner.lifecycleScope.launch { ... }`。
            //   用户点了分享、立刻按返回 —— 界面 scope 被取消，而 `withContext(io)`
            //   处于**可取消**状态，会在 `Bitmap.compress` 中途抛 `CancellationException`。

            //     而且 `FileOutputStream` 已打开，会留下**半截 PNG** ——
            //     而 FileProvider 能给它生成 URI（接收方打开时显示"图片损坏"）。
            //   → 生成 + 写盘是"要么完成、要么完全不做"的原子动作，不应被取消。
            val uri = withContext(io + NonCancellable) {
                composer.renderToUri(prepared, spec)
            }
            // ③ 主线程：启动系统面板
            withContext(mainDispatcher) {
                startActivity.start(composer.chooserFor(prepared, spec, uri))
            }
            Outcome.Launched
        } catch (e: CancellationException) {

            //   （父 scope 已取消，子协程还在跑）。
            Timber.d(e, "分享被取消（界面已销毁）")
            throw e
        } catch (_: ShareCardComposer.NoShareTargetException) {
            Timber.i("分享：本机没有可接收图片的应用")
            Outcome.NoTarget
        } catch (e: ActivityNotFoundException) {

            //   抛 ActivityNotFound（目标刚被卸载 / 被停用）。

            Timber.w(e, "分享：启动面板时找不到可用 Activity")
            Outcome.NoTarget
        } catch (e: Exception) {

            Timber.w(e, "分享卡片生成失败")
            Outcome.Failed(e)
        }
    }













    fun defaultStartActivity(): StartActivity = StartActivity { intent ->
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }









    fun messageRes(outcome: Outcome): Int? = when (outcome) {
        Outcome.Launched -> null
        Outcome.NoTarget -> R.string.record_share_no_target
        is Outcome.Failed -> R.string.record_share_failed
    }

    companion object {
        /** 弹提示。`Launched` 时什么也不做。 */
        @MainThread
        fun toastFor(context: Context, outcome: Outcome, launcher: ShareLauncher) {
            val res = launcher.messageRes(outcome) ?: return
            android.widget.Toast.makeText(context, res, android.widget.Toast.LENGTH_SHORT).show()
        }

        /** 当前是否在主线程。 */
        internal fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
    }
}
