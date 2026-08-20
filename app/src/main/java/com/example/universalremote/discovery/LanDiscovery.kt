package com.example.universalremote.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.PortService
import com.example.universalremote.model.ScanConfig
import com.example.universalremote.network.DeviceAnalyzer
import com.example.universalremote.network.Ipv4Range
import com.example.universalremote.network.MacVendorResolver
import com.example.universalremote.network.NeighborResolver
import com.example.universalremote.network.TcpProbe
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local IPv4 discovery and bounded TCP inspection.
 * Custom targets are accepted only for private/link-local IPv4 and are capped at 1024 addresses.
 */
class LanDiscovery(
    context: Context,
    private val onDevice: (NearbyDevice) -> Unit,
    private val onStatus: (String) -> Unit = {}
) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val generation = AtomicInteger(0)
    private var executor = Executors.newFixedThreadPool(32)

    fun start(config: ScanConfig = ScanConfig()) {
        stop()
        val token = generation.incrementAndGet()
        val network = connectivity.activeNetwork ?: return onStatus("Нет активной сети")
        val props = connectivity.getLinkProperties(network) ?: return onStatus("Нет параметров IPv4")
        val local = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return onStatus("IPv4 не найден")
        val range = resolveRange(local, config.range) ?: return
        if (!Ipv4Range.isPrivate(range)) return onStatus("Разрешены только локальные/private IPv4 диапазоны")
        if (range.size > 1024) return onStatus("Диапазон слишком большой: максимум 1024 IPv4")

        executor = Executors.newFixedThreadPool(config.parallelism.coerceIn(4, 64))
        onStatus("LAN ${range.label}: ${range.size} адресов")
        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
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
        val fastPorts = listOf(80, 443, 22, 445, 631, 3389, 4352, 8008, 9100)
        val reachable = runCatching { address.isReachable(timeout) }.getOrDefault(false) ||
            fastPorts.any { port -> generation.get() == token && TcpProbe.isOpen(host, port, timeout) }
        if (!reachable || generation.get() != token) return

        val ports: List<PortService> = if (config.scanPorts) {
            config.ports.distinct().filter { it in 1..65535 }.take(64).mapNotNull { port ->
                if (generation.get() != token || Thread.currentThread().isInterrupted) null
                else TcpProbe.inspect(host, port, timeout, config.bannerTimeoutMs.coerceIn(100, 3000))
            }
        } else emptyList()

        // Connecting to a host usually refreshes the neighbor table, so resolve MAC afterwards.
        val mac = NeighborResolver.macFor(host)
        val vendor = MacVendorResolver.resolve(mac)
        val os = DeviceAnalyzer.inferOs(ports)
        val kind = classify(ports)
        val hostname = runCatching {
            address.canonicalHostName.takeIf { it != host && !it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
        }.getOrNull()
        onDevice(
            NearbyDevice(
                id = "lan:$host",
                name = hostname ?: vendor?.let { "$it • $host" } ?: "Устройство $host",
                kind = kind,
                protocol = if (config.scanPorts) "LAN + TCP scan" else "LAN reachability",
                address = host,
                brand = vendor,
                ipAddress = host,
                macAddress = mac,
                hardwareVendor = vendor,
                openPorts = ports,
                osHint = os,
                hostname = hostname,
                analysisNote = if (config.scanPorts) "Проверено ${config.ports.distinct().take(64).size} TCP-портов" else "Обнаружен активный IPv4-хост",
                securityFindings = DeviceAnalyzer.securityFindings(ports)
            )
        )
    }

    private fun classify(ports: List<PortService>): String {
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
            if (parsed == null) onStatus("Диапазон: используйте CIDR или IP-IP")
            return parsed
        }
        val own = Ipv4Range.ipv4ToInt(local.address as Inet4Address)
        // Scan the real subnet while bounded. Networks larger than /22 are represented by local /22 window.
        val prefix = local.prefixLength.coerceIn(22, 30)
        val mask = (-1 shl (32 - prefix))
        val network = own and mask
        val broadcast = network or mask.inv()
        return Ipv4Range.Parsed(network + 1, broadcast - 1, "${Ipv4Range.intToIpv4(network).hostAddress}/$prefix")
    }
}
