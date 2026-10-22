package app.superinventos.sportcam

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.FileOutputStream

/**
 * Monta el resumen del partido: portada (3 s) + jugadas + patrocinadores (3 s cada uno).
 * Vuelve a codificar el vídeo, así que tarda algo; si falla, se usa el resumen sin portada.
 */
@OptIn(UnstableApi::class)
object SummaryMaker {

  private const val SLIDE_MS = 3000L

  /** Tamaño (ancho, alto) del vídeo, o 1280x720 si no se puede leer. */
  fun videoSize(file: File): Pair<Int, Int> {
    val ex = MediaExtractor()
    return try {
      ex.setDataSource(file.absolutePath)
      for (i in 0 until ex.trackCount) {
        val f = ex.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
          var w = f.getInteger(MediaFormat.KEY_WIDTH); var h = f.getInteger(MediaFormat.KEY_HEIGHT)
          val rot = if (f.containsKey("rotation-degrees")) f.getInteger("rotation-degrees") else 0
          if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
          return w to h
        }
      }
      1280 to 720
    } catch (_: Exception) { 1280 to 720 } finally { ex.release() }
  }

  /** Guarda las imágenes (portada y patrocinadores) como PNG del tamaño del vídeo. Hilo de fondo. */
  fun renderSlides(context: Context, w: Int, h: Int, st: OverlayState, subtitle: String): List<File> {
    val r = ScoreboardRenderer(w, h)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val dir = File(context.cacheDir, "summary").apply { deleteRecursively(); mkdirs() }
    val out = mutableListOf<File>()
    fun save(name: String) = File(dir, name).also { f -> FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }; out += f }
    r.renderIntro(st, subtitle, bmp); save("00_portada.png")
    st.sponsors.forEachIndexed { i, sp -> r.renderSponsorSlide(sp, bmp); save("%02d_patro.png".format(i + 1)) }
    bmp.recycle()
    return out
  }

  /**
   * Une [slides] (la primera va delante, el resto detrás) con [clip] en [out].
   * Se llama desde el hilo principal; avisa con [onProgress] (0-100) y [onDone] (true si salió bien).
   */
  fun compose(context: Context, clip: File, slides: List<File>, w: Int, h: Int, out: File,
              onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
    out.delete()
    val fit = Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)))
    fun image(f: File) = EditedMediaItem.Builder(
      MediaItem.Builder().setUri(Uri.fromFile(f)).setMimeType(MimeTypes.IMAGE_PNG).setImageDurationMs(SLIDE_MS).build())
      .setFrameRate(30).setEffects(fit).build()
    val video = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(clip))).setEffects(fit).build()
    val items = mutableListOf<EditedMediaItem>()
    slides.firstOrNull()?.let { items += image(it) }
    items += video
    slides.drop(1).forEach { items += image(it) }
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
        if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
        main.postDelayed(this, 500)
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
