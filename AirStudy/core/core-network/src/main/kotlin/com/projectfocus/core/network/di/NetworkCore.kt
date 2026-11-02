package com.projectfocus.core.network.di

import android.content.Context
import com.projectfocus.core.network.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class NetworkCore @Inject constructor(
    @ApplicationContext private val context: Context,
) {









    val baseUrl: String = BuildConfig.API_BASE_URL

    /** debug 打 body 日志，release 连 URL 都不打（含 token 与邀请码） */
    val logBodies: Boolean = BuildConfig.DEBUG












    val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

        //   retryOnConnectionFailure —— 它只处理连接层，不处理 429，
        //   而且它不遵守 Retry-After。
        .retryOnConnectionFailure(true)

        //   自动跟随会把 POST 变成 GET（并丢掉 body），产生极难查的 bug。
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    val authInterceptor: Interceptor = AuthInterceptor(baseUrl) { currentToken() }


    fun loggingInterceptor(includeBody: Boolean): Interceptor {
        val level = when {
            !BuildConfig.DEBUG -> HttpLoggingInterceptor.Level.NONE
            includeBody -> HttpLoggingInterceptor.Level.BASIC
            else -> HttpLoggingInterceptor.Level.BASIC
        }
        return HttpLoggingInterceptor { message ->
            Timber.tag("OkHttp").d("%s", message.redactSecrets())
        }.apply {
            this.level = level
            redactHeader("Authorization")
            redactHeader("Cookie")
            redactHeader("Set-Cookie")
            redactHeader("Last-Event-ID")
        }
    }

    /** HTTP 磁盘缓存：1 个 REST 用途，10 MB 上限 */
    fun provideHttpCache(): Cache {
        val dir = File(context.cacheDir, "http_cache")
        if (!dir.exists()) dir.mkdirs()
        return Cache(dir, HTTP_CACHE_BYTES)
    }

    private fun currentToken(): String? = TokenStore.peek()

    companion object {
        private const val CONNECT_TIMEOUT_SECONDS = 10L
        private const val READ_TIMEOUT_SECONDS = 20L
        private const val WRITE_TIMEOUT_SECONDS = 20L
        private const val CALL_TIMEOUT_SECONDS = 45L
        private const val HTTP_CACHE_BYTES = 10L * 1024 * 1024
    }
}











internal fun String.redactSecrets(): String {
    var s = this
    // JWT 形态：eyJ 开头的三段
    s = s.replace(Regex("""eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{4,}"""), "<jwt:redacted>")
    // Bearer 后面的任意 token
    s = s.replace(Regex("""(Bearer\s+)[A-Za-z0-9._\-]{8,}"""), "$1<redacted>")
    // 显式 JSON 字段
    s = s.replace(Regex(""""(token|accessToken|refreshToken|password)"\s*:\s*"[^"]*""""), "\"$1\":\"<redacted>\"")
    // query 参数
    s = s.replace(Regex("""([?&](?:token|access_token|code|inviteCode)=)[^&\s]+"""), "$1<redacted>")
    return s
}












object TokenStore {
    @Volatile
    private var token: String? = null
    private var generation: Long = 0L
    private val publicationMutex = kotlinx.coroutines.sync.Mutex()
    private val withinFlightLease = ThreadLocal<Boolean>()

    data class Snapshot(val token: String?, val generation: Long)
    private val identityState = kotlinx.coroutines.flow.MutableStateFlow(Snapshot(null, 0L))
    val identities: kotlinx.coroutines.flow.StateFlow<Snapshot> = identityState.asStateFlow()

    private fun publishValue(value: String?): Snapshot {
        token = value
        generation++
        return Snapshot(token, generation).also { identityState.value = it }
    }

    fun set(value: String?) {
        publishBlocking { publishValue(value) }
    }

    fun peek(): String? = token

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(token, generation)

    fun storageScope(value: Snapshot = snapshot()): String =
        value.token?.takeIf(String::isNotBlank)?.let { current ->
            java.security.MessageDigest.getInstance("SHA-256").digest(current.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        } ?: "local"

    fun clear() {
        publishBlocking { publishValue(null) }
    }

    /** Atomic compare/publish, returning the exact publication snapshot for bootstrap evidence. */
    fun publishIfCurrent(expected: Snapshot, value: String?): Snapshot? = publishBlocking {
        if (Snapshot(token, generation) != expected) null
        else publishValue(value)
    }

    /** Suspending production publication: cancellation while waiting never publishes late auth. */
    suspend fun publishIfCurrentCancellable(expected: Snapshot, value: String?): Snapshot? {
        assertPublicationAllowed()
        publicationMutex.lock()
        try {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            return synchronized(this) {
                if (Snapshot(token, generation) != expected) null
                else publishValue(value)
            }
        } finally { publicationMutex.unlock() }
    }

    /** Future login/logout publishers use this without blocking Main on a held lease. */
    suspend fun setCancellable(value: String?) {
        assertPublicationAllowed()
        publicationMutex.lock()
        try { synchronized(this) { publishValue(value) } }
        finally { publicationMutex.unlock() }
    }

    sealed interface ImmediateSnapshot<out T> {
        data class Value<T>(val value: T) : ImmediateSnapshot<T>
        object Busy : ImmediateSnapshot<Nothing>
        object Obsolete : ImmediateSnapshot<Nothing>
    }

    /** A synchronous UI effect can try the same real publication lock without waiting or
     * changing dispatcher while it is held. Busy grants no authority and performs no action. */
    fun <T> tryWithSnapshotLease(expected: Snapshot, action: () -> T): ImmediateSnapshot<T> {
        check(withinFlightLease.get() != true) { "A flight identity lease cannot reenter itself" }
        if (!publicationMutex.tryLock()) return ImmediateSnapshot.Busy
        try {
            if (snapshot() != expected) return ImmediateSnapshot.Obsolete
            val previous = withinFlightLease.get()
            withinFlightLease.set(true)
            try { return ImmediateSnapshot.Value(action()) }
            finally { withinFlightLease.set(previous) }
        } finally { publicationMutex.unlock() }
    }

    /**
     * A real identity lease: every set/clear/compare-publish waits for the entire action.
     * No JVM monitor is held across suspension. Entire acquire/action/unlock runs on IO,
     * including the finally: a synchronous Main publisher cannot block the unlock's dispatcher.
     * The leased action must not switch to Main or reenter account/Core publication commands.
     */
    suspend fun <T> withSnapshotLease(expected: Snapshot, action: suspend () -> T): T? {
        check(withinFlightLease.get() != true) { "A flight identity lease cannot reenter itself" }
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            publicationMutex.lock()
            try {
                if (snapshot() != expected) return@withContext null
                kotlinx.coroutines.withContext(withinFlightLease.asContextElement(true)) { action() }
            } finally { publicationMutex.unlock() }
        }
    }

    private fun <T> publishBlocking(action: () -> T): T {
        assertPublicationAllowed()
        // Preserve existing same-thread monitor callers when uncontended, but never wait
        // under that monitor: tryLock is atomic, unlike isLocked followed by lock.
        if (Thread.holdsLock(this)) {
            check(publicationMutex.tryLock()) { "Cannot wait for an identity lease under the TokenStore monitor" }
            try { return action() }
            finally { publicationMutex.unlock() }
        }
        return kotlinx.coroutines.runBlocking {
            publicationMutex.lock()
            try { synchronized(this@TokenStore) { action() } }
            finally { publicationMutex.unlock() }
        }
    }

    /** Check BEFORE taking account preference locks, so reentrant effects fail without deadlock. */
    fun assertPublicationAllowed() {
        check(withinFlightLease.get() != true) { "Account publication cannot reenter a held flight identity lease" }
    }

    fun requireSnapshotLease() {
        check(withinFlightLease.get() == true) { "A real account publication lease is required" }
    }
}
