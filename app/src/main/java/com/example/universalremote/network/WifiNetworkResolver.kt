package com.example.universalremote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

/**
 * Resolves the physical Wi-Fi network even when Android's default network is a VPN.
 *
 * Discovery already uses this network directly. v0.9.4 also binds the app process before
 * controller/probe traffic so ordinary Socket/URLConnection/OkHttp calls reach the LAN
 * instead of being sent into a VPN tunnel that may not route RFC1918 addresses.
 */
object WifiNetworkResolver {
    data class Selection(val network: Network, val linkProperties: LinkProperties)
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
            Selection(network, props) to caps
        }
        return candidates
            .sortedByDescending { (_, caps) ->
                (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) 2 else 0) +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) 1 else 0)
            }
            .firstOrNull()?.first
    }

    /** Bind future sockets from this app process to the selected physical Wi-Fi network. */
    fun bindProcessToWifi(context: Context): BindResult {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
            ?: return BindResult(false, null, null, "ConnectivityManager недоступен")
        val selection = current(app)
            ?: return BindResult(false, null, null, "Активная Wi-Fi сеть не найдена")
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
}
