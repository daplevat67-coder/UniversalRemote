package com.example.universalremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.universalremote.model.NearbyDevice

class NsdDiscovery(context: Context, private val onDevice: (NearbyDevice) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val serviceTypes = listOf(
        "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.",
        "_androidtvremote2._tcp.", "_hap._tcp.", "_matter._tcp.", "_ipp._tcp."
    )

    fun start() = serviceTypes.forEach { type ->
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = Unit
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
            override fun onServiceLost(s: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) = Unit
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress ?: return
                        onDevice(NearbyDevice(
                            "mdns:${s.serviceType}:${s.serviceName}", s.serviceName,
                            classify(s.serviceType), "mDNS ${s.serviceType}", "$host:${s.port}",
                            s.serviceType.contains("androidtvremote2")
                        ))
                    }
                })
            }
        }
        listeners += listener
        manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() { listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }; listeners.clear() }

    private fun classify(type: String) = when {
        type.contains("cast") || type.contains("airplay") || type.contains("androidtv") -> "ТВ / проектор"
        type.contains("matter") || type.contains("hap") -> "Умный дом"
        type.contains("ipp") -> "Принтер"
        else -> "Сетевое устройство"
    }
}
