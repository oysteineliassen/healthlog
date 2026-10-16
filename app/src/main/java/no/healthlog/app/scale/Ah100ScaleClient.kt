package no.healthlog.app.scale

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import no.healthlog.app.scale.Ah100Protocol as P

/** What the UI shows. */
sealed interface ScaleState {
    data object Idle : ScaleState
    data object Searching : ScaleState            // scanning, waiting for the scale to wake
    data object Connecting : ScaleState
    data object Measuring : ScaleState            // authorised, waiting for result
    data class Done(val reading: P.Reading) : ScaleState
    data class Failed(val message: String) : ScaleState
}

/**
 * Connects to a Huawei AH100/CH100, runs the handshake and delivers one measurement.
 *
 * Usage: call [start] (permissions must already be granted), ask the user to step on
 * the scale barefoot, observe [state]. Call [stop] when the screen closes.
 *
 * The scale only advertises while awake, so we scan until it shows up.
 */
@SuppressLint("MissingPermission") // the screen checks permissions before calling start()
class Ah100ScaleClient(
    context: Context,
    private val profile: P.Profile,
    /** Last known weight; the scale uses it as a hint in the profile. */
    private val lastWeightKg: Float?,
) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ScaleState>(ScaleState.Idle)
    val state: StateFlow<ScaleState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var mac = ByteArray(6)
    private val token = P.authToken(profile.slot)
    private var key: ByteArray? = null
    private var authTries = 0
    private var pendingBody: ByteArray? = null
    private var finished = false

    // Android allows only one GATT operation at a time, so writes are queued.
    private val queue = ArrayDeque<() -> Boolean>()
    private var busy = false

    fun start() {
        val a = adapter
        if (a == null || !a.isEnabled) {
            _state.value = ScaleState.Failed("Bluetooth er av")
            return
        }
        stop()
        finished = false
        _state.value = ScaleState.Searching
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        a.bluetoothLeScanner?.startScan(null, settings, scanCallback)
        main.postDelayed(scanTimeout, 60_000)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        gatt?.let { runCatching { it.disconnect(); it.close() } }
        gatt = null
        queue.clear()
        busy = false
        key = null
        authTries = 0
        pendingBody = null
    }

    private val scanTimeout = Runnable {
        if (_state.value == ScaleState.Searching) {
            stop()
            _state.value = ScaleState.Failed("Fant ikke vekten. Tråkk på den så den våkner, og prøv igjen.")
        }
    }

    // ------------------------------------------------------------------ scan

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name
            if (!P.matchesName(name) || gatt != null) return
            log("Fant $name ${result.device.address}")
            runCatching { adapter?.bluetoothLeScanner?.stopScan(this) }
            main.removeCallbacks(scanTimeout)
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = ScaleState.Failed("Bluetooth-søk feilet (kode $errorCode)")
        }
    }

    private fun connect(device: BluetoothDevice) {
        _state.value = ScaleState.Connecting
        mac = P.macToBytes(device.address)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    // ------------------------------------------------------------------ GATT

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post { onLinkChanged(g, status, newState) }
        }

        private fun onLinkChanged(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.discoverServices()
                BluetoothProfile.STATE_DISCONNECTED -> {
                    g.close()
                    if (gatt === g) gatt = null
                    if (!finished && _state.value !is ScaleState.Failed) {
                        _state.value = ScaleState.Failed(
                            if (status == 61 /* MIC failure: stale pairing */)
                                "Tilkoblingen ble avvist. Glem vekten i Android sine Bluetooth-innstillinger og prøv igjen."
                            else "Mistet kontakten med vekten (status $status)"
                        )
                    }
                }
                else -> Unit
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post { setUpNotifications(g) }
        }

        private fun setUpNotifications(g: BluetoothGatt) {
            val service = g.getService(P.SERVICE_UUID)
            val tx = service?.getCharacteristic(P.WRITE_UUID)
            val rx = service?.getCharacteristic(P.NOTIFY_UUID)
            if (tx == null || rx == null) {
                fail("Dette ser ikke ut som en AH100 (mangler tjeneste FAA0)")
                return
            }
            writeChar = tx
            g.setCharacteristicNotification(rx, true)
            val cccd = rx.getDescriptor(P.CCCD_UUID)
            enqueue { writeDescriptor(g, cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) }
            // Usually the scale says "wakeup" on its own; if not, start the handshake anyway.
            main.postDelayed({ if (key == null && authTries == 0) sendAuth() }, 2_000)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            main.post { next() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            main.post { next() }
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == P.NOTIFY_UUID) value.copyOf().let { main.post { onNotify(it) } }
        }

        // API < 33
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == P.NOTIFY_UUID) {
                @Suppress("DEPRECATION")
                val v = c.value?.copyOf() ?: return
                main.post { onNotify(v) }
            }
        }
    }

    // ------------------------------------------------------------------ protocol flow

    private fun onNotify(frame: ByteArray) {
        val n = P.parse(frame, mac) ?: return
        log("← op=%02X %s".format(n.op, frame.joinToString(" ") { "%02X".format(it) }))
        when (n.op) {
            P.Op.WAKEUP -> if (key == null) sendAuth()

            P.Op.AUTH_RESULT -> {
                if (n.body.firstOrNull()?.toInt() == 1) onAuthorised()
                else if (authTries < 3) sendAuth()
                else send(P.bindFrame(token, mac))       // first time with this phone: bind, then auth again
            }

            P.Op.BIND_OK -> sendAuth()

            P.Op.USER_CHANGED -> key?.let { sendUserInfo(it) }

            P.Op.MEASUREMENT -> if (n.start == P.START_NOTIFY_ENCRYPTED) {
                if (n.body.size >= 15) decode(n.body) else pendingBody = n.body
            }

            P.Op.MEASUREMENT_PART2 -> pendingBody?.let { first ->
                pendingBody = null
                decode(first + n.body)
            }

            else -> Unit // clock/unit/version acks, history, sleep — not needed
        }
    }

    private fun sendAuth() {
        authTries++
        send(P.authFrame(token, mac))
    }

    private fun onAuthorised() {
        if (key != null) return
        val k = P.sessionKey(token, mac)
        key = k
        send(P.unitKgFrame(mac))
        send(P.clockFrame(LocalDateTime.now(), mac))
        sendUserInfo(k)
        send(P.versionFrame(mac))
        _state.value = ScaleState.Measuring
        main.postDelayed({
            if (_state.value == ScaleState.Measuring) fail("Fikk ingen måling. Stå stille og barbent på vekten.")
        }, 45_000)
    }

    private fun sendUserInfo(k: ByteArray) {
        val hint = lastWeightKg?.takeIf { it > 0 } ?: (22f * (profile.heightCm / 100f).let { it * it })
        send(P.userInfoFrame(token, profile, hint, k, mac))
    }

    private fun decode(body: ByteArray) {
        val k = key ?: return
        val reading = runCatching { P.decodeMeasurement(body, k) }.getOrElse {
            log("Dekoding feilet: ${it.message}")
            return
        }
        if (reading.weightKg !in 2.5f..150f) {
            log("Urimelig vekt ${reading.weightKg}, ignorerer")
            return
        }
        send(P.resultAckFrame(mac))
        finished = true
        _state.value = ScaleState.Done(reading)
        main.postDelayed({ stop() }, 1_500) // let the ACK go out first
    }

    private fun fail(msg: String) {
        finished = true
        stop()
        _state.value = ScaleState.Failed(msg)
    }

    // ------------------------------------------------------------------ write queue

    private fun send(frame: ByteArray) {
        val g = gatt ?: return
        val c = writeChar ?: return
        log("→ " + frame.joinToString(" ") { "%02X".format(it) })
        enqueue { writeCharacteristic(g, c, frame) }
    }

    private fun enqueue(op: () -> Boolean) {
        queue.addLast(op)
        if (!busy) next()
    }

    private fun next() {
        busy = false
        while (queue.isNotEmpty()) {
            if (queue.removeFirst().invoke()) {
                busy = true
                return
            }
        }
    }

    private fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(c, v, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0
        } else {
            @Suppress("DEPRECATION")
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            c.value = v
            @Suppress("DEPRECATION")
            g.writeCharacteristic(c)
        }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor?, v: ByteArray): Boolean {
        if (d == null) return false
        return if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, v) == 0
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun log(msg: String) = Log.d("Ah100Scale", msg)
}
