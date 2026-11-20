package me.ykrank.s1next.widget.hostcheck

import me.ykrank.s1next.data.api.Api
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.ProtocolException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Fork: Bounded recovery for forum, thread-list, and post-list reads. */
internal class ForumReadRetryInterceptor(private val host: AppHostUrl) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host !in Api.HOST_LIST) return chain.proceed(request)

        val primary = host.baseHttpUrl?.let { onRoute(request, it) } ?: request
        if (request.method != "GET" || request.url.queryParameter("module") !in readModules) {
            return chain.proceed(primary)
        }

        val routes = (listOf(primary) + host.fallbackForumUrls.map { onRoute(request, it) } + request)
            .distinctBy { it.url }
        // With no alternate route, allow one fresh attempt on the same URL.
        val attempts = if (routes.size == 1) routes + primary else routes
        val retryChain = chain.withConnectTimeout(5, TimeUnit.SECONDS)
        for ((index, attempt) in attempts.withIndex()) {
            val last = index == attempts.lastIndex
            try {
                val response = retryChain.proceed(attempt)
                if (last || response.code !in temporaryHttpErrors || chain.call().isCanceled()) return response
                response.close()
            } catch (e: IOException) {
                if (last || chain.call().isCanceled() || e is SSLException || e is ProtocolException) throw e
            }
        }
        error("Empty forum route list")
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
    }
}
