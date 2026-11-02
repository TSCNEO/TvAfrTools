package dev.custom.tvafrtools.server

import dev.custom.tvafrtools.BuildConfig
import dev.custom.tvafrtools.afr.FrameRates
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
import java.net.InetSocketAddress
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
 *      - Parses `VideoSyncFRAC: [detectPattern] 3:2 pattern detected` -> estimated 24 fps family
 *      - Parses `VideoRenderQualityTracker: Rendered frame is earlier than the next expected frame (tsUs, ...)` delta
 *    - Layer B (Android 12–14 / SDK 31+): `IWindowManager.registerTaskFpsCallback`
 *      via `/system/framework/framework.jar`s `android.window.ITaskFpsCallback$Stub`
 *    - Layer C (Android 9–14 Universal MediaMetrics / SurfaceFlinger latency):
 *      - `dumpsys media.metrics` (`frameRate=` / `frame-rate=` / `operating-rate=`)
 *    - Layer D (Amlogic / MediaTek / Xiaomi sysfs & system properties):
 *      - `/sys/class/video/frame_rate`, `/sys/class/vdec/vdec_status`, `sys.nes.info.frame_rate`
 * 2. Real Hardware HDMI Link Mode Detection (Amlogic / DRM sysfs):
 *    - `/sys/class/amhdmitx/amhdmitx0/disp_mode`, `/sys/class/display/mode`, `/sys/class/amhdmitx/amhdmitx0/config`
 * 3. Universal Real Screen Off (Android 9–14):
 *    - SDK < 29: `SurfaceControl.getBuiltInDisplay(0)`
 *    - SDK 29–33: `SurfaceControl.getInternalDisplayToken()` / `getPhysicalDisplayIds()`
 *    - SDK 34+ (Android 14): `DisplayControl` via `ClassLoaderFactory` (`/system/framework/services.jar`)
 *    - Honor / Huawei Android 14 quirk fallback
 * 4. Universal Seamless / Non-Seamless AFR Enforcement:
 *    - Configures `settings put secure match_content_frame_rate 2` (ALWAYS)
 *    - Persists `service.adb.tcp.port 5555` so local ADB remains available across reboots
 * 5. Universal Thermal & System Info (`IThermalService` + `/sys/class/thermal`)
 * 6. Force-Stop Package (`IActivityManager.forceStopPackage`) & Clean Background RAM
 * 7. HDR / Dolby Vision Conversion Mode & Audio Output Format Cycling
 */
object PrivilegedServer {
    const val PORT = BuildConfig.DAEMON_PORT

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

    @Volatile
    private var lastTunneledSource: String = "IDLE"

    @Volatile private var foregroundAppPid: Int = -1
    private val decoderFrameRates = DecoderFrameRateTracker()
    @Volatile private var inputVideoGeneration = 0L
    @Volatile private var lastFpsRequestAtMs = 0L
    private val freshDetection = FreshDetectionGate()
    private val ptsWake = Object()
    @Volatile private var verifiedInputRate: VerifiedInputRate? = null

    private fun currentVerifiedInputRate(): VerifiedInputRate? = verifiedInputRate?.takeIf {
        it.isCurrent(foregroundAppPid, inputVideoGeneration, decoderFrameRates.activeStreamKeys(it.pid),
            SystemClock.elapsedRealtime())
    }

    private data class LiveRate(val fps: Float, val source: String, val verified: Boolean,
        val samples: Int = 0, val spanUs: Long = 0)

    private fun readLiveRate(): LiveRate {
        lastFpsRequestAtMs = SystemClock.elapsedRealtime()
        if (synchronized(freshDetection) { freshDetection.pending }) return LiveRate(0f, "DETECTING", false)
        currentVerifiedInputRate()?.let {
            return LiveRate(it.fps, "VIDEO_INPUT_PTS", true, it.samples, it.spanUs)
        }
        val fps = if (SystemClock.elapsedRealtime() - lastTunneledFpsTimestampMs < 8000L && lastTunneledFps > 1f)
            lastTunneledFps else if (currentFps > 1f) currentFps else readUniversalFallbackFps()
        val source = if (currentFpsSource.startsWith("TUNNEL") && !currentFpsSource.contains("ESTIMATED"))
            "${currentFpsSource}_ESTIMATED" else currentFpsSource
        return LiveRate(fps, source, false)
    }

    private fun startInputPtsMonitorThread() {
        Thread({
            var failures = 0
            var previousStatus = ""
            while (true) {
                val pid = foregroundAppPid
                val streams = decoderFrameRates.activeStreamKeys(pid)
                val generation = inputVideoGeneration
                if (pid <= 0 || SystemClock.elapsedRealtime() - lastFpsRequestAtMs > 10_000L) {
                    synchronized(ptsWake) { ptsWake.wait(500L) }; continue
                }
                val captureRequest = synchronized(freshDetection) { freshDetection.requestId }
                val result = PerfettoPtsSampler.capture(pid)
                val sameStream = pid == foregroundAppPid && generation == inputVideoGeneration &&
                    (streams.isEmpty() || streams == decoderFrameRates.activeStreamKeys(pid))
                synchronized(freshDetection) {
                    if (sameStream && captureRequest == freshDetection.requestId) {
                        val reading = CodecInputTrace.uniqueVerified(result.readings)
                        if (reading != null) {
                            val evidence = reading.evidence
                            val fps = requireNotNull(evidence.fps)
                            if (currentVerifiedInputRate()?.fps != fps)
                                println("FPS verified=1 source=VIDEO_INPUT_PTS pid=$pid codec=${reading.stream.codec} fps=$fps samples=${evidence.samples} spanUs=${evidence.spanUs}")
                            verifiedInputRate = VerifiedInputRate(pid, generation, streams, fps, SystemClock.elapsedRealtime(), evidence.samples, evidence.spanUs)
                        } else if (result.readings.isNotEmpty()) {
                            // Competing codecs or a non-fitting timestamp sequence invalidate old evidence.
                            verifiedInputRate = null
                        }
                    }
                    freshDetection.complete(captureRequest)
                }
                val status = when {
                    !sameStream -> "SESSION_CHANGED"
                    result.error != null -> "UNAVAILABLE:${result.error}"
                    result.readings.isEmpty() -> "NO_VIDEO_PTS"
                    result.readings.size > 1 -> "COMPETING_CODECS"
                    else -> result.readings.single().evidence.status.name
                }
                if ("$pid:$status" != previousStatus) {
                    println("Input PTS status=$status pid=$pid traceBytes=${result.traceBytes}")
                    previousStatus = "$pid:$status"
                }
                failures = if (result.error != null) failures + 1 else 0
                if (failures == 1) println("Input PTS capture unavailable: ${result.error}")
                // Back off unsupported devices; every session terminates and releases its tracing resources.
                synchronized(ptsWake) {
                    if (!synchronized(freshDetection) { freshDetection.pending })
                        ptsWake.wait(if (failures > 0) 15_000L else 1000L)
                }
            }
        }, "InputPtsWatcher").apply { isDaemon = true }.start()
    }

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
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper()
            }
        } catch (_: Throwable) {
            try {
                if (Looper.myLooper() == null) {
                    Looper.prepare()
                }
            } catch (_: Throwable) {}
        }

        // Start ServerSocket listener FIRST with reuseAddress = true
        val executor = Executors.newCachedThreadPool()
        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
            if (displaySnapshotFile().exists() && !restoreDisplaySnapshot()) {
                displayRecoveryFailed = true
                System.err.println("Display settings recovery failed; refusing further AFR switches")
            }
            println("TvAfrTools PrivilegedServer listening on 127.0.0.1:$PORT")
            Thread({
                while (true) {
                    try {
                        val client = server.accept()
                        executor.execute { handleClient(client) }
                    } catch (e: Throwable) {
                        // Keep accepting connections even if one throws
                    }
                }
            }, "ServerListener").apply { isDaemon = true }.start()
        } catch (e: Throwable) {
            e.printStackTrace()
            System.exit(1)
        }

        // Background monitors protected with individual try/catch
        try { initDisplayClasses() } catch (t: Throwable) { t.printStackTrace() }
        try { ensureUniversalSystemSettings() } catch (t: Throwable) { t.printStackTrace() }
        currentTaskId = getForegroundTaskId()
        try { startTaskFpsMonitorThread() } catch (t: Throwable) { t.printStackTrace() }
        try { startTunneledVideoLogWatcherThread() } catch (t: Throwable) { t.printStackTrace() }
        try { startInputPtsMonitorThread() } catch (t: Throwable) { t.printStackTrace() }

        try {
            Looper.loop()
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        // Fallback keepalive loop
        while (true) {
            try {
                Thread.sleep(60000L)
            } catch (_: Throwable) {}
        }
    }

    /**
     * Ensures universal Android TV settings for AFR and persistent local ADB across Xiaomi,
     * Chromecast with Google TV, Homatics, Nvidia Shield, and Sony/TCL TVs.
     */
    private fun ensureUniversalSystemSettings() {
        Thread {
            try {
                runShellCommand("setprop persist.adb.tcp.port 5555")
            } catch (_: Throwable) {
            }
        }.start()
    }

    /**
     * Watches live logcat specifically for Tunneled Video Playback (Widevine L1 4K HDR in Disney+,
     * Prime Video, Netflix, Apple TV+) where frames bypass SurfaceFlinger and go straight to HWCVIDEO.
     */
    private fun startTunneledVideoLogWatcherThread() {
        Thread({
            while (true) {
                var proc: Process? = null
                try {
                    // Stream continuously; -t exits and repeatedly replays historical samples.
                    proc = ProcessBuilder(
                        "logcat",
                        "-v", "epoch",
                        "-s",
                        "VDA",
                        "VideoRenderQualityTracker",
                        "VideoSyncFRAC",
                        "Mediahal_VideoTunnelRenderer",
                        "C2VdecComponent",
                        "CCodec", "CCodecConfig", "CCodecBuffers", "ACodec",
                        "MediaCodecVideoRenderer"
                    ).redirectErrorStream(true).start()

                    val reader = BufferedReader(InputStreamReader(proc.inputStream))
                    var lastPtsUs = -1L
                    val ptsDeltasUs = ArrayDeque<Long>(8)

                    var lastVdaOutCount = -1L
                    var lastVdaTimeMs = 0L
                    var lastVdaStream = ""

                    val regexQualityTracker = Regex("""Rendered frame is earlier than the next expected frame \((\d+),\s*(\d+)\)""")
                    val regexVdaOuts = Regex("""^\s*(\d+\.\d+).*?OUTs=(\d+)/(\d+)""")

                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val l = line ?: continue
                        val nowMs = SystemClock.elapsedRealtime()
                        val epoch = l.trim().substringBefore(' ').toDoubleOrNull() ?: continue
                        if (CodecInputTrace.isNewVideoCodecForPid(l, foregroundAppPid) &&
                            kotlin.math.abs(System.currentTimeMillis() - (epoch * 1000).toLong()) < 2000L) {
                            inputVideoGeneration++
                            verifiedInputRate = null
                        }
                        val decoderFps = decoderFrameRates.accept(l, foregroundAppPid, System.currentTimeMillis() / 1000.0)
                        if (decoderFps != null) {
                            if (lastTunneledSource != "TUNNEL_DECODER" || abs(lastTunneledFps - decoderFps) > 0.005f)
                                println("FPS source=TUNNEL_DECODER fps=$decoderFps appPid=$foregroundAppPid")
                            lastTunneledFps = decoderFps
                            lastTunneledFpsTimestampMs = nowMs
                            lastTunneledSource = "TUNNEL_DECODER"
                            currentFps = decoderFps
                            currentFpsSource = lastTunneledSource
                            tunneledVideoActive = true
                        }
                        if (kotlin.math.abs(System.currentTimeMillis() - (epoch * 1000).toLong()) > 2000) continue
                        captureVideoSize(l)
                        // Keep a live, app-bound decoder rate ahead of PTS and coarse counters.
                        if (lastTunneledSource == "TUNNEL_DECODER" && nowMs - lastTunneledFpsTimestampMs < 8000L) continue

                        // 1. Check Amlogic / Xiaomi / Homatics VideoSyncFRAC 3:2 pulldown detector
                        if (l.contains("3:2 pattern detected")) {
                            if (lastTunneledSource.startsWith("TUNNEL_PTS") && nowMs - lastTunneledFpsTimestampMs < 8000L) continue
                            lastTunneledFps = 24f
                            lastTunneledFpsTimestampMs = nowMs
                            tunneledVideoActive = true
                            currentFps = 24f
                            currentFpsSource = "TUNNEL_3_2_PULLDOWN_ESTIMATED"
                            lastTunneledSource = currentFpsSource
                            continue
                        }

                        // 2. Check Universal ExoPlayer / Media3 VideoRenderQualityTracker PTS delta (Disney+, Prime, etc.)
                        val mQt = regexQualityTracker.find(l)
                        if (mQt != null) {
                            val ptsUs = mQt.groupValues[1].toLongOrNull() ?: -1L
                            if (ptsUs > 0L) {
                                if (lastPtsUs > 0L) {
                                    val deltaUs = ptsUs - lastPtsUs
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
                                                lastTunneledSource = currentFpsSource
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
                                val stream = Regex("""\[([^]]+)\].*ServiceDeviceTask""").find(l)?.groupValues?.get(1).orEmpty()
                                if (stream != lastVdaStream || outCount < lastVdaOutCount) {
                                    lastVdaOutCount = -1L
                                    lastVdaTimeMs = 0L
                                    lastVdaStream = stream
                                }
                                if (outCount > 0L) {
                                    if (lastVdaOutCount > 0L && outCount > lastVdaOutCount && logTimeMs > lastVdaTimeMs) {
                                        val dtMs = logTimeMs - lastVdaTimeMs
                                        val dFrames = outCount - lastVdaOutCount
                                        if (dtMs in 4000L..10000L && dFrames in 40L..1200L) {
                                            val vdaFps = (dFrames * 1000f) / dtMs.toFloat()
                                            val snapped = FrameRates.estimateFamily(vdaFps)
                                            if (snapped > 0f && !(lastTunneledSource.startsWith("TUNNEL_PTS") && nowMs - lastTunneledFpsTimestampMs < 8000L)) {
                                                lastTunneledFps = snapped
                                                lastTunneledFpsTimestampMs = nowMs
                                                tunneledVideoActive = true
                                                currentFps = snapped
                                                currentFpsSource = "TUNNEL_VDA_ESTIMATED"
                                                lastTunneledSource = currentFpsSource
                                            }
                                        }
                                    }
                                    if (lastVdaOutCount < 0L || logTimeMs - lastVdaTimeMs >= 4000L) {
                                        lastVdaOutCount = outCount
                                        lastVdaTimeMs = logTimeMs
                                    }
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

    private fun snapTunneledFps(rawFps: Float): Float =
        if (FrameRates.estimateFamily(rawFps) > 0f) FrameRates.snapMeasured(rawFps) else 0f

    private fun startTaskFpsMonitorThread() {
        Thread({
            val wmBinder = getSystemServiceBinder("window")
            val wmStubClass = try {
                Class.forName("android.view.IWindowManager\$Stub")
            } catch (_: Throwable) {
                null
            }
            val asInterface = wmStubClass?.getMethod("asInterface", IBinder::class.java)
            val wm = try { asInterface?.invoke(null, wmBinder) } catch (_: Throwable) { null }
            val wmClass = wm?.javaClass

            val registerMethod: Method? = try {
                val callbackIntf = Class.forName("android.window.ITaskFpsCallback")
                wmClass?.getMethod("registerTaskFpsCallback", Int::class.javaPrimitiveType, callbackIntf)
            } catch (_: Throwable) {
                null
            }
            val unregisterMethod: Method? = try {
                val callbackIntf = Class.forName("android.window.ITaskFpsCallback")
                wmClass?.getMethod("unregisterTaskFpsCallback", callbackIntf)
            } catch (_: Throwable) {
                null
            }

            while (true) {
                try {
                    val nowMs = SystemClock.elapsedRealtime()
                    var tunnelRecent = (nowMs - lastTunneledFpsTimestampMs) < 8000L && lastTunneledFps > 0f

                    val topTaskId = getForegroundTaskId()
                    if (topTaskId > 0 && topTaskId != currentTaskId) {
                        currentFps = 0f
                        currentFpsSource = "IDLE"
                        lastTunneledFps = 0f
                        lastTunneledFpsTimestampMs = 0L
                        lastTunneledSource = "IDLE"
                        tunneledVideoActive = false
                        tunnelRecent = false
                    }
                    if (topTaskId > 0 && topTaskId != currentTaskId && registerMethod != null && wm != null) {
                        registeredCallback?.let { oldCb ->
                            try {
                                unregisterMethod?.invoke(wm, oldCb)
                            } catch (_: Throwable) {
                            }
                        }
                        val newCb = createTaskFpsCallbackStub { fps ->
                            if (fps > 1.0f) {
                                val ageMs = SystemClock.elapsedRealtime() - lastTunneledFpsTimestampMs
                                if (ageMs < 8000L && lastTunneledFps > 1f) {
                                    currentFps = lastTunneledFps
                                    currentFpsSource = lastTunneledSource
                                } else {
                                    currentFps = fps
                                    currentFpsSource = "SURFACE_FLINGER"
                                }
                            }
                        }
                        if (newCb != null) {
                            registerMethod.invoke(wm, topTaskId, newCb)
                            registeredCallback = newCb
                        }
                    }

                    if (topTaskId > 0) currentTaskId = topTaskId
                    if (tunnelRecent) {
                        currentFps = lastTunneledFps
                        currentFpsSource = lastTunneledSource
                    } else {
                        val fallbackFps = readUniversalFallbackFps()
                        if (fallbackFps > 1.0f) {
                            currentFps = fallbackFps
                        } else if (currentFpsSource != "SURFACE_FLINGER") {
                            currentFps = 0f
                            currentFpsSource = "IDLE"
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
                    if (code == FIRST_CALL_TRANSACTION) {
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

    private var foregroundPackageForPid = ""
    private var foregroundPidCheckedAt = 0L

    private fun updateForegroundPid(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        if (pkg != foregroundPackageForPid || now - foregroundPidCheckedAt >= 2000L) {
            foregroundPackageForPid = pkg
            foregroundPidCheckedAt = now
            foregroundAppPid = if (pkg.matches(Regex("[A-Za-z0-9_.]+")))
                runShellCommand("pidof $pkg").trim().split(' ').firstOrNull()?.toIntOrNull() ?: -1 else -1
        }
    }

    private fun getForegroundTaskId(): Int {
        try {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val atm = atmClass.getMethod("getService").invoke(null)
            val tasks = atm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType).invoke(atm, 1) as? List<*>
            val taskInfo = tasks?.firstOrNull()
            if (taskInfo != null) {
                val activity = taskInfo.javaClass.getField("topActivity").get(taskInfo) as? android.content.ComponentName
                if (activity != null) {
                    updateForegroundPid(activity.packageName)
                    return taskInfo.javaClass.getField("taskId").getInt(taskInfo)
                }
            }
        } catch (_: Throwable) {
            // Hidden getTasks overloads vary by Android version; do not disable decoder attribution.
        }
        val activities = runShellCommand("dumpsys activity activities")
        val top = activities.lineSequence().firstOrNull {
            it.contains("topResumedActivity=") || it.contains("mResumedActivity:")
        }.orEmpty()
        val pkg = Regex("""\s([A-Za-z0-9_.]+)/""").find(top)?.groupValues?.get(1)
        if (pkg != null) {
            updateForegroundPid(pkg)
            return Regex("""\bt(\d+)\b""").find(top)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
        foregroundAppPid = -1
        foregroundPackageForPid = ""
        return -1
    }

    /**
     * Reads the REAL hardware physical HDMI output negotiated by the transmitter
     * with the TV sink (Amlogic, DRM/KMS).
     * e.g. "2160p23hz (YUV422 10bit HDR10)" or "1080p50hz".
     */
    fun readRealHardwareHdmiMode(): String {
        val paths = arrayOf(
            "/sys/class/amhdmitx/amhdmitx0/disp_mode",
            "/sys/class/display/mode",
            "/sys/class/drm/card0-HDMI-A-1/mode"
        )
        for (p in paths) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val mode = f.readText().trim()
                    if (mode.isNotEmpty() && mode != "null") {
                        val cfgFile = File("/sys/class/amhdmitx/amhdmitx0/config")
                        if (cfgFile.exists() && cfgFile.canRead()) {
                            val cfg = cfgFile.readText()
                            val colorM = Regex("""color_format:\s*(\w+)""").find(cfg)?.groupValues?.getOrNull(1)
                            val depthM = Regex("""color_depth:\s*(\w+)""").find(cfg)?.groupValues?.getOrNull(1)
                            val eotfM = Regex("""eotf:\s*(\w+)""").find(cfg)?.groupValues?.getOrNull(1)
                            val extra = listOfNotNull(colorM, depthM, eotfM).joinToString(" ")
                            if (extra.isNotEmpty()) {
                                return "$mode ($extra)"
                            }
                        }
                        return mode
                    }
                }
            } catch (_: Throwable) {}
        }
        return "N/D"
    }

    /**
     * Universal Fallback FPS reader for:
     * - Xiaomi Mi Box / TV Stick (Android 9-14)
     * - Amlogic / MediaTek / Realtek / Nvidia Shield sysfs & system properties
     * - MediaMetrics codec frameRate
     */
    private data class VideoSizeEvidence(val pkg: String, val pid: Int, val size: String, val tid: Int)
    @Volatile private var sizeEvidence: VideoSizeEvidence? = null
    @Volatile private var sizePackage = ""
    @Volatile private var sizePid = -1
    private var videoSizeReadAt = 0L
    private var cachedVideoSize = "UNKNOWN"
    private var videoSurfaceIdentity = ""
    private data class VendorSizeLink(val pkg: String, val appPid: Int, val serverPid: Int,
        val thread: Int, val stream: String)
    @Volatile private var vendorSizeLink: VendorSizeLink? = null
    private var clientSizeAt = 0L

    private fun captureLinkedVendorSize(line: String, pid: Int): Boolean {
        val fields = line.trim().split(Regex("\\s+"), limit = 6)
        if (fields.getOrNull(4)?.removeSuffix(":") != "C2VdecComponent") return false
        val tid = fields.getOrNull(2)?.toIntOrNull() ?: return true
        val stream = Regex("""\[([^]]+##\d+)\]""").find(line)?.groupValues?.get(1) ?: return true
        val crop = Regex("""crop_rect=\(\d+,\s*\d+\)\s*(\d+)x(\d+)""").find(line)
        val link = vendorSizeLink
        val evidence = sizeEvidence
        if (link == null && crop != null && evidence != null &&
            evidence.pkg == sizePackage && evidence.pid == sizePid &&
            SystemClock.elapsedRealtime() - clientSizeAt in 0L..2000L &&
            "${crop.groupValues[1]}x${crop.groupValues[2]}" == evidence.size) {
            // Bind only at the initial matching output format, not on a later arbitrary HAL line.
            vendorSizeLink = VendorSizeLink(sizePackage, sizePid, pid, tid, stream)
        }
        val bound = vendorSizeLink ?: return true
        if (bound.pkg != sizePackage || bound.appPid != sizePid ||
            bound.serverPid != pid || bound.thread != tid || bound.stream != stream) return true
        if (line.contains("[stop]") || line.contains("[release]") || line.contains("destroy", true)) {
            vendorSizeLink = null
            sizeEvidence = null
            cachedVideoSize = "UNKNOWN"
        } else if (crop != null && evidence != null) {
            val w = crop.groupValues[1].toInt()
            val h = crop.groupValues[2].toInt()
            if (w in 64..8192 && h in 64..8192)
                sizeEvidence = evidence.copy(size = "${w}x${h}")
        }
        return true
    }

    private data class SizeBatch(val epoch: String, val values: MutableMap<String, Int> = mutableMapOf())
    private val sizeBatches = mutableMapOf<String, SizeBatch>()

    private fun captureVideoSize(line: String) {
        val pid = line.trim().split(Regex("\\s+"), limit = 4).getOrNull(1)?.toIntOrNull() ?: return
        // Vendor service logs cannot be attributed to an app merely because it is foreground.
        if (sizePackage.isBlank()) return
        if (pid != sizePid) {
            captureLinkedVendorSize(line, pid)
            return
        }
        val fields = line.trim().split(Regex("\\s+"), limit = 6)
        val tid = fields.getOrNull(2)?.toIntOrNull() ?: return
        val tag = fields.getOrNull(4).orEmpty().removeSuffix(":")
        if (tag !in setOf("CCodec", "CCodecConfig", "CCodecBuffers", "ACodec", "MediaCodecVideoRenderer")) return
        val previous = sizeEvidence
        if (line.contains("allocate(") && Regex("""(?i)(?:avc|hevc|av1|vp9|mpeg|dolby|video)""").containsMatchIn(line)) {
            vendorSizeLink = null
            sizeEvidence = null
            cachedVideoSize = "UNKNOWN"
        }
        val lifecycle = Regex("""(?i)(?:\ballocate\(|\bconfigure\(|\brelease\(|\bshutdown\(|state.*(?:RELEASED|UNINITIALIZED))""")
        if (previous?.tid == tid && lifecycle.containsMatchIn(line)) {
            sizeEvidence = null
            vendorSizeLink = null
            cachedVideoSize = "UNKNOWN"
        }
        val crop = Regex("""crop(?:_rect)?[ :=]*(?:\(0,\s*0\)\s*)(\d+)x(\d+)""").find(line)
        val rect = Regex("""crop\(\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)""").find(line)
        val pair = Regex("""(?:visible width|width\s*[=:])\s*(\d+).*?(?:height\s*[=:]?)\s*(\d+)""").find(line)
        val batchKey = "$pid:$tid:$tag"
        val epoch = fields[0]
        val batch = sizeBatches.getOrPut(batchKey) { SizeBatch(epoch) }.let {
            if (it.epoch != epoch) SizeBatch(epoch).also { fresh -> sizeBatches[batchKey] = fresh } else it
        }
        // Only combine fields from one log message timestamp, process, thread and tag.
        val field = Regex("""(?:raw\.(crop|size)\.(width|height)|int32_t (width|height))\s*=\s*(\d+)""").find(line)
        if (field != null) {
            val family = field.groupValues[1].ifBlank { "output" }
            val axis = field.groupValues[2].ifBlank { field.groupValues[3] }
            batch.values["$family.$axis"] = field.groupValues[4].toInt()
        }
        val batched = listOf("crop", "size", "output").firstNotNullOfOrNull { family ->
            val w = batch.values["$family.width"]
            val h = batch.values["$family.height"]
            if (w != null && h != null) w to h else null
        }
        if (sizeBatches.size > 32) sizeBatches.keys.firstOrNull { it != batchKey }?.let { sizeBatches.remove(it) }
        val dimensions = when {
            crop != null -> crop.groupValues[1].toInt() to crop.groupValues[2].toInt()
            rect != null -> (rect.groupValues[3].toInt() - rect.groupValues[1].toInt() + 1) to
                (rect.groupValues[4].toInt() - rect.groupValues[2].toInt() + 1)
            pair != null && !line.contains("max", true) && !line.contains("coded_size") ->
                pair.groupValues[1].toInt() to pair.groupValues[2].toInt()
            else -> batched
        }
        if (dimensions != null && dimensions.first in 64..8192 && dimensions.second in 64..8192) {
            sizeEvidence = VideoSizeEvidence(sizePackage, pid, "${dimensions.first}x${dimensions.second}", tid)
            clientSizeAt = SystemClock.elapsedRealtime()
        }

    }

    @Synchronized
    private fun readVideoBufferSize(): String {
        val now = SystemClock.elapsedRealtime()
        val activities = runShellCommand("dumpsys activity activities")
        val pkg = Regex("""(?:topResumedActivity|mResumedActivity)=.*?\s([A-Za-z0-9_.]+)/""")
            .find(activities)?.groupValues?.get(1) ?: return "UNKNOWN"
        val pid = runShellCommand("pidof $pkg").trim().split(' ').firstOrNull()?.toIntOrNull() ?: -1
        if (pkg != sizePackage || pid != sizePid) {
            vendorSizeLink = null
            sizeEvidence = null
            videoSizeReadAt = 0L
            sizePackage = pkg
            sizePid = pid
        }
        if (now - videoSizeReadAt < 1000L) {
            sizeEvidence?.let { if (it.pkg == pkg && it.pid == pid) return it.size }
            return cachedVideoSize
        }
        videoSizeReadAt = now
        cachedVideoSize = "UNKNOWN"
        val blocks = runShellCommand("dumpsys SurfaceFlinger").split("+ Layer").filter {
            val header = it.lineSequence().firstOrNull().orEmpty()
            header.contains("SurfaceView") && header.contains(pkg)
        }
        val identity = blocks.map { it.lineSequence().firstOrNull().orEmpty() }.sorted().joinToString("|")
        if (videoSurfaceIdentity.isNotEmpty() && identity != videoSurfaceIdentity) {
            sizeEvidence = null
            vendorSizeLink = null
        }
        videoSurfaceIdentity = identity
        if (blocks.isEmpty()) sizeEvidence = null
        sizeEvidence?.let { if (it.pkg == pkg && it.pid == pid) return it.size }
        // A sideband video may have a separate UI/subtitle buffer: never treat that as video.
        if (blocks.any { Regex("""(?i)(?:composition[^\n]*SIDEBAND|sideband(?:Stream| stream)?\s*[=:]\s*(?!0x0\b|null)[0-9a-fx]+)""").containsMatchIn(it) }) return "UNKNOWN"
        val candidates = blocks.mapNotNull { block ->
            val buffer = Regex("""activeBuffer=\[\s*(\d+)\s*x\s*(\d+)""").find(block) ?: return@mapNotNull null
            val width = buffer.groupValues[1].toInt()
            val height = buffer.groupValues[2].toInt()
            if (width < 64 || height < 64) return@mapNotNull null
            val crop = Regex("""(?:geomContentCrop|crop)=\[\s*(\d+)[, ]+\s*(\d+)[, ]+\s*(\d+)[, ]+\s*(\d+)\s*\]""").find(block) ?: return@mapNotNull null
            val w = crop.groupValues[3].toInt() - crop.groupValues[1].toInt()
            val h = crop.groupValues[4].toInt() - crop.groupValues[2].toInt()
            if (w in 64..width && h in 64..height) w to h else null
        }.distinct()
        // Ambiguous/missing crop is unknown, not an assumed standard resolution.
        if (candidates.size == 1) cachedVideoSize = "${candidates[0].first}x${candidates[0].second}"
        return cachedVideoSize
    }

    private fun readSurfaceVideoFps(): Float {
        val layers = runShellCommand("dumpsys SurfaceFlinger --list").lineSequence()
            .filter { it.contains("SurfaceView") && it.contains("(BLAST)") }.toList()
        for (layer in layers) {
            val quoted = "'" + layer.replace("'", "'\"'\"'") + "'"
            val timestamps = runShellCommand("dumpsys SurfaceFlinger --latency $quoted")
                .lineSequence().drop(1).mapNotNull {
                    it.trim().split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
                }.filter { it > 0 && it < Long.MAX_VALUE }.toList()
            if (timestamps.size < 10) continue
            if (kotlin.math.abs(System.nanoTime() - timestamps.last()) > 2_000_000_000L) continue
            val duration = timestamps.last() - timestamps.first()
            if (duration <= 0) continue
            val fps = (timestamps.size - 1) * 1_000_000_000f / duration
            if (fps in 10f..120f) {
                currentFpsSource = "SURFACE_VIDEO"
                return fps
            }
        }
        return 0f
    }

    private fun readUniversalFallbackFps(): Float {
        // 1. Check recent Tunneled Video detection first
        val ageMs = SystemClock.elapsedRealtime() - lastTunneledFpsTimestampMs
        if (ageMs < 8000L && lastTunneledFps > 1.0f) {
            currentFpsSource = lastTunneledSource
            return lastTunneledFps
        }

        val surfaceFps = readSurfaceVideoFps()
        if (surfaceFps > 1f) return surfaceFps

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

        // 3. Check readable sysfs nodes (parses direct numbers and key-value formats)
        val sysPaths = arrayOf(
            "/sys/class/video/frame_rate",
            "/sys/class/vdec/vdec_status",
            "/proc/amstream/vdec_status"
        )
        for (path in sysPaths) {
            try {
                val f = File(path)
                if (f.exists() && f.canRead()) {
                    val text = f.readText().trim()
                    // Check direct float/integer (e.g. 23976, 24000, 24, 96000)
                    val rawNum = text.toFloatOrNull()
                    if (rawNum != null && rawNum > 1.0f) {
                        val v = when {
                            rawNum > 10000f -> rawNum / 1000f
                            rawNum == 96000f -> 24.0f
                            else -> rawNum
                        }
                        currentFpsSource = "SYSFS:$path"
                        return v
                    }
                    val m1 = Regex("""(?:frame_rate|fps)\s*[:=]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)
                    if (m1 != null) {
                        val raw = m1.groupValues[1].toFloatOrNull() ?: 0f
                        if (raw > 1.0f) {
                            val v = if (raw > 10000f) raw / 1000f else raw
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
     */
    private fun probeActiveVideoFpsNow(): Pair<Float, String> {
        currentVerifiedInputRate()?.let { return it.fps to "VIDEO_INPUT_PTS" }
        val nowMs = SystemClock.elapsedRealtime()
        if ((nowMs - lastTunneledFpsTimestampMs) < 8000L && lastTunneledFps > 1.0f) {
            return lastTunneledFps to lastTunneledSource
        }

        // Check recent Codec2 VDA / VideoRenderQualityTracker / VideoSyncFRAC logs directly
        try {
            val logOut = runShellCommand("logcat -d -v epoch -t 80 -s VDA VideoRenderQualityTracker VideoSyncFRAC")

            // Prefer media PTS over wall-clock counter estimates.
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
                        lastTunneledSource = currentFpsSource
                        return snapped to currentFpsSource
                    }
                }
            }

            // Fall back to VDA OUTs delta (estimated rate family).
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
                    val snapped = FrameRates.estimateFamily(vdaFps)
                    if (snapped > 0f) {
                        lastTunneledFps = snapped
                        lastTunneledFpsTimestampMs = SystemClock.elapsedRealtime()
                        currentFps = snapped
                        currentFpsSource = "TUNNEL_VDA_PROBE_ESTIMATED"
                        lastTunneledSource = currentFpsSource
                        return snapped to currentFpsSource
                    }
                }
            }

            // 3. Check 3:2 pulldown pattern
            if (logOut.contains("3:2 pattern detected")) {
                lastTunneledFps = 24f
                lastTunneledFpsTimestampMs = SystemClock.elapsedRealtime()
                currentFps = 24f
                currentFpsSource = "TUNNEL_3_2_PROBE_ESTIMATED"
                lastTunneledSource = currentFpsSource
                return 24f to currentFpsSource
            }
        } catch (_: Throwable) {
        }

        // Check Android 9-14 dumpsys media.metrics for codec frameRate
        try {
            val mmOut = runShellCommand("dumpsys media.metrics")
            val regexCodecFps = Regex("""(?:frameRate|frame-rate|operating-rate)\s*=\s*(\d+(?:\.\d+)?)""")
            val matches = regexCodecFps.findAll(mmOut).toList()
            if (matches.isNotEmpty()) {
                val fps = matches.last().groupValues[1].toFloatOrNull() ?: 0f
                if (fps > 1.0f) {
                    return fps to "MEDIA_METRICS"
                }
            }
        } catch (_: Throwable) {
        }

        // Check universal fallback sysfs/sysprops
        val fallback = readUniversalFallbackFps()
        if (fallback > 1.0f) {
            return fallback to currentFpsSource
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
    // This journal is written before touching system settings, and survives a
    // process restart or APK replacement. The alpha uses an independent file.
    private fun displaySnapshotFile(): File = File(
        "/data/local/tmp/" + if (BuildConfig.DEBUG) "tvafrtools_alpha_display.properties" else "tvafrtools_display.properties"
    )
    private var displayRecoveryFailed = false
    private var displaySnapshotActive = false

    private fun runChecked(vararg command: String): Boolean = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroy()
            false
        } else process.exitValue() == 0
    } catch (_: Throwable) { false }

    private fun captureDisplaySnapshot(): Boolean {
        return try {
        val preferredOutput = runShellCommand("cmd display get-user-preferred-display-mode 0").trim()
        val preferred = Regex("""(\d+)\s+(\d+)\s+(\d+(?:\.\d+)?)""").find(preferredOutput)?.value
        if (preferred == null && !preferredOutput.contains("null", ignoreCase = true)) return false
        val min = runShellCommand("settings get system min_refresh_rate").trim()
        val peak = runShellCommand("settings get system peak_refresh_rate").trim()
        val match = runShellCommand("settings get secure match_content_frame_rate").trim()
        val numberOrNull = Regex("""(?:null|\d+(?:\.\d+)?)""")
        if (!numberOrNull.matches(min) || !numberOrNull.matches(peak) || !numberOrNull.matches(match)) return false
        val snapshot = java.util.Properties().apply {
            setProperty("min", min)
            setProperty("peak", peak)
            setProperty("match", match)
            setProperty("preferred", preferred.orEmpty())
        }
        val target = displaySnapshotFile()
        val temp = File(target.path + ".tmp")
        java.io.FileOutputStream(temp).use { stream -> snapshot.store(stream, "AFR display state"); stream.fd.sync() }
        if (!temp.renameTo(target)) { temp.delete(); return false }
        true
        } catch (_: Throwable) { false }
    }

    @Synchronized
    private fun restoreDisplaySnapshot(): Boolean {
        val file = displaySnapshotFile()
        if (!file.exists()) return true
        return try {
            val snapshot = java.util.Properties().apply { file.inputStream().use { load(it) } }
            val numberOrNull = Regex("""(?:null|\d+(?:\.\d+)?)""")
            val min = snapshot.getProperty("min") ?: return false
            val peak = snapshot.getProperty("peak") ?: return false
            val match = snapshot.getProperty("match") ?: return false
            val preferred = snapshot.getProperty("preferred") ?: return false
            if (!numberOrNull.matches(min) || !numberOrNull.matches(peak) || !numberOrNull.matches(match) ||
                (preferred.isNotEmpty() && !Regex("""\d+\s+\d+\s+\d+(?:\.\d+)?""").matches(preferred))) return false
            fun restoreSetting(namespace: String, key: String, value: String): Boolean =
                if (value == "null") runChecked("settings", "delete", namespace, key)
                else runChecked("settings", "put", namespace, key, value)
            val minOk = restoreSetting("system", "min_refresh_rate", min)
            val peakOk = restoreSetting("system", "peak_refresh_rate", peak)
            val matchOk = restoreSetting("secure", "match_content_frame_rate", match)
            val modeOk = if (preferred.isEmpty()) runChecked("cmd", "display", "clear-user-preferred-display-mode", "0")
                else runChecked("cmd", "display", "set-user-preferred-display-mode", *preferred.split(Regex("""\s+""")).toTypedArray(), "0")
            if (minOk && peakOk && matchOk && modeOk && file.delete()) {
                displaySnapshotActive = false
                displayRecoveryFailed = false
                true
            } else false
        } catch (_: Throwable) { false }
    }

    @Synchronized
    private fun resetRefreshLimits(): Boolean {
        val restored = restoreDisplaySnapshot()
        val minCleared = runChecked("settings", "delete", "system", "min_refresh_rate")
        val peakCleared = runChecked("settings", "delete", "system", "peak_refresh_rate")
        return restored && minCleared && peakCleared
    }

    @Synchronized
    private fun setSystemPreferredDisplayMode(modeId: Int, width: Int, height: Int, refreshRate: Float): Boolean {
        if (modeId <= 0 || width <= 0 || height <= 0 || refreshRate <= 1.0f) return restoreDisplaySnapshot()
        if (displayRecoveryFailed) return false
        if (!displaySnapshotActive) {
            if (displaySnapshotFile().exists() && !restoreDisplaySnapshot()) return false
            if (!captureDisplaySnapshot()) return false
            displaySnapshotActive = true
        }
        val hz = String.format(Locale.US, "%.6f", refreshRate)
        val matchOk = runChecked("settings", "put", "secure", "match_content_frame_rate", "2")
        val minOk = runChecked("settings", "put", "system", "min_refresh_rate", hz)
        val peakOk = runChecked("settings", "put", "system", "peak_refresh_rate", hz)
        val modeOk = runChecked("cmd", "display", "set-user-preferred-display-mode", width.toString(), height.toString(), hz, "0")
        if (matchOk && minOk && peakOk && modeOk) return true
        if (!restoreDisplaySnapshot()) displayRecoveryFailed = true
        return false
    }

    /**
     * Universal SoC temperature reader across Amlogic, MediaTek, Qualcomm, Nvidia Tegra, Realtek.
     */
    private fun readUniversalSocTempCelsius(): Float {
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
                    if (pkg != "dev.custom.tvafrtools" && pkg != "dev.custom.tvafrtools.alpha" &&
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
            if (pkg != excludePkg && pkg != "dev.custom.tvafrtools" && pkg != "dev.custom.tvafrtools.alpha") {
                if (forceStopPackage(pkg)) {
                    killed++
                }
            }
        }
        return killed
    }

    private fun cycleHdrConversionMode(): String {
        return try {
            val cur = runShellCommand("settings get global hdr_conversion_mode").trim()
            if (cur == "1" || cur.isEmpty()) {
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
                    writer.println("OK PONG VERSION=${BuildConfig.VERSION_CODE} FPS=${String.format(Locale.US, "%.3f", currentFps)} SRC=$currentFpsSource")
                }
                "REDETECT_FPS" -> {
                    val id = synchronized(freshDetection) {
                        val next = freshDetection.reset()
                        verifiedInputRate = null
                        currentFps = 0f
                        currentFpsSource = "DETECTING"
                        lastTunneledFps = 0f
                        lastTunneledFpsTimestampMs = 0L
                        lastFpsRequestAtMs = SystemClock.elapsedRealtime()
                        next
                    }
                    synchronized(ptsWake) { ptsWake.notifyAll() }
                    writer.println("OK REQUEST=$id")
                }
                "GET_FPS" -> {
                    val rate = readLiveRate()
                    writer.println("OK ${String.format(Locale.US, "%.3f", rate.fps)} ${rate.source} VERIFIED=${if (rate.verified) 1 else 0}")
                }
                "PROBE_FPS" -> {
                    val (fps, src) = probeActiveVideoFpsNow()
                    writer.println("OK ${String.format(Locale.US, "%.3f", fps)} $src")
                }
                "GET_SYSINFO" -> {
                    val (rate, resetState) = synchronized(freshDetection) {
                        readLiveRate() to "REQUEST=${freshDetection.requestId} COMPLETED=${freshDetection.completedId}"
                    }
                    val tempC = readUniversalSocTempCelsius()
                    val hdmi = readRealHardwareHdmiMode()
                    writer.println("OK $resetState FPS=${String.format(Locale.US, "%.3f", rate.fps)} SRC=${rate.source} VERIFIED=${if (rate.verified) 1 else 0} SAMPLES=${rate.samples} SPAN_US=${rate.spanUs} TEMP=${String.format(Locale.US, "%.1f", tempC)} HDMI=$hdmi")
                }
                "GET_VIDEO_SIZE" -> writer.println("OK ${readVideoBufferSize()}")
                "GET_HDMI_REAL" -> {
                    val hdmi = readRealHardwareHdmiMode()
                    writer.println("OK $hdmi")
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
                "RESET_REFRESH_LIMITS" -> {
                    writer.println(if (resetRefreshLimits()) "OK RESET_REFRESH_LIMITS" else "ERR RESET_REFRESH_LIMITS")
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
