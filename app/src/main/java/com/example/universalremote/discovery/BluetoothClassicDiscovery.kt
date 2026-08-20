package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.example.universalremote.model.NearbyDevice
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

/** Classic Bluetooth discovery of devices that are actually discoverable now. */
class BluetoothClassicDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        .takeUnless { it == Short.MIN_VALUE }?.toInt()
                    emit(device, rssi)
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onStatus("Classic Bluetooth: поиск завершён (${seen.size})")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        seen.clear()
        val adapter = manager?.adapter
        if (adapter == null) return onStatus("Classic Bluetooth не поддерживается")
        if (!adapter.isEnabled) return onStatus("Classic Bluetooth: выключен")
        try {
            registerReceiver()
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (adapter.startDiscovery()) onStatus("Classic Bluetooth: поиск запущен")
            else onStatus("Classic Bluetooth: startDiscovery не запустился")
        } catch (e: SecurityException) {
            onStatus("Classic Bluetooth: нет разрешения Nearby devices")
        } catch (e: Throwable) {
            onStatus("Classic Bluetooth: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { manager?.adapter?.takeIf { it.isDiscovering }?.cancelDiscovery() }
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter)
        }
        registered = true
    }

    @SuppressLint("MissingPermission")
    private fun emit(device: BluetoothDevice, rssi: Int?) {
        val address = device.address ?: return
        val name = runCatching { device.name }.getOrNull()?.trim().orEmpty().ifBlank { "BT ${address.takeLast(5)}" }
        val kind = classify(name, device.bluetoothClass?.majorDeviceClass)
        val distance = rssi?.let { 10.0.pow((-59 - it) / 22.0).coerceAtLeast(0.1) }
        onDevice(
            NearbyDevice(
                id = "bt:$address",
                name = name,
                kind = kind,
                protocol = "Bluetooth Classic",
                address = address,
                signalDbm = rssi,
                distanceMeters = distance,
                analysisNote = "Обнаружено в текущем Bluetooth discovery; сохранённые, но отсутствующие устройства сюда не добавляются"
            )
        )
        if (seen.add(address)) {
            val n = seen.size
            if (n == 1 || n % 10 == 0) onStatus("Classic Bluetooth: найдено $n")
        }
    }

    private fun classify(name: String, major: Int?): String {
        val n = name.lowercase()
        return when {
            listOf("tv", "bravia", "webos").any { it in n } -> "Телевизор"
            listOf("speaker", "soundbar", "jbl", "sonos", "audio").any { it in n } -> "Колонка / аудио"
            listOf("headphone", "headset", "buds", "airpods").any { it in n } -> "Наушники"
            listOf("watch", "band").any { it in n } -> "Часы / браслет"
            listOf("phone", "iphone", "galaxy", "redmi", "pixel").any { it in n } -> "Телефон"
            listOf("keyboard", "mouse", "gamepad", "controller").any { it in n } -> "Аксессуар"
            major == 0x0200 -> "Телефон"
            major == 0x0400 -> "Колонка / аудио"
            major == 0x0100 -> "Компьютер"
            else -> "Bluetooth-устройство"
        }
    }
}
