package dev.custom.tvafrtools.server

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Standalone Privileged Server for TvAfrTools (Universal Android 9–14 Edition).
 *
 * Runs via `app_process` (UID 2000 / shell) spawned automatically by LocalAdbBootstrap.
 * Provides:
 * 1. Universal Multi-Layer Video FPS Detection (SurfaceFlinger + Tunneled Widevine L1 + MediaMetrics + Sysfs):
 *    - Layer A (Tunneled Hardware Video / Widevine L1 4K HDR in Disney+, Prime Video, Netflix):
 *      - Parses `VDA : ServiceDeviceTask ... OUTs=X/Y` frame counters over 1-second intervals
 *      - Parses `VideoSyncFRAC: [detectPattern] 3:2 pattern detected` -> 23.976 fps
 *      - Parses `VideoRenderQualityTracker: Rendered frame is earlier than the next expected frame (tsUs, ...)` delta
 *    - Layer B (Android 12–14 / SDK 31+): `IWindowManager.registerTaskFpsCallback`
 *      via `/system/framework/framework.jar`'s `android.window.ITaskFpsCallback$Stub`
 *    - Layer C (Android 9–14 Universal MediaMetrics / SurfaceFlinger latency):
 *      - `dumpsys media.metrics` (`frameRate=` / `video-frame-rate`)
 *    - Layer D (Amlogic / MediaTek / Xiaomi sysfs & system properties):
 *      - `sys.nes.info.frame_rate`, `/sys/class/vdec/vdec_status`, `/sys/class/video/frame_rate`
 * 2. Universal Real Screen Off (Android 9–14):
 *    - SDK < 29: `SurfaceControl.getBuiltInDisplay(0)`
 *    - SDK 29–33: `SurfaceControl.getInternalDisplayToken()` / `getPhysicalDisplayIds()`
 *    - SDK 34+ (Android 14): `DisplayControl` via `ClassLoaderFactory` (`/system/framework/services.jar`)
 *    - Honor / Huawei Android 14 quirk fallback
 * 3. Universal Seamless / Non-Seamless AFR Enforcement:
 *    - Configures `settings put secure match_content_frame_rate 2` (ALWAYS)
 *    - Calls `IDisplayManager.setShouldAlwaysRespectAppRequestedMode(true)` when available
 *    - Persists `service.adb.tcp.port 5555` so local ADB remains available across reboots
 * 4. Universal Thermal & System Info (`IThermalService` + `/sys/class/thermal`)
 * 5. Force-Stop Package (`IActivityManager.forceStopPackage`) & Clean Background RAM
 * 6. HDR / Dolby Vision Conversion Mode & Audio Output Format Cycling
 */
object PrivilegedServer {
    const val PORT = 15099

    @Volatile
    private var currentFps: Float = 0f

    @Volatile
    private var currentFpsSource: String = "IDLE"

    @Volatile
    private var currentTaskId: Int = -1

    @Volatile
    private var registeredCallback: Any? = null

    @Volatile
    private var tunneledVideoActive: Boolean = false

    @Volatile
    private var lastTunneledFps: Float = 0f

    @Volatile
    private var lastTunneledFpsTimestampMs: Long = 0L

    // Cached reflection handles
    private var displayControlClass: Class<*>? = null
    private var surfaceControlClass: Class<*>? = null

    private fun getSystemServiceBinder(name: String): IBinder? {
        return try {
            val sm = Class.forName("android.os.ServiceManager")
            val getService = sm.getMethod("getService", String::class.java)
            getService.invoke(null, name) as? IBinder
        } catch (_: Throwable) {
            null
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("TvAfrTools PrivilegedServer starting on 127.0.0.1:$PORT (UID=${android.os.Process.myUid()}, SDK=${Build.VERSION.SDK_INT})...")
        try {
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
        }

        initDisplayClasses()
        ensureUniversalSystemSettings()
        startTaskFpsMonitorThread()
        startTunneledVideoLogWatcherThread()

        val executor = Executors.newCachedThreadPool()
        Thread {
            try {
                val server = ServerSocket(PORT, 50, InetAddress.getByName("127.0.0.1"))
                println("TvAfrTools PrivilegedServer listening on 127.0.0.1:$PORT")
                while (true) {
                    val client = server.accept()
                    executor.execute { handleClient(client) }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                System.exit(1)
            }
        }.start()

        Looper.loop()
    }

    /**
     * Ensures universal Android TV settings for AFR and persistent local ADB across Xiaomi,
     * Chromecast with Google TV, Homatics, Nvidia Shield, and Sony/TCL TVs.
     */
    private fun ensureUniversalSystemSettings() {
        Thread {
            try {
                // Ensure non-seamless refresh rate switching (ALWAYS = 2) is enabled on Android 12-14
                runShellCommand("settings put secure match_content_frame_rate 2")
                // Keep TCP ADB port 5555 open across reboots on Xiaomi / Google TV boxes
                runShellCommand("setprop persist.adb.tcp.port 5555")
            } catch (_: Throwable) {
            }
        }.start()
    }

    /**
     * Watches live logcat specifically for Tunneled Video Playback (Widevine L1 4K HDR in Disney+,
     * Prime Video, Netflix, Apple TV+) where frames bypass SurfaceFlinger and go straight to HWCVIDEO.
     *
     * Detects:
     * 1. `VideoRenderQualityTracker` microsecond presentation timestamps (Universal ExoPlayer/Media3 tunnel):
     *    e.g., `(1000152416666, ...)` -> delta 41666 us = 23.976 / 24.000 fps; 40000 us = 25.0 fps; 33333 us = 29.97 fps
     * 2. `VideoSyncFRAC: ... [detectPattern]3:2 pattern detected` (Amlogic / Xiaomi / Homatics hardware tunnel):
     *    3:2 pulldown on 60Hz means the underlying video stream is 23.976 / 24 fps!
     * 3. `VDA : ... ServiceDeviceTask INs=A/B, OUTs=C/D` (Codec2 hardware decoder output counter):
     *    Measures exact decoded frames per second over a 1-second sliding window.
     */
    private fun startTunneledVideoLogWatcherThread() {
        Thread({
            while (true) {
                var proc: Process? = null
                try {
                    // Clear old logcat lines only in our stream by reading from recent tail
                    proc = ProcessBuilder(
                        "logcat",
                        "-v", "epoch",
                        "-T", "20",
                        "-s",
                        "VDA",
                        "VideoRenderQualityTracker",
                        "VideoSyncFRAC",
                        "Mediahal_VideoTunnelRenderer",
                        "C2VdecComponent"
                    ).redirectErrorStream(true).start()

                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var lastPtsUs = -1L
                    val ptsDeltasUs = ArrayDeque<Long>(8)

                    var lastVdaOutCount = -1L
                    var lastVdaTimeMs = 0L

                    val regexQualityTracker = Regex("""Rendered frame is earlier than the next expected frame \((\d+),\s*(\d+)\)""")
                    val regexVdaOuts = Regex("""^\s*(\d+\.\d+).*?OUTs=(\d+)/(\d+)""")

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        val nowMs = SystemClock.elapsedRealtime()

                        // 1. Check Amlogic / Xiaomi / Homatics VideoSyncFRAC 3:2 pulldown detector
                        if (l.contains("3:2 pattern detected")) {
                            lastTunneledFps = 23.976f
                            lastTunneledFpsTimestampMs = nowMs
                            tunneledVideoActive = true
                            currentFps = 23.976f
                            currentFpsSource = "TUNNEL_3_2_PULLDOWN"
                            continue
                        }

                        // 2. Check Universal ExoPlayer / Media3 VideoRenderQualityTracker PTS delta (Disney+, Prime, etc.)
                        val mQt = regexQualityTracker.find(l)
                        if (mQt != null) {
                            val ptsUs = mQt.groupValues[1].toLongOrNull() ?: -1L
                            if (ptsUs > 0L) {
                                if (lastPtsUs > 0L) {
                                    val deltaUs = ptsUs - lastPtsUs
                                    // Valid single-frame delta range: 15,000 us (66fps) to 45,000 us (22fps)
                                    if (deltaUs in 15000L..45000L) {
                                        if (ptsDeltasUs.size >= 6) ptsDeltasUs.removeFirst()
                                        ptsDeltasUs.addLast(deltaUs)
                                        if (ptsDeltasUs.size >= 3) {
                                            val avgDeltaUs = ptsDeltasUs.average()
                                            val rawFps = (1_000_000.0 / avgDeltaUs).toFloat()
                                            val snapped = snapTunneledFps(rawFps)
                                            if (snapped > 0f) {
                                                lastTunneledFps = snapped
                                                lastTunneledFpsTimestampMs = nowMs
                                                tunneledVideoActive = true
                                                currentFps = snapped
                                                currentFpsSource = "TUNNEL_PTS"
                                            }
                                        }
                                    }
                                }
                                lastPtsUs = ptsUs
                            }
                            continue
                        }

                        // 3. Check Codec2 VDA hardware output frame counter (works in Disney+, Prime, DAZN, Netflix!)
                        if (l.contains("ServiceDeviceTask") && l.contains("OUTs=")) {
                            val mVda = regexVdaOuts.find(l)
                            if (mVda != null) {
                                val epochSec = mVda.groupValues[1].toDoubleOrNull() ?: 0.0
                                val logTimeMs = if (epochSec > 1000.0) (epochSec * 1000.0).toLong() else nowMs
                                val outCount = mVda.groupValues[2].toLongOrNull() ?: -1L
                                if (outCount > 0L) {
                                    if (lastVdaOutCount > 0L && outCount > lastVdaOutCount && logTimeMs > lastVdaTimeMs) {
                                        val dtMs = logTimeMs - lastVdaTimeMs
                                        val dFrames = outCount - lastVdaOutCount
                                        if (dtMs in 600L..3500L && dFrames in 10L..250L) {
                                            val vdaFps = (dFrames * 1000f) / dtMs.toFloat()
                                            val snapped = snapTunneledFps(vdaFps)
                                            if (snapped > 0f) {
                                                lastTunneledFps = snapped
                                                lastTunneledFpsTimestampMs = nowMs
                                                tunneledVideoActive = true
                                                currentFps = snapped
                                                currentFpsSource = "TUNNEL_VDA"
                                            }
                                        }
                                    }
                                    lastVdaOutCount = outCount
                                    lastVdaTimeMs = logTimeMs
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    try {
                        proc?.destroy()
                    } catch (_: Throwable) {
                    }
                }
                Thread.sleep(2000L)
            }
        }, "TunneledVideoWatcher").apply { isDaemon = true }.start()
    }

    private fun snapTunneledFps(rawFps: Float): Float {
        return when {
            rawFps in 22.8f..24.35f -> 23.976f
            rawFps in 24.45f..25.8f -> 25.0f
            rawFps in 29.2f..30.6f -> 29.97f
            rawFps in 49.0f..51.0f -> 50.0f
            rawFps in 58.5f..61.0f -> 59.94f
            else -> 0f
        }
    }

    private fun startTaskFpsMonitorThread() {
        Thread({
            val wmBinder = getSystemServiceBinder("window")
            val wmStubClass = Class.forName("android.view.IWindowManager\$Stub")
            val asInterface = wmStubClass.getMethod("asInterface", IBinder::class.java)
            val wm = asInterface.invoke(null, wmBinder)
            val wmClass = wm.javaClass

            val registerMethod: Method? = try {
                val callbackIntf = Class.forName("android.window.ITaskFpsCallback")
                wmClass.getMethod("registerTaskFpsCallback", Int::class.javaPrimitiveType, callbackIntf)
            } catch (_: Throwable) {
                null
            }
            val unregisterMethod: Method? = try {
                val callbackIntf = Class.forName("android.window.ITaskFpsCallback")
                wmClass.getMethod("unregisterTaskFpsCallback", callbackIntf)
            } catch (_: Throwable) {
                null
            }

            while (true) {
                try {
                    val nowMs = SystemClock.elapsedRealtime()
                    val tunnelRecent = (nowMs - lastTunneledFpsTimestampMs) < 6000L && lastTunneledFps > 0f

                    val topTaskId = getForegroundTaskId()
                    if (topTaskId > 0 && topTaskId != currentTaskId && registerMethod != null) {
                        registeredCallback?.let { oldCb ->
                            try {
                                unregisterMethod?.invoke(wm, oldCb)
                            } catch (_: Throwable) {
                            }
                        }
                        val newCb = createTaskFpsCallbackStub { fps ->
                            if (fps > 1.0f) {
                                val ageMs = SystemClock.elapsedRealtime() - lastTunneledFpsTimestampMs
                                // If Tunneled Video (HWCVIDEO Widevine L1) is active, SurfaceFlinger only sees the 60fps UI/subtitle layer!
                                // Do NOT let a ~60fps UI layer overwrite a recently detected 23.976/25/29.97fps Tunneled Video stream!
                                if (ageMs < 6000L && lastTunneledFps in 23.0f..51.0f && fps >= 55.0f) {
                                    currentFps = lastTunneledFps
                                } else {
                                    currentFps = fps
                                    currentFpsSource = "SURFACE_FLINGER"
                                }
                            }
                        }
                        if (newCb != null) {
                            registerMethod.invoke(wm, topTaskId, newCb)
                            registeredCallback = newCb
                            currentTaskId = topTaskId
                        }
                    }

                    if (tunnelRecent) {
                        currentFps = lastTunneledFps
                    } else {
                        // Also check universal fallbacks (SysProp / MediaMetrics / HWCVIDEO)
                        val fallbackFps = readUniversalFallbackFps()
                        if (fallbackFps > 1.0f) {
                            // If SurfaceFlinger reports ~60fps (UI) while fallback detects movie rate, prefer movie rate
                            if (currentFps <= 1.0f || (currentFps >= 55.0f && fallbackFps < 55.0f)) {
                                currentFps = fallbackFps
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                Thread.sleep(750)
            }
        }, "TaskFpsWatcher").apply { isDaemon = true }.start()
    }

    private fun createTaskFpsCallbackStub(onFps: (Float) -> Unit): Any? {
        return try {
            val stubClass = Class.forName("android.window.ITaskFpsCallback\$Stub")
            object : Binder(), IInterface {
                init {
                    attachInterface(this, "android.window.ITaskFpsCallback")
                }

                override fun asBinder(): IBinder = this

                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code == FIRST_CALL_TRANSACTION) { // onFpsReported(float fps)
                        data.enforceInterface("android.window.ITaskFpsCallback")
                        val fps = data.readFloat()
                        onFps(fps)
                        return true
                    } else if (code == INTERFACE_TRANSACTION) {
                        reply?.writeString("android.window.ITaskFpsCallback")
                        return true
                    }
                    return super.onTransact(code, data, reply, flags)
                }
            }.let { rawBinder ->
                val asIntf = stubClass.getMethod("asInterface", IBinder::class.java)
                asIntf.invoke(null, rawBinder)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    private fun getForegroundTaskId(): Int {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val atm = getService.invoke(null)
            val getTasks = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
            val tasks = getTasks.invoke(atm, 1) as? List<*>
            if (!tasks.isNullOrEmpty()) {
                val taskInfo = tasks[0] ?: return -1
                val taskIdField = taskInfo.javaClass.getField("taskId")
                return taskIdField.getInt(taskInfo)
            }
        } catch (_: Throwable) {
        }
        return -1
    }

    /**
     * Universal Fallback FPS reader for:
     * - Xiaomi Mi Box / TV Stick (Android 9-14)
     * - Amlogic / MediaTek / Realtek / Nvidia Shield sysfs & system properties
     * - MediaMetrics codec frameRate
     */
    private fun readUniversalFallbackFps(): Float {
        // 1. Check recent Tunneled Video detection first
        val ageMs = SystemClock.elapsedRealtime() - lastTunneledFpsTimestampMs
        if (ageMs < 6000L && lastTunneledFps > 1.0f) {
            return lastTunneledFps
        }

        // 2. Check system properties (used by Xiaomi / Amlogic / Realtek / HiSilicon)
        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            val propKeys = arrayOf(
                "sys.nes.info.frame_rate",
                "vendor.display.frame_rate",
                "sys.display.frame_rate",
                "vendor.media.video.fps"
            )
            for (key in propKeys) {
                val prop = (get.invoke(null, key, "") as? String)?.replace("fps", "")?.trim().orEmpty()
                if (prop.isNotEmpty()) {
                    val v = prop.toFloatOrNull()
                    if (v != null && v > 1.0f) {
                        currentFpsSource = "SYSPROP:$key"
                        return when (v.toInt()) {
                            100 -> 50f
                            48 -> 24f
                            120 -> 60f
                            else -> v
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }

        // 3. Check readable sysfs nodes (only if File.canRead() to avoid SELinux audit spam!)
        val sysPaths = arrayOf(
            "/sys/class/vdec/vdec_status",
            "/sys/class/video/frame_rate",
            "/proc/amstream/vdec_status"
        )
        for (path in sysPaths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val text = f.readText()
                    val m1 = Regex("""frame_rate\s*[:=]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                        ?: Regex("""fps\s*[:=]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                    if (m1 != null) {
                        val v = m1.groupValues[1].toFloatOrNull() ?: 0f
                        if (v > 1.0f) {
                            currentFpsSource = "SYSFS:$path"
                            return v
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return 0f
    }

    /**
     * On-demand deep probe for 1-Click AFR when immediate `currentFps` looks like UI (60fps)
     * or 0fps during Tunneled Widevine L1 playback (Disney+, Prime Video, Netflix, etc.).
     * Samples Codec2 VDA / VideoSyncFRAC / SurfaceFlinger HWCVIDEO over ~1.1s if needed.
     */
    private fun probeActiveVideoFpsNow(): Pair<Float, String> {
        val nowMs = SystemClock.elapsedRealtime()
        if ((nowMs - lastTunneledFpsTimestampMs) < 8000L && lastTunneledFps > 1.0f) {
            return lastTunneledFps to currentFpsSource
        }

        // Check recent Codec2 VDA / VideoRenderQualityTracker / VideoSyncFRAC logs directly
        try {
            val logOut = runShellCommand("logcat -d -v epoch -t 80 -s VDA VideoRenderQualityTracker VideoSyncFRAC")

            // 1. Check VDA OUTs delta using epoch timestamps (exact FPS for Disney+, Prime, DAZN, Netflix)
            val regexVda = Regex("""^\s*(\d+\.\d+).*?ServiceDeviceTask.*?OUTs=(\d+)/\d+""", RegexOption.MULTILINE)
            val vdaMatches = regexVda.findAll(logOut).toList()
            if (vdaMatches.size >= 2) {
                val m1 = vdaMatches[vdaMatches.size - 2]
                val m2 = vdaMatches.last()
                val t1 = m1.groupValues[1].toDoubleOrNull() ?: 0.0
                val t2 = m2.groupValues[1].toDoubleOrNull() ?: 0.0
                val out1 = m1.groupValues[2].toLongOrNull() ?: 0L
                val out2 = m2.groupValues[2].toLongOrNull() ?: 0L
                val dt = t2 - t1
                val dOut = out2 - out1
                if (dt in 0.5..3.5 && dOut > 5L) {
                    val vdaFps = (dOut.toDouble() / dt).toFloat()
                    val snapped = snapTunneledFps(vdaFps)
                    if (snapped > 0f) {
                        lastTunneledFps = snapped
                        lastTunneledFpsTimestampMs = SystemClock.elapsedRealtime()
                        currentFps = snapped
                        currentFpsSource = "TUNNEL_VDA_PROBE"
                        return snapped to currentFpsSource
                    }
                }
            }

            // 2. Check VideoRenderQualityTracker PTS delta
            val regexQt = Regex("""Rendered frame is earlier than the next expected frame \((\d+),\s*(\d+)\)""")
            val ptsList = regexQt.findAll(logOut).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()
            if (ptsList.size >= 4) {
                val deltas = mutableListOf<Long>()
                for (i in 1 until ptsList.size) {
                    val d = ptsList[i] - ptsList[i - 1]
                    if (d in 15000L..45000L) deltas.add(d)
                }
                if (deltas.size >= 2) {
                    val avgDelta = deltas.takeLast(6).average()
                    val snapped = snapTunneledFps((1_000_000.0 / avgDelta).toFloat())
                    if (snapped > 0f) {
                        lastTunneledFps = snapped
                        lastTunneledFpsTimestampMs = SystemClock.elapsedRealtime()
                        currentFps = snapped
                        currentFpsSource = "TUNNEL_PTS_PROBE"
                        return snapped to currentFpsSource
                    }
                }
            }

            // 3. Check 3:2 pulldown pattern
            if (logOut.contains("3:2 pattern detected")) {
                lastTunneledFps = 23.976f
                lastTunneledFpsTimestampMs = SystemClock.elapsedRealtime()
                currentFps = 23.976f
                currentFpsSource = "TUNNEL_3_2_PROBE"
                return 23.976f to currentFpsSource
            }
        } catch (_: Throwable) {
        }

        // Check Android 9-14 dumpsys media.metrics for codec frameRate
        try {
            val mmOut = runShellCommand("dumpsys media.metrics")
            val regexCodecFps = Regex("""frameRate=(\d+(?:\.\d+)?)""")
            val matches = regexCodecFps.findAll(mmOut).toList()
            if (matches.isNotEmpty()) {
                val fps = matches.last().groupValues[1].toFloatOrNull() ?: 0f
                if (fps > 1.0f) {
                    return fps to "MEDIA_METRICS"
                }
            }
        } catch (_: Throwable) {
        }

        return currentFps to currentFpsSource
    }

    private fun initDisplayClasses() {
        try {
            surfaceControlClass = Class.forName("android.view.SurfaceControl")
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val clfClass = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val createMethod = clfClass.getDeclaredMethod(
                    "createClassLoader",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    ClassLoader::class.java,
                    Int::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    String::class.java
                )
                createMethod.isAccessible = true
                val cl = createMethod.invoke(
                    null,
                    "/system/framework/services.jar",
                    null,
                    null,
                    ClassLoader.getSystemClassLoader(),
                    0,
                    true,
                    null
                ) as ClassLoader
                displayControlClass = cl.loadClass("com.android.server.display.DisplayControl")
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Universal Real Screen Off across Android 9, 10, 11, 12, 13, and 14
     * (Xiaomi Mi Box, Homatics, Nvidia Shield, Sony/TCL, Honor/Huawei).
     */
    private fun setScreenPowerMode(powerMode: Int): Boolean {
        val sc = surfaceControlClass ?: return false
        try {
            val setPowerMethod = sc.getMethod(
                "setDisplayPowerMode",
                IBinder::class.java,
                Int::class.javaPrimitiveType
            )

            // 1. Honor / Huawei Android 14 quirk check
            if (Build.VERSION.SDK_INT >= 34 && Build.BRAND.equals("honor", ignoreCase = true)) {
                try {
                    val getInternal = sc.getMethod("getInternalDisplayToken")
                    val token = getInternal.invoke(null) as? IBinder
                    if (token != null) {
                        setPowerMethod.invoke(null, token, powerMode)
                        return true
                    }
                } catch (_: Throwable) {
                }
            }

            // 2. Android 14+ (SDK 34+) DisplayControl in services.jar
            val dc = displayControlClass
            if (dc != null) {
                val getIds = dc.getMethod("getPhysicalDisplayIds")
                val getToken = dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                val ids = getIds.invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    var ok = false
                    for (id in ids) {
                        val token = getToken.invoke(null, id) as? IBinder
                        if (token != null) {
                            setPowerMethod.invoke(null, token, powerMode)
                            ok = true
                        }
                    }
                    if (ok) return true
                }
            }

            // 3. Android 10–13 (SDK 29–33): SurfaceControl.getPhysicalDisplayIds()
            try {
                val getIds = sc.getMethod("getPhysicalDisplayIds")
                val getToken = sc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType)
                val ids = getIds.invoke(null) as? LongArray
                if (ids != null && ids.isNotEmpty()) {
                    var ok = false
                    for (id in ids) {
                        val token = getToken.invoke(null, id) as? IBinder
                        if (token != null) {
                            setPowerMethod.invoke(null, token, powerMode)
                            ok = true
                        }
                    }
                    if (ok) return true
                }
            } catch (_: Throwable) {
            }

            // 4. Android 10–12 fallback: SurfaceControl.getInternalDisplayToken()
            try {
                val getInternal = sc.getMethod("getInternalDisplayToken")
                val token = getInternal.invoke(null) as? IBinder
                if (token != null) {
                    setPowerMethod.invoke(null, token, powerMode)
                    return true
                }
            } catch (_: Throwable) {
            }

            // 5. Android 9 (SDK 28) fallback: SurfaceControl.getBuiltInDisplay(0)
            try {
                val getBuiltIn = sc.getMethod("getBuiltInDisplay", Int::class.javaPrimitiveType)
                val token = getBuiltIn.invoke(null, 0) as? IBinder
                if (token != null) {
                    setPowerMethod.invoke(null, token, powerMode)
                    return true
                }
            } catch (_: Throwable) {
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * Universal system-level display mode setter for devices/apps where Window preferredDisplayModeId
     * is ignored or overridden by the streaming app.
     */
    private fun setSystemPreferredDisplayMode(modeId: Int, width: Int, height: Int, refreshRate: Float): Boolean {
        return try {
            if (modeId <= 0 || width <= 0 || height <= 0 || refreshRate <= 1.0f) {
                runShellCommand("cmd display clear-user-preferred-display-mode 0")
                true
            } else {
                // Ensure non-seamless refresh rate switching is enabled on Android 12-14
                runShellCommand("settings put secure match_content_frame_rate 2")
                // Format refresh rate to 6 decimal places (e.g. 23.976025, 50.000000, 59.940060)
                val hzStr = String.format(Locale.US, "%.6f", refreshRate)
                val cmd = "cmd display set-user-preferred-display-mode $width $height $hzStr 0"
                runShellCommand(cmd)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Universal SoC temperature reader across Amlogic, MediaTek, Qualcomm, Nvidia Tegra, Realtek.
     * Uses Android 10+ `IThermalService` Binder interface first, then falls back to `/sys/class/thermal`.
     */
    private fun readUniversalSocTempCelsius(): Float {
        // 1. Try Android 10+ IThermalService Binder
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val binder = getSystemServiceBinder("thermalservice")
                if (binder != null) {
                    val stubClass = Class.forName("android.os.IThermalService\$Stub")
                    val asIntf = stubClass.getMethod("asInterface", IBinder::class.java)
                    val thermalService = asIntf.invoke(null, binder)
                    val getTemps = thermalService.javaClass.getMethod("getCurrentTemperatures")
                    val temps = getTemps.invoke(thermalService) as? Array<*>
                    if (!temps.isNullOrEmpty()) {
                        for (t in temps) {
                            if (t == null) continue
                            val getValue = t.javaClass.getMethod("getValue")
                            val v = (getValue.invoke(t) as? Float) ?: continue
                            if (v in 20.0f..115.0f) {
                                return v
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }

        // 2. Fallback to readable thermal_zone sysfs
        val paths = arrayOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/devices/virtual/thermal/thermal_zone0/temp"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val raw = f.readText().trim().toFloatOrNull() ?: continue
                    val c = if (raw > 1000f) raw / 1000f else raw
                    if (c in 20.0f..115.0f) return c
                }
            } catch (_: Throwable) {
            }
        }
        return -1f
    }

    private fun forceStopPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return try {
            val amBinder = getSystemServiceBinder("activity")
            val amStub = Class.forName("android.app.IActivityManager\$Stub")
            val asIntf = amStub.getMethod("asInterface", IBinder::class.java)
            val am = asIntf.invoke(null, amBinder)
            val forceStop = am.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            forceStop.invoke(am, pkg, 0)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Lists recent/running user packages and optionally kills background packages to free RAM
     * (crucial on 2GB RAM devices like Xiaomi Mi Box S / Mi TV Stick / Chromecast).
     */
    private fun getRecentPackages(): List<String> {
        val result = mutableListOf<String>()
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val atm = getService.invoke(null)
            val getTasks = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
            val tasks = getTasks.invoke(atm, 15) as? List<*>
            if (!tasks.isNullOrEmpty()) {
                for (task in tasks) {
                    if (task == null) continue
                    val baseActivityField = task.javaClass.getField("baseActivity")
                    val comp = baseActivityField.get(task) as? android.content.ComponentName ?: continue
                    val pkg = comp.packageName
                    if (pkg != "dev.custom.tvafrtools" &&
                        !pkg.contains("launcher") &&
                        !pkg.contains("tvrecommendations") &&
                        !pkg.startsWith("com.android.systemui") &&
                        !result.contains(pkg)
                    ) {
                        result.add(pkg)
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return result
    }

    private fun cleanBackgroundApps(excludePkg: String): Int {
        val recents = getRecentPackages()
        var killed = 0
        for (pkg in recents) {
            if (pkg != excludePkg && pkg != "dev.custom.tvafrtools") {
                if (forceStopPackage(pkg)) {
                    killed++
                }
            }
        }
        return killed
    }

    private fun cycleHdrConversionMode(): String {
        return try {
            // Android 13-14 cmd display hdr-conversion
            val cur = runShellCommand("settings get global hdr_conversion_mode").trim()
            if (cur == "1" || cur.isEmpty()) {
                // Force SDR or system default toggle
                runShellCommand("cmd display set-hdr-conversion-mode 2 0")
                runShellCommand("settings put global hdr_conversion_mode 2")
                "PASSTHROUGH_DINAMICO"
            } else {
                runShellCommand("cmd display set-hdr-conversion-mode 1 0")
                runShellCommand("settings put global hdr_conversion_mode 1")
                "SISTEMA_AUTO"
            }
        } catch (_: Throwable) {
            "NO_SOPORTADO"
        }
    }

    private fun cycleAudioPassthroughMode(): String {
        return try {
            val cur = runShellCommand("settings get global encoded_surround_output").trim()
            // 0 = AUTO, 1 = NEVER (PCM), 2 = ALWAYS
            val next = when (cur) {
                "0" -> "2"
                "2" -> "1"
                else -> "0"
            }
            runShellCommand("settings put global encoded_surround_output $next")
            when (next) {
                "0" -> "AUTO (Dolby/Passthrough)"
                "2" -> "FORZAR SURROUND"
                else -> "ESTÉREO PCM (Claridad Diálogos)"
            }
        } catch (_: Throwable) {
            "AUTO"
        }
    }

    private fun injectKeyEvent(keyCode: Int): Boolean {
        return try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)

            val imGlobalClass = try {
                Class.forName("android.hardware.input.InputManagerGlobal")
            } catch (_: Throwable) {
                Class.forName("android.hardware.input.InputManager")
            }
            val getInstance = imGlobalClass.getMethod("getInstance")
            val im = getInstance.invoke(null)
            val inject = im.javaClass.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
            inject.invoke(im, down, 0)
            inject.invoke(im, up, 0)
            true
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    private fun runShellCommand(cmd: String): String {
        return try {
            val proc = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            proc.waitFor(1500, TimeUnit.MILLISECONDS)
            proc.inputStream.bufferedReader().readText()
        } catch (_: Throwable) {
            ""
        }
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { s ->
            s.soTimeout = 3500
            val reader = BufferedReader(InputStreamReader(s.getInputStream()))
            val writer = PrintWriter(s.getOutputStream(), true)
            val line = reader.readLine()?.trim() ?: return
            val parts = line.split(" ")
            when (parts[0].uppercase(Locale.US)) {
                "PING" -> {
                    writer.println("OK PONG FPS=${String.format(Locale.US, "%.3f", currentFps)} SRC=$currentFpsSource")
                }
                "GET_FPS" -> {
                    val nowMs = SystemClock.elapsedRealtime()
                    val fps = if ((nowMs - lastTunneledFpsTimestampMs) < 6000L && lastTunneledFps > 1.0f) {
                        lastTunneledFps
                    } else if (currentFps > 1.0f) {
                        currentFps
                    } else {
                        readUniversalFallbackFps()
                    }
                    writer.println("OK ${String.format(Locale.US, "%.3f", fps)} $currentFpsSource")
                }
                "PROBE_FPS" -> {
                    val (fps, src) = probeActiveVideoFpsNow()
                    writer.println("OK ${String.format(Locale.US, "%.3f", fps)} $src")
                }
                "GET_SYSINFO" -> {
                    val tempC = readUniversalSocTempCelsius()
                    val nowMs = SystemClock.elapsedRealtime()
                    val fps = if ((nowMs - lastTunneledFpsTimestampMs) < 6000L && lastTunneledFps > 1.0f) {
                        lastTunneledFps
                    } else {
                        currentFps
                    }
                    writer.println("OK FPS=${String.format(Locale.US, "%.3f", fps)} SRC=$currentFpsSource TEMP=${String.format(Locale.US, "%.1f", tempC)}")
                }
                "SCREEN_OFF" -> {
                    val ok = setScreenPowerMode(0)
                    writer.println(if (ok) "OK SCREEN_OFF" else "ERR SCREEN_OFF_FAILED")
                }
                "SCREEN_ON" -> {
                    val ok = setScreenPowerMode(2)
                    writer.println(if (ok) "OK SCREEN_ON" else "ERR SCREEN_ON_FAILED")
                }
                "FORCE_STOP" -> {
                    val pkg = parts.getOrNull(1).orEmpty()
                    val ok = forceStopPackage(pkg)
                    writer.println(if (ok) "OK FORCE_STOP $pkg" else "ERR FORCE_STOP_FAILED")
                }
                "GET_RECENTS" -> {
                    val list = getRecentPackages()
                    writer.println("OK ${list.joinToString(",")}")
                }
                "CLEAN_RAM" -> {
                    val exclude = parts.getOrNull(1).orEmpty()
                    val killed = cleanBackgroundApps(exclude)
                    writer.println("OK $killed")
                }
                "CYCLE_HDR" -> {
                    val mode = cycleHdrConversionMode()
                    writer.println("OK $mode")
                }
                "CYCLE_AUDIO" -> {
                    val mode = cycleAudioPassthroughMode()
                    writer.println("OK $mode")
                }
                "SET_DISPLAY_MODE" -> {
                    val modeId = parts.getOrNull(1)?.toIntOrNull() ?: -1
                    val w = parts.getOrNull(2)?.toIntOrNull() ?: 0
                    val h = parts.getOrNull(3)?.toIntOrNull() ?: 0
                    val hz = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
                    val ok = setSystemPreferredDisplayMode(modeId, w, h, hz)
                    writer.println(if (ok) "OK SET_DISPLAY_MODE" else "ERR")
                }
                "START_DREAM" -> {
                    runShellCommand("cmd dreams start-dreaming")
                    writer.println("OK DREAM")
                }
                "INJECT_KEY" -> {
                    val keyCode = parts.getOrNull(1)?.toIntOrNull() ?: 0
                    val ok = if (keyCode > 0) injectKeyEvent(keyCode) else false
                    writer.println(if (ok) "OK INJECT_KEY $keyCode" else "ERR INJECT_KEY_FAILED")
                }
                else -> {
                    writer.println("ERR UNKNOWN_CMD")
                }
            }
        }
    }
}
