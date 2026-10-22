package app.superinventos.sportcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.os.Looper
import android.view.Gravity
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import com.pedro.common.ConnectChecker
import com.pedro.common.StreamingStatsReport
import com.pedro.common.Throughput
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.gl.render.filters.`object`.ImageFilterRender
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.QueueAwareBitrateAdapter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), ConnectChecker {

  private lateinit var prefs: Prefs
  private lateinit var root: View
  private lateinit var preview: SurfaceView
  private lateinit var web: WebView
  private lateinit var panel: View
  private lateinit var tvStatus: TextView
  private lateinit var tvInfo: TextView
  private lateinit var tvZoom: TextView
  private lateinit var btnLive: TextView
  private lateinit var btnRec: TextView
  private lateinit var btnRemote: TextView
  private lateinit var btnMute: TextView
  private lateinit var dimOverlay: View
  private lateinit var tvDim: TextView
  private var muted = false
  @Volatile private var weakNetAt = 0L
  private var lastWeakWarn = 0L
  private var batteryWarned = 101

  // Resumen de jugadas
  private var recStartTs = 0L          // hora de servidor en que empezó a escribirse la grabación
  private var liveStartTs = 0L         // hora de servidor en que empezó el directo
  @Volatile private var lastRecording: Highlights.Recording? = null
  @Volatile private var makingHighlights = false

  private fun serverNow() = System.currentTimeMillis() + (overlayState?.serverOffset ?: 0L)

  // Portada (miniatura de YouTube y prepartido)
  @Volatile private var coverData = ""

  // Cámara a distancia
  @Volatile private var remoteOpenTs = 0L
  private var lastStatusAt = 0L
  private var lastPreviewAt = 0L
  @Volatile private var previewBusy = false

  // YouTube
  private var pendingYt: ((String) -> Unit)? = null
  private val ytAuth = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
    try {
      val res = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(r.data)
      val token = res.accessToken
      val action = pendingYt; pendingYt = null
      if (token != null && action != null) action(token) else ytPublish(message = "No se completó el inicio de sesión")
    } catch (e: Exception) {
      pendingYt = null
      ytPublish(message = "No se completó el inicio de sesión: ${e.message}")
    }
  }

  private var stream: GenericStream? = null
  private var surfaceReady = false
  private var configSig = ""
  private var loadedKey = ""

  // Marcador superpuesto
  private var renderer: ScoreboardRenderer? = null
  private var overlayFilter: ImageFilterRender? = null
  private val overlayBitmaps = arrayOfNulls<Bitmap>(3)
  private var overlayIdx = 0
  @Volatile private var overlayState: OverlayState? = null
  @Volatile private var stateVersion = 0
  private var lastSig = ""

  // Directo y grabación
  @Volatile private var wantLive = false
  @Volatile private var connected = false
  @Volatile private var reconnecting = false
  @Volatile private var currentBitrate = 0L
  private var liveSince = 0L
  private var recordFile: File? = null
  private var recordAuto = false
  private var recordSince = 0L
  private var bitrateAdapter: QueueAwareBitrateAdapter? = null
  private var panelOpen = false
  private var panelMini = true

  private val ui = Handler(Looper.getMainLooper())
  private val ticker = object : Runnable {
    override fun run() { tick(); ui.postDelayed(this, 500) }
  }
  private val hideZoom = Runnable { tvZoom.visibility = View.GONE }

  private var fileCallback: ValueCallback<Array<Uri>>? = null
  private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
    fileCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
    fileCallback = null
  }
  private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    if (hasPerms()) setupStream()
    else toast("VoleyCam necesita la cámara y el micrófono para funcionar")
  }

  /* ======================= Ciclo de vida ======================= */

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    WindowCompat.setDecorFitsSystemWindows(window, false)
    setContentView(R.layout.activity_main)
    prefs = Prefs(this)

    root = findViewById(R.id.root)
    preview = findViewById(R.id.preview)
    web = findViewById(R.id.web)
    panel = findViewById(R.id.remotePanel)
    tvStatus = findViewById(R.id.tvStatus)
    tvInfo = findViewById(R.id.tvInfo)
    tvZoom = findViewById(R.id.tvZoom)
    btnLive = findViewById(R.id.btnLive)
    btnRec = findViewById(R.id.btnRec)
    btnRemote = findViewById(R.id.btnRemote)
    btnMute = findViewById(R.id.btnMute)
    dimOverlay = findViewById(R.id.dimOverlay)
    tvDim = findViewById(R.id.tvDim)
    btnMute.setOnClickListener { setMuted(!muted, fromUser = true) }
    findViewById<View>(R.id.btnDim).setOnClickListener { setDim(true) }
    findViewById<View>(R.id.btnMark).setOnClickListener { v ->
      web.evaluateJavascript("window.scCmd && scCmd('mark')", null)
      vibrate()
      val b = v as TextView
      b.text = "✓"; b.setBackgroundResource(R.drawable.bg_btn_on); b.setTextColor(ContextCompat.getColor(this, R.color.bg))
      ui.postDelayed({ b.text = "⭐"; b.setBackgroundResource(R.drawable.bg_btn); b.setTextColor(ContextCompat.getColor(this, R.color.text)) }, 1000)
    }
    findViewById<View>(R.id.btnMark).setOnLongClickListener { showHighlightsMenu(); true }
    dimOverlay.setOnClickListener { setDim(false) }

    btnLive.setOnClickListener { toggleLive() }
    btnRec.setOnClickListener { toggleRecord() }
    btnRemote.setOnClickListener { togglePanel() }
    findViewById<View>(R.id.btnSwitch).setOnClickListener { switchCamera() }
    findViewById<View>(R.id.btnZoomIn).setOnClickListener { zoomBy(1.25f) }
    findViewById<View>(R.id.btnZoomOut).setOnClickListener { zoomBy(0.8f) }
    findViewById<View>(R.id.btnSettings).setOnClickListener {
      startActivity(Intent(this, SettingsActivity::class.java))
    }

    // Panel del mando oculto a la izquierda: mini (abajo a la izquierda) o completo
    root.post { applyPanelSize() }

    preview.holder.addCallback(object : SurfaceHolder.Callback {
      override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startPreview()
      }
      override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        stream?.getGlInterface()?.setPreviewResolution(width, height)
      }
      override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stream?.let { if (it.isOnPreview) it.stopPreview() }
      }
    })
    setupPinchZoom()
    setupWeb()

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        when {
          dimOverlay.visibility == View.VISIBLE -> setDim(false)
          panelOpen -> togglePanel()
          wantLive || stream?.isRecording == true -> AlertDialog.Builder(this@MainActivity)
            .setTitle("¿Salir?")
            .setMessage("Se cortará la emisión y la grabación.")
            .setPositiveButton("Salir") { _, _ -> finish() }
            .setNegativeButton("Cancelar", null).show()
          else -> finish()
        }
      }
    })

    // Grabaciones que quedaron a medias en una sesión anterior
    Thread { RecordingSaver.pending(applicationContext).forEach { RecordingSaver.moveToGallery(applicationContext, it) } }.start()

    if (hasPerms()) setupStream()
    else askPerms.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
  }

  override fun onResume() {
    super.onResume()
    hideSystemBars()
    if (loadedKey.isNotEmpty() && loadedKey != prefs.channelKey) loadWeb()
    val s = stream
    if (s != null && configSig != configSignature() && !s.isStreaming && !s.isRecording) {
      val wasPreview = s.isOnPreview
      if (wasPreview) s.stopPreview()
      if (!prepare()) toast("Esta cámara no admite esa calidad. Prueba otra en Ajustes.")
      if (wasPreview || surfaceReady) startPreview()
    }
    ui.removeCallbacks(ticker)
    ui.post(ticker)
  }

  override fun onPause() {
    super.onPause()
    ui.removeCallbacks(ticker)
  }

  override fun onDestroy() {
    ui.removeCallbacksAndMessages(null)
    wantLive = false
    stream?.let {
      if (it.isStreaming) it.stopStream()
      if (it.isRecording) stopRecord()
      it.release()
    }
    stream = null
    web.destroy()
    super.onDestroy()
  }

  /* ======================= Cámara y emisión ======================= */

  private fun hasPerms() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
    ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
  }

  private fun configSignature() = "${prefs.resolution}|${prefs.bitrateKbps}|${prefs.fps}"

  private fun setupStream() {
    if (stream != null) return
    val s = GenericStream(this, this)
    s.getStreamClient().setReTries(RETRIES)
    stream = s
    if (!prepare()) toast("Esta cámara no admite esa calidad. Prueba otra en Ajustes.")
    if (surfaceReady) startPreview()
    if (muted) setMuted(true, fromUser = false)
  }

  private fun prepare(): Boolean {
    val s = stream ?: return false
    val (w, h) = prefs.videoSize()
    val vBitrate = prefs.bitrateKbps * 1000
    val ok = try {
      s.prepareVideo(w, h, vBitrate, prefs.fps) && s.prepareAudio(AUDIO_RATE, true, AUDIO_BITRATE)
    } catch (_: IllegalArgumentException) { false }
    if (ok) {
      renderer = ScoreboardRenderer(w, h).also { it.cover = coverData }
      for (i in overlayBitmaps.indices) overlayBitmaps[i] = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
      lastSig = ""
      bitrateAdapter = QueueAwareBitrateAdapter(vBitrate + AUDIO_BITRATE) { b ->
        stream?.setVideoBitrateOnFly((b - AUDIO_BITRATE).coerceAtLeast(300_000))
      }
      configSig = configSignature()
    }
    return ok
  }

  private fun startPreview() {
    val s = stream ?: return
    if (!surfaceReady) return
    try {
      if (!s.isOnPreview) s.startPreview(preview)
      s.getGlInterface().setPreviewResolution(preview.width, preview.height)
    } catch (e: Exception) {
      toast("No se pudo abrir la cámara: ${e.message}")
      return
    }
    attachOverlay()
  }

  /** El motor de vídeo borra los filtros al pararse, así que se vuelve a poner el marcador en cada arranque. */
  private fun attachOverlay() {
    val s = stream ?: return
    val f = ImageFilterRender()
    f.setScale(100f, 100f)
    f.setPosition(0f, 0f)
    overlayFilter = f
    lastSig = ""
    s.getGlInterface().setFilter(f)
    updateOverlay()
  }

  private fun toggleLive() {
    if (wantLive) {
      AlertDialog.Builder(this)
        .setTitle("¿Terminar el directo?")
        .setPositiveButton("Terminar") { _, _ -> stopLive() }
        .setNegativeButton("Seguir emitiendo", null).show()
      return
    }
    startLive(fromRemote = false)
  }

  private fun startLive(fromRemote: Boolean) {
    if (wantLive) return
    val s = stream ?: return toast("La cámara aún no está lista")
    val url = prefs.streamUrl()
    if (url == null) {
      toast("Configura la plataforma y la clave de emisión")
      if (!fromRemote) startActivity(Intent(this, SettingsActivity::class.java))
      return
    }
    wantLive = true; connected = false; reconnecting = false; liveSince = 0L; liveStartTs = 0L
    try {
      s.startStream(url)
    } catch (e: Exception) {
      wantLive = false
      toast("No se pudo iniciar: ${e.message}")
      return
    }
    if (prefs.recordLocal && !s.isRecording) startRecord(auto = true)
    attachOverlay()
    updateButtons()
  }

  private fun stopLive() {
    wantLive = false; connected = false; reconnecting = false
    stream?.let { if (it.isStreaming) it.stopStream() }
    if (recordAuto) stopRecord()
    updateButtons()
  }

  private fun toggleRecord() {
    val s = stream ?: return
    if (s.isRecording) stopRecord() else { startRecord(auto = false); attachOverlay() }
  }

  private fun startRecord(auto: Boolean) {
    val s = stream ?: return
    val name = "VoleyCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
    val f = RecordingSaver.tempFile(this, name)
    try {
      recStartTs = serverNow()
      s.startRecord(f.absolutePath) { status ->
        // La grabación empieza de verdad en el primer fotograma clave
        if (status == RecordController.Status.RECORDING) recStartTs = serverNow()
        runOnUiThread { updateButtons() }
      }
      recordFile = f; recordAuto = auto; recordSince = System.currentTimeMillis()
    } catch (e: Exception) {
      toast("No se pudo grabar: ${e.message}")
    }
    updateButtons()
  }

  private fun stopRecord() {
    val s = stream ?: return
    if (!s.isRecording) return
    s.stopRecord()
    recordAuto = false
    val f = recordFile ?: return
    recordFile = null
    val start = recStartTs
    val end = serverNow()
    Thread {
      Thread.sleep(600)
      val uri = RecordingSaver.moveToGallery(applicationContext, f)
      if (uri != null) lastRecording = Highlights.Recording(uri, start, end)
      runOnUiThread {
        toast(if (uri != null) "Vídeo guardado en Películas/VoleyCam" else "El vídeo se guardará al volver a abrir la app")
        val n = overlayState?.marks?.count { it.ts in start..end } ?: 0
        if (uri != null && n > 0) showHighlightsMenu()
      }
    }.start()
    updateButtons()
  }

  private fun cam(): Camera2Source? = stream?.videoSource as? Camera2Source

  private fun switchCamera() {
    try { cam()?.switchCamera() } catch (e: Exception) { toast("No se pudo cambiar de cámara") }
  }

  private fun zoomBy(factor: Float) {
    val c = cam() ?: return
    try {
      val r = c.getZoomRange()
      val z = (c.getZoom() * factor).coerceIn(r.lower, r.upper)
      c.setZoom(z)
      showZoom(z)
    } catch (_: Exception) {}
  }

  private fun showZoom(z: Float) {
    tvZoom.text = String.format(Locale.US, "%.1fx", z)
    tvZoom.visibility = View.VISIBLE
    ui.removeCallbacks(hideZoom)
    ui.postDelayed(hideZoom, 1200)
  }

  @SuppressLint("ClickableViewAccessibility")
  private fun setupPinchZoom() {
    val detector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(d: ScaleGestureDetector): Boolean { zoomBy(d.scaleFactor); return true }
    })
    preview.setOnTouchListener { _, e -> detector.onTouchEvent(e); true }
  }

  /* ======================= Avisos de la conexión ======================= */

  override fun onConnectionStarted(url: String) {
    bitrateAdapter?.reset()
  }

  override fun onConnectionSuccess() {
    connected = true; reconnecting = false
    if (liveSince == 0L) { liveSince = System.currentTimeMillis(); liveStartTs = serverNow() }
    runOnUiThread { updateButtons() }
  }

  override fun onConnectionFailed(reason: String) {
    connected = false
    val s = stream ?: return
    if (wantLive && s.getStreamClient().reTry(RETRY_DELAY_MS, reason)) {
      reconnecting = true
      runOnUiThread { updateStatus() }
    } else {
      runOnUiThread {
        if (wantLive) {
          stopLive()
          toast("Se perdió la conexión: $reason")
        }
      }
    }
  }

  override fun onDisconnect() {
    connected = false
    runOnUiThread { updateStatus() }
  }

  override fun onAuthError() {
    runOnUiThread {
      stopLive()
      toast("La plataforma rechazó la clave de emisión")
    }
  }

  override fun onAuthSuccess() {}

  override fun onNewBitrate(bitrate: Long) {
    currentBitrate = bitrate
  }

  override fun onStreamingStats(report: StreamingStatsReport) {
    currentBitrate = report.smoothedBitrate
    if (report.throughput == Throughput.INSUFFICIENT || report.queueCongestionPercent > 50f) weakNetAt = System.currentTimeMillis()
    runOnUiThread { bitrateAdapter?.onStreamingStats(report) }
  }

  /* ======================= Marcador y estado ======================= */

  private fun tick() {
    updateOverlay()
    updateStatus()
    val now = System.currentTimeMillis()
    if (now - lastStatusAt > 3000) { lastStatusAt = now; publishStatus() }
    if (serverNow() - remoteOpenTs < 15_000 && now - lastPreviewAt > 5000) { lastPreviewAt = now; sendPreview() }
  }

  private fun publishStatus() {
    val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
    val o = JSONObject()
      .put("wantLive", wantLive).put("connected", connected).put("reconnecting", reconnecting)
      .put("rec", stream?.isRecording == true)
      .put("liveSecs", if (liveSince > 0) (System.currentTimeMillis() - liveSince) / 1000 else 0)
      .put("recSecs", if (recordSince > 0 && stream?.isRecording == true) (System.currentTimeMillis() - recordSince) / 1000 else 0)
      .put("battery", bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1)
      .put("charging", bm?.isCharging ?: false)
      .put("bitrateKbps", currentBitrate / 1000)
      .put("weakNet", wantLive && connected && System.currentTimeMillis() - weakNetAt < 4000)
      .put("muted", muted).put("dim", dimOverlay.visibility == View.VISIBLE)
      .put("liveStartTs", liveStartTs)
    web.evaluateJavascript("window.scStatus && scStatus($o)", null)
  }

  /** Foto pequeña de lo que se emite (con marcador) para la ventana Remoto. */
  private fun sendPreview() {
    val s = stream ?: return
    if (previewBusy || !s.isOnPreview) return
    previewBusy = true
    try {
      s.getGlInterface().takePhoto { bmp ->
        Thread {
          try {
            val w = 480; val h = (bmp.height * w / bmp.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(bmp, w, h, true)
            val out = ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 55, out)
            val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            runOnUiThread { web.evaluateJavascript("window.scPreview && scPreview('data:image/jpeg;base64,$b64')", null) }
          } catch (_: Exception) {} finally { previewBusy = false }
        }.start()
      }
    } catch (_: Exception) { previewBusy = false }
  }

  /** Órdenes que llegan desde la ventana Remoto del mando. */
  private fun remoteCommand(action: String) {
    when (action) {
      "live_start" -> startLive(fromRemote = true)
      "live_stop" -> if (wantLive) stopLive()
      "rec_toggle" -> toggleRecord()
      "mute_toggle" -> setMuted(!muted, fromUser = true)
      "zoom_in" -> zoomBy(1.25f)
      "zoom_out" -> zoomBy(0.8f)
      "switch" -> switchCamera()
      "dim_on" -> setDim(true)
      "dim_off" -> setDim(false)
    }
    lastStatusAt = 0L
  }

  /* ======================= YouTube ======================= */

  private fun withYtToken(action: (String) -> Unit) {
    val req = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(YouTubeClient.SCOPE))).build()
    Identity.getAuthorizationClient(this).authorize(req)
      .addOnSuccessListener { res ->
        val pi = res.pendingIntent
        if (res.hasResolution() && pi != null) {
          pendingYt = action
          ytAuth.launch(IntentSenderRequest.Builder(pi.intentSender).build())
        } else {
          val t = res.accessToken
          if (t != null) action(t) else ytPublish(message = "Google no devolvió acceso a YouTube")
        }
      }
      .addOnFailureListener { e ->
        ytPublish(message = "No se pudo conectar con Google (${e.message}). Revisa la configuración de Google Cloud.")
      }
  }

  private fun ytPublish(connected: Boolean = false, account: String = "", broadcasts: List<YouTubeClient.Broadcast> = emptyList(), message: String = "") {
    val arr = JSONArray()
    val selected = prefs.ytBroadcastId
    broadcasts.forEach { b ->
      arr.put(JSONObject().put("id", b.id).put("title", b.title).put("when", prettyDate(b.start))
        .put("startIso", b.start).put("raw", b.status)
        .put("privacy", when (b.privacy) { "public" -> "pública"; "unlisted" -> "oculta"; "private" -> "privada"; else -> b.privacy })
        .put("status", when (b.status) { "created", "ready" -> "programada"; "live" -> "EN DIRECTO"; "complete" -> "terminada"; "testing" -> "en prueba"; else -> b.status })
        .put("selected", b.id == selected))
    }
    val o = JSONObject().put("connected", connected).put("account", account).put("broadcasts", arr).put("message", message)
    runOnUiThread { web.evaluateJavascript("window.scYt && scYt($o)", null) }
  }

  private fun prettyDate(iso: String): String = try {
    val d = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
    String.format(Locale("es", "ES"), "%02d/%02d %02d:%02d", d.dayOfMonth, d.monthValue, d.hour, d.minute)
  } catch (_: Exception) { iso }

  private fun ytRun(work: (String) -> String?) {
    withYtToken { token ->
      Thread {
        var msg: String? = null
        try { msg = work(token) } catch (e: Exception) { msg = e.message ?: "Error con YouTube" }
        try {
          ytPublish(true, YouTubeClient.channelTitle(token), YouTubeClient.listBroadcasts(token), msg.orEmpty())
        } catch (e: Exception) {
          ytPublish(message = e.message ?: "Error con YouTube")
        }
      }.start()
    }
  }

  /** Portada en JPEG 1280×720 como máximo, para la miniatura de YouTube. */
  private fun coverJpeg(): ByteArray? {
    if (coverData.isBlank()) return null
    return try {
      val bytes = Base64.decode(coverData.substringAfter("base64,"), Base64.DEFAULT)
      var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
      if (bmp.width > 1280) bmp = Bitmap.createScaledBitmap(bmp, 1280, (bmp.height * 1280f / bmp.width).toInt(), true)
      val out = ByteArrayOutputStream()
      bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
      out.toByteArray()
    } catch (_: Exception) { null }
  }

  /** Usa la emisión: la asocia a la emisión VoleyCam y guarda su clave en Ajustes. */
  private fun ytSelect(token: String, broadcastId: String) {
    val st = YouTubeClient.ensureStream(token)
    YouTubeClient.bind(token, broadcastId, st.id)
    prefs.platform = Prefs.PLATFORM_YOUTUBE
    prefs.youtubeKey = st.key
    prefs.ytBroadcastId = broadcastId
  }

  private fun updateOverlay() {
    val r = renderer ?: return
    val f = overlayFilter ?: return
    val now = System.currentTimeMillis()
    val st = overlayState
    val sig = r.signature(st, stateVersion, now)
    if (sig == lastSig) return
    lastSig = sig
    overlayIdx = (overlayIdx + 1) % overlayBitmaps.size
    val bmp = overlayBitmaps[overlayIdx] ?: return
    try {
      r.render(st, now, bmp)
      f.setImage(bmp)
    } catch (_: Exception) {}
  }

  private fun updateButtons() {
    btnLive.text = if (wantLive) "PARAR" else "EN\nDIRECTO"
    btnLive.setBackgroundResource(if (wantLive) R.drawable.bg_btn else R.drawable.bg_btn_live)
    val rec = stream?.isRecording == true
    btnRec.text = if (rec) "■ REC" else "REC"
    btnRec.setTextColor(if (rec) ContextCompat.getColor(this, R.color.live) else ContextCompat.getColor(this, R.color.text))
    updateStatus()
  }

  private fun updateStatus() {
    val now = System.currentTimeMillis()
    val rec = stream?.isRecording == true
    val weakNet = wantLive && connected && now - weakNetAt < 4000
    if (weakNet && now - lastWeakWarn > 30_000) { lastWeakWarn = now; vibrate() }
    val (txt, color) = when {
      weakNet -> "● RED DÉBIL" to Color.rgb(255, 176, 32)
      wantLive && connected -> "● EN DIRECTO" to ContextCompat.getColor(this, R.color.live)
      wantLive && reconnecting -> "● RECONECTANDO…" to Color.rgb(255, 176, 32)
      wantLive -> "● CONECTANDO…" to Color.rgb(255, 176, 32)
      rec -> "● GRABANDO" to ContextCompat.getColor(this, R.color.live)
      else -> "● SIN EMITIR" to ContextCompat.getColor(this, R.color.muted)
    }
    tvStatus.text = txt
    tvStatus.setTextColor(color)

    val parts = mutableListOf<String>()
    if (wantLive && liveSince > 0) parts += duration(now - liveSince)
    else if (rec && recordSince > 0) parts += duration(now - recordSince)
    if (wantLive && connected && currentBitrate > 0) parts += String.format(Locale.US, "%.1f Mb/s", currentBitrate / 1_000_000f)
    if (wantLive && rec) parts += "REC"
    if (muted) parts += "MICRO OFF"
    val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
    val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    val charging = bm?.isCharging ?: false
    val lowBat = battery in 0..20 && !charging
    if (battery in 0..100) parts += if (lowBat) "⚠ Bat. $battery%" else "Bat. $battery%"
    tvInfo.text = parts.joinToString("  ·  ")
    tvInfo.setTextColor(ContextCompat.getColor(this, if (lowBat) R.color.live else R.color.text))
    if (lowBat) {
      val step = if (battery <= 10) 10 else 20
      if (batteryWarned > step) { batteryWarned = step; vibrate(); toast("Batería baja ($battery %). Enchufa el móvil.") }
    } else if (charging) batteryWarned = 101
    if (dimOverlay.visibility == View.VISIBLE) {
      tvDim.text = "$txt  ${tvInfo.text}\n\nToca para encender la pantalla"
      tvDim.setTextColor(if (lowBat || weakNet) Color.argb(200, 255, 120, 90) else Color.argb(90, 255, 255, 255))
    }
  }

  private fun duration(ms: Long): String {
    val t = ms / 1000
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    else String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
  }

  /* ======================= Resumen de jugadas ======================= */

  private fun showHighlightsMenu() {
    val rec = lastRecording
    val marks = overlayState?.marks.orEmpty()
    val b = AlertDialog.Builder(this).setTitle("Resumen y YouTube")
    if (rec != null) {
      val n = marks.count { it.ts in rec.startTs..rec.endTs }
      b.setMessage("La última grabación tiene $n jugadas marcadas.")
      b.setPositiveButton("Crear resumen") { _, _ -> makeHighlights() }
    } else {
      b.setMessage(if (stream?.isRecording == true) "Para la grabación para poder crear el vídeo resumen."
                   else "Aún no hay grabación terminada; puedes copiar la descripción con los capítulos del directo.")
    }
    b.setNeutralButton("Copiar descripción + capítulos") { _, _ -> copyChapters() }
    b.setNegativeButton("Cerrar", null)
    b.show()
  }

  private fun makeHighlights() {
    val rec = lastRecording ?: return
    if (makingHighlights) return toast("Ya se está creando el resumen…")
    val st = overlayState
    val ranges = Highlights.ranges(rec, st?.marks.orEmpty(), st?.hlBefore ?: 10, st?.hlAfter ?: 3)
    if (ranges.isEmpty()) return toast("No hay jugadas marcadas en la última grabación")
    makingHighlights = true
    toast("Creando resumen de ${ranges.size} jugadas…")
    Thread {
      var msg: String
      try {
        val out = File(cacheDir, "resumen.mp4").apply { delete() }
        val n = Highlights.cut(applicationContext, rec.uri, ranges, out)
        val name = "VoleyCam_resumen_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".mp4"
        msg = if (n > 0 && RecordingSaver.moveToGallery(applicationContext, out, name) != null)
          "Resumen guardado en Películas/VoleyCam ($n jugadas)" else "No se pudo crear el resumen"
      } catch (e: Exception) {
        msg = "No se pudo crear el resumen: ${e.message}"
      }
      makingHighlights = false
      runOnUiThread { toast(msg) }
    }.start()
  }

  private fun copyChapters() {
    val marks = overlayState?.marks.orEmpty()
    val rec = lastRecording
    // Los capítulos van referidos al inicio del directo (el vídeo de YouTube empieza ahí)
    val start = if (liveStartTs > 0) liveStartTs else rec?.startTs ?: return toast("No hay directo ni grabación de referencia")
    val chapters = Highlights.chapters(start, marks)
    val desc = overlayState?.ytDesc.orEmpty()
    val text = if (desc.isBlank()) chapters else "$desc\n\nMOMENTOS DEL PARTIDO\n$chapters"
    copyToClipboard(text)
    toast("Copiado: pégalo en la descripción del vídeo en YouTube Studio")
  }

  private fun copyToClipboard(text: String) {
    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("VoleyCam", text))
  }

  /* ======================= Micrófono y ahorro ======================= */

  private fun setMuted(v: Boolean, fromUser: Boolean) {
    muted = v
    (stream?.audioSource as? MicrophoneSource)?.let { if (v) it.mute() else it.unMute() }
    btnMute.text = if (v) "🔇" else "🎙"
    btnMute.setBackgroundResource(if (v) R.drawable.bg_btn_live else R.drawable.bg_btn)
    if (fromUser) {
      web.evaluateJavascript("window.scCmd && scCmd('mute', $v)", null)
      toast(if (v) "Micrófono silenciado" else "Micrófono activado")
    }
    updateStatus()
  }

  private fun setDim(on: Boolean) {
    dimOverlay.visibility = if (on) View.VISIBLE else View.GONE
    window.attributes = window.attributes.apply {
      screenBrightness = if (on) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
    if (on && panelOpen) togglePanel()
    updateStatus()
  }

  @Suppress("DEPRECATION")
  private fun vibrate() {
    try { (getSystemService(VIBRATOR_SERVICE) as? Vibrator)?.vibrate(VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
  }

  /* ======================= Mando (web de control) ======================= */

  @SuppressLint("SetJavaScriptEnabled")
  private fun setupWeb() {
    val loader = WebViewAssetLoader.Builder()
      .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
      .build()
    web.setBackgroundColor(0xFFEDF1F5.toInt())
    web.settings.javaScriptEnabled = true
    web.settings.domStorageEnabled = true
    web.addJavascriptInterface(Bridge(), "SportCamNative")
    web.webViewClient = object : WebViewClient() {
      override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)
    }
    web.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: FileChooserParams?): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try { pickImage.launch("image/*"); true } catch (_: Exception) { fileCallback = null; false }
      }
    }
    loadWeb()
  }

  private fun loadWeb() {
    loadedKey = prefs.channelKey
    overlayState = null; stateVersion++
    web.loadUrl("https://appassets.androidplatform.net/assets/index.html#k=$loadedKey&native=1&mini=${if (panelMini) 1 else 0}")
  }

  private fun panelWidth(): Int = (panel.layoutParams as FrameLayout.LayoutParams).width

  /** Mini: esquina inferior izquierda, pequeño. Completo: todo el alto, ~42% del ancho. */
  private fun applyPanelSize() {
    val dp = resources.displayMetrics.density
    val lp = panel.layoutParams as FrameLayout.LayoutParams
    if (panelMini) {
      lp.width = maxOf((root.width * 0.36f).toInt(), (340 * dp).toInt()).coerceAtMost(root.width)
      lp.height = minOf((root.height * 0.9f).toInt(), (320 * dp).toInt())
      lp.gravity = Gravity.BOTTOM or Gravity.START
      lp.setMargins((8 * dp).toInt(), 0, 0, (8 * dp).toInt())
    } else {
      lp.width = maxOf((root.width * 0.42f).toInt(), (320 * dp).toInt()).coerceAtMost(root.width)
      lp.height = FrameLayout.LayoutParams.MATCH_PARENT
      lp.gravity = Gravity.START
      lp.setMargins(0, 0, 0, 0)
    }
    panel.layoutParams = lp
    panel.translationX = if (panelOpen) 0f else -lp.width.toFloat() - 40f
  }

  private fun togglePanel() {
    panelOpen = !panelOpen
    panel.animate().translationX(if (panelOpen) 0f else -panelWidth().toFloat() - 40f).setDuration(200).start()
    btnRemote.setBackgroundResource(if (panelOpen) R.drawable.bg_btn_on else R.drawable.bg_btn)
    btnRemote.setTextColor(ContextCompat.getColor(this, if (panelOpen) R.color.bg else R.color.text))
  }

  inner class Bridge {
    @JavascriptInterface
    fun changeChannel() {
      ui.post { ChannelPicker.show(this@MainActivity, prefs) { loadWeb() } }
    }

    @JavascriptInterface
    fun setMini(mini: Boolean) {
      ui.post { panelMini = mini; applyPanelSize() }
    }

    @JavascriptInterface
    fun onState(json: String) {
      try {
        val st = OverlayState.parse(json)
        overlayState = st
        stateVersion++
        ui.post {
          if (st.muted != muted) setMuted(st.muted, fromUser = false)
          updateOverlay()
        }
      } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun command(action: String) { ui.post { remoteCommand(action) } }

    @JavascriptInterface
    fun setCover(dataUrl: String) {
      coverData = dataUrl
      ui.post { renderer?.cover = dataUrl; stateVersion++; updateOverlay() }
    }

    @JavascriptInterface
    fun onRemote(ts: Double) { remoteOpenTs = ts.toLong() }

    @JavascriptInterface
    fun openVideoHighlights() {
      ui.post {
        val st = overlayState
        startActivity(Intent(this@MainActivity, VideoHighlightsActivity::class.java)
          .putExtra("matches", st?.savedMatchesJson ?: "[]")
          .putExtra("before", st?.hlBefore ?: 10)
          .putExtra("after", st?.hlAfter ?: 3))
      }
    }

    @JavascriptInterface
    fun ytConnect() { ui.post { ytRun { null } } }

    @JavascriptInterface
    fun ytCreate(json: String) {
      ui.post {
        ytRun { token ->
          val o = JSONObject(json)
          val st = YouTubeClient.ensureStream(token)
          val id = YouTubeClient.createBroadcast(token, o.getString("title"), o.optString("desc"), o.getString("when"), o.optString("privacy", "unlisted"), st.id)
          prefs.platform = Prefs.PLATFORM_YOUTUBE; prefs.youtubeKey = st.key; prefs.ytBroadcastId = id
          var extra = ""
          coverJpeg()?.let { jpg ->
            extra = try { YouTubeClient.setThumbnail(token, id, jpg); " Portada puesta como miniatura." }
                    catch (e: Exception) { " No se pudo poner la miniatura (${e.message}); revisa que el canal tenga las funciones intermedias activadas." }
          }
          "Emisión creada y lista: al pulsar EN DIRECTO se emitirá en ella.$extra"
        }
      }
    }

    @JavascriptInterface
    fun ytUse(id: String) {
      ui.post { ytRun { token -> ytSelect(token, id); "Emisión seleccionada: la app emitirá en ella." } }
    }

    @JavascriptInterface
    fun ytUpdateDesc(id: String, desc: String) {
      ui.post {
        val marks = overlayState?.marks.orEmpty()
        val start = if (liveStartTs > 0) liveStartTs else lastRecording?.startTs ?: 0L
        val full = if (start > 0 && marks.isNotEmpty()) "$desc\n\nMOMENTOS DEL PARTIDO\n${Highlights.chapters(start, marks)}" else desc
        ytRun { token -> YouTubeClient.updateDescription(token, id, full); "Descripción actualizada en YouTube." }
      }
    }

    @JavascriptInterface
    fun copyText(text: String) {
      ui.post { copyToClipboard(text) }
    }

    @JavascriptInterface
    fun makeHighlights() {
      ui.post { showHighlightsMenu() }
    }

    @JavascriptInterface
    fun shareImage(dataUrl: String) {
      try {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.DEFAULT)
        val dir = File(cacheDir, "share").apply { mkdirs() }
        val f = File(dir, "resultado.png").apply { writeBytes(bytes) }
        val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).setType("image/png")
          .putExtra(Intent.EXTRA_STREAM, uri)
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ui.post { startActivity(Intent.createChooser(send, "Compartir resultado")) }
      } catch (_: Exception) {
        toast("No se pudo compartir la imagen")
      }
    }
  }

  /* ======================= Varios ======================= */

  private fun hideSystemBars() {
    WindowCompat.getInsetsController(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  private fun toast(msg: String) {
    if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    else runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
  }

  companion object {
    private const val AUDIO_RATE = 44100
    private const val AUDIO_BITRATE = 128_000
    private const val RETRIES = 1000
    private const val RETRY_DELAY_MS = 5000L
  }
}
