package com.example.universalremote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.PortService
import com.example.universalremote.model.ScanConfig
import com.example.universalremote.network.DeviceAnalyzer
import com.example.universalremote.network.Ipv4Range
import com.example.universalremote.network.MacVendorResolver
import com.example.universalremote.network.NeighborResolver
import com.example.universalremote.network.TcpProbe
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two-phase local IPv4 discovery.
 * Phase 1 touches neighbors with one UDP socket and snapshots the kernel neighbor table once.
 * Phase 2 performs only a small protocol-gate probe; the expensive 64-port analysis is deferred
 * until the user opens a device card.
 */
class LanDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val generation = AtomicInteger(0)
    private val found = AtomicInteger(0)
    private val emitted = ConcurrentHashMap.newKeySet<String>()
    private var executor = Executors.newFixedThreadPool(24)

    fun start(config: ScanConfig = ScanConfig()) {
        stop()
        found.set(0)
        emitted.clear()
        val token = generation.incrementAndGet()
        val network = connectivity.allNetworks.firstOrNull { n ->
            connectivity.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: connectivity.activeNetwork ?: return onStatus("нет активной сети")
        val caps = connectivity.getNetworkCapabilities(network)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
            return onStatus("Wi‑Fi не подключён — LAN-скан пропущен")
        }
        val props = connectivity.getLinkProperties(network) ?: return onStatus("нет параметров IPv4")
        val local = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return onStatus("IPv4 не найден")
        val fullRange = resolveRange(local, config.range) ?: return
        if (!Ipv4Range.isPrivate(fullRange)) return onStatus("разрешены только local/private IPv4")
        if (fullRange.size > MAX_CONFIGURED_RANGE) return onStatus("диапазон слишком большой; максимум $MAX_CONFIGURED_RANGE IPv4")

        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
        val activeRange = boundedActiveRange(fullRange, own, MAX_ACTIVE_ADDRESSES)

        // Gateway/DNS are emitted after the single post-sweep neighbor snapshot below.
        val infrastructure = buildSet {
            props.routes.mapNotNullTo(this) { it.gateway as? Inet4Address }
            props.dnsServers.mapNotNullTo(this) { it as? Inet4Address }
        }.filter { Ipv4Range.ipv4ToInt(it) != own }

        executor = Executors.newFixedThreadPool(config.parallelism.coerceIn(8, 48))
        val scopeNote = if (activeRange.first == fullRange.first && activeRange.last == fullRange.last) {
            "${fullRange.label}: ${fullRange.size} адресов"
        } else {
            "${fullRange.label}: ${fullRange.size} адресов; активные пробы ограничены ${activeRange.size}, neighbor-table проверяется по всей подсети"
        }
        onStatus("$scopeNote • фаза 1: соседи")

        executor.execute {
            if (generation.get() != token) return@execute
            touchRange(activeRange, own, token)
            runCatching { Thread.sleep(220L) }
            if (generation.get() != token) return@execute

            // One snapshot for the whole scan: fixes the former ~1024 `ip neigh` process launches.
            val neighborSnapshot = NeighborResolver.snapshot()
            val neighbors = neighborSnapshot.filterKeys { host ->
                val value = Ipv4Range.parseIp(host) ?: return@filterKeys false
                inRange(value, fullRange) && value != own
            }
            infrastructure.forEach { address ->
                val host = address.hostAddress ?: return@forEach
                emitHost(host, emptyList(), neighborSnapshot[host], "Сетевая инфраструктура")
            }
            neighbors.forEach { (host, mac) ->
                emitHost(host, emptyList(), mac, "LAN neighbor sweep")
                executor.execute { inspectKnownHost(host, mac, token, config) }
            }

            onStatus("соседей: ${neighbors.size} • фаза 2: быстрые API/службы")
            var ip = activeRange.first
            while (ip.toUInt() <= activeRange.last.toUInt() && generation.get() == token) {
                if (ip != own) {
                    val host = Ipv4Range.intToIpv4(ip).hostAddress
                    if (host != null && host !in neighbors) executor.execute { probeFallback(host, token, config) }
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

    private fun inspectKnownHost(host: String, mac: String?, token: Int, config: ScanConfig) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) return
        val timeout = config.connectTimeoutMs.coerceIn(80, 700)
        val ports = CONTROL_DISCOVERY_PORTS.mapNotNull { port ->
            if (generation.get() != token || Thread.currentThread().isInterrupted) null
            else if (TcpProbe.isOpen(host, port, timeout)) PortService(port, TcpProbe.serviceName(port)) else null
        }
        emitHost(host, ports, mac, "LAN neighbor + быстрые управляющие порты")
    }

    private fun probeFallback(host: String, token: Int, config: ScanConfig) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) return
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return
        val timeout = config.connectTimeoutMs.coerceIn(80, 500)
        val reachable = runCatching { address.isReachable(timeout) }.getOrDefault(false)
        var firstPort: Int? = null
        if (!reachable) {
            for (port in FAST_GATE_PORTS) {
                if (generation.get() != token || Thread.currentThread().isInterrupted) return
                if (TcpProbe.isOpen(host, port, timeout)) { firstPort = port; break }
            }
        }
        if (!reachable && firstPort == null) return
        val ports = firstPort?.let { listOf(PortService(it, TcpProbe.serviceName(it))) }.orEmpty()
        emitHost(host, ports, null, if (reachable) "LAN reachability" else "LAN protocol gate")
    }

    private fun touchRange(range: Ipv4Range.Parsed, own: Int, token: Int) {
        runCatching {
            DatagramSocket().use { socket ->
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
                name = vendor?.let { "$it • $host" } ?: if (protocol == "Сетевая инфраструктура") "Маршрутизатор / DNS • $host" else "Устройство $host",
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
                    mac != null -> "Обнаружено через общую таблицу сетевых соседей; полный port scan не выполнялся"
                    else -> "Хост отвечает в локальной сети; подробный анализ отложен до открытия карточки"
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

    private fun resolveRange(local: LinkAddress, configured: String): Ipv4Range.Parsed? {
        val value = configured.trim()
        if (value.isNotBlank() && !value.equals("auto", true)) {
            val parsed = Ipv4Range.parse(value)
            if (parsed == null) onStatus("диапазон: используйте CIDR или IP-IP")
            return parsed
        }
        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
        val prefix = local.prefixLength.coerceIn(8, 30)
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
        private val CONTROL_DISCOVERY_PORTS = listOf(45123, 6466, 6467, 8060, 8002, 8001, 3001, 3000, 8009, 8008, 55443, 4352)
        // Crucially includes every supported remote endpoint before a host can be discarded.
        private val FAST_GATE_PORTS = CONTROL_DISCOVERY_PORTS + listOf(80, 443, 22, 445, 554, 631, 3389, 9100, 1883)
        private val MOBILE_VENDOR_HINTS = setOf("Apple", "Google", "Xiaomi", "Huawei")
    }
}
