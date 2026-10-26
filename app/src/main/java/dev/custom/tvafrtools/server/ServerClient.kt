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
        val socTempCelsius: Float
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

    fun getSysInfo(): SysInfo {
        val resp = sendCommand("GET_SYSINFO", 1200)
        if (resp.startsWith("OK ")) {
            var fps = 0f
            var src = "IDLE"
            var temp = -1f
            val parts = resp.substring(3).trim().split(" ")
            for (p in parts) {
                if (p.startsWith("FPS=")) fps = p.substring(4).toFloatOrNull() ?: 0f
                else if (p.startsWith("SRC=")) src = p.substring(4)
                else if (p.startsWith("TEMP=")) temp = p.substring(5).toFloatOrNull() ?: -1f
            }
            return SysInfo(fps, src, temp)
        }
        return SysInfo(0f, "OFFLINE", -1f)
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
        val resp = sendCommand("SET_DISPLAY_MODE $modeId $width $height $hzStr")
        return resp.startsWith("OK")
    }

    fun clearSystemDisplayMode(): Boolean {
        val resp = sendCommand("SET_DISPLAY_MODE -1 0 0 0")
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
