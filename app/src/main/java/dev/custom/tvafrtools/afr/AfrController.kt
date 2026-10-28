package dev.custom.tvafrtools.afr

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import dev.custom.tvafrtools.R
import dev.custom.tvafrtools.config.AppPrefs
import dev.custom.tvafrtools.server.ServerClient
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Clean-room AFR Engine for Android TV (Android 9–14, Universal SoC & Widevine L1 Tunnel Edition).
 *
 * Implements:
 * 1. Tunneled Widevine L1 Video Detection (`PROBE_FPS`) + 5-sample circular buffer Mode Filter:
 *    - When Disney+, Prime Video, or Netflix plays 4K HDR via `HWCVIDEO` tunnel, SurfaceFlinger UI
 *      still runs at 60fps while the tunnel plays at 23.976fps.
 *    - `probeActiveVideoFpsWithSource()` detects `TUNNEL_PTS`, `TUNNEL_VDA`, or `TUNNEL_3_2_PULLDOWN`
 *      directly from the hardware video pipeline so 23.976fps is recognized immediately!
 * 2. Exact Fractional & Cinema Frame Rate Mapping:
 *    - 23.976 fps (24 * 1000 / 1001) vs 24.000 fps
 *    - 29.970 fps (30 * 1000 / 1001) vs 30.000 fps
 *    - 59.940 fps (60 * 1000 / 1001) vs 60.000 fps
 *    - 25.000 fps -> 50.000 Hz (or 25Hz fallback)
 *    - Optional 24 -> 48Hz / 60 -> 120Hz doubling
 * 3. Persistent 0x0 Invisible `TYPE_APPLICATION_OVERLAY` window (`AfrOverlay`)
 *    setting `WindowManager.LayoutParams.preferredDisplayModeId` matching the active physical resolution.
 * 4. `DisplayManager.DisplayListener` handshake for Disney+ / Prime Video:
 *    Dispatches `KEYCODE_MEDIA_PAUSE` before switching display mode, waits for `onDisplayChanged`,
 *    and dispatches `KEYCODE_MEDIA_PLAY` after HDMI re-sync so Widevine L1 / ExoPlayer never crashes.
 */
class AfrController(
    private val context: Context,
    private val prefs: AppPrefs,
    private val onBannerMessage: (String) -> Unit,
    private val onRequestManualSelector: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val displayCommands = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var automaticAppEligible = false
    @Volatile var foregroundPackage = ""
    @Volatile private var destroyed = false
    @Volatile private var automaticSuspended = false
    private var afrOverlayView: View? = null
    private var afrOverlayParams: WindowManager.LayoutParams? = null
    private var isAfrActive: Boolean = false
    private var activeTargetModeId: Int = -1
    private var waitingForDisplayChangePlay: Boolean = false
    private val resumePlayback = Runnable {
        if (!destroyed && waitingForDisplayChangePlay) {
            waitingForDisplayChangePlay = false
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    // 5-sample circular buffer for FPS stabilization
    private val fpsSamples = FloatArray(5)
    private var sampleIndex = 0
    private var sampleCount = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val mode = getCurrentDisplayMode()
            if (mode != null && isAfrActive) {
                onBannerMessage(
                    context.getString(
                        R.string.hdmi_synced,
                        mode.physicalWidth,
                        mode.physicalHeight,
                        mode.refreshRate
                    )
                )
            }
            if (waitingForDisplayChangePlay && mode?.modeId == activeTargetModeId) {
                mainHandler.removeCallbacks(resumePlayback)
                mainHandler.postDelayed(resumePlayback, prefs.resumeDelayMs.toLong())
            }
        }
    }

    init {
        displayManager.registerDisplayListener(displayListener, mainHandler)
        Thread {
            var candidate = 0f
            var sampledPackage = ""
            var stable = 0
            var lastAutomaticChange = 0L
            while (!destroyed) {
                try {
                    if (prefs.afrEnabled && automaticAppEligible && foregroundPackage in prefs.automaticAfrApps && !automaticSuspended) {
                        val appPackage = foregroundPackage
                        if (sampledPackage != appPackage) {
                            sampledPackage = appPackage
                            stable = 0
                            candidate = 0f
                        }
                        val info = ServerClient.getSysInfo()
                        // Presentation timestamps are constrained by the current HDMI mode.
                        // They must never drive automatic mode changes.
                        val fps = if (info.fpsSource == "SURFACE_VIDEO" || info.fpsSource == "SURFACE_FLINGER") 0f else info.fps
                        val target = if (fps > 1f) normalizeTargetRefreshRate(fps) else 0f
                        stable = if (target > 1f && abs(target - candidate) < 0.1f) stable + 1 else 0
                        candidate = target
                        if (stable >= 4 && SystemClock.elapsedRealtime() - lastAutomaticChange > 15000L) {
                            lastAutomaticChange = SystemClock.elapsedRealtime()
                            stable = 0
                            mainHandler.post {
                            if (!destroyed && foregroundPackage == appPackage && prefs.afrEnabled && automaticAppEligible && foregroundPackage in prefs.automaticAfrApps && !automaticSuspended) {
                                val mode = getCurrentDisplayMode()
                                if (mode != null && abs(mode.refreshRate - target) > 0.5f)
                                    applyRefreshRate(target)
                            }
                            }
                        }
                    } else { stable = 0; candidate = 0f }
                    Thread.sleep(1000)
                } catch (_: Exception) { Thread.sleep(1000) }
            }
        }.apply { isDaemon = true; name = "AutoAfr" }.start()
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        stopAfr(silent = true)
    }

    fun getCurrentDisplayMode(): Display.Mode? {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        return display.mode
    }

    fun getSupportedModesSameResolution(): List<Display.Mode> {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        val current = display.mode ?: return emptyList()
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .sortedBy { it.refreshRate }
    }

    /**
     * Triggers 1-Click Auto Frame Rate detection and switches HDMI refresh rate.
     */
    fun triggerOneClickAfr() {
        automaticSuspended = false
        Thread {
            // 1. First perform a deep probe (catches Tunneled Widevine L1 23.976fps in Disney+/Prime/Netflix!)
            val (probedFps, probedSource) = ServerClient.probeActiveVideoFpsWithSource()
            if (probedFps > 1.0f && probedSource.startsWith("TUNNEL_")) {
                val targetHz = normalizeTargetRefreshRate(probedFps)
                mainHandler.post {
                    onBannerMessage(
                        String.format(
                            Locale.US,
                            "🎬 Vídeo Detectado: %.3f fps (%s) → %.3f Hz",
                            probedFps,
                            formatSourceLabel(probedSource),
                            targetHz
                        )
                    )
                    applyRefreshRate(targetHz, pausePlaybackHandshake = prefs.pauseOnSwitch)
                }
                return@Thread
            }

            // 2. Otherwise collect 5 samples (or until stable) over 600ms
            resetSamples()
            var detected = probedFps
            var detectedSource = probedSource
            for (i in 0 until 5) {
                val sample = readCurrentVideoFps()
                if (sample > 1.0f) {
                    val stabilized = addSampleAndGetMode(sample)
                    if (stabilized > 1.0f) {
                        detected = stabilized
                    } else if (detected <= 1.0f) {
                        detected = sample
                    }
                }
                if (i < 4) Thread.sleep(130L)
            }

            mainHandler.post {
                if (detected <= 1.0f) {
                    val serverUp = ServerClient.isServerRunning()
                    if (!serverUp) {
                        dev.custom.tvafrtools.adb.LocalAdbBootstrap.ensureServerRunningAsync(context)
                        onBannerMessage(context.getString(R.string.daemon_disconnected_manual))
                    } else {
                        onBannerMessage(context.getString(R.string.no_active_stream_manual))
                    }
                    onRequestManualSelector?.invoke()
                } else {
                    val targetHz = normalizeTargetRefreshRate(detected)
                    onBannerMessage(
                        String.format(
                            Locale.US,
                            "🎬 Vídeo: %.3f fps (%s) → Ajustando a %.3f Hz",
                            detected,
                            formatSourceLabel(detectedSource),
                            targetHz
                        )
                    )
                    applyRefreshRate(targetHz, pausePlaybackHandshake = prefs.pauseOnSwitch)
                }
            }
        }.start()
    }

    private fun formatSourceLabel(src: String): String {
        return when {
            src.startsWith("TUNNEL_") -> "Widevine L1 Tunnel"
            src.startsWith("SURFACE_FLINGER") -> "SurfaceFlinger"
            src.startsWith("MEDIA_METRICS") -> "MediaCodec"
            src.startsWith("SYSPROP") || src.startsWith("SYSFS") -> "Hardware Decoder"
            else -> "Auto"
        }
    }

    /**
     * Reads the current video FPS from PrivilegedServer or fallback system properties.
     */
    fun readCurrentVideoFps(): Float {
        val serverFps = ServerClient.getLiveVideoFps()
        if (serverFps > 1.0f) return serverFps

        try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java, String::class.java)
            val prop = (get.invoke(null, "sys.nes.info.frame_rate", "") as? String)
                ?.replace("fps", "")
                ?.trim()
                .orEmpty()
            if (prop.isNotEmpty()) {
                val raw = prop.toFloatOrNull() ?: 0f
                if (raw > 1.0f) {
                    return when (raw.toInt()) {
                        100 -> 50f
                        48 -> 24f
                        120 -> 60f
                        else -> raw
                    }
                }
            }
        } catch (_: Throwable) {
        }

        val paths = listOf("/sys/class/vdec/vdec_status", "/sys/class/video/frame_rate")
        for (p in paths) {
            try {
                val f = File(p)
                if (f.exists() && f.canRead()) {
                    val content = f.readText()
                    val m = Regex("""frame_rate\s*[:=]\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(content)
                    if (m != null) {
                        val v = m.groupValues[1].toFloatOrNull() ?: 0f
                        if (v > 1.0f) return v
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return 0f
    }

    private fun resetSamples() {
        sampleIndex = 0
        sampleCount = 0
        for (i in fpsSamples.indices) fpsSamples[i] = 0f
    }

    private fun addSampleAndGetMode(fps: Float): Float {
        val snapped = snapRawFpsToStandard(fps)
        fpsSamples[sampleIndex] = snapped
        sampleIndex = (sampleIndex + 1) % fpsSamples.size
        if (sampleCount < fpsSamples.size) sampleCount++

        var bestVal = snapped
        var bestCount = 0
        for (i in 0 until sampleCount) {
            val candidate = fpsSamples[i]
            var count = 0
            for (j in 0 until sampleCount) {
                if (abs(fpsSamples[j] - candidate) < 0.08f) count++
            }
            if (count > bestCount) {
                bestCount = count
                bestVal = candidate
            }
        }
        return if (bestCount >= 2) bestVal else snapped
    }

    private fun snapRawFpsToStandard(fps: Float): Float {
        return when {
            abs(fps - 23.976f) < 0.12f -> 23.976f
            abs(fps - 24.0f) < 0.35f -> if (prefs.fractionalFrameRate) 23.976f else 24.0f
            abs(fps - 25.0f) < 0.45f -> 25.0f
            abs(fps - 29.97f) < 0.12f -> 29.97f
            abs(fps - 30.0f) < 0.35f -> if (prefs.fractionalFrameRate) 29.97f else 30.0f
            abs(fps - 50.0f) < 0.6f -> 50.0f
            abs(fps - 59.94f) < 0.15f -> 59.94f
            abs(fps - 60.0f) < 0.6f -> if (prefs.fractionalFrameRate) 59.94f else 60.0f
            else -> fps
        }
    }

    fun normalizeTargetRefreshRate(videoFps: Float): Float {
        var target = snapRawFpsToStandard(videoFps)

        if (abs(target - 25.0f) < 0.2f && prefs.convert25To50Hz) {
            target = 50.0f
        }

        if (prefs.convert30To60Hz && (abs(target - 30f) < 0.05f || abs(target - 29.97f) < 0.05f)) {
            val doubled = target * 2f
            if (getSupportedModesSameResolution().any { abs(it.refreshRate - doubled) < 0.05f }) target = doubled
        }

        if (prefs.doubleRefreshRate24To48 && (abs(target - 24.0f) < 0.2f || abs(target - 23.976f) < 0.2f)) {
            val has48 = getSupportedModesSameResolution().any { abs(it.refreshRate - 48.0f) < 0.5f }
            if (has48) {
                target *= 2f
            }
        }

        if (prefs.fractionalFrameRate) {
            when {
                abs(target - 24.0f) < 0.05f -> target = 24f * 1000f / 1001f // 23.976025
                abs(target - 30.0f) < 0.05f -> target = 30f * 1000f / 1001f // 29.97003
                abs(target - 60.0f) < 0.05f -> target = 60f * 1000f / 1001f // 59.94006
            }
        }
        return target
    }

    /**
     * Finds the best matching Display.Mode for the current resolution and desired refresh rate,
     * and applies it via the persistent 0x0 AfrOverlay window.
     */
    fun applyRefreshRate(targetHz: Float, pausePlaybackHandshake: Boolean = prefs.pauseOnSwitch) {
        val currentMode = getCurrentDisplayMode() ?: return
        val modes = getSupportedModesSameResolution()
        if (modes.isEmpty()) return

        var chosen = modes.minByOrNull { abs(it.refreshRate - targetHz) } ?: return
        if (abs(chosen.refreshRate - targetHz) > 0.5f) {
            if (abs(targetHz - 25.0f) < 0.3f) {
                val mode50 = modes.minByOrNull { abs(it.refreshRate - 50.0f) }
                if (mode50 != null && abs(mode50.refreshRate - 50.0f) < 0.5f) {
                    chosen = mode50
                }
            }
        }

        if (chosen.modeId == currentMode.modeId && abs(currentMode.refreshRate - chosen.refreshRate) < 0.02f) {
            onBannerMessage(
                context.getString(
                    R.string.display_already_at_mode,
                    currentMode.physicalWidth,
                    currentMode.physicalHeight,
                    currentMode.refreshRate
                )
            )
            return
        }

        if (pausePlaybackHandshake) {
            waitingForDisplayChangePlay = true
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            mainHandler.postDelayed({
                applyDisplayModeDirect(chosen)
            }, 180L)
        } else {
            waitingForDisplayChangePlay = false
            applyDisplayModeDirect(chosen)
        }
    }

    fun applyModeId(modeId: Int, pausePlaybackHandshake: Boolean = prefs.pauseOnSwitch) {
        automaticSuspended = true
        val currentMode = getCurrentDisplayMode()
        if (currentMode != null && currentMode.modeId == modeId) {
            onBannerMessage(
                context.getString(
                    R.string.mode_already_active,
                    currentMode.refreshRate
                )
            )
            return
        }
        if (pausePlaybackHandshake) {
            waitingForDisplayChangePlay = true
            dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            mainHandler.postDelayed({
                applyModeIdInternal(modeId)
            }, 180L)
        } else {
            applyModeIdInternal(modeId)
        }
    }

    private fun applyModeIdInternal(modeId: Int) {
        val target = getSupportedModesSameResolution().find { it.modeId == modeId }
        if (target != null) {
            applyDisplayModeDirect(target)
        } else {
            ensureAfrOverlayCreated()
            val v = afrOverlayView ?: return
            val p = afrOverlayParams ?: return
            p.preferredDisplayModeId = modeId
            activeTargetModeId = modeId
            isAfrActive = true
            try {
                windowManager.updateViewLayout(v, p)
            } catch (_: Exception) {}
        }
    }

    private fun applyDisplayModeDirect(targetMode: Display.Mode) {
        // Withdraw the previous window vote before submitting a different mode.
        afrOverlayView?.let { oldView ->
            try { windowManager.removeViewImmediate(oldView) } catch (_: Exception) {}
        }
        afrOverlayView = null
        afrOverlayParams = null
        ensureAfrOverlayCreated()
        val view = afrOverlayView
        val params = afrOverlayParams
        if (view != null && params != null) {
            params.preferredDisplayModeId = targetMode.modeId
            activeTargetModeId = targetMode.modeId
            isAfrActive = true
            try {
                windowManager.updateViewLayout(view, params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Dual-punch: Enforce system preferred mode globally via DisplayManager HAL
        // This guarantees mode switching even when DAZN, Netflix, or Disney+ has a private secure window!
        displayCommands.execute {
            ServerClient.setSystemDisplayMode(
                targetMode.modeId,
                targetMode.physicalWidth,
                targetMode.physicalHeight,
                targetMode.refreshRate
            )
        }
    }

    fun stopAfr(silent: Boolean = false) {
        automaticSuspended = false
        mainHandler.removeCallbacks(resumePlayback)
        waitingForDisplayChangePlay = false
        val wasActive = isAfrActive
        isAfrActive = false
        activeTargetModeId = -1
        afrOverlayView?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        afrOverlayView = null
        afrOverlayParams = null

        displayCommands.execute {
            ServerClient.clearSystemDisplayMode()
        }

        if (wasActive && !silent) {
            onBannerMessage(context.getString(R.string.afr_stopped_default))
        }
    }

    fun isAfrCurrentlyActive(): Boolean = isAfrActive

    private fun ensureAfrOverlayCreated() {
        if (afrOverlayView != null && afrOverlayParams != null) return

        val view = View(context)
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            1,
            1,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "AfrOverlay"
            alpha = 0.02f
        }

        try {
            windowManager.addView(view, params)
            afrOverlayView = view
            afrOverlayParams = params
        } catch (e: Exception) {
            e.printStackTrace()
            onBannerMessage(context.getString(R.string.overlay_perm_required))
        }
    }

    private fun dispatchMediaKey(keyCode: Int) {
        try {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

fun AfrController.getCurrentDisplaySummary(): String {
    val m = getCurrentDisplayMode() ?: return "Desconocido"
    return String.format(Locale.US, "%dx%d @ %.3f Hz (ID %d)", m.physicalWidth, m.physicalHeight, m.refreshRate, m.modeId)
}
