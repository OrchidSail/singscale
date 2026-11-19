package dev.singscale.app.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import dev.singscale.app.SingScaleApp
import io.nekohasekai.libbox.InterfaceUpdateListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.Inet6Address
import java.net.NetworkInterface

internal data class InterfaceSyncSignature(
    val networkHandle: Long,
    val ifName: String,
    val expensive: Boolean,
    val dnsServers: List<String>,
    val linkAddresses: List<String>
)

internal fun shouldDispatchInterfaceSync(
    previous: InterfaceSyncSignature?,
    next: InterfaceSyncSignature
): Boolean = previous != next

object DefaultNetworkMonitor {

    @Volatile
    var defaultNetwork: Network? = null
        private set

    @Volatile
    private var listener: InterfaceUpdateListener? = null

    @Volatile
    private var lastAppliedSignature: InterfaceSyncSignature? = null

    private var registered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var interfaceSyncJob: Job? = null

    private val _hasPhysicalNetwork = MutableStateFlow(true)
    val hasPhysicalNetwork: StateFlow<Boolean> = _hasPhysicalNetwork.asStateFlow()

    private val _activePhysicalNetwork = MutableStateFlow<Network?>(null)
    val activePhysicalNetwork: StateFlow<Network?> = _activePhysicalNetwork.asStateFlow()

    private fun updateDefaultNetworkRef(network: Network?) {
        defaultNetwork = network
        _activePhysicalNetwork.value = network
        _hasPhysicalNetwork.value = network != null
    }

    private fun buildLinkSignature(
        network: Network,
        lp: LinkProperties,
        caps: NetworkCapabilities?
    ): InterfaceSyncSignature? {
        val ifName = lp.interfaceName
        if (ifName.isNullOrBlank() || ifName.startsWith("tun")) return null
        val expensive = caps?.let {
            !it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } ?: false
        val dns = lp.dnsServers.mapNotNull { it.hostAddress }.sorted()
        val addrs = lp.linkAddresses.map { "${it.address.hostAddress}/${it.prefixLength}" }.sorted()
        return InterfaceSyncSignature(
            networkHandle = network.networkHandle,
            ifName = ifName,
            expensive = expensive,
            dnsServers = dns,
            linkAddresses = addrs
        )
    }

    private fun isPhysicalUpstreamNetwork(network: Network?): Boolean {
        if (network == null) return false
        val caps = runCatching { SingScaleApp.connectivity.getNetworkCapabilities(network) }.getOrNull() ?: return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        val ifName = runCatching { SingScaleApp.connectivity.getLinkProperties(network)?.interfaceName }.getOrNull().orEmpty()
        if (ifName.startsWith("tun") || ifName.startsWith("ppp") || ifName.startsWith("wg")) {
            return false
        }
        return true
    }

    fun resolvePhysicalNetwork(): Network? {
        val current = defaultNetwork
        if (isPhysicalUpstreamNetwork(current)) {
            _activePhysicalNetwork.value = current
            _hasPhysicalNetwork.value = true
            return current
        }
        val active = runCatching { SingScaleApp.connectivity.activeNetwork }.getOrNull()
        if (isPhysicalUpstreamNetwork(active)) {
            updateDefaultNetworkRef(active)
            return active
        }
        val candidates = runCatching { SingScaleApp.connectivity.allNetworks.toList() }
            .getOrDefault(emptyList())
            .filter { isPhysicalUpstreamNetwork(it) }
        val best = candidates.maxByOrNull { net ->
            val caps = runCatching { SingScaleApp.connectivity.getNetworkCapabilities(net) }.getOrNull()
            var score = 0
            if (caps != null) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 40
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 30
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10
            }
            score
        }
        updateDefaultNetworkRef(best)
        return best
    }

    fun hasGlobalIpv6(): Boolean {
        val network = resolvePhysicalNetwork() ?: return false
        val lp = runCatching { SingScaleApp.connectivity.getLinkProperties(network) }.getOrNull() ?: return false
        val hasGlobalAddr = lp.linkAddresses.any { linkAddr ->
            val addr = linkAddr.address
            addr is Inet6Address &&
                !addr.isLinkLocalAddress &&
                !addr.isLoopbackAddress &&
                !addr.isAnyLocalAddress &&
                !addr.isMulticastAddress &&
                (addr.address[0].toInt() and 0xfe) != 0xfc
        }
        if (!hasGlobalAddr) return false
        return lp.routes.any { route ->
            val dest = route.destination.address
            dest is Inet6Address && route.destination.prefixLength == 0
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!isPhysicalUpstreamNetwork(network)) return
            updateDefaultNetworkRef(network)
            notifyInterfaceChanged(network, force = true)
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    val fallback = resolvePhysicalNetwork()
                    notifyInterfaceChanged(fallback, force = true)
                }
                return
            }
            if (isPhysicalUpstreamNetwork(network)) {
                updateDefaultNetworkRef(network)
                // Filter out pure RSRP signal-strength / bandwidth ticks when link topology is unchanged
                val lp = runCatching { SingScaleApp.connectivity.getLinkProperties(network) }.getOrNull()
                val nextSig = if (lp != null) buildLinkSignature(network, lp, networkCapabilities) else null
                if (nextSig != null && !shouldDispatchInterfaceSync(lastAppliedSignature, nextSig)) {
                    return
                }
                notifyInterfaceChanged(network, force = false)
            }
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            if (!isPhysicalUpstreamNetwork(network)) return
            updateDefaultNetworkRef(network)
            val caps = runCatching { SingScaleApp.connectivity.getNetworkCapabilities(network) }.getOrNull()
            val nextSig = buildLinkSignature(network, linkProperties, caps)
            if (nextSig != null && !shouldDispatchInterfaceSync(lastAppliedSignature, nextSig)) {
                return
            }
            notifyInterfaceChanged(network, force = false)
        }

        override fun onLost(network: Network) {
            if (defaultNetwork == network) {
                defaultNetwork = null
                val fallback = resolvePhysicalNetwork()
                notifyInterfaceChanged(fallback, force = true)
            }
        }
    }

    @Synchronized
    fun start() {
        val initial = resolvePhysicalNetwork()
        if (!registered) {
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            runCatching {
                SingScaleApp.connectivity.registerNetworkCallback(req, networkCallback, mainHandler)
                registered = true
            }
        }
        notifyInterfaceChanged(initial, force = true)
    }

    @Synchronized
    fun stop() {
        interfaceSyncJob?.cancel()
        interfaceSyncJob = null
        lastAppliedSignature = null
        if (!registered) return
        runCatching {
            SingScaleApp.connectivity.unregisterNetworkCallback(networkCallback)
        }
        registered = false
        listener = null
    }

    fun setListener(newListener: InterfaceUpdateListener?) {
        lastAppliedSignature = null
        listener = newListener
        if (newListener != null) {
            notifyInterfaceChanged(resolvePhysicalNetwork(), force = true)
        } else {
            interfaceSyncJob?.cancel()
            interfaceSyncJob = null
        }
    }

    private fun tryApplyInterfaceSync(
        currentListener: InterfaceUpdateListener,
        network: Network,
        force: Boolean
    ): Boolean {
        val lp = runCatching { SingScaleApp.connectivity.getLinkProperties(network) }.getOrNull() ?: return false
        val caps = runCatching { SingScaleApp.connectivity.getNetworkCapabilities(network) }.getOrNull()
        val sig = buildLinkSignature(network, lp, caps) ?: return false
        if (!force && !shouldDispatchInterfaceSync(lastAppliedSignature, sig)) {
            return true
        }
        val index = runCatching { NetworkInterface.getByName(sig.ifName)?.index ?: -1 }.getOrDefault(-1)
        if (index < 0) return false
        lastAppliedSignature = sig
        runCatching {
            currentListener.updateDefaultInterface(sig.ifName, index, sig.expensive, false)
        }
        return true
    }

    private fun notifyInterfaceChanged(network: Network?, force: Boolean = false) {
        interfaceSyncJob?.cancel()
        val currentListener = listener ?: return
        val target = if (isPhysicalUpstreamNetwork(network)) network else resolvePhysicalNetwork()
        if (target == null) {
            lastAppliedSignature = null
            _activePhysicalNetwork.value = null
            _hasPhysicalNetwork.value = false
            interfaceSyncJob = monitorScope.launch {
                runCatching { currentListener.updateDefaultInterface("", -1, false, false) }
            }
            return
        }
        _activePhysicalNetwork.value = target
        _hasPhysicalNetwork.value = true
        interfaceSyncJob = monitorScope.launch {
            for (attempt in 0 until 8) {
                val activeListener = listener ?: return@launch
                if (tryApplyInterfaceSync(activeListener, target, force = force)) {
                    return@launch
                }
                delay(75L)
            }
        }
    }
}
