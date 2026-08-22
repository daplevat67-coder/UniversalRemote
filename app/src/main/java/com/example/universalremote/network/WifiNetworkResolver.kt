package com.example.universalremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finds the physical LAN even when Android's default network is a VPN.
 *
 * Some Android/OEM builds do not expose the underlying Wi-Fi Network through ConnectivityManager
 * while a VPN is active. Discovery therefore has three layers:
 *  1) ConnectivityManager Wi-Fi Network (best: sockets can be explicitly bound),
 *  2) a real wlan*/wifi* interface with a private IPv4,
 *  3) WifiManager DHCP information as a last-resort LAN snapshot.
 */
object WifiNetworkResolver {
    data class Selection(val network: Network, val linkProperties: LinkProperties)

    data class LanInfo(
        val network: Network?,
        val interfaceName: String?,
        val ipv4: Inet4Address,
        val prefixLength: Int,
        val gateways: List<Inet4Address>,
        val dnsServers: List<Inet4Address>,
        val source: String
    ) {
        val label: String
            get() = "${interfaceName ?: "LAN"} • ${ipv4.hostAddress}/$prefixLength • $source"
    }

    data class BindResult(
        val bound: Boolean,
        val interfaceName: String?,
        val ipv4: String?,
        val message: String
    )

    fun current(context: Context): Selection? {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = cm.allNetworks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
            val props = cm.getLinkProperties(network) ?: return@mapNotNull null
            val hasPrivateV4 = props.linkAddresses.any { link ->
                val a = link.address as? Inet4Address ?: return@any false
                Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(a))
            }
            if (!hasPrivateV4) return@mapNotNull null
            Selection(network, props) to caps
        }
        return candidates
            .sortedByDescending { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 1 else 0)
            }
            .firstOrNull()?.first
    }

    /** A LAN snapshot that survives VPN/OEM quirks where TRANSPORT_WIFI is not visible. */
    fun lanInfo(context: Context): LanInfo? {
        val app = context.applicationContext

        current(app)?.let { selection ->
            val props = selection.linkProperties
            val link = props.linkAddresses.firstOrNull { item ->
                val a = item.address as? Inet4Address ?: return@firstOrNull false
                Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(a))
            }
            val addr = link?.address as? Inet4Address
            if (addr != null) {
                return LanInfo(
                    network = selection.network,
                    interfaceName = props.interfaceName,
                    ipv4 = addr,
                    prefixLength = link.prefixLength.coerceIn(8, 30),
                    gateways = props.routes.mapNotNull { it.gateway as? Inet4Address }.distinct(),
                    dnsServers = props.dnsServers.mapNotNull { it as? Inet4Address }.distinct(),
                    source = "ConnectivityManager Wi-Fi"
                )
            }
        }

        // Fallback for Samsung/other OEM builds with a VPN that hides the physical Network object.
        runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            val candidate = interfaces
                .filter { it.isUp && !it.isLoopback }
                .sortedByDescending { iface ->
                    val n = iface.name.lowercase()
                    when {
                        n.startsWith("wlan") || n.startsWith("wifi") || n.startsWith("swlan") -> 3
                        n.startsWith("eth") -> 2
                        else -> 0
                    }
                }
                .firstNotNullOfOrNull { iface ->
                    val ia = iface.interfaceAddresses.firstOrNull { item ->
                        val a = item.address as? Inet4Address ?: return@firstOrNull false
                        Ipv4Range.isPrivate(Ipv4Range.ipv4ToInt(a))
                    } ?: return@firstNotNullOfOrNull null
                    val addr = ia.address as Inet4Address
                    Triple(iface.name, addr, ia.networkPrefixLength.toInt().coerceIn(8, 30))
                }
            if (candidate != null) {
                val (name, addr, prefix) = candidate
                val dhcp = dhcpSnapshot(app)
                return LanInfo(
                    network = null,
                    interfaceName = name,
                    ipv4 = addr,
                    prefixLength = prefix,
                    gateways = listOfNotNull(dhcp?.gateway),
                    dnsServers = dhcp?.dns.orEmpty(),
                    source = "interface fallback"
                )
            }
        }

        val dhcp = dhcpSnapshot(app) ?: return null
        val addr = dhcp.ipv4 ?: return null
        return LanInfo(
            network = null,
            interfaceName = "Wi-Fi",
            ipv4 = addr,
            prefixLength = dhcp.prefixLength,
            gateways = listOfNotNull(dhcp.gateway),
            dnsServers = dhcp.dns,
            source = "DHCP fallback"
        )
    }

    /** Bind future controller sockets to physical Wi-Fi when Android exposes that Network object. */
    fun bindProcessToWifi(context: Context): BindResult {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
            ?: return BindResult(false, null, null, "ConnectivityManager недоступен")
        val selection = current(app)
        if (selection == null) {
            val fallback = lanInfo(app)
            return if (fallback != null) {
                BindResult(false, fallback.interfaceName, fallback.ipv4.hostAddress,
                    "Wi-Fi Network API скрыт; LAN найден через ${fallback.source} (${fallback.ipv4.hostAddress})")
            } else {
                BindResult(false, null, null, "Активная локальная Wi-Fi/LAN сеть не найдена")
            }
        }
        val iface = selection.linkProperties.interfaceName
        val ipv4 = selection.linkProperties.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
        return runCatching {
            val ok = cm.bindProcessToNetwork(selection.network)
            if (ok) {
                BindResult(true, iface, ipv4, "LAN → Wi-Fi ${iface ?: "interface"}${ipv4?.let { " • $it" } ?: ""}")
            } else {
                BindResult(false, iface, ipv4, "Android отклонил привязку LAN-трафика к Wi-Fi")
            }
        }.getOrElse {
            BindResult(false, iface, ipv4, "Не удалось привязать LAN к Wi-Fi: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    fun unbindProcess(context: Context) {
        runCatching {
            context.applicationContext.getSystemService(ConnectivityManager::class.java)
                ?.bindProcessToNetwork(null)
        }
    }

    private data class DhcpSnapshot(
        val ipv4: Inet4Address?,
        val gateway: Inet4Address?,
        val dns: List<Inet4Address>,
        val prefixLength: Int
    )

    @Suppress("DEPRECATION")
    private fun dhcpSnapshot(context: Context): DhcpSnapshot? = runCatching {
        val wifi = context.getSystemService(WifiManager::class.java) ?: return@runCatching null
        val d = wifi.dhcpInfo ?: return@runCatching null
        val ip = littleEndianIpv4(d.ipAddress)
        val gateway = littleEndianIpv4(d.gateway)
        val dns = listOfNotNull(littleEndianIpv4(d.dns1), littleEndianIpv4(d.dns2)).distinct()
        val mask = d.netmask
        val prefix = if (mask != 0) Integer.bitCount(mask) else 24
        DhcpSnapshot(ip, gateway, dns, prefix.coerceIn(8, 30))
    }.getOrNull()

    private fun littleEndianIpv4(value: Int): Inet4Address? {
        if (value == 0) return null
        return runCatching {
            InetAddress.getByAddress(
                byteArrayOf(
                    value.toByte(),
                    (value ushr 8).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 24).toByte()
                )
            ) as? Inet4Address
        }.getOrNull()
    }
}
