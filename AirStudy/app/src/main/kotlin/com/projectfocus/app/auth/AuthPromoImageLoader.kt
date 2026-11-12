package com.projectfocus.app.auth

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import com.projectfocus.core.network.di.NetworkCore
import com.projectfocus.core.network.di.NetworkModule.FlightPlanOkHttpClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject


@Singleton
class AuthPromoImageLoader internal constructor(
    context: Context,
    client: OkHttpClient,
    private val metadataUrl: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val timezone: () -> TimeZone = TimeZone::getDefault,
    private val cacheDirectory: File = context.cacheDir,
    private val timeoutMillis: Long = 12_000L,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        core: NetworkCore,
        @FlightPlanOkHttpClient client: OkHttpClient,
    ) : this(context, client, core.baseUrl.trimEnd('/') + "/auth/promo-image")

    private val http: OkHttpClient

    init {
        require(timeoutMillis > 0L)
        // This own anonymous image adapter must not inherit dynamic account or
        // body-logging interceptors if another client is supplied in a test/host.
        http = client.newBuilder().apply {
            interceptors().clear()
            networkInterceptors().clear()
        }.authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .cookieJar(CookieJar.NO_COOKIES).cache(null)
            .followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }

    fun bind(view: ImageView): AutoCloseable = Binding(view).also { it.begin() }

    private inner class Binding(private val view: ImageView) : AutoCloseable {
        private val closed = AtomicBoolean(false)
        private val lock = Any()
        private val calls = mutableSetOf<Call>()
        private val posts = mutableSetOf<Runnable>()
        private val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        private val cache = File(cacheDirectory, CACHE_FILE_NAME)

        fun begin() {
            view.clipToOutline = true
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height,
                        24f * view.resources.displayMetrics.density)
                }
            }
            view.setImageDrawable(null)
            view.alpha = 0f
            try {
                if (cache.exists()) {
                    val sameDay = sameLocalDay(cache.lastModified(), now())
                    val bitmap = if (sameDay) BitmapFactory.decodeFile(cache.absolutePath) else null
                    if (sameDay && bitmap != null) {
                        render(view, bitmap)
                        return
                    }
                }
                // Source ignores delete's Boolean result; a failure still tries
                // metadata, and an Exception in this whole cache block skips it.
                if (cache.exists()) cache.delete()
            } catch (_: Exception) {
                // No failure Toast and no bundled-image fallback in source.
            }
            try {
                enqueue(Request.Builder().url(metadataUrl).get()
                    .header("Accept", "application/json").build(), ::metadata)
            } catch (_: Exception) {
                // Synchronous construction/enqueue failure is silent as well.
            }
        }

        private fun metadata(response: Response) {
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful || !isOpen()) return
            val url = imageUrlFromMetadata(body) ?: return
            if (!isOpen()) return
            // Pass the returned String directly to OkHttp; do not trim it or
            // replace org.json getter coercion with a strict JSON DTO.
            enqueue(Request.Builder().url(url).get().header("Accept", "image/*").build(), ::image)
        }

        private fun image(response: Response) {
            val stream = response.body?.byteStream() ?: return
            if (!isOpen()) return
            // Source intentionally has no HTTP-success or decoded-bitmap guard.
            val bitmap = BitmapFactory.decodeStream(stream)
            if (!isOpen()) return
            post(bitmap)
            if (!isOpen()) return
            // Post is independent of cache success. Own resource cleanup uses
            // use/finally; source only closes on its successful write path.
            FileOutputStream(cache).use { output ->
                bitmap!!.compress(Bitmap.CompressFormat.JPEG, 90, output)
                output.flush()
            }
        }

        private fun enqueue(request: Request, consume: (Response) -> Unit) {
            if (!isOpen()) return
            val call = http.newCall(request)
            call.timeout().deadlineNanoTime(deadlineNanos)
            synchronized(lock) {
                if (closed.get()) { call.cancel(); return }
                calls += call
            }
            try {
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        synchronized(lock) { calls -= call }
                        // Source image onFailure is return-void; metadata's text
                        // helper also has no visible consumer. Do not Toast.
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (isOpen() && !call.isCanceled()) consume(response)
                            }
                        } catch (_: Exception) {
                            // Decode/parse/IO errors never restore an old image
                            // or retract the already-posted source render.
                        } finally {
                            synchronized(lock) { calls -= call }
                        }
                    }
                })
            } catch (_: Exception) {
                synchronized(lock) { calls -= call }
                call.cancel()
            }
        }

        private fun post(bitmap: Bitmap?) {
            val action = object : Runnable {
                override fun run() {
                    synchronized(lock) { posts -= this }
                    if (isOpen()) render(view, bitmap)
                }
            }
            synchronized(lock) {
                if (!isOpen()) return
                posts += action
                if (!view.post(action)) posts -= action
            }
        }

        private fun isOpen(): Boolean {
            if (closed.get()) return false
            if (System.nanoTime() - deadlineNanos >= 0L) {
                close()
                return false
            }
            return true
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            val ownedCalls: List<Call>
            val ownedPosts: List<Runnable>
            synchronized(lock) {
                ownedCalls = calls.toList(); calls.clear()
                ownedPosts = posts.toList(); posts.clear()
            }
            ownedCalls.forEach(Call::cancel)
            ownedPosts.forEach { view.removeCallbacks(it) }
        }
    }

    private fun sameLocalDay(modified: Long, current: Long): Boolean {
        val zone = timezone()
        val before = Calendar.getInstance(zone).apply { timeInMillis = modified }
        val today = Calendar.getInstance(zone).apply { timeInMillis = current }
        return before.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            before.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    }

    private fun render(view: ImageView, bitmap: Bitmap?) {
        view.setImageBitmap(bitmap)
        view.animate().alpha(1f).setDuration(600L)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    internal companion object {
        const val CACHE_FILE_NAME = "projectfocus_auth_promo_daily.jpg"

        fun imageUrlFromMetadata(body: String): String? {
            val root = JSONObject(body)
            val successful = root.optBoolean("success", false)
            val data = root.optJSONObject("data")
            val url = data?.optString("url", null)
            return url?.takeIf { successful && it.isNotEmpty() }
        }
    }
}
