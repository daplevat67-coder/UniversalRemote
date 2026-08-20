package com.example.universalremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice

class NsdDiscovery(context: Context, private val onDevice: (NearbyDevice) -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val serviceTypes = listOf(
        "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.",
        "_androidtvremote2._tcp.", "_hap._tcp.", "_matter._tcp.",
        "_matterc._udp.", "_matterd._udp.", "_ipp._tcp.", "_spotify-connect._tcp.",
        "_sonos._tcp.", "_philipshue._tcp.", "_hue._tcp.", "_wled._tcp.", "_roku._tcp.",
        "_bose._tcp.", "_smb._tcp.", "_workstation._tcp.", "_ssh._tcp."
    )

    fun start() = serviceTypes.forEach { type ->
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) = Unit
            override fun onStopDiscoveryFailed(t: String, e: Int) = Unit
            override fun onServiceLost(s: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                manager.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) = Unit
                    @Suppress("DEPRECATION")
                    override fun onServiceResolved(s: NsdServiceInfo) {
                        val host = s.host?.hostAddress ?: return
                        val info = classify(s.serviceType, s.serviceName)
                        val attrs = runCatching { s.attributes }.getOrNull().orEmpty()
                        val location = attrs["location"]?.toString(Charsets.UTF_8)?.takeIf { it.startsWith("http") }
                        val typeLower = s.serviceType.lowercase()
                        val sonos = "sonos" in typeLower
                        val androidTv = "androidtvremote2" in typeLower
                        val roku = "roku" in typeLower
                        val wled = "wled" in typeLower
                        val protocol = when {
                            androidTv -> "Android TV Remote Service v2"
                            roku -> "Roku ECP"
                            wled -> "WLED JSON API"
                            sonos && location != null -> "UPnP MediaRenderer / Sonos mDNS"
                            else -> "mDNS ${s.serviceType}"
                        }
                        onDevice(
                            NearbyDevice(
                                id = "mdns:${s.serviceType}:${s.serviceName}:$host",
                                name = s.serviceName.ifBlank { info.first },
                                kind = info.first,
                                protocol = protocol,
                                address = "$host:${s.port}",
                                controllable = androidTv || roku || wled || (sonos && location != null),
                                brand = info.second,
                                capabilities = info.third,
                                descriptionUrl = location,
                                ipAddress = host
                            )
                        )
                    }
                })
            }
        }
        listeners += listener
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
    }

    fun stop() {
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
    }

    private fun classify(type: String, name: String): Triple<String, String?, Set<ControlCapability>> {
        val t = type.lowercase()
        val n = name.lowercase()
        return when {
            "androidtv" in t -> Triple("Телевизор / Android TV", "Android TV", setOf(ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.INPUT))
            "googlecast" in t -> Triple("ТВ / медиаплеер", "Google Cast", setOf(ControlCapability.VOLUME, ControlCapability.MEDIA))
            "airplay" in t || "raop" in t -> Triple("ТВ / медиаплеер", "Apple AirPlay", setOf(ControlCapability.VOLUME, ControlCapability.MEDIA))
            "spotify" in t -> Triple("Колонка / медиаплеер", "Spotify Connect", setOf(ControlCapability.VOLUME, ControlCapability.MEDIA))
            "sonos" in t -> Triple("Колонка / медиаплеер", "Sonos", setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA))
            "bose" in t -> Triple("Колонка / медиаплеер", "Bose", emptySet())
            "roku" in t -> Triple("Телевизор / медиаплеер", "Roku", setOf(ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.CHANNEL, ControlCapability.INPUT))
            "wled" in t -> Triple("Лампа / свет", "WLED", setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR))
            "philipshue" in t || "_hue" in t -> Triple("Лампа / свет", "Philips Hue", setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR))
            "smb" in t || "workstation" in t || "ssh" in t -> Triple("Компьютер", null, emptySet())
            "matter" in t || "hap" in t -> {
                val kind = if (listOf("light", "lamp", "bulb", "hue").any { it in n }) "Лампа / свет" else "Умный дом"
                Triple(kind, if ("hap" in t) "HomeKit" else "Matter", setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR))
            }
            "ipp" in t -> Triple("Принтер", null, emptySet())
            else -> Triple("Сетевое устройство", null, emptySet())
        }
    }
}
