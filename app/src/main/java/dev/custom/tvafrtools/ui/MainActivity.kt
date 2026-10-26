package dev.custom.tvafrtools.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import dev.custom.tvafrtools.R
import dev.custom.tvafrtools.adb.LocalAdbBootstrap
import dev.custom.tvafrtools.afr.getCurrentDisplaySummary
import dev.custom.tvafrtools.config.AppPrefs
import dev.custom.tvafrtools.config.ButtonMapping
import dev.custom.tvafrtools.config.QuickAction
import dev.custom.tvafrtools.server.ServerClient
import dev.custom.tvafrtools.server.isDaemonOnlineSync
import dev.custom.tvafrtools.server.querySysInfoSync
import dev.custom.tvafrtools.service.TvAfrAccessibilityService
import dev.custom.tvafrtools.web.WebRemoteServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AppPrefs
    private var autoBootstrapAttempted = false

    // UI navigation state
    private enum class NavSection(val title: String, val subtitle: String, val icon: String) {
        AFR("Auto Frame Rate", "Conmutación precisa 24p/50p/60p para Disney+, Prime Video y Netflix", "🎬"),
        MAPPER("Mapeo de Mando", "Reasigna cualquier botón físico para 1 clic, doble clic o pulsación larga", "🎮"),
        ACTIONS("Acciones Rápidas", "Herramientas de pantalla apagada real, limpieza de RAM y selector Hz", "🚀"),
        WEBREMOTE("Mando Web Móvil", "Controla tu Android TV desde el navegador del smartphone o PC", "🌐"),
        SYSTEM("Estado & Demonio", "Monitor de temperatura, tasa de refresco nativa y demonio ADB interno", "⚡")
    }

    private var currentSection = NavSection.AFR
    private val navButtons = mutableMapOf<NavSection, TextView>()
    private lateinit var contentContainer: FrameLayout
    private lateinit var liveStatusPill: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        setContentView(buildMasterDetailUi())

        lifecycleScope.launch {
            while (isActive) {
                updateLiveStatusBadge()
                delay(1500L)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            val online = withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }
            if (!online && !autoBootstrapAttempted) {
                autoBootstrapAttempted = true
                triggerInternalAdbAutoStart(showToast = false)
            }
            updateLiveStatusBadge()
        }
    }

    private fun triggerInternalAdbAutoStart(showToast: Boolean) {
        lifecycleScope.launch {
            if (showToast) {
                Toast.makeText(
                    this@MainActivity,
                    "⚡ Conectando al ADB interno y arrancando demonio en segundo plano...",
                    Toast.LENGTH_SHORT
                ).show()
            }
            val result = withContext(Dispatchers.IO) {
                LocalAdbBootstrap.autoStartPrivilegedDaemon(this@MainActivity)
            }
            delay(600L)
            val onlineNow = withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }
            if (showToast) {
                Toast.makeText(
                    this@MainActivity,
                    if (onlineNow) "✓ Demonio ADB y permisos activados con éxito"
                    else "ℹ ${result.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateLiveStatusBadge()
            if (currentSection == NavSection.SYSTEM) {
                switchSection(NavSection.SYSTEM)
            }
        }
    }

    private fun buildMasterDetailUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#111216")) // Deep cinema graphite
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // ================= LEFT SIDEBAR (Leanback Rail) =================
        val sidebar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0C0D10")) // Darker rail background
            setPadding(28, 28, 20, 28)
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(270),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // App Branding Header
        val brandHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(10, 8, 10, 24)
        }
        val logoImg = ImageView(this).apply {
            setImageResource(R.drawable.ic_app_logo)
            layoutParams = LinearLayout.LayoutParams(dpToPx(42), dpToPx(42)).apply {
                rightMargin = dpToPx(14)
            }
        }
        val brandTextLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appTitle = TextView(this).apply {
            text = "TvAfrTools"
            setTextColor(Color.parseColor("#F59E0B")) // Warm cinema amber
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val appSubtitle = TextView(this).apply {
            text = "Home Cinema Suite"
            setTextColor(Color.parseColor("#717686"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        }
        brandTextLayout.addView(appTitle)
        brandTextLayout.addView(appSubtitle)
        brandHeader.addView(logoImg)
        brandHeader.addView(brandTextLayout)
        sidebar.addView(brandHeader)

        // Navigation Items
        for (sec in NavSection.values()) {
            val navItem = TextView(this).apply {
                text = "${sec.icon}   ${sec.title}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dpToPx(16), dpToPx(13), dpToPx(16), dpToPx(13))
                isFocusable = true
                isClickable = true
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.nav_item_bg_selector)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.drawable.nav_item_text_selector))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8)
                }

                setOnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        (v as TextView).typeface = Typeface.DEFAULT_BOLD
                        if (currentSection != sec) {
                            switchSection(sec)
                        }
                    } else {
                        (v as TextView).typeface = Typeface.DEFAULT
                    }
                }
                setOnClickListener {
                    switchSection(sec)
                }
            }
            navButtons[sec] = navItem
            sidebar.addView(navItem)
        }

        // Bottom Device Status in Sidebar
        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        sidebar.addView(spacer)

        val deviceModelText = TextView(this).apply {
            text = "${Build.MANUFACTURER.capitalize()} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            setTextColor(Color.parseColor("#5A5F70"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            setPadding(10, 0, 10, 6)
        }
        sidebar.addView(deviceModelText)
        root.addView(sidebar)

        // ================= RIGHT DETAIL PANE =================
        val rightPane = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(36), dpToPx(28), dpToPx(36), dpToPx(28))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        }

        // Top Status Bar (Display & Live Mode)
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(18))
        }

        val sectionHeaderLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sectionTitle = TextView(this).apply {
            id = View.generateViewId()
            tag = "SECTION_TITLE"
            text = currentSection.title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sectionSubtitle = TextView(this).apply {
            id = View.generateViewId()
            tag = "SECTION_SUBTITLE"
            text = currentSection.subtitle
            setTextColor(Color.parseColor("#8E95A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        }
        sectionHeaderLayout.addView(sectionTitle)
        sectionHeaderLayout.addView(sectionSubtitle)
        topBar.addView(sectionHeaderLayout)

        liveStatusPill = TextView(this).apply {
            text = "Comprobando..."
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(dpToPx(14), dpToPx(7), dpToPx(14), dpToPx(7))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1C1E26"))
                cornerRadius = dpToPx(8).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#363A48"))
            }
        }
        topBar.addView(liveStatusPill)
        rightPane.addView(topBar)

        // Main Content Container
        contentContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        rightPane.addView(contentContainer)
        root.addView(rightPane)

        // Initial setup
        switchSection(NavSection.AFR)
        navButtons[NavSection.AFR]?.requestFocus()

        return root
    }

    private fun refreshSectionContent() {
        contentContainer.removeAllViews()
        val contentView = when (currentSection) {
            NavSection.AFR -> buildAfrSection()
            NavSection.MAPPER -> buildMapperSection()
            NavSection.ACTIONS -> buildQuickActionsSection()
            NavSection.WEBREMOTE -> buildWebRemoteSection()
            NavSection.SYSTEM -> buildSystemSection()
        }
        contentContainer.addView(contentView)
    }

    private fun switchSection(section: NavSection) {
        currentSection = section
        for ((sec, btn) in navButtons) {
            btn.isSelected = (sec == section)
        }

        // Update headers
        val titleView = window.decorView.findViewWithTag<TextView>("SECTION_TITLE")
        val subView = window.decorView.findViewWithTag<TextView>("SECTION_SUBTITLE")
        titleView?.text = section.title
        subView?.text = section.subtitle

        refreshSectionContent()
    }

    // ================= SECTIONS BUILDERS =================

    private fun buildAfrSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        col.addView(makeInteractiveToggleCard(
            title = "Motor AFR Híbrido Automático",
            desc = "Detecta la reproducción en streaming vía Túnel Widevine L1, SurfaceFlinger y Sysfs para conmutar los Hz de tu TV",
            initial = prefs.afrEnabled
        ) { prefs.afrEnabled = it })

        col.addView(makeInteractiveToggleCard(
            title = "Refresco Fraccional Preciso",
            desc = "Conmuta a 23.976 Hz para cine 24p y a 59.94 Hz en 60p para eliminar tirones (judder)",
            initial = prefs.fractionalFrameRate
        ) { prefs.fractionalFrameRate = it })

        col.addView(makeInteractiveToggleCard(
            title = "Auto-Pausa y Reproducción en cambio HDMI",
            desc = "Pausa 1.5s durante el handshake HDMI para que Disney+ y Prime Video no pierdan el túnel HDCP",
            initial = prefs.pauseOnSwitch
        ) { prefs.pauseOnSwitch = it })

        col.addView(makeInteractiveToggleCard(
            title = "Convertir 25 fps a 50 Hz",
            desc = "Series y emisiones deportivas europeas con visualización suave en pantallas a 50Hz",
            initial = prefs.convert25To50Hz
        ) { prefs.convert25To50Hz = it })

        col.addView(makeInteractiveToggleCard(
            title = "Restaurar 60 Hz al volver al Launcher",
            desc = "Al salir de la app de streaming hacia Projectivy o Android Home, el panel recupera 60Hz de inmediato",
            initial = prefs.autoStopAfrOnExit
        ) { prefs.autoStopAfrOnExit = it })

        col.addView(makeInteractiveToggleCard(
            title = "Filtro anti-repetición de Volumen",
            desc = "Evita cambios bruscos de sonido al mantener pulsado el volumen en mandos Bluetooth",
            initial = prefs.volumeButtonsFix
        ) { prefs.volumeButtonsFix = it })

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(14), 0, 0)
        }
        testRow.addView(makeActionButton("🎬 Probar AFR 1-Clic") { runServiceAction(QuickAction.ONE_CLICK_AFR) })
        testRow.addView(makeActionButton("📺 Abrir Selector Hz") { runServiceAction(QuickAction.AFR_PANEL) })
        testRow.addView(makeActionButton("↩ Restaurar 60Hz") { runServiceAction(QuickAction.STOP_AFR) })
        col.addView(testRow)

        return scroll
    }

    private fun buildMapperSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(14))
        }
        val infoText = TextView(this).apply {
            text = "Reasigna los botones del mando físico para lanzar funciones personalizadas"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(infoText)
        topRow.addView(makeActionButton("➕ Capturar Botón del Mando") { showCaptureKeyDialog() })
        col.addView(topRow)

        val mappings = prefs.getMappings()
        if (mappings.isEmpty()) {
            val emptyCard = TextView(this).apply {
                text = "No hay botones personalizados configurados todavía.\nPulsa 'Capturar Botón del Mando' para asignar acciones."
                setTextColor(Color.parseColor("#717686"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setPadding(dpToPx(20), dpToPx(24), dpToPx(20), dpToPx(24))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            }
            col.addView(emptyCard)
        } else {
            for (m in mappings) {
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    isFocusable = false
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        setColor(Color.parseColor("#191B22"))
                        cornerRadius = dpToPx(14).toFloat()
                        setStroke(dpToPx(1), Color.parseColor("#282B35"))
                    }
                    setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(14))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = dpToPx(10)
                    }
                }

                val keyName = KeyEvent.keyCodeToString(m.keyCode)
                val detail = TextView(this).apply {
                    text = buildString {
                        append("🔘 ").append(keyName).append(" (código ").append(m.keyCode).append(")\n")
                        append("   • 1 Clic : ").append(m.singleClick.label).append("\n")
                        append("   • 2 Clics: ").append(m.doubleClick.label).append("\n")
                        append("   • Larga  : ").append(m.longPress.label)
                        if (m.appOverrides.isNotEmpty()) {
                            append("\n   • Streaming Override: ").append(m.appOverrides.values.first().label)
                        }
                    }
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                card.addView(detail)

                card.addView(makeActionButton("✏ Editar") { showEditMappingDialog(m) })
                card.addView(makeActionButton("🗑 Borrar") {
                    prefs.removeMapping(m.keyCode)
                    refreshSectionContent()
                })

                col.addView(card)
            }
        }

        return scroll
    }

    private fun buildQuickActionsSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        val grid1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid1.addView(makeCardButton("🌑 Real Screen Off", "Apaga el panel OLED/LCD mientras reproduces música en segundo plano") {
            runServiceAction(QuickAction.REAL_SCREEN_TOGGLE)
        })
        grid1.addView(makeCardButton("📊 HUD Técnico en Vivo", "Muestra en pantalla FPS reales, temperatura del SoC y Hz de refresco") {
            runServiceAction(QuickAction.HUD_TOGGLE)
        })
        col.addView(grid1)

        val grid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid2.addView(makeCardButton("🧹 Limpiar Memoria RAM", "Cierra procesos zombies en segundo plano para máxima fluidez en streaming") {
            runServiceAction(QuickAction.CLEAN_BACKGROUND_RAM)
        })
        grid2.addView(makeCardButton("🗂 Multitarea Rápida", "Conmutador instantáneo entre las últimas aplicaciones abiertas") {
            runServiceAction(QuickAction.RECENT_APPS_SWITCHER)
        })
        col.addView(grid2)

        val grid3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid3.addView(makeCardButton("🌙 Filtro de Luz Nocturna", "Atenuador cálido para sesiones de cine nocturnas sin fatiga visual") {
            runServiceAction(QuickAction.NIGHT_DIMMER_TOGGLE)
        })
        grid3.addView(makeCardButton("🖱 Ratón Virtual D-Pad", "Controla un cursor con las flechas del mando en aplicaciones móviles no adaptadas") {
            runServiceAction(QuickAction.MOUSE_TOGGLE)
        })
        col.addView(grid3)

        return scroll
    }

    private fun buildWebRemoteSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        col.addView(makeInteractiveToggleCard(
            title = "Activar Servidor Mando Web Móvil",
            desc = "Permite enviar textos, pulsaciones y comandos desde cualquier móvil o portátil en tu red Wi-Fi",
            initial = prefs.webRemoteEnabled
        ) { prefs.webRemoteEnabled = it })

        val ip = WebRemoteServer.getLocalIpAddress()
        val url = "http://$ip:${WebRemoteServer.PORT}"

        val urlCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(12)
            }
        }

        val urlLabel = TextView(this).apply {
            text = "Dirección de acceso desde tu móvil:"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        }
        val urlVal = TextView(this).apply {
            text = url
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dpToPx(6), 0, dpToPx(6))
        }
        val urlHint = TextView(this).apply {
            text = "Abre esta dirección en Safari, Chrome o Firefox desde el teléfono conectado a la misma red."
            setTextColor(Color.parseColor("#717686"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        }
        urlCard.addView(urlLabel)
        urlCard.addView(urlVal)
        urlCard.addView(urlHint)
        col.addView(urlCard)

        return scroll
    }

    private fun buildSystemSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(16))
        }
        btnRow.addView(makeActionButton("⚡ Auto-Activar Todo (ADB Interno)") {
            triggerInternalAdbAutoStart(showToast = true)
        })
        btnRow.addView(makeActionButton("🪟 Permiso Superposición") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "✓ Permiso de superposición ya concedido", Toast.LENGTH_SHORT).show()
            }
        })
        btnRow.addView(makeActionButton("🛡 Ajustes Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btnRow)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            isFocusable = true
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        val accActive = TvAfrAccessibilityService.instance != null

        lifecycleScope.launch {
            val daemonSample = withContext(Dispatchers.IO) { ServerClient.querySysInfoSync() }
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val curMode = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
            val dispStr = if (curMode != null) "${curMode.physicalWidth}x${curMode.physicalHeight} @ ${"%.3f".format(curMode.refreshRate)}Hz" else "N/A"

            val info = buildString {
                append("• Modelo de Dispositivo : ${Build.MANUFACTURER.capitalize()} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                append("• Pantalla actual HDMI  : $dispStr\n")
                append("• Servicio Accesibilidad: ${if (accActive) "🟢 Concedido y activo" else "🔴 Inactivo"}\n")
                append("• Permiso Superposición : ${if (overlayGranted) "🟢 Concedido (AfrOverlay & HUD listos)" else "🔴 Pendiente"}\n")
                append("• Demonio Interno ADB   : ${if (daemonSample.online) "🟢 Conectado (FPS: ${"%.2f".format(daemonSample.fps)} | Temp: ${"%.1f".format(daemonSample.cpuTempC)}°C)" else "🟡 Arrancando..."}\n")
                append("• Mando Web Local       : http://${WebRemoteServer.getLocalIpAddress()}:${WebRemoteServer.PORT}")
            }

            statusCard.removeAllViews()
            val tv = TextView(this@MainActivity).apply {
                text = info
                setTextColor(Color.parseColor("#F1F5F9"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.MONOSPACE
                setLineSpacing(0f, 1.35f)
            }
            statusCard.addView(tv)
        }

        col.addView(statusCard)
        return scroll
    }

    // ================= HELPER UI BUILDERS =================

    private fun makeInteractiveToggleCard(
        title: String,
        desc: String,
        initial: Boolean,
        onChanged: (Boolean) -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(20), dpToPx(14), dpToPx(20), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(10)
            }
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val descTv = TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dpToPx(2), 0, 0)
        }
        textLayout.addView(titleTv)
        textLayout.addView(descTv)
        card.addView(textLayout)

        val chk = CheckBox(this).apply {
            isChecked = initial
            isFocusable = false // Let card handle click
            buttonTintList = ColorStateList.valueOf(Color.parseColor("#F59E0B"))
        }
        card.addView(chk)

        card.setOnClickListener {
            chk.isChecked = !chk.isChecked
            onChanged(chk.isChecked)
        }

        return card
    }

    private fun makeCardButton(
        title: String,
        desc: String,
        onClick: () -> Unit
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(18))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(10)
            }
            setOnClickListener { onClick() }
        }

        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val descTv = TextView(this).apply {
            text = desc
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dpToPx(6), 0, 0)
        }
        card.addView(titleTv)
        card.addView(descTv)
        return card
    }

    private fun makeActionButton(label: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColorStateList(this@MainActivity, R.drawable.action_button_text_selector))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dpToPx(18), dpToPx(10), dpToPx(18), dpToPx(10))
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.action_button_selector)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dpToPx(10)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun updateLiveStatusBadge() {
        val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val m = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
        val hzStr = if (m != null) "%.2f Hz".format(m.refreshRate) else "60.00 Hz"
        lifecycleScope.launch {
            val daemonOnline = withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }
            val statusText = "📺 $hzStr  •  ${if (daemonOnline) "🟢 Demonio Conectado" else "🟡 Demonio Iniciando"}"
            liveStatusPill.text = statusText
        }
    }

    private fun showCaptureKeyDialog() {
        val service = TvAfrAccessibilityService.instance
        if (service == null) {
            val commonKeys = listOf(
                KeyEvent.KEYCODE_BOOKMARK to "KEYCODE_BOOKMARK (Botón Favoritos / Guardar)",
                KeyEvent.KEYCODE_INFO to "KEYCODE_INFO (Botón Info)",
                KeyEvent.KEYCODE_GUIDE to "KEYCODE_GUIDE (Botón Guía TV / Live)",
                KeyEvent.KEYCODE_PROG_RED to "KEYCODE_PROG_RED (Botón Rojo)",
                KeyEvent.KEYCODE_PROG_GREEN to "KEYCODE_PROG_GREEN (Botón Verde)",
                KeyEvent.KEYCODE_PROG_YELLOW to "KEYCODE_PROG_YELLOW (Botón Amarillo)",
                KeyEvent.KEYCODE_PROG_BLUE to "KEYCODE_PROG_BLUE (Botón Azul)",
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK to "KEYCODE_MEDIA_AUDIO_TRACK (Pista de Audio)",
                KeyEvent.KEYCODE_CAPTIONS to "KEYCODE_CAPTIONS (Subtítulos)",
                KeyEvent.KEYCODE_TV_INPUT to "KEYCODE_TV_INPUT (Fuente / Input)",
                KeyEvent.KEYCODE_SETTINGS to "KEYCODE_SETTINGS (Engranaje Ajustes)"
            )
            AlertDialog.Builder(this)
                .setTitle("Selecciona un botón del mando")
                .setItems(commonKeys.map { it.second }.toTypedArray()) { _, which ->
                    val chosenKey = commonKeys[which].first
                    val existing = prefs.getMappings().find { it.keyCode == chosenKey }
                        ?: ButtonMapping(chosenKey, singleClick = QuickAction.ONE_CLICK_AFR)
                    showEditMappingDialog(existing)
                }
                .show()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Pulsa ahora el botón de tu mando que quieras mapear")
            .setMessage("Esperando pulsación de cualquier botón físico (excepto cruceta D-Pad y OK)...")
            .setNegativeButton("Cancelar") { _, _ ->
                TvAfrAccessibilityService.keyCaptureCallback = null
            }
            .create()

        TvAfrAccessibilityService.keyCaptureCallback = { capturedKeyCode ->
            dialog.dismiss()
            val existing = prefs.getMappings().find { it.keyCode == capturedKeyCode }
                ?: ButtonMapping(
                    keyCode = capturedKeyCode,
                    singleClick = QuickAction.ONE_CLICK_AFR,
                    doubleClick = QuickAction.AFR_PANEL,
                    longPress = QuickAction.HUD_TOGGLE
                )
            showEditMappingDialog(existing)
        }
        dialog.setOnDismissListener {
            TvAfrAccessibilityService.keyCaptureCallback = null
        }
        dialog.show()
    }

    private fun showEditMappingDialog(mapping: ButtonMapping) {
        val actions = QuickAction.values()
        val labels = actions.map { it.label }.toTypedArray()

        val steps = arrayOf(
            "1 Clic: ${mapping.singleClick.label}",
            "Doble Clic: ${mapping.doubleClick.label}",
            "Pulsación Larga: ${mapping.longPress.label}",
            "Override exclusivo en Streaming (Disney+, Prime Video, Netflix)"
        )

        AlertDialog.Builder(this)
            .setTitle("Configurar ${KeyEvent.keyCodeToString(mapping.keyCode)}")
            .setItems(steps) { _, stepIdx ->
                AlertDialog.Builder(this)
                    .setTitle(steps[stepIdx])
                    .setItems(labels) { _, actIdx ->
                        val chosen = actions[actIdx]
                        val updated = when (stepIdx) {
                            0 -> mapping.copy(singleClick = chosen)
                            1 -> mapping.copy(doubleClick = chosen)
                            2 -> mapping.copy(longPress = chosen)
                            else -> {
                                val map = mapping.appOverrides.toMutableMap()
                                if (chosen == QuickAction.NONE) {
                                    map.clear()
                                } else {
                                    map["com.disney.disneyplus"] = chosen
                                    map["com.amazon.amazonvideo.livingroom"] = chosen
                                    map["com.amazon.avod.thirdpartyclient"] = chosen
                                    map["com.netflix.ninja"] = chosen
                                }
                                mapping.copy(appOverrides = map)
                            }
                        }
                        prefs.upsertMapping(updated)
                        refreshSectionContent()
                    }
                    .show()
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun runServiceAction(action: QuickAction) {
        val svc = TvAfrAccessibilityService.instance
        if (svc != null) {
            svc.executeQuickAction(action)
        } else {
            triggerInternalAdbAutoStart(showToast = true)
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
