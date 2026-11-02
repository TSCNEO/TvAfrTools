# 📺 TvAfrTools

[![Última Versión](https://img.shields.io/github/v/release/TSCNEO/TvAfrTools?color=38BDF8&label=Versión&display_name=tag&logo=github)](https://github.com/TSCNEO/TvAfrTools/releases/latest)
[![Plataforma](https://img.shields.io/badge/Plataforma-Android%20TV%20%7C%20Google%20TV-22C55E?logo=android)](https://github.com/TSCNEO/TvAfrTools)
[![Versión Android](https://img.shields.io/badge/Android%20TV-9.0%20a%2014-F59E0B)](https://github.com/TSCNEO/TvAfrTools)
[![Licencia: GPL-3.0](https://img.shields.io/badge/Licencia-GPL--3.0-blue)](LICENSE)
[![GitHub Issues](https://img.shields.io/github/issues/TSCNEO/TvAfrTools)](https://github.com/TSCNEO/TvAfrTools/issues)

**TvAfrTools** es una suite gratuita, nativa y de código abierto para **Android TV y Google TV** (Android 9–14). La empecé porque me cansé de no tener ajuste automático de frecuencia (AFR) en servicios como **Disney+ y DAZN**: cuando los fotogramas del vídeo y los Hz de la tele no coinciden, los paneos pueden verse a tirones. La app intenta detectar los fps del vídeo y ajustar la pantalla, incluso con ciertos vídeos protegidos por Widevine L1. El resultado depende del dispositivo y de cada aplicación.

> 🇬🇧 **[Read this guide in English](README.md)**

---

<p align="center">
  <img src="app/src/main/res/drawable-xhdpi/ic_tv_banner.png" alt="TvAfrTools Banner" width="560"/>
</p>

---

## Novedades en 1.3.3

- Corregida la identificación de la app en Android 14 y la asociación del códec cuando los fps iniciales difieren de los reales.
- Conserva los fps medidos ante cambios de buffers, pausas y resolución en Prime; admite el arranque a 30 fps de Jellyfin.
- Mejorada la detección inicial del HUD y añadidos registros de decisiones AFR.
- [Notas e instrucciones de actualización](docs/releases/v1.3.3.md).

## Novedades en 1.3.2

- Nueva lectura del decodificador Amlogic vinculada a la app para distinguir 24,000/23,976 fps, también en reproducción protegida cuando está disponible.
- Se mantiene mientras avanzan los fotogramas; se conservan timestamps y estimaciones como respaldo.
- [Notas e instrucciones de actualización](docs/releases/v1.3.2.md).

## Novedades en 1.3.1

- Se distinguen 24,000/23,976, 30/29,97 y 60/59,94 fps medidos al elegir los Hz de pantalla.
- Los timestamps del vídeo tienen prioridad sobre las estimaciones. El HUD marca los fps estimados con **≈**.
- La preferencia fraccional solo afecta a estimaciones y respeta las frecuencias medidas.
- [Notas completas de la versión](docs/releases/v1.3.1.md).

## Novedades en 1.3.0

- **Interfaz renovada para TV:** navegación lateral con iconos, foco más visible, cuadrículas compactas y mando web actualizado.
- **Selector de frecuencia más claro:** modos agrupados por resolución, frecuencias habituales primero y acceso a otras resoluciones y opciones adicionales.
- **Pantalla y reproducción:** se guarda la configuración anterior antes de aplicar AFR, también ante reinicios del motor o actualizaciones; un respaldo evita esperar indefinidamente para reanudar tras el cambio HDMI. El resultado puede depender del dispositivo y de la aplicación de vídeo.
- **Rescate de pantalla:** en Información y en las opciones del selector se pueden eliminar los límites de frecuencia que hayan quedado bloqueados, sin imponer 50 ni 60 Hz.
- **Detalles de uso:** HUD más compacto, avisos de cambio más legibles, espera de reanudación vinculada a la pausa y textos de restauración más claros.

Antes de actualizar desde una versión anterior, sal de la reproducción y vuelve al inicio de la TV para que AFR libere el modo de pantalla activo. Después usa **Información → Buscar actualizaciones**.

---

## ✨ Funcionalidades Principales

### 🎬 1. Auto Frame Rate (AFR) Universal sin Tirones (*Judder-Free*)
- **Sincronización exacta de Hz**: Detecta en tiempo real los fotogramas del vídeo (23.976p, 24.0p, 25p, 50p, 59.94p, 60p) y conmuta la frecuencia de refresco HDMI de tu TV para eliminar por completo los tirones (*judder*) al reproducir películas o series.
- **Compatible con Túnel Protegido Widevine L1**: Funciona incluso con vídeo protegido por DRM en apps de streaming oficial (**Netflix, Disney+, Prime Video, DAZN, Tivimate, Kodi**, etc.) mediante arquitectura híbrida *SurfaceFlinger* + Demonio ADB local.
- **Restauración de pantalla**: Al cambiar de aplicación o volver al inicio, intenta recuperar la configuración anterior sin imponer 60 Hz.
- **Selector Manual Rápido**: Panel flotante interactivo para forzar cualquier frecuencia con las flechas del mando a distancia.

### ⚡ 2. Activación Autónoma por ADB Local (Sin PC ni Móvil)
- **Activación en 1 Clic**: Incluye su propio motor ADB interno (`127.0.0.1:5555`).
- Permite conceder permisos avanzados (`WRITE_SECURE_SETTINGS`, estadísticas de uso, etc.) directamente desde la TV sin tener que conectar ningún ordenador ni móvil.
- Compatible con el desbloqueo automático de **Ajustes Restringidos** en Android 13 y 14.

### 🎮 3. Mapeador de Botones del Mando con Anti-Rebote
- **Acciones personalizadas**: Asigna funciones a 1 Clic, Doble Clic o Pulsación Larga.
- **Acciones específicas en vídeo**: Posibilidad de configurar que un botón haga algo distinto mientras reproduces vídeo a pantalla completa.
- **Filtro Anti-Rebote (Debounce 220ms)**: Evita los molestos dobles clics accidentales provocados por mandos Bluetooth o por el retorno HDMI-CEC en televisores Samsung o LG.

### 🗂 4. Multitarea Flotante y Liberador Profundo de RAM
- **Selector de Apps Recientes**: Soluciona la falta de botón de multitarea en Android TV / Google TV o al usar launchers de terceros como Projectivy Launcher.
- **Liberación de Memoria Real**: Escanea los procesos activos en segundo plano (`ps -A`), los finaliza forzosamente (`am force-stop` y nivel SDK) y mide en tiempo real los MB de memoria RAM liberados.
- **Cambio rápido o cierre**: Pulsa `OK` para ir a una app, o mantén pulsado `OK` para cerrarla por completo.

### 🌑 5. Apagado Real de Pantalla (Modo Música)
- Apaga la pantalla del televisor mientras el sonido continúa reproduciéndose.
- Ideal para escuchar **Spotify**, **YouTube**, radio o podcasts en televisores OLED o LED sin riesgo de quemados y ahorrando energía. Al pulsar cualquier tecla del mando, la pantalla vuelve al instante.

### 📊 6. HUD Técnico en Pantalla
- Panel flotante con información técnica en tiempo real:
  - Resolución HDMI y Hz actuales.
  - FPS reales del vídeo y motor de renderizado.
  - Temperatura del procesador (SoC) en °C.
  - Consumo y velocidad de red (Kb/s, Mb/s).
  - Memoria RAM libre y total.

### 🌐 7. Mando a Distancia Web desde el Móvil
- Servidor web ultraligero integrado (puerto `8099`).
- Abre la IP de tu TV en el navegador de tu móvil o tablet para navegar o **pegar contraseñas largas y enlaces** cómodamente con el teclado del móvil.

### 🔄 8. Actualizador Automático desde GitHub
- Comprueba si hay nuevas versiones publicadas en GitHub Releases.
- Descarga e instala la actualización directamente en tu televisor sin necesidad de volver a pasar archivos por pendrive ni Downloader.

---

## 📺 Dispositivos Compatibles

- **Sistema Operativo**: Android TV / Google TV 9.0 (API 28) hasta Android 14 (API 34).
- **Plataformas Soportadas**: **Exclusivamente Android TV y Google TV estándar / certificado (Android 9.0 a 14)**.
- **Dispositivos que he probado personalmente**:
  - Homatics Box R 4K Plus.
  - Xiaomi TV Box 3rd Gen.
- La compatibilidad puede variar en otros modelos. Si la pruebas, cuéntame el modelo, la versión de Android TV y la aplicación usada.
- ⛔ **Amazon Fire TV / Fire OS NO es compatible**:
  - Amazon bloquea por firmware las conexiones ADB locales (`127.0.0.1:5555` - CVE-2024-27350), impidiendo la activación autónoma en 1 clic.
  - Fire OS oculta los servicios de accesibilidad de terceros en los ajustes del sistema.
  - La pila de vídeo de Amazon y sus procesadores MediaTek bloquean el cambio forzado de Hz (AFR) en apps de streaming externas.

---

## 🚀 Instalación y Puesta en Marcha

1. **Descarga el APK**:
   - Enlace directo permanente (sin versión en el nombre, siempre sirve la última versión estable):

     ```
     https://github.com/TSCNEO/TvAfrTools/releases/latest/download/TvAfrTools.apk
     ```
   - **Desde la app Downloader del televisor**: introduce el código **7763887** (enlace corto: [aftv.news/7763887](https://aftv.news/7763887)). Ese código apunta siempre a la última versión, así que te sirve para todas las futuras.
   - No uses nunca un enlace con la versión en el nombre (por ejemplo `TvAfrTools-v1.2.33.apk`): deja de funcionar en cuanto se publica la siguiente versión.
2. **Instala el APK**: ábrelo y confirma la instalación. Si Android la bloquea, permite **Instalar aplicaciones desconocidas** para Downloader o para la app desde la que abres el archivo.
3. **Prepara ADB y activa los permisos**:
   - Activa las **Opciones de desarrollador** del televisor (normalmente pulsando varias veces en **Compilación**, dentro de **Ajustes → Información**) y después activa **Depuración USB**. No necesitas conectar ningún cable USB.
   - La app arranca el demonio privilegiado mediante ADB local en el puerto **5555**. Algunos dispositivos también requieren activar **Depuración por red / ADB por red**; la depuración USB por sí sola puede no abrir ese puerto. Los nombres de los ajustes dependen del fabricante.
   - Abre TvAfrTools en la tele.
   - Pulsa en **"Activar automáticamente en 1 clic"**.
   - Si sale el mensaje en pantalla *"¿Permitir depuración USB?"*, marca *"Permitir siempre"* y pulsa **OK**.
4. **Comprueba que el motor está listo**: en **Información**, verifica que el servicio de accesibilidad aparece **Activo y vinculado**, la superposición **Concedido** y el motor en segundo plano está activo.
5. **Elige dónde usar AFR**: entra en **Apps AFR** y activa las aplicaciones que quieras, por ejemplo Disney+ o DAZN. **La lista empieza vacía: si no seleccionas ninguna app, el AFR automático no actuará.** En los ajustes AFR, deja activado **Ajuste automático de Hz al reproducir vídeo**.
6. **Prueba una reproducción**: abre una de las apps seleccionadas y reproduce un vídeo. El cambio de señal HDMI puede dejar la pantalla negra unos instantes. Comprueba los Hz con el HUD o con la información de señal del televisor; si la pantalla ya estaba en una frecuencia adecuada, puede no haber cambio. Al volver al inicio, la app intentará restaurar la configuración anterior.

### Si algo no funciona

- **No aparece la autorización ADB o el motor no arranca**: comprueba la depuración USB y, si existe, la depuración por red. Vuelve a pulsar la activación y acepta el diálogo en la TV. El arranque automático requiere que el dispositivo permita conexiones ADB locales al puerto **5555**; si el fabricante no lo habilita, activar la depuración USB puede no bastar.
- **Accesibilidad inactiva**: vuelve a intentar la activación automática. Puedes comprobar el servicio en **Ajustes → Accesibilidad**; en Android 13/14 puede haber restricciones para apps instaladas fuera de la tienda.
- **El motor está activo pero no cambian los Hz**: revisa **Apps AFR**, el interruptor de ajuste automático y que el televisor admita la frecuencia deseada. Prueba el selector manual para distinguir un problema de detección de uno de cambio de pantalla. La detección puede variar según el dispositivo y la app de vídeo.
- **La pantalla queda limitada a una frecuencia tras salir**: usa **Información → Rescatar pantalla (quitar límites de Hz)**.
- **Tras reiniciar deja de funcionar**: abre TvAfrTools, comprueba el estado del motor y repite la activación si hace falta.

Si sigue fallando, abre un issue con el modelo, la versión de Android, la versión de TvAfrTools, la app de vídeo y el estado de los permisos y del motor.

---

## 🤝 Soporte y Reporte de Fallos

¿Has encontrado algún fallo o tienes alguna idea para mejorar la app?
- 🐛 **Reportar un Error**: [Abrir un Issue en GitHub](https://github.com/TSCNEO/TvAfrTools/issues/new?template=bug_report.md)
- 💡 **Sugerir Funcionalidades**: [Proponer una Mejora](https://github.com/TSCNEO/TvAfrTools/issues/new?template=feature_request.md)
- ⭐ Si te resulta útil el proyecto, ¡dale una estrella en GitHub!

## 📜 Licencia

TvAfrTools es software libre distribuido bajo la **GNU General Public License v3.0** (GPL-3.0). Puedes usarlo, estudiarlo, modificarlo y redistribuirlo libremente. Si distribuyes una versión modificada, debes publicar su código fuente bajo la misma licencia. El texto completo está en [LICENSE](LICENSE).

Copyright (C) 2026 The TvAfrTools contributors.
