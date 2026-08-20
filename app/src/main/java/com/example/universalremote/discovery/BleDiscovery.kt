package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.example.universalremote.model.NearbyDevice
import kotlin.math.pow

class BleDiscovery(context: Context, private val onDevice: (NearbyDevice) -> Unit) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val settings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .setReportDelay(700L)
        .build()

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) = emit(result)

        @SuppressLint("MissingPermission")
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::emit)
    }

    @SuppressLint("MissingPermission")
    private fun emit(result: ScanResult) {
        val address = result.device.address
        val advertisedName = result.scanRecord?.deviceName?.trim().orEmpty()
        val label = advertisedName.ifBlank { "BLE ${address.takeLast(5)}" }
        val identity = classify(label)
        val advertisedPower = result.scanRecord?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: -59
        // RSSI distance is only an estimate. Do not artificially stop at 99.9 m.
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
    }

    @SuppressLint("MissingPermission")
    fun start() = scanner?.startScan(null, settings, callback)

    @SuppressLint("MissingPermission")
    fun stop() = scanner?.stopScan(callback)

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
