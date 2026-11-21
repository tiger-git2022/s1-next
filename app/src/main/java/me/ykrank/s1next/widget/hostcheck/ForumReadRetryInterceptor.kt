package me.ykrank.s1next.widget.hostcheck

import android.os.SystemClock
import me.ykrank.s1next.data.api.Api
import me.ykrank.s1next.widget.net.ForegroundConnectionResetter
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
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
        // 无备用线路时允许最多 3 次尝试，覆盖后台切回前台瞬间网络解冻/5G 唤醒丢包
        val attempts = if (routes.size == 1) listOf(primary, primary, primary) else routes
        for ((index, attempt) in attempts.withIndex()) {
            val last = index == attempts.lastIndex
            // 非末次尝试收紧超时（连接 2.5s、读 4s），首包被静默丢弃时更快切到重试；末次保留 5s/10s 兜底慢网
            val attemptChain = if (last) {
                chain.withConnectTimeout(5, TimeUnit.SECONDS)
            } else {
                chain.withConnectTimeout(2500, TimeUnit.MILLISECONDS)
                    .withReadTimeout(4000, TimeUnit.MILLISECONDS)
            }
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val response = attemptChain.proceed(attempt)
                if (last || response.code !in temporaryHttpErrors || chain.call().isCanceled()) return response
                response.close()
                backoffIfFastFailure(startedAt, chain)
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
    }
}
