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

  data class Mark(val ts: Long, val label: String)

  /** Grabación terminada: dónde está y cuándo empezó/acabó (hora del servidor, ms). */
  data class Recording(val uri: Uri, val startTs: Long, val endTs: Long)

  /** Tramos en microsegundos dentro del vídeo, ordenados y sin solaparse. */
  fun ranges(rec: Recording, marks: List<Mark>, beforeSec: Int, afterSec: Int): List<LongRange> {
    val dur = (rec.endTs - rec.startTs) * 1000
    val raw = marks.filter { it.ts in rec.startTs..rec.endTs }.map {
      val t = (it.ts - rec.startTs) * 1000
      maxOf(0L, t - beforeSec * 1_000_000L)..minOf(dur, t + afterSec * 1_000_000L)
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

  /** Capítulos para la descripción de YouTube (el primero debe ser 00:00). */
  fun chapters(startTs: Long, marks: List<Mark>): String {
    val sb = StringBuilder("00:00 Inicio\n")
    var lastSec = 0L
    marks.filter { it.ts > startTs }.sortedBy { it.ts }.forEach { m ->
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
