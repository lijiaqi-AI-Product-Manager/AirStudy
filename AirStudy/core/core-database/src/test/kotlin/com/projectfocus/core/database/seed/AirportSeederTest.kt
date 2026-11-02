package com.projectfocus.core.database.seed

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.TestAssets
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.AppSettingEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * 机场库导入器的行为测试（真 SQLite，Robolectric 内存库）。
 *
 * 覆盖的是「首启导入」最容易出错的四种状态，而不是 happy path 本身：
 *   · 重复调用 → 必须幂等，不能重复插入
 *   · 库被清掉一部分 → 必须能自愈
 *   · 资产版本升级 → 必须删掉旧版本里有、新版本里没有的机场
 *   · 坏资产 → 必须整体回滚，不能留下半套数据
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AirportSeederTest {

    private lateinit var db: AppDatabase

    private val openAsset: (String) -> InputStream = { TestAssets.airportCsv().inputStream() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun ensureSeeded(
        opener: (String) -> InputStream = openAsset,
    ): AirportSeeder.Result = AirportSeeder.ensureSeeded(db, opener)

    @Test
    fun `first run seeds every row and marks the version`() = runTest {
        val result = ensureSeeded()

        assertTrue(result is AirportSeeder.Result.Seeded)
        assertEquals(AirportSeeder.EXPECTED_ROWS, (result as AirportSeeder.Result.Seeded).rows)
        assertEquals(AirportSeeder.EXPECTED_ROWS, db.airportDao().count(hasPremium = true))
        assertEquals(
            AirportSeeder.ASSET_VERSION.toString(),
            db.appSettingDao().get(AirportSeeder.KEY_ASSET_VERSION),
        )
    }

    @Test
    fun `second run is a no-op`() = runTest {
        ensureSeeded()
        val second = ensureSeeded()

        assertTrue(second is AirportSeeder.Result.UpToDate)
        assertEquals(AirportSeeder.EXPECTED_ROWS, (second as AirportSeeder.Result.UpToDate).rows)
        assertEquals(AirportSeeder.EXPECTED_ROWS, db.airportDao().count(hasPremium = true))
    }

    @Test
    fun `repairs a partially deleted library`() = runTest {
        ensureSeeded()
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM airports WHERE id = (SELECT id FROM airports LIMIT 1)",
        )
        assertEquals(AirportSeeder.EXPECTED_ROWS - 1, db.airportDao().count(hasPremium = true))

        val result = ensureSeeded()

        assertTrue(result is AirportSeeder.Result.Seeded)
        assertEquals(AirportSeeder.EXPECTED_ROWS, db.airportDao().count(hasPremium = true))
    }

    @Test
    fun `version bump removes rows that no longer exist in the asset`() = runTest {
        ensureSeeded()
        db.airportDao().upsert(
            AirportEntity(
                id = 99_999_999L,
                iataCode = "ZZZ",
                icaoCode = "ZZZZ",
                name = "Stale Airport",
                city = "",
                country = "ZZ",
                latitude = 0.0,
                longitude = 0.0,
                elevation = 0,
                continent = "",
                type = "small_airport",
                popularity = 0,
                isPremiumOnly = false,
            ),
        )
        db.appSettingDao().put(
            AppSettingEntity(AirportSeeder.KEY_ASSET_VERSION, "0", updatedAt = 0L),
        )

        val result = ensureSeeded()

        assertTrue(result is AirportSeeder.Result.Seeded)
        assertNull(db.airportDao().findByCode("ZZZ"))
        assertEquals(AirportSeeder.EXPECTED_ROWS, db.airportDao().count(hasPremium = true))
    }

    @Test
    fun `broken asset rolls back and keeps the previous library`() = runTest {
        ensureSeeded()
        db.appSettingDao().put(
            AppSettingEntity(AirportSeeder.KEY_ASSET_VERSION, "0", updatedAt = 0L),
        )

        val result = ensureSeeded { _ ->
            ByteArrayInputStream("id,iataCode\n1,AAA\n".toByteArray())
        }

        assertTrue(result is AirportSeeder.Result.Failed)
        assertEquals(AirportSeeder.EXPECTED_ROWS, db.airportDao().count(hasPremium = true))
        assertEquals("0", db.appSettingDao().get(AirportSeeder.KEY_ASSET_VERSION))
    }

    @Test
    fun `missing asset returns failed instead of throwing`() = runTest {
        val result = ensureSeeded { _ -> throw FileNotFoundException("no asset") }

        assertTrue(result is AirportSeeder.Result.Failed)
        assertEquals(0, db.airportDao().count(hasPremium = true))
    }
}
