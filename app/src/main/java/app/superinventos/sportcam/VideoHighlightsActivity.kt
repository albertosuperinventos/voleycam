package app.superinventos.sportcam

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resumen a partir de un vídeo ya grabado o descargado:
 * se reproduce, se marcan las jugadas con ⭐ (o se aplican las de un partido) y se crea el resumen.
 */
class VideoHighlightsActivity : AppCompatActivity() {

  private lateinit var video: VideoView
  private lateinit var seek: SeekBar
  private lateinit var tvTime: TextView
  private lateinit var btnPlay: Button
  private lateinit var btnMark: Button
  private lateinit var marksBox: LinearLayout
  private lateinit var etBefore: EditText
  private lateinit var etAfter: EditText
  private lateinit var etOffset: EditText
  private lateinit var tvEmpty: TextView

  private var uri: Uri? = null
  private var durationMs = 0
  private val marks = sortedSetOf<Int>()      // posiciones en ms dentro del vídeo
  private val labels = HashMap<Int, String>()
  private var busy = false
  private val ui = Handler(Looper.getMainLooper())
  private val ticker = object : Runnable {
    override fun run() {
      if (durationMs > 0) {
        seek.progress = video.currentPosition
        tvTime.text = "${fmt(video.currentPosition)} / ${fmt(durationMs)}"
        btnPlay.text = if (video.isPlaying) "❚❚" else "▶"
      }
      ui.postDelayed(this, 250)
    }
  }

  private val pick = registerForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) load(u) else if (uri == null) finish() }

  private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
  private fun color(id: Int) = ContextCompat.getColor(this, id)

  private fun btn(text: String, accent: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text; isAllCaps = false; textSize = 14f
    setTextColor(if (accent) color(R.color.bg) else color(R.color.text))
    background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(if (accent) color(R.color.accent) else color(R.color.panel2)) }
    minHeight = dp(40); minimumHeight = dp(40); setPadding(dp(12), 0, dp(12), 0)
    setOnClickListener { onClick() }
  }

  private fun label(text: String, size: Float = 12f, col: Int = R.color.muted) = TextView(this).apply {
    this.text = text; textSize = size; setTextColor(color(col))
  }

  private fun number(value: Int) = EditText(this).apply {
    inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
    setText(value.toString()); setTextColor(color(R.color.text)); textSize = 15f
    background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(color(R.color.panel2)) }
    setPadding(dp(10), dp(6), dp(10), dp(6)); gravity = Gravity.CENTER
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val lp = { w: Int, h: Int, weight: Float -> LinearLayout.LayoutParams(w, h, weight) }
    val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(color(R.color.bg)); setPadding(dp(10), dp(10), dp(10), dp(10)) }

    // Izquierda: vídeo y controles
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    video = VideoView(this)
    frame.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
    left.addView(frame, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    seek = SeekBar(this)
    seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
      override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) video.seekTo(p) }
      override fun onStartTrackingTouch(sb: SeekBar?) {}
      override fun onStopTrackingTouch(sb: SeekBar?) {}
    })
    left.addView(seek, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
    val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    tvTime = label("00:00 / 00:00", 14f, R.color.text)
    btnPlay = btn("▶") { if (video.isPlaying) video.pause() else video.start() }
    btnMark = btn("⭐ Marcar jugada", accent = true) { markHere() }
    controls.addView(btn("−10 s") { video.seekTo(maxOf(0, video.currentPosition - 10_000)) })
    controls.addView(btnPlay, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    controls.addView(btn("+10 s") { video.seekTo(minOf(durationMs, video.currentPosition + 10_000)) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
    controls.addView(tvTime, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(12) })
    controls.addView(btnMark)
    left.addView(controls, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f))
    root.addView(left, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f))

    // Derecha: ajustes, marcas y crear
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
    right.addView(label("Resumen desde vídeo", 18f, R.color.accent))
    val nums = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    val prefs = getSharedPreferences("sportcam", MODE_PRIVATE)
    etBefore = number(intent.getIntExtra("before", 10)); etAfter = number(intent.getIntExtra("after", 3))
    fun col(t: String, e: EditText) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(label(t)); addView(e) }
    nums.addView(col("Seg. antes", etBefore), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    nums.addView(col("Seg. después", etAfter), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(8) })
    right.addView(nums)
    right.addView(btn("📋 Usar marcas de un partido") { applyMatchMarks() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply { topMargin = dp(8) })
    val off = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    etOffset = number(prefs.getInt("hlOffset", 0))
    off.addView(label("Desfase de esas marcas (s):"), lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    off.addView(etOffset, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
    right.addView(off)
    right.addView(label("Jugadas marcadas (toca para ir, ✕ para quitar)").apply { setPadding(0, dp(10), 0, dp(4)) })
    val sc = ScrollView(this)
    marksBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    tvEmpty = label("Ninguna todavía.", 13f)
    marksBox.addView(tvEmpty)
    sc.addView(marksBox)
    right.addView(sc, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    right.addView(btn("🎬 Crear resumen", accent = true) { create() }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply { topMargin = dp(6) })
    right.addView(btn("🎞 Elegir otro vídeo") { pick.launch("video/*") }, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 0f).apply { topMargin = dp(6) })
    root.addView(right, lp(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    setContentView(root)

    video.setOnPreparedListener { mp ->
      durationMs = mp.duration
      seek.max = durationMs
      tvTime.text = "00:00 / ${fmt(durationMs)}"
    }
    video.setOnErrorListener { _, _, _ -> toast("No se puede reproducir este vídeo"); true }
    pick.launch("video/*")
  }

  override fun onResume() { super.onResume(); ui.post(ticker) }
  override fun onPause() { super.onPause(); ui.removeCallbacks(ticker); video.pause() }

  private fun load(u: Uri) {
    uri = u; marks.clear(); labels.clear(); durationMs = 0
    video.setVideoURI(u)
    video.seekTo(1)
    refreshMarks()
  }

  private fun markHere() {
    if (durationMs == 0) return
    val p = video.currentPosition
    marks += p
    refreshMarks()
    btnMark.text = "✓"
    (btnMark.background as? GradientDrawable)?.setColor(Color.rgb(61, 220, 132))
    ui.postDelayed({ btnMark.text = "⭐ Marcar jugada"; (btnMark.background as? GradientDrawable)?.setColor(color(R.color.accent)) }, 1000)
  }

  private fun refreshMarks() {
    marksBox.removeAllViews()
    if (marks.isEmpty()) { marksBox.addView(tvEmpty); return }
    marks.forEachIndexed { i, p ->
      val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2)) }
      val t = label("${i + 1}.  ${fmt(p)}" + (labels[p]?.let { "  ·  $it" } ?: ""), 14f, R.color.text).apply {
        setOnClickListener { video.seekTo(maxOf(0, p - (etBefore.text.toString().toIntOrNull() ?: 10) * 1000)); video.start() }
      }
      row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      row.addView(btn("✕") { marks.remove(p); labels.remove(p); refreshMarks() })
      marksBox.addView(row)
    }
  }

  /** Coloca en el vídeo las marcas de un partido emitido con SportCam (el vídeo del directo empieza al iniciar la emisión). */
  private fun applyMatchMarks() {
    if (durationMs == 0) return toast("Espera a que cargue el vídeo")
    val arr = try { JSONArray(intent.getStringExtra("matches") ?: "[]") } catch (_: Exception) { JSONArray() }
    val options = (0 until arr.length()).map { arr.getJSONObject(it) }
      .filter { it.optLong("liveStart") > 0 && (it.optJSONArray("marks")?.length() ?: 0) > 0 }
    if (options.isEmpty()) return toast("No hay partidos con marcas y hora de inicio del directo guardadas")
    AlertDialog.Builder(this)
      .setTitle("Marcas de qué partido")
      .setItems(options.map { "${it.optString("title")} (${it.optJSONArray("marks")?.length()} marcas)" }.toTypedArray()) { _, which ->
        val m = options[which]
        val ms0 = m.getJSONArray("marks")
        val nPlayer = (0 until ms0.length()).count { ms0.getJSONObject(it).optString("kind") == "carmen" }
        if (nPlayer == 0) { applyMarks(m, null); return@setItems }
        val name = intent.getStringExtra("playerName") ?: "Carmen"
        AlertDialog.Builder(this)
          .setTitle("¿Qué jugadas?")
          .setItems(arrayOf("⭐ Las del partido (${ms0.length() - nPlayer})", "💜 Las de $name ($nPlayer)", "Todas (${ms0.length()})")) { _, k ->
            applyMarks(m, when (k) { 0 -> false; 1 -> true; else -> null })
          }.show()
      }
      .setNegativeButton("Cancelar", null).show()
  }

  /** [player]: true solo 💜, false solo ⭐, null todas. */
  private fun applyMarks(m: org.json.JSONObject, player: Boolean?) {
      run {
        val start = m.optLong("liveStart")
        val offset = (etOffset.text.toString().toIntOrNull() ?: 0)
        getSharedPreferences("sportcam", MODE_PRIVATE).edit().putInt("hlOffset", offset).apply()
        val ms = m.getJSONArray("marks")
        var added = 0
        for (i in 0 until ms.length()) {
          val mk = ms.getJSONObject(i)
          if (player != null && (mk.optString("kind") == "carmen") != player) continue
          val p = (mk.optLong("ts") - start + offset * 1000L).toInt()
          if (p in 0..durationMs) { marks += p; labels[p] = mk.optString("label"); added++ }
        }
        refreshMarks()
        toast(if (added > 0) "$added marcas añadidas. Revisa alguna y ajusta el desfase si no cuadran." else "Ninguna marca cae dentro de este vídeo")
      }
  }

  private fun create() {
    val u = uri ?: return
    if (busy) return toast("Ya se está creando…")
    if (marks.isEmpty()) return toast("Marca al menos una jugada")
    val before = (etBefore.text.toString().toIntOrNull() ?: 10).coerceIn(0, 120)
    val after = (etAfter.text.toString().toIntOrNull() ?: 3).coerceIn(0, 60)
    val raw = marks.map { p -> maxOf(0L, (p - before * 1000L) * 1000L)..minOf(durationMs * 1000L, (p + after * 1000L) * 1000L) }
    val ranges = mutableListOf<LongRange>()
    for (r in raw.sortedBy { it.first }) {
      val last = ranges.lastOrNull()
      if (last != null && r.first <= last.last) ranges[ranges.size - 1] = last.first..maxOf(last.last, r.last) else ranges += r
    }
    busy = true
    video.pause()
    toast("Creando resumen de ${ranges.size} tramos…")
    Thread {
      val msg = try {
        val out = File(cacheDir, "resumen_video.mp4").apply { delete() }
        val n = Highlights.cut(applicationContext, u, ranges, out)
        val name = "VoleyCam_resumen_" + SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date()) + ".mp4"
        if (n > 0 && RecordingSaver.moveToGallery(applicationContext, out, name) != null) "Resumen guardado en Películas/VoleyCam ($n tramos)"
        else "No se pudo crear el resumen con este vídeo"
      } catch (e: Exception) { "No se pudo crear el resumen: ${e.message}" }
      busy = false
      runOnUiThread { toast(msg) }
    }.start()
  }

  private fun fmt(ms: Int): String {
    val t = ms / 1000
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60)
    else String.format(Locale.US, "%02d:%02d", t / 60, t % 60)
  }

  private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
}
