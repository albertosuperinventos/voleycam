package app.superinventos.sportcam

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Base64
import android.util.LruCache
import kotlin.math.min

/**
 * Pinta el marcador, rótulos, patrocinadores y pantallas completas en un Bitmap
 * transparente del tamaño del vídeo. La app lo superpone sobre la cámara.
 * Todas las medidas están pensadas para 1280x720 y se escalan.
 */
class ScoreboardRenderer(val width: Int, val height: Int) {

  private val k = width / 1280f
  private fun d(v: Float) = v * k
  private val margin get() = d(24f)

  private val accent = Color.rgb(198, 244, 50)
  private val ink = Color.rgb(11, 18, 32)
  private val dark = Color.argb(235, 11, 18, 32)
  private val dark2 = Color.argb(235, 27, 38, 60)
  private val serveColor = Color.rgb(255, 176, 32)

  private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed; color = Color.WHITE }
  private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

  private val logoCache = object : LruCache<String, Bitmap>(32) {}

  /** Imagen de portada (data URL). Se alterna en la pantalla PREPARTIDO. */
  var cover: String = ""

  /** Qué ocupa la pantalla completa en este instante. */
  private sealed class Screen {
    object None : Screen()
    object Break : Screen()
    object Cover : Screen()
    data class Spons(val sp: Sponsor) : Screen()
  }

  private fun screen(s: OverlayState, now: Long): Screen {
    s.fullSponsor?.let { return Screen.Spons(it) }
    if (!s.breakActive(now)) return Screen.None
    // Ciclo. Prepartido: portada, escudos, patrocinadores. Tiempo muerto y fin de set: pantalla y patrocinadores
    // (siempre con carrusel). El resto: carrusel solo si está activado.
    val forced = s.breakKind == "pre" || s.breakKind == "tm" || s.breakKind == "set"
    val slots = mutableListOf<Screen>()
    if (s.breakKind == "pre" && cover.isNotBlank()) slots += Screen.Cover
    slots += Screen.Break
    if (forced || s.sponsCarousel) s.sponsors.forEach { slots += Screen.Spons(it) }
    if (slots.size == 1) return slots[0]
    return slots[((now / 1000 / s.sponsSec) % slots.size).toInt()]
  }

  /** Clave que cambia cuando hay que volver a pintar. */
  fun signature(s: OverlayState?, version: Int, now: Long): String {
    if (s == null) return "none"
    val clockSec = if (!s.isVolley || s.timerShow) s.clockMs(now) / 1000 else 0
    val tick = if (s.sponsors.size > 1 || s.breakShow) now / 1000 / s.sponsSec else 0
    return "$version|$clockSec|$tick|${cover.length}|${s.breakActive(now)}"
  }

  /** Pinta sobre [bmp] (se borra antes). */
  fun render(s: OverlayState?, now: Long, bmp: Bitmap): Bitmap {
    bmp.eraseColor(Color.TRANSPARENT)
    if (s == null) return bmp
    val c = Canvas(bmp)
    when (val sc = screen(s, now)) {
      is Screen.Spons -> { drawFullSponsor(c, sc.sp); return bmp }
      Screen.Break -> { drawBreak(c, s); return bmp }
      Screen.Cover -> {
        rect(c, 0f, 0f, width.toFloat(), height.toFloat(), Color.BLACK)
        drawLogoFit(c, cover, RectF(0f, 0f, width.toFloat(), height.toFloat()))
        return bmp
      }
      Screen.None -> {}
    }

    // Marcador
    var board: RectF? = null
    if (s.boardShow) {
      val (bw, bh) = if (s.isVolley) volleySize(s) else footballSize(s)
      val left = if (s.boardPos.endsWith("r")) width - margin - bw else margin
      val top = if (s.boardPos.startsWith("b")) height - margin - bh else margin
      board = RectF(left, top, left + bw, top + bh)
      if (s.isVolley) drawVolley(c, s, now, left, top) else drawFootball(c, s, now, left, top)
    }
    val boardBottom = board != null && s.boardPos.startsWith("b")

    // Patrocinador en esquina: abajo a la derecha, o arriba a la derecha si ahí está el marcador
    var sponsorBox: RectF? = null
    if (s.sponsShow && s.sponsors.isNotEmpty()) {
      val atTop = board != null && s.boardPos == "br"
      sponsorBox = drawSponsorCorner(c, s, now, atTop)
    }

    // Rótulo inferior entre lo que haya abajo
    if (s.bannerShow && s.bannerText.isNotBlank()) {
      var left = d(40f)
      var right = width - d(40f)
      if (boardBottom && s.boardPos == "bl") left = board!!.right + margin
      if (boardBottom && s.boardPos == "br") right = board!!.left - margin
      sponsorBox?.let { if (it.bottom > height / 2f) right = min(right, it.left - margin) }
      if (right - left > d(160f)) drawBanner(c, s.bannerText, left, right)
    }
    return bmp
  }

  /* ---------------- Utilidades ---------------- */

  private fun logo(data: String): Bitmap? {
    if (data.isBlank()) return null
    val key = "${data.length}:${data.hashCode()}"
    logoCache.get(key)?.let { return it }
    return try {
      val b64 = data.substringAfter("base64,", data)
      val bytes = Base64.decode(b64, Base64.DEFAULT)
      BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.also { logoCache.put(key, it) }
    } catch (_: Exception) { null }
  }

  private fun drawLogoFit(c: Canvas, data: String, box: RectF) {
    val b = logo(data) ?: return
    val scale = min(box.width() / b.width, box.height() / b.height)
    val w = b.width * scale; val h = b.height * scale
    val l = box.centerX() - w / 2; val t = box.centerY() - h / 2
    c.drawBitmap(b, null, RectF(l, t, l + w, t + h), bmpPaint)
  }

  private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, color: Int, radius: Float = 0f) {
    fill.color = color
    if (radius > 0) c.drawRoundRect(RectF(l, t, r, b), radius, radius, fill) else c.drawRect(l, t, r, b, fill)
  }

  /** Texto centrado verticalmente en [top,bottom]; align: -1 izq, 0 centro, 1 der. Encoge si no cabe. */
  private fun label(c: Canvas, str: String, x: Float, top: Float, bottom: Float, sizePx: Float, color: Int, align: Int = -1, maxW: Float = Float.MAX_VALUE) {
    text.color = color
    text.textSize = sizePx
    // Sombra solo en textos claros sobre la imagen (no en números oscuros sobre casillas blancas)
    if (shadowOn && color != ink) text.setShadowLayer(d(6f), 0f, d(2f), Color.argb(220, 0, 0, 0)) else text.clearShadowLayer()
    var w = text.measureText(str)
    if (w > maxW) { text.textSize = sizePx * maxW / w; w = text.measureText(str) }
    val fm = text.fontMetrics
    val baseline = (top + bottom) / 2 - (fm.ascent + fm.descent) / 2
    val left = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
    c.drawText(str, left, baseline, text)
  }

  /* ---------------- Voleibol ---------------- */

  private val vRowH get() = d(46f)
  private val vGap get() = d(2f)
  private val vTabH get() = d(26f)
  /** Ancho de la columna de nombres según el nombre más largo de los dos equipos. */
  private fun nameWidth(s: OverlayState, sizeDp: Float, minDp: Float, maxDp: Float): Float {
    text.textSize = d(sizeDp)
    val w = maxOf(text.measureText(s.home.short), text.measureText(s.away.short)) + d(18f)
    return w.coerceIn(d(minDp), d(maxDp))
  }

  private fun vNameW(s: OverlayState) = nameWidth(s, 30f, 130f, 300f)
  private fun vTotalW(s: OverlayState) = d(8f + 46f + 20f + 40f + 58f) + vNameW(s)

  private fun volleySize(s: OverlayState) = vTotalW(s) to (vRowH * 2 + vGap * 2 + vTabH)

  private fun drawVolley(c: Canvas, s: OverlayState, now: Long, x: Float, y0: Float) {
    var y = y0
    val wBar = d(8f); val wLogo = d(46f); val wName = vNameW(s); val wServe = d(20f); val wSets = d(40f); val wPts = d(58f)
    val totalW = vTotalW(s)
    val rowH = vRowH
    for ((team, side) in listOf(s.home to "h", s.away to "a")) {
      var cx = x
      rect(c, cx, y, cx + totalW, y + rowH, dark)
      rect(c, cx, y, cx + wBar, y + rowH, team.color); cx += wBar
      if (team.logo.isNotBlank()) drawLogoFit(c, team.logo, RectF(cx + d(5f), y + d(5f), cx + wLogo - d(5f), y + rowH - d(5f)))
      else { fill.color = team.color; c.drawCircle(cx + wLogo / 2, y + rowH / 2, d(14f), fill) }
      cx += wLogo
      label(c, team.short, cx + d(6f), y - d(3f), y + rowH - d(3f), d(30f), Color.WHITE, -1, wName - d(10f))
      // Tiempos muertos: dos rayitas; las gastadas se apagan
      val used = if (side == "h") s.timeoutsH else s.timeoutsA
      for (i in 0 until 2) {
        val bx = cx + d(6f) + i * d(18f)
        rect(c, bx, y + rowH - d(8f), bx + d(14f), y + rowH - d(4f), if (i < used) Color.argb(70, 255, 255, 255) else accent)
      }
      cx += wName
      if (s.serve == side) { fill.color = serveColor; c.drawCircle(cx + wServe / 2, y + rowH / 2, d(6f), fill) }
      cx += wServe
      rect(c, cx, y, cx + wSets, y + rowH, dark2)
      label(c, (if (side == "h") s.setsH else s.setsA).toString(), cx + wSets / 2, y, y + rowH, d(28f), Color.WHITE, 0); cx += wSets
      rect(c, cx, y, cx + wPts, y + rowH, Color.WHITE)
      label(c, (if (side == "h") s.scoreH else s.scoreA).toString(), cx + wPts / 2, y, y + rowH, d(34f), ink, 0)
      y += rowH + vGap
    }
    val tab = if (s.finished) "FINAL" else "SET ${s.setNo}"
    rect(c, x, y, x + d(110f), y + vTabH, accent)
    label(c, tab, x + d(55f), y, y + vTabH, d(20f), ink, 0)
    if (s.timerShow) {
      val t = s.clockMs(now) / 1000
      val tx = x + d(110f)
      rect(c, tx, y, tx + d(92f), y + vTabH, dark2)
      label(c, "%02d:%02d".format(t / 60, t % 60), tx + d(46f), y, y + vTabH, d(22f), Color.WHITE, 0)
    }
  }

  /* ---------------- Fútbol ---------------- */

  private val fH get() = d(50f)
  private val fTabH get() = d(30f)
  private fun fNameW(s: OverlayState) = nameWidth(s, 30f, 84f, 230f)
  private fun fTotalW(s: OverlayState) = d((6f + 50f) * 2 + 112f) + fNameW(s) * 2

  private fun footballSize(s: OverlayState) = fTotalW(s) to (fH + fTabH)

  private fun drawFootball(c: Canvas, s: OverlayState, now: Long, x: Float, y: Float) {
    val h = fH
    val wBar = d(6f); val wLogo = d(50f); val wName = fNameW(s); val wScore = d(112f)
    val total = fTotalW(s)
    rect(c, x, y, x + total, y + h, dark)
    var cx = x
    rect(c, cx, y, cx + wBar, y + h, s.home.color); cx += wBar
    teamLogo(c, s.home, cx, y, wLogo, h); cx += wLogo
    label(c, s.home.short, cx + wName / 2, y, y + h, d(30f), Color.WHITE, 0, wName - d(8f)); cx += wName
    rect(c, cx, y, cx + wScore, y + h, Color.WHITE)
    label(c, "${s.scoreH} - ${s.scoreA}", cx + wScore / 2, y, y + h, d(36f), ink, 0); cx += wScore
    label(c, s.away.short, cx + wName / 2, y, y + h, d(30f), Color.WHITE, 0, wName - d(8f)); cx += wName
    teamLogo(c, s.away, cx, y, wLogo, h); cx += wLogo
    rect(c, cx, y, cx + wBar, y + h, s.away.color)

    val t = s.clockMs(now) / 1000
    val clock = "%02d:%02d".format(t / 60, t % 60)
    val tabY = y + h
    val tabW = d(210f); val tabX = x + (total - tabW) / 2
    rect(c, tabX, tabY, tabX + tabW, tabY + fTabH, dark2)
    rect(c, tabX, tabY, tabX + d(80f), tabY + fTabH, accent)
    label(c, clock, tabX + d(40f), tabY, tabY + fTabH, d(24f), ink, 0)
    label(c, s.period, tabX + d(80f) + (tabW - d(80f)) / 2, tabY, tabY + fTabH, d(18f), Color.WHITE, 0, tabW - d(90f))
  }

  private fun teamLogo(c: Canvas, t: TeamInfo, x: Float, y: Float, w: Float, h: Float) {
    if (t.logo.isNotBlank()) drawLogoFit(c, t.logo, RectF(x + d(6f), y + d(6f), x + w - d(6f), y + h - d(6f)))
    else { fill.color = t.color; c.drawCircle(x + w / 2, y + h / 2, d(15f), fill) }
  }

  /* ---------------- Rótulo inferior ---------------- */

  private fun drawBanner(c: Canvas, str: String, left: Float, maxRight: Float) {
    val h = d(58f); val bottom = height - d(40f); val top = bottom - h
    text.textSize = d(32f)
    val w = min(text.measureText(str) + d(52f), maxRight - left)
    rect(c, left, top, left + w, bottom, dark, d(6f))
    rect(c, left, top, left + d(8f), bottom, accent)
    label(c, str, left + d(28f), top, bottom, d(32f), Color.WHITE, -1, w - d(44f))
  }

  /* ---------------- Patrocinadores ---------------- */

  private fun drawSponsorCorner(c: Canvas, s: OverlayState, now: Long, atTop: Boolean): RectF {
    val n = s.sponsors.size
    val idx = ((now / 1000 / s.sponsSec) % n).toInt()
    val bw = d(170f); val bh = d(86f)
    val r = width - d(32f)
    val t = if (atTop) d(32f) else height - d(32f) - bh
    val box = RectF(r - bw, t, r, t + bh)
    rect(c, box.left, box.top, box.right, box.bottom, Color.argb(225, 255, 255, 255), d(10f))
    drawLogoFit(c, s.sponsors[idx].logo.ifBlank { s.sponsors[idx].full }, RectF(box.left + d(10f), box.top + d(8f), box.right - d(10f), box.bottom - d(8f)))
    return box
  }

  /** Fondo semitransparente de las pantallas completas: se ve la pista detrás. */
  private val fullBg = Color.argb(130, 8, 12, 22)

  private var shadowOn = false

  private fun withShadow(block: () -> Unit) {
    shadowOn = true
    try { block() } finally { shadowOn = false; text.clearShadowLayer() }
  }

  private fun drawFullSponsor(c: Canvas, sp: Sponsor) = withShadow {
    rect(c, 0f, 0f, width.toFloat(), height.toFloat(), fullBg)
    if (sp.full.isNotBlank()) {
      // Diapositiva del patrocinador a toda pantalla
      drawLogoFit(c, sp.full, RectF(0f, 0f, width.toFloat(), height.toFloat()))
      return@withShadow
    }
    val hasText = sp.text.isNotBlank()
    // Tarjeta blanca para que se vean bien logos oscuros o con transparencia
    val cardW = d(760f); val cardH = d(if (hasText) 400f else 460f)
    val cx = width / 2f
    val top = (height - cardH - (if (hasText) d(90f) else 0f)) / 2f
    val card = RectF(cx - cardW / 2, top, cx + cardW / 2, top + cardH)
    rect(c, card.left, card.top, card.right, card.bottom, Color.WHITE, d(18f))
    drawLogoFit(c, sp.logo, RectF(card.left + d(40f), card.top + d(36f), card.right - d(40f), card.bottom - d(36f)))
    if (hasText) label(c, sp.text, cx, card.bottom + d(24f), card.bottom + d(84f), d(40f), Color.WHITE, 0, width - d(160f))
  }

  /* ---------------- Pantallas completas ---------------- */

  private fun drawBreak(c: Canvas, s: OverlayState) = withShadow {
    rect(c, 0f, 0f, width.toFloat(), height.toFloat(), fullBg)
    label(c, s.breakText, width / 2f, d(60f), d(140f), d(68f), accent, 0, width - d(120f))
    when {
      s.breakKind == "pre" -> drawPreMatch(c, s)
      s.isVolley && (s.breakKind == "set" || s.breakKind == "end") && s.history.isNotEmpty() -> drawSetTable(c, s, s.breakKind == "end")
      else -> drawResult(c, s)
    }
  }

  private fun bigLogo(c: Canvas, t: TeamInfo, box: RectF) {
    if (t.logo.isNotBlank()) drawLogoFit(c, t.logo, box)
    else { fill.color = t.color; c.drawCircle(box.centerX(), box.centerY(), min(box.width(), box.height()) / 2.4f, fill) }
  }

  /** Prepartido: escudos grandes, nombres y VS. */
  private fun drawPreMatch(c: Canvas, s: OverlayState) {
    val cx = width / 2f
    val logoSize = d(240f); val top = d(190f)
    for ((t, x) in listOf(s.home to cx - d(320f), s.away to cx + d(320f))) {
      bigLogo(c, t, RectF(x - logoSize / 2, top, x + logoSize / 2, top + logoSize))
      label(c, t.name.uppercase(), x, top + logoSize + d(20f), top + logoSize + d(76f), d(38f), Color.WHITE, 0, d(500f))
    }
    label(c, "VS", cx, top + d(60f), top + logoSize - d(60f), d(96f), Color.WHITE, 0)
  }

  /** Resultado: escudos, sets (o goles) y puntos del set en juego. */
  private fun drawResult(c: Canvas, s: OverlayState) {
    val cx = width / 2f
    val logoSize = d(190f); val cy = d(220f)
    for ((t, x) in listOf(s.home to cx - d(330f), s.away to cx + d(330f))) {
      bigLogo(c, t, RectF(x - logoSize / 2, cy, x + logoSize / 2, cy + logoSize))
      label(c, t.name.uppercase(), x, cy + logoSize + d(14f), cy + logoSize + d(64f), d(34f), Color.WHITE, 0, d(400f))
    }
    val main = if (s.isVolley) "${s.setsH} - ${s.setsA}" else "${s.scoreH} - ${s.scoreA}"
    label(c, main, cx, cy + d(30f), cy + logoSize - d(10f), d(120f), Color.WHITE, 0)
    if (s.isVolley) {
      label(c, "SETS", cx, cy - d(10f), cy + d(26f), d(24f), Color.argb(200, 255, 255, 255), 0)
      val parts = s.history.map { "${it.first}-${it.second}" }.toMutableList()
      if (!s.finished && (s.scoreH > 0 || s.scoreA > 0)) parts += "SET ${s.setNo}: ${s.scoreH}-${s.scoreA}"
      if (parts.isNotEmpty()) label(c, parts.joinToString("   "), cx, cy + logoSize + d(84f), cy + logoSize + d(128f), d(32f), Color.argb(230, 255, 255, 255), 0, width - d(160f))
    } else if (s.period.isNotBlank()) {
      label(c, s.period, cx, cy + logoSize + d(84f), cy + logoSize + d(128f), d(32f), Color.argb(230, 255, 255, 255), 0)
    }
  }

  /** Fin de set / fin de partido: tabla con el resultado de cada set. */
  private fun drawSetTable(c: Canvas, s: OverlayState, isFinal: Boolean) {
    val n = s.history.size
    val colW = d(96f); val setsW = d(120f); val logoW = d(84f)
    val nameW = (width - d(120f) - logoW - n * colW - setsW).coerceAtMost(d(420f))
    val tableW = logoW + nameW + n * colW + setsW
    val left = (width - tableW) / 2f
    val headH = d(48f); val rowH = d(104f)
    var y = d(200f)

    // Cabecera
    var x = left + logoW + nameW
    for (i in 0 until n) { label(c, "SET ${i + 1}", x + colW / 2, y, y + headH, d(24f), Color.argb(200, 255, 255, 255), 0); x += colW }
    label(c, "SETS", x + setsW / 2, y, y + headH, d(24f), accent, 0)
    y += headH

    val winner = when { !isFinal -> ""; s.setsH > s.setsA -> "h"; s.setsA > s.setsH -> "a"; else -> "" }
    for ((t, side) in listOf(s.home to "h", s.away to "a")) {
      val isWin = winner == side
      rect(c, left, y, left + tableW, y + rowH - d(6f), if (isWin) Color.argb(235, 40, 52, 20) else dark, d(10f))
      rect(c, left, y, left + d(8f), y + rowH - d(6f), t.color)
      bigLogo(c, t, RectF(left + d(16f), y + d(12f), left + logoW - d(4f), y + rowH - d(18f)))
      label(c, t.name.uppercase(), left + logoW + d(10f), y, y + rowH - d(6f), d(36f), if (isWin) accent else Color.WHITE, -1, nameW - d(20f))
      x = left + logoW + nameW
      for ((h, a) in s.history) {
        val mine = if (side == "h") h else a
        val theirs = if (side == "h") a else h
        label(c, mine.toString(), x + colW / 2, y, y + rowH - d(6f), d(48f),
          if (mine > theirs) Color.WHITE else Color.argb(140, 255, 255, 255), 0)
        x += colW
      }
      rect(c, x + d(8f), y + d(10f), x + setsW - d(8f), y + rowH - d(16f), if (isWin) accent else Color.WHITE, d(8f))
      label(c, (if (side == "h") s.setsH else s.setsA).toString(), x + setsW / 2, y, y + rowH - d(6f), d(56f), ink, 0)
      y += rowH
    }
    if (isFinal && winner.isNotEmpty()) {
      val w = if (winner == "h") s.home else s.away
      label(c, "GANADOR: ${w.name.uppercase()}", width / 2f, y + d(20f), y + d(76f), d(34f), accent, 0, width - d(160f))
    }
  }

  /* ---------------- Vídeo resumen y repetición ---------------- */

  /** Letra del título del resumen (Barlow Condensed Black). */
  var titleFont: Typeface? = null

  /** Portada del vídeo resumen (opaca): resultado por sets y una línea con fecha y competición; con o sin título. */
  fun renderIntro(s: OverlayState, subtitle: String, bmp: Bitmap, withTitle: Boolean = true): Bitmap {
    bmp.eraseColor(ink)
    val c = Canvas(bmp)
    withShadow {
      if (s.isVolley && s.history.isNotEmpty()) drawSetTable(c, s, true) else drawResult(c, s)
      if (subtitle.isNotBlank()) label(c, subtitle, width / 2f, height - d(90f), height - d(40f), d(30f), Color.argb(220, 255, 255, 255), 0, width - d(120f))
    }
    if (withTitle) drawTitle(c)
    return bmp
  }

  /** Solo el título "MEJORES JUGADAS" sobre transparente, del tamaño del vídeo (para animarlo). */
  fun renderTitle(bmp: Bitmap): Bitmap {
    bmp.eraseColor(Color.TRANSPARENT)
    drawTitle(Canvas(bmp))
    return bmp
  }

  private fun drawTitle(c: Canvas) {
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = titleFont ?: condensed; textSize = d(104f); color = accent; textAlign = Paint.Align.CENTER
      letterSpacing = 0.02f
      setShadowLayer(d(10f), 0f, d(4f), Color.argb(230, 0, 0, 0))
    }
    val str = "MEJORES JUGADAS"
    val w = p.measureText(str)
    if (w > width - d(80f)) p.textSize = p.textSize * (width - d(80f)) / w
    val fm = p.fontMetrics
    val base = d(105f) - (fm.ascent + fm.descent) / 2
    c.drawText(str, width / 2f, base, p)
  }

  /** Diapositiva de patrocinador para el final del vídeo resumen (opaca). */
  fun renderSponsorSlide(sp: Sponsor, bmp: Bitmap): Bitmap {
    bmp.eraseColor(ink)
    drawFullSponsor(Canvas(bmp), sp)
    return bmp
  }

  private val replayRect = RectF()

  /** Repetición: el fotograma guardado a toda pantalla y el rótulo REPETICIÓN. */
  fun renderReplay(frame: Bitmap, slow: Boolean, bmp: Bitmap): Bitmap {
    val c = Canvas(bmp)
    replayRect.set(0f, 0f, width.toFloat(), height.toFloat())
    c.drawBitmap(frame, null, replayRect, bmpPaint)
    val txt = if (slow) "REPETICIÓN · CÁMARA LENTA" else "REPETICIÓN"
    text.textSize = d(30f)
    val w = text.measureText(txt) + d(44f)
    rect(c, d(28f), d(28f), d(28f) + w, d(28f) + d(52f), accent, d(6f))
    label(c, txt, d(28f) + d(22f), d(28f), d(80f), d(30f), ink, -1)
    return bmp
  }
}
