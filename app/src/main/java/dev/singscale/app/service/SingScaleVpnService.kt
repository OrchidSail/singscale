package dev.singscale.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.singscale.app.MainActivity
import dev.singscale.app.R
import dev.singscale.app.SingScaleApp
import dev.singscale.app.VpnToggleTrampolineActivity
import dev.singscale.app.core.AppPowerStateMonitor
import dev.singscale.app.core.ConfigBuilder
import dev.singscale.app.core.GatewayProbeStatus
import dev.singscale.app.core.LinkQualityMetrics
import dev.singscale.app.core.QualityEngine
import dev.singscale.app.core.RuntimePowerMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.TailnetPeer
import dev.singscale.app.core.TrafficSplitTracker
import dev.singscale.app.core.TrafficStats
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.widget.SingScaleTileService
import dev.singscale.app.widget.SingScaleWidgetProvider
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

class SingScaleVpnService : VpnService(), SingScalePlatformInterface, CommandServerHandler {

    companion object {
        private const val TAG = "SingScaleVpnService"
        private const val NOTIFICATION_ID = 1001
        private const val LIVE_TELEMETRY_INTERVAL_MS = 1500L
        private const val BOOTSTRAP_WINDOW_MS = 15_000L

        const val ACTION_START = "dev.singscale.app.action.START"
        const val ACTION_STOP = "dev.singscale.app.action.STOP"
        const val ACTION_TOGGLE = "dev.singscale.app.action.TOGGLE"
        const val ACTION_TRIGGER_LOGIN = "dev.singscale.app.action.TRIGGER_LOGIN"
        const val ACTION_LOGOUT = "dev.singscale.app.action.LOGOUT"
        const val ACTION_REAPPLY_POLICY = "dev.singscale.app.action.REAPPLY_POLICY"
        const val ACTION_PROBE_NOW = "dev.singscale.app.action.PROBE_NOW"
        const val ACTION_DETECT_PEER_PORTS = "dev.singscale.app.action.DETECT_PEER_PORTS"
        const val EXTRA_PEER_ID = "extra_peer_id"
        const val EXTRA_PEER_IP = "extra_peer_ip"

        @Volatile
        private var activeInstance: SingScaleVpnService? = null

        fun isVpnPermissionNeeded(context: Context): Boolean = prepare(context) != null

        fun startTunnel(context: Context, requestInteractiveLogin: Boolean = false) {
            val appCtx = context.applicationContext
            if (prepare(appCtx) != null) {
                launchTrampoline(appCtx, requestInteractiveLogin)
                return
            }
            val running = activeInstance
            if (running != null) {
                running.dispatchInProcessStart(requestInteractiveLogin)
                return
            }
            val intent = Intent(appCtx, SingScaleVpnService::class.java).apply {
                action = if (requestInteractiveLogin) ACTION_TRIGGER_LOGIN else ACTION_START
            }
            runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.onFailure { e ->
                Log.w(TAG, "startForegroundService blocked from background, falling back to trampoline: ${e.message}")
                launchTrampoline(appCtx, requestInteractiveLogin)
            }
        }

        private fun launchTrampoline(appCtx: Context, requestInteractiveLogin: Boolean) {
            runCatching {
                val trampIntent = Intent(appCtx, VpnToggleTrampolineActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("login", requestInteractiveLogin)
                }
                appCtx.startActivity(trampIntent)
            }.onFailure { e ->
                Log.e(TAG, "Unable to launch VpnToggleTrampolineActivity", e)
            }
        }

        fun stopTunnel(context: Context) {
            val appCtx = context.applicationContext
            val running = activeInstance
            if (running != null) {
                running.shutdownTunnel()
                return
            }
            val cur = SingScaleRepository.state.value
            if (cur.tunnelStatus != TunnelStatus.STOPPED) {
                val stoppedMsg = if (cur.hasSavedLogin) {
                    val tn = cur.tailnetName.ifBlank { "Tailnet" }
                    "已就绪 ($tn) · 点击一键恢复组网连接"
                } else {
                    "未连接 · 点击开启 SingScale 组网引擎"
                }
                SingScaleRepository.updateTunnelStatus(TunnelStatus.STOPPED, stoppedMsg)
                SingScaleWidgetProvider.updateAllWidgets(appCtx)
                SingScaleTileService.requestListeningUpdate(appCtx)
            }
        }

        fun toggleTunnel(context: Context) {
            val status = SingScaleRepository.state.value.tunnelStatus
            if (activeInstance != null ||
                status == TunnelStatus.RUNNING ||
                status == TunnelStatus.STARTING ||
                status == TunnelStatus.NEEDS_LOGIN
            ) {
                stopTunnel(context)
            } else {
                startTunnel(context)
            }
        }

        fun notifyPolicyOrNodeChanged(context: Context) {
            activeInstance?.dispatchReapplyPolicy()
        }

        fun triggerProbeNow(context: Context) {
            activeInstance?.dispatchProbeNow()
        }

        fun triggerDetectPeerPorts(context: Context, peerId: String, peerIp: String) {
            if (peerId.isNotBlank() && peerIp.isNotBlank()) {
                activeInstance?.dispatchDetectPeerPorts(peerId, peerIp)
            }
        }

        fun triggerLogout(context: Context) {
            val appCtx = context.applicationContext
            val running = activeInstance
            if (running != null) {
                running.dispatchLogout()
            } else {
                SingScaleApp.clearPersistedTailscaleState(appCtx)
                SingScaleRepository.clearAuthStateOnLogout()
            }
        }

        fun closeTrackedConnection(connId: String) {
            activeInstance?.closeConnectionInternal(connId)
        }

        fun closeAllTrackedConnections() {
            activeInstance?.closeAllConnectionsInternal()
        }
    }

    private val serviceExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Unhandled exception in SingScaleVpnService coroutine", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + serviceExceptionHandler)
    private val reloadMutex = Mutex()
    private val probeMutex = Mutex()
    private val gatewayScanMutex = Mutex()
    private val jsonParser = Json { ignoreUnknownKeys = true }

    private val tailnetWakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val qualityWakeSignal = Channel<Unit>(Channel.CONFLATED)
    private val instanceEpochGen = AtomicInteger(0)

    private var tunPfd: ParcelFileDescriptor? = null
    private var commandServer: CommandServer? = null
    private var statusClient: CommandClient? = null
    private var powerObserverJob: Job? = null
    private var tailnetWatchJob: Job? = null
    private var qualityLoopJob: Job? = null
    private var trafficTickerJob: Job? = null
    private var streamReconnectJob: Job? = null

    @Volatile
    private var isForegroundPromoted: Boolean = false
    @Volatile
    private var isShuttingDown: Boolean = false
    @Volatile
    private var currentInstanceEpoch: Int = 0
    @Volatile
    private var lastNotifiedSubtitle: String = ""
    @Volatile
    private var lastUrlTestSweepAtMs: Long = 0L
    @Volatile
    private var lastObservedPathTypeName: String = ""

    private var lastAppliedConfigJson: String = ""
    private var lastAppliedReloadFingerprint: String = ""

    @Serializable
    private data class GoTailnetSnapshot(
        val endpointTag: String = "",
        val backendState: String = "",
        val authUrl: String = "",
        val hasSavedLogin: Boolean = false,
        val networkName: String = "",
        val magicDnsSuffix: String = "",
        val exitNodeId: String = "",
        val self: GoPeerDto? = null,
        val peers: List<GoPeerDto> = emptyList()
    )

    @Serializable
    private data class GoPeerDto(
        val stableId: String = "",
        val hostName: String = "",
        val dnsName: String = "",
        val userLogin: String = "",
        val userDisplayName: String = "",
        val os: String = "",
        val tailscaleIPs: List<String> = emptyList(),
        val online: Boolean = false,
        val active: Boolean = false,
        val expired: Boolean = false,
        val exitNode: Boolean = false,
        val exitNodeOption: Boolean = false,
        val curAddr: String = "",
        val peerRelay: String = "",
        val derpRegion: String = "",
        val addrs: List<String> = emptyList(),
        val primaryRoutes: List<String> = emptyList(),
        val allowedIPs: List<String> = emptyList(),
        val hasPeerRelayCap: Boolean = false,
        val rxBytes: Long = 0L,
        val txBytes: Long = 0L,
        val lastSeen: Long = 0L,
        val lastHandshake: Long = 0L
    ) {
        fun toDomain(): TailnetPeer = TailnetPeer(
            stableId = stableId,
            hostName = hostName,
            dnsName = dnsName,
            userLogin = userLogin,
            userDisplayName = userDisplayName,
            os = os,
            tailscaleIPs = tailscaleIPs,
            online = online,
            active = active,
            expired = expired,
            exitNode = exitNode,
            exitNodeOption = exitNodeOption,
            curAddr = curAddr,
            peerRelay = peerRelay,
            derpRegion = derpRegion,
            addrs = addrs,
            primaryRoutes = primaryRoutes,
            allowedIPs = allowedIPs,
            hasPeerRelayCap = hasPeerRelayCap,
            rxBytes = rxBytes,
            txBytes = txBytes,
            lastSeen = lastSeen,
            lastHandshake = lastHandshake
        )
    }

    @Serializable
    private data class GoProbeDto(
        val peerIp: String = "",
        val reachable: Boolean = false,
        val latencyMs: Double = 0.0,
        val minLatencyMs: Double = 0.0,
        val jitterMs: Double = 0.0,
        val lossRate: Double = 0.0,
        val pathType: String = "OFFLINE",
        val endpoint: String = "",
        val peerRelay: String = "",
        val derpRegionCode: String = "",
        val qualityScore: Int = 0
    )

    @Serializable
    private data class GoGatewayPortDto(
        val port: Int = 0,
        val protocol: String = "",
        val handshakeMs: Double = 0.0
    )

    @Serializable
    private data class GoGatewayProbeDto(
        val peerIp: String = "",
        val bestProtocol: String = "",
        val bestPort: Int = 0,
        val bestLatencyMs: Double = 0.0,
        val reachablePorts: List<GoGatewayPortDto> = emptyList()
    )

    override fun onCreate() {
        super.onCreate()
        SingScaleApp.instance.ensureLibboxSetup()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Guarantee FGS contract even if invoked via startForegroundService before stopping
                if (!isForegroundPromoted) {
                    promoteForeground("正在关闭 SingScale…")
                }
                shutdownTunnel()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE -> {
                val cur = SingScaleRepository.state.value.tunnelStatus
                if (cur == TunnelStatus.RUNNING || cur == TunnelStatus.STARTING || cur == TunnelStatus.NEEDS_LOGIN) {
                    if (!isForegroundPromoted) {
                        promoteForeground("正在关闭 SingScale…")
                    }
                    shutdownTunnel()
                    return START_NOT_STICKY
                } else {
                    promoteForeground("正在启动 SingScale Tailscale 组网引擎…")
                    serviceScope.launch { ensureStarted(triggerInteractiveLogin = false) }
                }
            }

            ACTION_TRIGGER_LOGIN -> {
                promoteForeground("正在连接 Tailscale 控制面获取登录授权…")
                dispatchInProcessStart(requestInteractiveLogin = true)
            }

            ACTION_REAPPLY_POLICY -> {
                if (!isForegroundPromoted) {
                    promoteForeground("SingScale 智能组网运行中")
                }
                dispatchReapplyPolicy()
            }

            ACTION_PROBE_NOW -> {
                if (!isForegroundPromoted) {
                    promoteForeground("SingScale 智能组网运行中")
                }
                dispatchProbeNow()
            }

            ACTION_DETECT_PEER_PORTS -> {
                if (!isForegroundPromoted) {
                    promoteForeground("SingScale 智能组网运行中")
                }
                val peerId = intent.getStringExtra(EXTRA_PEER_ID).orEmpty()
                val peerIp = intent.getStringExtra(EXTRA_PEER_IP).orEmpty()
                if (peerId.isNotBlank() && peerIp.isNotBlank()) {
                    dispatchDetectPeerPorts(peerId, peerIp)
                }
            }

            ACTION_LOGOUT -> {
                if (!isForegroundPromoted) {
                    promoteForeground("正在注销 Tailscale 会话…")
                }
                dispatchLogout()
            }

            else -> {
                promoteForeground("正在启动 SingScale 组网与智能路由…")
                serviceScope.launch { ensureStarted(triggerInteractiveLogin = false) }
            }
        }
        return START_STICKY
    }

    private fun dispatchInProcessStart(requestInteractiveLogin: Boolean) {
        serviceScope.launch {
            if (commandServer != null && tunPfd != null) {
                if (requestInteractiveLogin) {
                    requestInteractiveLoginNow()
                }
            } else {
                ensureStarted(triggerInteractiveLogin = requestInteractiveLogin)
            }
        }
    }

    private fun dispatchReapplyPolicy() {
        val status = SingScaleRepository.state.value.tunnelStatus
        if (status == TunnelStatus.RUNNING || status == TunnelStatus.NEEDS_LOGIN) {
            serviceScope.launch { reapplyRoutingConfiguration(forceReloadIfConfigDiffers = true) }
        }
    }

    private fun dispatchProbeNow() {
        if (SingScaleRepository.state.value.tunnelStatus == TunnelStatus.RUNNING) {
            serviceScope.launch {
                autoDiscoverOnlinePeerGateways(forceRescan = true)
                runLinkQualitySweep(forceDecisionCheck = true, forceUrlTest = true)
            }
        }
    }

    private fun dispatchDetectPeerPorts(peerId: String, peerIp: String) {
        if (SingScaleRepository.state.value.tunnelStatus == TunnelStatus.RUNNING) {
            serviceScope.launch {
                detectPeerGatewayPorts(
                    peerId = peerId,
                    peerIp = peerIp,
                    silentIfNone = false,
                    triggerImmediateReload = true
                )
            }
        }
    }

    private fun dispatchLogout() {
        serviceScope.launch { performTailscaleLogout() }
    }

    override fun onBind(intent: Intent?): IBinder? = super.onBind(intent)

    override fun onRevoke() {
        shutdownTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (activeInstance === this) {
            activeInstance = null
        }
        serviceScope.cancel()
        cleanupResources()
        super.onDestroy()
    }

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) {
            error("missing Android VPN permission")
        }
        val builder = Builder()
            .setSession("SingScale")
            .setMtu(if (options.mtu > 0) options.mtu else 1400)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        DefaultNetworkMonitor.resolvePhysicalNetwork()?.let { net ->
            runCatching { builder.setUnderlyingNetworks(arrayOf(net)) }
        }

        // Prevent our own sockets from re-entering TUN
        runCatching {
            builder.addDisallowedApplication(packageName)
        }

        val inet4 = options.inet4Address
        var hasV4 = false
        while (inet4.hasNext()) {
            val addr = inet4.next()
            runCatching {
                builder.addAddress(addr.address(), addr.prefix())
                hasV4 = true
            }
        }

        val inet6 = options.inet6Address
        var hasV6 = false
        while (inet6.hasNext()) {
            val addr = inet6.next()
            runCatching {
                builder.addAddress(addr.address(), addr.prefix())
                hasV6 = true
            }
        }

        if (options.autoRoute) {
            val dnsServers = options.dnsServerAddress
            while (dnsServers.hasNext()) {
                val dns = dnsServers.next()
                if (dns.isNotBlank() && (hasV6 || !dns.contains(':'))) {
                    runCatching { builder.addDnsServer(dns) }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (hasV4) {
                    val r4 = options.inet4RouteAddress
                    if (r4.hasNext()) {
                        while (r4.hasNext()) {
                            r4.next().toAndroidIpPrefixOrNull()?.let { pfx ->
                                runCatching { builder.addRoute(pfx) }
                            }
                        }
                    } else {
                        runCatching { builder.addRoute("0.0.0.0", 0) }
                    }
                    val ex4 = options.inet4RouteExcludeAddress
                    while (ex4.hasNext()) {
                        ex4.next().toAndroidIpPrefixOrNull()?.let { pfx ->
                            runCatching { builder.excludeRoute(pfx) }
                        }
                    }
                }

                if (hasV6) {
                    val r6 = options.inet6RouteAddress
                    if (r6.hasNext()) {
                        while (r6.hasNext()) {
                            r6.next().toAndroidIpPrefixOrNull()?.let { pfx ->
                                runCatching { builder.addRoute(pfx) }
                            }
                        }
                    } else {
                        runCatching { builder.addRoute("::", 0) }
                    }
                    val ex6 = options.inet6RouteExcludeAddress
                    while (ex6.hasNext()) {
                        ex6.next().toAndroidIpPrefixOrNull()?.let { pfx ->
                            runCatching { builder.excludeRoute(pfx) }
                        }
                    }
                }
            } else {
                if (hasV4) {
                    val r4 = options.inet4RouteRange
                    if (r4.hasNext()) {
                        while (r4.hasNext()) {
                            val a = r4.next()
                            runCatching { builder.addRoute(a.address(), a.prefix()) }
                        }
                    } else {
                        runCatching { builder.addRoute("0.0.0.0", 0) }
                    }
                }
                if (hasV6) {
                    val r6 = options.inet6RouteRange
                    if (r6.hasNext()) {
                        while (r6.hasNext()) {
                            val a = r6.next()
                            runCatching { builder.addRoute(a.address(), a.prefix()) }
                        }
                    } else {
                        runCatching { builder.addRoute("::", 0) }
                    }
                }
            }

            val includePkg = options.includePackage
            while (includePkg.hasNext()) {
                val pkg = includePkg.next()
                if (pkg != packageName) {
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {}
                }
            }

            val excludePkg = options.excludePackage
            while (excludePkg.hasNext()) {
                val pkg = excludePkg.next()
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {}
            }
        }

        val previousPfd = tunPfd
        val newPfd = builder.establish() ?: error("VPN Builder.establish() returned null")
        tunPfd = newPfd
        runCatching { previousPfd?.close() }
        return newPfd.fd
    }

    private suspend fun ensureStarted(triggerInteractiveLogin: Boolean) {
        reloadMutex.withLock {
            if (isShuttingDown) return
            try {
                SingScaleRepository.updateTunnelStatus(
                    TunnelStatus.STARTING,
                    "正在初始化 sing-box 内核与 Tailscale 节点…"
                )
                SingScaleTileService.requestListeningUpdate(this)

                SingScaleApp.instance.ensureLibboxSetup()
                DefaultNetworkMonitor.start()

                val isFirstSessionBoot = commandServer == null
                if (commandServer == null) {
                    val srv = CommandServer(this, this)
                    srv.start()
                    commandServer = srv
                }

                val curInitState = SingScaleRepository.state.value
                val plan = ConfigBuilder.build(
                    curInitState,
                    tailscaleHostname(),
                    enableIpv6 = DefaultNetworkMonitor.hasGlobalIpv6(),
                    stateDirectory = SingScaleApp.tailscaleStateDir(this).absolutePath
                )
                SingScaleRepository.updateLastConfigJson(plan.configJson)
                lastAppliedConfigJson = plan.configJson
                lastAppliedReloadFingerprint = plan.reloadFingerprint

                runCatching {
                    commandServer?.singScaleConfigureDirectFirst(
                        plan.directFirstActive,
                        curInitState.thresholds.directAcceptableLatencyMs,
                        1050,
                        true
                    )
                }

                val overrideOpts = OverrideOptions().apply {
                    autoRedirect = false
                    excludePackage = StringListIterator(listOf(packageName))
                }

                commandServer?.startOrReloadService(plan.configJson, overrideOpts)
                currentInstanceEpoch = instanceEpochGen.incrementAndGet()

                runCatching {
                    commandServer?.singScaleSelectOutbound(ConfigBuilder.OUTBOUND_SELECTOR, plan.primaryEgressTag)
                }

                SingScaleRepository.updateTunnelStatus(
                    TunnelStatus.RUNNING,
                    "组网隧道已建立，正在同步 Tailnet 状态…"
                )
                SingScaleRepository.appendDecision(
                    title = "SingScale 隧道引擎启动",
                    detail = "模式：${SingScaleRepository.state.value.policyMode.title} · 主出站 ${plan.primaryEgressTag}",
                    badge = "ONLINE"
                )

                if (isFirstSessionBoot) {
                    TrafficSplitTracker.resetSession()
                }
                startPowerLifecycleObserver()
                startTailnetWatcherLoop(triggerInteractiveLogin)
                startQualityEngineLoop()
                refreshNotification(force = true)
                SingScaleWidgetProvider.updateAllWidgets(this)
                SingScaleTileService.requestListeningUpdate(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SingScale service", e)
                SingScaleRepository.updateTunnelStatus(
                    TunnelStatus.ERROR,
                    "启动失败：${e.message ?: "未知错误"}",
                    error = e.message
                )
                cleanupResources()
                withContext(Dispatchers.Main) {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
    }

    /**
     * Central power-saving coordinator:
     * - Connects 1.5s live gRPC Status & Connections streams ONLY when MainActivity is visible & screen is ON.
     * - Calls CommandServer.pause() + SingScaleSetPowerMode(sleeping=true) when screen turns OFF,
     *   closing idle sockets and silencing Tailscale Disco/DERP heartbeats so 5G/LTE radio enters RRC_IDLE.
     * - Suspends Tailscale reconnect/probing completely when physical network is offline.
     */
    private fun startPowerLifecycleObserver() {
        powerObserverJob?.cancel()
        powerObserverJob = serviceScope.launch {
            var previousInteractive: Boolean? = null
            var previousUnderlyingNet = DefaultNetworkMonitor.defaultNetwork

            combine(
                AppPowerStateMonitor.state,
                DefaultNetworkMonitor.hasPhysicalNetwork,
                DefaultNetworkMonitor.activePhysicalNetwork
            ) { power, hasNet, activeNet -> Triple(power, hasNet, activeNet) }
                .distinctUntilChanged()
                .collect { (power, hasNet, activeNet) ->
                    if (isShuttingDown) return@collect
                    val srv = commandServer ?: return@collect

                    // Sync underlying physical network onto VpnService immediately across Wi-Fi <-> 5G handoffs
                    val latestNet = activeNet ?: DefaultNetworkMonitor.resolvePhysicalNetwork()
                    if (latestNet != previousUnderlyingNet) {
                        previousUnderlyingNet = latestNet
                        runCatching {
                            setUnderlyingNetworks(if (latestNet != null) arrayOf(latestNet) else null)
                        }
                    }

                    val screenChanged = previousInteractive != power.isScreenInteractive
                    previousInteractive = power.isScreenInteractive

                    runCatching {
                        srv.recordScreenState(power.isScreenInteractive)
                        if (!power.isScreenInteractive) {
                            srv.pause()
                        } else if (screenChanged) {
                            srv.wake()
                        }
                        srv.singScaleSetPowerMode(
                            ConfigBuilder.ENDPOINT_TAG,
                            !power.isScreenInteractive,
                            hasNet
                        )
                    }

                    if (power.shouldStreamLiveUiTelemetry && hasNet) {
                        if (statusClient == null) {
                            startStatusStreamClient()
                        }
                        if (trafficTickerJob?.isActive != true) {
                            startTrafficTickerLoop()
                        }
                    } else {
                        stopLiveTelemetryStreams()
                    }

                    if (power.isScreenInteractive && hasNet) {
                        tailnetWakeSignal.trySend(Unit)
                        if (power.mode == RuntimePowerMode.FOREGROUND_INTERACTIVE) {
                            qualityWakeSignal.trySend(Unit)
                        }
                        if (screenChanged) {
                            refreshNotification(force = false)
                        }
                    }
                }
        }
    }

    private fun startTrafficTickerLoop() {
        trafficTickerJob?.cancel()
        trafficTickerJob = serviceScope.launch {
            while (isActive) {
                delay(LIVE_TELEMETRY_INTERVAL_MS)
                if (!AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry) break
                TrafficSplitTracker.tick1Hz(SingScaleRepository.state.value)
            }
        }
    }

    private fun stopLiveTelemetryStreams() {
        trafficTickerJob?.cancel()
        trafficTickerJob = null
        streamReconnectJob?.cancel()
        streamReconnectJob = null
        val existing = statusClient
        statusClient = null
        if (existing != null) {
            runCatching { existing.disconnect() }
        }
    }

    private fun closeConnectionInternal(connId: String) {
        if (connId.isBlank()) return
        serviceScope.launch {
            runCatching { statusClient?.closeConnection(connId) }
        }
    }

    private fun closeAllConnectionsInternal() {
        serviceScope.launch {
            runCatching { statusClient?.closeConnections() }
        }
    }

    private fun startStatusStreamClient() {
        if (!AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry || isShuttingDown) return
        streamReconnectJob?.cancel()
        runCatching { statusClient?.disconnect() }
        val opts = CommandClientOptions().apply {
            addCommand(Libbox.CommandStatus)
            addCommand(Libbox.CommandConnections)
            statusInterval = LIVE_TELEMETRY_INTERVAL_MS * 1_000_000L
        }
        val client = CommandClient(
            object : CommandClientHandler {
                override fun connected() {}
                override fun disconnected(message: String?) {
                    if (!isShuttingDown &&
                        commandServer != null &&
                        tunPfd != null &&
                        AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry
                    ) {
                        streamReconnectJob?.cancel()
                        streamReconnectJob = serviceScope.launch {
                            delay(1200L)
                            if (!isShuttingDown &&
                                commandServer != null &&
                                tunPfd != null &&
                                AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry
                            ) {
                                startStatusStreamClient()
                            }
                        }
                    }
                }
                override fun setDefaultLogLevel(level: Int) {}
                override fun clearLogs() {}
                override fun writeLogs(messageList: LogIterator?) {}
                override fun writeStatus(message: StatusMessage?) {
                    message ?: return
                    SingScaleRepository.updateTraffic(
                        TrafficStats(
                            uplinkBps = message.uplink,
                            downlinkBps = message.downlink,
                            uplinkTotal = message.uplinkTotal,
                            downlinkTotal = message.downlinkTotal,
                            memoryBytes = message.memory,
                            connectionsIn = message.connectionsIn,
                            connectionsOut = message.connectionsOut
                        )
                    )
                }
                override fun writeGroups(message: OutboundGroupIterator?) {}
                override fun writeOutbounds(message: OutboundGroupItemIterator?) {}
                override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) {}
                override fun updateClashMode(newMode: String?) {}
                override fun writeConnectionEvents(events: ConnectionEvents?) {
                    TrafficSplitTracker.onConnectionEvents(
                        events = events,
                        uiState = SingScaleRepository.state.value,
                        instanceEpoch = currentInstanceEpoch
                    )
                }
            },
            opts
        )
        statusClient = client
        serviceScope.launch {
            delay(250L)
            if (statusClient === client && AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry) {
                runCatching { client.connect() }
            }
        }
    }

    private suspend fun requestInteractiveLoginNow() {
        val srv = commandServer ?: return
        val curState = SingScaleRepository.state.value
        if (curState.authUrl.isNotBlank() && curState.backendState != "Running") {
            SingScaleRepository.onAuthUrlReceived(curState.authUrl)
            refreshNotification()
            return
        }
        val url = runCatching {
            srv.singScaleStartInteractiveLogin(ConfigBuilder.ENDPOINT_TAG)
        }.onFailure { e ->
            Log.w(TAG, "Interactive login trigger failed: ${e.message}")
        }.getOrDefault("")

        if (url.isNotBlank() && url != "Running") {
            SingScaleRepository.onAuthUrlReceived(url)
            refreshNotification()
        }
    }

    private fun startTailnetWatcherLoop(initialInteractiveLogin: Boolean) {
        tailnetWatchJob?.cancel()
        tailnetWatchJob = serviceScope.launch {
            var pendingLoginTrigger = initialInteractiveLogin
            var lastLoginAttemptMs = 0L
            val watcherBootMs = System.currentTimeMillis()
            var firstSyncDone = false

            while (isActive && !isShuttingDown) {
                val power = AppPowerStateMonitor.state.value
                val hasNet = DefaultNetworkMonitor.hasPhysicalNetwork.value
                val curUiState = SingScaleRepository.state.value
                val nowBeforePoll = System.currentTimeMillis()
                val withinBootstrap = !firstSyncDone || (nowBeforePoll - watcherBootMs) < BOOTSTRAP_WINDOW_MS
                val waitingAuth = pendingLoginTrigger ||
                    !curUiState.isLoggedIn ||
                    curUiState.tunnelStatus == TunnelStatus.NEEDS_LOGIN ||
                    curUiState.authUrl.isNotBlank()

                val pollIntervalMs = if (!hasNet) {
                    0L
                } else {
                    power.tailnetPollIntervalMs(
                        isWaitingForAuth = waitingAuth,
                        withinStartupBootstrapWindow = withinBootstrap
                    )
                }

                // Zero wakeups when Screen is OFF or device has no physical connectivity
                if (pollIntervalMs <= 0L) {
                    tailnetWakeSignal.receive()
                    continue
                }

                val srv = commandServer
                if (srv != null) {
                    runCatching {
                        val snapRaw = srv.singScaleGetTailnetSnapshot(ConfigBuilder.ENDPOINT_TAG)
                        if (snapRaw.isNotBlank()) {
                            val dto = jsonParser.decodeFromString<GoTailnetSnapshot>(snapRaw)
                            val domainPeers = dto.peers.map { it.toDomain() }
                            SingScaleRepository.updateTailnetSnapshot(
                                backendState = dto.backendState,
                                authUrl = dto.authUrl,
                                hasSavedLogin = dto.hasSavedLogin,
                                tailnetName = dto.networkName,
                                magicDnsSuffix = dto.magicDnsSuffix,
                                selfPeer = dto.self?.toDomain(),
                                peers = domainPeers,
                                reportedExitNodeId = dto.exitNodeId
                            )

                            val hasAuthKey = SingScaleRepository.state.value.authConfig.authKey.isNotBlank()
                            val currentAuthUrl = SingScaleRepository.state.value.authUrl
                            val now = System.currentTimeMillis()
                            val elapsedMs = now - watcherBootMs

                            // NEVER call StartLoginInteractive during transient "NoState" / "Starting" when
                            // saved credentials exist in tailscaled.state — doing so forces LoginInteractive
                            // (regen=true) and wipes the authorized NodeKey!
                            val shouldAutoTriggerLogin = !hasAuthKey &&
                                currentAuthUrl.isBlank() &&
                                dto.backendState != "Running" &&
                                (now - lastLoginAttemptMs > 10_000L) &&
                                (
                                    (!dto.hasSavedLogin && elapsedMs >= 800L) ||
                                        (dto.backendState == "NeedsLogin" && elapsedMs >= 3_000L)
                                    )

                            if ((pendingLoginTrigger || shouldAutoTriggerLogin) &&
                                dto.backendState != "Running"
                            ) {
                                pendingLoginTrigger = false
                                lastLoginAttemptMs = now
                                requestInteractiveLoginNow()
                            }

                            if (dto.backendState == "Running" && domainPeers.isNotEmpty()) {
                                val hasUnprobedOnline = SingScaleRepository.state.value.peers.any {
                                    it.online && !it.expired && it.primaryIpv4.isNotBlank() &&
                                        it.gatewayProbeStatus == GatewayProbeStatus.UNPROBED
                                }
                                if (!firstSyncDone || hasUnprobedOnline) {
                                    firstSyncDone = true
                                    launch {
                                        autoDiscoverOnlinePeerGateways(forceRescan = false)
                                        runLinkQualitySweep(forceDecisionCheck = true, forceUrlTest = true)
                                    }
                                }
                            }
                            refreshNotification()
                        }
                    }
                }

                withTimeoutOrNull(pollIntervalMs) {
                    tailnetWakeSignal.receive()
                }
            }
        }
    }

    private fun startQualityEngineLoop() {
        qualityLoopJob?.cancel()
        qualityLoopJob = serviceScope.launch {
            delay(3500L)
            var cycle = 0
            while (isActive && !isShuttingDown) {
                val power = AppPowerStateMonitor.state.value
                val hasNet = DefaultNetworkMonitor.hasPhysicalNetwork.value
                val state = SingScaleRepository.state.value

                val probeIntervalSec = if (!hasNet) {
                    0
                } else {
                    power.qualityProbeIntervalSec(
                        configuredIntervalSec = state.thresholds.probeIntervalSec,
                        policyMode = state.policyMode
                    )
                }

                // Zero active radio probes when Screen is OFF, offline, or MESH_ONLY in background
                if (probeIntervalSec <= 0) {
                    qualityWakeSignal.receive()
                    continue
                }

                if (state.isLoggedIn && state.peers.any { it.online }) {
                    cycle++
                    if (power.mode == RuntimePowerMode.FOREGROUND_INTERACTIVE &&
                        cycle % 8 == 0 &&
                        state.exitReadyPeersCount == 0
                    ) {
                        autoDiscoverOnlinePeerGateways(forceRescan = true)
                    }
                    runLinkQualitySweep(forceDecisionCheck = true, forceUrlTest = false)
                }

                withTimeoutOrNull(probeIntervalSec * 1000L) {
                    qualityWakeSignal.receive()
                }
            }
        }
    }

    private suspend fun runLinkQualitySweep(
        forceDecisionCheck: Boolean,
        forceUrlTest: Boolean = false
    ) {
        if (!DefaultNetworkMonitor.hasPhysicalNetwork.value || isShuttingDown) return
        if (!probeMutex.tryLock()) return
        try {
            val srv = commandServer ?: return
            val state = SingScaleRepository.state.value
            val onlinePeers = state.peers.filter { it.online && !it.expired && it.primaryIpv4.isNotBlank() }
            if (onlinePeers.isEmpty()) return

            // In background mode, only probe candidate Exit/Relay peers instead of pinging every idle mesh device
            val powerMode = AppPowerStateMonitor.state.value.mode
            val peersToProbe = if (powerMode == RuntimePowerMode.FOREGROUND_INTERACTIVE) {
                onlinePeers
            } else {
                val candidates = onlinePeers.filter {
                    it.canActAsExit ||
                        it.canActAsRelay ||
                        it.stableId == state.activeExitPeerId ||
                        it.stableId == state.activeRelayPeerId
                }
                candidates.ifEmpty { onlinePeers.take(3) }
            }

            SingScaleRepository.setProbing(true)
            val samples = if (powerMode == RuntimePowerMode.FOREGROUND_INTERACTIVE) 2 else 1
            val csvIps = peersToProbe.joinToString(",") { it.primaryIpv4 }
            val rawJson = runCatching {
                srv.singScaleBatchProbePeers(ConfigBuilder.ENDPOINT_TAG, csvIps, samples, 1200)
            }.getOrDefault("[]")

            val probeList = runCatching {
                jsonParser.decodeFromString<List<GoProbeDto>>(rawJson)
            }.getOrDefault(emptyList())

            val activeDto = probeList.firstOrNull { dto ->
                onlinePeers.firstOrNull { it.primaryIpv4 == dto.peerIp }
                    ?.let { it.stableId == state.activeExitPeerId || it.stableId == state.activeRelayPeerId } == true
            }
            val nowMs = System.currentTimeMillis()
            val pathChanged = activeDto != null && activeDto.pathType != lastObservedPathTypeName
            if (activeDto != null) {
                lastObservedPathTypeName = activeDto.pathType
            }

            // Throttle HTTPS generate_204 URLTest (at most once every 3 minutes unless forced or path changed)
            val shouldRunHttpUrlTest = (state.activeExitPeerId.isNotBlank() || state.activeRelayPeerId.isNotBlank()) &&
                powerMode == RuntimePowerMode.FOREGROUND_INTERACTIVE &&
                (forceUrlTest || pathChanged || (nowMs - lastUrlTestSweepAtMs >= 180_000L))

            val activeEgressUrlMs = if (shouldRunHttpUrlTest) {
                lastUrlTestSweepAtMs = nowMs
                runCatching {
                    srv.singScaleTestOutboundURL(
                        ConfigBuilder.OUTBOUND_SELECTOR,
                        "https://cp.cloudflare.com/generate_204",
                        3000
                    )
                }.getOrDefault(-1)
            } else -1

            val prevByIp = state.peers.flatMap { p -> p.tailscaleIPs.map { ip -> ip to p.quality } }.toMap()
            val updatedMap = mutableMapOf<String, LinkQualityMetrics>()

            for (dto in probeList) {
                val isCurrentActiveIp = onlinePeers.firstOrNull { it.primaryIpv4 == dto.peerIp }
                    ?.let { it.stableId == state.activeExitPeerId || it.stableId == state.activeRelayPeerId } == true
                val merged = QualityEngine.mergeProbeWithHistory(
                    previous = prevByIp[dto.peerIp],
                    peerIp = dto.peerIp,
                    reachable = dto.reachable,
                    latencyMs = dto.latencyMs,
                    minLatencyMs = dto.minLatencyMs,
                    jitterMs = dto.jitterMs,
                    lossRate = dto.lossRate,
                    pathTypeStr = dto.pathType,
                    endpoint = dto.endpoint,
                    peerRelay = dto.peerRelay,
                    derpRegion = dto.derpRegionCode,
                    rawScore = dto.qualityScore,
                    urlTestMs = if (isCurrentActiveIp) activeEgressUrlMs else -1,
                    preferDirectPath = state.thresholds.preferDirectPath
                )
                updatedMap[dto.peerIp] = merged
            }

            if (updatedMap.isNotEmpty()) {
                SingScaleRepository.updatePeerQualities(updatedMap)
            }

            if (forceDecisionCheck) {
                val decision = QualityEngine.evaluate(SingScaleRepository.state.value)
                val needsApply = decision.changed
                SingScaleRepository.applyDynamicDecision(decision)
                if (needsApply) {
                    reapplyRoutingConfiguration(forceReloadIfConfigDiffers = true)
                }
                refreshNotification()
            }
        } finally {
            SingScaleRepository.setProbing(false)
            probeMutex.unlock()
        }
    }

    private suspend fun autoDiscoverOnlinePeerGateways(forceRescan: Boolean) {
        if (!DefaultNetworkMonitor.hasPhysicalNetwork.value || isShuttingDown) return
        if (!gatewayScanMutex.tryLock()) return
        try {
            val targets = SingScaleRepository.state.value.peers.filter { peer ->
                peer.online && !peer.expired && peer.primaryIpv4.isNotBlank() &&
                    (forceRescan || peer.gatewayProbeStatus == GatewayProbeStatus.UNPROBED)
            }
            if (targets.isEmpty()) return

            SingScaleRepository.markPeersProbingGateway(targets.map { it.stableId }.toSet())

            val anyNewlyBound = coroutineScope {
                targets.map { peer ->
                    async {
                        detectPeerGatewayPorts(
                            peerId = peer.stableId,
                            peerIp = peer.primaryIpv4,
                            silentIfNone = true,
                            triggerImmediateReload = false
                        )
                    }
                }.awaitAll().any { it }
            }

            if (anyNewlyBound) {
                reapplyRoutingConfiguration(forceReloadIfConfigDiffers = true)
            } else {
                val decision = QualityEngine.evaluate(SingScaleRepository.state.value)
                val needsApply = decision.changed
                SingScaleRepository.applyDynamicDecision(decision)
                if (needsApply) {
                    reapplyRoutingConfiguration(forceReloadIfConfigDiffers = true)
                }
            }
        } finally {
            gatewayScanMutex.unlock()
        }
    }

    private suspend fun detectPeerGatewayPorts(
        peerId: String,
        peerIp: String,
        silentIfNone: Boolean = false,
        triggerImmediateReload: Boolean = true
    ): Boolean {
        val srv = commandServer ?: return false
        SingScaleRepository.markPeersProbingGateway(setOf(peerId))
        val existingPort = SingScaleRepository.state.value.peerConfigs[peerId]?.proxyPort ?: 0
        val portsCsv = buildList {
            if (existingPort > 0) add(existingPort)
            addAll(listOf(1080, 10808, 10809, 7890, 7891, 7897, 2080, 2081, 10800, 8080, 8118, 8888, 3128))
        }.distinct().joinToString(",")

        val raw = runCatching {
            srv.singScaleProbePeerGateways(ConfigBuilder.ENDPOINT_TAG, peerIp, portsCsv, 950)
        }.getOrDefault("")
        if (raw.isBlank()) {
            SingScaleRepository.applyPeerGatewayDiscovery(peerId, emptyList(), "", 0)
            return false
        }

        val resp = runCatching { jsonParser.decodeFromString<GoGatewayProbeDto>(raw) }.getOrNull()
        if (resp == null) {
            SingScaleRepository.applyPeerGatewayDiscovery(peerId, emptyList(), "", 0)
            return false
        }

        val discoveredLabels = resp.reachablePorts.map { item ->
            val proto = if (item.protocol.lowercase() == "http") "HTTP" else "SOCKS5"
            "$proto:${item.port}"
        }
        val newlyBound = SingScaleRepository.applyPeerGatewayDiscovery(
            stableId = peerId,
            discoveredPorts = discoveredLabels,
            bestProtocol = resp.bestProtocol,
            bestPort = resp.bestPort
        )
        val peerName = SingScaleRepository.state.value.peers.firstOrNull { it.stableId == peerId }?.displayName ?: peerIp

        if (resp.bestPort > 0 && resp.bestProtocol.isNotBlank()) {
            if (!silentIfNone || newlyBound) {
                SingScaleRepository.appendDecision(
                    title = "发现节点网关能力 $peerName (${resp.bestProtocol.uppercase()}:${resp.bestPort})",
                    detail = "握手耗时 ${resp.bestLatencyMs.roundToInt()} ms，已自动纳入出口 (Exit) 与中继 (Relay) 智能切换候选池",
                    badge = "GATEWAY"
                )
            }
            if (triggerImmediateReload) {
                reapplyRoutingConfiguration(forceReloadIfConfigDiffers = true)
            }
            return true
        } else {
            if (!silentIfNone) {
                SingScaleRepository.appendDecision(
                    title = "节点网关检测完成 ($peerName)",
                    detail = "未检测到开放的 SOCKS5/HTTP 端口，将按原生 Tailscale Exit Node / Peer Relay 能力参与调度",
                    badge = "CHECKED"
                )
            }
            return false
        }
    }

    private suspend fun reapplyRoutingConfiguration(forceReloadIfConfigDiffers: Boolean) {
        reloadMutex.withLock {
            if (isShuttingDown) return
            val srv = commandServer ?: return
            val state = SingScaleRepository.state.value

            val decision = QualityEngine.evaluate(state)
            SingScaleRepository.applyDynamicDecision(decision)

            val freshState = SingScaleRepository.state.value
            val plan = ConfigBuilder.build(
                freshState,
                tailscaleHostname(),
                enableIpv6 = DefaultNetworkMonitor.hasGlobalIpv6(),
                stateDirectory = SingScaleApp.tailscaleStateDir(this).absolutePath
            )
            SingScaleRepository.updateLastConfigJson(plan.configJson)

            if (forceReloadIfConfigDiffers && plan.reloadFingerprint != lastAppliedReloadFingerprint) {
                val overrideOpts = OverrideOptions().apply {
                    autoRedirect = false
                    excludePackage = StringListIterator(listOf(packageName))
                }
                runCatching {
                    srv.startOrReloadService(plan.configJson, overrideOpts)
                    currentInstanceEpoch = instanceEpochGen.incrementAndGet()
                    lastAppliedConfigJson = plan.configJson
                    lastAppliedReloadFingerprint = plan.reloadFingerprint
                    if (AppPowerStateMonitor.state.value.shouldStreamLiveUiTelemetry) {
                        startStatusStreamClient()
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Hot-reload warning: ${e.message}")
                }
            } else {
                lastAppliedConfigJson = plan.configJson
            }

            // Apply Tailscale runtime prefs & active selector outbound live in <1ms without restarting tsnet
            val exitPeer = freshState.peers.firstOrNull { it.stableId == freshState.activeExitPeerId }
            val relayPeer = freshState.peers.firstOrNull { it.stableId == freshState.activeRelayPeerId }
            val relayStaticCsv = buildList {
                relayPeer?.let { rp ->
                    freshState.peerConfigs[rp.stableId]?.staticRelayEndpoint?.takeIf { it.isNotBlank() }?.let { add(it) }
                    rp.peerRelay.takeIf { it.isNotBlank() && it.contains(':') }?.let { add(it) }
                }
            }.joinToString(",")

            val runtimeExitIdOrIp = if (!plan.usesProxyExit && plan.nativeExitNodeTarget.isNotBlank()) {
                exitPeer?.stableId?.ifBlank { plan.nativeExitNodeTarget } ?: plan.nativeExitNodeTarget
            } else {
                ""
            }

            runCatching {
                srv.singScaleApplyTailscalePrefs(
                    ConfigBuilder.ENDPOINT_TAG,
                    runtimeExitIdOrIp,
                    freshState.thresholds.allowLanAccess,
                    freshState.thresholds.acceptSubnetRoutes,
                    freshState.authConfig.localRelayServerPort,
                    relayStaticCsv
                )
            }
            runCatching {
                srv.singScaleConfigureDirectFirst(
                    plan.directFirstActive,
                    freshState.thresholds.directAcceptableLatencyMs,
                    1050,
                    false
                )
            }
            runCatching {
                srv.singScaleSelectOutbound(ConfigBuilder.OUTBOUND_SELECTOR, plan.primaryEgressTag)
            }
            runCatching {
                srv.singScaleSetPowerMode(
                    ConfigBuilder.ENDPOINT_TAG,
                    !AppPowerStateMonitor.state.value.isScreenInteractive,
                    DefaultNetworkMonitor.hasPhysicalNetwork.value
                )
            }
            refreshNotification()
        }
    }

    private suspend fun performTailscaleLogout() {
        val srv = commandServer
        if (srv != null) {
            runCatching {
                srv.singScaleLogoutTailscale(ConfigBuilder.ENDPOINT_TAG)
            }.onFailure {
                SingScaleApp.clearPersistedTailscaleState(this)
            }
        } else {
            SingScaleApp.clearPersistedTailscaleState(this)
        }
        SingScaleRepository.clearAuthStateOnLogout()
        SingScaleRepository.appendDecision(
            title = "已注销当前 Tailscale 节点会话",
            detail = "正在请求新的交互式登录链接…",
            badge = "LOGOUT"
        )
        requestInteractiveLoginNow()
    }

    internal fun shutdownTunnel() {
        if (isShuttingDown) return
        isShuttingDown = true
        SingScaleRepository.updateTunnelStatus(TunnelStatus.STOPPING, "正在关闭组网隧道…")
        SingScaleWidgetProvider.updateAllWidgets(this)
        SingScaleTileService.requestListeningUpdate(this)

        serviceScope.launch {
            cleanupResources()
            val cur = SingScaleRepository.state.value
            val stoppedMsg = if (cur.hasSavedLogin) {
                val tn = cur.tailnetName.ifBlank { "Tailnet" }
                "已就绪 ($tn) · 点击一键恢复组网连接"
            } else {
                "未连接 · 点击开启 SingScale 组网引擎"
            }
            SingScaleRepository.updateTunnelStatus(
                TunnelStatus.STOPPED,
                stoppedMsg
            )
            SingScaleWidgetProvider.updateAllWidgets(this@SingScaleVpnService)
            SingScaleTileService.requestListeningUpdate(this@SingScaleVpnService)
            withContext(Dispatchers.Main) {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                isForegroundPromoted = false
                stopSelf()
            }
        }
    }

    private fun cleanupResources() {
        powerObserverJob?.cancel()
        powerObserverJob = null
        tailnetWatchJob?.cancel()
        tailnetWatchJob = null
        qualityLoopJob?.cancel()
        qualityLoopJob = null
        stopLiveTelemetryStreams()
        TrafficSplitTracker.tick1Hz(SingScaleRepository.state.value, System.currentTimeMillis() + 5000L)
        DefaultNetworkMonitor.stop()
        val srv = commandServer
        commandServer = null
        if (srv != null) {
            runCatching { srv.closeService() }
            runCatching { srv.close() }
        }
        val pfd = tunPfd
        tunPfd = null
        if (pfd != null) {
            runCatching { pfd.close() }
        }
    }

    private fun promoteForeground(contentText: String) {
        val notification = buildVpnNotification(contentText)
        lastNotifiedSubtitle = contentText
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundPromoted = true
        }.onFailure { e ->
            Log.e(TAG, "promoteForeground failed", e)
        }
    }

    private fun refreshNotification(force: Boolean = false) {
        if (!force && !AppPowerStateMonitor.state.value.isScreenInteractive) return
        val s = SingScaleRepository.state.value
        val activeNode = s.activeExitPeer?.displayName ?: s.activeRelayPeer?.displayName ?: "仅组网"
        val latency = if (s.activeQuality.reachable && s.activeQuality.latencyMs > 0) {
            // Quantize to 10ms steps in notification text so 1ms jitter never wakes SystemUI!
            val bucketedMs = ((s.activeQuality.latencyMs / 10.0).roundToInt() * 10).coerceAtLeast(1)
            " · ~${bucketedMs}ms (${s.activeQuality.pathType.badge})"
        } else ""
        val subtitle = when (s.tunnelStatus) {
            TunnelStatus.NEEDS_LOGIN -> "点击完成 Tailscale 登录授权"
            TunnelStatus.RUNNING -> "节点: $activeNode$latency · ${s.policyMode.badge}"
            else -> s.statusMessage
        }
        if (!force && subtitle == lastNotifiedSubtitle) return
        lastNotifiedSubtitle = subtitle
        runCatching {
            SingScaleApp.notificationManager.notify(NOTIFICATION_ID, buildVpnNotification(subtitle))
        }
    }

    private fun buildVpnNotification(subtitle: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SingScaleVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, SingScaleApp.CHANNEL_VPN)
            .setSmallIcon(R.drawable.ic_singscale_logo)
            .setContentTitle("SingScale 智能组网运行中")
            .setContentText(subtitle)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, "断开连接", stopIntent)
            .build()
    }

    // CommandServerHandler callbacks
    override fun serviceStop() {
        shutdownTunnel()
    }

    override fun serviceReload() {
        serviceScope.launch { reapplyRoutingConfiguration(forceReloadIfConfigDiffers = false) }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {}

    override fun triggerNativeCrash() {}

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) Log.d(TAG, message)
    }

    override fun connectSSHAgent(): Int = -1
}
