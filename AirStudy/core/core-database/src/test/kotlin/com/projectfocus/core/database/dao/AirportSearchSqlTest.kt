package com.projectfocus.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AirportEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Real Room + native SQLite; no seed data or Kotlin result-ranking substitute. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AirportSearchSqlTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: AirportDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.airportDao()
    }

    @After
    fun close() = db.close()

    @Test
    fun `source CASE order precedes popularity and ICAO exact gets no extra tier`() = runTest {
        dao.upsertAll(rows())
        assertThat(dao.search("AB", 20, false).map { it.id })
            .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L).inOrder()
    }

    @Test
    fun `paid filtering occurs before ranking and limit`() = runTest {
        dao.upsertAll(rows())
        assertThat(dao.search("AB", 3, false).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(dao.search("AB", 3, true).map { it.id }).containsExactly(1L, 9L, 2L).inOrder()
        assertThat(dao.search("AB", 20, false).any { it.isPremiumOnly }).isFalse()
    }

    @Test
    fun `raw binding preserves equality case whitespace LIKE wildcards and limit`() = runTest {
        dao.upsertAll(rows())
        // LIKE folds ASCII case; the source CASE's '=' comparison does not.
        assertThat(dao.search("ab", 3, false).map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(dao.search(" AB ", 20, false)).isEmpty()
        assertThat(dao.search("ＡＢ", 20, false)).isEmpty()
        assertThat(dao.search("%%", 20, false).map { it.id }).contains(8L)
        assertThat(dao.search("AB", 0, true)).isEmpty()
    }

    private fun rows() = listOf(
        row(1, "AB", "XEX", "Other", "Else", 1),
        row(2, "ABC", "XP1", "Other", "Else", 80),
        row(3, "ABD", "XP2", "Other", "Else", 10),
        row(4, "DEF", "XN1", "AB Resort", "Else", 900),
        row(5, "GHI", "AB", "Other", "Else", 9_999),
        row(6, "JKL", "XC1", "Other", "AB City", 5_000),
        row(7, "MNO", "XN2", "Port AB", "Else", 100),
        row(8, "PQR", "XZH", "Other", "Else", 20_000).copy(nameZh = "AB"),
        row(9, "ABE", "XPR", "Other", "Else", 2_000).copy(isPremiumOnly = true),
    )

    private fun row(id: Long, iata: String, icao: String, name: String, city: String, popularity: Int) = AirportEntity(
        id = id, iataCode = iata, icaoCode = icao, name = name, city = city, country = "ZZ",
        latitude = 0.0, longitude = 0.0, elevation = 0, continent = "", type = "large_airport",
        popularity = popularity, isPremiumOnly = false, nameZh = null, cityZh = null,
    )
}
