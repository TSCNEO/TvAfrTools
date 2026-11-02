package dev.custom.tvafrtools.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * Robust IPC Client connecting to PrivilegedServer daemon at 127.0.0.1:15099.
 */
object ServerClient {
    private const val HOST = "127.0.0.1"
    private const val PORT = PrivilegedServer.PORT

    data class SysInfo(
        val fps: Float,
        val fpsSource: String,
        val socTempCelsius: Float,
        val hdmiRealHardware: String = "",
        val fpsVerified: Boolean = false,
        val fpsSamples: Int = 0,
        val fpsSpanUs: Long = 0,
        val detectionRequest: Long = 0,
        val detectionCompleted: Long = 0
    )

    fun sendCommand(cmd: String, timeoutMs: Int = 1800): String {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(HOST, PORT), timeoutMs)
            socket.soTimeout = timeoutMs
            val out = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            out.println(cmd)
            reader.readLine()?.trim().orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    fun isServerRunning(): Boolean {
        val resp = sendCommand("PING", 800)
        return resp.startsWith("OK")
    }

    fun isServerVersionCurrent(): Boolean {
        val response = sendCommand("PING", 800)
        return response.split(' ').any { it == "VERSION=${dev.custom.tvafrtools.BuildConfig.VERSION_CODE}" }
    }

    fun getLiveVideoFps(): Float {
        val resp = sendCommand("GET_FPS")
        if (resp.startsWith("OK ")) {
            val parts = resp.substring(3).trim().split(" ")
            return parts.getOrNull(0)?.toFloatOrNull() ?: 0f
        }
        return 0f
    }

    fun probeActiveVideoFpsWithSource(): Pair<Float, String> {
        val resp = sendCommand("PROBE_FPS", 3500)
        if (resp.startsWith("OK ")) {
            val parts = resp.substring(3).trim().split(" ")
            val fps = parts.getOrNull(0)?.toFloatOrNull() ?: 0f
            val src = parts.getOrNull(1) ?: "PROBE"
            return fps to src
        }
        return 0f to "ERR"
    }

    fun forceFreshDetection(): SysInfo? {
        val response = sendCommand("REDETECT_FPS")
        val id = Regex("""REQUEST=(\d+)""").find(response)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val deadline = System.nanoTime() + 14_000_000_000L
        while (System.nanoTime() < deadline) {
            val info = getSysInfo()
            if (info.detectionRequest != id) return null
            if (info.detectionCompleted == id) return info.takeIf { it.fpsVerified }
            Thread.sleep(250L)
        }
        return null
    }

    fun getSysInfo(): SysInfo = parseSysInfo(sendCommand("GET_SYSINFO", 1200))

    internal fun parseSysInfo(resp: String): SysInfo {
        if (!resp.startsWith("OK ")) return SysInfo(0f, "OFFLINE", -1f, "")
        val values = resp.substring(3).split(' ').mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else part.substring(0, separator) to part.substring(separator + 1)
        }.toMap()
        val fps = values["FPS"]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f
        val source = values["SRC"] ?: "IDLE"
        val samples = values["SAMPLES"]?.toIntOrNull() ?: 0
        val spanUs = values["SPAN_US"]?.toLongOrNull() ?: 0L
        val verified = values["VERIFIED"] == "1" && source == "VIDEO_INPUT_PTS" &&
            fps > 1f && samples >= 40 && spanUs >= 3_000_000L &&
            values["REQUEST"] == values["COMPLETED"]
        return SysInfo(fps, source, values["TEMP"]?.toFloatOrNull() ?: -1f,
            values["HDMI"].orEmpty(), verified, samples, spanUs,
            values["REQUEST"]?.toLongOrNull() ?: 0, values["COMPLETED"]?.toLongOrNull() ?: 0)
    }

    fun getVideoBufferSize(): String {
        val response = sendCommand("GET_VIDEO_SIZE", 3500)
        return if (response.startsWith("OK ")) response.substring(3).trim() else "UNKNOWN"
    }

    fun getRealHardwareHdmi(): String {
        val resp = sendCommand("GET_HDMI_REAL", 1000)
        if (resp.startsWith("OK ")) {
            return resp.substring(3).trim()
        }
        return ""
    }

    fun setRealScreenOff(targetOff: Boolean): Boolean {
        val cmd = if (targetOff) "SCREEN_OFF" else "SCREEN_ON"
        val resp = sendCommand(cmd, 2500)
        return resp.startsWith("OK ")
    }

    fun forceStopPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val resp = sendCommand("FORCE_STOP $pkg")
        return resp.startsWith("OK ")
    }

    fun getRecentPackages(): List<String> {
        val resp = sendCommand("GET_RECENTS", 1500)
        if (resp.startsWith("OK ")) {
            val data = resp.substring(3).trim()
            if (data.isEmpty()) return emptyList()
            return data.split(",").filter { it.isNotBlank() }
        }
        return emptyList()
    }

    fun cleanBackgroundRam(excludePkg: String): Int {
        val resp = sendCommand("CLEAN_RAM $excludePkg", 3000)
        if (resp.startsWith("OK ")) {
            return resp.substring(3).trim().toIntOrNull() ?: 0
        }
        return 0
    }

    fun cycleHdrMode(): String {
        val resp = sendCommand("CYCLE_HDR")
        if (resp.startsWith("OK ")) return resp.substring(3).trim()
        return "ERROR"
    }

    fun cycleAudioPassthrough(): String {
        val resp = sendCommand("CYCLE_AUDIO")
        if (resp.startsWith("OK ")) return resp.substring(3).trim()
        return "ERROR"
    }

    fun startScreensaver(): Boolean {
        val resp = sendCommand("START_DREAM")
        return resp.startsWith("OK")
    }

    fun injectKey(keyCode: Int): Boolean {
        val resp = sendCommand("INJECT_KEY $keyCode")
        return resp.startsWith("OK")
    }

    fun setSystemDisplayMode(modeId: Int, width: Int, height: Int, refreshRate: Float): Boolean {
        val hzStr = String.format(Locale.US, "%.6f", refreshRate)
        val resp = sendCommand("SET_DISPLAY_MODE $modeId $width $height $hzStr", 10000)
        return resp.startsWith("OK")
    }

    fun clearSystemDisplayMode(): Boolean {
        val resp = sendCommand("SET_DISPLAY_MODE -1 0 0 0", 10000)
        return resp.startsWith("OK")
    }

    fun resetRefreshLimits(): Boolean = sendCommand("RESET_REFRESH_LIMITS", 15000).startsWith("OK")

    fun installApk(apkPath: String): Boolean {
        val resp = sendCommand("INSTALL_APK $apkPath", 15000)
        return resp.startsWith("OK")
    }
}

data class DaemonSample(
    val online: Boolean,
    val fps: Float,
    val fpsSource: String,
    val cpuTempC: Float
)

fun ServerClient.isDaemonOnlineSync(): Boolean = isServerRunning()

fun ServerClient.querySysInfoSync(): DaemonSample {
    val info = getSysInfo()
    val online = info.fpsSource != "OFFLINE" && info.fpsSource != "ERR"
    return DaemonSample(
        online = online,
        fps = info.fps,
        fpsSource = info.fpsSource,
        cpuTempC = info.socTempCelsius
    )
}
