package app.superinventos.sportcam

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Llamadas a la API de YouTube (Data API v3). Todas bloqueantes: usar fuera del hilo principal. */
object YouTubeClient {

  const val SCOPE = "https://www.googleapis.com/auth/youtube"
  private const val BASE = "https://www.googleapis.com/youtube/v3/"
  private const val STREAM_TITLE = "VoleyCam"

  data class Broadcast(val id: String, val title: String, val start: String, val privacy: String, val status: String, val streamId: String)
  data class Stream(val id: String, val rtmpUrl: String, val key: String)

  class ApiException(msg: String) : Exception(msg)

  private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

  /** YouTube no admite < ni > en título ni descripción. */
  fun clean(s: String, max: Int) = s.replace("<", "‹").replace(">", "›").let { if (it.length > max) it.take(max - 1) + "…" else it }

  private fun call(token: String, method: String, path: String, body: JSONObject? = null): JSONObject {
    val c = URL(BASE + path).openConnection() as HttpURLConnection
    c.requestMethod = method
    c.connectTimeout = 15000; c.readTimeout = 20000
    c.setRequestProperty("Authorization", "Bearer $token")
    c.setRequestProperty("Accept", "application/json")
    if (body != null) {
      c.doOutput = true
      c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
      c.outputStream.use { it.write(body.toString().toByteArray()) }
    }
    val code = c.responseCode
    val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    c.disconnect()
    if (code !in 200..299) {
      val msg = try { JSONObject(text).getJSONObject("error").optString("message") } catch (_: Exception) { text.take(200) }
      throw ApiException("YouTube ($code): $msg")
    }
    return if (text.isBlank()) JSONObject() else JSONObject(text)
  }

  fun channelTitle(token: String): String =
    call(token, "GET", "channels?part=snippet&mine=true").optJSONArray("items")?.optJSONObject(0)
      ?.optJSONObject("snippet")?.optString("title").orEmpty()

  fun listBroadcasts(token: String): List<Broadcast> {
    val items = call(token, "GET", "liveBroadcasts?part=snippet,status,contentDetails&mine=true&maxResults=15").optJSONArray("items") ?: JSONArray()
    return (0 until items.length()).map { i ->
      val b = items.getJSONObject(i)
      val sn = b.optJSONObject("snippet") ?: JSONObject()
      val st = b.optJSONObject("status") ?: JSONObject()
      val cd = b.optJSONObject("contentDetails") ?: JSONObject()
      Broadcast(b.optString("id"), sn.optString("title"), sn.optString("scheduledStartTime"),
        st.optString("privacyStatus"), st.optString("lifeCycleStatus"), cd.optString("boundStreamId"))
    }.filter { it.status != "revoked" }
  }

  /** Emisión reutilizable "SportCam": la crea si no existe. */
  fun ensureStream(token: String): Stream {
    val items = call(token, "GET", "liveStreams?part=id,snippet,cdn&mine=true&maxResults=50").optJSONArray("items") ?: JSONArray()
    for (i in 0 until items.length()) {
      val s = items.getJSONObject(i)
      if (s.optJSONObject("snippet")?.optString("title") == STREAM_TITLE) return toStream(s)
    }
    val body = JSONObject()
      .put("snippet", JSONObject().put("title", STREAM_TITLE))
      .put("cdn", JSONObject().put("ingestionType", "rtmp").put("resolution", "variable").put("frameRate", "variable"))
      .put("contentDetails", JSONObject().put("isReusable", true))
    return toStream(call(token, "POST", "liveStreams?part=snippet,cdn,contentDetails", body))
  }

  private fun toStream(s: JSONObject): Stream {
    val ing = s.optJSONObject("cdn")?.optJSONObject("ingestionInfo") ?: JSONObject()
    return Stream(s.optString("id"), ing.optString("ingestionAddress"), ing.optString("streamName"))
  }

  /** Crea la emisión programada y la asocia a la emisión SportCam. Devuelve el id (también es el id del vídeo). */
  fun createBroadcast(token: String, title: String, desc: String, startIso: String, privacy: String, streamId: String): String {
    val body = JSONObject()
      .put("snippet", JSONObject().put("title", clean(title, 100)).put("description", clean(desc, 5000)).put("scheduledStartTime", startIso))
      .put("status", JSONObject().put("privacyStatus", privacy).put("selfDeclaredMadeForKids", false))
      .put("contentDetails", JSONObject().put("enableAutoStart", true).put("enableAutoStop", true).put("enableDvr", true))
    val id = call(token, "POST", "liveBroadcasts?part=snippet,status,contentDetails", body).optString("id")
    bind(token, id, streamId)
    return id
  }

  fun bind(token: String, broadcastId: String, streamId: String) {
    call(token, "POST", "liveBroadcasts/bind?id=${enc(broadcastId)}&streamId=${enc(streamId)}&part=id,contentDetails")
  }

  /** Cambia la descripción del vídeo manteniendo título y categoría. */
  fun updateDescription(token: String, videoId: String, desc: String) {
    val v = call(token, "GET", "videos?part=snippet&id=${enc(videoId)}").optJSONArray("items")?.optJSONObject(0)
      ?: throw ApiException("No se encontró el vídeo en YouTube")
    val sn = v.getJSONObject("snippet")
    val body = JSONObject().put("id", videoId).put("snippet", JSONObject()
      .put("title", sn.optString("title"))
      .put("categoryId", sn.optString("categoryId", "17"))
      .put("description", clean(desc, 5000)))
    call(token, "PUT", "videos?part=snippet", body)
  }
}
