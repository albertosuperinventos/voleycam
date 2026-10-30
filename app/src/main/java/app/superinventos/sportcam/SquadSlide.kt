package app.superinventos.sportcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Una jugadora convocada. kind: "foto" (se le pone el cromo) o "cromo" (imagen ya hecha, tal cual). */
data class SquadPlayer(val dorsal: String, val name: String, val pos: String, val captain: Boolean, val img: String, val kind: String)

/** Convocatoria para la diapositiva de plantilla del PREPARTIDO. */
data class Squad(val teamName: String, val teamLogo: String, val tag: String, val players: List<SquadPlayer>) {
  /** 7 por pantalla (fila de 4 + fila de 3 y el escudo en el hueco); con 15-16, de 8 en 8. */
  val perPage get() = if (players.size <= 14) 7 else 8
  val pages get() = if (players.isEmpty()) 0 else ceil(players.size / perPage.toDouble()).toInt()

  companion object {
    fun parse(json: String): Squad? = try {
      if (json.isBlank()) null else {
        val o = JSONObject(json)
        val arr = o.optJSONArray("players")
        val list = (0 until (arr?.length() ?: 0)).map { i ->
          val p = arr!!.getJSONObject(i)
          SquadPlayer(p.optString("d"), p.optString("n"), p.optString("p"), p.optBoolean("c"), p.optString("img"), p.optString("kind", "foto"))
        }
        if (list.isEmpty()) null else Squad(o.optString("name"), o.optString("logo"), o.optString("tag"), list)
      }
    } catch (_: Exception) { null }
  }
}

/**
 * Dibuja una pantalla de la plantilla con el estilo de los cromos del club:
 * fondo granate con rayas, borde dorado, dorsal gigante contorneado, foto, nombre y posición.
 * Medidas pensadas para 1280x720 y escaladas con [k].
 */
class SquadSlide(private val width: Int, private val height: Int, private val font: Typeface,
                 private val image: (String) -> Bitmap?) {

  private val k = width / 1280f
  private fun d(v: Float) = v * k

  private val gold = Color.rgb(226, 176, 60)
  private val wine = Color.rgb(110, 14, 24)
  private val wine2 = Color.rgb(135, 20, 32)
  private val bgDark = Color.rgb(30, 4, 10)
  private val bgStripe = Color.rgb(40, 6, 14)

  private val p = Paint(Paint.ANTI_ALIAS_FLAG)
  private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font }

  fun draw(c: Canvas, sq: Squad, page: Int) {
    val total = sq.pages
    // Fondo
    p.style = Paint.Style.FILL; p.shader = null
    p.color = bgDark; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
    stripes(c, RectF(0f, 0f, width.toFloat(), height.toFloat()), d(40f), d(14f), bgStripe)
    // Cabecera
    val headH = d(80f)
    p.color = wine; c.drawRect(0f, 0f, width.toFloat(), headH, p)
    p.color = gold; c.drawRect(0f, headH, width.toFloat(), headH + d(3f), p)
    fit(c, sq.teamLogo, RectF(d(20f), d(8f), d(84f), d(72f)))
    text(c, sq.teamName.ifBlank { "PLANTILLA" }, d(100f), headH / 2, d(44f), Color.WHITE, Paint.Align.LEFT, width - d(260f))
    if (total > 1) text(c, "${page + 1}/$total", width - d(40f), headH / 2, d(44f), gold, Paint.Align.RIGHT)

    // Tarjetas
    val per = sq.perPage
    val items: List<SquadPlayer?> = sq.players.drop(page * per).take(per).let { if (it.size < 8) it + listOf(null) else it }
    val cw = d(220f); val ch = d(296f); val gx = d(24f); val gy = d(14f); val y0 = headH + d(20f)
    items.forEachIndexed { i, pl ->
      val row = i / 4
      val inRow = min(4, items.size - row * 4)
      val xs = (width - (inRow * cw + (inRow - 1) * gx)) / 2f
      val x = xs + (i % 4) * (cw + gx); val y = y0 + row * (ch + gy)
      val r = RectF(x, y, x + cw, y + ch)
      if (pl == null) crestSlot(c, sq, r) else card(c, sq, pl, r)
    }
  }

  private fun crestSlot(c: Canvas, sq: Squad, r: RectF) {
    val s = min(r.width(), r.height() * 0.72f) * 0.9f
    fit(c, sq.teamLogo, RectF(r.centerX() - s / 2, r.top + d(18f), r.centerX() + s / 2, r.top + d(18f) + s))
    if (sq.tag.isNotBlank()) text(c, sq.tag.uppercase(), r.centerX(), r.top + d(18f) + s + d(34f), d(30f), gold, Paint.Align.CENTER, r.width() + d(40f))
  }

  private fun card(c: Canvas, sq: Squad, pl: SquadPlayer, r: RectF) {
    val rad = d(14f)
    val clip = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
    val bmp = image(pl.img)
    c.save(); c.clipPath(clip)
    if (pl.kind == "cromo" && bmp != null) {
      cover(c, bmp, r, 0.5f)
      c.restore()
      return
    }
    p.shader = null; p.style = Paint.Style.FILL; p.color = wine; c.drawRect(r, p)
    stripes(c, r, d(25f), d(10f), wine2)
    // Dorsal gigante contorneado
    t.style = Paint.Style.STROKE; t.strokeWidth = d(3f); t.color = gold; t.textAlign = Paint.Align.CENTER
    t.textSize = r.height() * 0.62f; t.clearShadowLayer()
    val fm = t.fontMetrics
    c.drawText(pl.dorsal, r.centerX(), r.top + r.height() * 0.42f - (fm.ascent + fm.descent) / 2, t)
    t.style = Paint.Style.FILL
    // Foto o silueta
    if (bmp != null) {
      // Foto recortada (PNG/WebP sin fondo): al 86 % del ancho, desde el 13 % de arriba, para que se vea el dorsal detrás
      val fw = r.width() * 0.86f; val fh = fw * bmp.height / bmp.width
      val l = r.centerX() - fw / 2; val tp = r.top + r.height() * 0.13f
      c.drawBitmap(bmp, null, RectF(l, tp, l + fw, tp + fh), bmpPaint)
    }
    else {
      p.color = Color.rgb(235, 205, 185); c.drawOval(RectF(r.left + r.width() * 0.36f, r.top + r.height() * 0.2f, r.left + r.width() * 0.64f, r.top + r.height() * 0.44f), p)
      p.color = Color.rgb(240, 240, 250); c.drawOval(RectF(r.left + r.width() * 0.18f, r.top + r.height() * 0.43f, r.right - r.width() * 0.18f, r.bottom + r.height() * 0.3f), p)
    }
    // Degradado inferior para que se lea el nombre
    p.shader = LinearGradient(0f, r.top + r.height() * 0.6f, 0f, r.bottom, Color.argb(0, 40, 0, 6), Color.argb(235, 40, 0, 6), Shader.TileMode.CLAMP)
    c.drawRect(r.left, r.top + r.height() * 0.6f, r.right, r.bottom, p); p.shader = null
    c.restore()
    // Borde dorado y escudo
    p.style = Paint.Style.STROKE; p.strokeWidth = d(3.5f); p.color = gold
    c.drawRoundRect(RectF(r.left + d(1.5f), r.top + d(1.5f), r.right - d(1.5f), r.bottom - d(1.5f)), rad, rad, p)
    p.style = Paint.Style.FILL
    fit(c, sq.teamLogo, RectF(r.left + d(10f), r.top + d(10f), r.left + d(44f), r.top + d(44f)))
    // Dorsal, nombre y posición
    val baseY = r.bottom - d(36f)
    t.color = gold; t.textAlign = Paint.Align.LEFT; t.textSize = d(54f)
    t.setShadowLayer(d(4f), 0f, d(2f), Color.argb(200, 0, 0, 0))
    val nx = r.left + d(12f)
    c.drawText(pl.dorsal, nx, baseY + d(18f), t)
    val after = nx + t.measureText(pl.dorsal) + d(8f)
    val maxW = r.right - after - (if (pl.captain) d(34f) else d(10f))
    t.color = Color.WHITE; t.textSize = d(34f)
    val nm = pl.name.uppercase()
    if (t.measureText(nm) > maxW) t.textSize *= maxW / t.measureText(nm)
    c.drawText(nm, after, baseY, t)
    if (pl.pos.isNotBlank()) { t.color = gold; t.textSize = d(17f); c.drawText(pl.pos.uppercase(), after + d(1f), baseY + d(22f), t) }
    t.clearShadowLayer()
    if (pl.captain) captainBadge(c, r.right - d(30f), baseY - d(28f))
  }

  private fun captainBadge(c: Canvas, x: Float, y: Float) {
    val w = d(24f); val h = d(30f)
    val path = Path().apply { moveTo(x, y); lineTo(x + w, y); lineTo(x + w, y + h * 0.65f); lineTo(x + w / 2, y + h); lineTo(x, y + h * 0.65f); close() }
    p.color = gold; c.drawPath(path, p)
    t.color = wine; t.textAlign = Paint.Align.CENTER; t.textSize = d(24f)
    val fm = t.fontMetrics
    c.drawText("C", x + w / 2, y + h * 0.42f - (fm.ascent + fm.descent) / 2, t)
  }

  private fun stripes(c: Canvas, r: RectF, step: Float, w: Float, color: Int) {
    p.color = color; p.style = Paint.Style.FILL
    val path = Path()
    var x = r.left - r.height()
    while (x < r.right) {
      path.reset()
      path.moveTo(x, r.bottom); path.lineTo(x + w, r.bottom); path.lineTo(x + w + r.height(), r.top); path.lineTo(x + r.height(), r.top); path.close()
      c.drawPath(path, p)
      x += step
    }
  }

  /** Imagen recortada para llenar el rectángulo (como "cover"); fy = punto vertical que se conserva (0 arriba). */
  private fun cover(c: Canvas, b: Bitmap, r: RectF, fy: Float) {
    val s = max(r.width() / b.width, r.height() / b.height)
    val sw = r.width() / s; val sh = r.height() / s
    val sx = (b.width - sw) / 2f; val sy = (b.height - sh) * fy
    c.drawBitmap(b, Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt()), r, bmpPaint)
  }

  private fun fit(c: Canvas, data: String, box: RectF) {
    val b = image(data) ?: return
    val s = min(box.width() / b.width, box.height() / b.height)
    val w = b.width * s; val h = b.height * s
    c.drawBitmap(b, null, RectF(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2), bmpPaint)
  }

  private fun text(c: Canvas, s: String, x: Float, cy: Float, size: Float, color: Int, align: Paint.Align, maxW: Float = Float.MAX_VALUE) {
    t.style = Paint.Style.FILL; t.color = color; t.textAlign = align; t.textSize = size; t.clearShadowLayer()
    val w = t.measureText(s)
    if (w > maxW) t.textSize = size * maxW / w
    val fm = t.fontMetrics
    c.drawText(s, x, cy - (fm.ascent + fm.descent) / 2, t)
  }
}
