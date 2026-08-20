package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.example.universalremote.model.NearbyDevice
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class BleDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(0L)
        .build()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) = emit(result)

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::emit)

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "сканирование уже запущено"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "ошибка регистрации Bluetooth"
                SCAN_FAILED_INTERNAL_ERROR -> "внутренняя ошибка Bluetooth"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE scan не поддерживается"
                SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> "нет ресурсов Bluetooth-контроллера"
                SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> "слишком частые BLE-сканы"
                else -> "ошибка BLE $errorCode"
            }
            onStatus(reason)
        }
    }

    @SuppressLint("MissingPermission")
    private fun emit(result: ScanResult) {
        val address = result.device.address
        val advertisedName = result.scanRecord?.deviceName?.trim().orEmpty()
        val label = advertisedName.ifBlank { "BLE ${address.takeLast(5)}" }
        val identity = classify(label)
        val advertisedPower = result.scanRecord?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: -59
        // RSSI-derived distance is a rough radio estimate, not a measurement.
        val distance = 10.0.pow((advertisedPower - result.rssi) / 22.0).coerceAtLeast(0.1)
        onDevice(
            NearbyDevice(
                id = "ble:$address",
                name = label,
                kind = identity.first,
                protocol = "Bluetooth LE",
                address = address,
                controllable = false,
                signalDbm = result.rssi,
                distanceMeters = distance,
                brand = identity.second
            )
        )
        if (seen.add(address)) {
            val n = seen.size
            if (n == 1 || n % 10 == 0) onStatus("найдено BLE: $n")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        seen.clear()
        val adapter = manager?.adapter
        if (adapter == null) return onStatus("Bluetooth не поддерживается")
        if (!adapter.isEnabled) return onStatus("Bluetooth выключен")
        val scanner = adapter.bluetoothLeScanner ?: return onStatus("BLE-сканер недоступен")
        try {
            scanner.startScan(null, settings, callback)
            onStatus("BLE-сканирование запущено")
        } catch (e: SecurityException) {
            onStatus("нет разрешения Nearby devices/геолокации")
        } catch (e: Throwable) {
            onStatus("BLE: ${e.javaClass.simpleName}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { manager?.adapter?.bluetoothLeScanner?.stopScan(callback) }
    }

    private fun classify(name: String): Pair<String, String?> {
        val n = name.lowercase()
        val brand = when {
            "samsung" in n || "galaxy" in n -> "Samsung"
            "iphone" in n || "ipad" in n || "apple" in n -> "Apple"
            "xiaomi" in n || "redmi" in n || n.startsWith("mi ") -> "Xiaomi"
            "huawei" in n || "honor" in n -> "Huawei / Honor"
            "sony" in n -> "Sony"
            "lg" in n -> "LG"
            "philips" in n || "hue" in n -> "Philips"
            "tuya" in n || "smart life" in n -> "Tuya"
            "jbl" in n -> "JBL"
            "sonos" in n -> "Sonos"
            else -> null
        }
        val kind = when {
            listOf("tv", "bravia", "webos", "androidtv", "android tv").any { it in n } -> "Телевизор"
            listOf("lamp", "light", "bulb", "hue", "люстра").any { it in n } -> "Лампа / свет"
            listOf("projector", "beamer", "проектор").any { it in n } -> "Проектор"
            listOf("speaker", "soundbar", "jbl", "sonos", "audio", "колонка").any { it in n } -> "Колонка / аудио"
            listOf("buds", "headphone", "headset", "airpods").any { it in n } -> "Наушники"
            listOf("desktop", "laptop", "pc-", "macbook", "computer").any { it in n } -> "Компьютер"
            listOf("watch", "band", "fitbit").any { it in n } -> "Часы / браслет"
            listOf("phone", "iphone", "galaxy", "redmi", "pixel").any { it in n } -> "Телефон"
            listOf("keyboard", "mouse", "gamepad", "controller").any { it in n } -> "Аксессуар"
            listOf("printer", "epson", "canon", "deskjet").any { it in n } -> "Принтер"
            else -> "Bluetooth-устройство"
        }
        return kind to brand
    }
}
