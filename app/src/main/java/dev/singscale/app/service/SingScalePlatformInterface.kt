package dev.singscale.app.service

import android.app.PendingIntent
import android.content.Intent
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import dev.singscale.app.R
import dev.singscale.app.SingScaleApp
import dev.singscale.app.core.SingScaleRepository
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterface
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.WIFIState
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface as JavaNetworkInterface
import java.util.concurrent.ConcurrentHashMap

interface SingScalePlatformInterface : PlatformInterface {

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int
    ): ConnectionOwner {
        val cleanSrc = sourceAddress.substringBefore('%')
        val cleanDst = destinationAddress.substringBefore('%')
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = try {
                SingScaleApp.connectivity.getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(cleanSrc, sourcePort.coerceIn(0, 65535)),
                    InetSocketAddress(cleanDst, destinationPort.coerceIn(0, 65535))
                )
            } catch (e: Exception) {
                throw IllegalStateException("connection owner lookup failed: ${e.message}")
            }
            if (uid == Process.INVALID_UID) throw IllegalStateException("connection owner not found")
            ConnectionOwner().also { it.userId = uid }
        } else {
            Libbox.findConnectionOwner(ipProtocol, cleanSrc, sourcePort, cleanDst, destinationPort)
        }
        val pkgs = UidPackageCache.resolve(owner.userId)
        owner.userName = pkgs.firstOrNull().orEmpty()
        owner.setAndroidPackageNames(StringListIterator(pkgs))
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val networks = SingScaleApp.connectivity.allNetworks
        val sysInterfaces = runCatching { JavaNetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
        val list = mutableListOf<NetworkInterface>()

        for (network in networks) {
            val lp = SingScaleApp.connectivity.getLinkProperties(network) ?: continue
            val caps = SingScaleApp.connectivity.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            ) {
                continue
            }
            val ifName = lp.interfaceName ?: continue
            if (ifName.startsWith("tun") || ifName.startsWith("ppp")) continue
            val sysIf = sysInterfaces.firstOrNull { it.name == ifName } ?: continue

            val boxIf = NetworkInterface().apply {
                name = ifName
                index = sysIf.index
                mtu = runCatching { sysIf.mtu }.getOrDefault(1500)
                type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                dnsServer = StringListIterator(lp.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringListIterator(
                    lp.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress }
                )
                addresses = StringListIterator(
                    runCatching { sysIf.interfaceAddresses }
                        .getOrDefault(emptyList())
                        .mapNotNull { it?.toValidCidrPrefixOrNull() }
                )

                var flagsValue = 0
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    flagsValue = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
                }
                if (sysIf.isLoopback) flagsValue = flagsValue or OsConstants.IFF_LOOPBACK
                if (sysIf.isPointToPoint) flagsValue = flagsValue or OsConstants.IFF_POINTOPOINT
                if (sysIf.supportsMulticast()) flagsValue = flagsValue or OsConstants.IFF_MULTICAST
                flags = flagsValue
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
            list.add(boxIf)
        }
        return NetworkInterfaceListIterator(list)
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? {
        @Suppress("DEPRECATION")
        val info = runCatching { SingScaleApp.wifiManager.connectionInfo }.getOrNull() ?: return null
        var ssid = info.ssid ?: return null
        if (ssid == "<unknown ssid>") return WIFIState("", "")
        if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) {
            ssid = ssid.substring(1, ssid.length - 1)
        }
        return WIFIState(ssid, info.bssid.orEmpty())
    }

    override fun clearDNSCache() {}

    override fun sendNotification(notification: Notification) {
        val builder = NotificationCompat.Builder(SingScaleApp.instance, SingScaleApp.CHANNEL_AUTH)
            .setSmallIcon(R.drawable.ic_singscale_logo)
            .setContentTitle(notification.title.ifBlank { "SingScale Tailscale 认证" })
            .setContentText(notification.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        if (notification.openURL.isNotBlank()) {
            SingScaleRepository.onAuthUrlReceived(notification.openURL)
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(notification.openURL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(
                SingScaleApp.instance,
                notification.typeID,
                viewIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentIntent(pi)
        }
        SingScaleApp.notificationManager.notify(notification.typeID + 2000, builder.build())
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        SingScaleApp.notificationManager.cancel(typeID + 2000)
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {}

    override fun registerMyInterface(name: String?) {}

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("platform shell disabled in SingScale")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int
    ): ShellSession {
        error("shell session not enabled")
    }

    override fun lookupUser(username: String?): PlatformUser {
        error("lookupUser not enabled")
    }

    override fun lookupSFTPServer(): String {
        error("sftp not enabled")
    }

    override fun readSystemSSHHostKey(): String {
        error("ssh host key not enabled")
    }

    override fun tailscaleHostname(): String {
        val customName = runCatching {
            Settings.Global.getString(SingScaleApp.instance.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        val raw = customName?.takeIf { it.isNotBlank() } ?: "${Build.MANUFACTURER}-${Build.MODEL}"
        return raw.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "singscale-android" }
    }

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession {
        error("platform bridge not enabled")
    }
}

class StringListIterator(items: List<String>) : StringIterator {
    private val iterator = items.iterator()
    private val count = items.size
    override fun len(): Int = count
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): String = iterator.next()
}

class NetworkInterfaceListIterator(items: List<NetworkInterface>) : NetworkInterfaceIterator {
    private val iterator = items.iterator()
    override fun hasNext(): Boolean = iterator.hasNext()
    override fun next(): NetworkInterface = iterator.next()
}

private object UidPackageCache {
    private val cache = ConcurrentHashMap<Int, List<String>>(64)

    fun resolve(uid: Int): List<String> {
        if (uid < 0) return emptyList()
        cache[uid]?.let { return it }
        val resolved = runCatching {
            SingScaleApp.instance.packageManager.getPackagesForUid(uid).orEmpty().toList()
        }.getOrDefault(emptyList())
        if (cache.size > 256) cache.clear()
        cache[uid] = resolved
        return resolved
    }
}

internal fun formatValidatedCidrPrefix(hostAddrRaw: String?, isIpv6: Boolean, prefixLen: Int): String? {
    val raw = hostAddrRaw?.trim()?.substringBefore('%').orEmpty()
    if (raw.isEmpty() || raw.equals("null", ignoreCase = true)) return null
    val maxBits = if (isIpv6) 128 else 32
    // Android cellular/CLAT interfaces can transiently report -1 or 128 on IPv4 addresses during handoff
    val normalizedBits = when {
        prefixLen < 0 -> return null
        !isIpv6 && prefixLen == 128 -> 32
        prefixLen > maxBits -> return null
        else -> prefixLen
    }
    return "$raw/$normalizedBits"
}

private fun InterfaceAddress.toValidCidrPrefixOrNull(): String? = runCatching {
    val addr = address ?: return null
    when (addr) {
        is Inet6Address -> {
            val cleanHost = InetAddress.getByAddress(addr.address).hostAddress
            formatValidatedCidrPrefix(cleanHost, isIpv6 = true, prefixLen = networkPrefixLength.toInt())
        }
        is Inet4Address -> {
            formatValidatedCidrPrefix(addr.hostAddress, isIpv6 = false, prefixLen = networkPrefixLength.toInt())
        }
        else -> null
    }
}.getOrNull()

fun RoutePrefix.toAndroidIpPrefixOrNull(): IpPrefix? = runCatching {
    val rawHost = address().trim().substringBefore('%')
    if (rawHost.isEmpty()) return null
    val inet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        InetAddresses.parseNumericAddress(rawHost)
    } else {
        InetAddress.getByName(rawHost)
    }
    val maxBits = if (inet is Inet6Address) 128 else 32
    val pfx = prefix().coerceIn(0, maxBits)
    IpPrefix(inet, pfx)
}.getOrNull()
