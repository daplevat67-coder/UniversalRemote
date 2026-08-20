package com.example.universalremote.discovery

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import com.example.universalremote.model.NearbyDevice
import java.util.concurrent.ConcurrentHashMap

/**
 * Nearby Wi-Fi access-point scan. This discovers radio beacons (SSID/BSSID), not clients behind an AP.
 * Android requires precise-location permission and Location services for scan results.
 */
class WifiDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(WifiManager::class.java)
    private val location = app.getSystemService(LocationManager::class.java)
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            emitLatest(if (Build.VERSION.SDK_INT >= 23) intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) else true)
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        seen.clear()
        if (wifi == null) return onStatus("Wi‑Fi не поддерживается")
        if (Build.VERSION.SDK_INT >= 28 && location?.isLocationEnabled != true) {
            onStatus("включите Геолокацию для Wi‑Fi скана")
            return
        }
        registerReceiver()
        try {
            // Surface the latest cached radio scan immediately, then request a refresh.
            emit(wifi.scanResults.orEmpty())
            val requested = wifi.startScan()
            onStatus(if (requested) "Wi‑Fi скан запущен" else "Wi‑Fi: новый скан ограничен Android, показаны последние результаты")
        } catch (e: SecurityException) {
            onStatus("Wi‑Fi: нужно разрешение точной геолокации")
        } catch (e: Throwable) {
            onStatus("Wi‑Fi: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    private fun registerReceiver() {
        if (registered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            app.registerReceiver(receiver, filter)
        }
        registered = true
    }

    @SuppressLint("MissingPermission")
    private fun emitLatest(updated: Boolean) {
        try {
            emit(wifi?.scanResults.orEmpty())
            if (!updated && seen.isEmpty()) onStatus("Wi‑Fi: свежие результаты недоступны")
        } catch (e: SecurityException) {
            onStatus("Wi‑Fi: нет доступа к результатам")
        }
    }

    @Suppress("DEPRECATION")
    private fun emit(results: List<ScanResult>) {
        results.sortedByDescending { it.level }.forEach { r ->
            val bssid = r.BSSID?.trim().orEmpty()
            if (bssid.isBlank() || bssid == "02:00:00:00:00:00" || !seen.add(bssid)) return@forEach
            val ssid = r.SSID?.trim().orEmpty().ifBlank { "Скрытая Wi‑Fi сеть" }
            val band = when (r.frequency) {
                in 2400..2500 -> "2.4 GHz"
                in 4900..5900 -> "5 GHz"
                in 5925..7125 -> "6 GHz"
                else -> "${r.frequency} MHz"
            }
            onDevice(
                NearbyDevice(
                    id = "wifi:$bssid",
                    name = ssid,
                    kind = "Wi‑Fi точка доступа",
                    protocol = "Wi‑Fi эфир • $band",
                    address = bssid,
                    signalDbm = r.level,
                    analysisNote = "Радиомаяк точки доступа; это не означает, что клиенты этой сети доступны для управления"
                )
            )
        }
        if (seen.isNotEmpty()) onStatus("найдено Wi‑Fi точек: ${seen.size}")
    }
}
