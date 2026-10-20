# SportCam

Emite partidos de voleibol y fútbol desde un móvil Android a YouTube, Facebook o cualquier servidor RTMP, con marcador superpuesto, logos, patrocinadores, rótulos y pantalla de descanso. El marcador se lleva desde la propia app o desde otro móvil.

## Piezas

- **app/** – App Android (Kotlin + RootEncoder). Cámara, emisión con reconexión automática, grabación en el móvil, zoom y el marcador pintado sobre el vídeo.
- **docs/** – Web de control (mando). Va dentro de la app y también se publica en GitHub Pages para abrirla desde el segundo móvil.
- **firebase/database.rules.json** – Reglas de la base de datos.
- **.github/workflows/build.yml** – Compila el APK en cada cambio y lo publica en Releases.

## Puesta en marcha (una sola vez)

### 1. Firebase
1. Entra en console.firebase.google.com y crea un proyecto (p. ej. "sportcam"). Analytics no hace falta.
2. Menú Compilación → Realtime Database → Crear base de datos → ubicación Bélgica (europe-west1) → modo bloqueado.
3. Pestaña Reglas: pega el contenido de firebase/database.rules.json y publica.
4. Configuración del proyecto (rueda) → Tus apps → icono web (</>) → registra la app "sportcam" (sin Hosting).
5. Copia los valores apiKey, authDomain, databaseURL, projectId y appId en docs/config.js.

### 2. GitHub
1. Sube este proyecto a un repositorio (p. ej. "sportcam").
2. Settings → Pages → Source: "Deploy from a branch" → rama main, carpeta /docs → Save. La web quedará en https://albertosuperinventos.github.io/voleycam/
3. Pestaña Actions: se compila solo. Al terminar, el APK está en Releases → "SportCam (última versión)" → SportCam.apk.

### 3. Móvil cámara (Android 10 o superior)
1. Descarga SportCam.apk desde Releases en el propio móvil e instálalo (permite "instalar apps desconocidas" si lo pide).
2. Abre la app, acepta cámara y micrófono.
3. Ajustes (⚙): elige plataforma y pega la clave de emisión. Escribe la dirección de la web de GitHub Pages: aparecerá un QR.

### 4. Móvil mando
Escanea el QR de Ajustes. Se abre la web con el canal ya vinculado (se recuerda para la próxima vez).

### YouTube
La primera vez hay que activar las emisiones en directo en YouTube Studio; puede tardar hasta 24 h. Después: YouTube Studio → Emitir en directo → Emitir → copia la "Clave de emisión" a la app. La clave se puede reutilizar en todos los partidos.

## Uso en el partido

1. En la web: pestaña Equipos → crea los equipos con nombre, abreviatura (3-4 letras), color y logo. Se guardan para siempre.
2. Pestaña Partido → deporte, local y visitante, reglas. "Nuevo partido" pone todo a cero.
3. En la app: EN DIRECTO. Si "Grabar también en el móvil" está activo, a la vez se graba el partido completo (queda en Galería → Películas/SportCam).
4. Marcador: toca el panel del equipo para sumar. En voleibol el set se cierra solo (25 / 15 en el decisivo, con 2 de diferencia) y la pelota naranja marca el saque.
5. MANDO en la app abre el mismo marcador en un lateral, por si llevas tú solo la cámara.
6. Rótulos: texto inferior, pantalla completa (tiempo muerto, descanso, fin del set…) y patrocinadores que rotan en la esquina.

## Consejos

- Mantén la app en primer plano y el móvil enchufado si el partido es largo.
- Si la red va justa, la app baja la calidad sola y, si se corta, reintenta durante más de una hora sin que hagas nada.
- La "clave del canal" (Ajustes) separa tus datos en Firebase: no la compartas fuera de tus móviles.

## Compilar en local (opcional)
Abrir la carpeta con Android Studio y ejecutar. Requiere JDK 17.
