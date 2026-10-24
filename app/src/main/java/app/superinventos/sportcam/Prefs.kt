package app.superinventos.sportcam

import android.content.Context
import java.security.SecureRandom

/** Ajustes guardados en el móvil. */
class Prefs(context: Context) {
  private val sp = context.getSharedPreferences("sportcam", Context.MODE_PRIVATE)

  var platform: String
    get() = sp.getString("platform", PLATFORM_YOUTUBE) ?: PLATFORM_YOUTUBE
    set(v) = sp.edit().putString("platform", v).apply()

  var youtubeKey: String
    get() = sp.getString("youtubeKey", "") ?: ""
    set(v) = sp.edit().putString("youtubeKey", v.trim()).apply()

  var facebookKey: String
    get() = sp.getString("facebookKey", "") ?: ""
    set(v) = sp.edit().putString("facebookKey", v.trim()).apply()

  var customUrl: String
    get() = sp.getString("customUrl", "") ?: ""
    set(v) = sp.edit().putString("customUrl", v.trim()).apply()

  /** Altura de vídeo: 480, 720 o 1080 */
  var resolution: Int
    get() = sp.getInt("resolution", 720)
    set(v) = sp.edit().putInt("resolution", v).apply()

  /** Bitrate de vídeo en kbps */
  var bitrateKbps: Int
    get() = sp.getInt("bitrateKbps", 3000)
    set(v) = sp.edit().putInt("bitrateKbps", v).apply()

  var fps: Int
    get() = sp.getInt("fps", 30)
    set(v) = sp.edit().putInt("fps", v).apply()

  var recordLocal: Boolean
    get() = sp.getBoolean("recordLocal", true)
    set(v) = sp.edit().putBoolean("recordLocal", v).apply()

  /** Emisión de YouTube seleccionada (id de la emisión / vídeo). */
  var ytBroadcastId: String
    get() = sp.getString("ytBroadcastId", "") ?: ""
    set(v) = sp.edit().putString("ytBroadcastId", v).apply()

  /** Título, privacidad y estado (de YouTube) de la emisión seleccionada, para el aviso al emitir. */
  var ytBroadcastTitle: String
    get() = sp.getString("ytBroadcastTitle", "") ?: ""
    set(v) = sp.edit().putString("ytBroadcastTitle", v).apply()
  var ytBroadcastPrivacy: String
    get() = sp.getString("ytBroadcastPrivacy", "") ?: ""
    set(v) = sp.edit().putString("ytBroadcastPrivacy", v).apply()
  var ytBroadcastStatus: String
    get() = sp.getString("ytBroadcastStatus", "") ?: ""
    set(v) = sp.edit().putString("ytBroadcastStatus", v).apply()

  /** Últimas grabaciones con su hora de inicio y fin (JSON), para unirlas y hacer resúmenes. */
  var recordingsJson: String
    get() = sp.getString("recordingsJson", "[]") ?: "[]"
    set(v) = sp.edit().putString("recordingsJson", v).apply()

  /** URL pública de la web de control (GitHub Pages), para el QR del segundo móvil. */
  var remoteUrl: String
    get() = sp.getString("remoteUrl", DEFAULT_REMOTE) ?: DEFAULT_REMOTE
    set(v) = sp.edit().putString("remoteUrl", v.trim()).apply()

  /** Clave secreta del canal: separa tus datos en Firebase. Se crea sola la primera vez. */
  var channelKey: String
    get() {
      var k = sp.getString("channelKey", "") ?: ""
      if (k.length < 16) { k = newKey(); sp.edit().putString("channelKey", k).apply() }
      return k
    }
    set(v) = sp.edit().putString("channelKey", v.trim()).apply()

  fun streamUrl(): String? = when (platform) {
    PLATFORM_YOUTUBE -> youtubeKey.takeIf { it.isNotBlank() }?.let { "rtmp://a.rtmp.youtube.com/live2/$it" }
    PLATFORM_FACEBOOK -> facebookKey.takeIf { it.isNotBlank() }?.let { "rtmps://live-api-s.facebook.com:443/rtmp/$it" }
    else -> customUrl.takeIf { it.startsWith("rtmp://") || it.startsWith("rtmps://") || it.startsWith("srt://") || it.startsWith("rtsp://") }
  }

  fun videoSize(): Pair<Int, Int> = when (resolution) {
    480 -> 854 to 480
    1080 -> 1920 to 1080
    else -> 1280 to 720
  }

  fun remoteLink(): String? {
    val base = remoteUrl.takeIf { it.startsWith("http") } ?: return null
    val clean = if (base.endsWith("/") || base.endsWith(".html")) base else "$base/"
    return "$clean#k=$channelKey"
  }

  companion object {
    const val DEFAULT_REMOTE = "https://albertosuperinventos.github.io/voleycam/"
    const val PLATFORM_YOUTUBE = "youtube"
    const val PLATFORM_FACEBOOK = "facebook"
    const val PLATFORM_CUSTOM = "custom"

    fun newKey(): String {
      val chars = "abcdefghijkmnpqrstuvwxyz23456789"
      val r = SecureRandom()
      return (1..24).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
  }
}
