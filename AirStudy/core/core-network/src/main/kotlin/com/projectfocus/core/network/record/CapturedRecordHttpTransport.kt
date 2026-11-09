package com.projectfocus.core.network.record

import com.projectfocus.core.network.di.TokenStore
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import okhttp3.*

/** A credential lease covers enqueue only; cancellation also stops the actual HTTP call. */
internal class CapturedRecordHttpTransport(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).build()

    suspend fun <T> execute(identity: TokenStore.Snapshot, request: Request, obsolete: T, failure: T,
                            parse: (Response) -> T): T = coroutineScope {
        if (TokenStore.snapshot() != identity) return@coroutineScope obsolete
        val result = CompletableDeferred<T>()
        val call = http.newCall(request)
        val owner = launch(start = CoroutineStart.UNDISPATCHED) {
            TokenStore.identities.collect { current ->
                if (current != identity) { result.complete(obsolete); call.cancel() }
            }
        }
        try {
            val started = TokenStore.withSnapshotLease(identity) {
                coroutineContext.ensureActive()
                if (result.isCompleted) false else {
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, error: IOException) {
                            result.complete(if (TokenStore.snapshot() == identity) failure else obsolete)
                        }
                        override fun onResponse(call: Call, response: Response) {
                            response.use {
                                if (result.isCompleted) return
                                val parsed = try { parse(response) } catch (_: IOException) { failure }
                                result.complete(if (TokenStore.snapshot() == identity) parsed else obsolete)
                            }
                        }
                    })
                    true
                }
            }
            if (started != true) return@coroutineScope obsolete
            val value = result.await()
            if (TokenStore.snapshot() == identity) value else obsolete
        } finally {
            owner.cancel(); call.cancel(); result.cancel()
        }
    }
}
