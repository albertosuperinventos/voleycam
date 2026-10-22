package app.superinventos.sportcam

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

class SettingsActivity : AppCompatActivity() {

  private lateinit var prefs: Prefs
  private lateinit var rgPlatform: RadioGroup
  private lateinit var etYoutube: EditText
  private lateinit var etFacebook: EditText
  private lateinit var etCustom: EditText
  private lateinit var rgRes: RadioGroup
  private lateinit var rgFps: RadioGroup
  private lateinit var etBitrate: EditText
  private lateinit var swRecord: SwitchCompat
  private lateinit var etRemote: EditText
  private lateinit var tvKey: TextView
  private lateinit var ivQr: ImageView
  private lateinit var tvQrHint: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings)
    prefs = Prefs(this)

    rgPlatform = findViewById(R.id.rgPlatform)
    etYoutube = findViewById(R.id.etYoutube)
    etFacebook = findViewById(R.id.etFacebook)
    etCustom = findViewById(R.id.etCustom)
    rgRes = findViewById(R.id.rgRes)
    rgFps = findViewById(R.id.rgFps)
    etBitrate = findViewById(R.id.etBitrate)
    swRecord = findViewById(R.id.swRecord)
    etRemote = findViewById(R.id.etRemote)
    tvKey = findViewById(R.id.tvKey)
    ivQr = findViewById(R.id.ivQr)
    tvQrHint = findViewById(R.id.tvQrHint)

    rgPlatform.check(when (prefs.platform) {
      Prefs.PLATFORM_FACEBOOK -> R.id.rbFacebook
      Prefs.PLATFORM_CUSTOM -> R.id.rbCustom
      else -> R.id.rbYoutube
    })
    etYoutube.setText(prefs.youtubeKey)
    etFacebook.setText(prefs.facebookKey)
    etCustom.setText(prefs.customUrl)
    rgRes.check(when (prefs.resolution) { 480 -> R.id.rb480; 1080 -> R.id.rb1080; else -> R.id.rb720 })
    rgFps.check(if (prefs.fps == 25) R.id.rb25 else R.id.rb30)
    etBitrate.setText(prefs.bitrateKbps.toString())
    swRecord.isChecked = prefs.recordLocal
    etRemote.setText(prefs.remoteUrl)
    tvKey.text = prefs.channelKey

    rgPlatform.setOnCheckedChangeListener { _, _ -> updatePlatformFields() }
    rgRes.setOnCheckedChangeListener { _, id ->
      etBitrate.setText(when (id) { R.id.rb480 -> "1500"; R.id.rb1080 -> "5000"; else -> "3000" })
    }
    etRemote.addTextChangedListener(object : TextWatcher {
      override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
      override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
      override fun afterTextChanged(s: Editable?) { prefs.remoteUrl = s.toString(); updateQr() }
    })
    findViewById<Button>(R.id.btnNewKey).setOnClickListener {
      AlertDialog.Builder(this)
        .setTitle("¿Crear una clave nueva?")
        .setMessage("Empezarás un canal vacío: equipos, logos y marcador no se verán con la clave nueva. Los mandos abiertos tendrán que volver a escanear el QR.")
        .setPositiveButton("Crear") { _, _ ->
          prefs.channelKey = Prefs.newKey(); tvKey.text = prefs.channelKey; updateQr()
          setResult(RESULT_OK)
        }
        .setNegativeButton("Cancelar", null)
        .show()
    }
    findViewById<Button>(R.id.btnUseKey).setOnClickListener {
      ChannelPicker.show(this, prefs) { tvKey.text = prefs.channelKey; updateQr(); setResult(RESULT_OK) }
    }
    findViewById<Button>(R.id.btnSave).setOnClickListener { save(); finish() }

    updatePlatformFields()
    updateQr()
  }

  override fun onPause() {
    super.onPause()
    save()
  }

  private fun updatePlatformFields() {
    val id = rgPlatform.checkedRadioButtonId
    fun show(label: Int, field: Int, on: Boolean) {
      val v = if (on) View.VISIBLE else View.GONE
      findViewById<View>(label).visibility = v; findViewById<View>(field).visibility = v
    }
    show(R.id.lblYoutube, R.id.etYoutube, id == R.id.rbYoutube)
    show(R.id.lblFacebook, R.id.etFacebook, id == R.id.rbFacebook)
    show(R.id.lblCustom, R.id.etCustom, id == R.id.rbCustom)
  }

  private fun save() {
    prefs.platform = when (rgPlatform.checkedRadioButtonId) {
      R.id.rbFacebook -> Prefs.PLATFORM_FACEBOOK
      R.id.rbCustom -> Prefs.PLATFORM_CUSTOM
      else -> Prefs.PLATFORM_YOUTUBE
    }
    prefs.youtubeKey = etYoutube.text.toString()
    prefs.facebookKey = etFacebook.text.toString()
    prefs.customUrl = etCustom.text.toString()
    prefs.resolution = when (rgRes.checkedRadioButtonId) { R.id.rb480 -> 480; R.id.rb1080 -> 1080; else -> 720 }
    prefs.fps = if (rgFps.checkedRadioButtonId == R.id.rb25) 25 else 30
    prefs.bitrateKbps = etBitrate.text.toString().toIntOrNull()?.coerceIn(300, 12000) ?: 3000
    prefs.recordLocal = swRecord.isChecked
    prefs.remoteUrl = etRemote.text.toString()
  }

  private fun updateQr() {
    val link = prefs.remoteLink()
    if (link == null) {
      ivQr.setImageBitmap(null)
      tvQrHint.text = "Escribe arriba la dirección de la web de control para generar el QR."
      return
    }
    tvQrHint.text = link
    try {
      val size = 512
      val matrix = QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
      val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
      for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
      ivQr.setImageBitmap(bmp)
    } catch (e: Exception) {
      Toast.makeText(this, "No se pudo generar el QR", Toast.LENGTH_SHORT).show()
    }
  }
}
