package com.projectfocus.server.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import java.time.Instant



















object Tables {

    // ═══════════════════════════════════════════════════════
    // 用户
    // ═══════════════════════════════════════════════════════

    object Users : Table("users") {
        val id = varchar("id", 36)                     // UUID
        val email = varchar("email", 254).uniqueIndex()
        val passwordHash = varchar("password_hash", 72) // bcrypt 固定 60 字符，留余量
        val username = varchar("username", 20)
        val avatarId = integer("avatar_id").default(0)
        val isPremium = bool("is_premium").default(false)

        val xp = integer("xp").default(0)
        val streak = integer("streak").default(0)
        val totalGroupFlight = integer("total_group_flight").default(0)
        val totalHostedFlight = integer("total_hosted_flight").default(0)
        val challengesCompleted = integer("challenges_completed").default(0)
        val totalFocusedMinutes = integer("total_focused_minutes").default(0)
        val countriesVisited = integer("countries_visited").default(0)
        val perfectWeeks = integer("perfect_weeks").default(0)


        val rank = integer("rank").default(0)









        val createdAt = timestamp("created_at").clientDefault { Instant.now() }
        val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

        override val primaryKey = PrimaryKey(id)
    }









    object UserStats : Table("user_stats") {
        val userId = varchar("user_id", 36).references(Users.id)
        /** 用户所在时区的当日 0 点（epoch day），不是 UTC */
        val epochDay = long("epoch_day")
        val focusMinutes = integer("focus_minutes").default(0)
        val flights = integer("flights").default(0)

        override val primaryKey = PrimaryKey(userId, epochDay)
    }

    // ═══════════════════════════════════════════════════════
    // 机场与飞行记录
    // ═══════════════════════════════════════════════════════

    object Airports : Table("airports") {
        val code = varchar("code", 4)                  // IATA 3 位，留 1 位给未来
        val name = varchar("name", 128)
        val city = varchar("city", 64)
        val country = varchar("country", 64)
        val state = varchar("state", 64).default("")
        val latitude = double("latitude")
        val longitude = double("longitude")
        /** 累计有多少次航班把这里当目的地 —— 用于"热门目的地"排序 */
        val popularity = integer("popularity").default(0)

        override val primaryKey = PrimaryKey(code)
    }

    object Flights : Table("flights") {
        val id = varchar("id", 36)
        val userId = varchar("user_id", 36).references(Users.id)
        val roomId = varchar("room_id", 36).nullable()

        val focusType = varchar("focus_type", 16).default("SOLO")
        val departureCode = varchar("departure_code", 4)
        val destinationCode = varchar("destination_code", 4)


        val startTimeMillis = long("start_time_millis")
        val endTimeMillis = long("end_time_millis").nullable()
        val durationMinutes = integer("duration_minutes")
        val actualFocusMinutes = integer("actual_focus_minutes")
        val distanceKm = double("distance_km")

        val status = varchar("status", 16).default("COMPLETED")
        val seat = varchar("seat", 4).nullable()
        val quote = varchar("quote", 256).nullable()
        val quoteAuthor = varchar("quote_author", 64).nullable()

        val createdAt = timestamp("created_at").clientDefault { Instant.now() }
        val updatedAt = timestamp("updated_at").clientDefault { Instant.now() }

        override val primaryKey = PrimaryKey(id)

        init {

            //   `WHERE user_id = ? ORDER BY start_time_millis DESC LIMIT 20`
            //   没有这个复合索引，用户飞到第 500 次时打开记录页要全表扫。
            index(isUnique = false, userId, startTimeMillis)
            index(isUnique = false, roomId)
        }
    }

    // ═══════════════════════════════════════════════════════
    // 组队房间
    // ═══════════════════════════════════════════════════════

    object Rooms : Table("rooms") {
        val id = varchar("id", 36)
        val creatorId = varchar("creator_id", 36).references(Users.id)


        val inviteCode = varchar("invite_code", 6).uniqueIndex()

        val durationMinutes = integer("duration_minutes")
        val chatEnabledDuringSession = bool("chat_enabled_during_session").default(false)

        /** WAITING / SCHEDULED / STARTED / COMPLETED / CANCELLED */
        val state = varchar("state", 16).default("WAITING")
        val isPrivate = bool("is_private").default(false)

        val startedAt = long("started_at").nullable()
        val completedAt = long("completed_at").nullable()
        val willStartAt = long("will_start_at").nullable()
        val createdAt = long("created_at")

        // 出发/目的地快照（6 个字段 × 2）—— 这里**保留**冗余，
        // 与 flights 表相反：房间是"即将飞的航线"，机场信息必须冻结在创建那一刻。
        val depCode = varchar("dep_code", 4)
        val depCountry = varchar("dep_country", 64).default("")
        val depName = varchar("dep_name", 128).default("")
        val depState = varchar("dep_state", 64).default("")
        val depLat = double("dep_lat").default(0.0)
        val depLng = double("dep_lng").default(0.0)

        val dstCode = varchar("dst_code", 4)
        val dstCountry = varchar("dst_country", 64).default("")
        val dstName = varchar("dst_name", 128).default("")
        val dstState = varchar("dst_state", 64).default("")
        val dstLat = double("dst_lat").default(0.0)
        val dstLng = double("dst_lng").default(0.0)

        override val primaryKey = PrimaryKey(id)

        init {
            // "公开房间列表"查询：state in (WAITING, SCHEDULED) AND is_private = false
            index(isUnique = false, state, isPrivate, createdAt)
            index(isUnique = false, creatorId)
        }
    }

    object RoomParticipants : Table("room_participants") {
        val id = varchar("id", 36)
        val roomId = varchar("room_id", 36).references(Rooms.id)
        val userId = varchar("user_id", 36).references(Users.id)

        val joinedAt = long("joined_at")
        val leftAt = long("left_at").nullable()
        val completed = bool("completed").default(false)

        val seatNumber = integer("seat_number").nullable()
        val seatColumn = varchar("seat_column", 1).nullable()

        override val primaryKey = PrimaryKey(id)

        init {

            //   Exposed 不支持部分唯一索引，所以这条约束由业务层在 join 时
            //   用 `SELECT ... FOR UPDATE` + 判空保证，见 RoomService.join()。
            index(isUnique = false, roomId, userId)
            // 座位唯一：同一房间同一排同一列不能坐两个人。
            // ⚠️ 这里不建唯一索引，因为 left_at 非空的行应该被排除；
            //    同样交给业务层在事务里判重（见 RoomService.pickSeat）。
            index(isUnique = false, roomId, seatNumber, seatColumn)
        }
    }

    object RoomMessages : Table("room_messages") {
        val id = varchar("id", 36)
        val roomId = varchar("room_id", 36).references(Rooms.id)
        val userId = varchar("user_id", 36).references(Users.id)













        val message = varchar("message", 600)
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(isUnique = false, roomId, createdAt)
        }
    }

    // ═══════════════════════════════════════════════════════
    // 认证
    // ═══════════════════════════════════════════════════════


    object RefreshTokens : Table("refresh_tokens") {
        /** 存的是 token 的 SHA-256，不是原文 —— 数据库泄漏时不能直接冒充用户 */
        val tokenHash = varchar("token_hash", 64)
        val userId = varchar("user_id", 36).references(Users.id)
        val issuedAt = long("issued_at")
        val expiresAt = long("expires_at")
        val revokedAt = long("revoked_at").nullable()
        val userAgent = varchar("user_agent", 256).default("")

        override val primaryKey = PrimaryKey(tokenHash)

        init {
            index(isUnique = false, userId)
        }
    }
}
