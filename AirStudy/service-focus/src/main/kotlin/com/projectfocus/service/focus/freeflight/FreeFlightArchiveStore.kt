package com.projectfocus.service.focus.freeflight

import com.projectfocus.core.database.dao.FreeFlightArchiveDao
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.database.entity.FreeFlightPositionEntity
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchive
import com.projectfocus.core.model.flight.freeflight.FreeFlightArchiveCodec
import com.projectfocus.core.model.flight.freeflight.FreeFlightPoint
import com.projectfocus.core.model.flight.freeflight.FreeFlightPositionSample
import com.projectfocus.core.model.flight.freeflight.FreeFlightStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface FreeFlightArchiveStore {
    suspend fun active(): FreeFlightArchive?
    suspend fun load(id: String, owner: String): FreeFlightArchive?
    suspend fun commit(expectedRevision: Long?, archive: FreeFlightArchive, sample: FreeFlightPositionSample?): Boolean
    suspend fun positions(id: String, owner: String): List<FreeFlightPositionSample>
    fun terminal(owner: String): Flow<List<FreeFlightArchive>>
}

class RoomFreeFlightArchiveStore @Inject constructor(
    private val dao: FreeFlightArchiveDao,
) : FreeFlightArchiveStore {
    override suspend fun active(): FreeFlightArchive? = dao.getActive()?.decode()
    override suspend fun load(id: String, owner: String): FreeFlightArchive? = dao.get(id, owner)?.decode()

    override suspend fun commit(
        expectedRevision: Long?,
        archive: FreeFlightArchive,
        sample: FreeFlightPositionSample?,
    ): Boolean {
        archive.validated()
        if (sample != null) {
            require(sample.sessionId == archive.sessionId && sample.sequence == archive.sampleSequence)
            require(sample.activeElapsedMs == archive.clock.activeElapsedMs)
            require(sample.point == archive.position && sample.cumulativeDistanceKm == archive.cumulativeDistanceKm)
        }
        val row = FreeFlightArchiveEntity(
            archive.sessionId, archive.ownerScope, archive.revision, archive.status.name,
            if (archive.status == FreeFlightStatus.ONGOING) 1 else null,
            archive.updatedAtWallMs, FreeFlightArchiveCodec.encode(archive),
        )
        return dao.commit(expectedRevision, row, sample?.let {
            FreeFlightPositionEntity(
                it.sessionId, it.sequence, it.activeElapsedMs, it.point.latitude, it.point.longitude,
                it.bearingDegrees, it.cumulativeDistanceKm, it.wallMs,
            )
        })
    }

    override suspend fun positions(id: String, owner: String): List<FreeFlightPositionSample> =
        dao.positions(id, owner).map {
            FreeFlightPositionSample(
                it.sessionId, it.sequence, it.activeElapsedMs, FreeFlightPoint(it.latitude, it.longitude),
                it.bearingDegrees, it.cumulativeDistanceKm, it.wallMs,
            )
        }

    override fun terminal(owner: String): Flow<List<FreeFlightArchive>> =
        dao.observeTerminal(owner).map { rows -> rows.map { it.decode() } }

    private fun FreeFlightArchiveEntity.decode(): FreeFlightArchive =
        FreeFlightArchiveCodec.decode(payloadJson).also {
            require(it.sessionId == sessionId && it.ownerScope == ownerScope && it.revision == revision)
            require(it.status.name == status && it.updatedAtWallMs == updatedAtWallMs)
            require((it.status == FreeFlightStatus.ONGOING) == (activeSlot == 1))
        }
}
