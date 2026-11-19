package com.projectfocus.core.network.freeflight

import com.projectfocus.core.network.dto.AirportLocationRequest
import kotlinx.coroutines.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class HttpAirportLocationGatewayTest {
    @Test fun actualPostUsesSourceGeometryAndPaginationWithoutBorrowingAnotherAccountsInterceptor() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient.Builder().addInterceptor { it.proceed(it.request().newBuilder().header("Authorization","wrong-current-account").build()) }.build()
            try {
                server.enqueue(MockResponse.Builder().body("""{"success":true,"data":[{"id":1,"code":"ABC","name":"Airport","city":"City","country":"ZZ","latitude":12.25,"longitude":179.75}]}""").build())
                val values = HttpAirportLocationGateway(client,server.url("/").toString()).search(AirportLocationRequest(12.0,179.5,134))
                assertEquals("ABC",values.single().code)
                val actual = server.takeRequest()
                assertEquals("POST",actual.method)
                assertEquals("/v2/airports/search/location",actual.url.encodedPath)
                assertEquals("limit=50&offset=0",actual.url.encodedQuery)
                assertEquals("""{"latitude":12.0,"longitude":179.5,"radius":134}""",actual.body!!.utf8())
                assertNull(actual.headers["Authorization"])
                assertEquals("no-store",actual.headers["Cache-Control"])
            } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun failedMissingOrMalformedRepliesDoNotBecomeAnEmptySuccessfulSearch() = runBlocking {
        MockWebServer().use { server ->
            server.start();val client=OkHttpClient()
            try {
                val gateway=HttpAirportLocationGateway(client,server.url("/").toString())
                for (body in listOf("{}","""{"success":false,"data":[]}""","""{"success":true,"data":null}""",
                    """{"success":true,"data":[{"id":1,"code":"ABC","name":"Airport","city":"","country":"ZZ","latitude":100.0,"longitude":0.0}]}""")) {
                    server.enqueue(MockResponse.Builder().body(body).build())
                    var failed=false;try { gateway.search(AirportLocationRequest(0.0,0.0,134)) } catch (_:Exception) { failed=true }
                    assertTrue(failed)
                }
                assertEquals(4,server.requestCount)
            } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow() }
        }
    }
    @Test fun obsoleteSearchCancellationCancelsTheRealHttpCall() = runBlocking {
        MockWebServer().use { server ->
            server.start();val client=OkHttpClient()
            try {
                server.enqueue(MockResponse.Builder().body("""{"success":true,"data":[]}""").headersDelay(5,TimeUnit.SECONDS).build())
                val request=launch(Dispatchers.IO) { HttpAirportLocationGateway(client,server.url("/").toString()).search(AirportLocationRequest(0.0,0.0,134)) }
                assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
                withTimeout(1_000) { request.cancelAndJoin() }
                assertEquals(1,server.requestCount)
            } finally { client.dispatcher.cancelAll();client.connectionPool.evictAll();client.dispatcher.executorService.shutdownNow() }
        }
    }
}
