package dev.custom.tvafrtools.update

import dev.custom.tvafrtools.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import dev.custom.tvafrtools.BuildConfig
import dev.custom.tvafrtools.server.ServerClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val fileSize: Long
)

/**
 * Universal GitHub Releases In-App Updater for Android TV.
 *
 * Supports:
 * 1. Checking `https://api.github.com/repos/TSCNEO/TvAfrTools/releases/latest`
 * 2. SemVer version comparison against `BuildConfig.VERSION_NAME`
 * 3. Streaming download with live progress tracking
 * 4. Dual-Mode Installation:
 *    - Silent Instant Mode: Installs in 1-second via local ADB Privileged daemon (`pm install -r -d`).
 *    - Native Fallback Mode: Prompts standard Android TV package installer dialog via `FileProvider`.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO = "TSCNEO/TvAfrTools"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun checkOnAppOpen(context: Context, onAvailable: (String) -> Unit) {
        val app = context.applicationContext
        val settings = app.getSharedPreferences("update_checks", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val savedVersion = settings.getString("available_version", "").orEmpty()
        if (savedVersion.isNotBlank() && !isNewerVersion(savedVersion.removePrefix("v").removePrefix("V"), BuildConfig.VERSION_NAME)) {
            settings.edit().remove("available_version").apply()
        }
        val last = settings.getLong("last_attempt", 0L)
        if (now >= last && now - last < 6 * 60 * 60 * 1000L) {
            val cached = settings.getString("available_version", "").orEmpty()
            if (cached.isNotBlank() && isNewerVersion(cached.removePrefix("v"), BuildConfig.VERSION_NAME)) onAvailable(cached)
            return
        }
        settings.edit().putLong("last_attempt", now).apply()
        checkForUpdate(app) { info, _ ->
            if (info != null) {
                settings.edit().putString("available_version", info.tagName).apply()
                onAvailable(info.tagName)
            }
        }
    }

    fun checkForUpdate(
        context: Context,
        onResult: (UpdateInfo?, String?) -> Unit
    ) {
        Thread {
            try {
                val url = URL(API_URL)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    setRequestProperty("User-Agent", "TvAfrTools-AndroidTV")
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val msg = if (responseCode == 404) "No hay releases publicadas todavía en GitHub." else "Error GitHub HTTP $responseCode"
                    mainHandler.post { onResult(null, msg) }
                    return@Thread
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(jsonStr)
                val tagName = root.optString("tag_name", "").trim()
                val body = root.optString("body", "Actualización con mejoras de rendimiento y correcciones.")
                val remoteVer = tagName.removePrefix("v").removePrefix("V").trim()
                val currentVer = BuildConfig.VERSION_NAME.trim()

                if (!isNewerVersion(remoteVer, currentVer)) {
                    mainHandler.post { onResult(null, context.getString(R.string.update_up_to_date, currentVer)) }
                    return@Thread
                }

                var downloadUrl = ""
                var fileSize = 0L
                val assets = root.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            fileSize = asset.optLong("size", 0L)
                            break
                        }
                    }
                }

                if (downloadUrl.isBlank()) {
                    mainHandler.post { onResult(null, "Nueva versión $tagName encontrada, pero el APK aún se está subiendo.") }
                    return@Thread
                }

                val info = UpdateInfo(
                    tagName = tagName,
                    versionName = remoteVer,
                    releaseNotes = body,
                    downloadUrl = downloadUrl,
                    fileSize = fileSize
                )
                mainHandler.post { onResult(info, null) }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking for updates", e)
                mainHandler.post { onResult(null, context.getString(R.string.update_error, e.message ?: "Unknown")) }
            }
        }.start()
    }

    fun downloadAndInstall(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        onStatus: (String) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        Thread {
            try {
                mainHandler.post { onStatus("Descargando ${info.tagName}...") }
                val url = URL(info.downloadUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 25000
                    setRequestProperty("User-Agent", "TvAfrTools-AndroidTV")
                    instanceFollowRedirects = true
                }

                val totalLength = conn.contentLength.let { if (it > 0) it.toLong() else info.fileSize }
                val localTmpFile = File("/data/local/tmp/tvafr_update.apk")
                val cacheFile = File(context.cacheDir, "tvafr_update.apk")

                conn.inputStream.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        var downloaded = 0L
                        var lastProgress = 0

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            if (totalLength > 0) {
                                val progress = ((downloaded * 100) / totalLength).toInt()
                                if (progress != lastProgress) {
                                    lastProgress = progress
                                    mainHandler.post { onProgress(progress) }
                                }
                            }
                        }
                        output.flush()
                    }
                }

                // Copy to /data/local/tmp for privileged installation if possible
                try {
                    cacheFile.copyTo(localTmpFile, overwrite = true)
                    localTmpFile.setReadable(true, false)
                } catch (_: Exception) {}

                mainHandler.post { onStatus("Instalando actualización...") }

                // 1. Try silent installation via PrivilegedServer
                var silentSuccess = false
                if (ServerClient.isServerRunning()) {
                    silentSuccess = ServerClient.installApk(localTmpFile.absolutePath)
                    if (!silentSuccess && cacheFile.exists()) {
                        silentSuccess = ServerClient.installApk(cacheFile.absolutePath)
                    }
                }

                if (silentSuccess) {
                    mainHandler.post {
                        onStatus("✓ ¡Actualización instalada con éxito!")
                        onComplete(true, "Actualización completada de forma silenciosa.")
                    }
                    // Give OS a moment to finish package manager cache, then restart app
                    mainHandler.postDelayed({
                        val pm = context.packageManager
                        val launchIntent = pm.getLeanbackLaunchIntentForPackage(context.packageName)
                            ?: pm.getLaunchIntentForPackage(context.packageName)
                        if (launchIntent != null) {
                            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            context.startActivity(launchIntent)
                        }
                    }, 1200L)
                    return@Thread
                }

                // 2. Native Fallback Mode via FileProvider & Intent.ACTION_VIEW
                mainHandler.post {
                    try {
                        val apkUri: Uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            cacheFile
                        )
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(installIntent)
                        onComplete(true, "Instalador del sistema iniciado.")
                    } catch (e: Exception) {
                        onComplete(false, "No se pudo abrir el instalador: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post {
                    onStatus("Error en la descarga")
                    onComplete(false, "Fallo de descarga: ${e.message}")
                }
            }
        }.start()
    }

    fun isNewerVersion(remoteVer: String, localVer: String): Boolean {
        if (remoteVer == localVer) return false
        val rParts = remoteVer.split(".").mapNotNull { it.toIntOrNull() }
        val lParts = localVer.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(rParts.size, lParts.size)
        for (i in 0 until maxLen) {
            val r = rParts.getOrElse(i) { 0 }
            val l = lParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }
}
