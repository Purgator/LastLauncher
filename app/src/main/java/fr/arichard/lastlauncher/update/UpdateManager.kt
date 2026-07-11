package fr.arichard.lastlauncher.update

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import fr.arichard.lastlauncher.settings.Prefs
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-updater backed by GitHub Releases.
 *
 * Once a day it fetches the latest-release metadata; when a newer version exists it
 * downloads the APK in the background (unmetered networks only) and the home screen
 * shows a small "update ready" pill. Android verifies the APK is signed with the
 * same key before installing, so a tampered file can never replace the app.
 */
object UpdateManager {

    private const val TAG = "UpdateManager"
    private const val API_URL =
        "https://api.github.com/repos/Purgator/LastLauncher/releases/latest"
    private const val ASSET_NAME = "LastLauncher.apk"
    private const val FILE_PREFIX = "LastLauncher-v"
    private const val MAX_APK_BYTES = 30L * 1024 * 1024
    private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    enum class Status { UP_TO_DATE, UPDATE_READY, UPDATE_DEFERRED, ERROR }
    data class Result(val status: Status, val version: String? = null, val detail: String? = null)

    fun currentVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (e: Exception) {
        "0"
    }

    /**
     * Daily background check called when the home screen resumes. Returns the version
     * of a ready-to-install update, or null. Cheap when throttled: an already
     * downloaded update is detected locally without any network. Call from a
     * background thread.
     */
    fun maybeDailyCheck(context: Context): String? {
        val appContext = context.applicationContext
        val prefs = Prefs(appContext)
        if (!prefs.autoUpdate) return null

        pendingDownloadedVersion(appContext)?.let { return it }

        val due = System.currentTimeMillis() - prefs.lastUpdateCheck >= CHECK_INTERVAL_MS
        if (!due && !prefs.updateDeferred) return null

        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm != null && !cm.isActiveNetworkMetered
        val result = check(appContext, allowDownload = unmetered)
        // Deferred (found but not downloaded on metered data) stays retryable so the
        // next unmetered network picks it up without waiting out the day.
        prefs.updateDeferred = result.status == Status.UPDATE_DEFERRED
        prefs.lastUpdateCheck = System.currentTimeMillis()
        return if (result.status == Status.UPDATE_READY) result.version else null
    }

    /** Highest already-downloaded update newer than the installed app, or null. Local only. */
    private fun pendingDownloadedVersion(context: Context): String? =
        File(context.cacheDir, "updates").listFiles()
            ?.filter { it.isFile && it.length() > 0 && it.name.endsWith(".apk") }
            ?.map { it.name.removePrefix(FILE_PREFIX).removeSuffix(".apk") }
            ?.filter { isNewer(it, currentVersion(context)) }
            ?.maxWithOrNull(::compareVersions)

    /**
     * Checks GitHub for a newer release and, if [allowDownload], fetches its APK into
     * the cache. Does not touch the throttle timestamp — the caller owns that.
     * Call from a background thread.
     */
    @Synchronized
    fun check(context: Context, allowDownload: Boolean): Result {
        val appContext = context.applicationContext
        return try {
            val json = JSONObject(httpGet(API_URL))

            val remote = json.getString("tag_name").removePrefix("v").trim()
            val current = currentVersion(appContext)
            cleanupOldApks(appContext, current)
            if (!isNewer(remote, current)) return Result(Status.UP_TO_DATE, current)

            var assetUrl: String? = null
            var assetSize = -1L
            val assets = json.optJSONArray("assets")
            for (i in 0 until (assets?.length() ?: 0)) {
                val asset = assets!!.getJSONObject(i)
                if (asset.optString("name") == ASSET_NAME) {
                    assetUrl = asset.optString("browser_download_url")
                    assetSize = asset.optLong("size", -1)
                    break
                }
            }
            if (assetUrl.isNullOrEmpty() || !assetUrl.startsWith("https://")) {
                return Result(Status.ERROR, remote, "release has no $ASSET_NAME asset")
            }
            if (assetSize !in 1..MAX_APK_BYTES) {
                return Result(Status.ERROR, remote, "unexpected asset size $assetSize")
            }

            val apk = apkFile(appContext, remote)
            if (apk.isFile && apk.length() == assetSize) return Result(Status.UPDATE_READY, remote)
            if (!allowDownload) return Result(Status.UPDATE_DEFERRED, remote)

            download(assetUrl, apk, assetSize)
            Log.i(TAG, "Downloaded update $remote (${apk.length()} bytes)")
            Result(Status.UPDATE_READY, remote)
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed: ${e.message}")
            Result(Status.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Starts installing the downloaded update APK for [version]. */
    fun install(context: Context, version: String) {
        ApkInstaller.install(context, apkFile(context, version))
    }

    /** True when [remote] is a strictly newer dotted version than [local]. */
    internal fun isNewer(remote: String, local: String): Boolean =
        compareVersions(remote, local) > 0

    /** Numeric dotted-version comparison: 1.10 > 1.9. Non-numeric parts count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        val x = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val cmp = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (cmp != 0) return cmp
        }
        return 0
    }

    private fun apkFile(context: Context, version: String): File =
        File(context.cacheDir, "updates/$FILE_PREFIX$version.apk").apply { parentFile?.mkdirs() }

    /** Removes cached update APKs that are no longer newer than the installed app. */
    private fun cleanupOldApks(context: Context, current: String) {
        File(context.cacheDir, "updates").listFiles()?.forEach { file ->
            val version = file.name.removePrefix(FILE_PREFIX).removeSuffix(".apk")
            if (!isNewer(version, current)) file.delete()
        }
    }

    private fun httpGet(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "LastLauncher-updater")
            if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File, expectedSize: Long) {
        val tmp = File(target.path + ".tmp")
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "LastLauncher-updater")
                if (connection.responseCode != 200) throw Exception("HTTP ${connection.responseCode}")
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                connection.disconnect()
            }
            if (tmp.length() != expectedSize) {
                throw Exception("size mismatch: got ${tmp.length()}, expected $expectedSize")
            }
            target.delete()
            if (!tmp.renameTo(target)) throw Exception("could not move downloaded file")
        } finally {
            tmp.delete()
        }
    }
}
