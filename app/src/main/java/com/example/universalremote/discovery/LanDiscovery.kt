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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local IPv4 discovery and bounded TCP inspection.
 * Discovery no longer requires an open TCP port: a UDP neighbor touch is used first,
 * then the kernel neighbor table, ICMP reachability and common TCP ports are combined.
 */
class LanDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val generation = AtomicInteger(0)
    private val found = AtomicInteger(0)
    private var executor = Executors.newFixedThreadPool(32)

    fun start(config: ScanConfig = ScanConfig()) {
        stop()
        found.set(0)
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
        val range = resolveRange(local, config.range) ?: return
        if (!Ipv4Range.isPrivate(range)) return onStatus("разрешены только local/private IPv4")
        if (range.size > 1024) return onStatus("диапазон больше 1024 IPv4")

        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
        // Always surface known gateway/DNS nodes; they are useful even when ICMP/TCP is filtered.
        val infrastructure = buildSet {
            props.routes.mapNotNullTo(this) { it.gateway as? Inet4Address }
            props.dnsServers.mapNotNullTo(this) { it as? Inet4Address }
        }.filter { Ipv4Range.ipv4ToInt(it) != own }
        infrastructure.forEach { address ->
            val host = address.hostAddress ?: return@forEach
            emitHost(host, emptyList(), NeighborResolver.macFor(host), "Сетевая инфраструктура")
        }

        executor = Executors.newFixedThreadPool(config.parallelism.coerceIn(4, 64))
        onStatus("${range.label}: ${range.size} адресов, поиск соседей")
        var ip = range.first
        while (ip.toUInt() <= range.last.toUInt()) {
            if (ip != own) {
                val candidate = ip
                executor.execute { inspectHost(candidate, token, config) }
            }
            if (ip == Int.MAX_VALUE) break
            ip++
        }
    }

    fun stop() {
        generation.incrementAndGet()
        executor.shutdownNow()
    }

    private fun inspectHost(candidate: Int, token: Int, config: ScanConfig) {
        if (generation.get() != token || Thread.currentThread().isInterrupted) return
        val address = Ipv4Range.intToIpv4(candidate)
        val host = address.hostAddress ?: return
        val timeout = config.connectTimeoutMs.coerceIn(80, 2000)

        // A connectionless UDP send causes normal ARP/ND neighbor resolution without
        // requiring the remote host to expose a service or answer the datagram.
        touchNeighbor(address)
        if (Thread.currentThread().isInterrupted) return
        runCatching { Thread.sleep(35L) }
        var mac = NeighborResolver.macFor(host)

        val fastPorts = listOf(80, 443, 22, 23, 53, 139, 445, 554, 631, 1883, 3389, 4352, 5000, 8008, 8080, 9100)
        val reachable = mac != null ||
            runCatching { address.isReachable(timeout) }.getOrDefault(false) ||
            fastPorts.any { port -> generation.get() == token && TcpProbe.isOpen(host, port, timeout) }
        if (!reachable || generation.get() != token) return

        val ports: List<PortService> = if (config.scanPorts) {
            config.ports.distinct().filter { it in 1..65535 }.take(64).mapNotNull { port ->
                if (generation.get() != token || Thread.currentThread().isInterrupted) null
                else TcpProbe.inspect(host, port, timeout, config.bannerTimeoutMs.coerceIn(100, 3000))
            }
        } else emptyList()

        if (mac == null) mac = NeighborResolver.macFor(host)
        emitHost(host, ports, mac, if (config.scanPorts) "LAN + TCP scan" else "LAN neighbor/reachability")
        val n = found.incrementAndGet()
        if (n == 1 || n % 10 == 0) onStatus("найдено LAN: $n")
    }

    private fun touchNeighbor(address: InetAddress) {
        runCatching {
            DatagramSocket().use { socket ->
                val one = byteArrayOf(0)
                socket.send(DatagramPacket(one, one.size, address, 9))
            }
        }
    }

    private fun emitHost(host: String, ports: List<PortService>, mac: String?, protocol: String) {
        val vendor = MacVendorResolver.resolve(mac)
        val os = DeviceAnalyzer.inferOs(ports)
        val kind = classify(ports, protocol)
        val hostname = runCatching {
            InetAddress.getByName(host).canonicalHostName.takeIf { it != host && !it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
        }.getOrNull()
        onDevice(
            NearbyDevice(
                id = "lan:$host",
                name = hostname ?: vendor?.let { "$it • $host" } ?: if (protocol == "Сетевая инфраструктура") "Маршрутизатор / DNS • $host" else "Устройство $host",
                kind = kind,
                protocol = protocol,
                address = host,
                brand = vendor,
                ipAddress = host,
                macAddress = mac,
                hardwareVendor = vendor,
                openPorts = ports,
                osHint = os,
                hostname = hostname,
                analysisNote = when {
                    ports.isNotEmpty() -> "Открытых TCP-портов: ${ports.size}"
                    mac != null -> "Обнаружено через таблицу сетевых соседей"
                    else -> "Хост отвечает в локальной сети"
                },
                securityFindings = DeviceAnalyzer.securityFindings(ports)
            )
        )
    }

    private fun classify(ports: List<PortService>, protocol: String): String {
        if (protocol == "Сетевая инфраструктура") return "Маршрутизатор / сеть"
        val p = ports.map { it.port }.toSet()
        return when {
            4352 in p -> "Проектор"
            9100 in p || 631 in p -> "Принтер"
            8008 in p || 8009 in p -> "ТВ / медиаплеер"
            3389 in p || 445 in p || 22 in p -> "Компьютер / сервер"
            554 in p -> "Камера / медиоустройство"
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
        val prefix = local.prefixLength.coerceIn(22, 30)
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        return Ipv4Range.Parsed(network + 1, broadcast - 1, "${Ipv4Range.intToIpv4(network).hostAddress}/$prefix")
    }
}
