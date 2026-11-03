package com.projectfocus.core.network.error

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.google.common.truth.Truth.assertThat
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.UnknownServiceException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
















@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ApiResultMapperTest {

    // ═══════════════════════════════════════════════════════
    // IO 继承树：每条都必须落到"最具体"的那一类
    // ═══════════════════════════════════════════════════════

    @Test
    fun `DNS 解析失败归为无网络`() {
        val error = ApiResultMapper.classifyThrowable(UnknownHostException("api.projectfocus.app"))
        assertThat(error).isInstanceOf(ApiError.NoNetwork::class.java)
        assertThat(error.isRetryable).isFalse()
    }

    @Test
    fun `连接被拒归为无网络`() {
        assertThat(ApiResultMapper.classifyThrowable(ConnectException("Connection refused")))
            .isInstanceOf(ApiError.NoNetwork::class.java)
    }

    @Test
    fun `无路由与端口不可达都归为无网络`() {
        assertThat(ApiResultMapper.classifyThrowable(NoRouteToHostException("no route")))
            .isInstanceOf(ApiError.NoNetwork::class.java)
        assertThat(ApiResultMapper.classifyThrowable(PortUnreachableException("icmp port unreachable")))
            .isInstanceOf(ApiError.NoNetwork::class.java)
    }

    @Test
    fun `SocketTimeoutException 归为超时而不是 IO 错误`() {

        //   InterruptedIOException 的父类才是 IOException。
        //   分支顺序错一位，这里就会变成 NoNetwork。
        val error = ApiResultMapper.classifyThrowable(SocketTimeoutException("timeout"))
        assertThat(error).isInstanceOf(ApiError.Timeout::class.java)
        assertThat(error.isRetryable).isTrue()
    }

    @Test
    fun `SSL 异常不被 SocketException 分支吞掉`() {

        //    SSLException 继承自 SocketException。
        //    如果 `when` 里 SocketException 排在前面，下列三条全部变 NoNetwork，
        //    用户会被引导去"检查网络"——而真实原因是证书/协议问题，查网络永远无用。
        listOf(
            SSLException("handshake"),
            SSLHandshakeException("bad cert"),
            SSLPeerUnverifiedException("hostname mismatch"),
        ).forEach { t ->
            val error = ApiResultMapper.classifyThrowable(t)
            assertThat(error)
                .isInstanceOf(ApiError.Unknown::class.java)
            assertThat(error.debugMessage).contains("TLS")
        }
    }

    @Test
    fun `裸 SocketException 归为无网络`() {
        assertThat(ApiResultMapper.classifyThrowable(SocketException("Socket closed")))
            .isInstanceOf(ApiError.NoNetwork::class.java)
    }

    @Test
    fun `响应流提前结束归为超时`() {
        // EOFException：服务端断流或代理截断。重试有意义（换一条链路）。
        assertThat(ApiResultMapper.classifyThrowable(EOFException("unexpected end of stream")))
            .isInstanceOf(ApiError.Timeout::class.java)
        assertThat(ApiResultMapper.classifyThrowable(InterruptedIOException("interrupted")))
            .isInstanceOf(ApiError.Timeout::class.java)
    }

    @Test
    fun `协议异常归为序列化而不是无网络`() {
        // 命中了错误端口（如给了明文端口却按 https 连）时 OkHttp 抛这个。
        // 归成"网络"会误导用户反复切 WiFi。
        assertThat(ApiResultMapper.classifyThrowable(UnknownServiceException("unexpected protocol")))
            .isInstanceOf(ApiError.Serialization::class.java)
    }

    @Test
    fun `兜底 IO 异常归为无网络`() {
        assertThat(ApiResultMapper.classifyThrowable(IOException("generic")))
            .isInstanceOf(ApiError.NoNetwork::class.java)
    }

    @Test
    fun `非 IO 异常归为未知`() {
        val error = ApiResultMapper.classifyThrowable(IllegalStateException("boom"))
        assertThat(error).isInstanceOf(ApiError.Unknown::class.java)
        assertThat(error.debugMessage).contains("IllegalStateException")
        assertThat(error.isRetryable).isFalse()
    }

    // ═══════════════════════════════════════════════════════
    // HTTP 状态码
    // ═══════════════════════════════════════════════════════

    @Test
    fun `401 与 403 都归为登录过期且不可重试`() {
        listOf(401, 403).forEach { code ->
            val error = ApiResultMapper.classifyHttp(code)
            assertThat(error).isInstanceOf(ApiError.Unauthorized::class.java)
            assertThat(error.isRetryable).isFalse()
        }
    }

    @Test
    fun `429 归为限流且可重试`() {
        val error = ApiResultMapper.classifyHttp(429)
        assertThat(error).isInstanceOf(ApiError.RateLimited::class.java)
        assertThat(error.isRetryable).isTrue()
    }

    @Test
    fun `5xx 归为服务端错误且可重试，但 501 不可重试`() {
        val e500 = ApiResultMapper.classifyHttp(500)
        assertThat(e500).isInstanceOf(ApiError.ServerError::class.java)
        assertThat(e500.isRetryable).isTrue()


        val e501 = ApiResultMapper.classifyHttp(501)
        assertThat(e501).isInstanceOf(ApiError.ServerError::class.java)
        assertThat(e501.isRetryable).isFalse()
    }

    @Test
    fun `404 归为业务错误而不是无网络`() {
        val error = ApiResultMapper.classifyHttp(404)
        assertThat(error).isInstanceOf(ApiError.Business::class.java)
        assertThat(error.isRetryable).isFalse()
        assertThat((error as ApiError.Business).serverMessage).isEqualTo("请求的资源不存在")
    }

    @Test
    fun `4xx 时优先采用服务端给的 message`() {
        val body = """{"error":{"code":1001,"message":"邀请码已失效","traceId":"tr_abc"}}"""
        val error = ApiResultMapper.classifyHttp(404, body) as ApiError.Business

        assertThat(error.serverMessage).isEqualTo("邀请码已失效")
        assertThat(error.traceId).isEqualTo("tr_abc")
    }

    @Test
    fun `5xx 时也带上服务端 message 与 traceId`() {
        val body = """{"message":"数据库连接池耗尽","traceId":"tr_500"}"""
        val error = ApiResultMapper.classifyHttp(503, body) as ApiError.ServerError
        assertThat(error.debugMessage).isEqualTo("数据库连接池耗尽")
        assertThat(error.traceId).isEqualTo("tr_500")
        assertThat(error.httpCode).isEqualTo(503)
    }

    @Test
    fun `网关返回的 HTML 错误页不会让解析崩溃`() {
        val html = "<html><body><h1>502 Bad Gateway</h1></body></html>"
        val error = ApiResultMapper.classifyHttp(502, html)
        assertThat(error).isInstanceOf(ApiError.ServerError::class.java)
        // 提不出 message 时就用默认文案，不能把 HTML 原样丢进 UI
        assertThat((error as ApiError.ServerError).debugMessage).isEqualTo("服务端错误 HTTP 502")
    }

    @Test
    fun `超长错误体被截断而不是整段进埋点`() {
        val huge = """{"message":"${"x".repeat(20000)}"}"""
        val error = ApiResultMapper.classifyHttp(500, huge) as ApiError.ServerError
        // 截断到 4096 后再提取，message 长度不会超过 4096
        assertThat(error.debugMessage.length).isAtMost(4096)
    }

    // ═══════════════════════════════════════════════════════
    // 消息提取的边界：转义 / 未闭合 / 字段不存在
    // ═══════════════════════════════════════════════════════

    @Test
    fun `message 里的转义引号被正确还原`() {
        val body = """{"message":"房间 \"A380\" 已满"}"""
        val error = ApiResultMapper.classifyHttp(400, body) as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("""房间 "A380" 已满""")
    }

    @Test
    fun `message 里的换行转义被还原`() {
        val body = """{"message":"第一行\n第二行"}"""
        val error = ApiResultMapper.classifyHttp(400, body) as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("第一行\n第二行")
    }

    @Test
    fun `message 里的 unicode 转义被还原`() {
        // \u98de\u673a = 飞机
        val body = """{"message":"\u98de\u673a\u5df2\u6ee1"}"""
        val error = ApiResultMapper.classifyHttp(400, body) as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("飞机已满")
    }

    @Test
    fun `未闭合的 message 也能读到已收到的部分`() {
        // 代理截断 body 是真实存在的（尤其大响应 + 弱网）
        val body = """{"message":"连接已中断，请重"""
        val error = ApiResultMapper.classifyHttp(400, body) as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("连接已中断，请重")
    }

    @Test
    fun `空 message 视为未提供`() {
        val body = """{"message":""}"""
        val error = ApiResultMapper.classifyHttp(400, body) as ApiError.Business
        assertThat(error.serverMessage).isEqualTo("请求参数有误")
    }

    @Test
    fun `body 为空时用默认文案`() {
        assertThat((ApiResultMapper.classifyHttp(400, null) as ApiError.Business).serverMessage)
            .isEqualTo("请求参数有误")
        assertThat((ApiResultMapper.classifyHttp(400, "   ") as ApiError.Business).serverMessage)
            .isEqualTo("请求参数有误")
    }

    // ═══════════════════════════════════════════════════════
    // ApiException 不二次分类
    // ═══════════════════════════════════════════════════════

    @Test
    fun `已分类的 ApiException 再分类时保持原样`() {
        val original = ApiError.Unauthorized()
        val ex = ApiException(original)
        assertThat(ApiResultMapper.classifyThrowable(ex)).isSameInstanceAs(original)
    }

    @Test
    fun `ApiException 的 message 就是 debugMessage`() {
        val ex = ApiException(ApiError.Timeout())
        assertThat(ex.message).isEqualTo("请求超时")
        assertThat(ex.toString()).contains("Timeout")
    }
}
