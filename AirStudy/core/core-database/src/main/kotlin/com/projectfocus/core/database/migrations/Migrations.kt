package com.projectfocus.core.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


object Migrations {

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //
    // v1 状态：只有一张 flight_sessions 表（上一代数据模型）
    // v2 新增：5 张表全建；flight_history 是 **25 列**版本
    //

    //   CREATE TABLE IF NOT EXISTS airports / flight_history(25 列) /
    //   focus_categories / blocked_apps / stamps
    //   CREATE INDEX index_flight_history_categoryId
    //

    //   startTimeMillis 索引要到 v3 才加（§5.3 末尾）。
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ① 先建父表（focus_categories），因为 flight_history 与
            //    blocked_apps 都有指向它的外键 —— 先父后子，避免建表时外键悬空。
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `focus_categories` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `name` TEXT NOT NULL,
                    `colorHex` TEXT NOT NULL,
                    `emoji` TEXT NOT NULL,
                    `isDefault` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )

            // ② 机场主数据
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `airports` (
                    `id` INTEGER NOT NULL,
                    `iataCode` TEXT NOT NULL,
                    `icaoCode` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `city` TEXT NOT NULL,
                    `country` TEXT NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `elevation` INTEGER NOT NULL,
                    `continent` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `popularity` INTEGER NOT NULL,
                    `isPremiumOnly` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )


            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `flight_history` (
                    `id` TEXT NOT NULL,
                    `roomId` TEXT,
                    `focusType` TEXT NOT NULL,
                    `roomParticipantCount` INTEGER,
                    `departureAirportCode` TEXT NOT NULL,
                    `departureAirportName` TEXT NOT NULL,
                    `departureCity` TEXT NOT NULL,
                    `departureCountry` TEXT NOT NULL,
                    `departureLatitude` REAL NOT NULL,
                    `departureLongitude` REAL NOT NULL,
                    `destinationAirportCode` TEXT NOT NULL,
                    `destinationAirportName` TEXT NOT NULL,
                    `destinationCity` TEXT NOT NULL,
                    `destinationCountry` TEXT NOT NULL,
                    `destinationLatitude` REAL NOT NULL,
                    `destinationLongitude` REAL NOT NULL,
                    `startTimeMillis` INTEGER NOT NULL,
                    `endTimeMillis` INTEGER,
                    `durationMinutes` INTEGER NOT NULL,
                    `actualFocusMinutes` INTEGER NOT NULL,
                    `distanceKm` REAL NOT NULL,
                    `status` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`categoryId`) REFERENCES `focus_categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )

            // ④ 屏蔽规则
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `blocked_apps` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `categoryId` INTEGER NOT NULL,
                    `packageName` TEXT NOT NULL,
                    `appName` TEXT NOT NULL,
                    `type` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    FOREIGN KEY(`categoryId`) REFERENCES `focus_categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )

            // ⑤ 护照印章
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stamps` (
                    `id` TEXT NOT NULL,
                    `countryName` TEXT NOT NULL,
                    `countryCode` TEXT NOT NULL,
                    `firstVisitDate` INTEGER NOT NULL,
                    `latestVisitDate` INTEGER NOT NULL,
                    `visitCount` INTEGER NOT NULL,
                    `focusHours` REAL NOT NULL,
                    `illustrationResId` INTEGER NOT NULL,
                    `airportCodes` TEXT NOT NULL,
                    `unlockedLandmarkIds` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )


            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_categoryId` " +
                    "ON `flight_history` (`categoryId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_blocked_apps_categoryId` " +
                    "ON `blocked_apps` (`categoryId`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //

    //   CREATE TABLE flight_history_temp (... 31 字段 ...)
    //   INSERT INTO flight_history_temp (31 列) SELECT (已有 25 列), 0, NULL, NULL, 0, 0, 0
    //     FROM flight_history
    //   DROP TABLE flight_history
    //   ALTER TABLE flight_history_temp RENAME TO flight_history
    //   CREATE INDEX index_flight_history_startTimeMillis
    //   CREATE INDEX index_flight_history_categoryId
    //

    //   isSynced               = 0     → 历史数据全部标"未同步"，升级后一次性上云
    //   logJson                = NULL  → 老记录没有事件日志
    //   seat                   = NULL  → 老记录没选过座位
    //   hasAnnouncedDeparture  = 0     → 老记录不会补播起飞播报
    //   hasAnnouncedDescent    = 0     → 同上
    //   hasAnnouncedArrival    = 0     → 同上
    //

    //   31 列的选择列表里，6 个新列的位置不能随便放 ——
    //   INSERT ... SELECT 是**按位置匹配**的，不是按列名。
    //   这里把 6 个新列放在 SELECT 末尾，对应新表里
    //   seat(24) / hasAnnounced*(25-27) / logJson(28) / isSynced(29) 的位置。
    //   所以 CREATE TABLE 里这 6 列也必须排在 categoryId 之后、createdAt 之前。
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `flight_history_temp` (
                    `id` TEXT NOT NULL,
                    `roomId` TEXT,
                    `focusType` TEXT NOT NULL,
                    `roomParticipantCount` INTEGER,
                    `departureAirportCode` TEXT NOT NULL,
                    `departureAirportName` TEXT NOT NULL,
                    `departureCity` TEXT NOT NULL,
                    `departureCountry` TEXT NOT NULL,
                    `departureLatitude` REAL NOT NULL,
                    `departureLongitude` REAL NOT NULL,
                    `destinationAirportCode` TEXT NOT NULL,
                    `destinationAirportName` TEXT NOT NULL,
                    `destinationCity` TEXT NOT NULL,
                    `destinationCountry` TEXT NOT NULL,
                    `destinationLatitude` REAL NOT NULL,
                    `destinationLongitude` REAL NOT NULL,
                    `startTimeMillis` INTEGER NOT NULL,
                    `endTimeMillis` INTEGER,
                    `durationMinutes` INTEGER NOT NULL,
                    `actualFocusMinutes` INTEGER NOT NULL,
                    `distanceKm` REAL NOT NULL,
                    `status` TEXT NOT NULL,
                    `categoryId` INTEGER,
                    `seat` TEXT,
                    `hasAnnouncedDeparture` INTEGER NOT NULL,
                    `hasAnnouncedDescent` INTEGER NOT NULL,
                    `hasAnnouncedArrival` INTEGER NOT NULL,
                    `logJson` TEXT,
                    `isSynced` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`categoryId`) REFERENCES `focus_categories`(`id`)
                        ON UPDATE NO ACTION ON DELETE SET NULL
                )
                """.trimIndent(),
            )


            //   最后 2 列（createdAt/updatedAt）从旧表同名列取。
            db.execSQL(
                """
                INSERT INTO `flight_history_temp` (
                    `id`, `roomId`, `focusType`, `roomParticipantCount`,
                    `departureAirportCode`, `departureAirportName`, `departureCity`,
                    `departureCountry`, `departureLatitude`, `departureLongitude`,
                    `destinationAirportCode`, `destinationAirportName`, `destinationCity`,
                    `destinationCountry`, `destinationLatitude`, `destinationLongitude`,
                    `startTimeMillis`, `endTimeMillis`, `durationMinutes`,
                    `actualFocusMinutes`, `distanceKm`, `status`, `categoryId`,
                    `seat`, `hasAnnouncedDeparture`, `hasAnnouncedDescent`,
                    `hasAnnouncedArrival`, `logJson`, `isSynced`,
                    `createdAt`, `updatedAt`
                )
                SELECT
                    `id`, `roomId`, `focusType`, `roomParticipantCount`,
                    `departureAirportCode`, `departureAirportName`, `departureCity`,
                    `departureCountry`, `departureLatitude`, `departureLongitude`,
                    `destinationAirportCode`, `destinationAirportName`, `destinationCity`,
                    `destinationCountry`, `destinationLatitude`, `destinationLongitude`,
                    `startTimeMillis`, `endTimeMillis`, `durationMinutes`,
                    `actualFocusMinutes`, `distanceKm`, `status`, `categoryId`,
                    NULL, 0, 0, 0, NULL, 0,
                    `createdAt`, `updatedAt`
                FROM `flight_history`
                """.trimIndent(),
            )

            db.execSQL("DROP TABLE `flight_history`")
            db.execSQL("ALTER TABLE `flight_history_temp` RENAME TO `flight_history`")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_categoryId` " +
                    "ON `flight_history` (`categoryId`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_startTimeMillis` " +
                    "ON `flight_history` (`startTimeMillis`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //

    // 因为它泄露了上一代数据模型的字段命名。
    //
    // 旧表 flight_sessions 字段 → 新表 flight_history 字段：
    //   startAirportIata        → departureAirportCode
    //   startAirportName        → departureAirportName
    //   startAirportCity        → departureCity
    //   destinationAirportIata  → destinationAirportCode
    //   destinationAirportName  → destinationAirportName
    //   destinationAirportCity  → destinationCity
    //   focusTimeMinutes        → actualFocusMinutes
    //   status='IN_PROGRESS'    → status='ONGOING'
    //
    // 三个关键洞察（照引原文）：
    //   ① 改名逻辑：`start*` → `departure*`，`Iata` → `Code`。
    //      **说明产品早期只有"起点"概念，后来才对称化成"出发/到达"。**
    //   ② 旧表没存国家和经纬度，靠 iataCode 反查 airports 补全：
    //        COALESCE((SELECT country FROM airports WHERE iataCode = ...), 'Unknown')

    //      "机场数据变更导致历史记录漂移"的坑。
    //   ③ focusType 硬编码 'SOLO' —— 联合飞行在 v4 之后才加入。
    //


    //   —— 也就是说它会**丢掉 v3 刚加上的 6 列**（seat/播报/logJson/isSynced）！

    //

    //   结果：v4 终点是 31 列，与 Room 实体一致；

    //   这正好解释了数据层还原 §1.2 里那句存疑：
    //   「若用户从 v1 升级到 v4，CREATE TABLE 只建了 25 字段的表，
    //     但 Room 会按 31 字段校验 → 身份哈希不匹配崩溃」。

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // 只在老表存在时才搬（新装用户没有 flight_sessions）
            val hasLegacy = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='flight_sessions'",
            ).use { it.moveToFirst() }

            if (!hasLegacy) return

            db.execSQL(
                """
                INSERT OR REPLACE INTO `flight_history` (
                    `id`, `roomId`, `focusType`, `roomParticipantCount`,
                    `departureAirportCode`, `departureAirportName`, `departureCity`,
                    `departureCountry`, `departureLatitude`, `departureLongitude`,
                    `destinationAirportCode`, `destinationAirportName`, `destinationCity`,
                    `destinationCountry`, `destinationLatitude`, `destinationLongitude`,
                    `startTimeMillis`, `endTimeMillis`, `durationMinutes`,
                    `actualFocusMinutes`, `distanceKm`, `status`, `categoryId`,
                    `seat`, `hasAnnouncedDeparture`, `hasAnnouncedDescent`,
                    `hasAnnouncedArrival`, `logJson`, `isSynced`,
                    `createdAt`, `updatedAt`
                )
                SELECT
                    s.`id`,
                    NULL,                                -- roomId：老表无组队
                    'SOLO',                              -- focusType：硬编码（洞察③）
                    NULL,                                -- roomParticipantCount
                    s.`startAirportIata`,
                    s.`startAirportName`,
                    s.`startAirportCity`,
                    COALESCE((SELECT `country`   FROM `airports`
                              WHERE `iataCode` = s.`startAirportIata` LIMIT 1), 'Unknown'),
                    COALESCE((SELECT `latitude`  FROM `airports`
                              WHERE `iataCode` = s.`startAirportIata` LIMIT 1), 0.0),
                    COALESCE((SELECT `longitude` FROM `airports`
                              WHERE `iataCode` = s.`startAirportIata` LIMIT 1), 0.0),
                    s.`destinationAirportIata`,
                    s.`destinationAirportName`,
                    s.`destinationAirportCity`,
                    COALESCE((SELECT `country`   FROM `airports`
                              WHERE `iataCode` = s.`destinationAirportIata` LIMIT 1), 'Unknown'),
                    COALESCE((SELECT `latitude`  FROM `airports`
                              WHERE `iataCode` = s.`destinationAirportIata` LIMIT 1), 0.0),
                    COALESCE((SELECT `longitude` FROM `airports`
                              WHERE `iataCode` = s.`destinationAirportIata` LIMIT 1), 0.0),
                    s.`startTimeMillis`,
                    s.`endTimeMillis`,
                    CAST(s.`durationMinutes` AS INTEGER),
                    CAST(COALESCE(s.`focusTimeMinutes`, 0) AS INTEGER),
                    CAST(s.`distanceKm` AS REAL),
                    CASE WHEN s.`status` = 'IN_PROGRESS' THEN 'ONGOING' ELSE s.`status` END,
                    s.`categoryId`,
                    NULL, 0, 0, 0, NULL, 0,             -- 6 个新列的默认值（同 v3）
                    s.`startTimeMillis`,                 -- createdAt：用起飞时刻兜底
                    s.`startTimeMillis`                  -- updatedAt：同上
                FROM `flight_sessions` s
                """.trimIndent(),
            )

            db.execSQL("DROP TABLE IF EXISTS `flight_sessions`")
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //


    //   「这是一个明确的产品缺陷，复刻时应当修复
    //     （在 FlightHistoryEntity 加 quote / quoteAuthor 两列）」
    //
    // 为什么不用「四步法」重建表：
    //   纯加列（ALTER TABLE ADD COLUMN）是 SQLite 支持的少数 ALTER 操作，
    //   且新列可空、无默认值约束 → 直接 ADD COLUMN 最安全、最快
    //   （31 列 × N 行不用拷数据）。
    //   四步法只用在「改类型/改约束/删列」场景。
    val MIGRATION_4_5 = object : Migration(4, 5) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `quote` TEXT")
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `quoteAuthor` TEXT")
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    //


    //   每次打包都可能重排 → 升级后旧数据指向错误的图。
    //   正确做法存字符串 key。
    //

    //   旧数据是 int（无意义），无法自动映射成 key。
    //   策略：置空字符串（""），由 UI 层在读取到空 key 时按 countryCode
    //        走兜底插画（如 flag_xx 或默认章）。

    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `stamps_new` (
                    `id` TEXT NOT NULL,
                    `countryName` TEXT NOT NULL,
                    `countryCode` TEXT NOT NULL,
                    `firstVisitDate` INTEGER NOT NULL,
                    `latestVisitDate` INTEGER NOT NULL,
                    `visitCount` INTEGER NOT NULL,
                    `focusHours` REAL NOT NULL,
                    `illustrationResId` TEXT NOT NULL,
                    `airportCodes` TEXT NOT NULL,
                    `unlockedLandmarkIds` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO `stamps_new` (
                    `id`, `countryName`, `countryCode`, `firstVisitDate`,
                    `latestVisitDate`, `visitCount`, `focusHours`,
                    `illustrationResId`, `airportCodes`, `unlockedLandmarkIds`
                )
                SELECT
                    `id`, `countryName`, `countryCode`, `firstVisitDate`,
                    `latestVisitDate`, `visitCount`, `focusHours`,
                    '',                                 -- 旧 int resId 无意义，置空由 UI 兜底
                    `airportCodes`, `unlockedLandmarkIds`
                FROM `stamps`
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE `stamps`")
            db.execSQL("ALTER TABLE `stamps_new` RENAME TO `stamps`")
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 待同步队列的 SQL 是 `WHERE isSynced = 0 AND status = 'COMPLETED'`。
    // 列顺序反过来（isSynced 在前）更优：等值条件都在，但 isSynced 基数低
    // （只有 0/1），SQLite 会走索引扫全 0 分支 —— 正好是需要的子集。
    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_isSynced_status` " +
                    "ON `flight_history` (`isSynced`, `status`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // `latestCompleted()` 是 `... WHERE status='COMPLETED' ORDER BY endTimeMillis DESC LIMIT 1`，
    // 首页每次进都会跑。没有索引就是全表扫 + 排序。

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_endTimeMillis` " +
                    "ON `flight_history` (`endTimeMillis`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 防止「同名的内置分类被灌两次」。

    //   M1 阶段表里只有种子数据，不会有重复；但为防升级到这一步时
    //   已经有脏数据（比如用户从旧版升级时种子重复灌过），
    //   这里先删掉重复行（保留 id 最小的那条）。
    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM `focus_categories`
                WHERE `id` NOT IN (
                    SELECT MIN(`id`) FROM `focus_categories`
                    GROUP BY `name`, `isDefault`
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_focus_categories_name_isDefault` " +
                    "ON `focus_categories` (`name`, `isDefault`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 用户可能在不同入口重复添加同一个 App，唯一索引兜住。
    // 同样先去重（保留 id 最小）。
    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                DELETE FROM `blocked_apps`
                WHERE `id` NOT IN (
                    SELECT MIN(`id`) FROM `blocked_apps`
                    GROUP BY `categoryId`, `packageName`
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_blocked_apps_categoryId_packageName` " +
                    "ON `blocked_apps` (`categoryId`, `packageName`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    // `findByCode` 与 `search` 都要走索引。
    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_iataCode` " +
                    "ON `airports` (`iataCode`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_icaoCode` " +
                    "ON `airports` (`icaoCode`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    // 83k 行 × 每次进国家页一次 —— 可接受但没必要。
    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_country_popularity` " +
                    "ON `airports` (`country`, `popularity`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 插画资源会迭代（重绘）。存一个资源版本号，
    // 资源更新时把旧版本的章标记为"待重取插画"，而不是靠 resId 失效。
    val MIGRATION_12_13 = object : Migration(12, 13) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "ALTER TABLE `stamps` ADD COLUMN `assetVersion` INTEGER NOT NULL DEFAULT 1",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 与 v7 的 `isSynced_status` 重复？不重复 ——
    // v7 是普通索引，v14 是 **覆盖索引**：把 pendingSync 需要的列也纳入，
    // 让同步队列查询完全走索引不回表。

    //   pendingSync 取 `SELECT *`，无法覆盖。所以这里改成**只服务计数**的
    //   窄索引（COUNT 查询只读索引页，不回表）。
    val MIGRATION_13_14 = object : Migration(13, 14) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_status_createdAt` " +
                    "ON `flight_history` (`status`, `createdAt`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════


    val MIGRATION_14_15 = object : Migration(14, 15) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `room` (
                    `id` TEXT NOT NULL,
                    `creatorId` TEXT NOT NULL,
                    `creatorUsername` TEXT NOT NULL,
                    `creatorAvatarId` TEXT,
                    `inviteCode` TEXT NOT NULL,
                    `durationMinutes` INTEGER NOT NULL,
                    `chatEnabledDuringSession` INTEGER NOT NULL,
                    `state` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `startedAt` INTEGER,
                    `willStartAt` INTEGER,
                    `completedAt` INTEGER,
                    `isPrivate` INTEGER NOT NULL,
                    `destinationAirportCode` TEXT,
                    `destinationAirportName` TEXT,
                    `cachedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_room_inviteCode` ON `room` (`inviteCode`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    val MIGRATION_15_16 = object : Migration(15, 16) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `room_participant` (
                    `id` TEXT NOT NULL,
                    `roomId` TEXT NOT NULL,
                    `userId` TEXT NOT NULL,
                    `username` TEXT NOT NULL,
                    `avatarId` TEXT,
                    `seatNumber` INTEGER NOT NULL,
                    `seatColumn` TEXT,
                    `joinedAt` INTEGER NOT NULL,
                    `leftAt` INTEGER,
                    `completed` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`roomId`) REFERENCES `room`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_room_participant_roomId` " +
                    "ON `room_participant` (`roomId`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    val MIGRATION_16_17 = object : Migration(16, 17) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `room_message` (
                    `id` TEXT NOT NULL,
                    `roomId` TEXT NOT NULL,
                    `senderId` TEXT NOT NULL,
                    `senderName` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `sentAt` INTEGER NOT NULL,
                    `isSystem` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`roomId`) REFERENCES `room`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_room_message_roomId_sentAt` " +
                    "ON `room_message` (`roomId`, `sentAt`)",
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════


    val MIGRATION_17_18 = object : Migration(17, 18) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `achievement` (
                    `id` TEXT NOT NULL,
                    `progress` INTEGER NOT NULL,
                    `target` INTEGER NOT NULL,
                    `unlockedAt` INTEGER,
                    `isNew` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent(),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════


    val MIGRATION_18_19 = object : Migration(18, 19) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `daily_stat` (
                    `dayStart` INTEGER NOT NULL,
                    `focusMinutes` INTEGER NOT NULL,
                    `flightCount` INTEGER NOT NULL,
                    `distanceKm` REAL NOT NULL,
                    PRIMARY KEY(`dayStart`)
                )
                """.trimIndent(),
            )
            // 从既有历史回填一次，避免升级后统计页空白
            db.execSQL(
                """
                INSERT OR REPLACE INTO `daily_stat` (`dayStart`, `focusMinutes`, `flightCount`, `distanceKm`)
                SELECT
                    `startTimeMillis` / 86400000 * 86400000 AS dayStart,
                    SUM(`actualFocusMinutes`),
                    COUNT(*),
                    SUM(`distanceKm`)
                FROM `flight_history`
                WHERE `status` = 'COMPLETED'
                GROUP BY dayStart
                """.trimIndent(),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════

    // 高频读取的仍留在 SP（内存缓存更快），低频配置进表，便于导出/同步。
    val MIGRATION_19_20 = object : Migration(19, 20) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `app_setting` (
                    `key` TEXT NOT NULL,
                    `value` TEXT,
                    `updatedAt` INTEGER NOT NULL,
                    PRIMARY KEY(`key`)
                )
                """.trimIndent(),
            )
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 只有 isSynced 布尔不够 —— 无法知道"什么时候同步的"，
    // 出问题时无法定位（是同步失败还是从没同步）。加审计列。
    val MIGRATION_20_21 = object : Migration(20, 21) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `syncedAt` INTEGER")
        }
    }

    // ═══════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════
    // 问题（audit_tools/verify_room_migration_parity.py 用 sqlite3 真跑迁移链实测）：
    //   v1~v21 的迁移加过的东西，实体从来没声明 ——
    //     列：flight_history.syncedAt（20→21）、stamps.assetVersion（12→13）
    //     索引：airports ×3（10→11、11→12）、flight_history ×3（6→7、7→8、13→14）
    //     唯一索引：focus_categories(name,isDefault)（8→9）、blocked_apps(categoryId,packageName)（9→10）
    //   反过来 airports.nameZh/cityZh 只在实体里有，迁移链从没加过。
    //   于是 v21 有两种形态：
    //     · 全新安装（Room 按实体建表）—— 缺上面的列和索引；
    //     · 从更老版本迁移上来 —— 多这些列/索引、缺 nameZh/cityZh。
    //   后一种打开就抛 "Migration didn't properly handle"（审计 P0-1 / P0-2b / P0-3b）。
    //
    // 做法：v22 的实体把它们全部声明进来（以迁移链的设计为准，每一项都有明确用途），
    //   本迁移对两种形态都幂等：
    //     ① 加列一律 addColumnIfMissing（已存在就跳过）；
    //     ② 普通索引一律 CREATE INDEX IF NOT EXISTS；
    //     ③ 唯一索引先去重。迁移期间外键不生效（Room 在 onOpen 才打开 foreign_keys），
    //        直接删重复行会留下悬空的 categoryId，迁移后的 foreignKeyCheck 会失败 ——
    //        所以先把引用改指到同组保留的那一行（MIN(id)），再删，再建索引。
    //        blocked_apps 要在 focus_categories 去重之后再去重：改指可能制造新的重复。
    val MIGRATION_21_22 = object : Migration(21, 22) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // ① 列
            db.addColumnIfMissing("airports", "nameZh", "TEXT")
            db.addColumnIfMissing("airports", "cityZh", "TEXT")
            db.addColumnIfMissing("flight_history", "syncedAt", "INTEGER")
            db.addColumnIfMissing("stamps", "assetVersion", "INTEGER NOT NULL DEFAULT 1")

            // ② 普通索引（名字与 v11/v12/v7/v8/v14 迁移逐字一致）
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_iataCode` ON `airports` (`iataCode`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_icaoCode` ON `airports` (`icaoCode`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_airports_country_popularity` " +
                    "ON `airports` (`country`, `popularity`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_isSynced_status` " +
                    "ON `flight_history` (`isSynced`, `status`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_endTimeMillis` " +
                    "ON `flight_history` (`endTimeMillis`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_flight_history_status_createdAt` " +
                    "ON `flight_history` (`status`, `createdAt`)",
            )

            // ③ focus_categories 唯一索引：改指引用 → 去重 → 建索引
            db.execSQL(
                """
                UPDATE `flight_history` SET `categoryId` = (
                    SELECT MIN(k.`id`) FROM `focus_categories` k
                    JOIN `focus_categories` c
                      ON k.`name` = c.`name` AND k.`isDefault` = c.`isDefault`
                    WHERE c.`id` = `flight_history`.`categoryId`
                )
                WHERE `categoryId` IN (SELECT `id` FROM `focus_categories`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE `blocked_apps` SET `categoryId` = (
                    SELECT MIN(k.`id`) FROM `focus_categories` k
                    JOIN `focus_categories` c
                      ON k.`name` = c.`name` AND k.`isDefault` = c.`isDefault`
                    WHERE c.`id` = `blocked_apps`.`categoryId`
                )
                WHERE `categoryId` IN (SELECT `id` FROM `focus_categories`)
                """.trimIndent(),
            )
            db.execSQL(
                """
                DELETE FROM `focus_categories`
                WHERE `id` NOT IN (
                    SELECT MIN(`id`) FROM `focus_categories`
                    GROUP BY `name`, `isDefault`
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_focus_categories_name_isDefault` " +
                    "ON `focus_categories` (`name`, `isDefault`)",
            )

            // ④ blocked_apps 唯一索引：去重（保留 id 最小）→ 建索引
            db.execSQL(
                """
                DELETE FROM `blocked_apps`
                WHERE `id` NOT IN (
                    SELECT MIN(`id`) FROM `blocked_apps`
                    GROUP BY `categoryId`, `packageName`
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_blocked_apps_categoryId_packageName` " +
                    "ON `blocked_apps` (`categoryId`, `packageName`)",
            )
        }
    }


    private fun SupportSQLiteDatabase.addColumnIfMissing(
        table: String,
        column: String,
        definition: String,
    ) {
        val exists = query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (!found && cursor.moveToNext()) {
                found = cursor.getString(nameIndex) == column
            }
            found
        }
        if (!exists) execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $definition")
    }

    /**
     * 全部迁移，按版本顺序排列。
     *
     * ⚠️ 这个列表的完整性由 `MigrationRegistryTest` 保证：
     *   1. 必须从 1 连续覆盖到 [AppDatabase.VERSION] - 1
     *   2. 不能有重复的 startVersion
     *   3. 每一项的 endVersion 必须等于 startVersion + 1
     */

    val MIGRATION_22_23 = object : Migration(22, 23) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `sceneSnapshotJson` TEXT DEFAULT NULL")
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `plannedDepartureJson` TEXT DEFAULT NULL")
        }
    }


    /** Own v24: independent nullable-destination archive and append-only positions. */
    val MIGRATION_23_24 = object : Migration(23, 24) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `free_flight_archive` (
                    `sessionId` TEXT NOT NULL,
                    `ownerScope` TEXT NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `activeSlot` INTEGER,
                    `updatedAtWallMs` INTEGER NOT NULL,
                    `payloadJson` TEXT NOT NULL,
                    PRIMARY KEY(`sessionId`)
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_free_flight_archive_activeSlot` " +
                    "ON `free_flight_archive` (`activeSlot`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_free_flight_archive_ownerScope_updatedAtWallMs` " +
                    "ON `free_flight_archive` (`ownerScope`, `updatedAtWallMs`)",
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `free_flight_position` (
                    `sessionId` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `activeElapsedMs` INTEGER NOT NULL,
                    `latitude` REAL NOT NULL,
                    `longitude` REAL NOT NULL,
                    `bearingDegrees` REAL NOT NULL,
                    `cumulativeDistanceKm` REAL NOT NULL,
                    `wallMs` INTEGER NOT NULL,
                    PRIMARY KEY(`sessionId`, `sequence`),
                    FOREIGN KEY(`sessionId`) REFERENCES `free_flight_archive`(`sessionId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_free_flight_position_sessionId` " +
                    "ON `free_flight_position` (`sessionId`)",
            )
        }
    }

    val MIGRATION_24_25 = object : Migration(24, 25) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `ownerScope` TEXT DEFAULT NULL")
        }
    }

    val MIGRATION_25_26 = object : Migration(25, 26) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS `flight_record_scene` (
                    `recordKind` TEXT NOT NULL,
                    `recordId` TEXT NOT NULL,
                    `ownerScope` TEXT NOT NULL,
                    `countdownId` TEXT,
                    `countUpId` TEXT,
                    `startedAtWallMs` INTEGER NOT NULL,
                    `revision` INTEGER NOT NULL,
                    `sceneJson` TEXT NOT NULL,
                    `updatedAtWallMs` INTEGER NOT NULL,
                    PRIMARY KEY(`recordKind`, `recordId`),
                    FOREIGN KEY(`countdownId`) REFERENCES `flight_history`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`countUpId`) REFERENCES `free_flight_archive`(`sessionId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_record_scene_ownerScope` ON `flight_record_scene` (`ownerScope`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_record_scene_countdownId` ON `flight_record_scene` (`countdownId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_flight_record_scene_countUpId` ON `flight_record_scene` (`countUpId`)")
        }
    }

    val MIGRATION_26_27 = object : Migration(26, 27) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `flight_history` ADD COLUMN `flightNumber` TEXT DEFAULT NULL")
        }
    }

    val ALL: List<Migration> = listOf(
        MIGRATION_1_2,
        MIGRATION_2_3,
        MIGRATION_3_4,
        MIGRATION_4_5,
        MIGRATION_5_6,
        MIGRATION_6_7,
        MIGRATION_7_8,
        MIGRATION_8_9,
        MIGRATION_9_10,
        MIGRATION_10_11,
        MIGRATION_11_12,
        MIGRATION_12_13,
        MIGRATION_13_14,
        MIGRATION_14_15,
        MIGRATION_15_16,
        MIGRATION_16_17,
        MIGRATION_17_18,
        MIGRATION_18_19,
        MIGRATION_19_20,
        MIGRATION_20_21,
        MIGRATION_21_22,
        MIGRATION_22_23,
        MIGRATION_23_24,
        MIGRATION_24_25,
        MIGRATION_25_26,
        MIGRATION_26_27,
    )
}
