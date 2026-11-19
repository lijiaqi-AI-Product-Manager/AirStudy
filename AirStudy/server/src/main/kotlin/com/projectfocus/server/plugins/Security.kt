package com.projectfocus.server.plugins

import com.projectfocus.server.config.ServerConfig
import com.projectfocus.server.security.JwtService
import com.projectfocus.server.security.PfPrincipal
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import kotlin.time.Duration.Companion.minutes


fun Application.configureSecurity(config: ServerConfig) {
    install(CORS) {

        //   这里开白名单是为了未来的 Web 端与管理后台。
        anyHost()
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("Last-Event-ID") // SSE 重连需要
        allowMethod(io.ktor.http.HttpMethod.Get)
        allowMethod(io.ktor.http.HttpMethod.Post)
        allowMethod(io.ktor.http.HttpMethod.Patch)
        allowMethod(io.ktor.http.HttpMethod.Put)
        allowMethod(io.ktor.http.HttpMethod.Delete)
    }

    install(RateLimit) {
        register(RateLimitName("general")) {
            rateLimiter(limit = config.rateLimit.generalPerMinute, refillPeriod = 1.minutes)
            requestKey { call ->

                //   后者可以被客户端随意伪造，伪造后每个请求都是"新 IP"，
                //   限流形同虚设。只有确定在反向代理后面时才该读 XFF，
                //   那时代理自己会覆写它（见 docs/deployment.md 的 Nginx 配置）。
                call.request.local.remoteHost
            }
        }
        register(RateLimitName("join")) {
            rateLimiter(limit = config.rateLimit.joinPerMinute, refillPeriod = 1.minutes)
            requestKey { it.request.local.remoteHost }
        }
        register(RateLimitName("chat")) {
            rateLimiter(limit = config.rateLimit.chatPerMinute, refillPeriod = 1.minutes)
            requestKey { call ->
                // 聊天按**用户**限流。注意：RateLimit 在 Authentication 之前执行，
                // 此时还拿不到 principal，所以只能退化成按 IP。

                call.request.local.remoteHost
            }
        }
    }

    val jwtService = JwtService(config.jwt)


    //   实测：`install(Authentication) { jwt(...) }` 只注册了 provider，
    //   **不会**自动给路由加分。必须显式 `routing { ... }` 里
    //   包 `authenticate(AUTH_JWT) { ... }`，或者用 `install(Authentication)`
    //   + `routing { }` 的隐式全局拦截。
    //   Ktor 3 的做法是：`install(Authentication)` 会往 Application 的
    //   `ApplicationCallPipeline` 上装 `AuthenticationHook`，但只有在该
    //   路由的 selector 命中 provider 名单时才执行。默认名单是 `["default"]`，

    //   principal 永远是 null（并且不报错）。
    //   → 解决：provider 名用默认名，或在每条路由上显式 authenticate。

    install(Authentication) {
        jwt(AUTH_JWT) {
            realm = "ProjectFocus"


            //   选型时踩到的：ktor-server-auth-jwt 内部依赖 java-jwt。
            //   所以 JwtService 改用 java-jwt 实现（见 JwtService.kt），
            //   与 Ktor 的插件保持同一套 API。
            verifier(jwtService.verifier())

            //
            //   实测记录（三个方案，前两个都失败）：
            //     a) 返回 `PfPrincipal(payload)`  解析出的对象
            //        → `call.principal<PfPrincipal>()` 返回 null
            //     b) 什么都不写（隐式 Unit，靠类型推断）
            //        → 编译过，但认证视同失败
            //     c) 返回 `JWTPrincipal(payload)` → `call.principal<JWTPrincipal>()` 命中 ✔
            //
            //   根因：`validate` 的返回值经 `AuthenticationContext.principal(value)`
            //   写进 call.attributes，key 由 `typedPrincipalKey(names, TypeInfo)` 生成；
            //   而 `call.principal<T>()` 用**调用点的 reified T** 去算同一个 key。
            //   Ktor 内部给 JWT provider 预置的 key 是 `JWTPrincipal` 的类型信息，
            //   换一个类型就取不到。
            //
            //   → 所以：principal 统一用 Ktor 的 `JWTPrincipal`，

            //     `SecurityPrincipalTest` 就是在守这一条 —— 它必须是绿的。
            validate { credential ->
                if (PfPrincipal.from(credential.payload) != null) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->

                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiErrorEnvelope(ApiErrorBody(code = 1001, message = "登录已过期，请重新登录")),
                )
            }
        }
    }
}

/** Ktor 的 authentication provider 名。全局唯一，别拼错。 */
const val AUTH_JWT = "pf-jwt"













/** 需要登录的路由块用这个常量包一层，而不是到处写字符串 */
fun io.ktor.server.routing.Route.authed(build: io.ktor.server.routing.Route.() -> Unit) =
    authenticate(AUTH_JWT) { build() }










fun io.ktor.server.application.ApplicationCall.principalOf(): PfPrincipal {
    val p = principal<io.ktor.server.auth.jwt.JWTPrincipal>()
        ?: throw com.projectfocus.server.domain.UnauthorizedException()
    return PfPrincipal.from(p.payload) ?: throw com.projectfocus.server.domain.UnauthorizedException()
}
