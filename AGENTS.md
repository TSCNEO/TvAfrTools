# TvAfrTools — Contexto del Proyecto y Manual de Agentes

## 1. Visión General del Proyecto
**TvAfrTools** (`dev.custom.tvafrtools`) es una aplicación nativa para **Android TV / Google TV (Android 9 a 14)** diseñada para proporcionar capacidades avanzadas de hardware y control que habitualmente están bloqueadas o son exclusivas de aplicaciones de pago (como tvQuickActions Pro):
1. **Auto Frame Rate (AFR) Híbrido Universal**: Detección en tiempo real de fotogramas (23.976p, 24.0p, 25p, 50p, 59.94p, 60p) y conmutación de frecuencia de refresco HDMI sin tirones (*judder*).
2. **Soporte para Túnel Widevine L1 (Cine Protegido)**: Disney+, Prime Video, Netflix y DAZN reproducen vídeo a través de decodificadores seguros por hardware (`OMX.amlogic` / `c2.amlogic` / túnel HDCP). TvAfrTools utiliza una arquitectura dual (*SurfaceFlinger + Demonio ADB privilegiado local + AfrOverlay*) para forzar el handshake de refresco sin romper la decodificación protegida.
3. **Arranque Autónomo de Demonio ADB (Sin PC / Sin Móvil)**: La app incluye un cliente ADB interno (`127.0.0.1:5555`) que inyecta su propio proceso demonio con permisos elevados (`android.permission.WRITE_SECURE_SETTINGS`, `SET_VOLUME_KEY_LONG_PRESS_LISTENER`, etc.).
4. **Filtrado Anti-Rebote y Mapeo de Mandos**: Interceptación limpia de eventos de accesibilidad con debounce de hardware (220ms) para evitar problemas de eco HDMI-CEC con televisores Samsung/LG y mandos Bluetooth.
5. **Interfaz Master-Detail Nativa Android TV**: Diseño de 2 paneles (menú lateral *rail* + panel de tarjetas con foco ámbar cinematográfico) adaptado 100% al control con mando a distancia (D-Pad).

---

## 2. Entorno y Dispositivos de Prueba
- **Dispositivo Principal**: Homatics Box R 4K Plus (`SEI804HM`, Android 14 / SDK 34, Amlogic S905X4-K, `armeabi-v7a`).
  - IP ADB: `192.0.2.10:5555`
  - Mando: Bluetooth B21 Keyboard + Homatics Remote.
  - Launcher: **Projectivy Launcher** (`io.freemytv`).
- **Dispositivo Secundario del Usuario**: Xiaomi Mi TV Box (`MiTV-AFMU0`, Android 14 / SDK 34, `armeabi-v7a`).
  - IP ADB: `192.0.2.11:5555`
  - Estado: Instalado y con permisos activos (`TvAfrTools-v1.1-android14.apk`).
- **Televisor Conectado**: Samsung 4K (`QBQ90S`) con Anynet+ (HDMI-CEC).
  - *Nota Importante CEC*: `hdmi_control_controlled_by_tv` debe mantenerse en `0` o filtrado con el debounce de accesibilidad para evitar dobles clics de mando enviados simultáneamente por BT y CEC.

---

## 3. Estructura de Directorios y Archivos Clave
- **Raíz del Repositorio**: `/Users/user/proyectos/tvQuickActions/TvAfrTools/`
- **Componentes de la App**:
  - `dev.custom.tvafrtools.ui.MainActivity.kt`: Interfaz Master-Detail en 2 paneles (AFR, Mapeo de Mando, Acciones Rápidas, Mando Web, Estado & Demonio).
  - `dev.custom.tvafrtools.afr.AfrController.kt`: Conmutación de resolución y modos de refresco (`Display.Mode`), algoritmos de redondeo fraccional (23.976p) y fallback dual-punch (`AfrOverlay` 1x1 + `cmd display set-user-preferred-display-mode`).
  - `dev.custom.tvafrtools.overlay.OverlayToolkit.kt`:
    - Menú flotante interactivo de selección manual de frecuencia (`openAfrPanel`). **Incluye botón rápido "Revertir a Frecuencia Estándar (60Hz)" justo debajo de la opción Auto**.
    - HUD técnico en tiempo real (`toggleHud`): muestra resolución, Hz de pantalla, FPS reales de vídeo, temperatura del SoC y consumo de red.
    - Multitarea flotante y limpiador de RAM (`toggleRecentsPanel`).
  - `dev.custom.tvafrtools.service.TvAfrAccessibilityService.kt`: Servicio de accesibilidad que detecta cambios de app en primer plano, pulsaciones de botones físicos de mando (1 clic, doble clic, pulsación larga) y restaura 60Hz inmediatamente al volver al Launcher (`CATEGORY_HOME` o Projectivy).
  - `dev.custom.tvafrtools.server.PrivilegedServer.kt` & `ServerClient.kt`: Servidor local con socket Unix/TCP para comandos de sistema, lectura de Sysfs, fps de túnel y cambio de parámetros display.
  - `dev.custom.tvafrtools.adb.LocalAdbBootstrap.kt`: Cliente ADB autónomo que se conecta a `127.0.0.1:5555` para elevar permisos del demonio automáticamente al abrir la app.
  - `dev.custom.tvafrtools.web.WebRemoteServer.kt`: Micro-servidor HTTP en puerto `8080` que sirve una interfaz web táctil para controlar la TV desde el móvil o escribir contraseñas.

---

## 4. Recursos Visuales y Estilo de Diseño
- **Paleta Home Cinema (Ámbar Cálido & Titanio)**:
  - Fondo: `#111216`
  - Barra lateral: `#0C0D10`
  - Tarjetas inactivas: `#191B22` (borde `#282B35`)
  - Elemento con foco: Píldora redondeada `#F59E0B` o `#F8FAFC` con texto oscuro `#0F1014` para contraste óptimo.
- **Logotipos y Banners**:
  - `drawable/ic_tv_banner.png`: Banner oficial Leanback (320x180) para el launcher de Android TV.
  - `drawable/ic_app_logo.png`: Icono de pantalla de bienvenida y cabecera.
  - `mipmap-*/ic_launcher.png`: Iconos adaptativos estándar.

---

## 5. Control de Versiones (Git Workflow)
El proyecto sigue **Versionado Semántico (SemVer)**:
- `v1.0.0`: Versión inicial funcional (AFR híbrido, túnel Widevine L1, demonio ADB autoarrancable, servidor web, debounce CEC).
- `v1.1.0`: Rediseño completo de la interfaz Master-Detail Leanback TV, paleta cinematográfica grafito/ámbar y botón directo de reversión inmediata a 60Hz bajo la opción Auto en el selector flotante.

Para futuras versiones:
- **Corrección de bugs**: `1.1.x`
- **Nuevas funciones**: `1.x.0`
- **Cambios mayores de arquitectura**: `x.0.0`

### Versión 1.2.0 (Release)
- **Mapeo de Botón ampliado a Navegación y Apps**:
  - `🏠 Ir al Inicio / Home (Launcher)`: Ejecución nativa instantánea vía `performGlobalAction(GLOBAL_ACTION_HOME)`.
  - `🗂 Apps Recientes del Sistema`: Selector nativo de Android TV / Google TV vía `performGlobalAction(GLOBAL_ACTION_RECENTS)`.
  - `📱 Abrir una Aplicación instalada...`: Al seleccionarlo en el menú de asignación, analiza automáticamente todas las aplicaciones del televisor (Disney+, Netflix, Prime, YouTube, Kodi, Plex, Tivimate, etc.) y permite vincular el botón directamente a esa app.
  - Se muestra el nombre real de la aplicación vinculada en la tarjeta del botón (`• 1 Clic : 📱 Abrir app: YouTube`).

### Versión 1.2.1 (Bugfix - Foco de Projectivy Launcher y Coexistencia de Servicios)
- **Causa del problema**: Al activarse los permisos por ADB o al iniciar el demonio, el script ejecutaba `settings put secure enabled_accessibility_services $accService`, lo cual sobreescribía la lista de servicios de accesibilidad activos del sistema y deshabilitaba silenciosamente el servicio `io.freemytv/io.freemytv.RemoteKeyService` ("Free My TV", servicio propio de Projectivy Launcher). Sin este servicio, el motor de navegación de Projectivy dejaba de recibir eventos de teclas y su cursor/resaltado visual de selección desaparecía (aunque el sistema siguiera navegando a ciegas).
- **Solución implementada**:
  1. `LocalAdbBootstrap.kt`: Ahora realiza un `append` condicional preservando todos los servicios de accesibilidad existentes (`cur:dev.custom...`), impidiendo desactivar Projectivy ni ninguna otra app instalada.
  2. `TvAfrAccessibilityService.kt`: En `launchPackage`, si la app destino es el launcher (Projectivy `io.freemytv` o launcher por defecto), se despacha de forma segura mediante `performGlobalAction(GLOBAL_ACTION_HOME)` nativo en lugar de recrear la tarea como app estándar.
  3. Se ha restaurado y verificado en Homatics que ambos servicios están `Bound` y activos al mismo tiempo.

### Versión 1.2.2 (Multitarea Universal y Detección de Apps Recientes)
- **Causa del problema**:
  - En Android TV (Homatics, Xiaomi Mi Box, Google TV), el sistema operativo carece del selector estándar de aplicaciones recientes de móviles/tablets cuando se usa un launcher de terceros (o `GLOBAL_ACTION_RECENTS` no hace nada).
  - La app base `tvQuickActions` resuelve esto prescindiendo del recents nativo de Android e implementando su propio gestor de aplicaciones recientes (`AbstractC0281c.f4637k` + `W1.H` + `UsageStatsManager`).
  - En versiones anteriores de `TvAfrTools`, la consulta dependía únicamente de `ActivityTaskManager.getTasks()`, que en Android 13/14 solo lista tareas con ventanas activas en ese instante (Running Tasks), arrojando 0 resultados para apps en segundo plano o suspendidas.
- **Solución implementada replicando fielmente la arquitectura de la app base**:
  1. `RecentAppsManager.kt`:
     - **Seguimiento en vivo en tiempo real**: `TvAfrAccessibilityService` intercepta los eventos `TYPE_WINDOW_STATE_CHANGED` y actualiza una lista concurrente ordenada por uso reciente (`liveRecentPackages`), idéntica a `f4637k` en `tvQuickActions`.
     - **Filtro de exclusión**: Omite launchers (`Intent.CATEGORY_HOME`, Projectivy `io.freemytv`), `SystemUI`, asistentes y daemons OEM (`EXCLUDED_PACKAGES`).
     - **Persistencia histórica**: Al iniciar el servicio o reiniciar el dispositivo, consulta `UsageStatsManager.queryUsageStats()` (últimas 48 horas) para reconstruir el historial antes de que se abran nuevas apps.
     - **Extracción de dumpsys recents**: El demonio privilegiado parsea los componentes de tareas recientes (`dumpsys activity recents`), asegurando 100% de cobertura en cualquier versión de Android.
     - **Resolución de banners e iconos**: Replicación de `W1.H` obteniendo los banners Leanback oficiales (`getApplicationBanner`, `getActivityBanner`) e iconos adaptativos para cada tarjeta.
  2. `OverlayToolkit.kt`:
     - Menú de multitarea rediseñado con iconos oficiales a 24dp, nombres amigables de aplicaciones e indicador `[EN PANTALLA]` para la app activa.
     - Conmutación instantánea al pulsar `OK` y detención forzada (`force-stop`) con pulsación larga de `OK`.
     - Botón superior "🧹 Liberar RAM" para matar apps en 2º plano conservando la actual.
  3. `AndroidManifest.xml` & `LocalAdbBootstrap.kt`:
     - Declarados permisos `PACKAGE_USAGE_STATS`, `GET_TASKS`, `KILL_BACKGROUND_PROCESSES` y auto-otorgamiento de `appops set dev.custom.tvafrtools GET_USAGE_STATS allow`.

### v1.2.3: In-App GitHub Releases Updater, Release Keystore & Redesigned Icons / Banners
- **GitHub Repository**: [https://github.com/TSCNEO/TvAfrTools](https://github.com/TSCNEO/TvAfrTools) (Public).
- **Automated CI/CD**: `.github/workflows/release.yml` triggers on tag push (`v*`) and publishes the release with the signed APK.
- **Official Keystore**: `keystore/release.jks` with alias `tvafrtools` configured in `app/build.gradle.kts`.
- **In-App Auto-Updater (`UpdateManager.kt`)**:
  - Connects to GitHub API `https://api.github.com/repos/TSCNEO/TvAfrTools/releases/latest`.
  - Performs semantic version check.
  - Streaming download with live percentage callback.
  - Dual-mode install:
    - **Privileged Silent Install**: Via `PrivilegedServer` command `INSTALL_APK /path/to/apk` (`pm install -r -d`) - fully unattended on TV without dialogs.
    - **Native Fallback**: Via `FileProvider` (`dev.custom.tvafrtools.fileprovider`) and `ACTION_VIEW`.
- **Icon & Banner Redesign**:
  - Replaced squashed asset with 16:9 Leanback banner (`ic_tv_banner.png`, 320x180 & 640x360).
  - Added full mipmap suite (`ic_launcher.png`, `ic_launcher_round.png`) from mdpi (48px) to xxxhdpi (192px).
  - Explicitly registered `android:banner`, `android:icon`, and `android:roundIcon` in `AndroidManifest.xml`.

### v1.2.4: Lenguaje Claro, Simplificación de UI y Test de Actualizador
- **Textos Humanos y Claros**: Eliminado vocabulario técnico excesivo (LLM-style). Descripciones directas orientadas al usuario de TV ("Ajuste de Hz", "Evita tirones", "Apagar pantalla mientras suena música", "Control desde el móvil").
- **Acciones y Diálogos Simplificados**: Opciones del mapeador de mando reducidas a términos sencillos ("1 Clic", "2 Clics rápidos", "Mantener pulsado", "Acción diferente dentro de apps de vídeo").
- **Validación del Autoupdater**: Publicación de release `v1.2.4` en GitHub con APK firmado para probar el flujo de actualización completa directamente desde el televisor.

### v1.2.5: Activación Autónoma de Permisos y Compatibilidad Android 13/14 (Restricted Settings)
- **Bypass de Ajustes Restringidos (Android 13/14)**:
  - Añadido `appops set dev.custom.tvafrtools ACCESS_RESTRICTED_SETTINGS allow` y `appops set dev.custom.tvafrtools 119 allow` en `LocalAdbBootstrap.kt`.
  - En Android 13+, las APKs instaladas fuera de Google Play tienen bloqueada la activación de accesibilidad (`Skipping enabling service disallowed by device admin policy`). Este bypass reactiva el servicio automáticamente.
- **Asistente Visual de Permisos en la App (`MainActivity.kt`)**:
  - Banner interactivo en la cabecera cuando el servicio de accesibilidad está inactivo.
  - Botón "⚡ Activar automáticamente en 1 clic": Conecta al ADB interno, guía al usuario si aparece el diálogo "¿Permitir depuración USB?" en la TV ("Permitir siempre"), y arranca el motor.
  - Botón directo para "Abrir Accesibilidad" y guía detallada para desbloquear "Ajustes restringidos" con acceso directo a la información de la aplicación (`ACTION_APPLICATION_DETAILS_SETTINGS`).
- **Robustez de Red ADB Interna**:
  - Detección multihost en `LocalAdbBootstrap`: prueba `127.0.0.1`, `localhost` y la IP WiFi local del dispositivo.
  - Polling activo tras el inicio del daemon para verificar la conexión en tiempo real sin requerir comandos desde el PC.

### v1.2.6: Robustez de Demonios Privilegiados y Clarificación de UI Android TV
- **Prevención de Caídas por Timeout**: Protegido `handleClient` en `PrivilegedServer.kt` con captura global de excepciones por sockets o timeouts para evitar caídas del demonio en segundo plano (`SocketTimeoutException`).
- **Clarificación de Ajustes Restringidos**: En Android TV no existe el menú de 3 puntos de los móviles. Se actualizó el diálogo explicativo informando de que la activación es 100% automática a través de la opción "⚡ Activar automáticamente".

### v1.2.7: Ajuste de Compatibilidad y Optimización de Permisos
- **Diagnóstico de Compatibilidad Fire OS**:
  - Se confirmó que los dispositivos Fire TV 4K de 1ª generación (`AFTMM` - 2018) ejecutan **Fire OS 6** (basado en **Android 7.1.2 / API 25**).
  - La aplicación requiere `minSdk = 28` (Android 9.0 Pie) por el uso intensivo de APIs modernas de Display (`Display.Mode`), `ActivityTaskManager` y arquitectura de superposiciones segura de Android 9+. Dispositivos con Fire OS 6 arrojan error de análisis de sintaxis al instalar (`There was a problem parsing the package`).
  - Compatible oficialmente a partir de **Android 9.0 hasta Android 14** (incluyendo Fire TV Stick 4K Max 2ª gen y Fire TV Cube 3ª gen con Fire OS 8 / Android 11+).

### v1.2.8: Motor Dual de Liberación de RAM y Sincronización de Multitarea
- **Persistencia Desasociada del Demonio ADB Local**:
  - En `LocalAdbBootstrap.kt`, el comando de arranque del demonio privileged ahora invoca `/system/bin/setsid` (`nohup /system/bin/setsid app_process ... </dev/null >... 2>&1 &`). Esto independiza el proceso con `PPID 1` (`init`), evitando que `adbd` termine el subshell al cerrar la conexión TCP del cliente ADB local.
- **Doble Capa de Cierre de Procesos y Escaneo Real de RAM**:
  - Detección en vivo de procesos en segundo plano mediante escaneo de subprocesos de usuario (`ps -A | grep u0_a...`).
  - Terminación dual: `ActivityManager.killBackgroundProcesses(pkg)` universal (nivel SDK) combinado con `am force-stop` a nivel demonio privilegiado.
  - Medición dinámica de incremento de memoria RAM disponible (`memInfo.availMem`) informando al usuario de los megabytes recuperados en tiempo real.
- **Sincronización Inmediata de la Multitarea**:
  - Al pulsar "🧹 Liberar RAM", `RecentAppsManager.onRamCleaned()` purga inmediatamente todos los paquetes en segundo plano de la memoria activa, conservando únicamente la aplicación en pantalla.
  - Al realizar pulsación prolongada sobre una aplicación concreta, se invoca `removePackage(pkg)` para eliminarla de la vista flotante y finalizarla en el sistema.
  - Se suprimió la consulta recurrente de `UsageStatsManager` a las 48 horas previas durante la ejecución en vivo, restringiéndola exclusivamente a la precarga en frío (`loadInitialRecents`).

---

### v1.2.9: Soporte Bilingüe Completo (Inglés / Español) e Internacionalización
- **Arquitectura de Recursos Nativos Android (i18n)**:
  - `res/values/strings.xml`: Recursos base por defecto en **Inglés** (para la comunidad internacional en GitHub y Reddit).
  - `res/values-es/strings.xml`: Recursos completos en **Español** para dispositivos configurados en idioma español.
  - Simetría del 100% de claves (197 cadenas traducidas) cubriendo interfaz principal (Master-Detail UI), selector de Hz manual, multitarea, limpiador de RAM, avisos flotantes (banners HUD), diálogo del instalador de actualizaciones y mapeador de botones.
- **Localización Dinámica de Enums y Botones**:
  - `QuickAction` en `AppPrefs.kt` implementa `getLocalizedLabel(context: Context)` vinculada a recursos `@string/qa_*`.
  - Mapeo de botones del mando muestra acciones y secuencias (1 clic, doble clic, pulsación larga) en el idioma correspondiente del sistema operativo.

## 6. Política de Plataformas y Exclusión de Fire OS
- **Plataformas Soportadas Oficialmente**: **Únicamente Android TV y Google TV estándar / puros (Android 9.0 a 14 / APIs 28 a 34)**.
  - Ejemplos: Xiaomi Mi TV Box S, Homatics Box R 4K Plus, Chromecast con Google TV, NVIDIA Shield, Nokia Streaming Box, etc.
- **Exclusión Definitiva de Amazon Fire TV (Fire OS)**:
  - **No se da soporte a Fire OS**: Amazon opera un fork fuertemente cerrado y modificado de Android que bloquea activamente los pilares de la app:
    1. *Bloqueo de Loopback ADB (CVE-2024-27350)*: Impide la activación autónoma en el dispositivo (`127.0.0.1:5555 Broken pipe`).
    2. *Ocultación de Accesibilidad*: Fire OS no permite activar servicios de accesibilidad de terceros desde sus menús.
    3. *Pila de Pantalla Cerrada*: Utiliza controladores propietarios en chips MediaTek que rechazan el cambio forzado de frecuencia de refresco (`Display.Mode` / AFR) desde procesos en segundo plano.
  - En toda la documentación y comunicaciones públicas se aclara que la app es exclusivamente para Android TV / Google TV.
