package com.flash.headway.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Updates from the project's GitHub releases: every CI build is a release named for its run
 * number, which is also the app's versionCode. The app checks the latest, downloads its APK and
 * hands it to the system installer. Android always asks the rider to confirm an install from an
 * app; it can't be silent.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/shanuflash/headway/releases/latest"

    /** A release newer than this install: its versionCode, its name ("1.0.83") and its APK. */
    data class Release(val code: Long, val name: String, val apkUrl: String)

    /** The installed versionCode. */
    fun installedCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    fun installedName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** What a check found. */
    sealed interface Check {
        data class Newer(val release: Release) : Check
        data object UpToDate : Check
        /** Offline, or the releases couldn't be read (they're unreadable while the repository is private). */
        data object Failed : Check
    }

    suspend fun check(context: Context): Check = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            // "v1.0.83": the last part is the CI run number, the same as the versionCode.
            val name = json.getString("tag_name").removePrefix("v")
            val code = name.substringAfterLast('.').toLong()
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name") == "headway.apk" }
                ?.getString("browser_download_url") ?: return@runCatching Check.Failed
            if (code > installedCode(context)) Check.Newer(Release(code, name, apk)) else Check.UpToDate
        }.getOrDefault(Check.Failed)
    }

    /** Starts downloading [r]'s APK; returns the download's id. */
    fun download(context: Context, r: Release): Long {
        val dm = context.getSystemService(DownloadManager::class.java)
        val request = DownloadManager.Request(Uri.parse(r.apkUrl))
            .setTitle("Headway ${r.name}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "headway-${r.name}.apk")
        return dm.enqueue(request)
    }

    /** A download's progress: 0 to 100, or null when it failed. */
    fun progress(context: Context, id: Long): Int? {
        val dm = context.getSystemService(DownloadManager::class.java)
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return null
            return when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> 100
                DownloadManager.STATUS_FAILED -> null
                else -> {
                    val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    if (total > 0) (done * 99 / total).toInt() else 0
                }
            }
        }
    }

    /** Whether the rider has let Headway install updates (Android asks once). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the system page where the rider allows Headway to install updates. */
    fun allowInstalls(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hands a finished download to the system installer. */
    fun install(context: Context, id: Long) {
        val dm = context.getSystemService(DownloadManager::class.java)
        val uri = dm.getUriForDownloadedFile(id) ?: return
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
