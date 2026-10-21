package app.superinventos.sportcam

import android.graphics.Color
import org.json.JSONObject

data class TeamInfo(val name: String, val short: String, val color: Int, val logo: String)

data class Sponsor(val logo: String, val text: String)

/** Estado que la web de control envía a la app para pintar el marcador. */
data class OverlayState(
  val sport: String,
  val home: TeamInfo,
  val away: TeamInfo,
  val scoreH: Int, val scoreA: Int,
  val setsH: Int, val setsA: Int,
  val setNo: Int,
  val serve: String,
  val history: List<Pair<Int, Int>>,
  val finished: Boolean,
  val clockRunning: Boolean,
  val clockBaseMs: Long,
  val clockStartedAt: Long,
  val clockDown: Boolean,
  val clockDurMs: Long,
  val period: String,
  val bannerShow: Boolean,
  val bannerText: String,
  val breakShow: Boolean,
  val breakText: String,
  /** pre | tm | set | end | half | custom */
  val breakKind: String,
  val sponsShow: Boolean,
  val sponsSec: Int,
  val sponsors: List<Sponsor>,
  val sponsCarousel: Boolean,
  val fullSponsor: Sponsor?,
  val boardShow: Boolean,
  /** tl, tr, bl, br */
  val boardPos: String,
  val timerShow: Boolean,
  val timeoutsH: Int,
  val timeoutsA: Int,
  val muted: Boolean,
  val marks: List<Highlights.Mark>,
  val hlBefore: Int,
  val hlAfter: Int,
  val ytDesc: String,
  val serverOffset: Long,
) {
  val isVolley get() = sport != "football"

  /** Milisegundos a mostrar en el reloj en este instante. */
  fun clockMs(nowLocal: Long = System.currentTimeMillis()): Long {
    val now = nowLocal + serverOffset
    val elapsed = clockBaseMs + if (clockRunning) maxOf(0L, now - clockStartedAt) else 0L
    return if (clockDown) maxOf(0L, clockDurMs - elapsed) else elapsed
  }

  companion object {
    private fun color(s: String?, def: Int): Int = try {
      if (s.isNullOrBlank()) def else Color.parseColor(s)
    } catch (_: IllegalArgumentException) { def }

    private fun team(o: JSONObject?, defName: String, defColor: Int) = TeamInfo(
      name = o?.optString("name").orEmpty().ifBlank { defName },
      short = o?.optString("short").orEmpty().ifBlank { defName }.uppercase(),
      color = color(o?.optString("color"), defColor),
      logo = o?.optString("logo").orEmpty()
    )

    fun parse(json: String): OverlayState {
      val o = JSONObject(json)
      val score = o.optJSONObject("score") ?: JSONObject()
      val sets = o.optJSONObject("sets") ?: JSONObject()
      val clock = o.optJSONObject("clock") ?: JSONObject()
      val banner = o.optJSONObject("banner") ?: JSONObject()
      val brk = o.optJSONObject("brk") ?: JSONObject()
      val spons = o.optJSONObject("spons") ?: JSONObject()
      val hist = mutableListOf<Pair<Int, Int>>()
      o.optJSONArray("history")?.let { arr ->
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { hist += it.optInt("h") to it.optInt("a") }
      }
      val board = o.optJSONObject("board") ?: JSONObject()
      val sponsors = mutableListOf<Sponsor>()
      o.optJSONArray("sponsorList")?.let { arr ->
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { sp ->
          val logo = sp.optString("logo")
          if (logo.isNotBlank()) sponsors += Sponsor(logo, sp.optString("text"))
        }
      }
      val full = o.optJSONObject("spFullItem")?.let { Sponsor(it.optString("logo"), it.optString("text")) }?.takeIf { it.logo.isNotBlank() }
      val pos = board.optString("pos", "tl").takeIf { it in setOf("tl", "tr", "bl", "br") } ?: "tl"
      return OverlayState(
        sport = o.optString("sport", "volley"),
        home = team(o.optJSONObject("home"), "LOCAL", Color.rgb(30, 107, 255)),
        away = team(o.optJSONObject("away"), "VISITANTE", Color.rgb(255, 59, 59)),
        scoreH = score.optInt("h"), scoreA = score.optInt("a"),
        setsH = sets.optInt("h"), setsA = sets.optInt("a"),
        setNo = o.optInt("setNo", 1),
        serve = o.optString("serve", ""),
        history = hist,
        finished = o.optBoolean("finished"),
        clockRunning = clock.optBoolean("running"),
        clockBaseMs = clock.optLong("baseMs"),
        clockStartedAt = clock.optLong("startedAt"),
        clockDown = clock.optString("dir") == "down",
        clockDurMs = clock.optLong("durMs", 40 * 60000L),
        period = clock.optString("period", ""),
        bannerShow = banner.optBoolean("show"),
        bannerText = banner.optString("text", ""),
        breakShow = brk.optBoolean("show"),
        breakText = brk.optString("text", ""),
        breakKind = brk.optString("kind", "custom"),
        sponsShow = spons.optBoolean("show", true),
        sponsSec = spons.optInt("sec", 10).coerceIn(3, 120),
        sponsors = sponsors,
        sponsCarousel = spons.optBoolean("carousel"),
        fullSponsor = full,
        boardShow = board.optBoolean("show", true),
        boardPos = pos,
        timerShow = o.optJSONObject("timer")?.optBoolean("show") ?: false,
        timeoutsH = o.optJSONObject("to")?.optInt("h") ?: 0,
        timeoutsA = o.optJSONObject("to")?.optInt("a") ?: 0,
        muted = o.optBoolean("muted"),
        marks = o.optJSONArray("marks")?.let { arr ->
          (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { Highlights.Mark(it.optLong("ts"), it.optString("label")) } }
        } ?: emptyList(),
        hlBefore = o.optJSONObject("hl")?.optInt("before", 10) ?: 10,
        hlAfter = o.optJSONObject("hl")?.optInt("after", 3) ?: 3,
        ytDesc = o.optString("ytDesc", ""),
        serverOffset = o.optLong("serverOffset"),
      )
    }
  }
}
