package com.projectfocus.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import com.projectfocus.server.config.DbConfig
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import javax.sql.DataSource





















object DatabaseFactory {

    private val log = LoggerFactory.getLogger(DatabaseFactory::class.java)

    fun init(config: DbConfig): Handle {
        val hikari = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            driverClassName = if (config.isH2) "org.h2.Driver" else "org.postgresql.Driver"

            maximumPoolSize = config.maxPoolSize
            minimumIdle = 1
            // 连接池满了以后最多等 3 秒。等太久会让请求堆积到 Netty 的
            // 事件循环，最后整个服务无响应 —— 快速失败好过雪崩。
            connectionTimeout = 3_000
            // 空闲连接 10 分钟回收；SQLite/H2 无所谓，PG 侧省进程
            idleTimeout = 600_000
            maxLifetime = 1_800_000

            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            // 连接校验：防止拿到已被中间网络设备掐死的"僵尸连接"
            validationTimeout = 2_000
            poolName = "projectfocus-pool"

            if (config.isH2) {
                // H2 内存库：允许并发读写 + 兼容 PostgreSQL 语法
                addDataSourceProperty("DB_CLOSE_DELAY", "-1")
            }
        }

        val dataSource = HikariDataSource(hikari)
        val database = Database.connect(dataSource)

        return Handle(dataSource, database, config.isH2).also { handle ->
            handle.migrate()
            log.info("数据库就绪（{}），连接池上限 {}",
                if (config.isH2) "H2" else "PostgreSQL", config.maxPoolSize)
        }
    }















    private fun Handle.migrate() {
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES)
        }
        log.info("已同步 {} 张表", ALL_TABLES.size)
    }

    /** 表清单 —— 新增表必须登记在这里，否则永远不会被创建 */
    private val ALL_TABLES: Array<Table> = arrayOf(
        Tables.Users,
        Tables.UserStats,
        Tables.Airports,
        Tables.Flights,
        Tables.Rooms,
        Tables.RoomParticipants,
        Tables.RoomMessages,
        Tables.RefreshTokens,
        FlightPlanDays,
        SubscriptionEntitlements,
        RegistrationChallenges,
        PasswordResetChallenges,
        FreeFlightDevices,
        FreeFlightDeviceGrants,
        FreeFlightAdmissionOperations,
    )

    class Handle(
        val dataSource: DataSource,
        val database: Database,
        private val isH2: Boolean,
    ) {
        fun close() {
            (dataSource as? HikariDataSource)?.close()
        }
    }
}
