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
import android.text.Spanned
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
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
    private enum class NavSection(val titleRes: Int, val subRes: Int, val icon: String) {
        AFR(R.string.nav_afr, R.string.nav_afr_sub, "🎬"),
        AFR_APPS(R.string.nav_afr_apps, R.string.nav_afr_apps_sub, "📺"),
        MAPPER(R.string.nav_mapper, R.string.nav_mapper_sub, "🎮"),
        ACTIONS(R.string.nav_actions, R.string.nav_actions_sub, "🚀"),
        WEBREMOTE(R.string.nav_webremote, R.string.nav_webremote_sub, "🌐"),
        SYSTEM(R.string.nav_system, R.string.nav_system_sub, "⚡");

        fun getTitle(ctx: Context): String = ctx.getString(titleRes)
        fun getSubtitle(ctx: Context): String = ctx.getString(subRes)
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
            val online = withContext(Dispatchers.IO) { LocalAdbBootstrap.isSetupReady(applicationContext) }
            if (!online) {
                withContext(Dispatchers.IO) {
                    LocalAdbBootstrap.autoStartPrivilegedDaemon(this@MainActivity)
                }
                for (i in 1..3) {
                    delay(1000L)
                    if (withContext(Dispatchers.IO) { LocalAdbBootstrap.isSetupReady(applicationContext) }) break
                    val result = withContext(Dispatchers.IO) {
                        LocalAdbBootstrap.autoStartPrivilegedDaemon(applicationContext)
                    }
                    if (result.waitingForTvAuthPopup) break
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

        btnRow.addView(makeActionButton(getString(R.string.btn_auto_activate)) {
            showAutoSetupDialog()
        })
        btnRow.addView(makeActionButton(getString(R.string.btn_open_accessibility)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            btnRow.addView(makeActionButton(getString(R.string.btn_setup_guide)) {
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
            .setTitle(getString(R.string.dialog_guide_title))
            .setMessage(
                "En Android TV y Google TV, la ficha de la app no tiene el menú de 3 puntos que tienen los teléfonos móviles.\n\n" +
                "Por eso, TvAfrTools desbloquea los permisos automáticamente:\n\n" +
                "1. Asegúrate de tener activada la \"Depuración USB\" en los Ajustes de Desarrollador de tu TV.\n" +
                "2. Pulsa en \"⚡ Activar automáticamente\".\n" +
                "3. Si tu TV muestra la ventana de confirmación, marca \"Permitir siempre\" y pulsa Aceptar con el mando."
            )
            .setPositiveButton(getString(R.string.btn_auto_activate)) { _, _ ->
                showAutoSetupDialog()
            }
            .setNegativeButton(getString(R.string.btn_got_it), null)
            .show()
    }

    private fun showAutoSetupDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_auto_setup_title))
            .setMessage(
                "Conectando al sistema ADB interno...\n\n" +
                "👉 IMPORTANTE: Si aparece una ventana en tu televisor preguntando si permites la depuración USB, marca la casilla Permitir siempre y pulsa Aceptar con el mando."
            )
            .setCancelable(false)
            .setNegativeButton(getString(R.string.mapper_btn_cancel), null)
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
                    .setTitle(getString(R.string.dialog_ready_title))
                    .setMessage(getString(R.string.dialog_ready_msg))
                    .setPositiveButton(getString(R.string.btn_great), null)
                    .show()
            } else {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(if (res.waitingForTvAuthPopup) getString(R.string.dialog_auth_popup_title) else getString(R.string.dialog_failed_title))
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
                text = "${sec.icon}   ${sec.getTitle(this@MainActivity)}"
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
                    (v as TextView).typeface = if (hasFocus) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                setOnClickListener {
                    if (currentSection != sec) {
                        switchSection(sec)
                    }
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
            text = "${currentSection.icon}   ${currentSection.getTitle(this@MainActivity)}"
            setTextColor(Color.parseColor("#F8FAFC"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val sectionSubtitle = TextView(this).apply {
            id = View.generateViewId()
            tag = "SECTION_SUBTITLE"
            text = currentSection.getSubtitle(this@MainActivity)
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
            NavSection.AFR_APPS -> buildAfrAppsSection()
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
        titleView?.text = "${section.icon}   ${section.getTitle(this)}"
        subView?.text = section.getSubtitle(this)

        refreshSectionContent()
    }

    // ================= SECTIONS BUILDERS =================

    private fun buildAfrAppsSection(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = getString(R.string.afr_apps_help)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dpToPx(20))
        })
        for (app in getInstalledTvApps()) {
            col.addView(makeInteractiveToggleCard(
                title = app.label,
                desc = app.packageName,
                initial = app.packageName in prefs.automaticAfrApps
            ) { enabled ->
                val selected = prefs.automaticAfrApps.toMutableSet()
                if (enabled) selected.add(app.packageName) else selected.remove(app.packageName)
                prefs.automaticAfrApps = selected
            })
        }
        return ScrollView(this).apply { addView(col) }
    }

    private fun buildAfrSection(): View {
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dpToPx(10), dpToPx(20))
        }
        scroll.addView(col)

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_auto_title),
            desc = getString(R.string.afr_auto_desc),
            initial = prefs.afrEnabled
        ) { prefs.afrEnabled = it })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_exact_title),
            desc = getString(R.string.afr_exact_desc),
            initial = prefs.fractionalFrameRate
        ) { prefs.fractionalFrameRate = it })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_pause_title),
            desc = getString(R.string.afr_pause_desc),
            initial = prefs.pauseOnSwitch
        ) { prefs.pauseOnSwitch = it })

        val delayLabel = TextView(this).apply {
            setTextColor(Color.WHITE)
            text = getString(R.string.afr_resume_delay, prefs.resumeDelayMs / 1000.0)
        }
        col.addView(delayLabel)
        col.addView(android.widget.SeekBar(this).apply {
            max = 18
            progress = (prefs.resumeDelayMs - 1000) / 500
            contentDescription = getString(R.string.afr_resume_accessibility)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: android.widget.SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.resumeDelayMs = 1000 + value * 500
                        delayLabel.text = getString(R.string.afr_resume_delay, prefs.resumeDelayMs / 1000.0)
                    }
                }
                override fun onStartTrackingTouch(bar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(bar: android.widget.SeekBar?) {}
            })
        })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_25_title),
            desc = getString(R.string.afr_25_desc),
            initial = prefs.convert25To50Hz
        ) { prefs.convert25To50Hz = it })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_30_title),
            desc = getString(R.string.afr_30_desc),
            initial = prefs.convert30To60Hz
        ) { prefs.convert30To60Hz = it })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.afr_restore_title),

            desc = getString(R.string.afr_restore_desc),
            initial = prefs.autoStopAfrOnExit
        ) { prefs.autoStopAfrOnExit = it })

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.volume_smooth_title),
            desc = getString(R.string.volume_smooth_desc),
            initial = prefs.volumeButtonsFix
        ) { prefs.volumeButtonsFix = it })

        val testRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpToPx(14), 0, 0)
        }
        testRow.addView(makeActionButton(getString(R.string.qa_one_click_afr)) { runServiceAction(QuickAction.ONE_CLICK_AFR) })
        testRow.addView(makeActionButton(getString(R.string.qa_afr_panel)) { runServiceAction(QuickAction.AFR_PANEL) })
        testRow.addView(makeActionButton(getString(R.string.qa_stop_afr)) { runServiceAction(QuickAction.STOP_AFR) })
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
        topRow.addView(makeActionButton(getString(R.string.mapper_btn_add)) { showCaptureKeyDialog() })
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
                        append(getString(R.string.button_label_key, keyName, m.keyCode)).append("\n")
                        append("   • ").append(getString(R.string.mapper_single_click)).append(": ").append(formatActionLabel(m.singleClick, m.singleClickPkg)).append("\n")
                        append("   • ").append(getString(R.string.mapper_double_click)).append(": ").append(formatActionLabel(m.doubleClick, m.doubleClickPkg)).append("\n")
                        append("   • ").append(getString(R.string.mapper_long_press)).append(": ").append(formatActionLabel(m.longPress, m.longPressPkg))
                        if (m.appOverrides.isNotEmpty()) {
                            append("\n   • ").append(getString(R.string.mapper_video_override)).append(": ").append(formatActionLabel(m.appOverrides.values.first(), m.overridePkg))
                        }
                    }
                    setTextColor(Color.parseColor("#F1F5F9"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                card.addView(detail)

                card.addView(makeActionButton("✏ " + getString(R.string.btn_edit)) { showEditMappingDialog(m) })
                card.addView(makeActionButton("🗑 " + getString(R.string.btn_delete)) {
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
        grid1.addView(makeCardButton(getString(R.string.action_screen_off_title), getString(R.string.action_screen_off_desc)) {
            runServiceAction(QuickAction.REAL_SCREEN_TOGGLE)
        })
        grid1.addView(makeCardButton(getString(R.string.action_hud_title), getString(R.string.action_hud_desc)) {
            runServiceAction(QuickAction.HUD_TOGGLE)
        })
        col.addView(grid1)

        val grid2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid2.addView(makeCardButton(getString(R.string.action_clean_ram_title), getString(R.string.action_clean_ram_desc)) {
            runServiceAction(QuickAction.CLEAN_BACKGROUND_RAM)
        })
        grid2.addView(makeCardButton(getString(R.string.action_recents_title), getString(R.string.action_recents_desc)) {
            runServiceAction(QuickAction.RECENT_APPS_SWITCHER)
        })
        col.addView(grid2)

        val grid3 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        grid3.addView(makeCardButton(getString(R.string.action_night_dimmer_title), getString(R.string.action_night_dimmer_desc)) {
            runServiceAction(QuickAction.NIGHT_DIMMER_TOGGLE)
        })
        grid3.addView(makeCardButton(getString(R.string.action_mouse_title), getString(R.string.action_mouse_desc)) {
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
            title = getString(R.string.webremote_toggle_title),
            desc = getString(R.string.webremote_toggle_desc),
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

        col.addView(makeInteractiveToggleCard(
            title = getString(R.string.boot_recovery_title),
            desc = getString(R.string.boot_recovery_desc),
            initial = prefs.startOnBoot
        ) { prefs.startOnBoot = it })


        val btnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(10))
        }
        val btnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dpToPx(16))
        }

        val b1 = makeActionButton(getString(R.string.btn_auto_activate)) {
            showAutoSetupDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(10)
            }
        }
        val b2 = makeActionButton(getString(R.string.btn_open_accessibility)) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val b3 = makeActionButton(getString(R.string.system_btn_overlay)) {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, getString(R.string.toast_overlay_granted), Toast.LENGTH_SHORT).show()
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dpToPx(10)
            }
        }

        val b4 = makeActionButton(getString(R.string.system_btn_app_settings)) {
            showRestrictedSettingsGuideDialog()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btnRow1.addView(b1)
        btnRow1.addView(b2)
        btnRow2.addView(b3)
        btnRow2.addView(b4)

        col.addView(btnRow1)
        col.addView(btnRow2)

        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.card_interactive_selector)
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            isFocusable = false
        }

        val cardTitle = TextView(this).apply {
            text = getString(R.string.system_card_title)
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dpToPx(12))
        }
        statusCard.addView(cardTitle)

        val overlayGranted = Settings.canDrawOverlays(this)
        val accActive = TvAfrAccessibilityService.instance != null || isAccessibilityEnabled(this)

        fun createStatusRow(label: String, value: String, valueColor: Int): LinearLayout {
            return LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dpToPx(6), 0, dpToPx(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )

                val labelTv = TextView(this@MainActivity).apply {
                    text = label
                    setTextColor(Color.parseColor("#94A3B8"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f)
                }
                val valueTv = TextView(this@MainActivity).apply {
                    text = value
                    setTextColor(valueColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.END
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.9f)
                }
                addView(labelTv)
                addView(valueTv)
            }
        }

        val statusRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        statusCard.addView(statusRowsContainer)

        lifecycleScope.launch {
            val daemonSample = withContext(Dispatchers.IO) { ServerClient.querySysInfoSync() }
            val dm = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
            val curMode = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.mode
            val dispStr = if (curMode != null) "${curMode.physicalWidth}x${curMode.physicalHeight} @ ${"%.3f".format(curMode.refreshRate)} Hz" else "N/A"

            statusRowsContainer.removeAllViews()
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_device), "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})", Color.parseColor("#F1F5F9")))
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_hdmi), dispStr, Color.parseColor("#38BDF8")))
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_acc), if (accActive) getString(R.string.status_active_linked) else getString(R.string.status_inactive), if (accActive) Color.parseColor("#4ADE80") else Color.parseColor("#EF4444")))
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_overlay), if (overlayGranted) getString(R.string.status_granted) else getString(R.string.status_pending), if (overlayGranted) Color.parseColor("#4ADE80") else Color.parseColor("#EF4444")))
            val daemonText = when {
                daemonSample.online -> "🟢 Listo (${"%.2f".format(daemonSample.fps)} FPS | ${"%.1f".format(daemonSample.cpuTempC)}°C)"
                accActive -> getString(R.string.status_standard_mode)
                else -> getString(R.string.status_inactive_activate_hint)
            }
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_daemon), daemonText, if (daemonSample.online || accActive) Color.parseColor("#4ADE80") else Color.parseColor("#F59E0B")))
            statusRowsContainer.addView(createStatusRow(getString(R.string.system_row_web_remote), "http://${WebRemoteServer.getLocalIpAddress()}:${WebRemoteServer.PORT}", Color.parseColor("#38BDF8")))
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
            text = getString(R.string.system_updates_title)
            setTextColor(Color.parseColor("#F59E0B"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val updateDesc = TextView(this).apply {
            text = getString(R.string.system_installed_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
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

        val checkUpdateBtn = makeActionButton(getString(R.string.system_check_update)) {
            updateStatusText.visibility = View.VISIBLE
            updateStatusText.text = getString(R.string.update_checking)
            updateStatusText.setTextColor(Color.parseColor("#F59E0B"))

            UpdateManager.checkForUpdate(this) { info, err ->
                if (info != null) {
                    updateStatusText.text = getString(R.string.update_available_title, info.tagName)
                    updateStatusText.setTextColor(Color.parseColor("#10B981"))

                    val changelogView = ScrollView(this).apply {
                        isFocusable = true
                        descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                        setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
                    }
                    val notesTv = TextView(this).apply {
                        text = markdownToSpanned(info.releaseNotes)
                        setTextColor(Color.parseColor("#E2E8F0"))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                        setLineSpacing(dpToPx(2).toFloat(), 1f)
                    }
                    changelogView.addView(notesTv)

                    AlertDialog.Builder(this)
                        .setTitle(getString(R.string.update_available_title, info.tagName))
                        .setView(changelogView)
                        .setPositiveButton(getString(R.string.update_btn_install)) { _, _ ->
                            updateStatusText.text = getString(R.string.update_downloading, 0)
                            UpdateManager.downloadAndInstall(
                                this,
                                info,
                                onProgress = { pct ->
                                    updateStatusText.text = getString(R.string.update_downloading, pct)
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
                        .setNegativeButton(getString(R.string.btn_later), null)
                        .show()
                } else {
                    updateStatusText.text = err ?: getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME)
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
        val a11yActive = TvAfrAccessibilityService.instance != null || isAccessibilityEnabled(this)
        lifecycleScope.launch {
            val daemonOnline = withContext(Dispatchers.IO) { ServerClient.isDaemonOnlineSync() }
            val (statusText, textColor) = when {
                daemonOnline && a11yActive -> ("📺 $hzStr  •  🟢 Sistema listo" to Color.parseColor("#4ADE80"))
                a11yActive -> ("📺 $hzStr  •  🟢 Botones activos" to Color.parseColor("#38BDF8"))
                daemonOnline -> ("📺 $hzStr  •  🟡 Accesibilidad pendiente" to Color.parseColor("#F59E0B"))
                else -> ("📺 $hzStr  •  ⚠️ Permisos pendientes" to Color.parseColor("#EF4444"))
            }
            liveStatusPill.text = statusText
            liveStatusPill.setTextColor(textColor)
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
            .setTitle(getString(R.string.dialog_press_button_title))
            .setMessage(getString(R.string.dialog_press_button_msg))
            .setNegativeButton(getString(R.string.mapper_btn_cancel)) { _, _ ->
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

    private fun markdownToSpanned(markdown: String): Spanned {
        val lines = markdown.lines()
        val sb = StringBuilder()
        for (line in lines) {
            var l = line.trim()
            if (l.isEmpty()) {
                sb.append("<br/>")
                continue
            }
            when {
                l.startsWith("### ") -> {
                    l = "<br/><b><font color='#F59E0B'>" + l.substring(4).trim() + "</font></b><br/>"
                }
                l.startsWith("## ") -> {
                    l = "<br/><big><b><font color='#F59E0B'>" + l.substring(3).trim() + "</font></b></big><br/>"
                }
                l.startsWith("# ") -> {
                    l = "<br/><h3><font color='#F59E0B'>" + l.substring(2).trim() + "</font></h3>"
                }
                l.startsWith("- ") || l.startsWith("* ") -> {
                    l = "&nbsp;&nbsp;• " + l.substring(2).trim() + "<br/>"
                }
                else -> {
                    l = l + "<br/>"
                }
            }

            // Bold: **text** -> <b>text</b>
            l = l.replace(Regex("""\*\*(.*?)\*\*"""), "<b>$1</b>")
            // Italic: *text* -> <i>text</i>
            l = l.replace(Regex("""\*(.*?)\*"""), "<i>$1</i>")
            // Backticks: `code` -> <font color='#38BDF8'><code>$1</code></font>
            l = l.replace(Regex("""`(.*?)`"""), "<font color='#38BDF8'><code>$1</code></font>")

            sb.append(l)
        }

        return HtmlCompat.fromHtml(sb.toString(), HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun formatActionLabel(action: QuickAction, pkg: String?): String {
        return if (action == QuickAction.OPEN_APP && !pkg.isNullOrBlank()) {
            val appName = try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (_: Throwable) {
                pkg
            }
            getString(R.string.mapper_open_app_format, appName)
        } else {
            action.getLocalizedLabel(this)
        }
    }

    private var activeEditDialog: AlertDialog? = null

    private fun showEditMappingDialog(mapping: ButtonMapping) {
        try { activeEditDialog?.dismiss() } catch (_: Throwable) {}

        val actions = QuickAction.values()
        val labels = actions.map { it.getLocalizedLabel(this) }.toTypedArray()

        val streamingActiveAction = if (mapping.appOverrides.isNotEmpty()) {
            formatActionLabel(mapping.appOverrides.values.first(), mapping.overridePkg)
        } else {
            getString(R.string.mapper_no_change)
        }

        val steps = arrayOf(
            "${getString(R.string.mapper_single_click)}: ${formatActionLabel(mapping.singleClick, mapping.singleClickPkg)}",
            "${getString(R.string.mapper_double_click)}: ${formatActionLabel(mapping.doubleClick, mapping.doubleClickPkg)}",
            "${getString(R.string.mapper_long_press)}: ${formatActionLabel(mapping.longPress, mapping.longPressPkg)}",
            "${getString(R.string.mapper_video_override)}: $streamingActiveAction"
        )

        activeEditDialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapper_dialog_title, KeyEvent.keyCodeToString(mapping.keyCode)))
            .setItems(steps) { _, stepIdx ->
                val dialogTitle = if (stepIdx == 3) {
                    getString(R.string.dialog_streaming_override_title)
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
                    .setNegativeButton(getString(R.string.btn_back)) { _, _ ->
                        showEditMappingDialog(mapping)
                    }
                    .show()
            }
            .setPositiveButton(getString(R.string.btn_save_back)) { d, _ ->
                d.dismiss()
                refreshSectionContent()
            }
            .setOnDismissListener {
                refreshSectionContent()
            }
            .show()
    }

    private fun showAppPickerDialog(mapping: ButtonMapping, stepIdx: Int) {
        val installedApps = getInstalledTvApps()
        if (installedApps.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_other_apps_installed), Toast.LENGTH_SHORT).show()
            showEditMappingDialog(mapping)
            return
        }
        val appNames = installedApps.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.mapper_select_app))
            .setItems(appNames) { _, appIdx ->
                val chosenApp = installedApps[appIdx]
                applyActionToStep(mapping, stepIdx, QuickAction.OPEN_APP, chosenApp.packageName)
            }
            .setNegativeButton(getString(R.string.btn_back)) { _, _ ->
                showEditMappingDialog(mapping)
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
        showEditMappingDialog(updated)
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
