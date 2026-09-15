package com.example.sonygps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updates from GitHub releases.
 *
 * The release workflow publishes every tag `vX.Y.Z` as a GitHub release with the
 * signed APK attached. This object asks the GitHub REST API for the latest
 * release, compares its tag with the installed version and, on request,
 * downloads the APK into the app cache and hands it to the system package
 * installer.
 *
 * No authentication: the API allows 60 anonymous requests per hour and IP,
 * and the app checks at most once a day unless the user asks explicitly.
 *
 * Installing works only if the downloaded APK is signed with the same key as
 * the installed app — i.e. for release builds from GitHub. A debug build gets
 * "App nicht installiert" from the installer; that's expected.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    const val OWNER = "Asdoos"
    const val REPO  = "SonyGpsApp"
    const val RELEASES_URL = "https://github.com/$OWNER/$REPO/releases"

    private const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"

    /** Automatic checks on app start happen at most this often. */
    const val CHECK_INTERVAL_MS = 24 * 60 * 60_000L

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS    = 30_000

    /** Cache sub-directory the APK is downloaded to; must match res/xml/file_paths.xml. */
    private const val CACHE_DIR = "updates"

    // ── Versions ─────────────────────────────────────────────────────────────

    /**
     * Numeric version like `0.2.0`, parsed from a tag (`v0.2.0`) or versionName.
     * Non-numeric suffixes (`-beta`) are ignored, missing components count as 0.
     */
    class Version private constructor(private val parts: List<Int>) : Comparable<Version> {

        override fun compareTo(other: Version): Int {
            val n = maxOf(parts.size, other.parts.size)
            for (i in 0 until n) {
                val a = parts.getOrElse(i) { 0 }
                val b = other.parts.getOrElse(i) { 0 }
                if (a != b) return a.compareTo(b)
            }
            return 0
        }

        override fun equals(other: Any?) = other is Version && compareTo(other) == 0
        override fun hashCode() = parts.dropLastWhile { it == 0 }.hashCode()
        override fun toString() = parts.joinToString(".")

        companion object {
            fun parse(text: String): Version? {
                val body = text.trim().removePrefix("v").removePrefix("V")
                val parts = body.split('.').map { part ->
                    part.takeWhile { it.isDigit() }.toIntOrNull() ?: return null
                }
                return if (parts.isEmpty()) null else Version(parts)
            }
        }
    }

    fun installedVersion(context: Context): Version? {
        val name = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: return null
        return Version.parse(name)
    }

    // ── GitHub API ───────────────────────────────────────────────────────────

    class Release(
        val tag: String,
        val version: Version,
        val apkUrl: String,
        val apkName: String,
        /** Release notes (Markdown), may be empty. */
        val notes: String,
        val htmlUrl: String
    )

    /**
     * Latest release with an APK asset, or null if the newest release has none
     * (e.g. the build failed) — callers then treat the app as up to date.
     * Network and parse errors surface as [IOException].
     */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val json = JSONObject(httpGet(LATEST_URL))
        val tag  = json.getString("tag_name")
        val version = Version.parse(tag) ?: throw IOException("Unbekanntes Tag-Format: $tag")

        val assets = json.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
        }
        if (apk == null) {
            Log.w(TAG, "Release $tag has no APK asset")
            return@withContext null
        }
        Release(
            tag     = tag,
            version = version,
            apkUrl  = apk.getString("browser_download_url"),
            apkName = apk.getString("name"),
            notes   = json.optString("body", "").trim(),
            htmlUrl = json.optString("html_url", RELEASES_URL)
        )
    }

    private fun httpGet(url: String): String {
        val conn = open(url)
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("GitHub antwortet mit HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout    = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "$REPO-Android")
        return conn
    }

    // ── Download ─────────────────────────────────────────────────────────────

    /**
     * Downloads the release APK into the app cache.
     * [onProgress] gets 0–100, or -1 while the size is unknown; called on the IO thread.
     */
    suspend fun download(
        context: Context,
        release: Release,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        // Only ever keep the current download around
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, release.apkName)
        val tmp    = File(dir, release.apkName + ".part")

        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("Download fehlgeschlagen: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            var done  = 0L
            var lastPct = -2
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("Download unvollständig ($done von $total Bytes)")
        } catch (e: Exception) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (!tmp.renameTo(target)) throw IOException("Konnte ${target.name} nicht anlegen")
        target
    }

    // ── Install ──────────────────────────────────────────────────────────────

    /** Android 8+ requires the user to allow installs from this app once. */
    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** Opens the system setting where the user allows this app to install APKs. */
    fun unknownSourcesSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Hands the downloaded APK to the system package installer. */
    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** True for debug builds, whose signature never matches the GitHub APK. */
    fun isDebugBuild(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Removes downloaded APKs, e.g. after a successful update (MY_PACKAGE_REPLACED). */
    fun clearDownloads(context: Context) {
        File(context.cacheDir, CACHE_DIR).listFiles()?.forEach { it.delete() }
    }
}
