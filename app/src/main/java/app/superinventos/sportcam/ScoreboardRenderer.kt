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
 * Pinta el marcador, rótulos, patrocinadores y pantalla de descanso en un Bitmap
 * transparente del tamaño del vídeo. La app lo superpone sobre la cámara.
 * Todas las medidas están pensadas para 1280x720 y se escalan.
 */
class ScoreboardRenderer(val width: Int, val height: Int) {

  private val k = width / 1280f
  private fun d(v: Float) = v * k

  private val accent = Color.rgb(198, 244, 50)
  private val dark = Color.argb(235, 11, 18, 32)
  private val dark2 = Color.argb(235, 27, 38, 60)
  private val serveColor = Color.rgb(255, 176, 32)

  private val condensed: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
  private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = condensed; color = Color.WHITE }
  private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

  private val logoCache = object : LruCache<String, Bitmap>(24) {}

  /** Clave que cambia cuando hay que volver a pintar. */
  fun signature(s: OverlayState?, version: Int, now: Long): String {
    if (s == null) return "none"
    val clockSec = if (!s.isVolley) s.clockMs(now) / 1000 else 0
    val spIdx = if (s.sponsShow && s.sponsorLogos.size > 1) (now / 1000 / s.sponsSec) % s.sponsorLogos.size else 0
    return "$version|$clockSec|$spIdx"
  }

  /** Pinta sobre [bmp] (se borra antes). */
  fun render(s: OverlayState?, now: Long, bmp: Bitmap): Bitmap {
    bmp.eraseColor(Color.TRANSPARENT)
    if (s == null) return bmp
    val c = Canvas(bmp)
    var sponsorLeft = width.toFloat()
    if (s.sponsShow && s.sponsorLogos.isNotEmpty()) sponsorLeft = drawSponsor(c, s, now)
    if (s.breakShow) {
      drawBreak(c, s)
    } else {
      if (s.isVolley) drawVolley(c, s) else drawFootball(c, s, now)
      if (s.bannerShow && s.bannerText.isNotBlank()) drawBanner(c, s.bannerText, sponsorLeft)
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
    var w = text.measureText(str)
    if (w > maxW) { text.textSize = sizePx * maxW / w; w = text.measureText(str) }
    val fm = text.fontMetrics
    val baseline = (top + bottom) / 2 - (fm.ascent + fm.descent) / 2
    val left = when (align) { 0 -> x - w / 2; 1 -> x - w; else -> x }
    c.drawText(str, left, baseline, text)
  }

  /* ---------------- Voleibol ---------------- */

  private fun drawVolley(c: Canvas, s: OverlayState) {
    val x = d(24f); var y = d(24f)
    val rowH = d(46f); val gap = d(2f)
    val wBar = d(8f); val wLogo = d(46f); val wName = d(130f); val wServe = d(20f); val wSets = d(40f); val wPts = d(58f)
    val total = wBar + wLogo + wName + wServe + wSets + wPts
    for ((team, side) in listOf(s.home to "h", s.away to "a")) {
      var cx = x
      rect(c, cx, y, cx + total, y + rowH, dark)
      rect(c, cx, y, cx + wBar, y + rowH, team.color); cx += wBar
      if (team.logo.isNotBlank()) drawLogoFit(c, team.logo, RectF(cx + d(5f), y + d(5f), cx + wLogo - d(5f), y + rowH - d(5f)))
      else { fill.color = team.color; c.drawCircle(cx + wLogo / 2, y + rowH / 2, d(14f), fill) }
      cx += wLogo
      label(c, team.short, cx + d(6f), y, y + rowH, d(30f), Color.WHITE, -1, wName - d(10f)); cx += wName
      if (s.serve == side) { fill.color = serveColor; c.drawCircle(cx + wServe / 2, y + rowH / 2, d(6f), fill) }
      cx += wServe
      rect(c, cx, y, cx + wSets, y + rowH, dark2)
      label(c, (if (side == "h") s.setsH else s.setsA).toString(), cx + wSets / 2, y, y + rowH, d(28f), Color.WHITE, 0); cx += wSets
      rect(c, cx, y, cx + wPts, y + rowH, Color.WHITE)
      label(c, (if (side == "h") s.scoreH else s.scoreA).toString(), cx + wPts / 2, y, y + rowH, d(34f), Color.rgb(11, 18, 32), 0)
      y += rowH + gap
    }
    val tab = if (s.finished) "FINAL" else "SET ${s.setNo}"
    rect(c, x, y, x + d(110f), y + d(26f), accent)
    label(c, tab, x + d(55f), y, y + d(26f), d(20f), Color.rgb(11, 18, 32), 0)
  }

  /* ---------------- Fútbol ---------------- */

  private fun drawFootball(c: Canvas, s: OverlayState, now: Long) {
    val x = d(24f); val y = d(24f); val h = d(50f)
    val wBar = d(6f); val wLogo = d(50f); val wName = d(84f); val wScore = d(112f)
    val total = (wBar + wLogo + wName) * 2 + wScore
    rect(c, x, y, x + total, y + h, dark)
    var cx = x
    rect(c, cx, y, cx + wBar, y + h, s.home.color); cx += wBar
    teamLogo(c, s.home, cx, y, wLogo, h); cx += wLogo
    label(c, s.home.short, cx + wName / 2, y, y + h, d(30f), Color.WHITE, 0, wName - d(8f)); cx += wName
    rect(c, cx, y, cx + wScore, y + h, Color.WHITE)
    label(c, "${s.scoreH} - ${s.scoreA}", cx + wScore / 2, y, y + h, d(36f), Color.rgb(11, 18, 32), 0); cx += wScore
    label(c, s.away.short, cx + wName / 2, y, y + h, d(30f), Color.WHITE, 0, wName - d(8f)); cx += wName
    teamLogo(c, s.away, cx, y, wLogo, h); cx += wLogo
    rect(c, cx, y, cx + wBar, y + h, s.away.color)

    val ms = s.clockMs(now)
    val t = ms / 1000
    val clock = "%02d:%02d".format(t / 60, t % 60)
    val tabY = y + h
    val tabW = d(210f); val tabX = x + (total - tabW) / 2
    rect(c, tabX, tabY, tabX + tabW, tabY + d(30f), dark2)
    rect(c, tabX, tabY, tabX + d(80f), tabY + d(30f), accent)
    label(c, clock, tabX + d(40f), tabY, tabY + d(30f), d(24f), Color.rgb(11, 18, 32), 0)
    label(c, s.period, tabX + d(80f) + (tabW - d(80f)) / 2, tabY, tabY + d(30f), d(18f), Color.WHITE, 0, tabW - d(90f))
  }

  private fun teamLogo(c: Canvas, t: TeamInfo, x: Float, y: Float, w: Float, h: Float) {
    if (t.logo.isNotBlank()) drawLogoFit(c, t.logo, RectF(x + d(6f), y + d(6f), x + w - d(6f), y + h - d(6f)))
    else { fill.color = t.color; c.drawCircle(x + w / 2, y + h / 2, d(15f), fill) }
  }

  /* ---------------- Rótulo inferior ---------------- */

  private fun drawBanner(c: Canvas, str: String, rightLimit: Float) {
    val left = d(40f)
    val maxRight = rightLimit - d(24f)
    val h = d(58f); val bottom = height - d(40f); val top = bottom - h
    text.textSize = d(32f)
    val w = min(text.measureText(str) + d(52f), maxRight - left)
    rect(c, left, top, left + w, bottom, dark, d(6f))
    rect(c, left, top, left + d(8f), bottom, accent)
    label(c, str, left + d(28f), top, bottom, d(32f), Color.WHITE, -1, w - d(44f))
  }

  /* ---------------- Patrocinadores ---------------- */

  /** Devuelve la X izquierda de la caja para no solapar el rótulo. */
  private fun drawSponsor(c: Canvas, s: OverlayState, now: Long): Float {
    val n = s.sponsorLogos.size
    val idx = ((now / 1000 / s.sponsSec) % n).toInt()
    val bw = d(170f); val bh = d(86f)
    val r = width - d(32f); val b = height - d(32f)
    val box = RectF(r - bw, b - bh, r, b)
    rect(c, box.left, box.top, box.right, box.bottom, Color.argb(225, 255, 255, 255), d(10f))
    drawLogoFit(c, s.sponsorLogos[idx], RectF(box.left + d(10f), box.top + d(8f), box.right - d(10f), box.bottom - d(8f)))
    return box.left
  }

  /* ---------------- Pantalla completa ---------------- */

  private fun drawBreak(c: Canvas, s: OverlayState) {
    rect(c, 0f, 0f, width.toFloat(), height.toFloat(), Color.argb(215, 8, 12, 22))
    val cx = width / 2f
    label(c, s.breakText, cx, d(90f), d(170f), d(72f), accent, 0, width - d(120f))

    val logoSize = d(170f); val cy = d(250f)
    val lx = cx - d(330f); val rx = cx + d(330f)
    for ((t, x) in listOf(s.home to lx, s.away to rx)) {
      val box = RectF(x - logoSize / 2, cy, x + logoSize / 2, cy + logoSize)
      if (t.logo.isNotBlank()) drawLogoFit(c, t.logo, box)
      else { fill.color = t.color; c.drawCircle(box.centerX(), box.centerY(), logoSize / 2.4f, fill) }
      label(c, t.name.uppercase(), x, cy + logoSize + d(14f), cy + logoSize + d(64f), d(34f), Color.WHITE, 0, d(380f))
    }
    val main = if (s.isVolley) "${s.setsH} - ${s.setsA}" else "${s.scoreH} - ${s.scoreA}"
    label(c, main, cx, cy + d(20f), cy + logoSize - d(20f), d(120f), Color.WHITE, 0)
    if (s.isVolley) {
      label(c, "SETS", cx, cy - d(12f), cy + d(24f), d(24f), Color.argb(200, 255, 255, 255), 0)
      val parts = s.history.map { "${it.first}-${it.second}" }.toMutableList()
      if (!s.finished && (s.scoreH > 0 || s.scoreA > 0)) parts += "(${s.scoreH}-${s.scoreA})"
      if (parts.isNotEmpty()) label(c, parts.joinToString("   "), cx, cy + logoSize + d(80f), cy + logoSize + d(124f), d(30f), Color.argb(220, 255, 255, 255), 0, width - d(160f))
    } else if (s.period.isNotBlank()) {
      label(c, s.period, cx, cy + logoSize + d(80f), cy + logoSize + d(124f), d(30f), Color.argb(220, 255, 255, 255), 0)
    }
  }
}
