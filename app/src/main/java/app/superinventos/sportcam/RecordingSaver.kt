package app.superinventos.sportcam

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Mueve la grabación terminada a la galería (Películas/SportCam). */
object RecordingSaver {

  fun tempFile(context: Context, name: String): File {
    val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "tmp").apply { mkdirs() }
    return File(dir, name)
  }

  /** Devuelve true si se copió a la galería. Bloqueante: llamar fuera del hilo principal. */
  fun moveToGallery(context: Context, file: File): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    val resolver = context.contentResolver
    val values = ContentValues().apply {
      put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
      put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
      put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SportCam")
      put(MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
    return try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
      values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
      file.delete()
      true
    } catch (e: Exception) {
      resolver.delete(uri, null, null)
      false
    }
  }

  /** Grabaciones que quedaron sin mover (p. ej. la app se cerró grabando). */
  fun pending(context: Context): List<File> =
    File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "tmp").listFiles()?.filter { it.length() > 0 }.orEmpty()
}
