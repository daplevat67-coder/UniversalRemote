package com.example.universalremote.network

import android.content.Context
import com.example.universalremote.model.NearbyDevice
import com.example.universalremote.model.PortService
import com.example.universalremote.model.ScanConfig
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class DeviceAnalyzer(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newFixedThreadPool(3)

    fun analyze(device: NearbyDevice, config: ScanConfig, callback: (NearbyDevice) -> Unit) {
        val host = device.ipAddress ?: hostFrom(device.address) ?: return callback(device.copy(analysisNote = "IPv4-адрес не определён"))
        if (!LocalEndpointPolicy.isInCurrentWifiSubnet(appContext, host)) {
            return callback(device.copy(analysisNote = "Сетевой анализ заблокирован: адрес вне текущей Wi-Fi подсети"))
        }
        executor.execute {
            val ports = if (config.scanPorts) scanPorts(host, config) else device.openPorts.sortedBy { it.port }
            val mac = device.macAddress ?: NeighborResolver.macFor(host)
            val vendor = device.hardwareVendor ?: MacVendorResolver.resolve(mac)
            val hostname = reverseName(host)
            val os = inferOs(ports, device)
            val note = when {
                !config.scanPorts -> "TCP-сканирование выключено в настройках; использованы только уже известные службы"
                ports.isEmpty() -> "TCP-порты из выбранного набора не ответили"
                else -> "Проверено ${config.ports.distinct().take(64).size} TCP-портов"
            }
            callback(
                device.copy(
                    ipAddress = host,
                    macAddress = mac,
                    hardwareVendor = vendor,
                    openPorts = ports,
                    hostname = hostname,
                    osHint = os,
                    analysisNote = note,
                    securityFindings = securityFindings(ports)
                )
            )
        }
    }

    private fun scanPorts(host: String, config: ScanConfig): List<PortService> {
        val portPool = Executors.newFixedThreadPool(config.parallelism.coerceIn(4, 32))
        val futures = mutableListOf<Future<PortService?>>()
        config.ports.distinct().take(64).forEach { port ->
            futures += portPool.submit<PortService?> { TcpProbe.inspect(host, port, config.connectTimeoutMs, config.bannerTimeoutMs) }
        }
        portPool.shutdown()
        portPool.awaitTermination((config.connectTimeoutMs + config.bannerTimeoutMs + 2000L), TimeUnit.MILLISECONDS)
        return futures.mapNotNull { runCatching { it.get(50, TimeUnit.MILLISECONDS) }.getOrNull() }.sortedBy { it.port }
    }

    fun close() = executor.shutdownNow()

    companion object {
        fun inferOs(ports: List<PortService>, device: NearbyDevice? = null): String? {
            val banners = ports.mapNotNull { it.banner }.joinToString(" ").lowercase()
            val numbers = ports.map { it.port }.toSet()
            return when {
                "openssh_for_windows" in banners || "microsoft-iis" in banners || "windows" in banners || 3389 in numbers || (445 in numbers && 139 in numbers) -> "Windows (эвристика)"
                "dropbear" in banners || "openwrt" in banners -> "Linux / embedded (эвристика)"
                "ubuntu" in banners || "debian" in banners || "raspbian" in banners -> "Linux (по баннеру)"
                "darwin" in banners || "macos" in banners || device?.brand == "Apple" -> "Apple/macOS/iOS (эвристика)"
                9100 in numbers || 631 in numbers -> "Принтер / embedded OS (эвристика)"
                6466 in numbers || 6467 in numbers -> "Android TV / Google TV (Remote Service v2)"
                8060 in numbers -> "Roku OS / Roku TV (ECP)"
                3000 in numbers || 3001 in numbers -> "Возможный LG webOS TV (SSAP)"
                4352 in numbers -> "Проектор / embedded OS (PJLink)"
                8001 in numbers && 8002 in numbers -> "Возможный Samsung Tizen TV (LAN remote ports)"
                8008 in numbers || 8009 in numbers -> "Google Cast / Android-based media device (эвристика)"
                62078 in numbers -> "Apple iOS/iPadOS/macOS Mobile Device service (эвристика)"
                55443 in numbers -> "Yeelight / smart light (LAN Control)"
                22 in numbers -> "Unix/Linux-like (SSH, эвристика)"
                else -> null
            }
        }

        fun securityFindings(ports: List<PortService>): List<String> {
            val p = ports.map { it.port }.toSet()
            val findings = mutableListOf<String>()
            if (23 in p) findings += "Telnet доступен: если служба используется, проверьте необходимость и отсутствие чувствительных данных"
            if (21 in p) findings += "FTP доступен: проверьте, используется ли TLS или сеть полностью доверенная"
            if (80 in p && 443 !in p && 8443 !in p && 5001 !in p) findings += "HTTP доступен; HTTPS не найден в выбранном наборе портов (это наблюдение, не доказательство уязвимости)"
            if (1883 in p && 8883 !in p) findings += "MQTT 1883 доступен; MQTT TLS 8883 не найден в выбранном наборе портов"
            if (445 in p) findings += "SMB доступен в локальной сети — проверьте необходимость общего доступа"
            if (3389 in p) findings += "RDP доступен в локальной сети — проверьте NLA и правила доступа"
            return findings
        }

        fun hostFrom(address: String): String? = when {
            address.startsWith("http://") || address.startsWith("https://") -> runCatching { java.net.URI(address).host }.getOrNull()
            else -> address.substringBefore(':').takeIf { it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
        }

        private fun reverseName(host: String): String? = runCatching {
            val value = InetAddress.getByName(host).canonicalHostName
            value.takeIf { it != host && !it.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) }
        }.getOrNull()
    }
}
