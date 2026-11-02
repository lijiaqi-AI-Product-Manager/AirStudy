package com.projectfocus.core.database.seed

import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import timber.log.Timber


object SeedCallback {

    /**
     * 建库回调：灌 9 个内置场景。
     *
     * @param now 时间源，测试可注入固定值
     */
    fun create(now: () -> Long = System::currentTimeMillis): RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                val ts = now()
                DefaultFocusScenarios.ALL.forEach { seed ->
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `focus_categories`
                            (`name`, `colorHex`, `emoji`, `isDefault`, `sortOrder`,
                             `createdAt`, `updatedAt`)
                        VALUES (?, ?, ?, 1, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(seed.key, seed.colorHex, seed.emoji, seed.sortOrder, ts, ts),
                    )
                }
                Timber.i("种子数据已灌入：%d 个内置场景", DefaultFocusScenarios.ALL.size)
            }
        }















    fun open(now: () -> Long = System::currentTimeMillis): RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                val ts = now()
                val existing = mutableSetOf<String>()
                db.query(
                    "SELECT `name` FROM `focus_categories` WHERE `isDefault` = 1",
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        existing += cursor.getString(0) ?: ""
                    }
                }

                val missing = DefaultFocusScenarios.ALL.filter { it.key !in existing }
                if (missing.isEmpty()) return

                missing.forEach { seed ->
                    db.execSQL(
                        """
                        INSERT OR IGNORE INTO `focus_categories`
                            (`name`, `colorHex`, `emoji`, `isDefault`, `sortOrder`,
                             `createdAt`, `updatedAt`)
                        VALUES (?, ?, ?, 1, ?, ?, ?)
                        """.trimIndent(),
                        arrayOf<Any?>(seed.key, seed.colorHex, seed.emoji, seed.sortOrder, ts, ts),
                    )
                }
                Timber.i("补齐内置场景 %d 个：%s", missing.size, missing.map { it.key })
            }
        }


    fun refreshDefaults(
        db: SupportSQLiteDatabase,
        validKeys: Set<String>,
        now: Long = System.currentTimeMillis(),
    ) {
        // ① 先补/更新模板。
        //

        //
        //   SQLite 在 **prepare 阶段** 就要求 ON CONFLICT 的目标列上存在
        //   PRIMARY KEY / UNIQUE 约束，否则直接抛：
        //     SQLiteException: ON CONFLICT clause does not match any
        //                      PRIMARY KEY or UNIQUE constraint
        //
        //   而 `(name, isDefault)` 的唯一索引是在 v8→v9 迁移里建的
        //   （Migrations.kt:544 → `index_focus_categories_name_isDefault`），
        //   `FocusCategoryEntity` 自己**没有** @Index ——
        //   也就是说「由 Room 直接建出来的全新库」根本没有这个索引。
        //   于是「全新安装 + 之后模板发生变更」的用户，第一次走到这里就崩。
        //
        //   （注：全新首启不会立刻走到这里 —— alignSeedTemplate 发现
        //     existing == expected 会提前 return（DatabaseBootstrap.kt:114），
        //     所以现场是「升级后才炸」，不是「装上就炸」。）
        //

        //   加 @Index 会改变 Room 的 schema identityHash；存量库不升版本号的话，
        //   下次打开直接抛 "Room cannot verify the data integrity" ——
        //   那是比现在更大的一次爆炸。正确做法是「升 DB 版本 + 写 21→22 迁移
        //   + 对历史重复行去重」，属于独立的一步（施工令 P0-3b），
        //   不混在「修崩溃」这一刀里做。
        //
        // 改成「先 UPDATE，没命中再 INSERT」：两条语句都不依赖任何索引，
        // 语义与 upsert 等价，且天然幂等（重复执行只是重复写同样的值）。
        DefaultFocusScenarios.ALL.forEach { seed ->
            db.execSQL(
                """
                UPDATE `focus_categories`
                   SET `colorHex` = ?, `emoji` = ?, `sortOrder` = ?, `updatedAt` = ?
                 WHERE `name` = ? AND `isDefault` = 1
                """.trimIndent(),
                arrayOf<Any?>(seed.colorHex, seed.emoji, seed.sortOrder, now, seed.key),
            )
            val hit = db.query("SELECT changes()").use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) > 0L
            }
            if (hit) return@forEach

            db.execSQL(
                """
                INSERT INTO `focus_categories`
                    (`name`, `colorHex`, `emoji`, `isDefault`, `sortOrder`,
                     `createdAt`, `updatedAt`)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(seed.key, seed.colorHex, seed.emoji, seed.sortOrder, now, now),
            )
        }

        // ② 删掉已下线的内置 key（用占位符拼接，避免 SQL 注入；key 来自代码常量本就安全）
        if (validKeys.isNotEmpty()) {
            val placeholders = validKeys.joinToString(",") { "?" }
            db.execSQL(
                "DELETE FROM `focus_categories` " +
                    "WHERE `isDefault` = 1 AND `name` NOT IN ($placeholders)",
                validKeys.toTypedArray(),
            )
        }
    }
}
