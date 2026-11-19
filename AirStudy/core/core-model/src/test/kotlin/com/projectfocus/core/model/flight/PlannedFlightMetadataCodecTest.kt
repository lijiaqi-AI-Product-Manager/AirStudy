package com.projectfocus.core.model.flight

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PlannedFlightMetadataCodecTest {
    @Test
    fun `scene snapshot keeps independent identity raw text display text and opaque metadata`() {
        val scene = PlannedScene(
            optionId = "custom:second",
            name = "\u00a0阅读 \"B\"\n",
            iconKey = "reading",
            color = 0xFFE73F77.toInt(),
            isCustom = true,
            cloudId = Long.MAX_VALUE,
            clientUuid = "client-B",
            displayName = "冻结的标题",
        )
        val encoded = PlannedFlightMetadataCodec.encodeScene(scene)
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(encoded)).isEqualTo(scene)
    }

    @Test
    fun `departure stores its date clock and zone independently of a session duration`() {
        val departure = PlannedDeparture("2026-10-31", 23, 59, "Pacific/Chatham")
        val decoded = PlannedFlightMetadataCodec.decodeDepartureOrNull(
            PlannedFlightMetadataCodec.encodeDeparture(departure),
        )
        assertThat(decoded).isEqualTo(departure)
        assertThat(decoded!!.instant()).isEqualTo(departure.instant())
        assertThat(decoded.minuteOfDay).isEqualTo(1439)
    }

    @Test
    fun `legacy null malformed and invalid semantic values do not break unrelated record reads`() {
        assertThat(PlannedFlightMetadataCodec.encodeScene(null)).isNull()
        assertThat(PlannedFlightMetadataCodec.encodeDeparture(null)).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(null)).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(null)).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull("{broken")).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull("{}")).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(
            """{"date":"2026-02-30","hour":1,"minute":2,"zoneId":"UTC"}""",
        )).isNull()
        assertThat(PlannedFlightMetadataCodec.decodeDepartureOrNull(
            """{"date":"2026-10-31","hour":1,"minute":2,"zoneId":"Not/A_Zone"}""",
        )).isNull()
    }

    @Test
    fun `known scene fields can be projected from future json without treating its icon as identity`() {
        val first = PlannedScene("first", "A", "study", 0xFF3CC612.toInt(), true)
        val second = first.copy(optionId = "second", name = "B")
        val json = PlannedFlightMetadataCodec.encodeScene(second)!!.dropLast(1) +
            ",\"future\":{\"revision\":2}}"
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(json)).isEqualTo(second)
        assertThat(PlannedFlightMetadataCodec.decodeSceneOrNull(json)).isNotEqualTo(first)
    }
}
