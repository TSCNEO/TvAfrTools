package dev.custom.tvafrtools.overlay

import dev.custom.tvafrtools.R

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.custom.tvafrtools.afr.AfrController
import dev.custom.tvafrtools.server.ServerClient
import dev.custom.tvafrtools.adb.LocalAdbBootstrap

import java.util.Locale
import kotlin.math.abs

/**
 * Full TV Overlay Toolkit (Universal Android 9–14 Edition):
 * 1. Top-Right Banner Notification (non-intrusive TV OSD toast, alpha <= 0.8f compliant on Android 12+)
 * 2. Live Technical HUD Overlay (HDMI Mode & Hz, Real Video FPS + Source [Widevine L1 Tunnel / SurfaceFlinger], SoC Temp, RAM, Net Speed)
 * 3. Quick Frame Rate Selector Panel (23.976 / 24 / 25 / 29.97 / 30 / 50 / 59.94 / 60 Hz D-Pad modal)
 * 4. Universal Recent Apps Switcher + RAM Cleaner Panel (solves Google TV / Xiaomi / Homatics lack of Recents button)
 * 5. Night Mode Screen Dimmer (OLED/LED dark filter overlay)
 * 6. Virtual Mouse Cursor Overlay (controlled via D-Pad for sideloaded mobile apps)
 */
class OverlayToolkit(
    private val context: Context,
    private val afrController: AfrController
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // 1. Banner Toast
    private var bannerView: TextView? = null
    private val hideBannerRunnable = Runnable { removeBanner() }

    // 2. Live HUD
    private var hudView: TextView? = null
    private var isHudVisible = false
    private var lastRxBytes = 0L
    private var lastRxTimeMs = 0L

    // 3. Manual AFR Selector Panel
    private var panelContainer: FrameLayout? = null
    private var isPanelVisible = false

    // 4. Universal Recent Apps Switcher Panel
    private var recentsContainer: FrameLayout? = null
    private var isRecentsVisible = false

    // 5. Night Dimmer
    private var dimmerView: View? = null
    private var dimmerLevelIndex = 0 // 0 = off, 1 = 35%, 2 = 55%, 3 = 75%

    // 6. Virtual Mouse Cursor
    private var cursorView: View? = null
    private var cursorParams: WindowManager.LayoutParams? = null
    private var isMouseActive = false
    private var cursorX = 960
    private var cursorY = 540

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

    fun showBanner(message: String, durationMs: Long = 3200L) {
        mainHandler.post {
            mainHandler.removeCallbacks(hideBannerRunnable)
            if (bannerView == null) {
                val tv = TextView(context).apply {
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setPadding(dp(20), dp(12), dp(20), dp(12))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(12).toFloat()
                        setColor(Color.parseColor("#E6141923"))
                        setStroke(dp(1), Color.parseColor("#38BDF8"))
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(28)
                    y = dp(28)
                    title = "TvAfrBanner"
                    // Android 12+ requires alpha <= 0.80f on FLAG_NOT_TOUCHABLE windows
                    alpha = 0.80f
                }
                try {
                    windowManager.addView(tv, params)
                    bannerView = tv
                } catch (_: Exception) {
                }
            }
            bannerView?.text = message
            mainHandler.postDelayed(hideBannerRunnable, durationMs)
        }
    }

    private fun removeBanner() {
        bannerView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        bannerView = null
    }

    // =========================================================================
    // LIVE TECHNICAL HUD OVERLAY
    // =========================================================================

    private val hudUpdateRunnable = object : Runnable {
        override fun run() {
            if (!isHudVisible) return
            Thread {
                val mode = afrController.getCurrentDisplayMode()
                val sysInfo = ServerClient.getSysInfo()
                val videoFps = if (sysInfo.fps > 1.0f) sysInfo.fps else afrController.readCurrentVideoFps()
                val fpsSrc = when {
                    sysInfo.fpsSource.startsWith("TUNNEL") -> "Widevine L1 Tunnel"
                    sysInfo.fpsSource.startsWith("SURFACE") -> "SurfaceFlinger"
                    sysInfo.fpsSource.startsWith("SYS") -> "HW Decoder"
                    else -> sysInfo.fpsSource
                }
                val tempStr = if (sysInfo.socTempCelsius > 0f) {
                    String.format(Locale.US, "%.1f °C", sysInfo.socTempCelsius)
                } else {
                    "N/D"
                }

                val memInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                val usedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024)
                val totalMb = memInfo.totalMem / (1024 * 1024)

                val nowMs = SystemClock.elapsedRealtime()
                val rxNow = TrafficStats.getTotalRxBytes()
                val speedKbps = if (lastRxTimeMs > 0 && rxNow >= lastRxBytes && nowMs > lastRxTimeMs) {
                    ((rxNow - lastRxBytes) * 8.0 / (nowMs - lastRxTimeMs)).toLong() // kbps
                } else {
                    0L
                }
                lastRxBytes = rxNow
                lastRxTimeMs = nowMs

                val speedStr = if (speedKbps >= 1000) {
                    String.format(Locale.US, "%.1f Mbps", speedKbps / 1000.0)
                } else {
                    "$speedKbps kbps"
                }

                val text = buildString {
                    append("⚡ TvAfrTools HUD\n")
                    if (mode != null) {
                        append(
                            String.format(
                                Locale.US,
                                "HDMI:  %dx%d @ %.3f Hz (ID %d)\n",
                                mode.physicalWidth,
                                mode.physicalHeight,
                                mode.refreshRate,
                                mode.modeId
                            )
                        )
                    }
                    if (sysInfo.hdmiRealHardware.isNotBlank() && sysInfo.hdmiRealHardware != "N/D") {
                        append(context.getString(R.string.hud_signal, sysInfo.hdmiRealHardware)).append('\n')
                    }
                    if (videoFps > 1.0f) {
                        val size = dev.custom.tvafrtools.server.ServerClient.getVideoBufferSize()
                        val dimensions = size.split('x').mapNotNull { it.toIntOrNull() }
                        if (dimensions.size == 2 && dimensions.all { it > 0 }) {
                            append("${minOf(dimensions[0], dimensions[1])}p | ")
                        }
                        append(String.format(Locale.getDefault(), "%.3f fps", videoFps)).append('\n')
                    } else {
                        append(context.getString(R.string.hud_no_stream)).append('\n')
                    }
                    append("SoC:   $tempStr  |  RAM: $usedMb / $totalMb MB\n")
                    append("Red:   ↓ $speedStr")
                }

                mainHandler.post {
                    hudView?.text = text
                    if (isHudVisible) {
                        mainHandler.postDelayed(this, 1000L)
                    }
                }
            }.start()
        }
    }

    fun toggleHud() {
        mainHandler.post {
            if (isHudVisible) {
                isHudVisible = false
                mainHandler.removeCallbacks(hudUpdateRunnable)
                hudView?.let {
                    try {
                        windowManager.removeView(it)
                    } catch (_: Exception) {
                    }
                }
                hudView = null
                showBanner("HUD Técnico ocultado")
            } else {
                val tv = TextView(context).apply {
                    setTextColor(Color.parseColor("#E2E8F0"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(10).toFloat()
                        setColor(Color.parseColor("#D90F172A"))
                        setStroke(dp(1), Color.parseColor("#22C55E"))
                    }
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = dp(24)
                    y = dp(24)
                    title = "TvAfrHud"
                    alpha = 0.80f
                }
                try {
                    windowManager.addView(tv, params)
                    hudView = tv
                    isHudVisible = true
                    lastRxBytes = TrafficStats.getTotalRxBytes()
                    lastRxTimeMs = SystemClock.elapsedRealtime()
                    hudUpdateRunnable.run()
                } catch (_: Exception) {
                    showBanner("⚠ Concede permiso de superposición para el HUD")
                }
            }
        }
    }

    // =========================================================================
    // MANUAL FRAME RATE SELECTOR PANEL (D-PAD NAVIGABLE OVERLAY)
    // =========================================================================

    fun toggleAfrPanel() {
        mainHandler.post {
            if (isPanelVisible) {
                closeAfrPanel()
            } else {
                openAfrPanel()
            }
        }
    }

    fun closeAfrPanel() {
        isPanelVisible = false
        panelContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        panelContainer = null
    }

    fun openAfrPanel() {
        closeAfrPanel()
        closeRecentsPanel()
        val currentMode = afrController.getCurrentDisplayMode()
        val modes = afrController.getSupportedModesSameResolution()
        val liveFps = afrController.readCurrentVideoFps()

        val root = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                    closeAfrPanel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#88000000"))
            isFocusable = true
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#F2111216"))
                setStroke(dp(2), Color.parseColor("#F59E0B"))
            }
        }

        val headerTitle = TextView(context).apply {
            text = "🎬 Selector de Frecuencia HDMI (AFR)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        card.addView(headerTitle)

        val curStr = if (currentMode != null) {
            String.format(Locale.US, "%dx%d @ %.3f Hz", currentMode.physicalWidth, currentMode.physicalHeight, currentMode.refreshRate)
        } else "Desconocido"

        val subtitle = TextView(context).apply {
            val fpsStr = if (liveFps > 1f) String.format(Locale.US, "%.3f fps", liveFps) else "Buscando stream..."
            text = "Actual: $curStr   |   Vídeo: $fpsStr"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(4), 0, dp(12))
        }
        card.addView(subtitle)

        Thread {
            val (probed, src) = dev.custom.tvafrtools.server.ServerClient.probeActiveVideoFpsWithSource()
            val realHdmi = dev.custom.tvafrtools.server.ServerClient.getRealHardwareHdmi()
            mainHandler.post {
                if (isPanelVisible) {
                    val fpsPart = if (probed > 1.0f) {
                        "🎯 Vídeo: %.3f fps [%s]".format(Locale.US, probed, src)
                    } else if (liveFps > 1.0f) {
                        "🎯 Vídeo: %.3f fps".format(Locale.US, liveFps)
                    } else {
                        "Vídeo: Sin stream activo"
                    }
                    val hdmiPart = if (realHdmi.isNotBlank() && realHdmi != "N/D") {
                        "Señal: $realHdmi"
                    } else {
                        "HDMI: $curStr"
                    }
                    subtitle.text = "$hdmiPart   |   $fpsPart"
                }
            }
        }.start()

        val autoBtn = createPanelButton(context.getString(R.string.overlay_auto_afr_btn), isHighlighted = true) {
            closeAfrPanel()
            afrController.triggerOneClickAfr()
        }
        card.addView(autoBtn)

        // Opción Revertir / Restaurar Frecuencia nativa 60Hz inmediatamente debajo de Auto
        val revertBtn = createPanelButton(context.getString(R.string.overlay_revert_60hz_btn), isHighlighted = false) {
            closeAfrPanel()
            afrController.stopAfr()
        }
        card.addView(revertBtn)

        modes.forEach { m ->
            val isCurrent = currentMode?.modeId == m.modeId
            val isMatch = liveFps > 1.0f && (abs(m.refreshRate - liveFps) < 0.05f || (liveFps in 23.9f..24.1f && abs(m.refreshRate - 23.976f) < 0.05f))

            val label = buildString {
                append(String.format(Locale.US, "%dx%d  —  %.3f Hz", m.physicalWidth, m.physicalHeight, m.refreshRate))
                if (abs(m.refreshRate - 23.976f) < 0.05f) append("  ⭐ Cine/Series (Disney+/Prime/Netflix)")
                else if (abs(m.refreshRate - 24.0f) < 0.05f) append("  ⭐ Cine 24.000p Exacto")
                else if (abs(m.refreshRate - 50.0f) < 0.05f) append("  ⭐ TV/Deportes PAL (25/50p)")
                else if (abs(m.refreshRate - 59.94f) < 0.05f) append("  (NTSC 59.94p)")
                if (isMatch) append("   🎯 VÍDEO DETECTADO")
                if (isCurrent) append("   [ACTIVO]")
            }
            val btn = createPanelButton(label, isHighlighted = isCurrent || isMatch) {
                closeAfrPanel()
                afrController.applyModeId(m.modeId)
            }
            card.addView(btn)
        }



        val closeBtn = createPanelButton("✕ Cerrar Panel (Atrás)", isHighlighted = false) {
            closeAfrPanel()
        }
        card.addView(closeBtn)

        val scroll = ScrollView(context).apply {
            addView(card)
        }

        val cardParams = FrameLayout.LayoutParams(
            dp(540),
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        root.addView(scroll, cardParams)

        val wmParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.45f
            gravity = Gravity.CENTER
            title = "TvAfrSelectorPanel"
        }

        try {
            windowManager.addView(root, wmParams)
            panelContainer = root
            isPanelVisible = true
            autoBtn.requestFocus()
        } catch (_: Exception) {
            showBanner("⚠ Concede el permiso 'Mostrar sobre otras aplicaciones'")
        }
    }

    // =========================================================================
    // UNIVERSAL RECENT APPS SWITCHER & RAM CLEANER (XIAOMI / GOOGLE TV / HOMATICS)
    // =========================================================================

    fun toggleRecentsPanel(currentForegroundPkg: String) {
        mainHandler.post {
            if (isRecentsVisible) {
                closeRecentsPanel()
            } else {
                openRecentsPanel(currentForegroundPkg)
            }
        }
    }

    fun performRamClean(currentForegroundPkg: String) {
        Thread {
            val memBefore = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memBefore)

            val tracked = dev.custom.tvafrtools.recents.RecentAppsManager.getTrackedPackages()
            var killedCount = 0

            // 1. Android ActivityManager SDK process termination (works universally without ADB)
            for (pkg in tracked) {
                if (pkg != currentForegroundPkg && !dev.custom.tvafrtools.recents.RecentAppsManager.isExcluded(context, pkg)) {
                    try {
                        activityManager.killBackgroundProcesses(pkg)
                        killedCount++
                    } catch (_: Exception) {}
                }
            }

            // 2. PrivilegedServer termination (am force-stop) if daemon online
            if (ServerClient.isServerRunning()) {
                val serverKilled = ServerClient.cleanBackgroundRam(currentForegroundPkg)
                if (serverKilled > killedCount) {
                    killedCount = serverKilled
                }
            } else {
                LocalAdbBootstrap.ensureServerRunningAsync(context)
            }

            // 3. Clear tracked background apps from RecentAppsManager
            dev.custom.tvafrtools.recents.RecentAppsManager.onRamCleaned(currentForegroundPkg)

            // 4. Measure freed memory
            val memAfter = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memAfter)
            val freedMb = maxOf(0L, (memAfter.availMem - memBefore.availMem) / (1024 * 1024))

            val banner = if (killedCount > 0) {
                if (freedMb > 5) {
                    context.getString(R.string.banner_ram_freed, killedCount, freedMb)
                } else {
                    context.getString(R.string.banner_ram_freed_simple, killedCount)
                }
            } else {
                context.getString(R.string.banner_ram_clean)
            }
            showBanner(banner)
        }.start()
    }

    fun closeRecentsPanel() {
        isRecentsVisible = false
        recentsContainer?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }
        recentsContainer = null
    }

    private fun openRecentsPanel(currentForegroundPkg: String) {
        closeRecentsPanel()
        closeAfrPanel()

        Thread {
            val recentApps = dev.custom.tvafrtools.recents.RecentAppsManager.getRecentApps(context, currentForegroundPkg)
            val memInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)

            mainHandler.post {
                val root = object : FrameLayout(context) {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_BACK) {
                            closeRecentsPanel()
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                }.apply {
                    setBackgroundColor(Color.parseColor("#88000000"))
                    isFocusable = true
                }

                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(20), dp(24), dp(20))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(16).toFloat()
                        setColor(Color.parseColor("#F2111827"))
                        setStroke(dp(2), Color.parseColor("#22C55E"))
                    }
                }

                val headerTitle = TextView(context).apply {
                    text = context.getString(R.string.overlay_recents_title)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                }
                card.addView(headerTitle)

                val subtitle = TextView(context).apply {
                    text = context.getString(R.string.overlay_free_mem, availMb, totalMb) + "  •  OK  |  " + context.getString(R.string.overlay_recents_hint)
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setPadding(0, dp(4), 0, dp(12))
                }
                card.addView(subtitle)

                val cleanBtn = createPanelButton(context.getString(R.string.overlay_clean_ram_btn), isHighlighted = true) {
                    closeRecentsPanel()
                    performRamClean(currentForegroundPkg)
                }
                card.addView(cleanBtn)

                val pm = context.packageManager
                if (recentApps.isEmpty()) {
                    val emptyTv = TextView(context).apply {
                        text = context.getString(R.string.overlay_no_recents)
                        setTextColor(Color.parseColor("#CBD5E1"))
                        setPadding(dp(8), dp(10), dp(8), dp(10))
                    }
                    card.addView(emptyTv)
                } else {
                    recentApps.forEach { app ->
                        val rowText = buildString {
                            append(app.label)
                            if (app.isForeground) append("  " + context.getString(R.string.overlay_on_screen))
                        }
                        val btn = createPanelButton(
                            text = rowText,
                            isHighlighted = app.isForeground,
                            icon = app.icon,
                            onClick = {
                                closeRecentsPanel()
                                val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
                                    ?: pm.getLaunchIntentForPackage(app.packageName)
                                if (intent != null) {
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            },
                            onLongClick = {
                                closeRecentsPanel()
                                Thread {
                                    dev.custom.tvafrtools.recents.RecentAppsManager.removePackage(app.packageName)
                                    try {
                                        activityManager.killBackgroundProcesses(app.packageName)
                                    } catch (_: Exception) {}
                                    if (ServerClient.isServerRunning()) {
                                        ServerClient.forceStopPackage(app.packageName)
                                    }
                                    showBanner(context.getString(R.string.banner_app_force_stopped, app.label))
                                }.start()
                            }
                        )
                        card.addView(btn)
                    }
                }

                val closeBtn = createPanelButton("✕ Cerrar Multitarea (Atrás)", isHighlighted = false) {
                    closeRecentsPanel()
                }
                card.addView(closeBtn)

                val scroll = ScrollView(context).apply {
                    addView(card)
                }

                val cardParams = FrameLayout.LayoutParams(
                    dp(580),
                    WindowManager.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.CENTER
                }
                root.addView(scroll, cardParams)

                val wmParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    dimAmount = 0.45f
                    gravity = Gravity.CENTER
                    title = "TvAfrRecentsPanel"
                }

                try {
                    windowManager.addView(root, wmParams)
                    recentsContainer = root
                    isRecentsVisible = true
                    cleanBtn.requestFocus()
                } catch (_: Exception) {
                    showBanner("⚠ Concede permiso de superposición para abrir Multitarea")
                }
            }
        }.start()
    }

    private fun createPanelButton(
        text: String,
        isHighlighted: Boolean,
        icon: Drawable? = null,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit
    ): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isFocusable = true
            isFocusableInTouchMode = true
            gravity = Gravity.CENTER_VERTICAL
            if (icon != null) {
                val iconSize = dp(24)
                icon.setBounds(0, 0, iconSize, iconSize)
                setCompoundDrawables(icon, null, null, null)
                compoundDrawablePadding = dp(12)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            }
            layoutParams = lp

            fun updateBg(focused: Boolean) {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(10).toFloat()
                    if (focused) {
                        setColor(Color.parseColor("#F59E0B"))
                        setStroke(dp(2), Color.WHITE)
                    } else if (isHighlighted) {
                        setColor(Color.parseColor("#26211C"))
                        setStroke(dp(1), Color.parseColor("#D97706"))
                    } else {
                        setColor(Color.parseColor("#191B22"))
                        setStroke(dp(1), Color.parseColor("#282B35"))
                    }
                }
            }
            updateBg(false)
            setOnFocusChangeListener { _, hasFocus ->
                setTextColor(if (hasFocus) Color.parseColor("#0F1014") else Color.WHITE)
                updateBg(hasFocus)
            }
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                setOnLongClickListener {
                    onLongClick()
                    true
                }
            }
        }
    }

    // =========================================================================
    // NIGHT MODE DIMMER OVERLAY
    // =========================================================================

    fun cycleNightDimmer() {
        mainHandler.post {
            dimmerLevelIndex = (dimmerLevelIndex + 1) % 4
            if (dimmerLevelIndex == 0) {
                dimmerView?.let {
                    try {
                        windowManager.removeView(it)
                    } catch (_: Exception) {
                    }
                }
                dimmerView = null
                showBanner("☀ Atenuador Nocturno: Desactivado")
                return@post
            }

            val alpha = when (dimmerLevelIndex) {
                1 -> 0.35f
                2 -> 0.55f
                else -> 0.75f
            }
            val pct = (alpha * 100).toInt()

            if (dimmerView == null) {
                val v = View(context).apply {
                    setBackgroundColor(Color.BLACK)
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    this.alpha = alpha
                    title = "TvAfrNightDimmer"
                }
                try {
                    windowManager.addView(v, params)
                    dimmerView = v
                } catch (_: Exception) {
                }
            } else {
                val params = dimmerView!!.layoutParams as WindowManager.LayoutParams
                params.alpha = alpha
                try {
                    windowManager.updateViewLayout(dimmerView, params)
                } catch (_: Exception) {
                }
            }
            showBanner("🌙 Atenuador Nocturno: -$pct% brillo")
        }
    }

    // =========================================================================
    // VIRTUAL MOUSE CURSOR OVERLAY
    // =========================================================================

    fun isMouseModeEnabled(): Boolean = isMouseActive

    fun toggleMouseMode() {
        mainHandler.post {
            if (isMouseActive) {
                isMouseActive = false
                cursorView?.let {
                    try {
                        windowManager.removeView(it)
                    } catch (_: Exception) {
                    }
                }
                cursorView = null
                cursorParams = null
                showBanner("🖱 Ratón Virtual desactivado (D-Pad normal)")
            } else {
                val size = dp(22)
                val v = View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor("#E638BDF8"))
                        setStroke(dp(2), Color.WHITE)
                    }
                }
                val params = WindowManager.LayoutParams(
                    size,
                    size,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = cursorX
                    y = cursorY
                    title = "TvAfrMouseCursor"
                    alpha = 0.80f
                }
                try {
                    windowManager.addView(v, params)
                    cursorView = v
                    cursorParams = params
                    isMouseActive = true
                    showBanner("🖱 Ratón Virtual activado (Usa las flechas del mando)")
                } catch (_: Exception) {
                }
            }
        }
    }

    fun moveMouseBy(dx: Int, dy: Int): Pair<Int, Int> {
        val dm = context.resources.displayMetrics
        cursorX = (cursorX + dx).coerceIn(0, dm.widthPixels - dp(20))
        cursorY = (cursorY + dy).coerceIn(0, dm.heightPixels - dp(20))
        mainHandler.post {
            val v = cursorView ?: return@post
            val p = cursorParams ?: return@post
            p.x = cursorX
            p.y = cursorY
            try {
                windowManager.updateViewLayout(v, p)
            } catch (_: Exception) {
            }
        }
        return cursorX to cursorY
    }

    fun getCursorPosition(): Pair<Int, Int> = cursorX to cursorY

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
}
