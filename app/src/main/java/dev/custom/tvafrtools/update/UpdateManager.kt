package dev.custom.tvafrtools.update

import dev.custom.tvafrtools.R

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import dev.custom.tvafrtools.BuildConfig
import dev.custom.tvafrtools.server.ServerClient
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
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
 * 4. Triple-Mode Installation:
 *    - Silent Instant Mode: Installs in 1-second via local ADB Privileged daemon (`pm install -r -d`).
 *    - Native PackageInstaller Mode: Uses a `PackageInstaller` session scoped to our own
 *      package (`setAppPackageName`), which shows the regular app-update confirmation and
 *      avoids the Play Protect "untrusted publisher" warning.
 *    - Settings fallback chain for REQUEST_INSTALL_PACKAGES grants across TV skins.
 */
object UpdateManager {
    private const val TAG = "UpdateManager"
    private const val GITHUB_REPO = "TSCNEO/TvAfrTools"
    private const val API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val APK_NAME = "tvafrtools-update.apk"
    const val ACTION_INSTALL_STATUS = "dev.custom.tvafrtools.action.INSTALL_STATUS"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Synchronized
    fun checkOnAppOpen(context: Context, onAvailable: (UpdateInfo) -> Unit) {
        if (BuildConfig.DEBUG) return
        val app = context.applicationContext
        val settings = app.getSharedPreferences("update_checks", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val savedVersion = settings.getString("available_version", "").orEmpty()
        if (savedVersion.isNotBlank() && !isNewerVersion(savedVersion.removePrefix("v").removePrefix("V"), BuildConfig.VERSION_NAME)) {
            settings.edit()
                .remove("available_version")
                .remove("available_tag")
                .remove("available_notes")
                .remove("available_url")
                .remove("available_size")
                .apply()
        }
        val last = settings.getLong("last_attempt", 0L)
        val cachedTag = settings.getString("available_tag", "").orEmpty()
        val cachedVer = settings.getString("available_version", "").orEmpty()
        val cachedUrl = settings.getString("available_url", "").orEmpty()
        val cachedNotes = settings.getString("available_notes", "").orEmpty()
        val cachedSize = settings.getLong("available_size", 0L)

        if (now >= last && now - last < 6 * 60 * 60 * 1000L && cachedUrl.isNotBlank()) {
            if (cachedVer.isNotBlank() && isNewerVersion(cachedVer.removePrefix("v").removePrefix("V"), BuildConfig.VERSION_NAME)) {
                val cachedInfo = UpdateInfo(
                    tagName = cachedTag.ifBlank { "v$cachedVer" },
                    versionName = cachedVer,
                    releaseNotes = cachedNotes,
                    downloadUrl = cachedUrl,
                    fileSize = cachedSize
                )
                mainHandler.post { onAvailable(cachedInfo) }
            }
            return
        }
        settings.edit().putLong("last_attempt", now).apply()
        checkForUpdate(app) { info, _ ->
            if (info != null) {
                settings.edit()
                    .putString("available_tag", info.tagName)
                    .putString("available_version", info.versionName)
                    .putString("available_notes", info.releaseNotes)
                    .putString("available_url", info.downloadUrl)
                    .putLong("available_size", info.fileSize)
                    .apply()
                onAvailable(info)
            }
        }
    }

    fun checkForUpdate(
        context: Context,
        onResult: (UpdateInfo?, String?) -> Unit
    ) {
        if (BuildConfig.DEBUG) {
            mainHandler.post { onResult(null, "Actualizaciones desactivadas en la versión de prueba") }
            return
        }
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

                // 2. Native PackageInstaller session scoped to our own package.
                // Runs on this worker thread: copying a multi-MB APK on the main thread
                // would risk an ANR. Callbacks are marshalled back to the main thread.
                installViaPackageInstaller(context, cacheFile, onComplete, onStatus)
            } catch (e: Exception) {
                Log.e(TAG, "Download failed", e)
                mainHandler.post {
                    onStatus("Error en la descarga")
                    onComplete(false, "Fallo de descarga: ${e.message}")
                }
            }
        }.start()
    }

    /** True when the app may start package install sessions (Android 8.0+). */
    fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return try {
            context.packageManager.canRequestPackageInstalls()
        } catch (e: Exception) {
            Log.w(TAG, "canRequestPackageInstalls failed, assuming granted", e)
            true
        }
    }

    /**
     * Opens the "Install unknown apps" permission screen for this app.
     *
     * TV vendors ship very different Settings builds, so we try three tiers:
     *   1. ACTION_MANAGE_UNKNOWN_APP_SOURCES with our package URI (AOSP / Google TV).
     *   2. ACTION_MANAGE_UNKNOWN_APP_SOURCES without data (Fire TV, some Chinese TVs).
     *   3. ACTION_SECURITY_SETTINGS as a last resort (vendor skins).
     * Falls back to the app details screen as a final safety net.
     */
    fun openInstallPermissionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val candidates = mutableListOf<Intent>()

        // Tier 1: per-app unknown-sources page.
        candidates += Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        // Tier 2: bare unknown-sources list.
        candidates += Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
        // Tier 3: generic security settings.
        candidates += Intent(Settings.ACTION_SECURITY_SETTINGS)
        // Final safety net: app details, where the toggle is also reachable.
        candidates += Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                Log.i(TAG, "Opened install permission settings via ${intent.action}")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "Settings activity unavailable: ${intent.action}")
            } catch (e: Exception) {
                Log.d(TAG, "Settings activity failed: ${intent.action}", e)
            }
        }
        Log.w(TAG, "Could not open any install permission settings screen")
        return false
    }

    /**
     * Installs [apkFile] through a [PackageInstaller] session.
     *
     * Using [PackageInstaller.SessionParams.setAppPackageName] with our own package makes the
     * system treat this as a self-update: the confirmation prompt is the normal
     * "Update app?" dialog and Play Protect no longer warns about an untrusted publisher.
     */
    @Suppress("DEPRECATION")
    fun installViaPackageInstaller(
        context: Context,
        apkFile: File,
        onComplete: (Boolean, String) -> Unit,
        onStatus: (String) -> Unit = {}
    ) {
        // Safe to call from any thread: every callback is marshalled to the main thread.
        val complete: (Boolean, String) -> Unit = { ok, msg ->
            mainHandler.post { onComplete(ok, msg) }
        }
        try {
            if (!apkFile.exists() || apkFile.length() <= 0L) {
                complete(false, "El archivo de actualización no existe o está vacío.")
                return
            }
            if (!canRequestPackageInstalls(context)) {
                complete(false, "Falta el permiso para instalar aplicaciones desconocidas.")
                return
            }

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE)
                }
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                try {
                    FileInputStream(apkFile).use { input ->
                        session.openWrite(APK_NAME, 0L, apkFile.length()).use { output ->
                            input.copyTo(output, 64 * 1024)
                            session.fsync(output)
                        }
                    }
                    val statusIntent = Intent(context, UpdateInstallReceiver::class.java).apply {
                        setAction(ACTION_INSTALL_STATUS)
                        `package` = context.packageName
                    }
                    // The system fills in the status extras, so the PendingIntent must be mutable
                    // (required explicitly from API 31).
                    var flags = PendingIntent.FLAG_UPDATE_CURRENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        flags = flags or PendingIntent.FLAG_MUTABLE
                    }
                    val pendingIntent = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        statusIntent,
                        flags
                    )
                    UpdateInstallReceiver.listener = { success, message, isFinal ->
                        mainHandler.post {
                            if (isFinal) onComplete(success, message) else onStatus(message)
                        }
                    }
                    mainHandler.post { onStatus("Instalando actualización...") }
                    session.commit(pendingIntent.intentSender)
                } catch (e: Exception) {
                    Log.e(TAG, "PackageInstaller session failed", e)
                    try {
                        session.abandon()
                    } catch (_: Exception) {}
                    UpdateInstallReceiver.listener = null
                    complete(false, "No se pudo preparar la instalación: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "PackageInstaller unavailable", e)
            UpdateInstallReceiver.listener = null
            complete(false, "No se pudo abrir el instalador del sistema: ${e.message}")
        }
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
