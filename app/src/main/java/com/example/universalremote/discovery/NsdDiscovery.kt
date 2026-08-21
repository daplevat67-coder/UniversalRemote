package com.example.universalremote.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import com.example.universalremote.model.ControlCapability
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.network.LocalEndpointPolicy
import java.util.ArrayDeque

/** mDNS discovery with staggered service starts and a serialized resolve queue. */
class NsdDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onDiagnostic: (String) -> Unit = {}
) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<NsdManager.DiscoveryListener>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var stopped = true
    private var generation = 0

    private val serviceTypes = listOf(
        "_uremote._tcp.", "_googlecast._tcp.", "_airplay._tcp.", "_raop._tcp.",
        "_androidtvremote2._tcp.", "_webos._tcp.", "_lge-app-remote._tcp.",
        "_companion-link._tcp.", "_apple-mobdev2._tcp.", "_device-info._tcp.",
        "_adb-tls-connect._tcp.", "_adb-tls-pairing._tcp.",
        "_hap._tcp.", "_matter._tcp.", "_matterc._udp.", "_matterd._udp.",
        "_ipp._tcp.", "_spotify-connect._tcp.", "_sonos._tcp.",
        "_philipshue._tcp.", "_hue._tcp.", "_wled._tcp.", "_roku._tcp.",
        "_bose._tcp.", "_smb._tcp.", "_workstation._tcp.", "_ssh._tcp."
    )

    fun start() {
        stop()
        stopped = false
        val run = ++generation
        serviceTypes.forEachIndexed { index, type ->
            handler.postDelayed({
                if (!stopped && generation == run) startType(type)
            }, index * START_STAGGER_MS)
        }
    }

    private fun startType(type: String) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) = Unit
            override fun onDiscoveryStopped(t: String) = Unit
            override fun onStartDiscoveryFailed(t: String, e: Int) {
                onDiagnostic("mDNS $t: start error $e")
                runCatching { manager.stopServiceDiscovery(this) }
            }
            override fun onStopDiscoveryFailed(t: String, e: Int) {
                onDiagnostic("mDNS $t: stop error $e")
            }
            override fun onServiceLost(s: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) = enqueueResolve(service)
        }
        listeners += listener
        runCatching { manager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { onDiagnostic("mDNS $type: ${it.javaClass.simpleName}") }
    }

    @Synchronized
    private fun enqueueResolve(service: NsdServiceInfo) {
        if (stopped) return
        if (resolveQueue.size >= 128) resolveQueue.removeFirst()
        resolveQueue.addLast(service)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        if (stopped || resolving || resolveQueue.isEmpty()) return
        val service = resolveQueue.removeFirst()
        resolving = true
        @Suppress("DEPRECATION")
        runCatching {
            manager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                    onDiagnostic("mDNS resolve ${s.serviceName}: $e")
                    finishResolve()
                }
                @Suppress("DEPRECATION")
                override fun onServiceResolved(s: NsdServiceInfo) {
                    runCatching { handleResolved(s) }
                        .onFailure { onDiagnostic("mDNS ${s.serviceName}: ${it.javaClass.simpleName}") }
                    finishResolve()
                }
            })
        }.onFailure {
            onDiagnostic("mDNS resolve start: ${it.javaClass.simpleName}")
            finishResolve()
        }
    }

    @Synchronized
    private fun finishResolve() {
        resolving = false
        resolveNext()
    }

    private fun handleResolved(s: NsdServiceInfo) {
        val host = s.host?.hostAddress ?: return
        if (!LocalEndpointPolicy.isPrivateIpv4(host)) return
        val info = classify(s.serviceType, s.serviceName)
        val attrs = runCatching { s.attributes }.getOrNull().orEmpty()
        val advertisedLocation = attrs["location"]?.toString(Charsets.UTF_8)?.takeIf { it.startsWith("http", true) }
        val location = advertisedLocation?.takeIf {
            LocalEndpointPolicy.samePrivateHost(host, it, setOf("http", "https"))
        }
        if (advertisedLocation != null && location == null) onDiagnostic("mDNS ${s.serviceName}: внешний location отклонён")

        val typeLower = s.serviceType.lowercase()
        val nameLower = s.serviceName.lowercase()
        val companion = "_uremote" in typeLower
        val companionPlatform = attrs["platform"]?.toString(Charsets.UTF_8)?.lowercase().orEmpty()
        val iosCompanion = companion && companionPlatform == "ios"
        val sonos = "sonos" in typeLower
        val androidTv = "androidtvremote2" in typeLower
        val androidPhone = "adb-tls-connect" in typeLower || "adb-tls-pairing" in typeLower
        val appleCompanion = "companion-link" in typeLower || "apple-mobdev2" in typeLower || "device-info" in typeLower
        val appleMobileByName = listOf("iphone", "ipad", "ipod").any { it in nameLower }
        val roku = "roku" in typeLower
        val wled = "wled" in typeLower
        val cast = "googlecast" in typeLower
        val hue = "philipshue" in typeLower || "_hue" in typeLower || "hue bridge" in nameLower
        val webos = "webos" in typeLower || "lge-app-remote" in typeLower || ("lg" in nameLower && "tv" in nameLower)
        val companionId = attrs["id"]?.toString(Charsets.UTF_8)?.take(80)
        val protocol = when {
            iosCompanion -> "UniversalRemote Companion v2 • iOS/iPadOS"
            companion -> "UniversalRemote Companion v2 • Android"
            androidTv -> "Android TV Remote Service v2"
            androidPhone -> "Android mDNS / ADB TLS advertisement"
            appleCompanion -> "Apple Bonjour / Mobile Device service"
            roku -> "Roku ECP"
            wled -> "WLED JSON API"
            cast -> "Google Cast v2"
            hue -> "Philips Hue Bridge API v2"
            webos -> "LG webOS SSAP"
            sonos && location != null -> "UPnP MediaRenderer / Sonos mDNS"
            else -> "mDNS ${s.serviceType}"
        }
        onDevice(
            NearbyDevice(
                id = "mdns:${s.serviceType}:${s.serviceName}:$host",
                name = s.serviceName.ifBlank { info.first },
                kind = when {
                    iosCompanion -> "iPhone / iPad (iOS Companion)"
                    appleCompanion || appleMobileByName -> if ("ipad" in nameLower) "iPad / Apple устройство" else if ("iphone" in nameLower) "iPhone / Apple устройство" else "iPhone / iPad / Apple устройство"
                    else -> info.first
                },
                protocol = protocol,
                address = "$host:${s.port}",
                controllable = companion || androidTv || roku || wled || cast || hue || webos || (sonos && location != null),
                brand = when { iosCompanion -> "Apple / UniversalRemote iOS Companion"; appleCompanion || appleMobileByName -> "Apple"; else -> info.second },
                capabilities = when { iosCompanion -> setOf(ControlCapability.FIND_DEVICE); else -> info.third },
                descriptionUrl = location,
                ipAddress = host,
                companionId = companionId
            )
        )
    }

    fun stop() {
        stopped = true
        generation++
        handler.removeCallbacksAndMessages(null)
        listeners.forEach { runCatching { manager.stopServiceDiscovery(it) } }
        listeners.clear()
        synchronized(this) {
            resolveQueue.clear()
            resolving = false
        }
    }

    private fun classify(type: String, name: String): Triple<String, String?, Set<ControlCapability>> {
        val t = type.lowercase()
        val n = name.lowercase()
        return when {
            "_uremote" in t -> Triple("Телефон / планшет (Companion)", "UniversalRemote Companion", emptySet())
            "adb-tls" in t -> Triple("Телефон / планшет (Android)", "Android", emptySet())
            "companion-link" in t || "apple-mobdev2" in t || "device-info" in t || "iphone" in n || "ipad" in n -> Triple("iPhone / iPad / Apple устройство", "Apple", emptySet())
            "androidtv" in t -> Triple("Телевизор / Android TV", "Android TV", tvCaps())
            "webos" in t || "lge-app-remote" in t -> Triple("Телевизор / LG webOS", "LG", tvCaps())
            "googlecast" in t -> Triple("ТВ / медиаплеер", "Google Cast", setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA))
            "airplay" in t || "raop" in t -> Triple("ТВ / медиаплеер", "Apple AirPlay", setOf(ControlCapability.VOLUME, ControlCapability.MEDIA))
            "spotify" in t -> Triple("Колонка / медиаплеер", "Spotify Connect", setOf(ControlCapability.VOLUME, ControlCapability.MEDIA))
            "sonos" in t -> Triple("Колонка / медиаплеер", "Sonos", setOf(ControlCapability.VOLUME, ControlCapability.MUTE, ControlCapability.MEDIA))
            "bose" in t -> Triple("Колонка / медиаплеер", "Bose", emptySet())
            "roku" in t -> Triple("Телевизор / медиаплеер", "Roku", tvCaps() + setOf(ControlCapability.CHANNEL))
            "wled" in t -> Triple("Лампа / свет", "WLED", lightCaps())
            "philipshue" in t || "_hue" in t || "hue bridge" in n -> Triple("Лампа / свет", "Philips Hue", lightCaps())
            "smb" in t || "workstation" in t || "ssh" in t -> Triple("Компьютер", null, emptySet())
            "matter" in t || "hap" in t -> {
                val kind = if (listOf("light", "lamp", "bulb", "hue").any { it in n }) "Лампа / свет" else "Умный дом"
                Triple(kind, if ("hap" in t) "HomeKit" else "Matter", lightCaps())
            }
            "ipp" in t -> Triple("Принтер", null, emptySet())
            else -> Triple("Сетевое устройство", null, emptySet())
        }
    }

    private fun tvCaps() = setOf(
        ControlCapability.POWER, ControlCapability.VOLUME, ControlCapability.MUTE,
        ControlCapability.MEDIA, ControlCapability.NAVIGATION, ControlCapability.INPUT
    )

    private fun lightCaps() = setOf(ControlCapability.LIGHT_POWER, ControlCapability.BRIGHTNESS, ControlCapability.COLOR)

    companion object {
        private const val START_STAGGER_MS = 120L
    }
}
