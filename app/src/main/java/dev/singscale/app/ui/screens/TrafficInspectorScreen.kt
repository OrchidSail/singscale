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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.singscale.app.core.DomainTrafficSummary
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TrackedConnectionItem
import dev.singscale.app.core.TrafficFormat
import dev.singscale.app.core.TrafficRouteCategory
import dev.singscale.app.core.TrafficSplitSnapshot
import dev.singscale.app.core.TrafficSplitTracker
import dev.singscale.app.core.TransportPathType
import dev.singscale.app.service.SingScaleVpnService
import dev.singscale.app.ui.components.DualTrafficSparkline
import dev.singscale.app.ui.components.GlassCard
import dev.singscale.app.ui.components.GlassFilterPill
import dev.singscale.app.ui.components.GlassIconButton
import dev.singscale.app.ui.components.GlassSearchBar
import dev.singscale.app.ui.components.GlassSegmentedControl
import dev.singscale.app.ui.components.MetricValueText
import dev.singscale.app.ui.components.MicroPillBadge
import dev.singscale.app.ui.components.SplitRatioProgressBar
import dev.singscale.app.ui.theme.AmberWarn
import dev.singscale.app.ui.theme.CoralAlert
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private enum class InspectorSubTab { DOMAINS, CONNECTIONS }
private enum class RouteFilterMode { ALL, RELAY_ONLY, MESH_ONLY, DIRECT_ONLY }
private enum class DomainSortMode(val label: String) {
    BY_BYTES("流量 ↓"),
    BY_SPEED("速率 ↓"),
    BY_RECENT("最新 ↓")
}

private val DIRECT_RTT_REGEX = Regex("""直连\s*(\d+\s*ms)""")

/**
 * Progressive-disclosure Traffic Inspector:
 * - Collapsible 30s waveform so 6-7 domains appear immediately above the fold
 * - On-demand expandable search bar (hidden until magnifying glass is tapped)
 * - Clean 2-line domain rows (Monogram + Host + Concise Route/RTT, Right-aligned Traffic)
 * - Tap any domain to open action sheet with 1-tap override & collapsible raw sockets
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficInspectorScreen(state: SingScaleUiState) {
    val context = LocalContext.current
    val split by TrafficSplitTracker.state.collectAsState()

    var subTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var filterOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var sortOrdinal by rememberSaveable { mutableIntStateOf(0) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var waveExpanded by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedDomainHost by remember { mutableStateOf<String?>(null) }
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }

    val subTab = InspectorSubTab.entries.getOrElse(subTabOrdinal) { InspectorSubTab.DOMAINS }
    val routeFilter = RouteFilterMode.entries.getOrElse(filterOrdinal) { RouteFilterMode.ALL }
    val sortMode = DomainSortMode.entries.getOrElse(sortOrdinal) { DomainSortMode.BY_BYTES }

    val normalizedQuery = searchQuery.trim().lowercase()

    val filteredDomains = remember(split.domains, routeFilter, sortMode, normalizedQuery) {
        val base = split.domains.filter { item ->
            val passRoute = when (routeFilter) {
                RouteFilterMode.ALL -> true
                RouteFilterMode.RELAY_ONLY -> item.primaryCategory == TrafficRouteCategory.RELAY_EXIT
                RouteFilterMode.MESH_ONLY -> item.primaryCategory == TrafficRouteCategory.TAILNET_MESH
                RouteFilterMode.DIRECT_ONLY -> item.primaryCategory == TrafficRouteCategory.DIRECT
            }
            val passQuery = normalizedQuery.isEmpty() ||
                item.host.lowercase().contains(normalizedQuery) ||
                item.routeNodeLabel.lowercase().contains(normalizedQuery) ||
                item.matchedRule.lowercase().contains(normalizedQuery) ||
                item.targetAddresses.any { it.lowercase().contains(normalizedQuery) }
            passRoute && passQuery
        }
        when (sortMode) {
            DomainSortMode.BY_BYTES -> base.sortedWith(
                compareByDescending<DomainTrafficSummary> { it.totalBytes }
                    .thenByDescending { it.totalBps }
            )
            DomainSortMode.BY_SPEED -> base.sortedWith(
                compareByDescending<DomainTrafficSummary> { it.totalBps }
                    .thenByDescending { it.activeConnections }
                    .thenByDescending { it.totalBytes }
            )
            DomainSortMode.BY_RECENT -> base.sortedByDescending { it.lastActiveAtMs }
        }
    }

    val filteredConnections = remember(split.connections, routeFilter, normalizedQuery) {
        split.connections.filter { conn ->
            val passRoute = when (routeFilter) {
                RouteFilterMode.ALL -> true
                RouteFilterMode.RELAY_ONLY -> conn.routeCategory == TrafficRouteCategory.RELAY_EXIT
                RouteFilterMode.MESH_ONLY -> conn.routeCategory == TrafficRouteCategory.TAILNET_MESH
                RouteFilterMode.DIRECT_ONLY -> conn.routeCategory == TrafficRouteCategory.DIRECT
            }
            val passQuery = normalizedQuery.isEmpty() ||
                conn.effectiveHost.lowercase().contains(normalizedQuery) ||
                conn.destination.lowercase().contains(normalizedQuery) ||
                conn.routeNodeLabel.lowercase().contains(normalizedQuery)
            passRoute && passQuery
        }
    }

    val relayItemCount = remember(subTab, split.domains, split.connections) {
        if (subTab == InspectorSubTab.DOMAINS) {
            split.domains.count { it.primaryCategory == TrafficRouteCategory.RELAY_EXIT }
        } else {
            split.connections.count { it.routeCategory == TrafficRouteCategory.RELAY_EXIT }
        }
    }
    val meshItemCount = remember(subTab, split.domains, split.connections) {
        if (subTab == InspectorSubTab.DOMAINS) {
            split.domains.count { it.primaryCategory == TrafficRouteCategory.TAILNET_MESH }
        } else {
            split.connections.count { it.routeCategory == TrafficRouteCategory.TAILNET_MESH }
        }
    }
    val directItemCount = remember(subTab, split.domains, split.connections) {
        if (subTab == InspectorSubTab.DOMAINS) {
            split.domains.count { it.primaryCategory == TrafficRouteCategory.DIRECT }
        } else {
            split.connections.count { it.routeCategory == TrafficRouteCategory.DIRECT }
        }
    }

    val maxDomainBytes = remember(filteredDomains) {
        max(1L, filteredDomains.maxOfOrNull { it.totalBytes } ?: 1L)
    }

    val activeNodeTitle = state.activeExitPeer?.displayName
        ?: state.activeRelayPeer?.displayName
        ?: "组网出口"

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 108.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Clean Header + On-Demand Search & Reset Icons
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "流量分流",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold),
                    color = TextPrimary
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassIconButton(
                        icon = Icons.Rounded.Search,
                        contentDescription = "搜索域名",
                        activeHighlight = searchVisible || searchQuery.isNotEmpty(),
                        onClick = {
                            if (searchVisible && searchQuery.isEmpty()) {
                                searchVisible = false
                            } else {
                                searchVisible = true
                            }
                        }
                    )
                    GlassIconButton(
                        icon = Icons.AutoMirrored.Rounded.ShowChart,
                        contentDescription = "展开或收起速率趋势图",
                        activeHighlight = waveExpanded,
                        onClick = { waveExpanded = !waveExpanded }
                    )
                    GlassIconButton(
                        icon = Icons.Rounded.DeleteSweep,
                        contentDescription = "重置统计",
                        onClick = {
                            TrafficSplitTracker.resetSession()
                            Toast.makeText(context, "已重置分流统计", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }

        // 2. Expandable Search Bar (Only takes space when needed!)
        item {
            AnimatedVisibility(
                visible = searchVisible || searchQuery.isNotEmpty(),
                enter = expandVertically() + fadeIn(tween(150)),
                exit = shrinkVertically() + fadeOut(tween(120))
            ) {
                GlassSearchBar(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "搜索域名、目标 IP…",
                    autoFocus = searchVisible,
                    onDismissIfEmpty = { searchVisible = false }
                )
            }
        }

        // 3. Compact Split Summary Card (Waveform folded unless toggled!)
        item {
            CompactSplitHeaderCard(
                split = split,
                activeNodeTitle = activeNodeTitle,
                waveExpanded = waveExpanded,
                onToggleWave = { waveExpanded = !waveExpanded },
                onCloseAllActive = {
                    SingScaleVpnService.closeAllTrackedConnections()
                    Toast.makeText(context, "已重置当前活跃连接", Toast.LENGTH_SHORT).show()
                }
            )
        }

        // 4. Streamlined Single-Block Filter & View Switcher
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassSegmentedControl(
                    items = listOf(
                        "域名 (${split.domains.size})" to null,
                        "活跃连接 (${split.connections.size})" to null
                    ),
                    selectedIndex = subTabOrdinal,
                    onSelect = { subTabOrdinal = it }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassFilterPill(
                        label = "全部",
                        selected = routeFilter == RouteFilterMode.ALL,
                        onClick = { filterOrdinal = RouteFilterMode.ALL.ordinal }
                    )
                    GlassFilterPill(
                        label = if (relayItemCount > 0) "中继/出口 $relayItemCount" else "中继/出口",
                        selected = routeFilter == RouteFilterMode.RELAY_ONLY,
                        accent = EmeraldGood,
                        leadingDotColor = EmeraldGood,
                        onClick = { filterOrdinal = RouteFilterMode.RELAY_ONLY.ordinal }
                    )
                    GlassFilterPill(
                        label = if (meshItemCount > 0) "组网直连 $meshItemCount" else "组网直连",
                        selected = routeFilter == RouteFilterMode.MESH_ONLY,
                        accent = IndigoAccent,
                        leadingDotColor = IndigoAccent,
                        onClick = { filterOrdinal = RouteFilterMode.MESH_ONLY.ordinal }
                    )
                    GlassFilterPill(
                        label = if (directItemCount > 0) "本地直连 $directItemCount" else "本地直连",
                        selected = routeFilter == RouteFilterMode.DIRECT_ONLY,
                        accent = AmberWarn,
                        leadingDotColor = AmberWarn,
                        onClick = { filterOrdinal = RouteFilterMode.DIRECT_ONLY.ordinal }
                    )

                    if (subTab == InspectorSubTab.DOMAINS) {
                        Spacer(modifier = Modifier.width(2.dp))
                        // Single cycling sort pill instead of 3 separate chips!
                        GlassFilterPill(
                            label = sortMode.label,
                            selected = true,
                            accent = CyanPrimary,
                            onClick = {
                                sortOrdinal = (sortOrdinal + 1) % DomainSortMode.entries.size
                            }
                        )
                    }
                }
            }
        }

        // 5. Scannable List
        if (subTab == InspectorSubTab.DOMAINS) {
            if (filteredDomains.isEmpty()) {
                item {
                    EmptyTrafficCard(
                        hasAnyTraffic = split.domains.isNotEmpty(),
                        isRunning = state.isRunning
                    )
                }
            } else {
                items(
                    items = filteredDomains,
                    key = { "dom_${it.host}" }
                ) { domainItem ->
                    DomainTrafficRowCard(
                        item = domainItem,
                        maxBytes = maxDomainBytes,
                        onClick = { selectedDomainHost = domainItem.host }
                    )
                }
            }
        } else {
            if (filteredConnections.isEmpty()) {
                item {
                    EmptyTrafficCard(
                        hasAnyTraffic = split.connections.isNotEmpty(),
                        isRunning = state.isRunning
                    )
                }
            } else {
                items(
                    items = filteredConnections,
                    key = { "conn_${it.id}" }
                ) { conn ->
                    ConnectionRecordCard(
                        conn = conn,
                        onClick = { selectedConnectionId = conn.id },
                        onDisconnect = {
                            SingScaleVpnService.closeTrackedConnection(conn.id)
                            Toast.makeText(context, "已断开 ${conn.effectiveHost}", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }

    // Domain Detail & Quick Rule Override Bottom Sheet
    val activeSelectedDomain = selectedDomainHost?.let { targetHost ->
        split.domains.firstOrNull { it.host.equals(targetHost, ignoreCase = true) }
    }
    if (activeSelectedDomain != null) {
        val domainConns = remember(split.connections, activeSelectedDomain.host) {
            split.connections.filter { it.effectiveHost.equals(activeSelectedDomain.host, ignoreCase = true) }
        }
        ModalBottomSheet(
            onDismissRequest = { selectedDomainHost = null },
            containerColor = ObsidianSurface
        ) {
            DomainDetailBottomSheet(
                domain = activeSelectedDomain,
                connections = domainConns,
                sessionTotalBytes = split.combinedBytes,
                onCloseSheet = { selectedDomainHost = null }
            )
        }
    }

    // Single Connection Detail Bottom Sheet
    val activeSelectedConn = selectedConnectionId?.let { cid ->
        split.connections.firstOrNull { it.id == cid }
    }
    if (activeSelectedConn != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedConnectionId = null },
            containerColor = ObsidianSurface
        ) {
            ConnectionDetailBottomSheet(
                conn = activeSelectedConn,
                onDisconnect = {
                    SingScaleVpnService.closeTrackedConnection(activeSelectedConn.id)
                    selectedConnectionId = null
                }
            )
        }
    }
}

@Composable
private fun CompactSplitHeaderCard(
    split: TrafficSplitSnapshot,
    activeNodeTitle: String,
    waveExpanded: Boolean,
    onToggleWave: () -> Unit,
    onCloseAllActive: () -> Unit
) {
    GlassCard(
        cornerRadius = 20.dp,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        onClick = onToggleWave
    ) {
        SplitRatioProgressBar(
            relayBytes = split.relayTotalBytes,
            meshBytes = split.meshTotalBytes,
            directBytes = split.directTotalBytes,
            relayBps = split.relayTotalBps,
            meshBps = split.meshTotalBps,
            directBps = split.directTotalBps
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            verticalAlignment = Alignment.CenterVertically
        ) {
            InspectorBucketColumn(
                title = "中继/出口",
                accent = EmeraldGood,
                downBps = split.relayDownBps,
                upBps = split.relayUpBps,
                totalBytes = split.relayTotalBytes,
                downBytes = split.relayDownBytes,
                upBytes = split.relayUpBytes,
                activeConns = split.activeRelayConnections,
                totalConns = split.totalRelayConnections,
                idleHint = activeNodeTitle,
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

            InspectorBucketColumn(
                title = "组网直连",
                accent = IndigoAccent,
                downBps = split.meshDownBps,
                upBps = split.meshUpBps,
                totalBytes = split.meshTotalBytes,
                downBytes = split.meshDownBytes,
                upBytes = split.meshUpBytes,
                activeConns = split.activeMeshConnections,
                totalConns = split.totalMeshConnections,
                idleHint = "100.x 内网",
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

            InspectorBucketColumn(
                title = "本地直连",
                accent = AmberWarn,
                downBps = split.directDownBps,
                upBps = split.directUpBps,
                totalBytes = split.directTotalBytes,
                downBytes = split.directDownBytes,
                upBytes = split.directUpBytes,
                activeConns = split.activeDirectConnections,
                totalConns = split.totalDirectConnections,
                idleHint = "物理网卡",
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
            )
        }

        if (split.totalActiveConnections > 0) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "活跃：中继 ${split.activeRelayConnections} · 组网 ${split.activeMeshConnections} · 直连 ${split.activeDirectConnections}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "重置活跃连接",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = CoralAlert,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(CoralAlert.copy(alpha = 0.12f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onCloseAllActive
                        )
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = waveExpanded,
            enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(160)),
            exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(120))
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                DualTrafficSparkline(
                    relaySamples = split.relayBpsHistory,
                    meshSamples = split.meshBpsHistory,
                    directSamples = split.directBpsHistory,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(66.dp)
                )
            }
        }
    }
}

@Composable
private fun InspectorBucketColumn(
    title: String,
    accent: Color,
    downBps: Long,
    upBps: Long,
    totalBytes: Long,
    downBytes: Long,
    upBytes: Long,
    activeConns: Int,
    totalConns: Int,
    idleHint: String,
    modifier: Modifier = Modifier
) {
    val hasLive = downBps > 0L || upBps > 0L
    val primaryText = when {
        downBps > 0L -> TrafficFormat.formatBps(downBps)
        upBps > 0L -> TrafficFormat.formatBps(upBps)
        else -> TrafficFormat.formatBytes(totalBytes)
    }
    val prefix = when {
        downBps > 0L -> "↓"
        upBps > 0L -> "↑"
        else -> ""
    }
    val subLine = when {
        hasLive -> "累计 ${TrafficFormat.formatBytes(totalBytes)}"
        totalBytes > 0L -> "↓${TrafficFormat.formatBytes(downBytes)} ↑${TrafficFormat.formatBytes(upBytes)}"
        activeConns > 0 -> "$activeConns 活跃连接"
        totalConns > 0 -> "$totalConns 条连接"
        else -> idleHint
    }

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
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        MetricValueText(
            formatted = primaryText,
            prefix = prefix,
            numberSize = 16.sp,
            unitSize = 10.sp,
            prefixColor = accent
        )
        Text(
            text = subLine,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontFeatureSettings = "tnum"
            ),
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Minimalist 2-line Domain Row:
 * Highlights ONLY Hostname + Route/RTT on the left, Total Bytes/Live Speed on the right,
 * and a thin proportional hairline bar underneath. All noisy secondary metadata is moved into the detail sheet.
 */
@Composable
private fun DomainTrafficRowCard(
    item: DomainTrafficSummary,
    maxBytes: Long,
    onClick: () -> Unit
) {
    val accent = item.primaryCategory.routeAccentColor()
    val barFraction = (item.totalBytes.toFloat() / maxBytes.toFloat()).coerceIn(0.03f, 1f)
    val monogram = remember(item.host) { domainMonogram(item.host) }

    val conciseSubtitle = remember(item.routeNodeLabel, item.matchedRule, item.transportPath) {
        val rtt = DIRECT_RTT_REGEX.find(item.matchedRule)?.groupValues?.getOrNull(1)?.replace(" ", " ")
        val pathSuffix = if (item.transportPath != TransportPathType.OFFLINE) {
            " · ${item.transportPath.shortLabel}"
        } else ""
        if (rtt != null) {
            "${item.routeNodeLabel} · $rtt"
        } else {
            "${item.routeNodeLabel}$pathSuffix"
        }
    }

    GlassCard(
        cornerRadius = 16.dp,
        highlightBorderColor = if (item.totalBps > 0L) accent.copy(alpha = 0.5f) else null,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                if (item.primaryCategory == TrafficRouteCategory.TAILNET_MESH || item.isPureIp) {
                    Icon(
                        imageVector = if (item.isPureIp) Icons.Rounded.Dns else Icons.Rounded.Hub,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    Text(
                        text = monogram,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                        color = accent
                    )
                }
            }

            Spacer(modifier = Modifier.width(11.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.host,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = conciseSubtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.90f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (item.totalBps > 0L) {
                        "↓ ${TrafficFormat.formatBps(item.downlinkBps)}"
                    } else {
                        TrafficFormat.formatBytes(item.totalBytes)
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (item.totalBps > 0L) accent else TextPrimary
                )
                if (item.totalBps > 0L) {
                    Text(
                        text = TrafficFormat.formatBytes(item.totalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }

            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }

        // Subtle 2.5dp Proportional Bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ObsidianElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(barFraction)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(accent.copy(alpha = 0.55f), accent)
                        )
                    )
            )
        }
    }
}

private fun domainMonogram(host: String): String {
    val parts = host.trim().lowercase().split('.').filter { it.isNotBlank() }
    val core = when {
        parts.size >= 2 && parts[parts.size - 2] !in setOf("com", "co", "org", "net", "gov", "edu", "apis") ->
            parts[parts.size - 2]
        parts.size >= 3 -> parts[parts.size - 3]
        else -> parts.firstOrNull().orEmpty()
    }
    return core.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "W"
}

@Composable
private fun ConnectionRecordCard(
    conn: TrackedConnectionItem,
    onClick: () -> Unit,
    onDisconnect: () -> Unit
) {
    val accent = conn.routeCategory.routeAccentColor()

    GlassCard(
        cornerRadius = 15.dp,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (conn.isActive) accent else TextMuted)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = conn.effectiveHost,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val pathPart = if (conn.transportPath != TransportPathType.OFFLINE) " · ${conn.transportPath.shortLabel}" else ""
                Text(
                    text = "${conn.routeNodeLabel}$pathPart · ${conn.network}/${conn.protocol.ifBlank { "TCP" }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (conn.totalBps > 0L) "↓${TrafficFormat.formatBps(conn.downlinkBps)}" else TrafficFormat.formatBytes(conn.totalBytes),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                color = if (conn.totalBps > 0L) accent else TextPrimary
            )
            if (conn.isActive) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onDisconnect, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "断开", tint = TextMuted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun DomainDetailBottomSheet(
    domain: DomainTrafficSummary,
    connections: List<TrackedConnectionItem>,
    sessionTotalBytes: Long,
    onCloseSheet: () -> Unit
) {
    val context = LocalContext.current
    val accent = domain.primaryCategory.routeAccentColor()
    var socketsExpanded by remember { mutableStateOf(false) }
    val chevronRot by animateFloatAsState(
        targetValue = if (socketsExpanded) 180f else 0f,
        label = "sheetSocketsChevron"
    )
    val pct = if (sessionTotalBytes > 0L) {
        String.format(Locale.US, "%.1f%%", domain.totalBytes.toDouble() * 100.0 / sessionTotalBytes.toDouble())
    } else "0.0%"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Domain Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = domain.host,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MicroPillBadge(text = domain.primaryCategory.badge, color = accent, filled = true)
                    if (domain.transportPath != TransportPathType.OFFLINE) {
                        MicroPillBadge(text = domain.transportPath.label, color = CyanBright)
                    }
                    domain.protocols.take(2).forEach { p ->
                        MicroPillBadge(text = p, color = TextSecondary)
                    }
                }
            }
            IconButton(
                onClick = {
                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cb.setPrimaryClip(ClipData.newPlainText("SingScale Domain", domain.host))
                    Toast.makeText(context, "已复制 ${domain.host}", Toast.LENGTH_SHORT).show()
                }
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "复制域名", tint = TextSecondary)
            }
        }

        // 2. Primary Action Up Front: 1-Tap Routing Override (public domains only; Tailnet Mesh stays internal)
        if (!domain.isPureIp && !domain.host.contains(' ') && domain.primaryCategory != TrafficRouteCategory.TAILNET_MESH) {
            val ruleHost = domain.host.lowercase().removePrefix("www.")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        SingScaleRepository.updateThresholds { cur ->
                            cur.copy(
                                forcedRelayDomains = (cur.forcedRelayDomains + ruleHost).distinct(),
                                customProxyDomains = (cur.customProxyDomains + ruleHost).distinct(),
                                customDirectDomains = cur.customDirectDomains.filterNot { it.equals(ruleHost, true) }
                            )
                        }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        Toast.makeText(context, "已将 $ruleHost 设为强制走中继/出口", Toast.LENGTH_SHORT).show()
                        onCloseSheet()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldGood),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("强制走中继", color = Color(0xFF021E14), fontWeight = FontWeight.Bold, maxLines = 1)
                }

                OutlinedButton(
                    onClick = {
                        SingScaleRepository.updateThresholds { cur ->
                            cur.copy(
                                customDirectDomains = (cur.customDirectDomains + ruleHost).distinct(),
                                forcedRelayDomains = cur.forcedRelayDomains.filterNot { it.equals(ruleHost, true) },
                                customProxyDomains = cur.customProxyDomains.filterNot { it.equals(ruleHost, true) }
                            )
                        }
                        SingScaleVpnService.notifyPolicyOrNodeChanged(context)
                        Toast.makeText(context, "已将 $ruleHost 设为强制本地直连", Toast.LENGTH_SHORT).show()
                        onCloseSheet()
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("强制走直连", color = AmberWarn, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }

        // 3. Concise Summary Card
        GlassCard(
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DetailKvRow("分流去向", "${domain.primaryCategory.label} · ${domain.routeNodeLabel}")
            DetailKvRow("累计流量 (会话占比 $pct)", "${TrafficFormat.formatBytes(domain.totalBytes)}  (↓ ${TrafficFormat.formatBytes(domain.downlinkTotal)} / ↑ ${TrafficFormat.formatBytes(domain.uplinkTotal)})")
            DetailKvRow("分流依据", domain.matchedRule)
        }

        // 4. Secondary Technical Details Folded by Default!
        GlassCard(
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            onClick = { socketsExpanded = !socketsExpanded }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "底层连接与目标 IP 明细 (${connections.size})",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextSecondary
                )
                Icon(
                    imageVector = Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextMuted,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer { rotationZ = chevronRot }
                )
            }

            AnimatedVisibility(visible = socketsExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HorizontalDivider(color = ObsidianBorder.copy(alpha = 0.6f))
                    if (domain.targetAddresses.isNotEmpty()) {
                        DetailKvRow("目标地址", domain.targetAddresses.joinToString(", "))
                    }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(connections.take(25), key = { "sheet_${it.id}" }) { c ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(ObsidianElevated.copy(alpha = 0.65f))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (c.isActive) accent else TextMuted)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${c.network}/${c.protocol.ifBlank { "RAW" }} → ${c.destination}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${c.chainSummary} · ${TrafficFormat.formatBytes(c.totalBytes)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        maxLines = 1
                                    )
                                }
                                if (c.isActive) {
                                    IconButton(
                                        onClick = { SingScaleVpnService.closeTrackedConnection(c.id) },
                                        modifier = Modifier.size(26.dp)
                                    ) {
                                        Icon(Icons.Rounded.LinkOff, contentDescription = "断开", tint = CoralAlert, modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun ConnectionDetailBottomSheet(
    conn: TrackedConnectionItem,
    onDisconnect: () -> Unit
) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val accent = conn.routeCategory.routeAccentColor()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = conn.effectiveHost,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = TextPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MicroPillBadge(text = conn.primaryCategoryBadgeOrNode(), color = accent, filled = true)
            if (conn.transportPath != TransportPathType.OFFLINE) {
                MicroPillBadge(text = conn.transportPath.label, color = CyanBright)
            }
            MicroPillBadge(text = "${conn.network} ${conn.protocol}".trim(), color = TextSecondary)
        }

        GlassCard(
            cornerRadius = 16.dp,
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            DetailKvRow("分流去向", "${conn.routeCategory.label} · ${conn.routeNodeLabel}")
            DetailKvRow("连接目标", "${conn.source} → ${conn.destination}")
            DetailKvRow("转发链路", conn.chainSummary)
            DetailKvRow("命中规则", conn.matchedRule)
            DetailKvRow("累计流量", "${TrafficFormat.formatBytes(conn.totalBytes)} (↓ ${TrafficFormat.formatBytes(conn.downlinkTotal)} / ↑ ${TrafficFormat.formatBytes(conn.uplinkTotal)}) · 建立于 ${fmt.format(Date(conn.createdAtMs))}")
        }

        if (conn.isActive) {
            Button(
                onClick = onDisconnect,
                colors = ButtonDefaults.buttonColors(containerColor = CoralAlert),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.LinkOff, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("断开该连接", fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun DetailKvRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = TextPrimary
        )
    }
}

@Composable
private fun EmptyTrafficCard(hasAnyTraffic: Boolean, isRunning: Boolean) {
    GlassCard(
        contentPadding = PaddingValues(28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.Public,
                contentDescription = null,
                tint = CyanPrimary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = when {
                    hasAnyTraffic -> "暂无匹配记录"
                    isRunning -> "正在监听实时流量…"
                    else -> "尚未连接组网"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = TextPrimary
            )
            Text(
                text = if (hasAnyTraffic) "切换上方筛选标签或清空关键词重试" else "开启组网后访问网页或应用即可实时查看域名分流",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
    }
}

private fun TrackedConnectionItem.primaryCategoryBadgeOrNode(): String = routeCategory.badge

internal fun TrafficRouteCategory.routeAccentColor(): Color = when (this) {
    TrafficRouteCategory.RELAY_EXIT -> EmeraldGood
    TrafficRouteCategory.TAILNET_MESH -> IndigoAccent
    TrafficRouteCategory.DIRECT -> AmberWarn
}
