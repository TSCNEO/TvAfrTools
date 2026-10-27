package dev.custom.tvafrtools.config

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

import dev.custom.tvafrtools.R

enum class QuickAction(val id: String, val label: String, val stringRes: Int) {
    NONE("none", "Sin acción (botón original)", R.string.qa_none),
    GO_HOME("go_home", "🏠 Ir a la pantalla de inicio", R.string.qa_go_home),
    RECENT_APPS_SWITCHER("recent_apps_switcher", "🗂 Ver apps abiertas y cambiar", R.string.qa_recent_apps_switcher),
    OPEN_APP("open_app", "📱 Abrir una aplicación...", R.string.qa_open_app),
    ONE_CLICK_AFR("one_click_afr", "⚡ Ajustar Hz al vídeo actual", R.string.qa_one_click_afr),
    AFR_PANEL("afr_panel", "📺 Elegir Hz manualmente", R.string.qa_afr_panel),
    STOP_AFR("stop_afr", "↩ Volver a 60 Hz normales", R.string.qa_stop_afr),
    REAL_SCREEN_TOGGLE("real_screen_toggle", "🌑 Apagar pantalla (música sonando)", R.string.qa_real_screen_toggle),
    HUD_TOGGLE("hud_toggle", "📊 Ver datos en pantalla (FPS y temp)", R.string.qa_hud_toggle),
    CLEAN_BACKGROUND_RAM("clean_background_ram", "🧹 Liberar memoria RAM", R.string.qa_clean_background_ram),
    PREVIOUS_APP("previous_app", "🔀 Volver a la app anterior", R.string.qa_previous_app),
    FORCE_STOP_FOREGROUND("force_stop_fg", "⏹ Cerrar la app actual por completo", R.string.qa_force_stop_foreground),
    RESTART_FOREGROUND("restart_fg", "🔄 Reiniciar la app actual", R.string.qa_restart_foreground),
    HDR_MODE_CYCLE("hdr_mode_cycle", "🌈 Cambiar modo HDR / SDR", R.string.qa_hdr_mode_cycle),
    AUDIO_PASSTHROUGH_CYCLE("audio_passthrough_cycle", "🔊 Cambiar audio Passthrough / Estéreo", R.string.qa_audio_passthrough_cycle),
    OPEN_NOTIFICATIONS("notifications", "🔔 Ver notificaciones", R.string.qa_open_notifications),
    MOUSE_TOGGLE("mouse_toggle", "🖱 Ratón con flechas del mando", R.string.qa_mouse_toggle),
    NIGHT_DIMMER_TOGGLE("night_dimmer", "🌙 Modo noche (bajar brillo)", R.string.qa_night_dimmer_toggle),
    SCREENSAVER_START("screensaver_start", "💤 Poner salvapantallas", R.string.qa_screensaver_start),
    SLEEP_TIMER_CYCLE("sleep_timer", "⏰ Temporizador de apagado", R.string.qa_sleep_timer_cycle),
    SYSTEM_RECENTS("system_recents", "🗂 Recientes del sistema", R.string.qa_system_recents);

    fun getLocalizedLabel(context: Context): String =
        try { context.getString(stringRes) } catch (_: Throwable) { label }

    companion object {
        fun fromId(id: String?): QuickAction =
            values().firstOrNull { it.id == id } ?: NONE
    }
}

data class ButtonMapping(
    val keyCode: Int,
    val singleClick: QuickAction = QuickAction.ONE_CLICK_AFR,
    val doubleClick: QuickAction = QuickAction.AFR_PANEL,
    val longPress: QuickAction = QuickAction.REAL_SCREEN_TOGGLE,
    val appOverrides: Map<String, QuickAction> = emptyMap(),
    val singleClickPkg: String? = null,
    val doubleClickPkg: String? = null,
    val longPressPkg: String? = null,
    val overridePkg: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("keyCode", keyCode)
        put("singleClick", singleClick.id)
        put("doubleClick", doubleClick.id)
        put("longPress", longPress.id)
        singleClickPkg?.let { put("singleClickPkg", it) }
        doubleClickPkg?.let { put("doubleClickPkg", it) }
        longPressPkg?.let { put("longPressPkg", it) }
        overridePkg?.let { put("overridePkg", it) }
        val overridesObj = JSONObject()
        appOverrides.forEach { (pkg, action) -> overridesObj.put(pkg, action.id) }
        put("appOverrides", overridesObj)
    }

    companion object {
        fun fromJson(obj: JSONObject): ButtonMapping {
            val overrides = mutableMapOf<String, QuickAction>()
            val overridesObj = obj.optJSONObject("appOverrides")
            if (overridesObj != null) {
                val keys = overridesObj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    overrides[k] = QuickAction.fromId(overridesObj.optString(k))
                }
            }
            return ButtonMapping(
                keyCode = obj.getInt("keyCode"),
                singleClick = QuickAction.fromId(obj.optString("singleClick")),
                doubleClick = QuickAction.fromId(obj.optString("doubleClick")),
                longPress = QuickAction.fromId(obj.optString("longPress")),
                appOverrides = overrides,
                singleClickPkg = obj.optString("singleClickPkg").takeIf { it.isNotBlank() },
                doubleClickPkg = obj.optString("doubleClickPkg").takeIf { it.isNotBlank() },
                longPressPkg = obj.optString("longPressPkg").takeIf { it.isNotBlank() },
                overridePkg = obj.optString("overridePkg").takeIf { it.isNotBlank() }
            )
        }
    }
}

class AppPrefs(private val context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("tvafr_prefs", Context.MODE_PRIVATE)

    private fun getBackupFiles(): List<java.io.File> {
        val list = mutableListOf<java.io.File>()
        try {
            list.add(java.io.File(context.filesDir, "button_mappings_backup.json"))
            context.getExternalFilesDir(null)?.let {
                list.add(java.io.File(it, "button_mappings_backup.json"))
            }
            list.add(java.io.File("/data/local/tmp/tvafr_button_mappings.json"))
        } catch (_: Throwable) {}
        return list
    }

    var afrEnabled: Boolean
        get() = prefs.getBoolean("afr_enabled", true)
        set(value) = prefs.edit().putBoolean("afr_enabled", value).apply()

    var fractionalFrameRate: Boolean
        get() = prefs.getBoolean("fractional_frame_rate", true)
        set(value) = prefs.edit().putBoolean("fractional_frame_rate", value).apply()

    var pauseOnSwitch: Boolean
        get() = prefs.getBoolean("pause_on_switch", true)
        set(value) = prefs.edit().putBoolean("pause_on_switch", value).apply()

    var convert25To50Hz: Boolean
        get() = prefs.getBoolean("convert_25_to_50", true)
        set(value) = prefs.edit().putBoolean("convert_25_to_50", value).apply()

    var doubleRefreshRate24To48: Boolean
        get() = prefs.getBoolean("double_24_to_48", false)
        set(value) = prefs.edit().putBoolean("double_24_to_48", value).apply()

    var autoStopAfrOnExit: Boolean
        get() = prefs.getBoolean("auto_stop_afr_on_exit", true)
        set(value) = prefs.edit().putBoolean("auto_stop_afr_on_exit", value).apply()

    var autoAfrStreamingApps: Boolean
        get() = prefs.getBoolean("auto_afr_streaming_apps", false)
        set(value) = prefs.edit().putBoolean("auto_afr_streaming_apps", value).apply()

    var volumeButtonsFix: Boolean
        get() = prefs.getBoolean("volume_buttons_fix", true)
        set(value) = prefs.edit().putBoolean("volume_buttons_fix", value).apply()

    var webRemoteEnabled: Boolean
        get() = prefs.getBoolean("web_remote_enabled", true)
        set(value) = prefs.edit().putBoolean("web_remote_enabled", value).apply()

    var doubleClickTimeoutMs: Long
        get() = prefs.getLong("double_click_timeout_ms", 320L)
        set(value) = prefs.edit().putLong("double_click_timeout_ms", value).apply()

    var longPressTimeoutMs: Long
        get() = prefs.getLong("long_press_timeout_ms", 550L)
        set(value) = prefs.edit().putLong("long_press_timeout_ms", value).apply()

    fun getStreamingAppsWhitelist(): Set<String> {
        val defaultSet = setOf(
            "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient",
            "com.amazon.amazonvideo.livingroom",
            "com.netflix.ninja",
            "org.xbmc.kodi",
            "com.plexapp.android",
            "org.jellyfin.androidtv",
            "com.wbd.stream",
            "com.HBO.max",
            "com.apple.atve.androidtv.appletv",
            "com.google.android.youtube.tv",
            "com.liskovsoft.smarttubetv.beta",
            "com.liskovsoft.smarttubetv",
            "org.smarttube.stable",
            "com.movistarplus.androidtv",
            "com.dazn",
            "studio.scillarium.ottnavigator",
            "ar.tvplayer.tv",
            "org.videolan.vlc",
            "com.brouken.player"
        )
        return prefs.getStringSet("streaming_apps_whitelist", defaultSet) ?: defaultSet
    }

    fun getMappings(): List<ButtonMapping> {
        var raw = prefs.getString("button_mappings_json", null)
        if (raw.isNullOrBlank()) {
            for (file in getBackupFiles()) {
                if (file.exists() && file.length() > 0) {
                    try {
                        val content = file.readText().trim()
                        if (content.startsWith("[") && content.endsWith("]")) {
                            raw = content
                            prefs.edit().putString("button_mappings_json", raw).apply()
                            break
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
        if (raw.isNullOrBlank()) {
            val defaults = listOf(
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_BOOKMARK,
                    singleClick = QuickAction.ONE_CLICK_AFR,
                    doubleClick = QuickAction.AFR_PANEL,
                    longPress = QuickAction.RECENT_APPS_SWITCHER
                ),
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_INFO,
                    singleClick = QuickAction.ONE_CLICK_AFR,
                    doubleClick = QuickAction.HUD_TOGGLE,
                    longPress = QuickAction.AFR_PANEL
                ),
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_PROG_BLUE,
                    singleClick = QuickAction.ONE_CLICK_AFR,
                    doubleClick = QuickAction.AFR_PANEL,
                    longPress = QuickAction.REAL_SCREEN_TOGGLE
                ),
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_PROG_RED,
                    singleClick = QuickAction.PREVIOUS_APP,
                    doubleClick = QuickAction.RECENT_APPS_SWITCHER,
                    longPress = QuickAction.FORCE_STOP_FOREGROUND
                ),
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_PROG_GREEN,
                    singleClick = QuickAction.HUD_TOGGLE,
                    doubleClick = QuickAction.HDR_MODE_CYCLE,
                    longPress = QuickAction.CLEAN_BACKGROUND_RAM
                ),
                ButtonMapping(
                    keyCode = KeyEvent.KEYCODE_SETTINGS,
                    singleClick = QuickAction.ONE_CLICK_AFR,
                    doubleClick = QuickAction.RECENT_APPS_SWITCHER,
                    longPress = QuickAction.AFR_PANEL
                )
            )
            saveMappings(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<ButtonMapping>()
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(ButtonMapping.fromJson(obj))
                } catch (_: Throwable) {}
            }
            if (list.isNotEmpty()) list else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMappings(mappings: List<ButtonMapping>) {
        val arr = JSONArray()
        mappings.forEach { arr.put(it.toJson()) }
        val jsonStr = arr.toString()
        prefs.edit().putString("button_mappings_json", jsonStr).apply()
        try {
            for (file in getBackupFiles()) {
                file.parentFile?.mkdirs()
                file.writeText(jsonStr)
            }
        } catch (_: Throwable) {}
    }

    fun upsertMapping(mapping: ButtonMapping) {
        val current = getMappings().toMutableList()
        val idx = current.indexOfFirst { it.keyCode == mapping.keyCode }
        if (idx >= 0) {
            current[idx] = mapping
        } else {
            current.add(0, mapping)
        }
        saveMappings(current)
    }

    fun removeMapping(keyCode: Int) {
        val current = getMappings().filterNot { it.keyCode == keyCode }
        saveMappings(current)
    }
}
