# TvAfrTools — sistema de diseño v1

**Estado: APROBADO por el usuario el 1 de noviembre de 2026.** Este documento es la fuente de verdad visual; cualquier cambio de identidad requiere aprobación explícita y registro aquí. La escala y zonas seguras se ajustarán tras validación en TV real. Si falta una regla, proponer su ampliación antes de implementarla. Las excepciones deben explicar su necesidad y recibir aprobación. Reutilizar tokens y componentes antes de crear otros. Este documento describe una dirección, no afirma que ya esté implementada.

## Diagnóstico basado en el repositorio

`MainActivity.kt` construye vistas Android tradicionales y un master-detail con seis secciones: AFR, aplicaciones AFR, mapeo, acciones, mando web y sistema. `OverlayToolkit.kt` contiene selector de frecuencia, HUD y recientes. Hay recursos de marca y textos inglés/español. El código y los recursos conservan grafito, ámbar y navegación lateral: son una base apropiada para un instrumento de control del cine doméstico. El foco inicial se solicita en AFR.

Los colores están repartidos entre XML y valores literales; superposiciones usan acentos cian y verde diferentes del ámbar. Hay tamaños de 10,5–14 sp en elementos de TV y emojis en navegación y estados, cuya apariencia varía según el dispositivo. Esos hechos sugieren riesgo de legibilidad y coherencia; no sustituyen una evaluación visual en televisor. Los diálogos de sistema y overlays necesitan una gramática común. No se han inspeccionado capturas ni probado foco con mando en esta auditoría.

## Dirección propuesta: consola de cine

Un instrumento sobrio y preciso que hace comprensible el estado de la reproducción y permite actuar con confianza desde el sofá. Dos rasgos: **rail oscuro con indicador de sección**, y **filas técnicas alineadas con valores destacados**, reservando el ámbar para foco y acciones. Jerarquía: acción enfocada, título y estado, detalles secundarios. Evitar decoración que compita con el vídeo. Conservar marca existente mientras no se apruebe otra.

## Tokens compartidos

Colores opacos en sRGB; no modificar por pantalla. Fondo `#111216`; rail `#0C0D10`; superficie `#191B22`; superficie elevada `#22252D`; borde `#454A57`; separador `#363A45`; texto principal `#F8FAFC`; secundario `#B8C0CE`; deshabilitado `#858D9B`; foco/acción `#F59E0B` con texto `#0F1014`; éxito `#6EE7B7`; aviso `#FCD34D`; error `#FCA5A5`; enlace `#93C5FD`. Los estados se expresan también con texto e icono, nunca solo color. Fondo modal negro `#000000` al 60% de opacidad. No usar gradientes, brillo ni vidrio.

Tipografía: familia Android `sans-serif` (Roboto donde esté disponible), regular 400 y medium 500; bold 700 solo títulos. TV: título 24sp/30sp, sección 20sp/26sp, cuerpo y controles 16sp/22sp, auxiliar 14sp/20sp. Datos técnicos: 20sp/26sp medium, cifras tabulares si están disponibles. Android táctil futuro: título 24sp/30sp, sección 20sp/26sp, cuerpo/control 16sp/24sp, auxiliar 14sp/20sp. Respetar escala de fuente del sistema, permitir dos líneas y crecimiento vertical; no reducir texto para encajarlo.

Espacios dp: 4, 8, 12, 16, 24, 32, 48 y 64. Separación icono-texto 12; filas 8; grupos 24; secciones 32. Bordes 1dp; foco 2dp. Radios: 4dp para indicadores, 8dp para filas y botones, 12dp para paneles y diálogos. Sin sombras decorativas; elevación 0, diálogo 8dp para separación funcional. Sin escalado de foco: evita recortes y desplazamiento.

## Componentes y estados

- **Rail TV:** 224dp de ancho dentro de la zona segura; filas de 56dp, icono 24dp y texto alineado. Sección seleccionada: marca lateral de 4dp ámbar y texto principal; foco: relleno ámbar y texto oscuro, independiente de selección. Mantener visible la selección cuando el foco sale del rail.
- **Filas y listas:** altura mínima TV 56dp, padding horizontal 16dp; título a izquierda y valor a derecha, descripción debajo si existe. Separador de 1dp. Agrupar ajustes por encabezado, sin tarjetas para cada fila.
- **Botones:** mínimo TV 48dp de alto, padding horizontal 24dp, radio 8dp. Normal superficie elevada con texto principal; enfocado relleno ámbar y texto oscuro; pulsado superficie ámbar y borde interior oscuro 2dp. Secundario normal con borde; nunca depender solo del borde para foco.
- **Tarjetas:** solo para agrupar acciones o datos relacionados; superficie, borde y padding 24dp, radio 12dp. Una tarjeta interactiva recibe el mismo foco que un botón; no anidar controles enfocables dentro de una tarjeta que también sea enfocada.
- **Diálogo:** superficie elevada, ancho máximo 560dp TV, padding 32dp, título, explicación breve y acciones; foco inicial en acción segura o cancelación para operaciones destructivas. Back cierra y devuelve foco al origen.
- **Controles:** interruptor con etiqueta y texto activado/desactivado; selección exclusiva con marca visible y etiqueta. Valor seleccionado y foco son estados distintos. Lista larga desplaza el elemento enfocado a la zona visible.
- **Estados:** normal según componente; focused con relleno y contraste indicados; pressed con borde interior; selected con marca de 4dp o check y texto; disabled texto deshabilitado, sin foco ni activación; loading indicador circular de 24dp y texto «Cargando», sin activación duplicada; error texto de error y acción de recuperación disponible; empty mensaje concreto y acción solo si existe. Nunca presentar datos simulados como reales.

Avisos de cambio de pantalla: texto 12sp y padding 12dp horizontal y 6dp vertical; formato «AFR: 2160p@23,976 Hz» y «↩ 2160p@60 Hz» al restaurar; superficie `#191B22`, borde `#454A57` y texto principal; informar del ajuste o restablecimiento y mostrar el modo observado durante 10 segundos. Durante la espera, mantener el aviso hasta 20 segundos; si no hay confirmación de modo, mostrar el modo observado tras 15 segundos sin afirmar que el cambio tuvo éxito. Sin ámbar decorativo.

Iconos de línea de 24dp, trazo visual uniforme de 2dp, con etiqueta para navegación y acciones; no usar emoji como iconografía de producto. Mantener banners e iconos de aplicaciones reales en recientes, sin deformar: `fit`, relación original y alternativa textual con nombre. No añadir ilustraciones de stock.

Movimiento: foco 120ms, entrada/salida de diálogo 160ms, desplazamiento 200ms, curva deceleración estándar; solo opacidad y desplazamiento máximo 8dp para diálogos. Sin animación repetitiva salvo carga; respetar duración de animación del sistema y reducir a 0 cuando esté desactivada.

## Android y Android TV

TV es la plataforma real principal. Referencia de composición 960×540dp, 16:9; proteger contenido con 48dp horizontales y 27dp verticales, más insets reales si son mayores. Fondo puede llegar al borde. Rail y contenido separados 32dp; contenido flexible y desplazable, sin recortar foco. Ajustar a dimensiones disponibles y probar overscan en hardware. Foco inicial en primer control disponible; flechas recorren vecinos previsibles, derecha entra en contenido, izquierda vuelve al rail; Select activa, Back cierra el nivel superior. Restaurar foco y posición al volver; modal captura foco. No depender de hover ni gestos.

No se diseñará una variante táctil de la aplicación nativa. Para el mando web existente, accesible desde el móvil: usar una sola columna por debajo de 600dp, navegación adaptable, márgenes 16dp, controles táctiles mínimos 48×48dp e insets del sistema. A partir de 600dp usar dos paneles si caben sin reducir texto. Mantener tokens compartidos; no trasladar mecánicamente el foco TV al tacto. Verificar lector de pantalla, nombres y roles, orden, contraste mínimo 4,5:1 para texto normal y 3:1 para componentes y foco, y texto ampliado en ambas variantes.

## Aplicación a pantallas reales

- **AFR:** cabecera y estado real; ajustes como filas agrupadas; acción enfocada clara. No convertir cada frecuencia en una tarjeta ornamental.
- **Aplicaciones AFR:** lista de aplicaciones reales con nombre y estado; selección independiente del foco. No inventar carátulas.
- **Mapeo:** cada botón físico como grupo; sus acciones de clic, doble clic y pulsación larga en filas legibles. No usar emojis como únicas etiquetas.
- **Acciones rápidas:** lista de acciones disponibles con descripción breve. No presentar métricas ficticias.
- **Mando web:** dirección real y estado con jerarquía clara; sin inventar QR ni conectividad.
- **Sistema:** filas alineadas para dispositivo, HDMI, permisos y demonio, con estados textuales. Permiso pendiente muestra acción concreta; no pintar todas las cifras de colores distintos.
- **Selector AFR:** modal con Auto y restablecimiento; agrupar modos por resolución, mostrando primero la actual en cuadrícula de dos columnas. Otras resoluciones y opciones adicionales son desplegables; rescate permanece en opciones. Ordenar 23,976, 24, 50, 59,94 y 60 Hz antes de otros modos disponibles, sin agrupar frecuencias distintas; marcar el modo activo y mantener foco independiente. Back cierra el panel. **HUD:** texto técnico compacto sobre superficie legible; sin borde verde arbitrario. **Recientes:** nombres y recursos reales, foco visible y acción de limpieza diferenciada.

## DO / DON'T y validación

**DO:** reutilizar tokens, conservar nombres reales, distinguir selección y foco, probar traducciones largas y ausencia de datos. **DON'T:** añadir acentos, radios, efectos o componentes sin ampliar esta propuesta; envolver todas las filas en tarjetas; usar texto pequeño para encajar; esconder un error tras un color; afirmar una comprobación no realizada.

Antes de cerrar una implementación: contrastes medidos en combinaciones reales; capturas a 16:9 y con texto ampliado; recorrido completo D-pad, foco inicial, restauración y Back; estados de carga, vacío, error y deshabilitado; revisión de accesibilidad y traducciones; verificación táctil si procede. Registrar dispositivo y limitaciones.

## Decisiones aprobadas y validación pendiente

1. Aprobadas la dirección «consola de cine» y el ámbar como foco.
2. Escala de texto y zona segura TV pendientes de comprobación en televisor real.
3. Aprobadas la sustitución de emojis por iconos de línea y la conservación de los activos existentes.
4. Aprobada la aplicación a las seis secciones, diálogos, superposiciones y mando web. No habrá variante táctil nativa.


**Ajuste aprobado por el usuario:** escala tipográfica reducida tras prueba en Homatics; los botones largos deben crecer en altura y las acciones de sistema y acciones rápidas se agrupan en una cuadrícula de dos columnas de igual altura; las filas crecen para textos largos. La restauración elimina el modo impuesto por la aplicación; no presupone 60 Hz.
