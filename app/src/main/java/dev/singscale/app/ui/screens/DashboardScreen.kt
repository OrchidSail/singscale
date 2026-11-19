package dev.singscale.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LaptopMac
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PhoneIphone
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.DomainTrafficSummary
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TailnetPeer
import dev.singscale.app.core.TrafficFormat
import dev.singscale.app.core.TrafficRouteCategory
import dev.singscale.app.core.TrafficSplitSnapshot
import dev.singscale.app.core.TrafficSplitTracker
import dev.singscale.app.core.TunnelStatus
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.ConnectionOrb
import dev.singscale.app.ui.components.DualTrafficSparkline
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.components.MetricValueText
import dev.singscale.app.ui.components.MicroPillBadge
import dev.singscale.app.ui.components.QrCodeView
import dev.singscale.app.ui.components.QualityScoreRing
import dev.singscale.app.ui.components.SingScaleBrandMark
import dev.singscale.app.ui.components.SparklineChart
import dev.singscale.app.ui.components.SplitRatioProgressBar
import dev.singscale.app.ui.components.TopologyBar
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CoralAlert
import dev.singscale.app.ui.theme.CyanBright
import dev.singscale.app.ui.theme.CyanPrimary
import dev.singscale.app.ui.theme.EmeraldGood
import dev.singscale.app.ui.theme.IndigoAccent
import dev.singscale.app.ui.theme.ObsidianBorder
import dev.singscale.app.ui.theme.ObsidianCard
import dev.singscale.app.ui.theme.ObsidianElevated
import dev.singscale.app.ui.theme.TextMuted
import dev.singscale.app.ui.theme.TextPrimary
import dev.singscale.app.ui.theme.TextSecondary
import dev.singscale.app.ui.theme.accentColor
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Calm, spacious, zero-overload Home Screen:
 * Primary view fits comfortably in a single viewport (Brand Header + Hero Core +
 * 1-line Expandable Smart Gateway Bar + Compact Traffic Split Summary).
 * Detailed topology, RTT sparkline, quick node switcher, and 30s split waveform
 * expand inline with spring physics only when the user taps to inspect.
 */
@Composable
fun DashboardScreen(
    state: SingScaleUiState,
    onToggleTunnel: () -> Unit,
    onStartInteractiveLogin: () -> Unit,
    onOpenAuthUrl: (String) -> Unit,
    onOpenAuthSettingsSheet: () -> Unit,
    onOpenQuickModeSheet: () -> Unit,
    onNavigateToNodes: () -> Unit,
    onNavigateToTraffic: () -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val split by TrafficSplitTracker.state.collectAsState()

    var gatewayExpanded by rememberSaveable { mutableStateOf(false) }
    var splitChartExpanded by rememberSaveable { mutableStateOf(false) }

    val tailnetBadge = when {
        state.tailnetName.isNotBlank() -> "${state.tailnetName} · ${state.onlinePeersCount} 台在线"
        state.authConfig.authKey.isNotBlank() -> "AuthKey 自动接入"
        else -> "Tailscale 智能组网"
    }

    // Only show live throughput pill next to tailnet badge when actively transferring (> 512 B/s)
    val liveDownFormatted = if (state.traffic.downlinkBps > 512L || state.traffic.uplinkBps > 512L) {
        formatBytesPerSec(state.traffic.downlinkBps)
    } else ""
    val liveUpFormatted = if (state.traffic.uplinkBps > 512L) {
        formatBytesPerSec(state.traffic.uplinkBps)
    } else ""

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Minimalist Brand Header + 1-Tap Routing Mode Capsule
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SingScaleBrandMark(
                        size = 34.dp,
                        accent = state.tunnelStatus.accentColor()
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "SingScale",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp
                        ),
                        color = TextPrimary
                    )
                }

                // 1-Tap Quick Mode Selector Pill
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(ObsidianCard)
                        .border(1.dp, ObsidianBorder, RoundedCornerShape(999.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onOpenQuickModeSheet()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(CyanBright)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = state.policyMode.badge,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(3.dp))
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "切换路由模式",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // 2. Error Banner (only when present)
        if (!state.errorMessage.isNullOrBlank()) {
            item {
                GlassCard(
                    accentTint = CoralAlert,
                    highlightBorderColor = CoralAlert,
                    contentPadding = PaddingValues(14.dp)
                ) {
                    Text(
                        text = state.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = CoralAlert
                    )
                }
            }
        }

        // 3. Login Prompt (only when unauthenticated)
        if (!state.isLoggedIn || state.tunnelStatus == TunnelStatus.NEEDS_LOGIN || state.authUrl.isNotBlank()) {
            item {
                TailscaleLoginHeroCard(
                    state = state,
                    onStartInteractiveLogin = onStartInteractiveLogin,
                    onOpenAuthUrl = onOpenAuthUrl,
                    onOpenAuthSettingsSheet = onOpenAuthSettingsSheet
                )
            }
        }

        // 4. Spacious Chronograph Power Hero (Focal Point #1)
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                ConnectionOrb(
                    status = state.tunnelStatus,
                    connectedSinceMs = state.connectedSinceMs,
                    tailnetLabel = tailnetBadge,
                    downlinkFormatted = liveDownFormatted,
                    uplinkFormatted = liveUpFormatted,
                    onToggle = onToggleTunnel
                )
            }
        }

        // 5. Progressive-Disclosure Smart Gateway Card (Collapsed 1-row summary -> Tap to expand Topology & Quick Nodes)
        item {
            ExpandableGatewayCard(
                state = state,
                expanded = gatewayExpanded,
                onToggleExpand = { gatewayExpanded = !gatewayExpanded },
                onSelectAuto = {
                    SingScaleRepository.setSmartAutoSelectNode(true)
                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                },
                onSelectExitPeer = { peer ->
                    val nextId = if (state.selectedExitPeerId == peer.stableId && !state.smartAutoSelectNode) "" else peer.stableId
                    SingScaleRepository.selectExitPeer(nextId, disableAutoPick = nextId.isNotBlank())
                    SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                },
                onProbeNow = { SingScaleVpnService.triggerProbeNow(context) },
                onOpenAllNodes = onNavigateToNodes
            )
        }

        // 6. Compact Live Traffic Split Card (Focal Point #2 — Clean Ratio & Totals, Collapsible Waveform)
        item {
            CompactTrafficSplitCard(
                state = state,
                split = split,
                chartExpanded = splitChartExpanded,
                onToggleChart = { splitChartExpanded = !splitChartExpanded },
                onOpenInspector = onNavigateToTraffic
            )
        }
    }
}

@Composable
private fun ExpandableGatewayCard(
    state: SingScaleUiState,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSelectAuto: () -> Unit,
    onSelectExitPeer: (TailnetPeer) -> Unit,
    onProbeNow: () -> Unit,
    onOpenAllNodes: () -> Unit
) {
    val isRunning = state.tunnelStatus == TunnelStatus.RUNNING
    val exitPeer = state.activeExitPeer
    val relayPeer = state.activeRelayPeer
    val q = state.activeQuality
    val accent = if (isRunning) q.pathType.accentColor() else TextMuted

    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "gwChevron"
    )

    val gatewayTitle = when {
        exitPeer != null && relayPeer != null && relayPeer.stableId != exitPeer.stableId ->
            "${relayPeer.displayName} → ${exitPeer.displayName}"
        exitPeer != null -> exitPeer.displayName
        relayPeer != null -> relayPeer.displayName
        state.policyMode == RoutingPolicyMode.MESH_ONLY -> "仅组网内网互通"
        !isRunning -> "启动后自动优选出口"
        else -> "公网本地直连"
    }
    val roleLabel = when {
        exitPeer != null && relayPeer != null && relayPeer.stableId != exitPeer.stableId -> "中继 → 出口"
        exitPeer != null && state.isAutoExitActive -> "自动出口"
        exitPeer != null -> "锁定出口"
        relayPeer != null -> "当前中继"
        else -> "当前网关"
    }
    val rttBadge = when {
        !isRunning -> "待机"
        q.reachable && q.latencyMs > 0 -> "${q.latencyMs.roundToInt()} ms · ${q.pathType.shortLabel}"
        exitPeer != null -> exitPeer.effectivePathType.shortLabel
        relayPeer != null -> relayPeer.effectivePathType.shortLabel
        else -> "直连"
    }

    GlassCard(
        cornerRadius = 22.dp,
        accentTint = if (isRunning && expanded) accent else null,
        contentPadding = PaddingValues(0.dp)
    ) {
        // Primary 1-Row Summary (Zero Clutter!)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpand)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isRunning) EmeraldGood else TextMuted)
            )
            Spacer(modifier = Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = roleLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = gatewayTitle,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))
            MicroPillBadge(
                text = rttBadge,
                color = if (isRunning) accent else TextSecondary,
                filled = isRunning
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "收起节点详情" else "展开节点与拓扑",
                tint = TextMuted,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevronRotation }
            )
        }

        // Folded Secondary Details: Route Map + Quality Sparkline + Quick Node Switcher
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeOut(animationSpec = tween(140))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))

                // Visual Route Map
                TopologyBar(state = state)

                // Compact Quality Row + Sparkline
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    QualityScoreRing(score = q.score, grade = q.grade, ringSize = 48.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.dynamicTier.summary,
                            style = MaterialTheme.typography.labelLarge,
                            color = TextPrimary
                        )
                        val lossStr = if (q.updatedAtMs > 0L) "${(q.lossRate * 100).roundToInt()}%" else "—"
                        val jitterStr = if (q.reachable) "${q.jitterMs.roundToInt()} ms" else "—"
                        Text(
                            text = "抖动 $jitterStr · 丢包 $lossStr",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    SparklineChart(
                        samples = q.history,
                        lineColor = if (accent == TextMuted) CyanPrimary else accent,
                        modifier = Modifier
                            .width(88.dp)
                            .height(32.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onProbeNow,
                        enabled = isRunning && !state.isProbingNow,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(9.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        if (state.isProbingNow) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.8.dp, color = CyanPrimary)
                        } else {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Quick Horizontal Node Switcher
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val autoSelected = state.isAutoExitActive && state.isAutoRelayActive
                    CompactGatewayChip(
                        title = "自动优选",
                        subtitle = "智能调度",
                        selected = autoSelected,
                        accent = CyanPrimary,
                        onClick = onSelectAuto
                    )

                    val candidates = state.peers
                        .filter { it.canActAsExit || it.canActAsRelay || it.stableId == state.activeExitPeerId }
                        .sortedByDescending { it.quality.score }
                    candidates.take(5).forEach { peer ->
                        val isSelected = !autoSelected && (state.selectedExitPeerId == peer.stableId || state.activeExitPeerId == peer.stableId)
                        val rtt = if (peer.quality.reachable && peer.quality.latencyMs > 0) {
                            "${peer.quality.latencyMs.roundToInt()} ms"
                        } else "在线"
                        CompactGatewayChip(
                            title = peer.displayName,
                            subtitle = rtt,
                            selected = isSelected,
                            accent = EmeraldGood,
                            onClick = { onSelectExitPeer(peer) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .height(50.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(ObsidianElevated.copy(alpha = 0.5f))
                            .clickable(onClick = onOpenAllNodes)
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "全部 (${state.peers.size})",
                            style = MaterialTheme.typography.labelMedium,
                            color = CyanBright
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                            contentDescription = null,
                            tint = CyanBright,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactGatewayChip(
    title: String,
    subtitle: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(128.dp)
            .height(50.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else ObsidianElevated.copy(alpha = 0.55f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.65f) else ObsidianBorder,
                shape = RoundedCornerShape(13.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun CompactTrafficSplitCard(
    state: SingScaleUiState,
    split: TrafficSplitSnapshot,
    chartExpanded: Boolean,
    onToggleChart: () -> Unit,
    onOpenInspector: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    GlassCard(
        cornerRadius = 22.dp,
        contentPadding = PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        onClick = onOpenInspector
    ) {
        // Clean Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "流量分流",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Inline Waveform Peek Button (stops propagation to card click)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (chartExpanded) CyanPrimary.copy(alpha = 0.16f) else ObsidianElevated)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onToggleChart()
                            }
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ShowChart,
                        contentDescription = null,
                        tint = if (chartExpanded) CyanBright else TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (chartExpanded) "收起趋势" else "趋势",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (chartExpanded) CyanBright else TextSecondary
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (split.domains.isNotEmpty()) "${split.domains.size} 域名" else "详情",
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanBright
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = "打开分流详情",
                        tint = CyanBright,
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }

        // 3-Way Visual Ratio Bar (中继 / 组网直连 / 本地直连)
        SplitRatioProgressBar(
            relayBytes = split.relayTotalBytes,
            meshBytes = split.meshTotalBytes,
            directBytes = split.directTotalBytes,
            relayBps = split.relayTotalBps,
            meshBps = split.meshTotalBps,
            directBps = split.directTotalBps
        )

        // Clean 3-Column Essential Numbers (中继/出口 · 组网直连 · 本地直连)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EssentialSplitMetric(
                label = "中继/出口",
                primaryFormatted = when {
                    split.relayDownBps > 0L -> TrafficFormat.formatBps(split.relayDownBps)
                    split.relayUpBps > 0L -> TrafficFormat.formatBps(split.relayUpBps)
                    else -> TrafficFormat.formatBytes(split.relayTotalBytes)
                },
                prefix = when {
                    split.relayDownBps > 0L -> "↓"
                    split.relayUpBps > 0L -> "↑"
                    else -> ""
                },
                secondary = when {
                    split.relayTotalBps > 0L -> "累计 ${TrafficFormat.formatBytes(split.relayTotalBytes)}"
                    split.activeRelayConnections > 0 -> "${split.activeRelayConnections} 活跃连接"
                    else -> state.activeExitPeer?.displayName ?: "按需介入"
                },
                accent = EmeraldGood,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .background(ObsidianBorder)
            )

            EssentialSplitMetric(
                label = "组网直连",
                primaryFormatted = when {
                    split.meshDownBps > 0L -> TrafficFormat.formatBps(split.meshDownBps)
                    split.meshUpBps > 0L -> TrafficFormat.formatBps(split.meshUpBps)
                    else -> TrafficFormat.formatBytes(split.meshTotalBytes)
                },
                prefix = when {
                    split.meshDownBps > 0L -> "↓"
                    split.meshUpBps > 0L -> "↑"
                    else -> ""
                },
                secondary = when {
                    split.meshTotalBps > 0L -> "累计 ${TrafficFormat.formatBytes(split.meshTotalBytes)}"
                    split.activeMeshConnections > 0 -> "${split.activeMeshConnections} 活跃连接"
                    else -> "100.x 内网"
                },
                accent = IndigoAccent,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )

            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .padding(vertical = 2.dp)
                    .background(ObsidianBorder)
            )

            EssentialSplitMetric(
                label = "本地直连",
                primaryFormatted = when {
                    split.directDownBps > 0L -> TrafficFormat.formatBps(split.directDownBps)
                    split.directUpBps > 0L -> TrafficFormat.formatBps(split.directUpBps)
                    else -> TrafficFormat.formatBytes(split.directTotalBytes)
                },
                prefix = when {
                    split.directDownBps > 0L -> "↓"
                    split.directUpBps > 0L -> "↑"
                    else -> ""
                },
                secondary = when {
                    split.directTotalBps > 0L -> "累计 ${TrafficFormat.formatBytes(split.directTotalBytes)}"
                    split.activeDirectConnections > 0 -> "${split.activeDirectConnections} 活跃连接"
                    else -> "直连优先"
                },
                accent = AmberWarn,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }

        // Optional Expandable 30s 3-Way Sparkline + Active Breakdown + Top Domains
        AnimatedVisibility(
            visible = chartExpanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
                fadeOut(animationSpec = tween(140))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                DualTrafficSparkline(
                    relaySamples = split.relayBpsHistory,
                    meshSamples = split.meshBpsHistory,
                    directSamples = split.directBpsHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                )
                if (split.totalActiveConnections > 0) {
                    Text(
                        text = "活跃连接：中继 ${split.activeRelayConnections} · 组网直连 ${split.activeMeshConnections} · 本地直连 ${split.activeDirectConnections}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (split.domains.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        split.domains.take(3).forEach { dom ->
                            DashboardDomainPreviewRow(domain = dom)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EssentialSplitMetric(
    label: String,
    primaryFormatted: String,
    prefix: String,
    secondary: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(5.5.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MetricValueText(
            formatted = primaryFormatted,
            prefix = prefix,
            numberSize = 16.5.sp,
            unitSize = 10.sp,
            prefixColor = accent
        )
        Text(
            text = secondary,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontFeatureSettings = "tnum"
            ),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DashboardDomainPreviewRow(domain: DomainTrafficSummary) {
    val badgeColor = when (domain.primaryCategory) {
        TrafficRouteCategory.RELAY_EXIT -> EmeraldGood
        TrafficRouteCategory.TAILNET_MESH -> IndigoAccent
        TrafficRouteCategory.DIRECT -> AmberWarn
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            MicroPillBadge(text = domain.primaryCategory.badge, color = badgeColor)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = domain.host,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = TrafficFormat.formatBytes(domain.totalBytes),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun TailscaleLoginHeroCard(
    state: SingScaleUiState,
    onStartInteractiveLogin: () -> Unit,
    onOpenAuthUrl: (String) -> Unit,
    onOpenAuthSettingsSheet: () -> Unit
) {
    val context = LocalContext.current
    var showQr by remember { mutableStateOf(false) }

    GlassCard(
        accentTint = CyanPrimary,
        highlightBorderColor = CyanPrimary.copy(alpha = 0.55f),
        contentPadding = PaddingValues(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.Login,
                contentDescription = null,
                tint = CyanBright,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (state.authUrl.isNotBlank()) "完成设备授权" else "登录 Tailscale 组网",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = if (state.authUrl.isNotBlank()) {
                "点击下方按钮在浏览器一键确认，自动同步组网设备。"
            } else {
                "一键登录官方 Tailscale 或自建 Headscale，自动识别组网出口与中继。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(14.dp))

        if (state.authUrl.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { onOpenAuthUrl(state.authUrl) },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.OpenInBrowser, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("浏览器授权", fontWeight = FontWeight.Bold)
                }

                IconButton(
                    onClick = { showQr = !showQr },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianElevated)
                ) {
                    Icon(Icons.Rounded.QrCode2, contentDescription = "扫码登录", tint = CyanBright)
                }

                IconButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Tailscale Auth URL", state.authUrl))
                        Toast.makeText(context, "已复制登录授权链接", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(ObsidianElevated)
                ) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "复制链接", tint = TextPrimary)
                }
            }

            if (showQr) {
                Spacer(modifier = Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    QrCodeView(content = state.authUrl)
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartInteractiveLogin,
                    colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Tailscale 账号登录", fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = onOpenAuthSettingsSheet,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanBright)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("AuthKey", color = TextPrimary, maxLines = 1)
                }
            }
        }
    }
}

internal fun peerOsIcon(os: String): ImageVector {
    val norm = os.lowercase()
    return when {
        "android" in norm -> Icons.Rounded.PhoneAndroid
        "ios" in norm -> Icons.Rounded.PhoneIphone
        "macos" in norm || "darwin" in norm || "windows" in norm -> Icons.Rounded.LaptopMac
        else -> Icons.Rounded.Dns
    }
}

private fun formatBytesPerSec(bps: Long): String {
    if (bps <= 0L) return "0 B/s"
    return when {
        bps >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
        bps >= 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
        else -> "$bps B/s"
    }
}
