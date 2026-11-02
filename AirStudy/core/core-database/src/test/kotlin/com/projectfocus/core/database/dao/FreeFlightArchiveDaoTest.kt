package com.projectfocus.core.database.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.FreeFlightArchiveEntity
import com.projectfocus.core.database.entity.FreeFlightPositionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Prepared real Room tests; this candidate task does not compile or execute them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class FreeFlightArchiveDaoTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: FreeFlightArchiveDao

    @Before fun open() {
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dao = database.freeFlightArchiveDao()
    }
    @After fun close() { database.close() }

    private fun row(id: String, revision: Long = 0L, terminal: Boolean = false) = FreeFlightArchiveEntity(
        id, "owner", revision, if (terminal) "COMPLETED" else "ONGOING",
        if (terminal) null else 1, revision + 1L, "{}",
    )
    private fun position(id: String, sequence: Long) =
        FreeFlightPositionEntity(id, sequence, sequence * 1_000L, 0.0, sequence.toDouble(), 90.0, sequence.toDouble(), sequence)

    @Test fun oneActiveArchiveAndImmutableTerminalPreservePositions() = runBlocking {
        assertTrue(dao.commit(null, row("a"), position("a", 1L)))
        assertFalse(dao.commit(null, row("b"), position("b", 1L)))
        assertTrue(dao.commit(0L, row("a", 1L, true), position("a", 2L)))
        assertEquals(2, dao.positions("a", "owner").size)
        assertFalse(dao.commit(1L, row("a", 2L, true), null))
        assertTrue(dao.commit(null, row("b"), position("b", 1L)))
        assertEquals("b", dao.getActive()?.sessionId)
        assertEquals("COMPLETED", dao.get("a", "owner")?.status)
        assertEquals(2, dao.positions("a", "owner").size)
        assertNull(dao.get("a", "different-owner"))
        assertTrue(dao.positions("a", "different-owner").isEmpty())
    }

    @Test fun failedSampleAppendRollsBackTheCheckpointAndStaleRevisionIsRejected() = runBlocking {
        assertTrue(dao.commit(null, row("a"), position("a", 1L)))
        var failed = false
        try { dao.commit(0L, row("a", 1L), position("a", 1L)) }
        catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(0L, dao.get("a", "owner")?.revision)
        assertEquals(1, dao.positions("a", "owner").size)
        assertFalse(dao.commit(7L, row("a", 8L), null))
        assertEquals(0L, dao.getActive()?.revision)
    }
}
