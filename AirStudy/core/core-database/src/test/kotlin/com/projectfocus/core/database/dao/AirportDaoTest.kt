package com.projectfocus.core.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.TestAssets
import com.projectfocus.core.database.seed.AirportSeeder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AirportDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AirportDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.airportDao()
        runBlocking {
            val result = AirportSeeder.ensureSeeded(db) { TestAssets.airportCsv().inputStream() }
            check(result is AirportSeeder.Result.Seeded) { "seed failed: $result" }
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `count reflects the seeded library`() = runTest {
        assertEquals(AirportSeeder.EXPECTED_ROWS, dao.count(hasPremium = true))
    }

    @Test
    fun `findByCode matches iata and icao but not lowercase`() = runTest {
        assertEquals("PEK", dao.findByCode("PEK")?.iataCode)
        assertEquals("PEK", dao.findByCode("ZBAA")?.iataCode)
        // 契约：DAO 是精确匹配，大小写归一化由调用方（ViewModel）负责
        assertNull(dao.findByCode("pek"))
        assertNull(dao.findByCode("NOPE"))
    }

    @Test
    fun `topByPopularity starts with the curated hubs`() = runTest {
        val top = dao.topByPopularity(25)

        assertEquals(25, top.size)
        assertEquals("PEK", top.first().iataCode)
        assertTrue(top.map { it.iataCode }.containsAll(listOf("PEK", "PVG", "CAN", "HKG", "LHR")))
        // 热度必须单调不增 —— 这是「首页推荐」的排序契约
        assertEquals(top.map { it.popularity }.sortedDescending(), top.map { it.popularity })
    }

    @Test
    fun `byCountry returns at most 25 ordered by popularity`() = runTest {
        val china = dao.byCountry("CN")

        assertEquals(25, china.size)
        assertTrue(china.all { it.country == "CN" })
        assertEquals("PEK", china.first().iataCode)
        assertEquals(china.map { it.popularity }.sortedDescending(), china.map { it.popularity })
    }

    @Test
    fun `search matches name and city case-insensitively`() = runTest {
        val byName = dao.search("Beijing", limit = 10, hasPremium = true)
        val byCity = dao.search("Shanghai", limit = 10, hasPremium = true)

        assertTrue(byName.any { it.iataCode == "PEK" })
        assertTrue(byCity.any { it.iataCode == "PVG" })
        assertTrue(dao.search("definitely-no-such-airport", 10, true).isEmpty())
    }

    @Test
    fun `random returns distinct rows`() = runTest {
        val random = dao.random(5)

        assertEquals(5, random.size)
        assertEquals(5, random.map { it.id }.toSet().size)
    }

    @Test
    fun `premium filter hides flagged airports from free users`() = runTest {
        val pek = dao.findByCode("PEK")!!
        dao.upsert(pek.copy(isPremiumOnly = true))

        assertFalse(dao.getAll(hasPremium = false).any { it.iataCode == "PEK" })
        assertTrue(dao.getAll(hasPremium = true).any { it.iataCode == "PEK" })
        assertEquals(AirportSeeder.EXPECTED_ROWS - 1, dao.count(hasPremium = false))
    }

    @Test
    fun `observeAll emits the library as a flow`() = runTest {
        val all = dao.observeAll(hasPremium = true).first()

        assertEquals(AirportSeeder.EXPECTED_ROWS, all.size)
        assertNotNull(all.firstOrNull { it.iataCode == "PEK" })
    }
}
