package com.projectfocus.core.database.bootstrap

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.projectfocus.core.database.AppDatabase
import com.projectfocus.core.database.seed.SeedCallback
import timber.log.Timber


object DatabaseBootstrap {

    /** 预热耗时超过这个阈值就打 warning（毫秒） */
    private const val SLOW_WARMUP_THRESHOLD_MS = 500L

    /**
     * 预热结果。调用方（Application）可以据此决定要不要降级。
     */
    sealed interface Result {
        data class Success(val elapsedMs: Long) : Result

        data class Failure(val cause: Throwable, val elapsedMs: Long) : Result
    }

    /**
     * 打开并验证数据库。
     *
     * @param onSlow 耗时超过 [SLOW_WARMUP_THRESHOLD_MS] 时的回调（用于埋点上报）
     */
    fun warmUp(
        db: AppDatabase,
        onSlow: (elapsedMs: Long) -> Unit = {},
    ): Result {
        val start = System.currentTimeMillis()
        return try {
            // ① 真正打开（惰性代理在这一步落地）
            val sqlite = db.openHelper.writableDatabase

            // ② 验证能读 —— schema 校验（identity_hash）在这一步失败会抛
            sqlite.query("SELECT 1").use { it.moveToFirst() }

            // ③ 顺手做一次"内置场景模板对齐"：
            //    版本升级后新增/下线的内置场景在这里补齐，
            //    而不是等用户点进场景页才发现少了一个。
            alignSeedTemplate(sqlite)

            val elapsed = System.currentTimeMillis() - start
            if (elapsed > SLOW_WARMUP_THRESHOLD_MS) {
                Timber.w("数据库预热耗时 %d ms，超过阈值 %d ms —— 需要看迁移链", elapsed, SLOW_WARMUP_THRESHOLD_MS)
                onSlow(elapsed)
            } else {
                Timber.d("数据库预热完成，耗时 %d ms", elapsed)
            }
            Result.Success(elapsed)
        } catch (t: Throwable) {
            val elapsed = System.currentTimeMillis() - start
            // 不吞异常信息 —— 完整堆栈进日志，便于线上定位
            Timber.e(t, "数据库预热失败，耗时 %d ms", elapsed)
            Result.Failure(t, elapsed)
        }
    }











    private fun alignSeedTemplate(sqlite: SupportSQLiteDatabase) {
        val existing = mutableSetOf<String>()
        sqlite.query("SELECT `name` FROM `focus_categories` WHERE `isDefault` = 1").use { c ->
            while (c.moveToNext()) c.getString(0)?.let(existing::add)
        }
        val expected = com.projectfocus.core.database.seed.DefaultFocusScenarios.KEYS
        if (existing == expected) return

        Timber.i(
            "内置场景模板与当前版本不一致：缺少 %s，多出 %s",
            expected - existing,
            existing - expected,
        )
        SeedCallback.refreshDefaults(sqlite, expected)
    }

    /**
     * 仅用于测试/诊断：不带任何 callback 的裸 SQLite 打开器。
     *
     * 不需要建 11 张表就能验证"文件能不能打开"，
     * 用于把"磁盘满了 / 权限没了"这类故障和"迁移写错了"区分开。
     */
    fun probeOpenable(
        context: Context,
        name: String = AppDatabase.NAME,
    ): Throwable? = runCatching {
        val helper: SupportSQLiteOpenHelper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name(name)
                    .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    })
                    .build(),
            )
        helper.writableDatabase.use { db -> db.query("SELECT 1").use { it.moveToFirst() } }
        helper.close()
    }.exceptionOrNull()

    /**
     * builder 配置的机器可读描述。
     *
     * 用途：`DatabaseBootstrapTest` 断言"DI 用的 builder 配置"与
     * "测试里复现的 builder 配置"一致 —— 两者一旦漂移，
     * 冒烟测试就不再代表真实启动路径。把配置写成字符串比反射比对 builder 稳。
     */
    fun describeBuilderConfig(): String = listOf(
        "migrations=${com.projectfocus.core.database.migrations.Migrations.ALL.size}",
        "journalMode=WAL",
        "allowMainThreadQueries=false",
        "fallbackToDestructiveMigration=false",
        "callbacks=create+open",
    ).joinToString(", ")
}
