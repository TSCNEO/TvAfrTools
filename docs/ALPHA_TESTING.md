# Prueba de la interfaz alfa

La variante `debug` se instala con el identificador `dev.custom.tvafrtools.alpha` y nombre TvAfrTools Alpha. Convive instalada con la estable; usa preferencias separadas, puerto web 8100 y puerto del demonio 15100. No comparte la configuración existente. El actualizador automático está desactivado para esta variante.

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.custom.tvafrtools.alpha/dev.custom.tvafrtools.ui.MainActivity
```

La compilación debug se firma con la clave de desarrollo local si no existe una clave de release configurada; una compilación debug procedente de otra máquina puede necesitar desinstalar la alfa anterior si las firmas difieren. No desinstalar la versión estable.

Ambas aplicaciones controlan el mismo mando, pantalla y permisos del televisor. Para evitar respuestas duplicadas, desactivar temporalmente el servicio de accesibilidad de la versión estable mientras se pruebe el de la alfa y volver a activarlo al terminar. Revisar el estado del servicio antes de probar AFR, mapeo y superposiciones. No ejecutar acciones que alteren la pantalla durante una reproducción que no se quiera interrumpir.

Comprobar con D-pad: foco inicial y navegación por las seis secciones; activar y desactivar ajustes; abrir y cerrar diálogos y paneles con Atrás; selector de Hz y restauración; HUD, recientes y mando web; permisos activos e inactivos. Revisar legibilidad, bordes de pantalla y textos largos en español e inglés. Registrar modelo y versión Android, y los problemas observados.
