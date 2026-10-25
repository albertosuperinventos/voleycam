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

## Próxima versión (1.8.4) — acordado, sin compilar
- Cortes normales: el audio de cada tramo empieza donde empieza la imagen (fotograma clave); hoy el desfase va a más en cada jugada.
- Si el montaje con fundidos falla: reintentar con portada y patrocinadores sin fundidos (como 1.7.0) antes de cortes normales.
- Ventana final con el error completo del montaje.
- Revisar por qué el resumen del 24/10 salió con 81 jugadas (¿marcas de más?).
- Idea (sin confirmar): música de fondo en el resumen del partido (MP3 del móvil, pabellón bajo, fundido al final).
- Grabación con calidad propia (segundo codificador de RootEncoder: recordWidth/recordHeight/recordBitrate), independiente del bitrate adaptativo del directo. Ajuste "Calidad de la grabación": Normal 720p 4 Mbps / Alta 1080p 6 Mbps (por defecto) / Máxima 1080p 10 Mbps.
- Resúmenes montados (Transformer) a ~8 Mbps.
