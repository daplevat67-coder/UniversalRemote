package com.example.universalremote.discovery

import android.content.Context
import android.net.Network
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.PortService
import com.example.universalremote.model.ScanConfig
import com.example.universalremote.network.DeviceAnalyzer
import com.example.universalremote.network.Ipv4Range
import com.example.universalremote.network.MacVendorResolver
import com.example.universalremote.network.NeighborResolver
import com.example.universalremote.network.TcpProbe
import com.example.universalremote.network.WifiNetworkResolver
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local IPv4 discovery that stays on the physical Wi-Fi network even when Android's default route
 * is a VPN. Modern Android often hides ARP/neighbor details from ordinary apps, so discovery uses
 * three complementary signals:
 *  1) gateway/DNS from LinkProperties,
 *  2) one neighbor-table snapshot after a UDP touch sweep,
 *  3) short TCP presence probes where ECONNREFUSED also counts as proof that the host is alive.
 */
class LanDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val app = context.applicationContext
    private val generation = AtomicInteger(0)
    private val found = AtomicInteger(0)
    private val emitted = ConcurrentHashMap.newKeySet<String>()
    private var executor = Executors.newFixedThreadPool(32)

    fun start(config: ScanConfig = ScanConfig()) {
        stop()
        found.set(0)
        emitted.clear()
        val token = generation.incrementAndGet()

        val lan = WifiNetworkResolver.lanInfo(app) ?: return onStatus("LAN: локальная IPv4 сеть не найдена")
        val network = lan.network
        val local = lan.ipv4
        val fullRange = resolveRange(local, lan.prefixLength, config.range) ?: return
        if (!Ipv4Range.isPrivate(fullRange)) return onStatus("разрешены только local/private IPv4")
        if (fullRange.size > MAX_CONFIGURED_RANGE) return onStatus("диапазон слишком большой; максимум $MAX_CONFIGURED_RANGE IPv4")

        val own = Ipv4Range.ipv4ToInt(local)
        val activeRange = boundedActiveRange(fullRange, own, MAX_ACTIVE_ADDRESSES)
        val infrastructure = (lan.gateways + lan.dnsServers)
            .distinct()
            .filter { Ipv4Range.ipv4ToInt(it) != own }

        executor = Executors.newFixedThreadPool(config.parallelism.coerceIn(12, 48))
        val scopeNote = if (activeRange.first == fullRange.first && activeRange.last == fullRange.last) {
            "${fullRange.label}: ${fullRange.size} адресов"
        } else {
            "${fullRange.label}: ${fullRange.size} адресов; активное окно ${activeRange.size}"
        }

        // Emit infrastructure immediately. In v0.9.2 this happened only after the sweep and made the
        // UI look completely dead when Android/VPN prevented neighbor-table access.
        infrastructure.forEach { address ->
            val host = address.hostAddress ?: return@forEach
            emitHost(host, emptyList(), null, "Сетевая инфраструктура")
        }
        onStatus("$scopeNote • ${lan.label} • быстрый LAN-поиск")

        executor.execute {
            if (generation.get() != token) return@execute
            touchRange(network, activeRange, own, token)
            runCatching { Thread.sleep(180L) }
            if (generation.get() != token) return@execute

            val neighborSnapshot = NeighborResolver.snapshot()
            val neighbors = neighborSnapshot.filterKeys { host ->
                val value = Ipv4Range.parseIp(host) ?: return@filterKeys false
                inRange(value, fullRange) && value != own
            }
            neighbors.forEach { (host, mac) ->
                emitHost(host, emptyList(), mac, "LAN neighbor sweep")
                executor.execute { inspectKnownHost(network, host, mac, token, config) }
            }

            onStatus("neighbor: ${neighbors.size} • TCP presence scan")
            var ip = activeRange.first
            while (ip.toUInt() <= activeRange.last.toUInt() && generation.get() == token) {
                if (ip != own) {
                    val host = Ipv4Range.intToIpv4(ip).hostAddress
                    if (host != null && host !in neighbors) {
                        executor.execute { probeFallback(network, host, token, config) }
                    }
                }
                if (ip == Int.MAX_VALUE) break
                ip++
            }
        }
    }

    fun stop() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun inspectKnownHost(network: Network?, host: String, mac: String?, token: Int, config: ScanConfig) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) return
        val timeout = config.connectTimeoutMs.coerceIn(80, 500)
        val ports = CONTROL_DISCOVERY_PORTS.mapNotNull { port ->
            if (generation.get() != token || Thread.currentThread().isInterrupted) null
            else if (TcpProbe.isOpen(network, host, port, timeout)) PortService(port, TcpProbe.serviceName(port)) else null
        }
        emitHost(host, ports, mac, "LAN neighbor + управляющие порты")
    }

    /**
     * Active fallback for Android versions where ARP/neighbor data is inaccessible to normal apps.
     * A successful TCP connect is obvious evidence. A quick ECONNREFUSED is also evidence because
     * only a live IP can actively reject our SYN. Timeouts/unreachable errors are not emitted.
     */
    private fun probeFallback(network: Network?, host: String, token: Int, config: ScanConfig) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) return
        val quickTimeout = config.connectTimeoutMs.coerceIn(180, 350)
        var alive = false
        var firstOpen: Int? = null

        for (port in PRESENCE_PORTS) {
            if (generation.get() != token || Thread.currentThread().isInterrupted) return
            when (TcpProbe.presence(network, host, port, quickTimeout)) {
                TcpProbe.Presence.OPEN -> {
                    alive = true
                    firstOpen = port
                    break
                }
                TcpProbe.Presence.REFUSED -> {
                    alive = true
                    break
                }
                TcpProbe.Presence.NO_EVIDENCE -> Unit
            }
        }
        if (!alive) return

        val seedPorts = firstOpen?.let { listOf(PortService(it, TcpProbe.serviceName(it))) }.orEmpty()
        emitHost(host, seedPorts, null, if (firstOpen != null) "LAN TCP service" else "LAN TCP presence")

        // Only hosts that already proved they are alive get the more expensive protocol check.
        inspectKnownHost(network, host, null, token, config.copy(connectTimeoutMs = quickTimeout.coerceAtLeast(100)))
    }

    private fun touchRange(network: Network?, range: Ipv4Range.Parsed, own: Int, token: Int) {
        runCatching {
            DatagramSocket().use { socket ->
                if (network != null) runCatching { network.bindSocket(socket) }
                val one = byteArrayOf(0)
                var ip = range.first
                while (ip.toUInt() <= range.last.toUInt() && generation.get() == token && !Thread.currentThread().isInterrupted) {
                    if (ip != own) {
                        val address = Ipv4Range.intToIpv4(ip)
                        runCatching { socket.send(DatagramPacket(one, one.size, address, 9)) }
                    }
                    if (ip == Int.MAX_VALUE) break
                    ip++
                }
            }
        }
    }

    private fun emitHost(host: String, ports: List<PortService>, mac: String?, protocol: String) {
        val vendor = MacVendorResolver.resolve(mac)
        val os = DeviceAnalyzer.inferOs(ports)
        val kind = classify(ports, protocol, vendor)
        onDevice(
            NearbyDevice(
                id = "lan:$host",
                name = vendor?.let { "$it • $host" }
                    ?: if (protocol == "Сетевая инфраструктура") "Маршрутизатор / DNS • $host" else "Устройство $host",
                kind = kind,
                protocol = protocol,
                address = host,
                brand = vendor,
                ipAddress = host,
                macAddress = mac,
                hardwareVendor = vendor,
                openPorts = ports,
                osHint = os,
                analysisNote = when {
                    ports.isNotEmpty() -> "Быстрый discovery: найдено ${ports.size} характерных TCP-служб; полный анализ запускается из карточки"
                    mac != null -> "Обнаружено через таблицу сетевых соседей; полный port scan не выполнялся"
                    protocol == "LAN TCP presence" -> "Хост активно отклонил TCP-подключение: IP подтверждён даже без открытого управляющего порта"
                    else -> "Хост подтверждён в локальной Wi-Fi сети; подробный анализ отложен до открытия карточки"
                },
                securityFindings = DeviceAnalyzer.securityFindings(ports)
            )
        )
        if (emitted.add(host)) {
            val n = found.incrementAndGet()
            if (n == 1 || n % 10 == 0) onStatus("найдено LAN: $n")
        }
    }

    private fun classify(ports: List<PortService>, protocol: String, vendor: String?): String {
        if (protocol == "Сетевая инфраструктура") return "Маршрутизатор / сеть"
        val p = ports.map { it.port }.toSet()
        return when {
            45123 in p -> "Телефон / планшет (Companion)"
            62078 in p && vendor == "Apple" -> "iPhone / iPad / Apple устройство"
            6466 in p || 6467 in p -> "Телевизор / Android TV"
            3000 in p || 3001 in p || 8001 in p || 8002 in p || 8009 in p || 8060 in p -> "Телевизор / медиаплеер"
            4352 in p -> "Проектор"
            55443 in p -> "Лампа / свет"
            9100 in p || 631 in p -> "Принтер"
            3389 in p || 445 in p || 22 in p -> "Компьютер / сервер"
            554 in p -> "Камера / медиоустройство"
            ports.isEmpty() && vendor in MOBILE_VENDOR_HINTS -> "Телефон / планшет (вероятно)"
            else -> "Сетевое устройство"
        }
    }

    private fun resolveRange(local: Inet4Address, prefixLength: Int, configured: String): Ipv4Range.Parsed? {
        val value = configured.trim()
        if (value.isNotBlank() && !value.equals("auto", true)) {
            val parsed = Ipv4Range.parse(value)
            if (parsed == null) onStatus("диапазон: используйте CIDR или IP-IP")
            return parsed
        }
        val own = Ipv4Range.ipv4ToInt(local)
        val prefix = prefixLength.coerceIn(8, 30)
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        return Ipv4Range.Parsed(network + 1, broadcast - 1, "${Ipv4Range.intToIpv4(network).hostAddress}/$prefix")
    }

    private fun boundedActiveRange(full: Ipv4Range.Parsed, own: Int, max: Int): Ipv4Range.Parsed {
        if (full.size <= max) return full
        val fullFirst = full.first.toUInt().toLong()
        val fullLast = full.last.toUInt().toLong()
        val ownU = own.toUInt().toLong()
        var first = (ownU - max / 2).coerceAtLeast(fullFirst)
        var last = (first + max - 1).coerceAtMost(fullLast)
        first = (last - max + 1).coerceAtLeast(fullFirst)
        return Ipv4Range.Parsed(first.toUInt().toInt(), last.toUInt().toInt(), "${full.label} active-window")
    }

    private fun inRange(value: Int, range: Ipv4Range.Parsed): Boolean =
        value.toUInt() >= range.first.toUInt() && value.toUInt() <= range.last.toUInt()

    companion object {
        private const val MAX_ACTIVE_ADDRESSES = 1024
        private const val MAX_CONFIGURED_RANGE = 65534
        private val CONTROL_DISCOVERY_PORTS = listOf(
            45123, 62078, 6466, 6467, 8060, 8002, 8001, 3001, 3000, 8009, 8008,
            55443, 4352, 80, 443, 22, 445, 554, 631, 3389, 9100, 1883
        )
        // Representative ports: closed ports often return ECONNREFUSED immediately and prove liveness.
        private val PRESENCE_PORTS = listOf(45123, 8009, 8060, 8002, 6466, 80, 443, 22)
        private val MOBILE_VENDOR_HINTS = setOf("Apple", "Google", "Xiaomi", "Huawei", "Samsung")
    }
}
