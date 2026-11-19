package com.projectfocus.server.config

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import org.slf4j.LoggerFactory




















data class ServerConfig(
    val env: Env,
    val host: String,
    val port: Int,
    val db: DbConfig,
    val jwt: JwtConfig,
    val sse: SseConfig,
    val rateLimit: RateLimitConfig,
) {
    enum class Env { Dev, Staging, Prod;

        companion object {
            fun parse(raw: String?): Env = when (raw?.lowercase()) {
                "prod", "production" -> Prod
                "staging", "stage" -> Staging
                else -> Dev
            }
        }
    }

    val isProduction: Boolean get() = env == Env.Prod

    companion object {
        private val log = LoggerFactory.getLogger(ServerConfig::class.java)

        fun fromEnv(getenv: (String) -> String? = System::getenv): ServerConfig {
            val env = Env.parse(getenv("PF_ENV"))

            val db = DbConfig(
                url = getenv("PF_DB_URL") ?: "jdbc:h2:mem:pf;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                user = getenv("PF_DB_USER") ?: "sa",
                password = getenv("PF_DB_PASSWORD").orEmpty(),
                maxPoolSize = getenv("PF_DB_POOL")?.toIntOrNull() ?: if (env == Env.Prod) 20 else 4,
            )

            val rawSecret = getenv("PF_JWT_SECRET")
            val jwt = when {
                // HS256 要求密钥至少 32 字节；短于 32 的密钥会被 jjwt 直接拒绝签名
                rawSecret != null && rawSecret.toByteArray().size >= 32 ->
                    JwtConfig(secret = rawSecret, issuer = getenv("PF_JWT_ISSUER") ?: "projectfocus", ttlHours = 24 * 30)

                env == Env.Prod -> error(
                    "PF_JWT_SECRET 未设置或短于 32 字节。生产环境不允许使用默认密钥 —— " +
                        "那等于任何人都能伪造任意用户的 token。",
                )

                else -> {
                    log.warn(
                        "★★ 正在使用内置开发 JWT 密钥（PF_ENV={}）。仅限本地开发，切勿部署。",
                        env,
                    )
                    JwtConfig(
                        secret = "dev-only-secret-change-me-in-production-32bytes-minimum",
                        issuer = "projectfocus-dev",
                        ttlHours = 24 * 30,
                    )
                }
            }

            return ServerConfig(
                env = env,
                // 0.0.0.0 而不是 localhost：容器里必须监听全部网卡，否则探针打不进来
                host = getenv("PF_HOST") ?: "0.0.0.0",
                port = getenv("PORT")?.toIntOrNull() ?: getenv("PF_PORT")?.toIntOrNull() ?: 8080,
                db = db,
                jwt = jwt,
                sse = SseConfig(
                    heartbeatSeconds = getenv("PF_SSE_HEARTBEAT")?.toLongOrNull() ?: 15L,
                    maxConnectionsPerUser = getenv("PF_SSE_MAX_PER_USER")?.toIntOrNull() ?: 3,
                ),
                rateLimit = RateLimitConfig(

                    joinPerMinute = getenv("PF_RL_JOIN")?.toIntOrNull() ?: 20,
                    chatPerMinute = getenv("PF_RL_CHAT")?.toIntOrNull() ?: 60,
                    generalPerMinute = getenv("PF_RL_GENERAL")?.toIntOrNull() ?: 300,
                ),
            )
        }
    }
}

data class DbConfig(
    val url: String,
    val user: String,
    val password: String,
    val maxPoolSize: Int,
) {
    /** H2 只用于本地与测试；生产必须是 PostgreSQL */
    val isH2: Boolean get() = url.startsWith("jdbc:h2")
}

data class JwtConfig(
    val secret: String,
    val issuer: String,
    val ttlHours: Int,
) {
    val ttlMillis: Long get() = ttlHours * 3600_000L
}

data class SseConfig(
    val heartbeatSeconds: Long,
    /** 同一用户最多允许多少条 SSE 连接（防止开 100 个页面把连接打满） */
    val maxConnectionsPerUser: Int,
)

data class RateLimitConfig(
    val joinPerMinute: Int,
    val chatPerMinute: Int,
    val generalPerMinute: Int,
)

/** 把配置挂到 `Application.attributes` 上，供各插件读取 */
val ServerConfigKey = io.ktor.util.AttributeKey<ServerConfig>("ProjectFocusServerConfig")

fun Application.serverConfig(): ServerConfig =
    attributes.getOrNull(ServerConfigKey)
        ?: error("ServerConfig 未装载 —— 检查 Application.module() 的开头")
