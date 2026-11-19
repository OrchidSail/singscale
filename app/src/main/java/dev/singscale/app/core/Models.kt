package dev.singscale.app.core

import kotlinx.serialization.Serializable

@Serializable
enum class TunnelStatus {
    STOPPED,
    STARTING,
    RUNNING,
    NEEDS_LOGIN,
    STOPPING,
    ERROR
}

@Serializable
enum class TransportPathType(
    val label: String,
    val badge: String,
    val shortLabel: String
) {
    DIRECT("P2P 直连", "DIRECT", "P2P"),
    PEER_RELAY("节点中继", "RELAY", "中继"),
    DERP("DERP 中转", "DERP", "DERP"),
    OFFLINE("离线 / 未知", "IDLE", "离线")
}

@Serializable
enum class QualityGrade(val code: String, val label: String) {
    S("S", "极佳"),
    A("A", "优秀"),
    B("B", "一般"),
    C("C", "较差"),
    OFFLINE("—", "不可达")
}

@Serializable
enum class RoutingPolicyMode(
    val title: String,
    val subtitle: String,
    val badge: String
) {
    SMART_QUALITY(
        title = "智能质量动态策略（直连优先）",
        subtitle = "实测域名本地直连与组网链路质量：直连质量尚可（如 googleapis.com / CDN）优先走直连，直连受阻或过慢时自动切入最优中继/出口",
        badge = "智能直连优先"
    ),
    GLOBAL_EXIT(
        title = "全局出口代理",
        subtitle = "除局域网与控制面外，全部互联网及组网流量经选定出口 / Relay 节点转发",
        badge = "全局出口"
    ),
    RULE_SPLIT(
        title = "自定义规则分流",
        subtitle = "组网内网走 Tailnet，自定义指定域名分别走中继/出口或本地直连",
        badge = "规则分流"
    ),
    MESH_ONLY(
        title = "仅 Tailnet 组网互联",
        subtitle = "仅接管 100.64.0.0/10 组网设备、子网路由与 MagicDNS，公网流量本地直连",
        badge = "仅组网"
    )
}

@Serializable
enum class DynamicTier(val summary: String) {
    FULL_ACCELERATION("直连质量优先 · 受阻流量走高速出口"),
    BALANCED_SPLIT("直连质量优先 · 智能均衡分流"),
    DERP_CONSERVATIVE("DERP 降级保护 (仅关键流量走隧道)"),
    FAILSAFE_DIRECT("故障安全直连保护 (出口不可达，保留组网)")
}

@Serializable
enum class PeerGatewayMode {
    AUTO,           // Prefer native Tailscale ExitNode/PeerRelay, fallback to detected proxy port
    NATIVE_ONLY,    // Native Tailscale WireGuard Exit Node / Peer Relay
    PROXY_OVER_TS   // sing-box SOCKS5 / HTTP outbound tunneled over Tailscale IP
}

@Serializable
enum class GatewayProbeStatus {
    UNPROBED,
    PROBING,
    PROBED
}

@Serializable
data class PeerEndpointConfig(
    val stableId: String,
    val gatewayMode: PeerGatewayMode = PeerGatewayMode.AUTO,
    val proxyProtocol: String = "socks", // "socks" or "http"
    val proxyPort: Int = 0,              // 0 = auto-detected or none
    val staticRelayEndpoint: String = "", // optional ip:port for Tailscale Peer Relay
    val inAutoPool: Boolean = true
)

@Serializable
data class LinkQualityMetrics(
    val peerIp: String = "",
    val reachable: Boolean = false,
    val latencyMs: Double = 0.0,
    val minLatencyMs: Double = 0.0,
    val urlTestMs: Int = -1,
    val jitterMs: Double = 0.0,
    val lossRate: Double = 0.0,
    val pathType: TransportPathType = TransportPathType.OFFLINE,
    val endpoint: String = "",
    val peerRelay: String = "",
    val derpRegion: String = "",
    val score: Int = 0,
    val grade: QualityGrade = QualityGrade.OFFLINE,
    val history: List<Float> = emptyList(),
    val updatedAtMs: Long = 0L
) {
    companion object {
        fun gradeFor(score: Int, reachable: Boolean): QualityGrade {
            if (!reachable || score <= 0) return QualityGrade.OFFLINE
            return when {
                score >= 88 -> QualityGrade.S
                score >= 74 -> QualityGrade.A
                score >= 55 -> QualityGrade.B
                else -> QualityGrade.C
            }
        }
    }
}

@Serializable
data class TailnetPeer(
    val stableId: String,
    val hostName: String,
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
    val lastHandshake: Long = 0L,
    val quality: LinkQualityMetrics = LinkQualityMetrics(),
    val customConfig: PeerEndpointConfig? = null,
    val gatewayProbeStatus: GatewayProbeStatus = GatewayProbeStatus.UNPROBED,
    val discoveredProxyPorts: List<String> = emptyList()
) {
    val displayName: String
        get() = dnsName.substringBefore('.').ifBlank { hostName.ifBlank { primaryIpv4.ifBlank { stableId.take(8) } } }

    val primaryIpv4: String
        get() = tailscaleIPs.firstOrNull { !it.contains(':') } ?: tailscaleIPs.firstOrNull().orEmpty()

    val primaryIpv6: String
        get() = tailscaleIPs.firstOrNull { it.contains(':') }.orEmpty()

    val effectivePathType: TransportPathType
        get() = when {
            active && curAddr.isNotBlank() -> TransportPathType.DIRECT
            active && peerRelay.isNotBlank() -> TransportPathType.PEER_RELAY
            quality.updatedAtMs > 0L && quality.reachable -> quality.pathType
            curAddr.isNotBlank() -> TransportPathType.DIRECT
            peerRelay.isNotBlank() -> TransportPathType.PEER_RELAY
            derpRegion.isNotBlank() && online -> TransportPathType.DERP
            else -> TransportPathType.OFFLINE
        }

    val totalPeerBytes: Long
        get() = rxBytes.coerceAtLeast(0L) + txBytes.coerceAtLeast(0L)

    val subnetRoutes: List<String>
        get() = (primaryRoutes + allowedIPs)
            .filter { it != "0.0.0.0/0" && it != "::/0" && !it.endsWith("/32") && !it.endsWith("/128") }
            .distinct()

    val inAutoPool: Boolean
        get() = customConfig?.inAutoPool ?: true

    val effectiveProxyPort: Int
        get() = customConfig?.proxyPort ?: 0

    val effectiveProxyProtocolLabel: String
        get() = if ((customConfig?.proxyProtocol ?: "socks").lowercase() == "http") "HTTP" else "SOCKS5"

    val hasProxyGateway: Boolean
        get() = effectiveProxyPort > 0 && customConfig?.gatewayMode != PeerGatewayMode.NATIVE_ONLY

    val canActAsExit: Boolean
        get() = (exitNodeOption && customConfig?.gatewayMode != PeerGatewayMode.PROXY_OVER_TS) || hasProxyGateway

    val canActAsRelay: Boolean
        get() = ((hasPeerRelayCap || customConfig?.staticRelayEndpoint?.isNotBlank() == true) &&
            customConfig?.gatewayMode != PeerGatewayMode.PROXY_OVER_TS) || hasProxyGateway

    val exitCapabilityBadge: String
        get() = when {
            exitNodeOption && hasProxyGateway -> "原生 Exit + $effectiveProxyProtocolLabel:$effectiveProxyPort"
            exitNodeOption && customConfig?.gatewayMode != PeerGatewayMode.PROXY_OVER_TS -> "原生 Exit Node"
            hasProxyGateway -> "代理出口 $effectiveProxyProtocolLabel:$effectiveProxyPort"
            !online -> "离线"
            gatewayProbeStatus == GatewayProbeStatus.PROBING -> "正在探测端口…"
            else -> "未开启 Exit / 代理"
        }

    val relayCapabilityBadge: String
        get() = when {
            hasPeerRelayCap && hasProxyGateway -> "Peer Relay + $effectiveProxyProtocolLabel:$effectiveProxyPort"
            (hasPeerRelayCap || customConfig?.staticRelayEndpoint?.isNotBlank() == true) &&
                customConfig?.gatewayMode != PeerGatewayMode.PROXY_OVER_TS -> "原生 Peer Relay"
            hasProxyGateway -> "代理跳板 $effectiveProxyProtocolLabel:$effectiveProxyPort"
            subnetRoutes.isNotEmpty() -> "子网路由 ${subnetRoutes.first()}"
            !online -> "离线"
            gatewayProbeStatus == GatewayProbeStatus.PROBING -> "正在探测中继…"
            else -> "未开启 Relay / 代理"
        }

    val shortRoleTag: String
        get() = when {
            canActAsExit && canActAsRelay -> "出口+Relay"
            canActAsExit -> "可作出口"
            canActAsRelay -> "可作Relay"
            subnetRoutes.isNotEmpty() -> "子网路由"
            gatewayProbeStatus == GatewayProbeStatus.PROBING -> "探测中…"
            else -> "仅组网"
        }
}

@Serializable
data class PolicyThresholds(
    val probeIntervalSec: Int = 45,
    val hysteresisScoreDelta: Int = 10,
    val highLatencyThresholdMs: Int = 140,
    val failoverLatencyMs: Int = 380,
    val maxLossPercent: Int = 30,
    val directFirstPreferred: Boolean = true,
    val directAcceptableLatencyMs: Int = 220,
    val preferDirectPath: Boolean = true,
    val derpSmartDowngrade: Boolean = true,
    val failSafeDirectFallback: Boolean = true,
    val allowLanAccess: Boolean = true,
    val acceptSubnetRoutes: Boolean = true,
    val allowTailnetInboundLocalForward: Boolean = true,
    val forcedRelayDomains: List<String> = emptyList(),
    val customDirectDomains: List<String> = emptyList(),
    val customProxyDomains: List<String> = emptyList()
)

@Serializable
data class TailscaleAuthConfig(
    val controlUrl: String = "",        // empty = official https://controlplane.tailscale.com
    val authKey: String = "",           // optional tskey-auth-...
    val hostname: String = "",          // empty = device name
    val ephemeral: Boolean = false,
    val localRelayServerPort: Int = 0   // optional UDP port to advertise local Peer Relay
)

@Serializable
data class PolicyDecisionEvent(
    val id: Long,
    val timestampMs: Long,
    val title: String,
    val detail: String,
    val badge: String,
    val isWarning: Boolean = false
)

@Serializable
data class TrafficStats(
    val uplinkBps: Long = 0L,
    val downlinkBps: Long = 0L,
    val uplinkTotal: Long = 0L,
    val downlinkTotal: Long = 0L,
    val memoryBytes: Long = 0L,
    val connectionsIn: Int = 0,
    val connectionsOut: Int = 0
)

@Serializable
data class SingScaleUiState(
    val tunnelStatus: TunnelStatus = TunnelStatus.STOPPED,
    val statusMessage: String = "未连接 · 点击开启 SingScale 组网引擎",
    val errorMessage: String? = null,
    val connectedSinceMs: Long = 0L,

    // Tailscale Account & Tailnet
    val backendState: String = "Stopped",
    val authUrl: String = "",
    val hasSavedLogin: Boolean = false,
    val tailnetName: String = "",
    val magicDnsSuffix: String = "",
    val selfPeer: TailnetPeer? = null,
    val peers: List<TailnetPeer> = emptyList(),

    // Node Selection (Manual or Smart Dynamic)
    val selectedExitPeerId: String = "",      // empty = none or auto-picked when smartAutoPickExit = true
    val selectedRelayPeerId: String = "",     // empty = none or auto-picked
    val smartAutoSelectNode: Boolean = true,  // auto-pick best Exit/Relay from candidate pool
    val activeExitPeerId: String = "",        // currently applied Exit Node after dynamic policy evaluation
    val activeRelayPeerId: String = "",       // currently applied Relay Node after dynamic policy evaluation

    // Routing Policy & Dynamic Quality State
    val policyMode: RoutingPolicyMode = RoutingPolicyMode.SMART_QUALITY,
    val dynamicTier: DynamicTier = DynamicTier.BALANCED_SPLIT,
    val thresholds: PolicyThresholds = PolicyThresholds(),
    val authConfig: TailscaleAuthConfig = TailscaleAuthConfig(),
    val peerConfigs: Map<String, PeerEndpointConfig> = emptyMap(),

    // Telemetry
    val traffic: TrafficStats = TrafficStats(),
    val activeQuality: LinkQualityMetrics = LinkQualityMetrics(),
    val decisionLog: List<PolicyDecisionEvent> = emptyList(),
    val lastGeneratedConfigJson: String = "",
    val isProbingNow: Boolean = false
) {
    val isRunning: Boolean
        get() = tunnelStatus == TunnelStatus.RUNNING

    val isLoggedIn: Boolean
        get() = backendState == "Running" ||
            (hasSavedLogin && backendState != "NeedsLogin" && authUrl.isBlank()) ||
            (selfPeer != null && selfPeer.primaryIpv4.isNotBlank() && backendState != "NeedsLogin" && authUrl.isBlank())

    val activeExitPeer: TailnetPeer?
        get() = peers.firstOrNull { it.stableId == activeExitPeerId }
            ?: peers.firstOrNull { it.exitNode }

    val activeRelayPeer: TailnetPeer?
        get() = peers.firstOrNull { it.stableId == activeRelayPeerId }

    val isAutoExitActive: Boolean
        get() = smartAutoSelectNode && selectedExitPeerId.isBlank()

    val isAutoRelayActive: Boolean
        get() = smartAutoSelectNode && selectedRelayPeerId.isBlank()

    val onlinePeersCount: Int
        get() = peers.count { it.online && !it.expired }

    val exitReadyPeersCount: Int
        get() = peers.count { it.online && !it.expired && it.canActAsExit }

    val relayReadyPeersCount: Int
        get() = peers.count { it.online && !it.expired && it.canActAsRelay }
}
