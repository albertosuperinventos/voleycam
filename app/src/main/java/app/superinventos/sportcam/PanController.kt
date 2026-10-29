package app.superinventos.sportcam

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Cabezal de giro VoleyCam-Pan (ESP32 + servo) por Bluetooth LE.
 * Busca el cabezal, se conecta, reconecta solo si se pierde y le manda órdenes de texto
 * (L, R, S, C, V1..V5, A1500). Ver esp32/voleycam_pan/voleycam_pan.ino.
 * Los permisos de Bluetooth los pide MainActivity antes de llamar a start().
 */
@SuppressLint("MissingPermission")
class PanController(private val ctx: Context, private val onChange: () -> Unit) {

  companion object {
    val SERVICE: UUID = UUID.fromString("6e5a0001-7a1c-4c5e-9d3b-5f0c0a7e0c01")
    val RX: UUID = UUID.fromString("6e5a0002-7a1c-4c5e-9d3b-5f0c0a7e0c01")
    val TX: UUID = UUID.fromString("6e5a0003-7a1c-4c5e-9d3b-5f0c0a7e0c01")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val RETRY_MS = 4000L
    private const val SCAN_MS = 15000L
  }

  private val ui = Handler(Looper.getMainLooper())
  private val adapter get() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
  private var gatt: BluetoothGatt? = null
  private var rx: BluetoothGattCharacteristic? = null
  private var scanning = false
  private var running = false

  /** "off" (sin usar), "searching", "connected", "nobt" (Bluetooth apagado). */
  @Volatile var state = "off"
    private set
  /** Posición del servo en microsegundos (1500 = centro). */
  @Volatile var position = 1500
    private set
  @Volatile var speed = 3
    private set

  val connected get() = state == "connected"

  fun start() {
    if (running) return
    running = true
    connect()
  }

  fun stop() {
    running = false
    ui.removeCallbacksAndMessages(null)
    stopScan()
    try { gatt?.close() } catch (_: Exception) {}
    gatt = null; rx = null
    setState("off")
  }

  /** Manda una orden. Si no hay conexión no hace nada. */
  fun send(cmd: String) {
    val g = gatt ?: return
    val ch = rx ?: return
    if (cmd.startsWith("V")) cmd.drop(1).toIntOrNull()?.let { speed = it }
    val bytes = cmd.toByteArray()
    try {
      if (Build.VERSION.SDK_INT >= 33) {
        g.writeCharacteristic(ch, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
      } else {
        @Suppress("DEPRECATION")
        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        @Suppress("DEPRECATION")
        ch.value = bytes
        @Suppress("DEPRECATION")
        g.writeCharacteristic(ch)
      }
    } catch (_: Exception) {}
  }

  private fun setState(s: String) {
    if (state == s) return
    state = s
    ui.post { onChange() }
  }

  private fun retryLater() {
    if (!running) return
    ui.removeCallbacksAndMessages(null)
    ui.postDelayed({ connect() }, RETRY_MS)
  }

  private fun connect() {
    if (!running) return
    val a = adapter
    if (a == null || !a.isEnabled) { setState("nobt"); retryLater(); return }
    setState("searching")
    val scanner = a.bluetoothLeScanner ?: run { retryLater(); return }
    val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
    val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
    try { scanner.startScan(filters, settings, scanCb); scanning = true } catch (_: Exception) { retryLater(); return }
    // Si no aparece en un rato, se para la búsqueda y se reintenta más tarde
    ui.postDelayed({ if (scanning) { stopScan(); retryLater() } }, SCAN_MS)
  }

  private fun stopScan() {
    if (!scanning) return
    scanning = false
    try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
  }

  private val scanCb = object : ScanCallback() {
    override fun onScanResult(callbackType: Int, result: ScanResult) {
      if (!scanning) return
      stopScan()
      ui.removeCallbacksAndMessages(null)
      openGatt(result.device)
    }
    override fun onScanFailed(errorCode: Int) { scanning = false; retryLater() }
  }

  private fun openGatt(d: BluetoothDevice) {
    try { gatt?.close() } catch (_: Exception) {}
    gatt = d.connectGatt(ctx, false, gattCb, BluetoothDevice.TRANSPORT_LE)
  }

  private val gattCb = object : BluetoothGattCallback() {
    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
      if (newState == BluetoothProfile.STATE_CONNECTED) {
        g.discoverServices()
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        try { g.close() } catch (_: Exception) {}
        if (gatt == g) { gatt = null; rx = null }
        if (running) { setState("searching"); retryLater() }
      }
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
      val svc = g.getService(SERVICE)
      val rxCh = svc?.getCharacteristic(RX)
      if (svc == null || rxCh == null) { g.disconnect(); return }
      rx = rxCh
      svc.getCharacteristic(TX)?.let { tx ->
        g.setCharacteristicNotification(tx, true)
        tx.getDescriptor(CCCD)?.let { d ->
          if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
          else {
            @Suppress("DEPRECATION")
            d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
          }
        }
      }
      setState("connected")
      ui.postDelayed({ send("V$speed") }, 300)
    }

    @Deprecated("Android < 13")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
      @Suppress("DEPRECATION")
      parse(c.value ?: return)
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
      parse(value)
    }
  }

  /** Estado del cabezal: "P1500 V3 M0". */
  private fun parse(v: ByteArray) {
    val s = String(v)
    Regex("P(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { position = it }
    Regex("V(\\d)").find(s)?.groupValues?.get(1)?.toIntOrNull()?.let { speed = it }
  }
}
