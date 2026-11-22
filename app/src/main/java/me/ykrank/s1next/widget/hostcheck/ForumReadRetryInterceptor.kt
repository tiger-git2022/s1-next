package me.ykrank.s1next.widget.hostcheck

import android.os.SystemClock
import me.ykrank.s1next.data.api.Api
import me.ykrank.s1next.widget.net.ForegroundConnectionResetter
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Fork: Bounded recovery for forum, thread-list, and post-list reads. */
internal class ForumReadRetryInterceptor(
    private val host: AppHostUrl,
    private val connectionResetter: ForegroundConnectionResetter? = null,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        connectionResetter?.evictIfPending()
        val request = chain.request()
        if (request.url.host !in Api.HOST_LIST) return chain.proceed(request)

        val primary = host.baseHttpUrl?.let { onRoute(request, it) } ?: request
        if (request.method != "GET" || request.url.queryParameter("module") !in readModules) {
            return chain.proceed(primary)
        }

        val routes = (listOf(primary) + host.fallbackForumUrls.map { onRoute(request, it) } + request)
            .distinctBy { it.url }
        // 无备用线路时允许最多 3 次尝试，覆盖后台解冻、5G 唤醒及源站响应体卡顿丢包
        val attempts = if (routes.size == 1) listOf(primary, primary, primary) else routes
        for ((index, attempt) in attempts.withIndex()) {
            val last = index == attempts.lastIndex
            // 阶梯超时：首次 2.5s/3s 快速自救，二次 3s/5s，末次 5s/10s（默认）兜底慢网
            val attemptChain = when {
                last -> chain.withConnectTimeout(5, TimeUnit.SECONDS)
                index == 0 -> chain.withConnectTimeout(2500, TimeUnit.MILLISECONDS)
                    .withReadTimeout(3000, TimeUnit.MILLISECONDS)
                else -> chain.withConnectTimeout(3000, TimeUnit.MILLISECONDS)
                    .withReadTimeout(5000, TimeUnit.MILLISECONDS)
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val response = attemptChain.proceed(attempt)
                if (last || chain.call().isCanceled()) return response
                if (response.code in temporaryHttpErrors) {
                    response.close()
                    backoffIfFastFailure(startedAt, chain)
                    continue
                }
                // 在拦截器内完整读取正文，避免源站回完 HTTP 200 头后正文传输卡死逃逸重试
                return bufferResponseBody(response)
            } catch (e: IOException) {
                if (last || chain.call().isCanceled() || e is SSLHandshakeException ||
                    e is SSLPeerUnverifiedException || e is ProtocolException
                ) {
                    throw e
                }
                backoffIfFastFailure(startedAt, chain)
            }
        }
        error("Empty forum route list")
    }

    private fun bufferResponseBody(response: Response): Response {
        val body = response.body
        if (body.contentLength() > MAX_BUFFERED_BYTES) {
            return response
        }
        val contentType = body.contentType()
        val bytes = body.use { it.bytes() }
        return response.newBuilder()
            .body(bytes.toResponseBody(contentType))
            .build()
    }

    /** 后台恢复瞬间若系统网络规则尚未放行而毫秒级报错，短暂等待再重试，避免连续撞在解冻窗口里 */
    private fun backoffIfFastFailure(startedAt: Long, chain: Interceptor.Chain) {
        if (SystemClock.elapsedRealtime() - startedAt < FAST_FAILURE_MS && !chain.call().isCanceled()) {
            try {
                Thread.sleep(FAST_FAILURE_BACKOFF_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun onRoute(request: Request, base: HttpUrl): Request {
        val original = request.url
        val path = original.encodedPath.removePrefix(if (original.pathSegments.firstOrNull() == "2b") "/2b/" else "/")
        val target = base.newBuilder()
            .encodedPath(base.encodedPath + path)
            .encodedQuery(original.encodedQuery)
            .build()
        return if (target == original) request else request.newBuilder().url(target).removeHeader("Host").build()
    }

    companion object {
        private val readModules = setOf("forumindex", "forumdisplay", "viewthread")
        private val temporaryHttpErrors = setOf(500, 502, 503, 504)
        private const val FAST_FAILURE_MS = 500L
        private const val FAST_FAILURE_BACKOFF_MS = 300L
        private const val MAX_BUFFERED_BYTES = 4L * 1024 * 1024
    }
}
