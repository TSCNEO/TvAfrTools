package dev.custom.tvafrtools.service

import dev.custom.tvafrtools.R

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dev.custom.tvafrtools.adb.LocalAdbBootstrap
import dev.custom.tvafrtools.afr.AfrController
import dev.custom.tvafrtools.config.AppPrefs
import dev.custom.tvafrtools.config.ButtonMapping
import dev.custom.tvafrtools.config.QuickAction
import dev.custom.tvafrtools.overlay.OverlayToolkit
import dev.custom.tvafrtools.server.ServerClient
import dev.custom.tvafrtools.web.WebRemoteServer

/**
 * Core Accessibility Service for TvAfrTools (Universal Android 9–14 Edition).
 *
 * Implements:
 * 1. Multi-Action Remote Key Remapping:
 *    - Single Click, Double Click, Long Press
 *    - Per-App Overrides (e.g., Disney+ / Prime Video / Netflix / Kodi / YouTube)
 * 2. Live Key Capture Mode for UI configuration
 * 3. Universal Auto-AFR & Auto-Restore Refresh Rate on App Exit
 * 4. Volume Hold-Repeat Fix (for Android 14 / Amlogic / Xiaomi / Bluetooth remotes)
 * 5. Virtual Mouse D-Pad navigation + Accessibility Tap dispatch
 * 6. Real Screen Off state machine (wakes up screen on any remote key press)
 * 7. Universal Recent Apps Switcher, RAM Cleaner, HDR/SDR Mode Cycle, Audio Output Cycle, Sleep Timer
 * 8. Embedded HTTP Web Remote (`http://<TV_IP>:8099`)
 */
class TvAfrAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TvAfrAccessibilityService? = null
            private set

        @Volatile
        var keyCaptureCallback: ((Int) -> Unit)? = null
    }

    lateinit var prefs: AppPrefs
        private set
    lateinit var afrController: AfrController
        private set
    lateinit var overlayToolkit: OverlayToolkit
        private set
    private var webRemoteServer: WebRemoteServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var audioManager: AudioManager

    // Foreground app tracking for Per-App actions, Previous App (Alt+Tab), and AFR auto-stop
    private var currentForegroundPkg: String = ""
    private var previousForegroundPkg: String = ""

    // Navigation key debounce state (prevents double-jump ghosting from Bluetooth/CEC echo)
    private var lastNavKeyCode: Int = -1
    private var lastNavDownTime: Long = 0L

    private fun isNavKey(code: Int): Boolean {
        return code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_BACK
    }

    // Real Screen Off state
    private var isRealScreenOff: Boolean = false

    // Sleep timer state
    private var sleepTimerMinutes: Int = 0
    private val sleepTimerRunnable = Runnable {
        sleepTimerMinutes = 0
        executeQuickAction(QuickAction.REAL_SCREEN_TOGGLE)
    }

    // Button press state machine
    private var activeKeyCode: Int = -1
    private var clickCount: Int = 0
    private var longPressTriggered: Boolean = false

    private val longPressRunnable = Runnable {
        val code = activeKeyCode
        if (code > 0 && !longPressTriggered) {
            longPressTriggered = true
            clickCount = 0
            val mapping = findMapping(code)
            if (mapping != null) {
                executeQuickAction(mapping.longPress, mapping.longPressPkg)
            }
        }
    }

    private val singleClickCommitRunnable = Runnable {
        val code = activeKeyCode
        if (code > 0 && clickCount == 1 && !longPressTriggered) {
            clickCount = 0
            activeKeyCode = -1
            val mapping = findMapping(code)
            if (mapping != null) {
                val (action, targetPkg) = resolveSingleClickAction(mapping)
                executeQuickAction(action, targetPkg)
            }
        }
    }

    // Volume hold-repeat fix runnable
    private var volumeHoldDirection: Int = 0
    private val volumeRepeatRunnable = object : Runnable {
        override fun run() {
            if (volumeHoldDirection != 0) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    volumeHoldDirection,
                    AudioManager.FLAG_SHOW_UI
                )
                mainHandler.postDelayed(this, 110L)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        prefs = AppPrefs(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        afrController = AfrController(
            context = this,
            prefs = prefs,
            onBannerMessage = { msg -> overlayToolkit.showBanner(msg) },
            onRequestManualSelector = { overlayToolkit.openAfrPanel() }
        )
        overlayToolkit = OverlayToolkit(this, afrController)
        webRemoteServer = WebRemoteServer(
            context = this,
            onExecuteAction = { act -> executeQuickAction(act) },
            onBanner = { msg -> overlayToolkit.showBanner(msg) }
        )
        if (prefs.webRemoteEnabled) {
            webRemoteServer?.start()
        }
        overlayToolkit.showBanner(getString(R.string.service_started_banner))
        // Pre-load recent apps history (like tvQuickActions base app)
        dev.custom.tvafrtools.recents.RecentAppsManager.loadInitialRecents(this)
        // Ensure local ADB daemon is running automatically
        LocalAdbBootstrap.ensureServerRunningAsync(this)
    }

    override fun onDestroy() {
        instance = null
        webRemoteServer?.stop()
        if (::afrController.isInitialized) {
            afrController.destroy()
        }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotBlank() &&
                pkg != "dev.custom.tvafrtools" &&
                pkg != "com.android.systemui" &&
                !pkg.startsWith("android")
            ) {
                dev.custom.tvafrtools.recents.RecentAppsManager.onAppForegrounded(this, pkg)
                if (pkg != currentForegroundPkg) {
                    val oldPkg = currentForegroundPkg
                    if (oldPkg.isNotBlank() && !isLauncherPackage(oldPkg)) {
                        previousForegroundPkg = oldPkg
                    }
                    currentForegroundPkg = pkg

                    // If user navigated back to launcher (Projectivy, Google TV, ATV, etc.)
                    // or switched away from the streaming app, immediately restore 60Hz and clear system modes!
                    if (prefs.autoStopAfrOnExit && afrController.isAfrCurrentlyActive()) {
                        if (isLauncherPackage(pkg) || (!oldPkg.isBlank() && !isLauncherPackage(oldPkg) && pkg != oldPkg)) {
                            afrController.stopAfr(silent = false)
                        }
                    }
                }
            }
        }
    }

    private fun isLauncherPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        val lower = pkg.lowercase()
        if (lower.contains("launcher") ||
            lower.contains("tvhome") ||
            lower.contains("leanbacklauncher") ||
            lower.contains("google.android.tungsten") ||
            lower == "io.freemytv" ||
            lower.contains("projectivy") ||
            lower.contains("atvlauncher") ||
            lower.contains("flauncher")
        ) return true

        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolveInfo = packageManager.resolveActivity(intent, 0)
            val homePkg = resolveInfo?.activityInfo?.packageName
            pkg == homePkg
        } catch (_: Throwable) {
            false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val action = event.action

        // 1. If UI Key Capture dialog is waiting for a button press, capture it immediately
        val captureCb = keyCaptureCallback
        if (captureCb != null) {
            if (action == KeyEvent.ACTION_DOWN &&
                keyCode != KeyEvent.KEYCODE_DPAD_CENTER &&
                keyCode != KeyEvent.KEYCODE_ENTER &&
                keyCode != KeyEvent.KEYCODE_BACK
            ) {
                keyCaptureCallback = null
                mainHandler.post { captureCb(keyCode) }
                return true
            }
            return super.onKeyEvent(event)
        }

        // 2. Navigation hardware debounce filter (eliminates double-clicks from Bluetooth/CEC echo)
        if (isNavKey(keyCode)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (action == KeyEvent.ACTION_DOWN) {
                // If a duplicate DOWN or premature repeat arrives within 220ms, drop it
                if ((event.repeatCount in 1..2 && (now - lastNavDownTime) < 250L) ||
                    (keyCode == lastNavKeyCode && (now - lastNavDownTime) < 95L)
                ) {
                    return true // Consume ghost repeat!
                }
                lastNavKeyCode = keyCode
                lastNavDownTime = now
            }
        }

        // 3. If Real Screen Off is active, wake the screen on any key press
        if (isRealScreenOff && action == KeyEvent.ACTION_DOWN) {
            isRealScreenOff = false
            Thread {
                ServerClient.setRealScreenOff(false)
                overlayToolkit.showBanner(getString(R.string.screen_turned_on))
            }.start()
            return true
        }

        // 3. If Virtual Mouse Mode is active, intercept D-Pad to move pointer & click
        if (overlayToolkit.isMouseModeEnabled()) {
            val step = if (event.repeatCount > 2) 34 else 18
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (action == KeyEvent.ACTION_DOWN) overlayToolkit.moveMouseBy(0, -step)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (action == KeyEvent.ACTION_DOWN) overlayToolkit.moveMouseBy(0, step)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (action == KeyEvent.ACTION_DOWN) overlayToolkit.moveMouseBy(-step, 0)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (action == KeyEvent.ACTION_DOWN) overlayToolkit.moveMouseBy(step, 0)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (action == KeyEvent.ACTION_UP) {
                        val (cx, cy) = overlayToolkit.getCursorPosition()
                        dispatchAccessibilityTap(cx.toFloat(), cy.toFloat())
                    }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (action == KeyEvent.ACTION_UP) {
                        overlayToolkit.toggleMouseMode()
                    }
                    return true
                }
            }
        }

        // 4. Volume Hold-Repeat Fix for Android 14 / Bluetooth remotes
        if (prefs.volumeButtonsFix &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            val dir = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                AudioManager.ADJUST_RAISE
            } else {
                AudioManager.ADJUST_LOWER
            }
            if (action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                volumeHoldDirection = dir
                mainHandler.removeCallbacks(volumeRepeatRunnable)
                mainHandler.postDelayed(volumeRepeatRunnable, 350L)
            } else if (action == KeyEvent.ACTION_UP) {
                volumeHoldDirection = 0
                mainHandler.removeCallbacks(volumeRepeatRunnable)
            }
        }

        // 5. Check custom button mappings
        val mapping = findMapping(keyCode) ?: return super.onKeyEvent(event)

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) {
                if (activeKeyCode != keyCode) {
                    mainHandler.removeCallbacks(singleClickCommitRunnable)
                    mainHandler.removeCallbacks(longPressRunnable)
                    activeKeyCode = keyCode
                    clickCount = 0
                }
                longPressTriggered = false
                if (mapping.longPress != QuickAction.NONE) {
                    mainHandler.postDelayed(longPressRunnable, prefs.longPressTimeoutMs)
                }
            }
            return true
        }

        if (action == KeyEvent.ACTION_UP) {
            mainHandler.removeCallbacks(longPressRunnable)
            if (longPressTriggered) {
                longPressTriggered = false
                activeKeyCode = -1
                clickCount = 0
                return true
            }

            clickCount++
            if (mapping.doubleClick == QuickAction.NONE) {
                clickCount = 0
                activeKeyCode = -1
                val (act, pkg) = resolveSingleClickAction(mapping)
                executeQuickAction(act, pkg)
            } else if (clickCount == 1) {
                mainHandler.postDelayed(singleClickCommitRunnable, prefs.doubleClickTimeoutMs)
            } else if (clickCount >= 2) {
                mainHandler.removeCallbacks(singleClickCommitRunnable)
                clickCount = 0
                activeKeyCode = -1
                executeQuickAction(mapping.doubleClick, mapping.doubleClickPkg)
            }
            return true
        }

        return super.onKeyEvent(event)
    }

    private fun findMapping(keyCode: Int): ButtonMapping? =
        prefs.getMappings().firstOrNull { it.keyCode == keyCode }

    private fun resolveSingleClickAction(mapping: ButtonMapping): Pair<QuickAction, String?> {
        if (currentForegroundPkg.isNotBlank()) {
            val override = mapping.appOverrides[currentForegroundPkg]
            if (override != null && override != QuickAction.NONE) {
                return override to mapping.overridePkg
            }
        }
        return mapping.singleClick to mapping.singleClickPkg
    }

    fun launchPackage(pkg: String) {
        try {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val defaultHomePkg = packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
            if (pkg == defaultHomePkg || pkg == "io.freemytv") {
                performGlobalAction(GLOBAL_ACTION_HOME)
                return
            }

            val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                ?: packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                startActivity(intent)
            } else {
                overlayToolkit.showBanner("No se pudo abrir $pkg")
            }
        } catch (e: Throwable) {
            overlayToolkit.showBanner("Error al abrir: ${e.message}")
        }
    }

    fun executeQuickAction(action: QuickAction, targetPkg: String? = null) {
        when (action) {
            QuickAction.NONE -> {}
            QuickAction.GO_HOME -> {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            QuickAction.SYSTEM_RECENTS -> {
                performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            QuickAction.OPEN_NOTIFICATIONS -> {
                performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
            QuickAction.OPEN_APP -> {
                if (!targetPkg.isNullOrBlank()) {
                    launchPackage(targetPkg)
                } else {
                    overlayToolkit.showBanner(getString(R.string.banner_no_assigned_app))
                }
            }
            QuickAction.ONE_CLICK_AFR -> {
                afrController.triggerOneClickAfr()
            }
            QuickAction.AFR_PANEL -> {
                overlayToolkit.toggleAfrPanel()
            }
            QuickAction.STOP_AFR -> {
                afrController.stopAfr()
            }
            QuickAction.HUD_TOGGLE -> {
                overlayToolkit.toggleHud()
            }
            QuickAction.REAL_SCREEN_TOGGLE -> {
                Thread {
                    val targetOff = !isRealScreenOff
                    val ok = ServerClient.setRealScreenOff(targetOff)
                    if (ok) {
                        isRealScreenOff = targetOff
                        if (targetOff) {
                            overlayToolkit.showBanner(getString(R.string.screen_turned_off))
                        } else {
                            overlayToolkit.showBanner(getString(R.string.screen_turned_on))
                        }
                    } else {
                        overlayToolkit.showBanner(getString(R.string.reconnecting_daemon))
                        LocalAdbBootstrap.ensureServerRunningAsync(this)
                    }
                }.start()
            }
            QuickAction.RECENT_APPS_SWITCHER -> {
                overlayToolkit.toggleRecentsPanel(currentForegroundPkg)
            }
            QuickAction.CLEAN_BACKGROUND_RAM -> {
                overlayToolkit.performRamClean(currentForegroundPkg)
            }
            QuickAction.HDR_MODE_CYCLE -> {
                Thread {
                    val mode = ServerClient.cycleHdrMode()
                    overlayToolkit.showBanner(getString(R.string.hdr_mode_banner, mode))
                }.start()
            }
            QuickAction.AUDIO_PASSTHROUGH_CYCLE -> {
                Thread {
                    val mode = ServerClient.cycleAudioPassthrough()
                    overlayToolkit.showBanner(getString(R.string.audio_output_banner, mode))
                }.start()
            }
            QuickAction.FORCE_STOP_FOREGROUND -> {
                val pkg = currentForegroundPkg
                if (pkg.isBlank() || isLauncherPackage(pkg)) {
                    overlayToolkit.showBanner(getString(R.string.no_foreground_app))
                } else {
                    Thread {
                        val ok = ServerClient.forceStopPackage(pkg)
                        if (ok) {
                            overlayToolkit.showBanner(getString(R.string.app_closed_force, pkg))
                        } else {
                            performGlobalAction(GLOBAL_ACTION_HOME)
                            overlayToolkit.showBanner(getString(R.string.sent_to_home))
                            LocalAdbBootstrap.ensureServerRunningAsync(this)
                        }
                    }.start()
                }
            }
            QuickAction.RESTART_FOREGROUND -> {
                val pkg = currentForegroundPkg
                if (pkg.isBlank() || isLauncherPackage(pkg)) {
                    overlayToolkit.showBanner(getString(R.string.no_foreground_app))
                } else {
                    Thread {
                        ServerClient.forceStopPackage(pkg)
                        Thread.sleep(450L)
                        mainHandler.post {
                            val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(pkg)
                                ?: packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launchIntent)
                                overlayToolkit.showBanner(getString(R.string.app_restarted, pkg))
                            }
                        }
                    }.start()
                }
            }
            QuickAction.PREVIOUS_APP -> {
                val prev = previousForegroundPkg
                if (prev.isBlank()) {
                    overlayToolkit.showBanner(getString(R.string.no_previous_app))
                } else {
                    val launchIntent = packageManager.getLeanbackLaunchIntentForPackage(prev)
                        ?: packageManager.getLaunchIntentForPackage(prev)
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        overlayToolkit.showBanner(getString(R.string.switching_to, prev))
                    }
                }
            }
            QuickAction.MOUSE_TOGGLE -> {
                overlayToolkit.toggleMouseMode()
            }
            QuickAction.NIGHT_DIMMER_TOGGLE -> {
                overlayToolkit.cycleNightDimmer()
            }
            QuickAction.SCREENSAVER_START -> {
                Thread {
                    val ok = ServerClient.startScreensaver()
                    if (ok) {
                        overlayToolkit.showBanner(getString(R.string.screensaver_started))
                    }
                }.start()
            }
            QuickAction.SLEEP_TIMER_CYCLE -> {
                mainHandler.removeCallbacks(sleepTimerRunnable)
                sleepTimerMinutes = when (sleepTimerMinutes) {
                    0 -> 30
                    30 -> 60
                    60 -> 90
                    else -> 0
                }
                if (sleepTimerMinutes == 0) {
                    overlayToolkit.showBanner(getString(R.string.sleep_timer_off))
                } else {
                    mainHandler.postDelayed(sleepTimerRunnable, sleepTimerMinutes * 60_000L)
                    overlayToolkit.showBanner(getString(R.string.sleep_timer_min, sleepTimerMinutes))
                }
            }
        }
    }

    private fun dispatchAccessibilityTap(x: Float, y: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }
}
