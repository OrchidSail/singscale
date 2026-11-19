package dev.singscale.app.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Evaluates real-time Tailscale Disco / URLTest link telemetry across all online peers
 * and decides:
 *  1. Which Exit Node and/or Relay Node should be active (with anti-flapping hysteresis).
 *  2. Which DynamicTier routing policy should be applied based on actual transport quality.
 */
object QualityEngine {

    data class EvaluationDecision(
        val recommendedExitPeerId: String,
        val recommendedRelayPeerId: String,
        val recommendedTier: DynamicTier,
        val activeMetrics: LinkQualityMetrics,
        val changed: Boolean,
        val reasonTitle: String,
        val reasonDetail: String,
        val badge: String,
        val isWarning: Boolean = false
    )

    fun mergeProbeWithHistory(
        previous: LinkQualityMetrics?,
        peerIp: String,
        reachable: Boolean,
        latencyMs: Double,
        minLatencyMs: Double,
        jitterMs: Double,
        lossRate: Double,
        pathTypeStr: String,
        endpoint: String,
        peerRelay: String,
        derpRegion: String,
        rawScore: Int,
        urlTestMs: Int,
        preferDirectPath: Boolean
    ): LinkQualityMetrics {
        val pathType = when (pathTypeStr.uppercase()) {
            "DIRECT" -> TransportPathType.DIRECT
            "PEER_RELAY" -> TransportPathType.PEER_RELAY
            "DERP" -> TransportPathType.DERP
            else -> TransportPathType.OFFLINE
        }

        var adjustedScore = rawScore
        if (reachable && urlTestMs > 0) {
            // Blend end-to-end HTTP 204 latency with WireGuard Disco RTT
            val httpPenalty = max(0.0, (urlTestMs - 60) * 0.08).roundToInt()
            adjustedScore = (adjustedScore * 0.8 + max(10, 100 - httpPenalty) * 0.2).roundToInt()
        }
        if (reachable && preferDirectPath && pathType == TransportPathType.DIRECT) {
            adjustedScore = min(100, adjustedScore + 4)
        }
        adjustedScore = adjustedScore.coerceIn(0, 100)

        val history = buildList {
            previous?.history?.let { addAll(it) }
            if (reachable && latencyMs > 0) {
                add(latencyMs.toFloat())
            }
        }.takeLast(28)

        return LinkQualityMetrics(
            peerIp = peerIp,
            reachable = reachable,
            latencyMs = latencyMs,
            minLatencyMs = minLatencyMs,
            urlTestMs = if (urlTestMs > 0) urlTestMs else (previous?.urlTestMs ?: -1),
            jitterMs = jitterMs,
            lossRate = lossRate,
            pathType = pathType,
            endpoint = endpoint,
            peerRelay = peerRelay,
            derpRegion = derpRegion,
            score = if (reachable) adjustedScore else 0,
            grade = LinkQualityMetrics.gradeFor(adjustedScore, reachable),
            history = history,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    fun evaluate(state: SingScaleUiState): EvaluationDecision {
        val thresholds = state.thresholds
        val peers = state.peers.map { p ->
            val cfg = state.peerConfigs[p.stableId] ?: p.customConfig
            if (cfg != null && p.customConfig != cfg) p.copy(customConfig = cfg) else p
        }

        if (state.policyMode == RoutingPolicyMode.MESH_ONLY) {
            val bestRelay = if (state.selectedRelayPeerId.isNotBlank()) {
                peers.firstOrNull { it.stableId == state.selectedRelayPeerId }
            } else if (state.smartAutoSelectNode) {
                pickOptimalRelay(state, exitPeer = null)
            } else null
            return EvaluationDecision(
                recommendedExitPeerId = "",
                recommendedRelayPeerId = bestRelay?.stableId.orEmpty(),
                recommendedTier = DynamicTier.BALANCED_SPLIT,
                activeMetrics = bestRelay?.quality ?: LinkQualityMetrics(),
                changed = state.activeExitPeerId.isNotEmpty() || state.activeRelayPeerId != bestRelay?.stableId.orEmpty(),
                reasonTitle = "仅组网互联模式",
                reasonDetail = "仅路由 100.64.0.0/10 与组网子网，公网保持本地直连",
                badge = "MESH"
            )
        }

        // 1. Exit Node Selection:
        //    - Manual override if selectedExitPeerId is set
        //    - Otherwise automatic dynamic selection across all Exit-capable peers in auto pool
        val eligibleExitCandidates = peers.filter { peer ->
            peer.online && !peer.expired && peer.inAutoPool && peer.canActAsExit
        }

        val chosenExit: TailnetPeer? = when {
            state.selectedExitPeerId.isNotBlank() -> {
                peers.firstOrNull { it.stableId == state.selectedExitPeerId }
            }
            state.smartAutoSelectNode -> {
                val currentActive = eligibleExitCandidates.firstOrNull { it.stableId == state.activeExitPeerId }
                val bestCandidate = eligibleExitCandidates.maxByOrNull { effectiveCandidateRank(it, state) }

                if (currentActive != null && bestCandidate != null && currentActive.stableId != bestCandidate.stableId) {
                    val currentScore = effectiveCandidateRank(currentActive, state)
                    val bestScore = effectiveCandidateRank(bestCandidate, state)
                    // Hysteresis prevents rapid switching unless new node is meaningfully better or current is unhealthy
                    val currentUnhealthy = !currentActive.quality.reachable ||
                        currentActive.quality.latencyMs >= thresholds.failoverLatencyMs ||
                        (currentActive.quality.lossRate * 100) >= thresholds.maxLossPercent
                    if (currentUnhealthy || (bestScore - currentScore) >= thresholds.hysteresisScoreDelta) {
                        bestCandidate
                    } else {
                        currentActive
                    }
                } else {
                    bestCandidate ?: currentActive
                }
            }
            else -> null
        }

        // 2. Relay Node Selection:
        //    - Manual override if selectedRelayPeerId is set
        //    - Otherwise automatic dynamic Relay insertion & switching when Exit is on DERP or degraded
        val chosenRelay: TailnetPeer? = when {
            state.selectedRelayPeerId.isNotBlank() -> {
                peers.firstOrNull { it.stableId == state.selectedRelayPeerId && it.stableId != chosenExit?.stableId }
            }
            state.smartAutoSelectNode -> {
                pickOptimalRelay(state, chosenExit)
            }
            else -> null
        }

        val primaryPeer = chosenExit ?: chosenRelay
        if (primaryPeer == null) {
            return EvaluationDecision(
                recommendedExitPeerId = "",
                recommendedRelayPeerId = "",
                recommendedTier = DynamicTier.BALANCED_SPLIT,
                activeMetrics = LinkQualityMetrics(),
                changed = state.activeExitPeerId.isNotEmpty() || state.activeRelayPeerId.isNotEmpty(),
                reasonTitle = "等待选择或发现可用节点",
                reasonDetail = "组网已就绪，可在「节点」页指定任意设备作为出口或中继，或启用节点 Exit/Proxy 能力",
                badge = "STANDBY"
            )
        }

        val q = primaryPeer.quality
        val hasMeasured = q.updatedAtMs > 0L
        val lossPct = (q.lossRate * 100.0).roundToInt()

        val severelyUnhealthy = (!primaryPeer.online) ||
            (hasMeasured && !q.reachable) ||
            (hasMeasured && q.latencyMs >= thresholds.failoverLatencyMs) ||
            (hasMeasured && lossPct >= thresholds.maxLossPercent)

        if (severelyUnhealthy && thresholds.failSafeDirectFallback) {
            val reason = when {
                !primaryPeer.online -> "节点 ${primaryPeer.displayName} 已离线"
                !q.reachable -> "节点 ${primaryPeer.displayName} Disco 探测超时不可达"
                lossPct >= thresholds.maxLossPercent -> "节点 ${primaryPeer.displayName} 丢包率达 $lossPct%"
                else -> "节点 ${primaryPeer.displayName} 延迟过高 (${q.latencyMs.roundToInt()} ms)"
            }
            return EvaluationDecision(
                recommendedExitPeerId = chosenExit?.stableId.orEmpty(),
                recommendedRelayPeerId = chosenRelay?.stableId.orEmpty(),
                recommendedTier = DynamicTier.FAILSAFE_DIRECT,
                activeMetrics = q,
                changed = state.dynamicTier != DynamicTier.FAILSAFE_DIRECT ||
                    state.activeExitPeerId != chosenExit?.stableId.orEmpty(),
                reasonTitle = "触发故障安全直连保护",
                reasonDetail = "$reason → 自动旁路公网流量至本地直连，防止断网并持续后台探测恢复",
                badge = "FAILSAFE",
                isWarning = true
            )
        }

        val pathType = primaryPeer.effectivePathType
        val tier = when {
            state.policyMode != RoutingPolicyMode.SMART_QUALITY -> DynamicTier.FULL_ACCELERATION

            hasMeasured && pathType == TransportPathType.DERP &&
                thresholds.derpSmartDowngrade &&
                q.latencyMs >= thresholds.highLatencyThresholdMs -> {
                DynamicTier.DERP_CONSERVATIVE
            }

            (pathType == TransportPathType.DIRECT || pathType == TransportPathType.PEER_RELAY) &&
                (!hasMeasured || (q.latencyMs <= 95.0 && lossPct <= 5)) -> {
                DynamicTier.FULL_ACCELERATION
            }

            hasMeasured && q.score >= 78 -> DynamicTier.FULL_ACCELERATION
            else -> DynamicTier.BALANCED_SPLIT
        }

        val nextExitId = chosenExit?.stableId.orEmpty()
        val nextRelayId = chosenRelay?.stableId.orEmpty()
        val changed = nextExitId != state.activeExitPeerId ||
            nextRelayId != state.activeRelayPeerId ||
            tier != state.dynamicTier

        val latencyText = if (hasMeasured && q.reachable) "${q.latencyMs.roundToInt()} ms" else "就绪"
        val relaySuffix = if (chosenRelay != null && chosenExit != null) {
            " (经 ${chosenRelay.displayName} 中继)"
        } else ""

        return EvaluationDecision(
            recommendedExitPeerId = nextExitId,
            recommendedRelayPeerId = nextRelayId,
            recommendedTier = tier,
            activeMetrics = q,
            changed = changed,
            reasonTitle = "应用策略：${tier.summary}",
            reasonDetail = "节点 ${primaryPeer.displayName}$relaySuffix · ${pathType.label} · 延迟 $latencyText · 评分 ${q.score}/${q.grade.code}",
            badge = "${pathType.badge} · ${q.grade.code}",
            isWarning = tier == DynamicTier.DERP_CONSERVATIVE
        )
    }

    private fun effectiveCandidateRank(peer: TailnetPeer, state: SingScaleUiState): Int {
        val q = peer.quality
        var rank = if (q.updatedAtMs > 0L && q.reachable) {
            q.score
        } else {
            // Heuristic before first ping completes
            when {
                peer.curAddr.isNotBlank() -> 84
                peer.peerRelay.isNotBlank() -> 75
                peer.online -> 62
                else -> 0
            }
        }
        if (peer.exitNodeOption) rank += 5
        if (peer.stableId == state.selectedExitPeerId) rank += 3
        return rank
    }

    private fun pickOptimalRelay(state: SingScaleUiState, exitPeer: TailnetPeer?): TailnetPeer? {
        val thresholds = state.thresholds
        val hydratedPeers = state.peers.map { p ->
            val cfg = state.peerConfigs[p.stableId] ?: p.customConfig
            if (cfg != null && p.customConfig != cfg) p.copy(customConfig = cfg) else p
        }
        val relayCandidates = hydratedPeers.filter { peer ->
            peer.online &&
                !peer.expired &&
                peer.inAutoPool &&
                peer.canActAsRelay &&
                peer.stableId != exitPeer?.stableId &&
                peer.effectivePathType == TransportPathType.DIRECT
        }
        if (relayCandidates.isEmpty()) return null

        // In MESH_ONLY or when no Exit node is active: pick best Peer-Relay-capable node only if some online peer is stuck on DERP
        if (exitPeer == null) {
            val anyPeerOnDerp = hydratedPeers.any { it.online && it.effectivePathType == TransportPathType.DERP }
            if (!anyPeerOnDerp) return null
            return relayCandidates
                .filter { it.hasPeerRelayCap || it.customConfig?.staticRelayEndpoint?.isNotBlank() == true }
                .maxByOrNull { effectiveCandidateRank(it, state) }
        }

        // When Exit node IS active:
        // 1. If Exit already has a healthy, low-latency Direct P2P path, bypass Relay to avoid unnecessary extra hop latency
        val exitHasMeasured = exitPeer.quality.updatedAtMs > 0L && exitPeer.quality.reachable
        val exitLossPct = (exitPeer.quality.lossRate * 100.0).roundToInt()
        val exitIsCleanDirect = exitPeer.effectivePathType == TransportPathType.DIRECT &&
            (!exitHasMeasured || (
                exitPeer.quality.latencyMs < thresholds.highLatencyThresholdMs &&
                    exitLossPct < 5 &&
                    exitPeer.quality.score >= 82
                ))
        if (exitIsCleanDirect) {
            return null
        }

        // 2. Exit is on DERP or degraded Direct link: select best Direct Relay candidate with anti-flapping hysteresis
        val validRelays = relayCandidates.filter { relay ->
            val relayMeasured = relay.quality.updatedAtMs > 0L && relay.quality.reachable
            if (!exitHasMeasured || !relayMeasured) {
                true
            } else if (exitPeer.effectivePathType == TransportPathType.DERP) {
                // Relay is Direct while Exit is DERP: require Relay leg to be meaningfully faster than DERP RTT
                relay.quality.latencyMs + 15.0 < exitPeer.quality.latencyMs || relay.quality.score > exitPeer.quality.score
            } else {
                // Degraded Direct Exit: only chain via proxy-capable Relay if Relay leg is much cleaner
                relay.hasProxyGateway &&
                    relay.quality.latencyMs + 25.0 < exitPeer.quality.latencyMs &&
                    relay.quality.score >= exitPeer.quality.score + 6
            }
        }
        if (validRelays.isEmpty()) return null

        val currentRelay = validRelays.firstOrNull { it.stableId == state.activeRelayPeerId }
        val bestRelay = validRelays.maxByOrNull { effectiveCandidateRank(it, state) } ?: return null
        if (currentRelay != null && currentRelay.stableId != bestRelay.stableId) {
            val delta = effectiveCandidateRank(bestRelay, state) - effectiveCandidateRank(currentRelay, state)
            if (delta < thresholds.hysteresisScoreDelta && currentRelay.quality.reachable) {
                return currentRelay
            }
        }
        return bestRelay
    }
}
