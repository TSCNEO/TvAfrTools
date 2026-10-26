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
import dev.custom.tvafrtools.BuildConfig
import dev.custom.tvafrtools.update.UpdateManager
import dev.custom.tvafrtools.update.UpdateInfo
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
        AFR("Ajuste de Hz", "Evita tirones ajustando la tele a 24, 50 o 60 Hz", "🎬"),
        MAPPER("Botones del Mando", "Elige qué hace cada botón con 1 clic, 2 clics o mantener pulsado", "🎮"),
        ACTIONS("Acciones Rápidas", "Accesos útiles: apagar pantalla, cambiar de app o liberar memoria", "🚀"),
        WEBREMOTE("Mando desde el Móvil", "Controla la tele desde el navegador del móvil o el ordenador", "🌐"),
        SYSTEM("Estado y Ajustes", "Información del sistema, permisos y actualizaciones", "⚡")
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
            if (!online) {
                withContext(Dispatchers.IO) {
                    LocalAdbBootstrap.autoStartPrivilegedDaemon(this@MainActivity)
                }
                for (i in 1..4) {
                    delay(500L)
                    if (withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }) break
                }
            }
            updateLiveStatusBadge()
            refreshSectionContent()
        }
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        if (TvAfrAccessibilityService.instance != null) return true
        return try {
            val expected = "${context.packageName}/${TvAfrAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains(expected)
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildAccessibilityWarningBanner(): View {
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(18), dpToPx(14), dpToPx(18), dpToPx(14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, dpToPx(16))
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#261717"))
                cornerRadius = dpToPx(10).toFloat()
                setStroke(dpToPx(1), Color.parseColor("#DC2626"))
            }
        }

        val title = TextView(this).apply {
            text = "⚠️ Permiso de Accesibilidad inactivo"
            setTextColor(Color.parseColor("#FCA5A5"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(4))
        }
        val desc = TextView(this).apply {
            text = "Necesario para interceptar botones del mando, ajustar Hz automáticamente y mostrar el menú flotante."
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            setPadding(0, 0, 0, dpToPx(10))
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        btnRow.addView(makeActionButton("⚡ Activar automáticamente") {
            showAutoSetupDialog()
        })
        btnRow.addView(makeActionButton("🛡 Abrir Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btnRow.addView(makeActionButton("ℹ️ Si dice Ajuste restringido (Android 13/14)") {
                showRestrictedSettingsGuideDialog()
            })
        }

        banner.addView(title)
        banner.addView(desc)
        banner.addView(btnRow)
        return banner
    }

    private fun showRestrictedSettingsGuideDialog() {
        AlertDialog.Builder(this)
            .setTitle("🔓 Desbloquear Ajustes restringidos en Android 13/14")
            .setMessage(
                "En Android 13 y 14, el sistema bloquea por seguridad las aplicaciones instaladas desde archivo APK.\n\n" +
                "Pasos para desbloquearlo en tu TV:\n" +
                "1. Pulsa el botón inferior Ir a Información de la app.\n" +
                "2. Arriba a la derecha, pulsa en los 3 puntos (o menú Más).\n" +
                "3. Selecciona Permitir ajustes restringidos.\n" +
                "4. Vuelve a TvAfrTools y activa el servicio de Accesibilidad.\n\n" +
                "💡 Si tienes activada la Depuración USB en Ajustes de Desarrollador, pulsa Activar automáticamente y la app se desbloquea sola."
            )
            .setPositiveButton("⚙️ Ir a Información de la app") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showAutoSetupDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle("⚡ Activando permisos en tu TV")
            .setMessage(
                "Conectando al sistema ADB interno...\n\n" +
                "👉 IMPORTANTE: Si aparece una ventana en tu televisor preguntando si permites la depuración USB, marca la casilla Permitir siempre y pulsa Aceptar con el mando."
            )
            .setCancelable(false)
            .setNegativeButton("Cancelar", null)
            .show()

        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                LocalAdbBootstrap.autoStartPrivilegedDaemon(this@MainActivity)
            }

            var onlineNow = false
            for (i in 1..8) {
                delay(500L)
                if (withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }) {
                    onlineNow = true
                    break
                }
            }

            try { dialog.dismiss() } catch (_: Throwable) {}

            if (onlineNow) {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("✓ ¡Todo listo!")
                    .setMessage("Los permisos del mando y el motor de cambio de Hz se han activado con éxito. Ya puedes usar tus botones configurados.")
                    .setPositiveButton("Genial", null)
                    .show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (res.waitingForTvAuthPopup) "Acepta el mensaje en tu TV" else "No se pudo activar automáticamente")
                    .setMessage(
                        if (res.waitingForTvAuthPopup) {
                            "Ha aparecido una ventana de confirmación en tu televisor.\n\n" +
                            "1. Marca Permitir siempre desde este equipo\n" +
                            "2. Pulsa Aceptar con el mando\n\n" +
                            "Una vez aceptado, pulsa Reintentar."
                        } else {
                            "No se pudo conectar al sistema ADB interno.\n\n" +
                            "• Para activarlo manualmente, ve a Ajustes > Accesibilidad > TvAfrTools.\n" +
                            "• En Android 13/14, primero pulsa Permitir ajustes restringidos en la ficha de la app.\n" +
                            "• O bien activa Depuración USB en las Opciones de Desarrollador de tu televisor."
                        }
                    )
                    .setPositiveButton(if (res.waitingForTvAuthPopup) "Reintentar" else "🛡 Abrir Accesibilidad") { _, _ ->
                        if (res.waitingForTvAuthPopup) {
                            showAutoSetupDialog()
                        } else {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    }
                    .setNeutralButton("⚙️ Ficha de la app") { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    }
                    .setNegativeButton("Cerrar", null)
                    .show()
            }

            updateLiveStatusBadge()
            refreshSectionContent()
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
            text = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE}"
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val accActive = TvAfrAccessibilityService.instance != null || isAccessibilityEnabled(this)
        if (!accActive) {
            layout.addView(buildAccessibilityWarningBanner())
        }

        val contentView = when (currentSection) {
            NavSection.AFR -> buildAfrSection()
            NavSection.MAPPER -> buildMapperSection()
            NavSection.ACTIONS -> buildQuickActionsSection()
            NavSection.WEBREMOTE -> buildWebRemoteSection()
            NavSection.SYSTEM -> buildSystemSection()
        }
        contentView.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        )
        layout.addView(contentView)
        contentContainer.addView(layout)
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
            title = "Ajuste automático de Hz al reproducir vídeo",
            desc = "Detecta películas y series en apps como Disney+, Netflix o Prime y cambia los Hz de la tele para que se vea suave",
            initial = prefs.afrEnabled
        ) { prefs.afrEnabled = it })

        col.addView(makeInteractiveToggleCard(
            title = "Modo cine exacto (23,976 Hz)",
            desc = "Elimina los micropalpitares en películas grabadas a 24 fotogramas",
            initial = prefs.fractionalFrameRate
        ) { prefs.fractionalFrameRate = it })

        col.addView(makeInteractiveToggleCard(
            title = "Pausa breve al cambiar de Hz",
            desc = "Pausa el vídeo un segundo mientras la tele cambia de señal para no perder imagen ni sonido",
            initial = prefs.pauseOnSwitch
        ) { prefs.pauseOnSwitch = it })

        col.addView(makeInteractiveToggleCard(
            title = "Series y deportes europeos a 50 Hz",
            desc = "Adapta contenidos a 25 fps para que se vean fluidos a 50 Hz",
            initial = prefs.convert25To50Hz
        ) { prefs.convert25To50Hz = it })

        col.addView(makeInteractiveToggleCard(
            title = "Volver a 60 Hz al salir al menú principal",
            desc = "Al volver a la pantalla de inicio, la tele vuelve automáticamente a 60 Hz para que los menús vayan rápidos",
            initial = prefs.autoStopAfrOnExit
        ) { prefs.autoStopAfrOnExit = it })

        col.addView(makeInteractiveToggleCard(
            title = "Control suave del volumen",
            desc = "Evita saltos bruscos de volumen al dejar pulsado el botón en mandos Bluetooth",
            initial = prefs.volumeButtonsFix
        ) { prefs.volumeButtonsFix = it })

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(14), 0, 0)
        }
        testRow.addView(makeActionButton("🎬 Ajustar al vídeo ahora") { runServiceAction(QuickAction.ONE_CLICK_AFR) })
        testRow.addView(makeActionButton("📺 Elegir Hz en pantalla") { runServiceAction(QuickAction.AFR_PANEL) })
        testRow.addView(makeActionButton("↩ Volver a 60 Hz") { runServiceAction(QuickAction.STOP_AFR) })
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
            text = "Elige un botón del mando y ponle la función que quieras"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        topRow.addView(infoText)
        topRow.addView(makeActionButton("➕ Añadir botón del mando") { showCaptureKeyDialog() })
        col.addView(topRow)

        val mappings = prefs.getMappings()
        if (mappings.isEmpty()) {
            val emptyCard = TextView(this).apply {
                text = "Todavía no has configurado ningún botón.\nPulsa 'Añadir botón del mando' para empezar."
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
                        append("🔘 ").append(keyName).append(" (botón ").append(m.keyCode).append(")\n")
                        append("   • 1 Clic : ").append(formatActionLabel(m.singleClick, m.singleClickPkg)).append("\n")
                        append("   • 2 Clics: ").append(formatActionLabel(m.doubleClick, m.doubleClickPkg)).append("\n")
                        append("   • Mantener pulsado: ").append(formatActionLabel(m.longPress, m.longPressPkg))
                        if (m.appOverrides.isNotEmpty()) {
                            append("\n   • Solo en apps de vídeo: ").append(formatActionLabel(m.appOverrides.values.first(), m.overridePkg))
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
        grid1.addView(makeCardButton("🌑 Apagar Pantalla", "Apaga la pantalla mientras sigues escuchando música en Spotify o YouTube") {
            runServiceAction(QuickAction.REAL_SCREEN_TOGGLE)
        })
        grid1.addView(makeCardButton("📊 Datos en Pantalla", "Muestra los fotogramas (FPS), la temperatura y los Hz actuales") {
            runServiceAction(QuickAction.HUD_TOGGLE)
        })
        col.addView(grid1)

        val grid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid2.addView(makeCardButton("🧹 Liberar Memoria", "Cierra apps de fondo para que la tele vaya más fluida") {
            runServiceAction(QuickAction.CLEAN_BACKGROUND_RAM)
        })
        grid2.addView(makeCardButton("🗂 Apps Abiertas", "Panel flotante para cambiar rápido entre tus últimas aplicaciones") {
            runServiceAction(QuickAction.RECENT_APPS_SWITCHER)
        })
        col.addView(grid2)

        val grid3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid3.addView(makeCardButton("🌙 Modo Noche", "Atenúa la pantalla para ver la tele a oscuras sin deslumbrarte") {
            runServiceAction(QuickAction.NIGHT_DIMMER_TOGGLE)
        })
        grid3.addView(makeCardButton("🖱 Ratón con el Mando", "Mueve un cursor con las flechas en apps pensadas para móvil o tablet") {
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
            title = "Activar control desde el móvil",
            desc = "Te permite usar el móvil como mando a distancia para escribir rápido y controlar la tele",
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
            text = "Abre esta dirección en el navegador de tu móvil (conectado a la misma Wi-Fi):"
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
        btnRow.addView(makeActionButton("⚡ Activar permisos automáticamente") {
            showAutoSetupDialog()
        })
        btnRow.addView(makeActionButton("🛡 Abrir Accesibilidad") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btnRow.addView(makeActionButton("⚙️ Info App (Ajustes restringidos)") {
                showRestrictedSettingsGuideDialog()
            })
        }
        btnRow.addView(makeActionButton("🪟 Permitir superposición") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "✓ Permiso de superposición ya concedido", Toast.LENGTH_SHORT).show()
            }
        })
        col.addView(btnRow)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            isFocusable = false
        }

        val overlayGranted = Settings.canDrawOverlays(this)
        val accActive = TvAfrAccessibilityService.instance != null || isAccessibilityEnabled(this)

        lifecycleScope.launch {
            val daemonSample = withContext(Dispatchers.IO) { ServerClient.querySysInfoSync() }
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val curMode = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
            val dispStr = if (curMode != null) "${curMode.physicalWidth}x${curMode.physicalHeight} @ ${"%.3f".format(curMode.refreshRate)}Hz" else "N/A"

            val info = buildString {
                append("• Dispositivo : ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})\n")
                append("• Pantalla HDMI : $dispStr\n")
                append("• Accesibilidad : ${if (accActive) "🟢 Activo" else "🔴 Inactivo"}\n")
                append("• Superposición : ${if (overlayGranted) "🟢 Concedido" else "🔴 Pendiente"}\n")
                append("• Motor Interno : ${if (daemonSample.online) "🟢 Listo (${"%.2f".format(daemonSample.fps)} FPS | ${"%.1f".format(daemonSample.cpuTempC)}°C)" else "🟡 Iniciando..."}\n")
                append("• Mando Web     : http://${WebRemoteServer.getLocalIpAddress()}:${WebRemoteServer.PORT}")
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

        // Card de Actualización de Software (GitHub Releases)
        val updateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            isFocusable = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(16)
            }
        }

        val updateTitle = TextView(this).apply {
            text = "🔄 Actualizaciones"
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val updateDesc = TextView(this).apply {
            text = "Versión instalada: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\nRepositorio: https://github.com/TSCNEO/TvAfrTools"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, dpToPx(6), 0, dpToPx(14))
        }

        val updateStatusText = TextView(this).apply {
            text = ""
            setTextColor(Color.parseColor("#38BDF8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setPadding(0, dpToPx(10), 0, 0)
            visibility = View.GONE
        }

        val checkUpdateBtn = makeActionButton("🔍 Comprobar si hay nueva versión") {
            updateStatusText.visibility = View.VISIBLE
            updateStatusText.text = "Consultando releases en GitHub..."
            updateStatusText.setTextColor(Color.parseColor("#F59E0B"))

            UpdateManager.checkForUpdate(this) { info, err ->
                if (info != null) {
                    updateStatusText.text = "🎉 ¡Nueva versión ${info.tagName} disponible!"
                    updateStatusText.setTextColor(Color.parseColor("#10B981"))

                    AlertDialog.Builder(this)
                        .setTitle("Nueva versión ${info.tagName}")
                        .setMessage("Notas de la versión:\n\n${info.releaseNotes}\n\n¿Deseas descargar e instalar ahora?")
                        .setPositiveButton("Descargar e Instalar") { _, _ ->
                            updateStatusText.text = "Descargando actualización..."
                            UpdateManager.downloadAndInstall(
                                this,
                                info,
                                onProgress = { pct ->
                                    updateStatusText.text = "Descargando: $pct%"
                                },
                                onStatus = { status ->
                                    updateStatusText.text = status
                                },
                                onComplete = { success, msg ->
                                    updateStatusText.text = msg
                                    if (success) {
                                        updateStatusText.setTextColor(Color.parseColor("#10B981"))
                                    } else {
                                        updateStatusText.setTextColor(Color.parseColor("#EF4444"))
                                    }
                                }
                            )
                        }
                        .setNegativeButton("Más tarde", null)
                        .show()
                } else {
                    updateStatusText.text = err ?: "Estás en la última versión."
                    updateStatusText.setTextColor(Color.parseColor("#94A3B8"))
                }
            }
        }

        updateCard.addView(updateTitle)
        updateCard.addView(updateDesc)
        updateCard.addView(checkUpdateBtn)
        updateCard.addView(updateStatusText)

        col.addView(updateCard)
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
            val statusText = "📺 $hzStr  •  ${if (daemonOnline) "🟢 Sistema listo" else "🟡 Iniciando..."}"
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

    data class AppItem(val label: String, val packageName: String)

    private fun getInstalledTvApps(): List<AppItem> {
        val pm = packageManager
        val apps = mutableMapOf<String, String>()

        val tvIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val tvList = pm.queryIntentActivities(tvIntent, 0)
        for (info in tvList) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            val label = info.loadLabel(pm).toString()
            apps[pkg] = label
        }

        val normalIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val normalList = pm.queryIntentActivities(normalIntent, 0)
        for (info in normalList) {
            val pkg = info.activityInfo.packageName
            if (pkg == packageName) continue
            if (!apps.containsKey(pkg)) {
                val label = info.loadLabel(pm).toString()
                apps[pkg] = label
            }
        }

        return apps.map { AppItem(it.value, it.key) }.sortedBy { it.label.lowercase() }
    }

    private fun formatActionLabel(action: QuickAction, pkg: String?): String {
        return if (action == QuickAction.OPEN_APP && !pkg.isNullOrBlank()) {
            val appName = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                pkg
            }
            "📱 Abrir app: $appName"
        } else {
            action.label
        }
    }

    private fun showEditMappingDialog(mapping: ButtonMapping) {
        val actions = QuickAction.values()
        val labels = actions.map { it.label }.toTypedArray()

        val streamingActiveAction = if (mapping.appOverrides.isNotEmpty()) {
            formatActionLabel(mapping.appOverrides.values.first(), mapping.overridePkg)
        } else {
            "Sin cambio (misma acción)"
        }

        val steps = arrayOf(
            "1 Clic: ${formatActionLabel(mapping.singleClick, mapping.singleClickPkg)}",
            "2 Clics rápidos: ${formatActionLabel(mapping.doubleClick, mapping.doubleClickPkg)}",
            "Mantener pulsado: ${formatActionLabel(mapping.longPress, mapping.longPressPkg)}",
            "Cambiar acción solo en apps de vídeo: $streamingActiveAction"
        )

        AlertDialog.Builder(this)
            .setTitle("Configurar botón: ${KeyEvent.keyCodeToString(mapping.keyCode)}")
            .setItems(steps) { _, stepIdx ->
                val dialogTitle = if (stepIdx == 3) {
                    "¿Qué debe hacer este botón cuando estés viendo Disney+, Netflix o Prime?"
                } else {
                    steps[stepIdx]
                }
                AlertDialog.Builder(this)
                    .setTitle(dialogTitle)
                    .setItems(labels) { _, actIdx ->
                        val chosen = actions[actIdx]
                        if (chosen == QuickAction.OPEN_APP) {
                            showAppPickerDialog(mapping, stepIdx)
                        } else {
                            applyActionToStep(mapping, stepIdx, chosen, null)
                        }
                    }
                    .show()
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    private fun showAppPickerDialog(mapping: ButtonMapping, stepIdx: Int) {
        val installedApps = getInstalledTvApps()
        if (installedApps.isEmpty()) {
            Toast.makeText(this, "No se encontraron otras aplicaciones instaladas", Toast.LENGTH_SHORT).show()
            return
        }
        val appNames = installedApps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Selecciona la aplicación a abrir")
            .setItems(appNames) { _, appIdx ->
                val chosenApp = installedApps[appIdx]
                applyActionToStep(mapping, stepIdx, QuickAction.OPEN_APP, chosenApp.packageName)
            }
            .show()
    }

    private fun applyActionToStep(
        mapping: ButtonMapping,
        stepIdx: Int,
        action: QuickAction,
        targetPkg: String?
    ) {
        val updated = when (stepIdx) {
            0 -> mapping.copy(singleClick = action, singleClickPkg = targetPkg)
            1 -> mapping.copy(doubleClick = action, doubleClickPkg = targetPkg)
            2 -> mapping.copy(longPress = action, longPressPkg = targetPkg)
            else -> {
                val map = mapping.appOverrides.toMutableMap()
                if (action == QuickAction.NONE) {
                    map.clear()
                } else {
                    map["com.disney.disneyplus"] = action
                    map["com.amazon.amazonvideo.livingroom"] = action
                    map["com.amazon.avod.thirdpartyclient"] = action
                    map["com.netflix.ninja"] = action
                    map["com.apple.atve.androidtv.appletv"] = action
                    map["com.max.nordic"] = action
                }
                mapping.copy(appOverrides = map, overridePkg = targetPkg)
            }
        }
        prefs.upsertMapping(updated)
        refreshSectionContent()
    }

    private fun runServiceAction(action: QuickAction) {
        val svc = TvAfrAccessibilityService.instance
        if (svc != null) {
            svc.executeQuickAction(action)
        } else {
            showAutoSetupDialog()
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }
}
