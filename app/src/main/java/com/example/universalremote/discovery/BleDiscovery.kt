package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import com.example.universalremote.model.NearbyDevice

class BleDiscovery(context: Context, private val onDevice: (NearbyDevice) -> Unit) {
    private val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val label = result.scanRecord?.deviceName ?: "BLE-устройство"
            onDevice(NearbyDevice("ble:$address", label, "Bluetooth", "BLE", address))
        }
    }

    @SuppressLint("MissingPermission") fun start() = scanner?.startScan(callback)
    @SuppressLint("MissingPermission") fun stop() = scanner?.stopScan(callback)
}
