package com.projectfocus.core.database.seed

import android.content.Context
import androidx.room.withTransaction
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.entity.AirportEntity
import com.projectfocus.core.database.entity.AppSettingEntity
import timber.log.Timber
import java.io.InputStream


object AirportSeeder {

    /** assets 里的相对路径 */
    const val ASSET_PATH: String = "airports/airports.csv"

    /**
     * 资产版本。**改动 `assets/airports/airports.csv` 时必须 +1**，
     * 否则老用户不会重新导入。
     *
     * 版本历史：
     *   · 1 — 9,068 行，OurAirports 2026-08 快照，热门序前 25 为
     *         「12 个中国枢纽 + 13 个全球枢纽」
     */
    const val ASSET_VERSION: Int = 1

    /** 资产总行数（不含表头）。构建脚本 `tools/airports/build_airports_asset.py` 会断言 */
    const val EXPECTED_ROWS: Int = 9_068

    private const val BATCH_SIZE = 500

    /** `app_setting` 里的版本标记键 */
    const val KEY_ASSET_VERSION: String = "airport_asset_version"

    sealed interface Result {

        data class Seeded(val rows: Int, val elapsedMs: Long) : Result

        /** 标记与行数都正确，什么也没做 */
        data class UpToDate(val rows: Int) : Result

        /** 导入失败。库仍是导入前的状态（事务已回滚） */
        data class Failed(val cause: Throwable) : Result
    }

    /**
     * 幂等入口：已导入则跳过，否则全量导入。
     *
     * 调用点：`ProjectFocusApp.onCreate` 的预热协程里（`Dispatchers.IO`）。
     */
    suspend fun ensureSeeded(context: Context, db: AppDatabase): Result =
        ensureSeeded(db) { path -> context.assets.open(path) }


    internal suspend fun ensureSeeded(
        db: AppDatabase,
        openAsset: (String) -> InputStream,
    ): Result {
        val startedAt = System.currentTimeMillis()
        return try {
            val dao = db.airportDao()
            val settings = db.appSettingDao()
            val seededVersion = settings.get(KEY_ASSET_VERSION)
            val existingRows = dao.count(hasPremium = true)

            if (seededVersion == ASSET_VERSION.toString() && existingRows == EXPECTED_ROWS) {
                return Result.UpToDate(existingRows)
            }

            Timber.i(
                "机场库需要导入：标记=%s，现有 %d 行（期望 %d）",
                seededVersion,
                existingRows,
                EXPECTED_ROWS,
            )

            var imported = 0
            db.withTransaction {
                // 资产是**全量快照**：先清空，才能让「新版本删掉的机场」真正消失。
                // 如果只是 REPLACE，被删除的机场会永远留在老用户库里。
                dao.deleteAll()

                openAsset(ASSET_PATH).bufferedReader().use { reader ->
                    val batch = ArrayList<AirportEntity>(BATCH_SIZE)
                    for (airport in AirportCsvParser.parse(reader)) {
                        batch.add(airport)
                        if (batch.size == BATCH_SIZE) {
                            dao.upsertAll(batch)
                            imported += batch.size
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) {
                        dao.upsertAll(batch)
                        imported += batch.size
                    }
                }

                if (imported != EXPECTED_ROWS) {
                    error("airport asset has $imported rows, expected $EXPECTED_ROWS")
                }

                settings.put(
                    AppSettingEntity(
                        key = KEY_ASSET_VERSION,
                        value = ASSET_VERSION.toString(),
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }

            val rows = dao.count(hasPremium = true)
            val elapsed = System.currentTimeMillis() - startedAt
            Timber.i("机场库导入完成：%d 行，%d ms", rows, elapsed)
            Result.Seeded(rows, elapsed)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 取消不是「失败」：进程/作用域被销毁时不要把一个正常的
            // 取消记成导入错误，更不能吞掉取消信号。
            throw c
        } catch (t: Throwable) {
            // 事务已回滚；这里只负责把原因带回调用方
            Timber.e(t, "机场库导入失败")
            Result.Failed(t)
        }
    }
}
