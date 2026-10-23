package app.superinventos.sportcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Monta los vídeos resumen volviendo a codificar:
 *  - cada jugada se funde con la siguiente (fundido encadenado) y el sonido baja y sube;
 *  - el del partido lleva portada con "MEJORES JUGADAS" y los patrocinadores al final (3 s cada uno).
 * Si algo falla, quien lo llama guarda el resumen con cortes normales.
 */
@OptIn(UnstableApi::class)
object SummaryMaker {

  private const val SLIDE_MS = 3000L
  private const val FADE_US = 600_000L        // duración del fundido entre jugadas
  private const val AUDIO_FADE_US = 250_000L
  private const val TITLE_IN_US = 900_000L
  private const val TAIL_W = 640
  private const val TAIL_H = 360

  /** Lo que se ve al final de un tramo, para fundirlo sobre el siguiente. */
  private sealed class Tail {
    class Frames(val jpegs: List<ByteArray>) : Tail()
    class Still(val png: File) : Tail()
  }

  /** Tamaño (ancho, alto) del vídeo grabado, o 1280x720. */
  fun videoSize(context: Context, uri: Uri): Pair<Int, Int> {
    val r = MediaMetadataRetriever()
    return try {
      r.setDataSource(context, uri)
      var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
      var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
      val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
      if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
      w to h
    } catch (_: Exception) { 1280 to 720 } finally { try { r.release() } catch (_: Exception) {} }
  }

  class Slides(val intro: File, val introNoTitle: File, val title: File, val sponsors: List<File>)

  /** Dibuja portada (con y sin título), el título suelto y los patrocinadores. Hilo de fondo. */
  fun renderSlides(context: Context, w: Int, h: Int, st: OverlayState, subtitle: String): Slides {
    val r = ScoreboardRenderer(w, h)
    try { r.titleFont = Typeface.createFromAsset(context.assets, "fonts/BarlowCondensed-Black.ttf") } catch (_: Exception) {}
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val dir = File(context.cacheDir, "summary").apply { deleteRecursively(); mkdirs() }
    fun save(name: String) = File(dir, name).also { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    r.renderIntro(st, subtitle, bmp, withTitle = true); val intro = save("portada.png")
    r.renderIntro(st, subtitle, bmp, withTitle = false); val introNo = save("portada_sin_titulo.png")
    r.renderTitle(bmp); val title = save("titulo.png")
    val sp = st.sponsors.mapIndexed { i, s -> r.renderSponsorSlide(s, bmp); save("patro_$i.png") }
    bmp.recycle()
    return Slides(intro, introNo, title, sp)
  }

  /** Fotogramas (JPEG pequeños) de [startUs, endUs] del vídeo, para el fundido. Hilo de fondo. */
  private fun tailFrames(context: Context, uri: Uri, startUs: Long, endUs: Long): List<ByteArray> {
    val r = MediaMetadataRetriever()
    val out = mutableListOf<ByteArray>()
    fun add(b: Bitmap?) {
      b ?: return
      val small = Bitmap.createScaledBitmap(b, TAIL_W, TAIL_H, true)
      val bo = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 82, bo); out += bo.toByteArray()
      if (small !== b) small.recycle(); b.recycle()
    }
    try {
      r.setDataSource(context, uri)
      val count = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
      val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      if (count > 0 && durMs > 0) {
        // Rápido: fotogramas seguidos por índice
        val fps = count * 1000.0 / durMs
        val first = (startUs / 1e6 * fps).toInt().coerceIn(0, count - 1)
        val n = ((endUs - startUs) / 1e6 * fps).toInt().coerceIn(1, count - first)
        val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.RGB_565 }
        try { r.getFramesAtIndex(first, n, params).forEach { add(it) } } catch (_: Exception) { out.clear() }
      }
      if (out.isEmpty()) {
        // Lento: unos pocos fotogramas por tiempo
        val steps = 6
        for (i in 0 until steps) add(r.getFrameAtTime(startUs + (endUs - startUs) * i / steps, MediaMetadataRetriever.OPTION_CLOSEST))
      }
    } catch (_: Exception) {} finally { try { r.release() } catch (_: Exception) {} }
    return out
  }

  /** Superpone el final del tramo anterior desapareciendo poco a poco (fundido encadenado). */
  private class TailOverlay(private val tail: Tail, private val w: Int, private val h: Int) : BitmapOverlay() {
    private var firstUs = -1L
    private var shown = -1
    private var current: Bitmap? = null
    private val opts = BitmapFactory.Options().apply { inMutable = true }
    private var still: Bitmap? = null

    private fun rel(t: Long): Long { if (firstUs < 0 || t < firstUs) firstUs = t; return t - firstUs }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      val t = rel(presentationTimeUs)
      return when (tail) {
        is Tail.Still -> still ?: (BitmapFactory.decodeFile(tail.png.absolutePath) ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)).also { still = it }
        is Tail.Frames -> {
          val idx = (t * tail.jpegs.size / FADE_US).toInt().coerceIn(0, tail.jpegs.size - 1)
          val cur = current
          if (idx != shown || cur == null) {
            shown = idx
            opts.inBitmap = cur
            current = try { BitmapFactory.decodeByteArray(tail.jpegs[idx], 0, tail.jpegs[idx].size, opts) }
                      catch (_: Exception) { opts.inBitmap = null; BitmapFactory.decodeByteArray(tail.jpegs[idx], 0, tail.jpegs[idx].size) }
          }
          current!!
        }
      }
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
      val t = rel(presentationTimeUs)
      val a = (1f - t.toFloat() / FADE_US).coerceIn(0f, 1f)
      val b = current ?: still
      val sx = if (b != null && b.width > 0) w.toFloat() / b.width else 1f
      val sy = if (b != null && b.height > 0) h.toFloat() / b.height else 1f
      return OverlaySettings.Builder().setAlphaScale(a).setScale(sx, sy).build()
    }
  }

  /** "MEJORES JUGADAS" aparece con un fundido y un ligero acercamiento. */
  private class TitleOverlay(private val png: File) : BitmapOverlay() {
    private var firstUs = -1L
    private var bmp: Bitmap? = null
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
      if (firstUs < 0 || presentationTimeUs < firstUs) firstUs = presentationTimeUs
      return bmp ?: BitmapFactory.decodeFile(png.absolutePath).also { bmp = it }
    }
    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
      val t = if (firstUs < 0) 0L else presentationTimeUs - firstUs
      val p = (t.toFloat() / TITLE_IN_US).coerceIn(0f, 1f)
      val ease = 1f - (1f - p) * (1f - p)
      val sc = 1.18f - 0.18f * ease
      return OverlaySettings.Builder().setAlphaScale(ease).setScale(sc, sc).build()
    }
  }

  /** Baja el volumen al principio y al final de cada tramo para que el corte no suene seco. */
  private class FadeAudio(private val durationUs: Long) : BaseAudioProcessor() {
    private var frames = 0L
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
      if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
      return inputAudioFormat
    }
    override fun queueInput(inputBuffer: ByteBuffer) {
      val f = inputAudioFormat
      val bpf = f.bytesPerFrame
      val out = replaceOutputBuffer(inputBuffer.remaining())
      while (inputBuffer.remaining() >= bpf) {
        val t = frames * 1_000_000L / f.sampleRate
        val g = minOf(1f, t.toFloat() / AUDIO_FADE_US, (durationUs - t).toFloat() / AUDIO_FADE_US).coerceIn(0f, 1f)
        for (c in 0 until f.channelCount) out.putShort((inputBuffer.short * g).toInt().toShort())
        frames++
      }
      inputBuffer.position(inputBuffer.limit())
      out.flip()
    }
    override fun onFlush() { frames = 0 }
    override fun onReset() { frames = 0 }
  }

  /**
   * Prepara la lista de tramos (en hilo de fondo: saca los fotogramas de los fundidos).
   * [ranges] en microsegundos dentro de [src]. [slides] solo para el resumen del partido.
   */
  fun prepare(context: Context, src: Uri, ranges: List<LongRange>, slides: Slides?, w: Int, h: Int): List<EditedMediaItem> {
    val present: Effect = Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
    fun effects(extra: List<Effect>, audio: List<AudioProcessor> = emptyList()) = Effects(audio, listOf(present) + extra)
    fun overlay(o: BitmapOverlay): Effect = OverlayEffect(ImmutableList.of(o))
    fun image(f: File, fx: List<Effect>) = EditedMediaItem.Builder(
      MediaItem.Builder().setUri(Uri.fromFile(f)).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(SLIDE_MS).build())
      .setFrameRate(30).setEffects(effects(fx)).build()

    val items = mutableListOf<EditedMediaItem>()
    var prev: Tail? = null
    if (slides != null) {
      items += image(slides.introNoTitle, listOf(overlay(TitleOverlay(slides.title))))
      prev = Tail.Still(slides.intro)
    }
    ranges.forEachIndexed { i, r ->
      val last = i == ranges.size - 1
      val hasNext = !last || (slides != null && slides.sponsors.isNotEmpty())
      // El tramo termina antes del fundido; ese final se superpone al principio del siguiente
      val len = r.last - r.first
      val fade = if (hasNext && len > FADE_US * 3) FADE_US else 0L
      val endUs = r.last - fade
      val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(r.first / 1000).setEndPositionMs(endUs / 1000).build()
      val fx = prev?.let { listOf(overlay(TailOverlay(it, w, h))) } ?: emptyList()
      items += EditedMediaItem.Builder(MediaItem.Builder().setUri(src).setClippingConfiguration(clip).build())
        .setEffects(effects(fx, listOf(FadeAudio(endUs - r.first)))).build()
      prev = if (fade > 0) tailFrames(context, src, endUs, r.last).takeIf { it.isNotEmpty() }?.let { Tail.Frames(it) } else null
    }
    slides?.sponsors?.forEach { f ->
      items += image(f, prev?.let { listOf(overlay(TailOverlay(it, w, h))) } ?: emptyList())
      prev = Tail.Still(f)
    }
    return items
  }

  /** Exporta [items] a [out]. Desde el hilo principal; avisa del progreso (0-100, o -1 si no se sabe) y del final. */
  fun export(context: Context, items: List<EditedMediaItem>, out: File, onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
    out.delete()
    val composition = Composition.Builder(EditedMediaItemSequence(items))
      .experimentalSetForceAudioTrack(true)
      .build()
    val main = Handler(Looper.getMainLooper())
    var finished = false
    lateinit var transformer: Transformer
    val holder = ProgressHolder()
    val poll = object : Runnable {
      override fun run() {
        if (finished) return
        onProgress(if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else -1)
        main.postDelayed(this, 700)
      }
    }
    transformer = Transformer.Builder(context)
      .setVideoMimeType(MimeTypes.VIDEO_H264)
      .setAudioMimeType(MimeTypes.AUDIO_AAC)
      .addListener(object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
          finished = true; onDone(out.length() > 0, "")
        }
        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
          finished = true; out.delete(); onDone(false, exportException.message ?: "error")
        }
      })
      .build()
    try {
      transformer.start(composition, out.absolutePath)
      main.post(poll)
    } catch (e: Exception) {
      finished = true; onDone(false, e.message ?: "error")
    }
  }
}
