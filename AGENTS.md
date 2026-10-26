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
