package com.projectfocus.server

import com.projectfocus.core.network.dto.AirportLocationRequest
import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.domain.AirportLocationCatalog
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.ServerSocket
import java.net.URI
import java.net.http.*
import java.time.Duration
import java.util.UUID
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AirportLocationRoutesTest {
    @Test fun completeBundledCatalogueIsAvailableThroughTheRealOwnedHttpModule() {
        assertEquals(83798,AirportLocationCatalog.bundled().size)
        val port=ServerSocket(0).use { it.localPort }
        val config=ServerConfig.fromEnv { key -> when(key) {
            "PF_ENV"->"dev";"PF_DB_URL"->"jdbc:h2:mem:airports_${UUID.randomUUID().toString().replace("-","")};DB_CLOSE_DELAY=-1;MODE=PostgreSQL"
            "PF_DB_USER"->"sa";"PF_DB_PASSWORD"->"";"PF_JWT_SECRET"->"location-tests-only-secret-at-least-32-bytes"
            "PF_RL_GENERAL","PF_RL_JOIN","PF_RL_CHAT"->"100000";else->null
        } }
        val engine=embeddedServer(Netty,port=port,host="127.0.0.1") { module(config) }.start(wait=false)
        val http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        fun post(query:String,body:String)=http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v2/airports/search/location$query"))
            .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString())
        try {
            val first=post("?limit=50&offset=0","""{"latitude":40.080101,"longitude":116.584999,"radius":134}""")
            assertEquals(200,first.statusCode())
            val data=Json.parseToJsonElement(first.body()).jsonObject["data"]!!.jsonArray
            assertTrue(data.isNotEmpty() && data.size<=50)
            assertTrue(data.any { it.jsonObject["code"]!!.jsonPrimitive.content=="PEK" })
            assertEquals(data.first(),data.first { it.jsonObject["code"]!!.jsonPrimitive.content=="PEK" })
            val next=post("?limit=1&offset=1","""{"latitude":40.080101,"longitude":116.584999,"radius":134}""")
            assertEquals(data[1],Json.parseToJsonElement(next.body()).jsonObject["data"]!!.jsonArray.single())
            for (query in listOf("?limit=51","?limit=bad","?offset=-1","?region=unconfigured"))
                assertEquals(400,post(query,"""{"latitude":40.0,"longitude":116.0,"radius":134}""").statusCode())
            assertEquals(400,post("","""{"latitude":91.0,"longitude":0.0,"radius":134}""").statusCode())
        } finally { engine.stop(0,5_000) }
    }
    @Test fun locationSearchCrossesTheDateLineAndParsesQuotedSourceFieldsWithoutInventedAirports() {
        val csv="id,code,name,city,country,latitude,longitude,type\n1,EAST,\"Quoted, Airport\",City,ZZ,0,179.99,small_airport\n2,WEST,West,City,ZZ,0,-179.99,heliport\n3,FAR,Far,City,ZZ,0,170,closed\n"
        val catalog=AirportLocationCatalog.parse(csv.reader())
        val nearby=catalog.search(AirportLocationRequest(0.0,180.0,3),50,0)
        assertEquals(setOf("EAST","WEST"),nearby.map { it.code }.toSet())
        assertEquals("Quoted, Airport",nearby.first { it.code=="EAST" }.name)
        assertEquals(listOf("FAR"),catalog.search(AirportLocationRequest(0.0,170.0,1),50,0).map { it.code })
        assertThrows(IllegalArgumentException::class.java) { catalog.search(AirportLocationRequest(0.0,0.0,0),50,0) }
    }
}
