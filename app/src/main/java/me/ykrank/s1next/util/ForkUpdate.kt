package me.ykrank.s1next.util

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.format.Formatter
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ykrank.s1next.BuildConfig
import me.ykrank.s1next.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Fork: Check the self-use release and download its APK through Android's DownloadManager. */
object ForkUpdate {
    private const val RELEASE_ROOT = "https://github.com/tiger-git2022/s1-next/releases/"
    private const val RELEASES_URL = RELEASE_ROOT + "latest"
    private const val UPDATE_JSON_URL = "$RELEASES_URL/download/update.json"
    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val PREFS = "fork_update"
    private const val DOWNLOAD_ID = "download_id"
    private const val VERSION_CODE = "version_code"

    val enabled: Boolean get() = BuildConfig.BUILD_TYPE == "custom"

    private data class Release(val tag: String, val notes: String, val versionCode: Long, val apkUrl: String?)
    private data class Download(val status: Int, val bytes: Long, val total: Long, val reason: Int)

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    private fun currentVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    suspend fun check(activity: FragmentActivity) {
        val manager = activity.getSystemService(DownloadManager::class.java)
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedId = prefs.getLong(DOWNLOAD_ID, -1)
        if (manager != null && savedId >= 0) {
            if (prefs.getLong(VERSION_CODE, 0) > currentVersionCode(activity)) {
                val saved = withContext(Dispatchers.IO) { downloadStatus(manager, savedId) }
                if (saved != null && saved.status != DownloadManager.STATUS_FAILED) {
                    observeDownload(activity, manager, savedId)
                    return
                }
            }
            discardDownload(activity, manager, savedId)
        }
        Toast.makeText(activity, R.string.fork_update_checking, Toast.LENGTH_SHORT).show()
        val release = withContext(Dispatchers.IO) { runCatching { fetch() } }.getOrElse {
            AlertDialog.Builder(activity)
                .setTitle(R.string.fork_update_failed)
                .setMessage(activity.getString(R.string.fork_update_failed_message, it.message ?: it.javaClass.simpleName))
                .setPositiveButton(R.string.fork_update_open_releases) { _, _ -> openReleasePage(activity) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        if (release.versionCode <= currentVersionCode(activity)) {
            Toast.makeText(activity, activity.getString(R.string.fork_update_latest, "v" + currentVersionName(activity)),
                Toast.LENGTH_SHORT).show()
            return
        }
        val canDownload = manager != null && release.apkUrl != null
        AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.fork_update_found, release.tag, "v" + currentVersionName(activity)))
            .setMessage(release.notes.ifBlank { activity.getString(R.string.fork_update_no_notes) })
            .setPositiveButton(if (canDownload) R.string.fork_update_download else R.string.fork_update_open_releases) { _, _ ->
                if (manager != null && release.apkUrl != null) {
                    val existingId = prefs.getLong(DOWNLOAD_ID, -1)
                    val id = if (existingId >= 0) existingId else try {
                        manager.enqueue(DownloadManager.Request(Uri.parse(release.apkUrl))
                            .setTitle(activity.getString(R.string.fork_update_downloading))
                            .setMimeType(APK_MIME)
                            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                            .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS,
                                "s1next-${release.versionCode}-${System.currentTimeMillis()}.apk"))
                            .also { prefs.edit().putLong(DOWNLOAD_ID, it).putLong(VERSION_CODE, release.versionCode).apply() }
                    } catch (e: RuntimeException) {
                        showDownloadError(activity, e.localizedMessage.orEmpty())
                        return@setPositiveButton
                    }
                    activity.lifecycleScope.launch { observeDownload(activity, manager, id) }
                } else openReleasePage(activity)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private suspend fun observeDownload(activity: FragmentActivity, manager: DownloadManager, id: Long) {
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
        }
        val status = TextView(activity)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, 0)
            addView(progress, LinearLayout.LayoutParams(-1, -2))
            addView(status)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fork_update_downloading)
            .setView(panel)
            .setNegativeButton(R.string.fork_update_background, null)
            .setNeutralButton(R.string.fork_update_cancel_download) { _, _ -> discardDownload(activity, manager, id) }
            .show()
        try {
            while (dialog.isShowing) {
                val data = withContext(Dispatchers.IO) { downloadStatus(manager, id) }
                if (!dialog.isShowing) return
                when (data?.status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        dialog.dismiss()
                        val uri = manager.getUriForDownloadedFile(id)
                        if (uri != null) showInstall(activity, uri) else showDownloadError(activity, "")
                        return
                    }
                    null, DownloadManager.STATUS_FAILED -> {
                        dialog.dismiss()
                        discardDownload(activity, manager, id)
                        showDownloadError(activity, data?.reason?.toString().orEmpty())
                        return
                    }
                    else -> {
                        val bytes = Formatter.formatShortFileSize(activity, data.bytes.coerceAtLeast(0))
                        val percent = if (data.total > 0) (100.0 * data.bytes / data.total).toInt().coerceIn(0, 100) else null
                        progress.isIndeterminate = percent == null
                        if (percent != null) progress.progress = percent
                        status.text = when {
                            data.status == DownloadManager.STATUS_PAUSED -> activity.getString(R.string.fork_update_download_paused, bytes)
                            percent == null -> bytes
                            else -> activity.getString(R.string.fork_update_progress, percent, bytes,
                                Formatter.formatShortFileSize(activity, data.total))
                        }
                    }
                }
                delay(400)
            }
        } finally {
            dialog.dismiss()
        }
    }

    private fun downloadStatus(manager: DownloadManager, id: Long): Download? =
        manager.query(DownloadManager.Query().setFilterById(id))?.use { row ->
            if (!row.moveToFirst()) return@use null
            Download(
                row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                row.getLong(row.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                row.getInt(row.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }

    private fun discardDownload(context: Context, manager: DownloadManager, id: Long) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(DOWNLOAD_ID, -1) == id) prefs.edit().clear().apply()
        runCatching { manager.remove(id) }
    }

    private fun showInstall(activity: FragmentActivity, uri: Uri) {
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.fork_update_downloaded)
            .setMessage(R.string.fork_update_install_help)
            .setPositiveButton(R.string.fork_update_install, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.fromParts("package", activity.packageName, null)))
                    return@setOnClickListener
                }
                activity.startActivity(Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                dialog.dismiss()
            } catch (e: RuntimeException) {
                showDownloadError(activity, e.localizedMessage.orEmpty())
            }
        }
    }

    private fun showDownloadError(activity: FragmentActivity, detail: String) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.fork_update_download_error)
            .setMessage(activity.getString(R.string.fork_update_download_error_help, detail))
            .setPositiveButton(R.string.fork_update_open_releases) { _, _ -> openReleasePage(activity) }
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
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0)).asSequence()
                .mapNotNull { assets?.optJSONObject(it)?.optString("browser_download_url") }
                .firstOrNull { it.startsWith(RELEASE_ROOT + "download/") && it.endsWith(".apk") }
            return Release(json.optString("tag_name"), json.optString("body").trim(), json.getLong("version_code"), apk)
        } finally {
            conn.disconnect()
        }
    }

    private fun openReleasePage(context: Context) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) }
    }
}
