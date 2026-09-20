package top.uwu.mikubox.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep

/** Underlying-network DNS and socket ownership for the embedded Android core. */
@Keep
object AndroidNetworkBridge {
    @Volatile private var owner: android.app.Service? = null
    @Volatile private var vpn: VpnService? = null
    @Volatile private var underlying: Network? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var manager: ConnectivityManager? = null
    private var lastDns = emptyList<String>()

    @Synchronized fun start(service: android.app.Service) {
        stop()
        owner = service
        vpn = service as? VpnService
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        manager = cm
        fun refresh(lost: Network? = null) = synchronized(this) {
            if (owner !== service) return@synchronized
            val candidates = cm.allNetworks.filter {
                it != lost && cm.getNetworkCapabilities(it)?.let { c ->
                    c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            val network = candidates.firstOrNull { it == cm.activeNetwork }
                ?: candidates.firstOrNull { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true }
                ?: candidates.firstOrNull()
            underlying = network
            vpn?.setUnderlyingNetworks(network?.let { arrayOf(it) })
            val servers = network?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
                .mapNotNull { it.hostAddress }.distinct()
            if (servers != lastDns) {
                MihomoCore.updateSystemDns(servers)
                lastDns = servers
            }
        }
        refresh()
        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh(network)
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = refresh()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refresh()
        }.also {
            cm.registerNetworkCallback(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), it)
        }
    }

    @Synchronized fun stop(service: android.app.Service? = null) {
        if (service != null && owner !== service) return
        callback?.let { cb -> runCatching { manager?.unregisterNetworkCallback(cb) } }
        callback = null
        manager = null
        underlying = null
        owner = null
        vpn = null
        lastDns = emptyList()
    }

    @Keep @JvmStatic fun protect(fd: Int): Boolean {
        val service = vpn
        if (service != null && !service.protect(fd)) return false
        val network = underlying ?: return true
        return runCatching {
            // bindSocket(FileDescriptor) does not take ownership. Borrow a dup
            // so neither Java nor a network switch can close Go's descriptor.
            ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
            true
        }.getOrDefault(false)
    }
}
