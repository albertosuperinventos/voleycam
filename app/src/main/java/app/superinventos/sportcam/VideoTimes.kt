package app.superinventos.sportcam

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Averigua a qué hora empezó y acabó un vídeo grabado por la app (hora local del móvil, ms):
 *  1) por su nombre (VoleyCam_AAAAMMDD_HHMMSS), aunque el selector de vídeos lo esconda;
 *  2) por la fecha que el móvil guarda dentro del vídeo (que es la del final de la grabación).
 */
object VideoTimes {

  private val NAME = Regex("VoleyCam_(\\d{8})_(\\d{6})")

  fun displayName(ctx: Context, uri: Uri): String = try {
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
      if (c.moveToFirst()) c.getString(0) else null
    } ?: ""
  } catch (_: Exception) { "" }

  /** Nombre real en la galería: el selector de fotos suele dar un número (el id de la galería). */
  private fun galleryName(ctx: Context, uri: Uri): String {
    val id = uri.lastPathSegment?.toLongOrNull() ?: return ""
    return try {
      val u = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
      ctx.contentResolver.query(u, arrayOf(MediaStore.Video.Media.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
      } ?: ""
    } catch (_: Exception) { "" }
  }

  /** Nombre que sirve para ordenar y para sacar la hora. */
  fun bestName(ctx: Context, uri: Uri): String {
    val n = displayName(ctx, uri)
    if (NAME.containsMatchIn(n)) return n
    val g = galleryName(ctx, uri)
    return if (NAME.containsMatchIn(g)) g else n.ifBlank { g }
  }

  /** (inicio, fin) en ms de hora local del móvil, o null si no se puede saber. */
  fun times(ctx: Context, uri: Uri): Pair<Long, Long>? {
    var durMs = 0L
    var dateTag = ""
    val r = MediaMetadataRetriever()
    try {
      r.setDataSource(ctx, uri)
      durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      dateTag = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty()
    } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
    if (durMs <= 0) return null
    NAME.find(bestName(ctx, uri))?.let { m ->
      try {
        val local = LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val start = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return start to start + durMs
      } catch (_: Exception) {}
    }
    // Fecha interna del vídeo, p. ej. 20261024T074838.000Z (se escribe al terminar la grabación)
    Regex("(\\d{8})T(\\d{6})").find(dateTag)?.let { m ->
      try {
        val utc = LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val end = utc.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()
        if (end > 1_500_000_000_000L) return end - durMs to end
      } catch (_: Exception) {}
    }
    return null
  }
}
