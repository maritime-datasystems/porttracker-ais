package com.porttracker.ais

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * OTA Update Manager — checks a remote manifest for new APK versions
 * and downloads/installs them on user request.
 *
 * The manifest is a simple JSON file hosted on any HTTP(S) server:
 *
 *   {
 *     "version_code": 23,
 *     "version_name": "3.0-0620",
 *     "apk_url": "https://your-server.com/porttracker-ais-v23.apk",
 *     "apk_size": 12345678,
 *     "release_notes": "Fixed MQTT topics, added OTA updates",
 *     "min_version_code": 20
 *   }
 *
 * The manifest URL is configurable via SharedPreferences key "update_manifest_url".
 * Default: GitHub raw file in the porttracker-ais repo. GitHub keeps
 * serving the old porttracker-aiscatcher URLs after the rename, so
 * stations installed before it still find their manifest.
 *
 * Flow:
 *  1. checkForUpdate() → fetches manifest JSON, compares versionCode
 *  2. downloadAndInstall() → downloads APK from apk_url, triggers Android install
 *
 * The user must tap "Install" on the device to confirm (Android security requirement).
 */
class UpdateManager(private val context: Context) {

    companion object {
        private const val TAG = "UpdateManager"

        // Default manifest URL — raw JSON file in the GitHub repo
        // Can be overridden via SharedPreferences "update_manifest_url"
        const val DEFAULT_MANIFEST_URL =
            "https://raw.githubusercontent.com/maritime-datasystems/porttracker-ais/main/update-manifest.json"

        fun getCurrentVersionCode(context: Context): Int {
            return try {
                val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    pInfo.versionCode
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get version code", e)
                0
            }
        }

        fun getCurrentVersionName(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }
    }

    // State
    @Volatile var isChecking = false; private set
    @Volatile var isDownloading = false; private set
    @Volatile var downloadProgress = 0; private set
    @Volatile var lastCheckResult: UpdateInfo? = null; private set
    @Volatile var lastError: String? = null; private set

    data class UpdateInfo(
        val available: Boolean,
        val currentVersionCode: Int,
        val currentVersionName: String,
        val latestVersionCode: Int,
        val latestVersionName: String,
        val releaseNotes: String,
        val apkDownloadUrl: String?,
        val apkSizeBytes: Long,
        val manifestUrl: String,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("update_available", available)
            put("current_version_code", currentVersionCode)
            put("current_version_name", currentVersionName)
            put("latest_version_code", latestVersionCode)
            put("latest_version_name", latestVersionName)
            put("release_notes", releaseNotes)
            put("apk_download_url", apkDownloadUrl ?: "")
            put("apk_size_bytes", apkSizeBytes)
            put("manifest_url", manifestUrl)
        }
    }

    private fun getManifestUrl(): String {
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        return prefs.getString("update_manifest_url", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_MANIFEST_URL
    }

    /**
     * Check the remote manifest for a newer version.
     * Runs on calling thread (call from background!).
     */
    fun checkForUpdate(): UpdateInfo {
        check(BuildConfig.ALLOW_SIDELOAD_UPDATES) { "Updates are managed by Google Play" }
        isChecking = true
        lastError = null
        val manifestUrl = getManifestUrl()
        var conn: HttpURLConnection? = null
        try {
            val currentCode = getCurrentVersionCode(context)
            val currentName = getCurrentVersionName(context)

            Log.i(TAG, "Checking for updates from: $manifestUrl")

            conn = URL(manifestUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PortTracker-AIS/$currentName")
            conn.setRequestProperty("Cache-Control", "no-cache")
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("Manifest fetch failed: HTTP $responseCode from $manifestUrl")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val manifest = JSONObject(body)

            val latestCode = manifest.optInt("version_code", 0)
            val latestName = manifest.optString("version_name", "unknown")
            val apkUrl = manifest.optString("apk_url", "")
            val apkSize = manifest.optLong("apk_size", 0)
            val releaseNotes = manifest.optString("release_notes", "")
            val minVersionCode = manifest.optInt("min_version_code", 0)

            // Check if current version is too old for incremental update
            if (minVersionCode > 0 && currentCode < minVersionCode) {
                Log.w(TAG, "Current version $currentCode is below min_version_code $minVersionCode — full reinstall may be needed")
            }

            val info = UpdateInfo(
                available = latestCode > currentCode && apkUrl.isNotBlank(),
                currentVersionCode = currentCode,
                currentVersionName = currentName,
                latestVersionCode = latestCode,
                latestVersionName = latestName,
                releaseNotes = releaseNotes,
                apkDownloadUrl = apkUrl.ifBlank { null },
                apkSizeBytes = apkSize,
                manifestUrl = manifestUrl,
            )
            lastCheckResult = info
            Log.i(TAG, "Update check: current=$currentCode, latest=$latestCode, available=${info.available}")
            return info

        } catch (e: Exception) {
            Log.e(TAG, "Update check failed", e)
            lastError = e.message ?: "Unknown error"
            val currentCode = getCurrentVersionCode(context)
            val currentName = getCurrentVersionName(context)
            val info = UpdateInfo(
                available = false,
                currentVersionCode = currentCode,
                currentVersionName = currentName,
                latestVersionCode = currentCode,
                latestVersionName = currentName,
                releaseNotes = "",
                apkDownloadUrl = null,
                apkSizeBytes = 0,
                manifestUrl = manifestUrl,
            )
            lastCheckResult = info
            return info
        } finally {
            conn?.disconnect()
            isChecking = false
        }
    }

    /**
     * Download the APK and trigger Android's package installer.
     * Runs on calling thread (call from background!).
     * Returns true if install intent was launched successfully.
     */
    fun downloadAndInstall(): Boolean {
        check(BuildConfig.ALLOW_SIDELOAD_UPDATES) { "Updates are managed by Google Play" }
        val info = lastCheckResult
            ?: throw IllegalStateException("Call checkForUpdate() first")
        val apkUrl = info.apkDownloadUrl
            ?: throw IllegalStateException("No APK download URL in manifest")

        isDownloading = true
        downloadProgress = 0
        lastError = null
        var conn: HttpURLConnection? = null

        try {
            Log.i(TAG, "Downloading APK from: $apkUrl")

            conn = URL(apkUrl).openConnection() as HttpURLConnection
            conn.setRequestProperty("User-Agent", "PortTracker-AIS/${info.currentVersionName}")
            conn.connectTimeout = 30_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true

            val responseCode = conn.responseCode
            if (responseCode != 200) {
                throw Exception("Download failed: HTTP $responseCode")
            }

            val totalBytes = conn.contentLength.toLong()
            val apkFile = File(context.getExternalFilesDir(null), "update.apk")

            conn.inputStream.use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0
                    var count: Int
                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        bytesRead += count
                        downloadProgress = if (totalBytes > 0) {
                            ((bytesRead * 100) / totalBytes).toInt()
                        } else {
                            -1 // indeterminate
                        }
                    }
                }
            }

            downloadProgress = 100
            Log.i(TAG, "APK downloaded: ${apkFile.length()} bytes → ${apkFile.absolutePath}")

            // Trigger Android install intent
            installApk(apkFile)
            return true

        } catch (e: Exception) {
            Log.e(TAG, "Download/install failed", e)
            lastError = e.message ?: "Download failed"
            return false
        } finally {
            conn?.disconnect()
            isDownloading = false
        }
    }

    private fun installApk(apkFile: File) {
        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        Log.i(TAG, "Launching APK install intent for: $uri")
        context.startActivity(intent)
    }

    fun getStatusJson(): JSONObject = JSONObject().apply {
        put("checking", isChecking)
        put("downloading", isDownloading)
        put("download_progress", downloadProgress)
        put("error", lastError)
        put("manifest_url", getManifestUrl())
        if (lastCheckResult != null) {
            put("update", lastCheckResult!!.toJson())
        }
    }
}
