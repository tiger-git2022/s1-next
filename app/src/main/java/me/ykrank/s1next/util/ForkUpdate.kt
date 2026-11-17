package me.ykrank.s1next.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ykrank.s1next.BuildConfig
import me.ykrank.s1next.R
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fork（tiger-git2022）：自用版的检查更新。
 *
 * 发布工作流（.github/workflows/tiger-release.yml）每次发版都会在 Release 里上传 update.json
 * （tag_name、body、html_url、version_code、assets）。这里读取最新 Release 的 update.json，
 * 和本机安装的 versionCode 比较，有新版本就弹窗显示更新内容，点「下载」用浏览器下载 APK，下载完点开覆盖安装。
 * 上游的检查更新打开的是官方版的下载页，自用版不用。
 */
object ForkUpdate {

    private const val RELEASES_URL = "https://github.com/tiger-git2022/s1-next/releases/latest"
    private const val UPDATE_JSON_URL = "$RELEASES_URL/download/update.json"

    /** 自用版（custom 构建类型）才用这里的检查更新 */
    val enabled: Boolean get() = BuildConfig.BUILD_TYPE == "custom"

    private class Release(val tag: String, val notes: String, val versionCode: Long, val apkUrl: String?)

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** 在协程里调用（比如 lifecycleScope.launch），网络请求放在 IO 线程 */
    suspend fun check(context: Context) {
        Toast.makeText(context, R.string.fork_update_checking, Toast.LENGTH_SHORT).show()
        val release = withContext(Dispatchers.IO) { runCatching { fetch() } }.getOrElse {
            AlertDialog.Builder(context)
                .setTitle(R.string.fork_update_failed)
                .setMessage(context.getString(R.string.fork_update_failed_message, it.message ?: it.javaClass.simpleName))
                .setPositiveButton(R.string.fork_update_open_releases) { _, _ -> open(context, RELEASES_URL) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (release.versionCode <= currentVersionCode(context)) {
            Toast.makeText(context, context.getString(R.string.fork_update_latest, "v" + currentVersionName(context)),
                Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.fork_update_found, release.tag, "v" + currentVersionName(context)))
            .setMessage(release.notes.ifBlank { context.getString(R.string.fork_update_no_notes) })
            .setPositiveButton(R.string.fork_update_download) { _, _ -> open(context, release.apkUrl ?: RELEASES_URL) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun fetch(): Release {
        val conn = URL(UPDATE_JSON_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val url = assets.getJSONObject(i).optString("browser_download_url")
                    if (url.endsWith(".apk")) {
                        apk = url
                        break
                    }
                }
            }
            return Release(
                tag = json.optString("tag_name"),
                notes = json.optString("body").trim(),
                versionCode = json.getLong("version_code"),
                apkUrl = apk,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun open(context: Context, url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }
}
