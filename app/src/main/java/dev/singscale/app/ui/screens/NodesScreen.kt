package dev.singscale.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.singscale.app.core.GatewayProbeStatus
import dev.singscale.app.core.PeerEndpointConfig
import dev.singscale.app.core.PeerGatewayMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TailnetPeer
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.components.GlassIconButton
import dev.singscale.app.ui.components.GlassSearchBar
import dev.singscale.app.ui.components.MicroPillBadge
import dev.singscale.app.ui.components.QualityScoreRing
import dev.singscale.app.ui.components.SparklineChart
import dev.singscale.app.ui.components.singScaleSwitchColors
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.ObsidianSurface
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import dev.singscale.app.ui.theme.accentColor
import kotlin.math.roundToInt

/**
 * Progressive-Disclosure Nodes Screen:
 * 1. Compact Auto-Pilot switch banner (replaces bulky multi-box summary)
 * 2. Active/Capable Gateways highlighted first — concise 1-row card, tap to expand RTT chart & lock buttons
 * 3. Online Mesh Devices shown as clean, scannable rows
 * 4. Offline Devices folded into a collapsible group by default (eliminates 60%+ dead noise!)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    state: SingScaleUiState,
    onStartInteractiveLogin: () -> Unit
) {
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var expandedPeerId by rememberSaveable { mutableStateOf<String?>(null) }
    var offlineExpanded by rememberSaveable { mutableStateOf(false) }
    var editingPeer by remember { mutableStateOf<TailnetPeer?>(null) }

    val anyProbing = state.isProbingNow || state.peers.any { it.gatewayProbeStatus == GatewayProbeStatus.PROBING }

    val matchedPeers = remember(state.peers, query) {
        if (query.isBlank()) {
            state.peers
        } else {
            state.peers.filter { peer ->
                peer.displayName.contains(query, ignoreCase = true) ||
                    peer.hostName.contains(query, ignoreCase = true) ||
                    peer.tailscaleIPs.any { it.contains(query, ignoreCase = true) } ||
                    peer.os.contains(query, ignoreCase = true)
            }
        }
    }

    val gatewayPeers = remember(matchedPeers, state.activeExitPeerId, state.activeRelayPeerId) {
        matchedPeers
            .filter {
                (it.online && (it.canActAsExit || it.canActAsRelay)) ||
                    it.stableId == state.activeExitPeerId ||
                    it.stableId == state.activeRelayPeerId
            }
            .sortedWith(
                compareByDescending<TailnetPeer> {
                    it.stableId == state.activeExitPeerId || it.stableId == state.activeRelayPeerId
                }.thenByDescending { it.quality.score }
            )
    }

    val gatewayIds = remember(gatewayPeers) { gatewayPeers.map { it.stableId }.toSet() }

    val onlineMeshPeers = remember(matchedPeers, gatewayIds) {
        matchedPeers
            .filter { it.online && it.stableId !in gatewayIds }
            .sortedBy { it.displayName.lowercase() }
    }

    val offlinePeers = remember(matchedPeers, gatewayIds) {
        matchedPeers
            .filter { !it.online && it.stableId !in gatewayIds }
            .sortedBy { it.displayName.lowercase() }
    }

    val offlineChevronRot by animateFloatAsState(
        targetValue = if (offlineExpanded || query.isNotBlank()) 180f else 0f,
        label = "offlineChevron"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Clean Header + On-Demand Search & Refresh
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "组网节点",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = "搜索节点",
                        activeHighlight = searchVisible || query.isNotEmpty(),
                        onClick = {
                            if (searchVisible && query.isEmpty()) searchVisible = false else searchVisible = true
                        }
                    )
                    if (anyProbing) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(ObsidianElevated),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = CyanPrimary
                            )
                        }
                    } else {
                        GlassIconButton(
                            icon = Icons.Rounded.Refresh,
                            contentDescription = "扫描与测速",
                            enabled = state.tunnelStatus == TunnelStatus.RUNNING,
                            onClick = { SingScaleVpnService.triggerProbeNow(context) }
                        )
                    }
                }
            }
        }

        // 2. Expandable Search Bar (Hidden until tapped!)
        item {
            AnimatedVisibility(
                visible = searchVisible || query.isNotEmpty(),
                enter = expandVertically() + fadeIn(tween(150)),
                exit = shrinkVertically() + fadeOut(tween(120))
            ) {
                GlassSearchBar(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索设备名称、100.x IP 或系统…",
                    autoFocus = searchVisible,
                    onDismissIfEmpty = { searchVisible = false }
                )
            }
        }

        // 3. Compact 1-Row Auto-Pilot Card
        item {
            CompactAutoPilotCard(
                state = state,
                anyProbing = anyProbing,
                onToggleFullAuto = { enabled ->
                    SingScaleRepository.setSmartAutoSelectNode(enabled)
                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                }
            )
        }

        if (matchedPeers.isEmpty()) {
            item {
                EmptyNodesPlaceholder(
                    isLoggedIn = state.isLoggedIn,
                    isTunnelRunning = state.tunnelStatus == TunnelStatus.RUNNING,
                    onStartLogin = onStartInteractiveLogin
                )
            }
        } else {
            // Group 1: Active / Capable Gateways (Highlighted)
            if (gatewayPeers.isNotEmpty()) {
                item {
                    SectionHeaderLabel("可用出口与中继 (${gatewayPeers.size})")
                }
                items(gatewayPeers, key = { "gw_${it.stableId}" }) { peer ->
                    ExpandablePeerCard(
                        peer = peer,
                        expanded = expandedPeerId == peer.stableId,
                        isActiveExit = state.activeExitPeerId == peer.stableId,
                        isPinnedExit = state.selectedExitPeerId == peer.stableId,
                        isActiveRelay = state.activeRelayPeerId == peer.stableId,
                        isPinnedRelay = state.selectedRelayPeerId == peer.stableId,
                        onToggleExpand = {
                            expandedPeerId = if (expandedPeerId == peer.stableId) null else peer.stableId
                        },
                        onToggleExit = {
                            val clearing = state.selectedExitPeerId == peer.stableId
                            SingScaleRepository.selectExitPeer(
                                stableId = if (clearing) "" else peer.stableId,
                                disableAutoPick = false
                            )
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        },
                        onToggleRelay = {
                            val clearing = state.selectedRelayPeerId == peer.stableId
                            SingScaleRepository.selectRelayPeer(if (clearing) "" else peer.stableId)
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        },
                        onConfigureGateway = { editingPeer = peer }
                    )
                }
            }

            // Group 2: Online Mesh Devices (Clean Scannable Rows)
            if (onlineMeshPeers.isNotEmpty()) {
                item {
                    SectionHeaderLabel("在线组网设备 (${onlineMeshPeers.size})")
                }
                items(onlineMeshPeers, key = { "mesh_${it.stableId}" }) { peer ->
                    ExpandablePeerCard(
                        peer = peer,
                        expanded = expandedPeerId == peer.stableId,
                        isActiveExit = false,
                        isPinnedExit = state.selectedExitPeerId == peer.stableId,
                        isActiveRelay = false,
                        isPinnedRelay = state.selectedRelayPeerId == peer.stableId,
                        onToggleExpand = {
                            expandedPeerId = if (expandedPeerId == peer.stableId) null else peer.stableId
                        },
                        onToggleExit = {
                            val clearing = state.selectedExitPeerId == peer.stableId
                            SingScaleRepository.selectExitPeer(
                                stableId = if (clearing) "" else peer.stableId,
                                disableAutoPick = false
                            )
                            if (!clearing && peer.primaryIpv4.isNotBlank()) {
                                SingScaleVpnService.triggerDetectPeerPorts(context, peer.stableId, peer.primaryIpv4)
                            }
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        },
                        onToggleRelay = {
                            val clearing = state.selectedRelayPeerId == peer.stableId
                            SingScaleRepository.selectRelayPeer(if (clearing) "" else peer.stableId)
                            if (!clearing && peer.primaryIpv4.isNotBlank()) {
                                SingScaleVpnService.triggerDetectPeerPorts(context, peer.stableId, peer.primaryIpv4)
                            }
                            SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        },
                        onConfigureGateway = { editingPeer = peer }
                    )
                }
            }

            // Group 3: Offline Devices — Folded Away by Default!
            if (offlinePeers.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { offlineExpanded = !offlineExpanded }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "离线设备 (${offlinePeers.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = TextMuted
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (offlineExpanded || query.isNotBlank()) "收起" else "展开",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Icon(
                                imageVector = Icons.Rounded.KeyboardArrowDown,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier
                                    .size(17.dp)
                                    .graphicsLayer { rotationZ = offlineChevronRot }
                            )
                        }
                    }
                }

                if (offlineExpanded || query.isNotBlank()) {
                    items(offlinePeers, key = { "off_${it.stableId}" }) { peer ->
                        OfflinePeerRow(
                            peer = peer,
                            onClick = { editingPeer = peer }
                        )
                    }
                }
            }
        }
    }

    editingPeer?.let { target ->
        val freshTarget = state.peers.firstOrNull { it.stableId == target.stableId } ?: target
        ModalBottomSheet(
            onDismissRequest = { editingPeer = null },
            containerColor = ObsidianSurface
        ) {
            PeerGatewayConfigSheet(
                peer = freshTarget,
                existing = state.peerConfigs[freshTarget.stableId] ?: PeerEndpointConfig(stableId = freshTarget.stableId),
                onScanPorts = {
                    if (freshTarget.primaryIpv4.isNotBlank()) {
                        SingScaleVpnService.triggerDetectPeerPorts(context, freshTarget.stableId, freshTarget.primaryIpv4)
                    }
                },
                onSave = { updated ->
                    SingScaleRepository.updatePeerEndpointConfig(freshTarget.stableId) { updated }
                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                    editingPeer = null
                }
            )
        }
    }
}

@Composable
private fun SectionHeaderLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        modifier = Modifier.padding(top = 4.dp, start = 2.dp)
    )
}

@Composable
private fun CompactAutoPilotCard(
    state: SingScaleUiState,
    anyProbing: Boolean,
    onToggleFullAuto: (Boolean) -> Unit
) {
    val hasManualLock = state.selectedExitPeerId.isNotBlank() || state.selectedRelayPeerId.isNotBlank()
    val fullAutoActive = state.smartAutoSelectNode && !hasManualLock
    val activeExit = state.activeExitPeer

    val statusSub = when {
        activeExit != null -> {
            val modeStr = if (state.isAutoExitActive) "自动优选" else "手动锁定"
            val rtt = if (activeExit.quality.reachable && activeExit.quality.latencyMs > 0) {
                " · ${activeExit.quality.latencyMs.roundToInt()} ms"
            } else ""
            "$modeStr：${activeExit.displayName}$rtt"
        }
        anyProbing -> "正在自动探测在线节点的出口与中继能力…"
        !state.isLoggedIn -> "登录组网后自动识别可用出口与中继"
        else -> "${state.onlinePeersCount} 台在线 · 公网本地直连"
    }

    GlassCard(
        cornerRadius = 20.dp,
        accentTint = if (fullAutoActive) CyanPrimary else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(CyanPrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = CyanBright,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "全自动优选出口与中继",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    text = statusSub,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = fullAutoActive,
                onCheckedChange = onToggleFullAuto,
                colors = singScaleSwitchColors()
            )
        }
    }
}

/**
 * Clean 1-row node card by default; taps smoothly expand RTT sparkline, capability details, and lock buttons.
 */
@Composable
private fun ExpandablePeerCard(
    peer: TailnetPeer,
    expanded: Boolean,
    isActiveExit: Boolean,
    isPinnedExit: Boolean,
    isActiveRelay: Boolean,
    isPinnedRelay: Boolean,
    onToggleExpand: () -> Unit,
    onToggleExit: () -> Unit,
    onToggleRelay: () -> Unit,
    onConfigureGateway: () -> Unit
) {
    val pathAccent = peer.effectivePathType.accentColor()
    val roleAccent = when {
        isActiveExit || isPinnedExit -> EmeraldGood
        isActiveRelay || isPinnedRelay -> IndigoAccent
        else -> null
    }
    val chevronRot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "peerChevron"
    )

    val subtitleText = buildString {
        append(peer.primaryIpv4.ifBlank { peer.os })
        if (isActiveExit || isPinnedExit) {
            append(if (isPinnedExit) " · 已锁定出口" else " · 当前出口")
        } else if (isActiveRelay || isPinnedRelay) {
            append(if (isPinnedRelay) " · 已锁定中继" else " · 当前中继")
        } else if (peer.canActAsExit || peer.canActAsRelay) {
            append(" · ${peer.shortRoleTag}")
        }
    }

    GlassCard(
        cornerRadius = 18.dp,
        accentTint = roleAccent,
        highlightBorderColor = roleAccent,
        contentPadding = PaddingValues(0.dp)
    ) {
        // Primary Clean Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 15.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val iconColor = roleAccent ?: CyanBright
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconColor.copy(alpha = 0.13f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = peerOsIcon(peer.os),
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.labelSmall,
                    color = roleAccent ?: TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (peer.quality.reachable && peer.quality.latencyMs > 0) {
                MicroPillBadge(
                    text = "${peer.quality.latencyMs.roundToInt()} ms",
                    color = pathAccent,
                    filled = isActiveExit || isActiveRelay
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起" else "展开",
                tint = TextMuted,
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = chevronRot }
            )
        }

        // Folded Secondary Controls & Telemetry
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(160)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 15.dp, end = 15.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))

                // Score + Sparkline Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityScoreRing(
                        score = peer.quality.score,
                        grade = peer.quality.grade,
                        ringSize = 46.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${peer.effectivePathType.label} · ${peer.exitCapabilityBadge}",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "中继：${peer.relayCapabilityBadge}",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (peer.quality.history.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        SparklineChart(
                            samples = peer.quality.history,
                            lineColor = pathAccent,
                            modifier = Modifier
                                .width(84.dp)
                                .height(28.dp)
                        )
                    }
                }

                // Action Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isActiveExit || isPinnedExit) {
                        Button(
                            onClick = onToggleExit,
                            colors = ButtonDefaults.buttonColors(containerColor = EmeraldGood),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Public, contentDescription = null, tint = Color(0xFF021E14), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isPinnedExit) "解除锁定" else "当前出口",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF021E14),
                                maxLines = 1
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onToggleExit,
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (peer.canActAsExit) "设为出口" else "探测出口",
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1
                            )
                        }
                    }

                    if (isActiveRelay || isPinnedRelay) {
                        Button(
                            onClick = onToggleRelay,
                            colors = ButtonDefaults.buttonColors(containerColor = IndigoAccent),
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isPinnedRelay) "解除中继" else "当前中继",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onToggleRelay,
                            shape = RoundedCornerShape(11.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (peer.canActAsRelay) "设为中继" else "探测中继",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                maxLines = 1
                            )
                        }
                    }

                    IconButton(
                        onClick = onConfigureGateway,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(ObsidianElevated)
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = "高级网关配置", tint = CyanBright, modifier = Modifier.size(17.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflinePeerRow(
    peer: TailnetPeer,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ObsidianElevated.copy(alpha = 0.35f))
            .border(0.8.dp, ObsidianBorder.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = peerOsIcon(peer.os),
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(17.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = peer.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = peer.primaryIpv4,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted
        )
    }
}

@Composable
private fun EmptyNodesPlaceholder(
    isLoggedIn: Boolean,
    isTunnelRunning: Boolean,
    onStartLogin: () -> Unit
) {
    GlassCard(contentPadding = PaddingValues(28.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Devices,
                contentDescription = null,
                tint = CyanPrimary,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (!isLoggedIn && !isTunnelRunning) "尚未连接组网" else "未找到匹配节点",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (!isLoggedIn && !isTunnelRunning) {
                    "登录 Tailscale 后将自动列出组网设备并探测出口能力"
                } else {
                    "请更换搜索关键词重试"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            if (!isLoggedIn && !isTunnelRunning) {
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onStartLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("启动并登录", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PeerGatewayConfigSheet(
    peer: TailnetPeer,
    existing: PeerEndpointConfig,
    onScanPorts: () -> Unit,
    onSave: (PeerEndpointConfig) -> Unit
) {
    var mode by remember { mutableStateOf(existing.gatewayMode) }
    var protocol by remember { mutableStateOf(existing.proxyProtocol) }
    var portText by remember { mutableStateOf(if (existing.proxyPort > 0) existing.proxyPort.toString() else "") }
    var staticRelay by remember { mutableStateOf(existing.staticRelayEndpoint) }
    var inAutoPool by remember { mutableStateOf(existing.inAutoPool) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)
    ) {
        Text(
            text = "网关配置 · ${peer.displayName}",
            style = MaterialTheme.typography.titleLarge,
            color = TextPrimary
        )
        Text(
            text = "出口：${peer.exitCapabilityBadge}  ·  中继：${peer.relayCapabilityBadge}",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        FilledTonalButton(
            onClick = onScanPorts,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Rounded.Radar, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("重新扫描该设备的代理端口")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                PeerGatewayMode.AUTO to "智能自适应",
                PeerGatewayMode.NATIVE_ONLY to "仅原生",
                PeerGatewayMode.PROXY_OVER_TS to "代理跳点"
            ).forEach { (m, label) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { mode = m },
                    label = { Text(label) }
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = protocol == "socks",
                onClick = { protocol = "socks" },
                label = { Text("SOCKS5") }
            )
            FilterChip(
                selected = protocol == "http",
                onClick = { protocol = "http" },
                label = { Text("HTTP") }
            )
            OutlinedTextField(
                value = portText,
                onValueChange = { portText = it.filter { ch -> ch.isDigit() }.take(5) },
                label = { Text("端口") },
                placeholder = { Text("1080") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }

        OutlinedTextField(
            value = staticRelay,
            onValueChange = { staticRelay = it.trim() },
            label = { Text("静态 UDP Peer Relay 端点 (可选)") },
            placeholder = { Text("203.0.113.10:40000") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("参与全自动优选切换", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Switch(checked = inAutoPool, onCheckedChange = { inAutoPool = it }, colors = singScaleSwitchColors())
        }

        Button(
            onClick = {
                onSave(
                    PeerEndpointConfig(
                        stableId = peer.stableId,
                        gatewayMode = mode,
                        proxyProtocol = protocol,
                        proxyPort = portText.toIntOrNull()?.coerceIn(0, 65535) ?: 0,
                        staticRelayEndpoint = staticRelay,
                        inAutoPool = inAutoPool
                    )
                )
            },
            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("保存并热重载", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(14.dp))
    }
}
