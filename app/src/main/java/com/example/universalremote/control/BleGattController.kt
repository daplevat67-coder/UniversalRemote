package com.example.universalremote.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class BleGattController(context: Context) {
    data class CharacteristicInfo(
        val uuid: String,
        val name: String,
        val properties: List<String>,
        val descriptorCount: Int
    )

    data class ServiceInfo(
        val uuid: String,
        val name: String,
        val primary: Boolean,
        val characteristics: List<CharacteristicInfo>
    )

    data class InspectionResult(
        val ok: Boolean,
        val message: String,
        val address: String,
        val deviceName: String?,
        val bondState: String,
        val services: List<ServiceInfo> = emptyList()
    )

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val active = ConcurrentHashMap<String, BluetoothGatt>()

    @SuppressLint("MissingPermission")
    fun inspect(address: String, callback: (InspectionResult) -> Unit) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            callback(InspectionResult(false, "Некорректный Bluetooth MAC", address, null, "неизвестно"))
            return
        }
        val adapter = manager?.adapter
        if (adapter == null) {
            callback(InspectionResult(false, "Bluetooth не поддерживается", address, null, "неизвестно"))
            return
        }
        if (!adapter.isEnabled) {
            callback(InspectionResult(false, "Bluetooth выключен", address, null, "неизвестно"))
            return
        }

        val finished = AtomicBoolean(false)
        val timeout = Runnable {
            if (finished.compareAndSet(false, true)) {
                closeAddress(address)
                callback(InspectionResult(false, "BLE GATT: таймаут подключения/обнаружения сервисов", address, null, "неизвестно"))
            }
        }
        main.postDelayed(timeout, 15_000L)

        fun finish(result: InspectionResult, gatt: BluetoothGatt? = null) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            runCatching { gatt?.disconnect() }
            runCatching { gatt?.close() }
            active.remove(address, gatt)
            callback(result)
        }

        try {
            val device = adapter.getRemoteDevice(address)
            closeAddress(address)
            val gatt = device.connectGatt(appContext, false, object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(
                            InspectionResult(
                                false,
                                "BLE GATT: ошибка соединения status=$status",
                                address,
                                safeName(device),
                                bondStateName(device.bondState)
                            ),
                            g
                        )
                        return
                    }
                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            if (!g.discoverServices()) {
                                finish(
                                    InspectionResult(false, "BLE GATT: discoverServices() не запустился", address, safeName(device), bondStateName(device.bondState)),
                                    g
                                )
                            }
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> finish(
                            InspectionResult(false, "BLE GATT: устройство отключилось", address, safeName(device), bondStateName(device.bondState)),
                            g
                        )
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        finish(
                            InspectionResult(false, "BLE GATT: сервисы не прочитаны, status=$status", address, safeName(device), bondStateName(device.bondState)),
                            g
                        )
                        return
                    }
                    val services = g.services.orEmpty().map(::serviceInfo)
                    val writable = services.sumOf { service -> service.characteristics.count { info -> info.properties.any { it == "WRITE" || it == "WRITE_NO_RESPONSE" } } }
                    val notifying = services.sumOf { service -> service.characteristics.count { info -> info.properties.any { it == "NOTIFY" || it == "INDICATE" } } }
                    finish(
                        InspectionResult(
                            true,
                            "GATT найден: ${services.size} сервис(ов), writable=$writable, notify/indicate=$notifying",
                            address,
                            safeName(device),
                            bondStateName(device.bondState),
                            services
                        ),
                        g
                    )
                }
            }, BluetoothDevice.TRANSPORT_LE)
            active[address] = gatt
        } catch (e: SecurityException) {
            main.removeCallbacks(timeout)
            finished.set(true)
            callback(InspectionResult(false, "Нет разрешения Bluetooth Connect", address, null, "неизвестно"))
        } catch (e: Throwable) {
            main.removeCallbacks(timeout)
            finished.set(true)
            callback(InspectionResult(false, "BLE GATT: ${e.javaClass.simpleName}: ${e.message ?: "ошибка"}", address, null, "неизвестно"))
        }
    }

    @SuppressLint("MissingPermission")
    fun requestBond(address: String, callback: (Boolean, String) -> Unit) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            callback(false, "Некорректный Bluetooth MAC")
            return
        }
        val adapter = manager?.adapter ?: return callback(false, "Bluetooth не поддерживается")
        if (!adapter.isEnabled) return callback(false, "Bluetooth выключен")

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: Throwable) {
            return callback(false, "Не удалось получить Bluetooth-устройство")
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            callback(true, "Устройство уже сопряжено штатным Android Bluetooth pairing")
            return
        }

        val done = AtomicBoolean(false)
        lateinit var receiver: BroadcastReceiver
        val timeout = Runnable {
            if (done.compareAndSet(false, true)) {
                unregister(receiver)
                callback(false, "Сопряжение не завершилось за 60 секунд")
            }
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val changed = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } ?: return
                if (!changed.address.equals(address, ignoreCase = true)) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
                when {
                    state == BluetoothDevice.BOND_BONDED && done.compareAndSet(false, true) -> {
                        main.removeCallbacks(timeout)
                        unregister(this)
                        callback(true, "Сопряжение завершено")
                    }
                    state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING && done.compareAndSet(false, true) -> {
                        main.removeCallbacks(timeout)
                        unregister(this)
                        callback(false, "Сопряжение отменено или отклонено устройством")
                    }
                }
            }
        }

        try {
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") appContext.registerReceiver(receiver, filter)
            main.postDelayed(timeout, 60_000L)
            val started = if (device.bondState == BluetoothDevice.BOND_BONDING) true else device.createBond()
            if (!started && done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                unregister(receiver)
                callback(false, "Android не смог запустить штатное Bluetooth-сопряжение")
            } else if (started) {
                callback(false, "Запрос pairing отправлен. Подтвердите системный PIN/код, если Android или устройство его покажет.")
            }
        } catch (e: SecurityException) {
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                unregister(receiver)
            }
            callback(false, "Нет разрешения Bluetooth Connect")
        } catch (e: Throwable) {
            if (done.compareAndSet(false, true)) {
                main.removeCallbacks(timeout)
                unregister(receiver)
            }
            callback(false, "Pairing: ${e.javaClass.simpleName}: ${e.message ?: "ошибка"}")
        }
    }

    fun close() {
        active.keys.toList().forEach(::closeAddress)
    }

    @SuppressLint("MissingPermission")
    private fun closeAddress(address: String) {
        val gatt = active.remove(address) ?: return
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
    }

    private fun unregister(receiver: BroadcastReceiver) {
        runCatching { appContext.unregisterReceiver(receiver) }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String? = runCatching { device.name }.getOrNull()

    private fun serviceInfo(service: BluetoothGattService): ServiceInfo = ServiceInfo(
        uuid = service.uuid.toString(),
        name = sigName(service.uuid),
        primary = service.type == BluetoothGattService.SERVICE_TYPE_PRIMARY,
        characteristics = service.characteristics.orEmpty().map(::characteristicInfo)
    )

    private fun characteristicInfo(characteristic: BluetoothGattCharacteristic): CharacteristicInfo = CharacteristicInfo(
        uuid = characteristic.uuid.toString(),
        name = sigName(characteristic.uuid),
        properties = propertyNames(characteristic.properties),
        descriptorCount = characteristic.descriptors?.size ?: 0
    )

    private fun propertyNames(properties: Int): List<String> = buildList {
        if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXTENDED")
    }

    private fun bondStateName(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "сопряжено"
        BluetoothDevice.BOND_BONDING -> "сопряжение выполняется"
        BluetoothDevice.BOND_NONE -> "не сопряжено"
        else -> "неизвестно"
    }

    companion object {
        private val names = mapOf(
            "1800" to "Generic Access",
            "1801" to "Generic Attribute",
            "1802" to "Immediate Alert",
            "1803" to "Link Loss",
            "1804" to "Tx Power",
            "1805" to "Current Time",
            "180a" to "Device Information",
            "180d" to "Heart Rate",
            "180f" to "Battery Service",
            "1812" to "Human Interface Device",
            "1816" to "Cycling Speed and Cadence",
            "1818" to "Cycling Power",
            "181a" to "Environmental Sensing",
            "1826" to "Fitness Machine",
            "1848" to "Media Control",
            "1849" to "Generic Media Control",
            "2a00" to "Device Name",
            "2a01" to "Appearance",
            "2a05" to "Service Changed",
            "2a06" to "Alert Level",
            "2a19" to "Battery Level",
            "2a24" to "Model Number String",
            "2a25" to "Serial Number String",
            "2a26" to "Firmware Revision String",
            "2a27" to "Hardware Revision String",
            "2a28" to "Software Revision String",
            "2a29" to "Manufacturer Name String",
            "2a37" to "Heart Rate Measurement",
            "2a4a" to "HID Information",
            "2a4b" to "Report Map",
            "2a4d" to "Report",
            "2a4e" to "Protocol Mode"
        )

        fun sigName(uuid: UUID): String {
            val text = uuid.toString().lowercase()
            val short = if (text.startsWith("0000") && text.endsWith("-0000-1000-8000-00805f9b34fb")) text.substring(4, 8) else null
            return short?.let(names::get) ?: "Vendor / custom UUID"
        }
    }
}
