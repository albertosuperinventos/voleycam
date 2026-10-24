package app.superinventos.sportcam

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Crea el vídeo resumen cortando trozos de la grabación y uniéndolos,
 * sin volver a codificar (rápido y sin pérdida de calidad).
 */
object Highlights {

  /** kind "" = jugada del partido (⭐), "carmen" = jugada de la jugadora (💜). */
  data class Mark(val ts: Long, val label: String, val kind: String = "") {
    val isPlayer get() = kind == "carmen"
  }

  /** Trozo de una grabación: hora del servidor de inicio y fin (ms) y dónde empieza dentro del vídeo (µs). */
  data class Segment(val startTs: Long, val endTs: Long, val offsetUs: Long)

  /**
   * Grabación terminada: dónde está y cuándo empezó/acabó (hora del servidor, ms).
   * Un vídeo unido tiene varios trozos (con huecos entre ellos).
   */
  data class Recording(val uri: Uri, val startTs: Long, val endTs: Long,
                       val segments: List<Segment> = listOf(Segment(startTs, endTs, 0L))) {
    fun contains(ts: Long) = segments.any { ts in it.startTs..it.endTs }
  }

  /** Tramos en microsegundos dentro del vídeo, ordenados y sin solaparse. */
  fun ranges(rec: Recording, marks: List<Mark>, beforeSec: Int, afterSec: Int): List<LongRange> {
    val raw = marks.mapNotNull { m ->
      val seg = rec.segments.firstOrNull { m.ts in it.startTs..it.endTs } ?: return@mapNotNull null
      val segEnd = seg.offsetUs + (seg.endTs - seg.startTs) * 1000
      val t = seg.offsetUs + (m.ts - seg.startTs) * 1000
      maxOf(seg.offsetUs, t - beforeSec * 1_000_000L)..minOf(segEnd, t + afterSec * 1_000_000L)
    }.sortedBy { it.first }
    val merged = mutableListOf<LongRange>()
    for (r in raw) {
      val last = merged.lastOrNull()
      if (last != null && r.first <= last.last) merged[merged.size - 1] = last.first..maxOf(last.last, r.last)
      else merged += r
    }
    return merged
  }

  /** Corta [ranges] de [src] y los une en [out]. Devuelve el número de tramos escritos. */
  @SuppressLint("WrongConstant")
  fun cut(context: Context, src: Uri, ranges: List<LongRange>, out: File): Int {
    if (ranges.isEmpty()) return 0
    val ex = MediaExtractor()
    ex.setDataSource(context, src, null)
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val map = HashMap<Int, Int>()   // pista origen -> pista destino
    var videoTrack = -1
    var maxSize = 1 shl 20
    for (i in 0 until ex.trackCount) {
      val f = ex.getTrackFormat(i)
      val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
      if (mime.startsWith("video/") || mime.startsWith("audio/")) {
        if (mime.startsWith("video/")) videoTrack = i
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxSize = maxOf(maxSize, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
        map[i] = muxer.addTrack(f)
        ex.selectTrack(i)
      }
    }
    if (videoTrack < 0) { ex.release(); muxer.release(); out.delete(); return 0 }

    val buf = ByteBuffer.allocate(maxSize)
    val info = MediaCodec.BufferInfo()
    val lastPts = HashMap<Int, Long>()
    var offset = 0L
    var written = 0
    muxer.start()
    try {
      for (r in ranges) {
        // Salta al fotograma clave anterior al inicio del tramo
        ex.seekTo(r.first, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var segStart = -1L
        var segEnd = 0L
        while (true) {
          val track = ex.sampleTrackIndex
          if (track < 0) break
          val t = ex.sampleTime
          if (t > r.last + 300_000) break
          if (segStart < 0) {
            // El tramo empieza en el primer fotograma clave de vídeo
            if (track != videoTrack || (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) == 0) { ex.advance(); continue }
            segStart = t
          }
          if (t < segStart || t > r.last) { ex.advance(); continue }
          val size = ex.readSampleData(buf, 0)
          if (size < 0) break
          val dst = map[track]
          if (dst != null) {
            var pts = offset + (t - segStart)
            val prev = lastPts[dst]
            if (prev != null && pts <= prev) pts = prev + 1
            lastPts[dst] = pts
            info.set(0, size, pts,
              if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(dst, buf, info)
            segEnd = maxOf(segEnd, t)
          }
          ex.advance()
        }
        if (segStart >= 0) { offset += (segEnd - segStart) + 40_000; written++ }
      }
    } finally {
      try { muxer.stop() } catch (_: Exception) {}
      muxer.release()
      ex.release()
    }
    if (written == 0) out.delete()
    return written
  }

  /**
   * Une varios vídeos (mismo formato, como los de la app) uno detrás de otro sin volver a codificar.
   * Devuelve dónde empieza cada vídeo dentro del resultado (µs), o null si falla. [onProgress] 0-100.
   */
  @SuppressLint("WrongConstant")
  fun concat(context: Context, srcs: List<Uri>, out: File, onProgress: (Int) -> Unit = {}): List<Long>? {
    if (srcs.isEmpty()) return null
    val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    val dstByMime = HashMap<String, Int>()   // "video" / "audio" -> pista destino
    // Las pistas se crean con el formato del primer vídeo
    MediaExtractor().apply {
      setDataSource(context, srcs[0], null)
      for (i in 0 until trackCount) {
        val f = getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
        val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
        if (kind !in dstByMime) dstByMime[kind] = muxer.addTrack(f)
      }
      release()
    }
    if ("video" !in dstByMime) { muxer.release(); out.delete(); return null }
    muxer.start()
    val buf = ByteBuffer.allocate(4 shl 20)
    val info = MediaCodec.BufferInfo()
    val lastPts = HashMap<Int, Long>()
    val offsets = mutableListOf<Long>()
    var offset = 0L
    try {
      srcs.forEachIndexed { idx, uri ->
        offsets += offset
        val ex = MediaExtractor()
        ex.setDataSource(context, uri, null)
        val map = HashMap<Int, Int>()
        var durUs = 0L
        for (i in 0 until ex.trackCount) {
          val f = ex.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
          val kind = if (mime.startsWith("video/")) "video" else if (mime.startsWith("audio/")) "audio" else continue
          val dst = dstByMime[kind] ?: continue
          if (map.containsValue(dst)) continue
          map[i] = dst; ex.selectTrack(i)
          if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = maxOf(durUs, f.getLong(MediaFormat.KEY_DURATION))
        }
        var first = -1L
        var maxT = 0L
        var lastReport = 0L
        while (true) {
          val track = ex.sampleTrackIndex
          if (track < 0) break
          val dst = map[track]
          val size = ex.readSampleData(buf, 0)
          if (size < 0) break
          val t = ex.sampleTime
          if (first < 0) first = t
          if (dst != null) {
            var pts = offset + (t - first).coerceAtLeast(0)
            val prev = lastPts[dst]
            if (prev != null && pts <= prev) pts = prev + 1
            lastPts[dst] = pts
            info.set(0, size, pts, if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            muxer.writeSampleData(dst, buf, info)
            maxT = maxOf(maxT, t - first)
          }
          if (durUs > 0 && t - lastReport > 2_000_000) {
            lastReport = t
            onProgress(((idx + (t - first).toFloat() / durUs) * 100 / srcs.size).toInt().coerceIn(0, 100))
          }
          ex.advance()
        }
        ex.release()
        offset += maxT + 40_000
      }
    } catch (e: Exception) {
      try { muxer.stop() } catch (_: Exception) {}
      muxer.release(); out.delete(); return null
    }
    try { muxer.stop() } catch (_: Exception) { muxer.release(); out.delete(); return null }
    muxer.release()
    return offsets
  }

  /** Capítulos para la descripción de YouTube (el primero debe ser 00:00). */
  fun chapters(startTs: Long, marks: List<Mark>): String {
    val sb = StringBuilder("00:00 Inicio\n")
    var lastSec = 0L
    marks.filter { it.ts > startTs && !it.isPlayer }.sortedBy { it.ts }.forEach { m ->
      val sec = (m.ts - startTs) / 1000
      if (sec - lastSec < 10) return@forEach   // YouTube exige 10 s entre capítulos
      lastSec = sec
      sb.append(fmt(sec)).append(' ').append(m.label).append('\n')
    }
    return sb.toString().trim()
  }

  private fun fmt(sec: Long) =
    if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60)
    else String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)
}
