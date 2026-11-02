package dev.custom.tvafrtools.afr

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import dev.custom.tvafrtools.R
import dev.custom.tvafrtools.config.AppPrefs
import dev.custom.tvafrtools.server.ServerClient
import java.util.Locale
import kotlin.math.abs

/**
 * Clean-room AFR Engine for Android TV (Android 9–14, Universal SoC & Widevine L1 Tunnel Edition).
 *
 * Implements:
 * 1. Verified decoder input presentation timestamps:
 *    - PrivilegedServer captures bounded native Codec2 trace sessions.
 *    - Automatic AFR requires a unique supported cadence fit over sufficient media time.
 *    - Declared codec fps and compositor/counter estimates never authorize automatic switching.
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
    private val onDisplayNotice: (String, Long) -> Unit,
    private val onRequestManualSelector: (() -> Unit)? = null
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val displayCommands = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile var automaticAppEligible = false
    @Volatile var foregroundPackage = ""
    @Volatile private var nativeVideoSize: Pair<Int, Int>? = null
    @Volatile private var nativeVideoPackage = ""

    fun isNativeVideoMode(mode: Display.Mode): Boolean {
        val size = nativeVideoSize ?: return false
        return nativeVideoPackage == foregroundPackage && mode.physicalWidth == size.first && mode.physicalHeight == size.second
    }

    fun getManualDisplayModes(): List<Display.Mode> =
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.supportedModes?.sortedWith(
            compareByDescending<Display.Mode> { it.physicalWidth * it.physicalHeight }.thenBy { it.refreshRate }
        ).orEmpty()

    private fun refreshNativeVideoSize() {
        val pkg = foregroundPackage
        val values = ServerClient.getVideoBufferSize().split('x').mapNotNull { it.toIntOrNull() }
        nativeVideoSize = if (pkg == foregroundPackage && values.size == 2 && values.all { it > 0 }) values[0] to values[1] else null
        nativeVideoPackage = pkg
    }
    @Volatile private var destroyed = false
    @Volatile private var automaticSuspended = false
    private var afrOverlayView: View? = null
    private var afrOverlayParams: WindowManager.LayoutParams? = null
    private var isAfrActive: Boolean = false
    private var activeTargetModeId: Int = -1
    private var pendingDisplayNotice = false
    private var displayNoticeTargetId: Int? = null
    private var displayNoticeRestoring = false
    private val displayNoticeComplete = Runnable {
        if (!destroyed && pendingDisplayNotice) {
            pendingDisplayNotice = false
            getCurrentDisplayMode()?.let { mode ->
                val rate = java.text.NumberFormat.getNumberInstance().apply {
                    maximumFractionDigits = 3
                    isGroupingUsed = false
                }.format(mode.refreshRate.toDouble())
                val message = if (displayNoticeRestoring) R.string.display_restored_mode else R.string.display_current_mode
                onDisplayNotice(context.getString(message, mode.physicalHeight, rate), 10000L)
            }
        }
    }

    private fun startDisplayNotice(restoring: Boolean, targetModeId: Int? = null) {
        mainHandler.removeCallbacks(displayNoticeComplete)
        pendingDisplayNotice = true
        displayNoticeTargetId = targetModeId
        displayNoticeRestoring = restoring
        onDisplayNotice(context.getString(if (restoring) R.string.display_restoring else R.string.display_switching), 20000L)
        mainHandler.postDelayed(displayNoticeComplete, 15000L)
    }

    private var waitingForDisplayChangePlay: Boolean = false
    private var pausedForegroundPackage: String = ""
    private val resumePlayback = Runnable {
        if (!destroyed && waitingForDisplayChangePlay) {
            waitingForDisplayChangePlay = false
            if (pausedForegroundPackage.isEmpty() || foregroundPackage == pausedForegroundPackage) {
                Log.i("AfrController", "Resuming playback after display switch")
                dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            } else {
                Log.i("AfrController", "Not resuming: foreground app changed")
            }
        }
    }

    private fun beginPauseHandshake() {
        mainHandler.removeCallbacks(resumePlayback)
        pausedForegroundPackage = foregroundPackage
        waitingForDisplayChangePlay = true
        dispatchMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        // Some HDMI stacks omit a matching display callback. Never leave playback
        // paused indefinitely; the configured delay still applies after this limit.
        mainHandler.postDelayed(resumePlayback, 8000L + prefs.resumeDelayMs.toLong())
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val mode = getCurrentDisplayMode()
            if (pendingDisplayNotice && mode != null &&
                (displayNoticeTargetId == null || mode.modeId == displayNoticeTargetId)) {
                mainHandler.removeCallbacks(displayNoticeComplete)
                mainHandler.postDelayed(displayNoticeComplete, 1500L)
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
            var fpsStableSince = 0L
            var lastAutomaticChange = 0L
            var observedSize: Pair<Int, Int>? = null
            var resolutionChangedAt = 0L
            while (!destroyed) {
                try {
                    refreshNativeVideoSize()
                    if (prefs.afrEnabled && automaticAppEligible && foregroundPackage in prefs.automaticAfrApps && !automaticSuspended) {
                        val appPackage = foregroundPackage
                        if (sampledPackage != appPackage) {
                            sampledPackage = appPackage
                            fpsStableSince = SystemClock.elapsedRealtime()
                            candidate = 0f
                            observedSize = null
                            resolutionChangedAt = fpsStableSince
                        }
                        val now = SystemClock.elapsedRealtime()
                        val currentSize = nativeVideoSize
                        if (currentSize != observedSize) {
                            observedSize = currentSize
                            resolutionChangedAt = now
                        }
                        val info = ServerClient.getSysInfo()
                        // Presentation timestamps are constrained by the current HDMI mode.
                        // They must never drive automatic mode changes.
                        val fps = if (info.fpsVerified) info.fps else 0f
                        val target = if (fps > 1f) normalizeTargetRefreshRate(fps) else 0f
                        if (target <= 1f || abs(target - candidate) >= 0.005f) fpsStableSince = now
                        candidate = target
                        // No fixed startup delay: restart the five-second quiet window
                        // when either the target refresh rate or detected dimensions change.
                        val resolutionStable = !prefs.matchNativeResolution || now - resolutionChangedAt >= 5000L
                        if (target > 1f && now - fpsStableSince >= 5000L && resolutionStable &&
                            (lastAutomaticChange == 0L || now - lastAutomaticChange > 15000L)) {
                            lastAutomaticChange = SystemClock.elapsedRealtime()
                            fpsStableSince = now
                            mainHandler.post {
                            if (!destroyed && foregroundPackage == appPackage &&
                                (!prefs.matchNativeResolution || nativeVideoSize == currentSize) && prefs.afrEnabled && automaticAppEligible && foregroundPackage in prefs.automaticAfrApps && !automaticSuspended) {
                                val mode = getCurrentDisplayMode()
                                val desired = resolveRefreshMode(target)
                                if (mode != null && desired != null && mode.modeId != desired.modeId && !waitingForDisplayChangePlay) {
                                    Log.i("AfrController", "Automatic AFR app=$appPackage fps=$fps source=${info.fpsSource} target=$target selected=${desired.refreshRate} mode=${desired.modeId}")
                                    applyRefreshRate(target, automatic = true)
                                }
                            }
                            }
                        }
                    } else {
                        fpsStableSince = 0L
                        candidate = 0f
                        sampledPackage = ""
                        observedSize = null
                    }
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
    private val freshDetectionRunning = java.util.concurrent.atomic.AtomicBoolean(false)

    fun triggerOneClickAfr() {
        if (!freshDetectionRunning.compareAndSet(false, true)) return
        val requestedPackage = foregroundPackage
        automaticSuspended = true
        onBannerMessage(context.getString(R.string.fps_fresh_detection))
        Thread {
            refreshNativeVideoSize()
            val info = try { ServerClient.forceFreshDetection() } catch (_: Exception) { null }
            mainHandler.post {
                freshDetectionRunning.set(false)
                automaticSuspended = false
                if (destroyed || foregroundPackage != requestedPackage) return@post
                if (info != null && info.fpsVerified && info.fps > 1f) {
                    val target = normalizeTargetRefreshRate(info.fps)
                    Log.i("AfrController", "One-click AFR fps=${info.fps} source=${info.fpsSource} target=$target")
                    applyRefreshRate(target, pausePlaybackHandshake = prefs.pauseOnSwitch)
                } else {
                    if (!ServerClient.isServerRunning()) {
                        dev.custom.tvafrtools.adb.LocalAdbBootstrap.ensureServerRunningAsync(context)
                        onBannerMessage(context.getString(R.string.daemon_disconnected_manual))
                    } else onBannerMessage(context.getString(R.string.fps_not_verified_manual))
                    onRequestManualSelector?.invoke()
                }
            }
        }.start()
    }

    /** Manual selector suggestions use the same verified evidence as automatic AFR. */
    fun readCurrentVideoFps(): Float {
        val info = ServerClient.getSysInfo()
        return if (info.fpsVerified) info.fps else 0f
    }

    fun normalizeTargetRefreshRate(videoFps: Float): Float {
        var target = FrameRates.snapMeasured(videoFps)

        if (abs(target - 25.0f) < 0.2f && prefs.convert25To50Hz) {
            target = 50.0f
        }

        if (prefs.convert30To60Hz && (abs(target - 30f) < 0.05f || abs(target - 29.97f) < 0.05f)) {
            val doubled = target * 2f
            if (getSupportedModesSameResolution().any { abs(it.refreshRate - doubled) < 0.005f }) target = doubled
        }

        if (prefs.doubleRefreshRate24To48 && (abs(target - 24.0f) < 0.2f || abs(target - 23.976f) < 0.2f)) {
            val has48 = getSupportedModesSameResolution().any { abs(it.refreshRate - target * 2f) < 0.005f }
            if (has48) {
                target *= 2f
            }
        }

        return target
    }

    /**
     * Finds the best matching Display.Mode for the current resolution and desired refresh rate,
     * and applies it via the persistent 0x0 AfrOverlay window.
     */
    private fun resolveRefreshMode(targetHz: Float): Display.Mode? {
        val sameResolution = getSupportedModesSameResolution()
        val nativeModes = if (prefs.matchNativeResolution) getManualDisplayModes().filter { isNativeVideoMode(it) } else emptyList()
        val matchingNative = nativeModes.filter { abs(it.refreshRate - targetHz) < 0.5f }
        val modes = matchingNative.ifEmpty { sameResolution }
        if (modes.isEmpty()) return null

        var chosen = modes.minByOrNull { abs(it.refreshRate - targetHz) } ?: return null
        if (abs(chosen.refreshRate - targetHz) > 0.5f) {
            if (abs(targetHz - 25.0f) < 0.3f) {
                val mode50 = modes.minByOrNull { abs(it.refreshRate - 50.0f) }
                if (mode50 != null && abs(mode50.refreshRate - 50.0f) < 0.5f) {
                    chosen = mode50
                }
            }
        }

        return chosen
    }

    fun applyRefreshRate(targetHz: Float, pausePlaybackHandshake: Boolean = prefs.pauseOnSwitch, automatic: Boolean = false) {
        val currentMode = getCurrentDisplayMode() ?: return
        val chosen = resolveRefreshMode(targetHz) ?: return

        if (chosen.modeId == currentMode.modeId && abs(currentMode.refreshRate - chosen.refreshRate) < 0.02f) {
            if (automatic) return
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

        startDisplayNotice(restoring = false, targetModeId = chosen.modeId)
        if (pausePlaybackHandshake) {
            beginPauseHandshake()
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
        startDisplayNotice(restoring = false, targetModeId = modeId)
        if (pausePlaybackHandshake) {
            beginPauseHandshake()
            mainHandler.postDelayed({
                applyModeIdInternal(modeId)
            }, 180L)
        } else {
            applyModeIdInternal(modeId)
        }
    }

    private fun applyModeIdInternal(modeId: Int) {
        val target = getManualDisplayModes().find { it.modeId == modeId }
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
        if (wasActive && !silent) startDisplayNotice(restoring = true)
        if (silent) {
            pendingDisplayNotice = false
            mainHandler.removeCallbacks(displayNoticeComplete)
        }
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

    }

    fun rescueDisplay() {
        stopAfr(silent = true)
        displayCommands.execute {
            val success = ServerClient.resetRefreshLimits()
            mainHandler.post {
                onBannerMessage(context.getString(if (success) R.string.display_rescue_done else R.string.display_rescue_failed))
            }
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
