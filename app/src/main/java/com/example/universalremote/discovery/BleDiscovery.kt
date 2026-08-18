package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.example.universalremote.model.NearbyDevice
import kotlin.math.pow

class BleDiscovery(context: Context, private val onDevice: (NearbyDevice) -> Unit) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val label = result.scanRecord?.deviceName ?: "BLE-устройство"
            val identity = classify(label)
            val advertisedPower = result.scanRecord?.txPowerLevel?.takeUnless { it == Int.MIN_VALUE } ?: -59
            val distance = 10.0.pow((advertisedPower - result.rssi) / 22.0).coerceIn(0.1, 99.9)
            onDevice(NearbyDevice(
                "ble:$address", label, identity.first, "BLE", address,
                controllable = false, signalDbm = result.rssi,
                distanceMeters = distance, brand = identity.second
            ))
        }
    }

    @SuppressLint("MissingPermission") fun start() = scanner?.startScan(callback)
    @SuppressLint("MissingPermission") fun stop() = scanner?.stopScan(callback)

    private fun classify(name: String): Pair<String, String?> {
        val n = name.lowercase()
        val brand = when {
            "samsung" in n || "galaxy" in n -> "Samsung"
            "iphone" in n || "ipad" in n || "apple" in n -> "Apple"
            "xiaomi" in n || "redmi" in n || "mi " in n -> "Xiaomi"
            "huawei" in n || "honor" in n -> "Huawei / Honor"
            "sony" in n -> "Sony"
            "lg" in n -> "LG"
            "philips" in n || "hue" in n -> "Philips"
            "tuya" in n || "smart life" in n -> "Tuya"
            "jbl" in n -> "JBL"
            else -> null
        }
        val kind = when {
            listOf("tv", "bravia", "webos", "androidtv").any { it in n } -> "Телевизор"
            listOf("lamp", "light", "bulb", "hue", "люстра").any { it in n } -> "Лампа"
            listOf("projector", "beamer", "проектор").any { it in n } -> "Проектор"
            listOf("buds", "headphone", "headset", "airpods", "jbl", "speaker").any { it in n } -> "Аудиоустройство"
            listOf("watch", "band", "fitbit").any { it in n } -> "Часы / браслет"
            listOf("phone", "iphone", "galaxy", "redmi", "pixel").any { it in n } -> "Телефон"
            listOf("keyboard", "mouse", "gamepad", "controller").any { it in n } -> "Аксессуар"
            listOf("printer", "epson", "canon", "deskjet").any { it in n } -> "Принтер"
            else -> "Bluetooth-устройство"
        }
        return kind to brand
    }
}
