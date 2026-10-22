package app.superinventos.sportcam

import android.app.Activity
import android.app.AlertDialog
import android.net.Uri
import android.widget.EditText
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Cambiar al canal de otro móvil: escaneando su QR (enlace del mando) o pegando la clave. */
object ChannelPicker {

  /** Acepta la clave sola o un enlace con #k=CLAVE. */
  fun parseKey(text: String): String? {
    val t = text.trim()
    val m = Regex("[#&?]k=([^&#\\s]+)").find(t)
    val k = (if (m != null) Uri.decode(m.groupValues[1]) else t).trim()
    return if (k.length >= 16 && !k.contains(' ') && !k.contains('/')) k else null
  }

  fun show(a: Activity, prefs: Prefs, onChanged: () -> Unit) {
    AlertDialog.Builder(a)
      .setTitle("Usar el canal de otro móvil")
      .setMessage("Verás sus equipos, patrocinadores, historial y marcador. En el otro móvil, el QR está en Ajustes de la app.\n\nClave actual:\n${prefs.channelKey}")
      .setPositiveButton("Escanear QR") { _, _ -> scan(a, prefs, onChanged) }
      .setNeutralButton("Pegar clave") { _, _ -> paste(a, prefs, onChanged) }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun apply(a: Activity, prefs: Prefs, raw: String, onChanged: () -> Unit) {
    val k = parseKey(raw)
    if (k == null) { Toast.makeText(a, "No es una clave de canal válida", Toast.LENGTH_LONG).show(); return }
    if (k == prefs.channelKey) { Toast.makeText(a, "Ya estás usando ese canal", Toast.LENGTH_SHORT).show(); return }
    AlertDialog.Builder(a)
      .setTitle("¿Cambiar de canal?")
      .setMessage("Nueva clave:\n$k\n\nApunta la actual por si quieres volver:\n${prefs.channelKey}")
      .setPositiveButton("Cambiar") { _, _ ->
        prefs.channelKey = k
        Toast.makeText(a, "Canal cambiado", Toast.LENGTH_SHORT).show()
        onChanged()
      }
      .setNegativeButton("Cancelar", null)
      .show()
  }

  private fun scan(a: Activity, prefs: Prefs, onChanged: () -> Unit) {
    val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(a, opts).startScan()
      .addOnSuccessListener { b -> apply(a, prefs, b.rawValue ?: "", onChanged) }
      .addOnFailureListener { e -> Toast.makeText(a, "No se pudo escanear: ${e.message}. Prueba a pegar la clave.", Toast.LENGTH_LONG).show() }
  }

  private fun paste(a: Activity, prefs: Prefs, onChanged: () -> Unit) {
    val et = EditText(a).apply { hint = "Clave o enlace del mando"; setSingleLine() }
    val pad = (16 * a.resources.displayMetrics.density).toInt()
    val box = android.widget.FrameLayout(a).apply { setPadding(pad, pad / 2, pad, 0); addView(et) }
    AlertDialog.Builder(a)
      .setTitle("Pegar clave del canal")
      .setView(box)
      .setPositiveButton("Usar") { _, _ -> apply(a, prefs, et.text.toString(), onChanged) }
      .setNegativeButton("Cancelar", null)
      .show()
  }
}
