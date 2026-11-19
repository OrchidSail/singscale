package dev.singscale.app.core

import androidx.compose.runtime.Immutable
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import kotlin.math.max

enum class TrafficRouteCategory(val label: String, val badge: String) {
    RELAY_EXIT("走组网中继 / 出口", "中继/出口"),
    TAILNET_MESH("组网内网直达", "组网内网"),
    DIRECT("本地物理直连", "本地直连");

    val isTunnelRouted: Boolean
        get() = this == RELAY_EXIT || this == TAILNET_MESH
}

@Immutable
data class TrackedConnectionItem(
    val id: String,
    val domain: String,
    val effectiveHost: String,
    val destination: String,
    val source: String,
    val network: String,
    val protocol: String,
    val routeCategory: TrafficRouteCategory,
    val outboundTag: String,
    val routeNodeLabel: String,
    val chainSummary: String,
    val matchedRule: String,
    val uplinkBps: Long,
    val downlinkBps: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val createdAtMs: Long,
    val closedAtMs: Long = 0L,
    val packageName: String = ""
) {
    val isActive: Boolean get() = closedAtMs == 0L
    val totalBps: Long get() = uplinkBps + downlinkBps
    val totalBytes: Long get() = uplinkTotal + downlinkTotal
}

@Immutable
data class DomainTrafficSummary(
    val host: String,
    val isPureIp: Boolean,
    val primaryCategory: TrafficRouteCategory,
    val routeNodeLabel: String,
    val uplinkBps: Long,
    val downlinkBps: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
    val activeConnections: Int,
    val totalConnections: Int,
    val protocols: List<String>,
    val targetAddresses: List<String>,
    val matchedRule: String,
    val lastActiveAtMs: Long
) {
    val totalBps: Long get() = uplinkBps + downlinkBps
    val totalBytes: Long get() = uplinkTotal + downlinkTotal
}

@Immutable
data class TrafficSplitSnapshot(
    val relayUpBps: Long = 0L,
    val relayDownBps: Long = 0L,
    val directUpBps: Long = 0L,
    val directDownBps: Long = 0L,
    val relayUpBytes: Long = 0L,
    val relayDownBytes: Long = 0L,
    val directUpBytes: Long = 0L,
    val directDownBytes: Long = 0L,
    val meshBytes: Long = 0L,
    val activeRelayConnections: Int = 0,
    val activeDirectConnections: Int = 0,
    val relayBpsHistory: List<Float> = List(30) { 0f },
    val directBpsHistory: List<Float> = List(30) { 0f },
    val domains: List<DomainTrafficSummary> = emptyList(),
    val connections: List<TrackedConnectionItem> = emptyList(),
    val updatedAtMs: Long = 0L
) {
    val relayTotalBps: Long get() = relayUpBps + relayDownBps
    val directTotalBps: Long get() = directUpBps + directDownBps
    val combinedBps: Long get() = relayTotalBps + directTotalBps

    val relayTotalBytes: Long get() = relayUpBytes + relayDownBytes
    val directTotalBytes: Long get() = directUpBytes + directDownBytes
    val combinedBytes: Long get() = relayTotalBytes + directTotalBytes

    val relayShareRatio: Float
        get() = when {
            combinedBytes > 0L -> (relayTotalBytes.toDouble() / combinedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
            combinedBps > 0L -> (relayTotalBps.toDouble() / combinedBps.toDouble()).toFloat().coerceIn(0f, 1f)
            else -> 0.5f
        }

    val relaySharePercent: Float
        get() = if (combinedBytes > 0L) {
            ((relayTotalBytes.toDouble() * 100.0) / combinedBytes.toDouble()).toFloat().coerceIn(0f, 100f)
        } else 0f

    val directSharePercent: Float
        get() = if (combinedBytes > 0L) (100f - relaySharePercent).coerceIn(0f, 100f) else 0f
}

object TrafficFormat {
    fun formatBps(bps: Long): String {
        if (bps <= 0L) return "0 B/s"
        return when {
            bps >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB/s", bps / (1024.0 * 1024.0))
            bps >= 1024L -> String.format(Locale.US, "%.1f KB/s", bps / 1024.0)
            else -> "$bps B/s"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        return when {
            bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    fun formatDuration(durationMs: Long): String {
        val totalSec = max(0L, durationMs / 1000L)
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return when {
            h > 0L -> "${h}h ${m}m"
            m > 0L -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }
}

/**
 * Real-time split traffic & per-domain/per-connection telemetry tracker powered by sing-box
 * `CommandConnections` stream + DNS reverse-resolution enrichment.
 */
object TrafficSplitTracker {

    private const val MAX_CLOSED_RETENTION = 160
    private const val HISTORY_POINTS = 30
    private const val RATE_STALE_MS = 1650L
    private const val MAX_DNS_CACHE = 512

    data class RawConnData(
        val id: String,
        var domain: String,
        var destination: String,
        var source: String,
        var network: String,
        var protocol: String,
        var outboundTag: String,
        var chain: List<String>,
        var rule: String,
        var createdAtMs: Long,
        var closedAtMs: Long = 0L,
        var uplinkTotal: Long = 0L,
        var downlinkTotal: Long = 0L,
        var uplinkBps: Long = 0L,
        var downlinkBps: Long = 0L,
        var lastRateAtMs: Long = 0L,
        var lastSeenAtMs: Long = 0L,
        var packageName: String = ""
    )

    private val lock = Any()
    private val connectionsMap = LinkedHashMap<String, RawConnData>()
    private val ipToDomainCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_DNS_CACHE
        }
    }

    // Archived totals from hot-reloaded instances or evicted closed connections so cumulative bytes never drop
    private var archivedRelayUp: Long = 0L
    private var archivedRelayDown: Long = 0L
    private var archivedDirectUp: Long = 0L
    private var archivedDirectDown: Long = 0L
    private var archivedMeshBytes: Long = 0L
    private var lastInstanceEpoch: Int = 0

    private val relayHistory = ArrayDeque<Float>(HISTORY_POINTS).apply { repeat(HISTORY_POINTS) { addLast(0f) } }
    private val directHistory = ArrayDeque<Float>(HISTORY_POINTS).apply { repeat(HISTORY_POINTS) { addLast(0f) } }

    private val _state = MutableStateFlow(TrafficSplitSnapshot())
    val state: StateFlow<TrafficSplitSnapshot> = _state.asStateFlow()

    fun resetSession() {
        synchronized(lock) {
            connectionsMap.clear()
            ipToDomainCache.clear()
            archivedRelayUp = 0L
            archivedRelayDown = 0L
            archivedDirectUp = 0L
            archivedDirectDown = 0L
            archivedMeshBytes = 0L
            lastInstanceEpoch = 0
            relayHistory.clear()
            directHistory.clear()
            repeat(HISTORY_POINTS) {
                relayHistory.addLast(0f)
                directHistory.addLast(0f)
            }
            _state.value = TrafficSplitSnapshot(updatedAtMs = System.currentTimeMillis())
        }
    }

    fun onDnsResolved(domainRaw: String, ipRaw: String) {
        val cleanDomain = domainRaw.trim().trimEnd('.').lowercase()
        val cleanIp = ipRaw.trim()
        if (cleanDomain.isBlank() || cleanIp.isBlank()) return
        synchronized(lock) {
            ipToDomainCache[cleanIp] = cleanDomain
            // Backfill any already-open pure-IP connection matching cleanIp
            for (entry in connectionsMap.values) {
                if (entry.domain.isBlank() && extractHost(entry.destination) == cleanIp) {
                    entry.domain = cleanDomain
                }
            }
        }
    }

    fun onConnectionEvents(
        events: ConnectionEvents?,
        uiState: SingScaleUiState,
        instanceEpoch: Int = lastInstanceEpoch
    ) {
        events ?: return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (events.reset) {
                if (lastInstanceEpoch != 0 && instanceEpoch != lastInstanceEpoch) {
                    archiveMapTotalsLocked(uiState)
                }
                lastInstanceEpoch = instanceEpoch
                connectionsMap.clear()
            } else if (lastInstanceEpoch == 0 && instanceEpoch != 0) {
                lastInstanceEpoch = instanceEpoch
            }
            val iter = events.iterator()
            if (iter != null) {
                val typeNew = Libbox.ConnectionEventNew.toInt()
                val typeUpdate = Libbox.ConnectionEventUpdate.toInt()
                val typeClosed = Libbox.ConnectionEventClosed.toInt()

                while (iter.hasNext()) {
                    val ev = iter.next() ?: continue
                    val id = ev.id ?: continue
                    when (ev.type) {
                        typeNew -> {
                            val conn = ev.connection
                            if (conn != null) {
                                connectionsMap[id] = fromLibboxConnection(conn, now)
                            }
                        }

                        typeUpdate -> {
                            val existing = connectionsMap[id]
                            if (existing != null) {
                                existing.uplinkBps = max(0L, ev.uplinkDelta)
                                existing.downlinkBps = max(0L, ev.downlinkDelta)
                                existing.uplinkTotal += max(0L, ev.uplinkDelta)
                                existing.downlinkTotal += max(0L, ev.downlinkDelta)
                                existing.lastRateAtMs = now
                                existing.lastSeenAtMs = now
                            }
                        }

                        typeClosed -> {
                            val closedConn = ev.connection
                            val closedTs = if (ev.closedAt > 0L) ev.closedAt else now
                            val existing = connectionsMap[id]
                            if (closedConn != null) {
                                val updated = fromLibboxConnection(closedConn, now).apply {
                                    closedAtMs = closedTs
                                    uplinkBps = 0L
                                    downlinkBps = 0L
                                    if (existing != null) {
                                        if (domain.isBlank()) domain = existing.domain
                                        uplinkTotal = max(uplinkTotal, existing.uplinkTotal)
                                        downlinkTotal = max(downlinkTotal, existing.downlinkTotal)
                                    }
                                }
                                connectionsMap[id] = updated
                            } else if (existing != null) {
                                existing.closedAtMs = closedTs
                                existing.uplinkBps = 0L
                                existing.downlinkBps = 0L
                                existing.lastSeenAtMs = now
                            }
                        }
                    }
                }
            }
            evictExcessClosedLocked(uiState)
            publishSnapshotLocked(uiState, now, pushHistory = false)
        }
    }

    /**
     * Called once every 1000ms while tunnel is running so live speeds decay smoothly to 0 B/s
     * when idle and the 30-second dual-series sparkline advances in real time.
     */
    fun tick1Hz(uiState: SingScaleUiState, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            for (c in connectionsMap.values) {
                if (c.closedAtMs != 0L || nowMs - c.lastRateAtMs > RATE_STALE_MS) {
                    c.uplinkBps = 0L
                    c.downlinkBps = 0L
                }
            }
            publishSnapshotLocked(uiState, nowMs, pushHistory = true)
        }
    }

    /**
     * Pure-Kotlin helper used by unit tests to inject synthetic connection records without JNI.
     */
    internal fun ingestSyntheticRecordForTest(record: RawConnData, uiState: SingScaleUiState, pushHistory: Boolean = true) {
        synchronized(lock) {
            connectionsMap[record.id] = record
            evictExcessClosedLocked(uiState)
            publishSnapshotLocked(uiState, record.lastSeenAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(), pushHistory)
        }
    }

    private fun fromLibboxConnection(conn: Connection, now: Long): RawConnData {
        val chainList = mutableListOf<String>()
        val chainIter = runCatching { conn.chain() }.getOrNull()
        if (chainIter != null) {
            while (chainIter.hasNext()) {
                val tag = chainIter.next()
                if (!tag.isNullOrBlank()) chainList.add(tag)
            }
        }

        var pkgName = ""
        val pInfo = runCatching { conn.processInfo }.getOrNull()
        if (pInfo != null) {
            val pkgIter = runCatching { pInfo.packageNames() }.getOrNull()
            if (pkgIter != null && pkgIter.hasNext()) {
                pkgName = pkgIter.next().orEmpty()
            }
        }

        val dest = conn.destination.orEmpty()
        val hostPart = extractHost(dest)
        val rawDomain = conn.domain.orEmpty().trim().trimEnd('.')
        val resolvedDomain = rawDomain.ifBlank { ipToDomainCache[hostPart].orEmpty() }

        return RawConnData(
            id = conn.id.orEmpty(),
            domain = resolvedDomain,
            destination = dest,
            source = conn.source.orEmpty(),
            network = conn.network.orEmpty().uppercase(),
            protocol = conn.protocol.orEmpty().uppercase(),
            outboundTag = conn.outbound.orEmpty(),
            chain = chainList,
            rule = conn.rule.orEmpty(),
            createdAtMs = conn.createdAt.takeIf { it > 0L } ?: now,
            closedAtMs = conn.closedAt,
            uplinkTotal = max(0L, conn.uplinkTotal),
            downlinkTotal = max(0L, conn.downlinkTotal),
            uplinkBps = max(0L, conn.uplink),
            downlinkBps = max(0L, conn.downlink),
            lastRateAtMs = now,
            lastSeenAtMs = now,
            packageName = pkgName
        )
    }

    private fun archiveMapTotalsLocked(uiState: SingScaleUiState) {
        for (c in connectionsMap.values) {
            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination)
            if (cat.isTunnelRouted) {
                archivedRelayUp += c.uplinkTotal
                archivedRelayDown += c.downlinkTotal
                if (cat == TrafficRouteCategory.TAILNET_MESH) {
                    archivedMeshBytes += (c.uplinkTotal + c.downlinkTotal)
                }
            } else {
                archivedDirectUp += c.uplinkTotal
                archivedDirectDown += c.downlinkTotal
            }
        }
    }

    private fun evictExcessClosedLocked(uiState: SingScaleUiState) {
        val closedEntries = connectionsMap.values.filter { it.closedAtMs != 0L }
        if (closedEntries.size <= MAX_CLOSED_RETENTION) return
        val toEvict = closedEntries
            .sortedBy { it.closedAtMs }
            .take(closedEntries.size - MAX_CLOSED_RETENTION)
        for (c in toEvict) {
            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination)
            if (cat.isTunnelRouted) {
                archivedRelayUp += c.uplinkTotal
                archivedRelayDown += c.downlinkTotal
                if (cat == TrafficRouteCategory.TAILNET_MESH) {
                    archivedMeshBytes += (c.uplinkTotal + c.downlinkTotal)
                }
            } else {
                archivedDirectUp += c.uplinkTotal
                archivedDirectDown += c.downlinkTotal
            }
            connectionsMap.remove(c.id)
        }
    }

    private fun publishSnapshotLocked(uiState: SingScaleUiState, nowMs: Long, pushHistory: Boolean) {
        val peerByIp = HashMap<String, TailnetPeer>()
        for (peer in uiState.peers) {
            for (ip in peer.tailscaleIPs) {
                peerByIp[ip] = peer
            }
        }

        var liveRelayUpBps = 0L
        var liveRelayDownBps = 0L
        var liveDirectUpBps = 0L
        var liveDirectDownBps = 0L

        var mapRelayUpBytes = 0L
        var mapRelayDownBytes = 0L
        var mapDirectUpBytes = 0L
        var mapDirectDownBytes = 0L
        var mapMeshBytes = 0L

        var activeRelayCount = 0
        var activeDirectCount = 0

        val items = ArrayList<TrackedConnectionItem>(connectionsMap.size)

        for (c in connectionsMap.values) {
            val destHost = extractHost(c.destination)
            val peerMatch = peerByIp[destHost]
            val enrichedDomain = when {
                c.domain.isNotBlank() -> c.domain
                peerMatch != null -> "${peerMatch.displayName} (${destHost})"
                else -> ipToDomainCache[destHost].orEmpty()
            }
            val effectiveHost = enrichedDomain.ifBlank { destHost.ifBlank { c.destination.ifBlank { "未知目标" } } }

            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination)
            val nodeLabel = resolveRouteNodeLabel(cat, c.outboundTag, peerMatch, uiState)
            val chainLabel = if (c.chain.isNotEmpty()) {
                c.chain.asReversed().joinToString(" → ")
            } else {
                c.outboundTag.ifBlank { "direct" }
            }

            if (cat.isTunnelRouted) {
                liveRelayUpBps += c.uplinkBps
                liveRelayDownBps += c.downlinkBps
                mapRelayUpBytes += c.uplinkTotal
                mapRelayDownBytes += c.downlinkTotal
                if (cat == TrafficRouteCategory.TAILNET_MESH) {
                    mapMeshBytes += (c.uplinkTotal + c.downlinkTotal)
                }
                if (c.closedAtMs == 0L) activeRelayCount++
            } else {
                liveDirectUpBps += c.uplinkBps
                liveDirectDownBps += c.downlinkBps
                mapDirectUpBytes += c.uplinkTotal
                mapDirectDownBytes += c.downlinkTotal
                if (c.closedAtMs == 0L) activeDirectCount++
            }

            items.add(
                TrackedConnectionItem(
                    id = c.id,
                    domain = enrichedDomain,
                    effectiveHost = effectiveHost,
                    destination = c.destination,
                    source = c.source,
                    network = c.network.ifBlank { "TCP" },
                    protocol = c.protocol,
                    routeCategory = cat,
                    outboundTag = c.outboundTag,
                    routeNodeLabel = nodeLabel,
                    chainSummary = chainLabel,
                    matchedRule = humanizeRule(c.rule, cat),
                    uplinkBps = c.uplinkBps,
                    downlinkBps = c.downlinkBps,
                    uplinkTotal = c.uplinkTotal,
                    downlinkTotal = c.downlinkTotal,
                    createdAtMs = c.createdAtMs,
                    closedAtMs = c.closedAtMs,
                    packageName = c.packageName
                )
            )
        }

        // Sort connections: active with live traffic first, then active newest, then closed newest
        items.sortWith(
            compareByDescending<TrackedConnectionItem> { it.isActive }
                .thenByDescending { it.totalBps }
                .thenByDescending { max(it.createdAtMs, it.closedAtMs) }
        )

        // Aggregate by effectiveHost (Domain / Host Leaderboard)
        val domainGroups = items.groupBy { it.effectiveHost.lowercase() }
        val domainSummaries = ArrayList<DomainTrafficSummary>(domainGroups.size)
        for ((_, group) in domainGroups) {
            val sample = group.first()
            var dUpBps = 0L
            var dDownBps = 0L
            var dUpTotal = 0L
            var dDownTotal = 0L
            var actCnt = 0
            var relayBytesInGroup = 0L
            var directBytesInGroup = 0L
            var latestMs = 0L
            val protoSet = LinkedHashSet<String>()
            val addrSet = LinkedHashSet<String>()

            for (g in group) {
                dUpBps += g.uplinkBps
                dDownBps += g.downlinkBps
                dUpTotal += g.uplinkTotal
                dDownTotal += g.downlinkTotal
                if (g.isActive) actCnt++
                if (g.routeCategory.isTunnelRouted) {
                    relayBytesInGroup += g.totalBytes
                } else {
                    directBytesInGroup += g.totalBytes
                }
                val ts = max(g.createdAtMs, g.closedAtMs)
                if (ts > latestMs) latestMs = ts
                if (g.protocol.isNotBlank()) protoSet.add(g.protocol)
                if (g.network.isNotBlank()) protoSet.add(g.network)
                if (g.destination.isNotBlank()) addrSet.add(g.destination)
            }

            val dominantItem = if (relayBytesInGroup >= directBytesInGroup) {
                group.firstOrNull { it.routeCategory.isTunnelRouted } ?: sample
            } else {
                group.firstOrNull { !it.routeCategory.isTunnelRouted } ?: sample
            }

            val hostDisplay = sample.effectiveHost
            val pureIp = sample.domain.isBlank() && isIpLiteral(extractHost(sample.destination))

            domainSummaries.add(
                DomainTrafficSummary(
                    host = hostDisplay,
                    isPureIp = pureIp,
                    primaryCategory = dominantItem.routeCategory,
                    routeNodeLabel = dominantItem.routeNodeLabel,
                    uplinkBps = dUpBps,
                    downlinkBps = dDownBps,
                    uplinkTotal = dUpTotal,
                    downlinkTotal = dDownTotal,
                    activeConnections = actCnt,
                    totalConnections = group.size,
                    protocols = protoSet.take(4),
                    targetAddresses = addrSet.take(4),
                    matchedRule = dominantItem.matchedRule,
                    lastActiveAtMs = latestMs
                )
            )
        }

        domainSummaries.sortWith(
            compareByDescending<DomainTrafficSummary> { it.totalBps > 0L }
                .thenByDescending { it.totalBps }
                .thenByDescending { it.totalBytes }
                .thenByDescending { it.lastActiveAtMs }
        )

        if (pushHistory) {
            if (relayHistory.size >= HISTORY_POINTS) relayHistory.removeFirst()
            relayHistory.addLast((liveRelayUpBps + liveRelayDownBps).toFloat())

            if (directHistory.size >= HISTORY_POINTS) directHistory.removeFirst()
            directHistory.addLast((liveDirectUpBps + liveDirectDownBps).toFloat())
        }

        _state.value = TrafficSplitSnapshot(
            relayUpBps = liveRelayUpBps,
            relayDownBps = liveRelayDownBps,
            directUpBps = liveDirectUpBps,
            directDownBps = liveDirectDownBps,
            relayUpBytes = archivedRelayUp + mapRelayUpBytes,
            relayDownBytes = archivedRelayDown + mapRelayDownBytes,
            directUpBytes = archivedDirectUp + mapDirectUpBytes,
            directDownBytes = archivedDirectDown + mapDirectDownBytes,
            meshBytes = archivedMeshBytes + mapMeshBytes,
            activeRelayConnections = activeRelayCount,
            activeDirectConnections = activeDirectCount,
            relayBpsHistory = relayHistory.toList(),
            directBpsHistory = directHistory.toList(),
            domains = domainSummaries,
            connections = items,
            updatedAtMs = nowMs
        )
    }

    internal fun classifyRouteCategory(
        outboundTag: String,
        chain: List<String>,
        destination: String
    ): TrafficRouteCategory {
        val leaf = outboundTag.lowercase()
        if (leaf == ConfigBuilder.OUTBOUND_DIRECT || (leaf.isBlank() && chain.any { it.equals(ConfigBuilder.OUTBOUND_DIRECT, true) })) {
            return TrafficRouteCategory.DIRECT
        }
        if (leaf == ConfigBuilder.OUTBOUND_EXIT || leaf == ConfigBuilder.OUTBOUND_RELAY ||
            chain.any { it == ConfigBuilder.OUTBOUND_EXIT || it == ConfigBuilder.OUTBOUND_RELAY }
        ) {
            return TrafficRouteCategory.RELAY_EXIT
        }
        if (leaf == ConfigBuilder.ENDPOINT_TAG || chain.any { it == ConfigBuilder.ENDPOINT_TAG }) {
            val host = extractHost(destination)
            return if (isTailnetIp(host)) {
                TrafficRouteCategory.TAILNET_MESH
            } else {
                TrafficRouteCategory.RELAY_EXIT
            }
        }
        return TrafficRouteCategory.DIRECT
    }

    private fun resolveRouteNodeLabel(
        category: TrafficRouteCategory,
        outboundTag: String,
        destPeer: TailnetPeer?,
        uiState: SingScaleUiState
    ): String {
        return when (category) {
            TrafficRouteCategory.DIRECT -> "本地直连"
            TrafficRouteCategory.TAILNET_MESH -> {
                destPeer?.displayName?.let { "组网直达 · $it" } ?: "组网内网"
            }
            TrafficRouteCategory.RELAY_EXIT -> {
                val exitName = uiState.activeExitPeer?.displayName
                val relayName = uiState.activeRelayPeer?.displayName
                when {
                    outboundTag == ConfigBuilder.OUTBOUND_RELAY && !relayName.isNullOrBlank() -> "中继 · $relayName"
                    !relayName.isNullOrBlank() && !exitName.isNullOrBlank() && relayName != exitName -> "$relayName → $exitName"
                    !exitName.isNullOrBlank() -> "中继/出口 · $exitName"
                    !relayName.isNullOrBlank() -> "中继 · $relayName"
                    else -> "组网中继/出口"
                }
            }
        }
    }

    private fun humanizeRule(rawRule: String, category: TrafficRouteCategory): String {
        val r = rawRule.trim()
        if (r.isBlank()) {
            return if (category == TrafficRouteCategory.DIRECT) {
                "默认直连规则 (final → direct)"
            } else {
                "默认组网出口规则 (final → egress-select)"
            }
        }
        if (r.startsWith("direct-first(")) {
            val detail = r.substringAfter("direct-first(").substringBefore(")")
            return "实测直连良好 ($detail) → 优先本地直连"
        }
        if (r.startsWith("direct-poor(")) {
            val detail = r.substringAfter("direct-poor(").substringBefore(")")
            return "直连质量欠佳 ($detail) → 自动走中继/出口"
        }
        return when {
            r.contains("100.64.0.0/10") -> "Tailnet 内网段直达 ($r)"
            r.contains("ip_is_private") -> "局域网私网直连 ($r)"
            r.contains("tailscale.com") -> "Tailscale 控制面保活直连"
            r.contains("domain_suffix") && category == TrafficRouteCategory.DIRECT -> "自定义直连域名 ($r)"
            r.contains("domain_suffix") && category.isTunnelRouted -> "强制/规则代理域名分流 ($r)"
            else -> r
        }
    }

    private fun extractHost(addrPort: String): String {
        val s = addrPort.trim()
        if (s.isEmpty()) return ""
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 1) return s.substring(1, end)
        }
        val lastColon = s.lastIndexOf(':')
        val firstColon = s.indexOf(':')
        return if (lastColon > 0 && firstColon == lastColon) {
            s.substring(0, lastColon)
        } else {
            s
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.isBlank()) return false
        if (host.contains(':')) return true // IPv6 literal
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun isTailnetIp(host: String): Boolean {
        val h = host.lowercase()
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }
}
