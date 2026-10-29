# Pendiente

## Hecho en 1.1.0
- Marcador: posición elegible (4 esquinas) y mostrar/ocultar, desde Rótulos.
- Recuadro de estado de la app abajo al centro.
- Patrocinadores: texto opcional, botón "Pantalla completa" por patrocinador, carrusel a pantalla completa en el descanso, logos a 600 px.

## Versión 1.3.0
- Mini mando flotante en la app (abajo izq.) con botón para ampliar al mando completo.
- Modo compacto en el mando del segundo móvil (todo en una pantalla).
- Sets: −/+ en el marcador y edición/borrado/alta de resultados de sets jugados.
- Temporizador en voleibol con Mostrar/Ocultar, ascendente o cuenta atrás.
- Equipos Liga Oro Junior Sevilla y 2ª Andaluza Grupo 3B (carga con un toque).
- Pantallas completas de voleibol (prepartido, tiempo muerto, fin de set, fin de partido con tabla) y fondo semitransparente.
- Silenciar micrófono (app y mando), modo ahorro de batería, avisos de batería baja y red débil.
- Tiempos muertos por equipo en el marcador.
- Compartir resultado como imagen e historial de partidos.
- Título y descripción de YouTube generados con el partido y los patrocinadores.
- Resumen de jugadas: botón ⭐, marca automática de fin de set, vídeo resumen cortado de la grabación y capítulos para YouTube.

## Versión 1.4.0
- Botón ⭐ en verde con ✓ ~1 s (mando y app).
- Patrocinadores con imagen de pantalla completa (diapositiva).
- Ventana 📡 Remoto en el mando del segundo móvil: vista previa, estado y control de la cámara; avisos de batería/red.
- Resumen desde vídeo de la galería, con ⭐ y aplicando marcas de un partido (desfase ajustable).
- Conexión con YouTube: crear emisión programada, elegir emisión, actualizar descripción con capítulos (requiere configurar Google Cloud).
## Versión 1.5.0
- Nombre visible VoleyCam.
- Escudo del club Voleibol Tomares para EDM Voleibol Tomares.
- Imagen de portada: miniatura de YouTube al crear emisión y alternada en PREPARTIDO.
- Lista de emisiones de YouTube: en uso arriba, próximas debajo, anteriores ocultas.

## Versión 1.6.0
- Mando nuevo en tema claro con 5 pestañas: Juego, Pantalla, Partido, Después, Ajustes.
- Mini mando renovado (nombres, marcador oscuro, Deshacer · Jugada · T. muerto/Quitar · Más).
- Solo una pantalla completa a la vez (rótulo o patrocinador) y botón Quitar.
- Usar el canal de otro móvil: escanear QR o pegar clave (Ajustes de la app y Ajustes del mando).

## Versión 1.7.0
- Repetición en directo: botón aparte "⏪ Repetir" (⭐ solo marca). Búfer de ~10 s en memoria (≈640x360, 15 fps), rótulo REPETICIÓN, vuelve sola a cámara; audio en directo. Velocidad normal/lenta en Ajustes. Se lanza desde mando, mini mando y botón de cámara; botón para cortarla.
- Dos favoritos: ⭐ Jugada (general) y 💜 Carmen (nombre editable en Ajustes). Marcas con tipo; tocar una marca cambia su tipo. Resumen del partido / Resumen de Carmen por separado (también desde galería). Fin de set automático solo general. Capítulos YouTube solo con ⭐. Marcas a menos de 5 s se unen en una sola jugada.
- Menú fin de directo: Crear resumen · Subir descripción a YouTube · Cerrar; al terminar el resumen vuelve el menú con Compartir.
- Resumen del partido con portada 3 s (RESUMEN DEL PARTIDO, escudos, equipos, sets, fecha, competición) y patrocinadores al final 3 s cada uno (diapositiva o logo). Requiere recodificar (1-2 min, barra de progreso). El de Carmen SIN portada ni patrocinadores.
- Un solo botón "Crear resúmenes" hace los dos (el de Carmen solo si hay marcas 💜); en Después se puede crear uno suelto.
- Las jugadas 💜 NO entran en el resumen general (solo si además se pulsó ⭐).
- 💜 no lanza repetición (solo ⏪).

## Siguiente versión
- ~~Rediseño del mando~~ (hecho en 1.6.0) por momento de uso: Juego · Pantalla · Partido · Después · Ajustes (boceto antes de programar). Mini mando con nombres completos y botones con texto.
- Opcional: firma de la app como secreto de GitHub (requiere reinstalar y nueva SHA-1 en Google Cloud).

## Versión 1.8.0
- Resumen del partido y de Carmen: fundido encadenado entre jugadas (una se funde sobre la siguiente, no a negro), sonido también fundido.
- Portada del resumen del partido con "MEJORES JUGADAS" en letra grande y gruesa, apareciendo con fundido.
- Sin rótulos "JUGADA 1, 2…". El de Carmen sin títulos (ni portada ni patrocinadores).
- Si el fundido falla en el móvil: se guarda el resumen con cortes normales y avisa.
- Vídeo de patrocinador: aplazado.
- Tiempo muerto automático: al pulsar TM de un equipo en Juego se cuenta y sale TIEMPO MUERTO con carrusel de patrocinadores 30 s; vuelve sola a la cámara (botón Volver para antes).
- Fin de set: sale FIN DE SET con carrusel hasta pulsar "Volver al partido"; máximo 2 min y vuelve sola.
- La hora de vuelta se guarda en el canal (la cumple el móvil cámara aunque el mando se apague). Cuenta atrás visible en el botón.
- YouTube: interruptor "Contenido para niños" al crear la emisión (por defecto NO).
- PREPARTIDO siempre con carrusel en este orden: portada → escudos → patrocinadores (sin depender del interruptor).
- "Nuevo partido": guarda siempre el partido con sus jugadas en el historial y vacía la lista de jugadas, sin preguntar (sigue preguntando confirmación de poner a cero). Quitar botón "Borrar todas las marcas".

## Versión 1.8.1
- Al pulsar EN DIRECTO: comprueba la emisión de YouTube y dice dónde y con qué privacidad se emite; deja cambiar la privacidad o crear la emisión al momento si no hay ninguna.
- Unir grabaciones (Después y menú de resúmenes): une vídeos en orden de hora sin recodificar; si son grabaciones de la app, los resúmenes usan el vídeo unido con todas las jugadas.

## Versión 1.8.2
- Resúmenes de grabaciones anteriores: eliges los vídeos originales, la hora sale del nombre del archivo (VoleyCam_fecha_hora), se unen si hay varios y se colocan las jugadas (lista actual + historial).

## Versión 1.8.3
- La hora de los vídeos se saca aunque el selector esconda el nombre (nombre real en la galería o fecha interna del vídeo). Orden por hora.
- "Resumen desde un vídeo de la galería" también coloca las marcas por la hora real del vídeo (o por el inicio del directo si es de YouTube; el que más cuadre).

## Versión 1.8.4
- Cortes normales: el audio de cada tramo empieza donde empieza la imagen (fotograma clave); hoy el desfase va a más en cada jugada.
- Si el montaje con fundidos falla: reintentar con portada y patrocinadores sin fundidos (como 1.7.0) antes de cortes normales.
- Ventana final con el error completo del montaje.
- Idea (sin confirmar): música de fondo en el resumen del partido (MP3 del móvil, pabellón bajo, fundido al final).
- Grabación con calidad propia (segundo codificador de RootEncoder: recordWidth/recordHeight/recordBitrate), independiente del bitrate adaptativo del directo. Ajuste "Calidad de la grabación": Normal 720p 4 Mbps / Alta 1080p 6 Mbps (por defecto) / Máxima 1080p 10 Mbps.
- Resúmenes montados (Transformer) a ~8 Mbps.
- Calidad de la repetición en Ajustes: Baja (640x360, 15 fps, actual) / Media (960x540, 20 fps, por defecto) / Alta (1280x720, 25 fps).
- La calidad de la repetición se cambia en el mando: Ajustes → Resumen de jugadas (junto a "Repetición a cámara lenta").
- Barra de estado de la cámara con el tipo de conexión (5G / 4G / WiFi) y aviso si se empieza el directo en WiFi.
- Repeticiones en el resumen: la app guarda cuándo empieza y acaba cada repetición. Opción en Ajustes "Repeticiones en el resumen": Quitar (por defecto: el tramo acaba justo antes de la repetición; si una marca cae dentro de una repetición, cuenta desde su inicio) / Incluir enteras (el tramo se alarga hasta el final de la repetición).
- Nuevos tipos de jugada: 🧱 Bloqueo (5 s antes, 2 después) y 🛡 Recepción (4 antes, 1 después); ⭐ Jugada 7/3. Segundos de cada tipo editables en Ajustes.
- Botones pulsados con menos de 5 s de diferencia se juntan en una sola jugada con varias etiquetas (p. ej. 🧱+💜 = bloqueo de Carmen). Si junta tipos, usa la ventana más larga.
- "Crear resúmenes": Partido (todas: ⭐🧱🛡, con portada y patrocinadores), Carmen (sus jugadas de cualquier tipo), Bloqueos y Recepciones (vídeos aparte, sin portada, con fundidos). Solo se crean los que tengan jugadas.
- Botones en Juego, mando pequeño y cámara SIN desplazar: en la cámara, segunda columna a la izquierda de la actual con ⭐ Jugada · 💜 Carmen · 🧱 Bloqueo · 🛡 Recepción · ⏪ Repetir (botones grandes con icono y nombre); la columna de siempre: EN DIRECTO, REC, MANDO, −/+, 🎙/🌙, ⟲/⚙ (sin ScrollView). Aprobado boceto_camara.png.
- Formulario "Añadir patrocinador" con las dos imágenes: "Logo para la esquina (obligatorio)" y "Diapositiva para pantalla completa (opcional, 16:9)". En Editar, los mismos nombres.

## Ideas acordadas para más adelante (sin compilar)
- Cuenta atrás en PREPARTIDO hasta la hora del partido ("Empezamos en 4:59").
- Compartir el enlace del directo por WhatsApp con un botón; QR al vídeo en el cartel del resultado.
- Alineación opcional (por confirmar cómo): jugadoras por equipo con número y nombre; elegir convocadas antes del partido.
- Más adelante quizá: MVP con QR para votar.
- Descartadas por ahora: rótulo de jugadora (2), estadísticas (5: ya usa otra app).

## VoleyCam Estudio (Windows) 1.0.0
- Programa de PC (carpeta estudio/): clave del canal → partido (actual o historial) → vídeos (hora por nombre, fecha interna, inicio del directo o a mano) → lista de jugadas revisable (activar, segundos antes/después, vista previa) → resúmenes con FFmpeg: partido (portada MEJORES JUGADAS + patrocinadores), jugadora, bloqueos, recepciones; fundido encadenado, música opcional, calidad y resolución.
- Instalador: release "estudio-latest" (VoleyCamEstudio-Setup.exe y versión portable .zip), compilado en GitHub Actions (windows-latest).
- Siguiente versión: estadísticas e importar Voley Stats Pro.

## VoleyCam Estudio 1.1.0
- Cambiar el tipo de cada jugada (columna Tipo: ⭐/💜/🧱/🛡, varias a la vez), añadir jugadas desde la vista previa ("＋ Añadir jugada aquí"), controles −5 s / ▶ / +5 s, y "Guardar cambios en el canal" (PATCH de las jugadas cambiadas).
- Arreglo de la columna derecha en pantallas con letra grande (desplazable, casillas separadas).
- OJO 1.8.4 (app y mando): las jugadas pueden traer kind con varias etiquetas separadas por "+" (p. ej. "carmen+bloqueo", "jugada+carmen"); el mando y la app deben tratarlas como etiquetas.
- Estudio 1.1.1: cuatro botones ⭐ 💜 🧱 🛡 para añadir jugadas en la vista previa (se juntan si se pulsan seguidos, como en la app).
- Estudio 1.1.2: botón 🗑 Quitar jugadas (selección múltiple); al guardar se borran del canal.

## Próxima versión (1.8.5) — hecho en local, sin compilar
- Descripción de YouTube: "Retransmisión NO OFICIAL, ofrecida por:" y al final "Esta retransmisión incluye contenido promocional." (sin patrocinadores: "Retransmisión NO OFICIAL."). Los capítulos se meten antes del aviso final.
- Compartir (pestaña Partido): QR a pantalla completa (vídeo de la emisión elegida o, si no, youtube.com/@canal/live), envío por WhatsApp a un número sin guardar, copiar y compartir el mensaje (vídeo + canal).
- Ajustes → YouTube y compartir: enlace del canal (por defecto youtube.com/@bertoni1313) y mensaje de bienvenida en el chat (activable, editable).
- Descripción: primera línea "🔔 Suscríbete para no perderte ningún partido: (canal)".
- App: escribe una vez el mensaje en el chat de YouTube cuando la emisión pasa a directo; publica el id del vídeo en el estado para el QR del mando; puente openUrl y shareText.
- Diapositiva QR del canal (tomares_C, enlace /live) para subir como patrocinador.
- Cabezal de giro (fase 1): app se conecta por BLE a "VoleyCam-Pan" (PanController), permisos Bluetooth al activarlo; órdenes pan_l/pan_r (repetidas cada 300 ms mientras se mantiene), pan_s, pan_c, pan_v1..5, pan_enable/disable; estado en la barra ("Cabezal ✓") y en status.pan para el mando.
- Mando: pestaña Juego "Cámara motorizada" (◀ Centrar ▶ + velocidad 1-5, solo si el cabezal está activado); Ajustes → "Cabezal de giro" (activar/desactivar).
- ESP32: esp32/voleycam_pan/voleycam_pan.ino (centro 1500, topes 900-2100, rampa suave, se para solo si deja de recibir órdenes o se pierde la conexión). Sin compilar aquí: compilar en Arduino IDE con esp32 2.0.17 + ESP32Servo.

## Estudio 1.2 — aprobado (boceto_estudio.png), sin hacer
- Tres pestañas: 1 Preparar (canal, partido, vídeos) · 2 Ver y marcar (vídeo grande, línea de tiempo con marcas de colores clicables, ⏮ −10 −5 ▶ +5 +10 ⏭, velocidad, botones ⭐💜🧱🛡, jugada seleccionada con tipos/segundos/quitar, lista con filtros, teclado: Espacio, ←→, 1-4, Supr, N/P, guardar en el canal) · 3 Crear resúmenes.

## Plan para poder vender en el futuro (planteado 26/10)
Fase 0 · Proteger (ya): repo privado + mando en Firebase Hosting + repo público solo con descargas; firma fuera del repo (secreto); R8 en la app, PyArmor en Estudio, mando minificado; aviso de derechos.
Fase 1 · Hacerla genérica: sin "Carmen" por defecto, ligas/equipos por club, primera configuración guiada; imágenes a Firebase Storage (no en la base de datos); política de privacidad y términos; verificación de Google (OAuth YouTube) y cuota de la API; Google Play.
Fase 2 · Licencias: código por club en Firebase (club, caducidad, dispositivos), comprobación al abrir con margen sin conexión, panel web de administración, pagos (Stripe o factura), reglas de Firebase ligadas a la licencia.
Fase 3 · Legal y negocio: comprobar/registrar marca (OEPM), condiciones de uso, RGPD (grabación de menores: consentimiento de familias/club), facturación como Superinventos, costes (Firebase Blaze, Play 25 $, Apple 99 $/año si Mac/iOS).
- Estudio 1.2 también: al abrir, conecta solo y abre el último partido con jugadas (si "Partido actual" está vacío, el último del historial); recuerda los vídeos añadidos a cada partido y los vuelve a cargar si siguen en el disco; si hay cambios sin guardar al cerrar, pregunta.

## IDEA PENDIENTE 1.9 (recordar a Albert ANTES DE CADA COMPILACIÓN) — Familias marcan jugadas de su hija
- Lista de jugadoras en Ajustes (nombre, dorsal); Carmen pasa a ser una más.
- QR por familia con clave propia (no la del canal) → mando sencillo: ⭐ Jugada de X · 🧱 · 🛡 y marcador solo para ver. Se puede anular cada QR.
- Hora con la del servidor (ServerValue.TIMESTAMP) para que cuadre con la grabación.
- "Crear resúmenes": un vídeo por jugadora con jugadas; también Estudio.
- Preguntas abiertas: ¿enlace al directo en ese mando?, ¿las jugadas de familias entran en el resumen del partido o solo en el suyo?, ¿cuántas jugadoras (equipo ~12)?

## Giro motorizado y seguimiento automático (planteado 27/10)
- Hardware que tiene Albert: Lynxmotion BR-KT (base giratoria) + servo Hitec HS-422; ServoCity SPT200 (pan-tilt, sin servos, de momento no se usa). Pedidas 2 ESP32-WROOM-32 (ELEGOO, USB-C). Falta: condensador 1000 µF 16 V, cables Dupont, soporte móvil 1/4".
- Fase 1: giro manual ◀ ▶ desde el mando; el móvil cámara manda la orden a la ESP32 por Bluetooth (BLE). Movimiento con rampa suave en la ESP32.
- Fase 2a: seguimiento automático en voleibol con el HS-422 (MediaPipe Object Detector / YOLO TFLite en el móvil, 5-10 fps a baja resolución, filtro de Kalman).
- Fase 2b: fútbol con motor paso a paso NEMA17 + driver TMC2209 (suave y silencioso); seguir al centro del grupo de jugadores, el balón solo como ayuda.
- Descartado: seguimiento virtual (recorte digital) con un móvil fijo: no coge todo el campo.
- Otras ideas ESP32 para VoleyCam: botonera física inalámbrica (puntos, ⭐, 💜, ⏪), piloto EN DIRECTO en el trípode, marcador LED P10 para el pabellón.
- Zhiyun Smooth X (aparcado): se conecta por BLE sin emparejar como "SMOOTH-X_9019-1"; ZY Cami escribe órdenes de 10 bytes en el handle 0x2A y recibe respuestas por notificación en el handle 0x2C (MTU 243). El Xiaomi 14T Pro (MediaTek) no guarda el contenido de los paquetes en el informe. Para sacar el protocolo: sniffer BLE nRF52840 (~10-15 €) + Wireshark, o captura HCI con un móvil no MediaTek.

## Plantillas y diapositiva de plantilla en el PREPARTIDO (acordado 29/10, sin hacer)
- Solo para el carrusel del PREPARTIDO (de momento NO: marcar por jugadora, resumen por jugadora, QR familias).
- Varias plantillas en Ajustes (Junior Sevilla, Senior 2ª Andaluza…); en Partido se elige cuál juega. Máximo 14 jugadoras.
- Cada jugadora: dorsal, nombre, posición, capitana (C) y su CROMO como imagen (los que ya tiene el club). Si no hay cromo, la app genera uno con el mismo estilo.
- Estilo del cromo del club: fondo granate con rayas diagonales, borde dorado redondeado, dorsal gigante contorneado en dorado de fondo, foto recortada, escudo arriba a la izquierda, abajo dorsal dorado + nombre blanco + posición dorada, escudo "C" dorado para la capitana. SIN etiqueta de categoría (junior/senior/juvenil).
- Diapositiva: 2 pantallas de 7 (fila de 4 + fila de 3) y en el hueco el escudo del Tomares con "#VAMOSTOMARES"; si son menos, la última fila centrada. Cabecera granate con escudo + "VOLEIBOL TOMARES" + "1/2". Bocetos: cromos_1de2.png / cromos_2de2.png.
- Orden del carrusel: portada → escudos → plantilla 1/2 → plantilla 2/2 → patrocinadores.
- Fotos/cromos reducidos al subir (~10-15 KB cada uno).
- Jugadoras COMPARTIDAS entre plantillas: las juveniles suben con Junior y Senior → una lista única de jugadoras (con su cromo/foto una sola vez) y cada plantilla elige cuáles van. Juvenil solo para datos.
- Si una plantilla tiene 15-16: 2 pantallas de 8 (o 8+7 con escudo).
- PRIVACIDAD: el repositorio es público → los datos de las jugadoras (nombres, fotos) NO van al repo; se cargan desde el mando (Firebase) o con un archivo de importación local que no se sube (plantillas/*.json en .gitignore).
- Pendiente de Albert: listas reales (dorsal, nombre, posición) de Junior y Senior, ¿Carmen o Carmela?, y los cromos como imagen (el artefacto "Voleibol Tomares" no se puede abrir desde aquí: descargar el HTML o mandar capturas).

## Montaje del cabezal (29/10)
- Base Lynxmotion BR-KT montada, servo centrado en 1500 = frente. Prueba con Monitor serie OK ("va perfecto").
- Móvil: tornillo 1/4" por agujero de 6,5 mm a ~3 cm del centro + tuerca; soporte de móvil enroscado.
- Pendiente: medidas para piezas 3D (tapa inferior con tuerca 1/4" para trípode y hueco ESP32/power bank; adaptador superior centrado). Albert tiene impresora 3D.
