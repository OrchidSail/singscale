package dev.singscale.app.core

import android.content.Context
import dev.singscale.app.SingScaleApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong

/**
 * Single source of truth for SingScale UI, VpnService, Home Screen Widget, and Quick Settings Tile.
 * Persists user preferences, Tailscale auth settings, routing policies, and peer role assignments.
 */
object SingScaleRepository {

    private const val PREFS_NAME = "singscale_prefs"
    private const val KEY_PERSISTED = "persisted_config_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val eventIdGen = AtomicLong(1L)
    private var appContext: Context? = null
    @Volatile
    private var lastSnapshotPersistMs: Long = 0L
    @Volatile
    private var lastPersistedIdentitySig: String = ""

    private val _state = MutableStateFlow(SingScaleUiState())
    val state: StateFlow<SingScaleUiState> = _state.asStateFlow()

    private val v1LegacyDirectDefaults = setOf("cn", "baidu.com", "qq.com", "taobao.com", "bilibili.com", "163.com")
    private val v1LegacyProxyDefaults = setOf("google.com", "github.com", "youtube.com", "openai.com", "anthropic.com", "wikipedia.org")

    @Serializable
    private data class PersistedSettings(
        val selectedExitPeerId: String = "",
        val selectedRelayPeerId: String = "",
        val smartAutoSelectNode: Boolean = true,
        val policyMode: RoutingPolicyMode = RoutingPolicyMode.SMART_QUALITY,
        val thresholds: PolicyThresholds = PolicyThresholds(),
        val authConfig: TailscaleAuthConfig = TailscaleAuthConfig(),
        val peerConfigs: Map<String, PeerEndpointConfig> = emptyMap(),
        val rulesMigratedV2: Boolean = false,
        val powerMigratedV3: Boolean = false,
        // Cached last known tailnet summary for instant UI & Widget display before VPN starts
        val hasSavedLogin: Boolean = false,
        val cachedTailnetName: String = "",
        val cachedSelfName: String = "",
        val cachedSelfIp: String = "",
        val cachedPeers: List<TailnetPeer> = emptyList()
    )

    fun init(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        val diskHasLogin = SingScaleApp.hasPersistedTailscaleState(ctx)
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PERSISTED, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val saved = json.decodeFromString<PersistedSettings>(raw)
                val cachedSelf = if (diskHasLogin && (saved.cachedSelfName.isNotBlank() || saved.cachedSelfIp.isNotBlank())) {
                    TailnetPeer(
                        stableId = "cached-self",
                        hostName = saved.cachedSelfName,
                        tailscaleIPs = listOfNotNull(saved.cachedSelfIp.takeIf { it.isNotBlank() })
                    )
                } else null
                val v2Thresholds = if (!saved.rulesMigratedV2) {
                    val cleanedDirect = if (saved.thresholds.customDirectDomains.toSet() == v1LegacyDirectDefaults) {
                        emptyList()
                    } else {
                        saved.thresholds.customDirectDomains
                    }
                    val cleanedProxy = if (saved.thresholds.customProxyDomains.toSet() == v1LegacyProxyDefaults) {
                        emptyList()
                    } else {
                        saved.thresholds.customProxyDomains
                    }
                    val adjustedLatency = if (saved.thresholds.directAcceptableLatencyMs == 300) 220 else saved.thresholds.directAcceptableLatencyMs
                    saved.thresholds.copy(
                        customDirectDomains = cleanedDirect,
                        customProxyDomains = cleanedProxy,
                        directAcceptableLatencyMs = adjustedLatency
                    )
                } else {
                    saved.thresholds
                }
                val migratedThresholds = if (!saved.powerMigratedV3 && v2Thresholds.probeIntervalSec <= 15) {
                    v2Thresholds.copy(probeIntervalSec = 45)
                } else {
                    v2Thresholds
                }
                _state.update {
                    it.copy(
                        selectedExitPeerId = saved.selectedExitPeerId,
                        selectedRelayPeerId = saved.selectedRelayPeerId,
                        activeExitPeerId = saved.selectedExitPeerId,
                        activeRelayPeerId = saved.selectedRelayPeerId,
                        smartAutoSelectNode = saved.smartAutoSelectNode,
                        policyMode = saved.policyMode,
                        thresholds = migratedThresholds,
                        authConfig = saved.authConfig,
                        peerConfigs = saved.peerConfigs,
                        hasSavedLogin = diskHasLogin,
                        tailnetName = if (diskHasLogin) saved.cachedTailnetName else "",
                        selfPeer = cachedSelf,
                        peers = if (diskHasLogin) saved.cachedPeers.map { p -> p.copy(online = false, active = false) } else emptyList(),
                        statusMessage = if (diskHasLogin && saved.cachedTailnetName.isNotBlank()) {
                            "已就绪 (${saved.cachedTailnetName}) · 点击一键开启组网"
                        } else {
                            it.statusMessage
                        }
                    )
                }
            }
        } else if (diskHasLogin) {
            _state.update {
                it.copy(
                    hasSavedLogin = true,
                    statusMessage = "已保存设备授权 · 点击一键恢复组网连接"
                )
            }
        }
    }

    private fun persistCurrent() {
        val ctx = appContext ?: return
        val s = _state.value
        val compactPeers = s.peers.take(50).map { p ->
            p.copy(
                rxBytes = 0L,
                txBytes = 0L,
                lastSeen = 0L,
                lastHandshake = 0L,
                quality = LinkQualityMetrics()
            )
        }
        val payload = PersistedSettings(
            selectedExitPeerId = s.selectedExitPeerId,
            selectedRelayPeerId = s.selectedRelayPeerId,
            smartAutoSelectNode = s.smartAutoSelectNode,
            policyMode = s.policyMode,
            thresholds = s.thresholds,
            authConfig = s.authConfig,
            peerConfigs = s.peerConfigs,
            rulesMigratedV2 = true,
            powerMigratedV3 = true,
            hasSavedLogin = s.hasSavedLogin,
            cachedTailnetName = s.tailnetName,
            cachedSelfName = s.selfPeer?.displayName.orEmpty(),
            cachedSelfIp = s.selfPeer?.primaryIpv4.orEmpty(),
            cachedPeers = compactPeers
        )
        lastSnapshotPersistMs = System.currentTimeMillis()
        lastPersistedIdentitySig = buildIdentitySignature(s)
        runCatching {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_PERSISTED, json.encodeToString(payload))
                .apply()
        }
    }

    private fun buildIdentitySignature(s: SingScaleUiState): String = buildString {
        append(s.hasSavedLogin).append('|')
        append(s.tailnetName).append('|')
        append(s.selfPeer?.displayName.orEmpty()).append('|')
        append(s.selfPeer?.primaryIpv4.orEmpty()).append('|')
        s.peers.take(50).forEach { p ->
            append(p.stableId).append(':').append(p.primaryIpv4).append(',')
        }
    }

    fun updateTunnelStatus(
        status: TunnelStatus,
        message: String,
        error: String? = null
    ) {
        _state.update { prev ->
            val connectedSince = when {
                status == TunnelStatus.RUNNING && prev.connectedSinceMs == 0L -> System.currentTimeMillis()
                status == TunnelStatus.STOPPED || status == TunnelStatus.ERROR -> 0L
                else -> prev.connectedSinceMs
            }
            prev.copy(
                tunnelStatus = status,
                statusMessage = message,
                errorMessage = error,
                connectedSinceMs = connectedSince,
                traffic = if (status == TunnelStatus.STOPPED) TrafficStats() else prev.traffic
            )
        }
    }

    fun updateTailnetSnapshot(
        backendState: String,
        authUrl: String,
        hasSavedLogin: Boolean,
        tailnetName: String,
        magicDnsSuffix: String,
        selfPeer: TailnetPeer?,
        peers: List<TailnetPeer>,
        reportedExitNodeId: String
    ) {
        _state.update { prev ->
            // Preserve existing quality metrics, gateway discovery state & custom configs across peer refreshes
            val prevById = prev.peers.associateBy { it.stableId }
            val freshMergedPeers = peers.map { fresh ->
                val old = prevById[fresh.stableId]
                val custom = prev.peerConfigs[fresh.stableId]
                val keptProbeStatus = when {
                    !fresh.online -> GatewayProbeStatus.UNPROBED
                    old != null && old.online -> old.gatewayProbeStatus
                    else -> GatewayProbeStatus.UNPROBED
                }
                fresh.copy(
                    quality = old?.quality ?: fresh.quality,
                    customConfig = custom,
                    gatewayProbeStatus = keptProbeStatus,
                    discoveredProxyPorts = old?.discoveredProxyPorts.orEmpty()
                )
            }

            val effectiveSavedLogin = hasSavedLogin ||
                (backendState == "Running" && selfPeer != null && selfPeer.primaryIpv4.isNotBlank())

            // Keep cached peer list visible during the brief ~1s session-restore handshake
            val mergedPeers = if (freshMergedPeers.isEmpty() && effectiveSavedLogin && backendState != "Running") {
                prev.peers
            } else {
                freshMergedPeers
            }

            val hasAuthenticatedTailnet = backendState == "Running" ||
                (effectiveSavedLogin && backendState != "NeedsLogin" && authUrl.isBlank()) ||
                (selfPeer != null && selfPeer.primaryIpv4.isNotBlank() && backendState != "NeedsLogin" && authUrl.isBlank())

            val nextStatus = when {
                prev.tunnelStatus == TunnelStatus.STOPPED || prev.tunnelStatus == TunnelStatus.STOPPING -> prev.tunnelStatus
                !hasAuthenticatedTailnet -> TunnelStatus.NEEDS_LOGIN
                else -> TunnelStatus.RUNNING
            }
            val effectiveTailnet = tailnetName.ifBlank { prev.tailnetName }
            val nextStatusMsg = when (nextStatus) {
                TunnelStatus.NEEDS_LOGIN -> if (authUrl.isNotBlank()) {
                    "等待完成 Tailscale 账号登录授权"
                } else {
                    "正在向控制面获取 Tailscale 登录链接…"
                }
                TunnelStatus.RUNNING -> if (backendState != "Running" && effectiveSavedLogin) {
                    "正在恢复 ${effectiveTailnet.ifBlank { "Tailscale" }} 设备会话…"
                } else {
                    val activeNode = mergedPeers.firstOrNull {
                        it.stableId == prev.activeExitPeerId.ifBlank { reportedExitNodeId }
                    } ?: mergedPeers.firstOrNull { it.stableId == prev.activeRelayPeerId }
                    if (activeNode != null) {
                        "已连接 ${effectiveTailnet.ifBlank { "Tailnet" }} · 节点 ${activeNode.displayName}"
                    } else {
                        "已接入 ${effectiveTailnet.ifBlank { "Tailnet" }} (${mergedPeers.count { it.online }} 台设备在线)"
                    }
                }
                else -> prev.statusMessage
            }
            prev.copy(
                tunnelStatus = nextStatus,
                statusMessage = nextStatusMsg,
                backendState = backendState,
                hasSavedLogin = effectiveSavedLogin,
                authUrl = if (backendState == "Running") "" else authUrl.ifBlank { if (!hasAuthenticatedTailnet) prev.authUrl else "" },
                tailnetName = effectiveTailnet,
                magicDnsSuffix = magicDnsSuffix.ifBlank { prev.magicDnsSuffix },
                selfPeer = selfPeer ?: prev.selfPeer,
                peers = mergedPeers,
                activeExitPeerId = prev.activeExitPeerId.ifBlank { reportedExitNodeId }
            )
        }
        val updated = _state.value
        val newSig = buildIdentitySignature(updated)
        val now = System.currentTimeMillis()
        if (newSig != lastPersistedIdentitySig && (lastSnapshotPersistMs == 0L || now - lastSnapshotPersistMs >= 30_000L || updated.hasSavedLogin != (lastPersistedIdentitySig.startsWith("true|")))) {
            persistCurrent()
        }
    }

    fun updatePeerQualities(updatedByIp: Map<String, LinkQualityMetrics>) {
        _state.update { prev ->
            val updatedPeers = prev.peers.map { peer ->
                val metric = peer.tailscaleIPs.firstNotNullOfOrNull { updatedByIp[it] }
                if (metric != null) peer.copy(quality = metric) else peer
            }
            val activePeer = updatedPeers.firstOrNull { it.stableId == prev.activeExitPeerId }
                ?: updatedPeers.firstOrNull { it.stableId == prev.activeRelayPeerId }
            prev.copy(
                peers = updatedPeers,
                activeQuality = activePeer?.quality ?: prev.activeQuality
            )
        }
    }

    fun setProbing(probing: Boolean) {
        _state.update { it.copy(isProbingNow = probing) }
    }

    fun updateTraffic(stats: TrafficStats) {
        _state.update { it.copy(traffic = stats) }
    }

    fun updateLastConfigJson(configJson: String) {
        _state.update { it.copy(lastGeneratedConfigJson = configJson) }
    }

    fun selectExitPeer(stableId: String, disableAutoPick: Boolean = false) {
        _state.update { prev ->
            val cleanedRelay = if (stableId.isNotBlank() && prev.selectedRelayPeerId == stableId) "" else prev.selectedRelayPeerId
            prev.copy(
                selectedExitPeerId = stableId,
                activeExitPeerId = stableId.ifBlank { prev.activeExitPeerId },
                selectedRelayPeerId = cleanedRelay,
                activeRelayPeerId = if (stableId.isNotBlank() && prev.activeRelayPeerId == stableId) "" else prev.activeRelayPeerId,
                smartAutoSelectNode = if (disableAutoPick && stableId.isNotBlank()) prev.smartAutoSelectNode else true
            )
        }
        persistCurrent()
    }

    fun selectRelayPeer(stableId: String) {
        _state.update { prev ->
            val cleanedExit = if (stableId.isNotBlank() && prev.selectedExitPeerId == stableId) "" else prev.selectedExitPeerId
            prev.copy(
                selectedRelayPeerId = stableId,
                activeRelayPeerId = stableId.ifBlank { prev.activeRelayPeerId },
                selectedExitPeerId = cleanedExit,
                activeExitPeerId = if (stableId.isNotBlank() && prev.activeExitPeerId == stableId) "" else prev.activeExitPeerId
            )
        }
        persistCurrent()
    }

    fun setSmartAutoSelectNode(enabled: Boolean) {
        _state.update { prev ->
            if (enabled) {
                prev.copy(
                    smartAutoSelectNode = true,
                    selectedExitPeerId = "",
                    selectedRelayPeerId = ""
                )
            } else {
                prev.copy(smartAutoSelectNode = false)
            }
        }
        persistCurrent()
    }

    fun markPeersProbingGateway(peerIds: Set<String>) {
        if (peerIds.isEmpty()) return
        _state.update { prev ->
            prev.copy(
                peers = prev.peers.map { p ->
                    if (p.stableId in peerIds) p.copy(gatewayProbeStatus = GatewayProbeStatus.PROBING) else p
                }
            )
        }
    }

    fun applyPeerGatewayDiscovery(
        stableId: String,
        discoveredPorts: List<String>,
        bestProtocol: String,
        bestPort: Int
    ): Boolean {
        var autoConfiguredNewPort = false
        _state.update { prev ->
            val existingCfg = prev.peerConfigs[stableId]
            val shouldAutoBindPort = bestPort > 0 &&
                bestProtocol.isNotBlank() &&
                existingCfg?.gatewayMode != PeerGatewayMode.NATIVE_ONLY &&
                (existingCfg == null || existingCfg.proxyPort == 0)

            val updatedCfg = if (shouldAutoBindPort) {
                autoConfiguredNewPort = true
                (existingCfg ?: PeerEndpointConfig(stableId = stableId)).copy(
                    proxyProtocol = bestProtocol,
                    proxyPort = bestPort
                )
            } else {
                existingCfg
            }
            val newCfgMap = if (updatedCfg != null) {
                prev.peerConfigs + (stableId to updatedCfg)
            } else {
                prev.peerConfigs
            }
            val updatedPeers = prev.peers.map { p ->
                if (p.stableId == stableId) {
                    p.copy(
                        customConfig = updatedCfg ?: p.customConfig,
                        gatewayProbeStatus = GatewayProbeStatus.PROBED,
                        discoveredProxyPorts = discoveredPorts
                    )
                } else {
                    p
                }
            }
            prev.copy(
                peerConfigs = newCfgMap,
                peers = updatedPeers
            )
        }
        if (autoConfiguredNewPort) {
            persistCurrent()
        }
        return autoConfiguredNewPort
    }

    fun setPolicyMode(mode: RoutingPolicyMode) {
        _state.update { it.copy(policyMode = mode) }
        appendDecision(
            title = "切换路由策略为「${mode.title}」",
            detail = mode.subtitle,
            badge = mode.badge
        )
        persistCurrent()
    }

    fun updateThresholds(transform: (PolicyThresholds) -> PolicyThresholds) {
        _state.update { it.copy(thresholds = transform(it.thresholds)) }
        persistCurrent()
    }

    fun updateAuthConfig(config: TailscaleAuthConfig) {
        _state.update { it.copy(authConfig = config) }
        persistCurrent()
    }

    fun updatePeerEndpointConfig(stableId: String, transform: (PeerEndpointConfig) -> PeerEndpointConfig) {
        _state.update { prev ->
            val current = prev.peerConfigs[stableId] ?: PeerEndpointConfig(stableId = stableId)
            val updated = transform(current)
            val newMap = prev.peerConfigs + (stableId to updated)
            val updatedPeers = prev.peers.map { p ->
                if (p.stableId == stableId) p.copy(customConfig = updated) else p
            }
            prev.copy(peerConfigs = newMap, peers = updatedPeers)
        }
        persistCurrent()
    }

    fun applyDynamicDecision(decision: QualityEngine.EvaluationDecision) {
        _state.update { prev ->
            prev.copy(
                activeExitPeerId = decision.recommendedExitPeerId,
                activeRelayPeerId = decision.recommendedRelayPeerId,
                dynamicTier = decision.recommendedTier,
                activeQuality = decision.activeMetrics
            )
        }
        if (decision.changed) {
            appendDecision(
                title = decision.reasonTitle,
                detail = decision.reasonDetail,
                badge = decision.badge,
                isWarning = decision.isWarning
            )
        }
    }

    fun appendDecision(
        title: String,
        detail: String,
        badge: String,
        isWarning: Boolean = false
    ) {
        val event = PolicyDecisionEvent(
            id = eventIdGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            title = title,
            detail = detail,
            badge = badge,
            isWarning = isWarning
        )
        _state.update { prev ->
            prev.copy(decisionLog = (listOf(event) + prev.decisionLog).take(40))
        }
    }

    fun onAuthUrlReceived(url: String) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return
        _state.update { prev ->
            if (prev.backendState == "Running") prev
            else prev.copy(
                tunnelStatus = TunnelStatus.NEEDS_LOGIN,
                backendState = "NeedsLogin",
                authUrl = trimmed,
                statusMessage = "等待完成 Tailscale 账号登录授权"
            )
        }
    }

    fun clearAuthStateOnLogout() {
        _state.update { prev ->
            prev.copy(
                backendState = "NeedsLogin",
                hasSavedLogin = false,
                authUrl = "",
                tailnetName = "",
                magicDnsSuffix = "",
                selfPeer = null,
                peers = emptyList(),
                activeExitPeerId = "",
                activeRelayPeerId = ""
            )
        }
        persistCurrent()
    }
}
