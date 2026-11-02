package com.projectfocus.server

import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.config.ServerConfigKey
import com.projectfocus.server.db.DatabaseFactory
import com.projectfocus.server.domain.RuleBasedSceneContentChecker
import com.projectfocus.server.domain.SceneContentChecker
import com.projectfocus.server.plugins.configureHttp
import com.projectfocus.server.plugins.configureMonitoring
import com.projectfocus.server.plugins.configureSecurity
import com.projectfocus.server.plugins.configureSerialization
import com.projectfocus.server.route.registerRoutes
import com.projectfocus.server.route.registerFlightPlanRoutes
import com.projectfocus.server.route.registerSubscriptionStatusRoutes
import com.projectfocus.server.route.registerAuthPromoImageRoutes
import com.projectfocus.server.route.registerPasswordResetRoutes
import com.projectfocus.server.route.registerFreeFlightAdmissionRoutes
import com.projectfocus.server.route.registerAirportLocationRoutes
import com.projectfocus.server.domain.FreeFlightDevicePolicy
import com.projectfocus.server.sse.SseHub
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import java.time.Clock
import com.projectfocus.server.domain.RegistrationEmailDelivery
import com.projectfocus.server.domain.HttpRegistrationEmailDelivery

private val log = LoggerFactory.getLogger("com.projectfocus.server.Boot")

fun main() {
    val config = ServerConfig.fromEnv()

    log.info(
        "ProjectFocus server 启动：env={} port={} db={}",
        config.env,
        config.port,
        config.db.url.substringBefore(';').substringBefore('?'),
    )

    if (config.isProduction && config.db.isH2) {
        // H2 内存库在生产上意味着"重启即清空全部用户数据"。
        // 这类配置错误必须在启动时就拦掉，不能等第一个用户注册后才发现。
        error("生产环境不允许使用 H2（重启即丢数据）。请配置 PF_DB_URL 指向 PostgreSQL。")
    }

    embeddedServer(
        Netty,
        port = config.port,
        host = config.host,
        module = { module(config) },
    ).start(wait = true)
}












fun Application.module(
    config: ServerConfig = ServerConfig.fromEnv(),
    sceneContentChecker: SceneContentChecker = RuleBasedSceneContentChecker.fromEnv(),
    flightPlanClock: Clock = Clock.systemUTC(),
    subscriptionClock: Clock = Clock.systemUTC(),
    authPromoImageUrl: () -> String? = { System.getenv("AIRSTUDY_AUTH_PROMO_URL") },
    registrationEmailDelivery: RegistrationEmailDelivery = HttpRegistrationEmailDelivery.fromEnv(),
    registrationClock: Clock = Clock.systemUTC(),
    freeFlightDevicePolicy: FreeFlightDevicePolicy = FreeFlightDevicePolicy.fromEnv(),
    freeFlightAdmissionClock: Clock = Clock.systemUTC(),
) {
    attributes.put(ServerConfigKey, config)

    // ① 数据库
    val database = DatabaseFactory.init(config.db)
    monitor.subscribe(ApplicationStopped) { database.close() }

    // ② 应用的协程作用域：SSE 广播、定时器都挂在这里
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val sseHub = SseHub(appScope, config.sse)

    // ③ 基础设施插件
    configureSerialization()
    configureMonitoring(config)
    configureHttp(config)
    configureSecurity(config)
    registerFlightPlanRoutes(database.database, flightPlanClock)
    registerSubscriptionStatusRoutes(database.database, subscriptionClock)
    registerAuthPromoImageRoutes(authPromoImageUrl)

    // ④ 路由
    registerRoutes(database, sseHub, config, sceneContentChecker, registrationEmailDelivery, registrationClock)
    // Own routes/delivery policy; no original service or startup email request.
    registerPasswordResetRoutes(database.database, registrationEmailDelivery, config.jwt.secret, registrationClock)
    registerFreeFlightAdmissionRoutes(database.database, config.jwt, freeFlightDevicePolicy, freeFlightAdmissionClock)
    registerAirportLocationRoutes()

    log.info("路由注册完成")
}
