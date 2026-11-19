package com.projectfocus.server

import com.google.common.truth.Truth.assertThat
import com.projectfocus.server.domain.AirportLocationCatalog
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.jar.JarFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class AirportCataloguePackagingTest {
    @Test fun packagedServerRetainsTheCompleteAirportCatalogue() {
        val archive = File(checkNotNull(System.getProperty("airstudy.server.jarPath")))
        assertThat(archive.isFile).isTrue()
        val name = "airports/location-catalog-release.csv"
        val bytes = JarFile(archive).use { jar ->
            assertThat(jar.getJarEntry("airports/location-catalog.csv")).isNull()
            val entry = checkNotNull(jar.getJarEntry(name)) { "The server archive has no complete airport catalogue" }
            val metadata = Json.parseToJsonElement(jar.getInputStream(checkNotNull(jar.getJarEntry("airports/location-catalog.json")))
                .bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
            val data = jar.getInputStream(entry).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
            assertThat(digest).isEqualTo(metadata.getValue("output_sha256").jsonPrimitive.content)
            assertThat(metadata.getValue("output_rows").jsonPrimitive.content).isEqualTo("83798")
            data
        }
        URLClassLoader(arrayOf(archive.toURI().toURL()),null).use { isolated ->
            val packaged = checkNotNull(isolated.getResourceAsStream(name)).use { it.readBytes() }
            assertThat(packaged.contentEquals(bytes)).isTrue()
        }
        assertThat(AirportLocationCatalog::class.java.getResourceAsStream("/$name")!!.use { it.readBytes() }.contentEquals(bytes)).isTrue()
        assertThat(AirportLocationCatalog.bundled().size).isEqualTo(83798)
    }
}
