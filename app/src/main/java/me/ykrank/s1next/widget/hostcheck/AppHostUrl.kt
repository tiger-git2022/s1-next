package me.ykrank.s1next.widget.hostcheck

import com.github.ykrank.androidtools.widget.hostcheck.BaseHostUrl
import com.github.ykrank.androidtools.widget.hostcheck.BaseHostUrl.Companion.checkBaseHostUrl
import me.ykrank.s1next.data.api.Api
import me.ykrank.s1next.data.pref.NetworkPreferencesManager
import okhttp3.HttpUrl

/**
 * Base url and host ip
 */

class AppHostUrl(private val prefManager: NetworkPreferencesManager) : BaseHostUrl {
    @Volatile override var baseHttpUrl: HttpUrl? = null
    @Volatile override var hostIp: String? = null
    @Volatile var fallbackForumUrls: List<HttpUrl> = emptyList()
        private set

    init {
        refreshBaseHostUrl()
        refreshForceHostIp()
        refreshFallbackForumUrls()
    }

    fun refreshBaseHostUrl() {
        val url: String? = if (prefManager.isForceBaseUrlEnable) {
            prefManager.forceBaseUrl
        } else {
            Api.BASE_URL
        }

        if (url != baseHttpUrl?.toString()) {
            baseHttpUrl = checkBaseHostUrl(url)
        }
    }

    fun refreshFallbackForumUrls() {
        fallbackForumUrls = parseFallbackForumUrls(prefManager.fallbackForumUrls).orEmpty()
    }

    fun refreshForceHostIp() {
        hostIp = if (prefManager.isForceHostIpEnable) {
            prefManager.forceHostIp
        } else {
            null
        }
    }

    companion object {
        const val MAX_FALLBACK_URLS = 3

        fun parseFallbackForumUrls(value: String?): List<HttpUrl>? {
            val entries = value.orEmpty().split(',', '\n', '\r').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (entries.size > MAX_FALLBACK_URLS) return null
            return entries.map { entry ->
                val url = checkBaseHostUrl(entry) ?: return null
                if (!url.isHttps || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                    url.query != null || url.fragment != null ||
                    (url.host != Api.BASE_HOST && !url.host.endsWith(".${Api.BASE_HOST}"))) return null
                url
            }.distinct()
        }
    }
}
