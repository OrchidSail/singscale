package dev.singscale.app.core

import androidx.compose.runtime.Immutable
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.EnumMap
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

/**
 * Three mutually-exclusive routing traffic categories:
 * - [RELAY_EXIT]: Internet traffic forwarded via Exit Node / Peer Relay / Proxy-over-Tailscale.
 * - [TAILNET_MESH]: Direct Tailnet mesh traffic (`ts-mesh` -> `ts-ep`: 100.64.0.0/10, fd7a:115c:a1e0::/48,
 *   MagicDNS peers, and advertised Tailnet subnet routes).
 * - [DIRECT]: Local physical ISP / LAN direct traffic (`direct`).
 */
enum class TrafficRouteCategory(
    val label: String,
    val badge: String,
    val shortLabel: String
) {
    RELAY_EXIT("中继 / 出口", "中继/出口", "中继"),
    TAILNET_MESH("组网直连", "组网直连", "组网直连"),
    DIRECT("本地直连", "本地直连", "本地直连")
}

@Immutable
data class KernelBucketTotals(
    val uplinkBytes: Long = 0L,
    val downlinkBytes: Long = 0L,
    val activeConnections: Int = 0,
    val totalConnections: Int = 0
)

@Immutable
data class KernelTrafficSplit(
    val direct: KernelBucketTotals = KernelBucketTotals(),
    val mesh: KernelBucketTotals = KernelBucketTotals(),
    val relay: KernelBucketTotals = KernelBucketTotals()
)

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
    val packageName: String = "",
    val transportPath: TransportPathType = TransportPathType.OFFLINE
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
    val lastActiveAtMs: Long,
    val transportPath: TransportPathType = TransportPathType.OFFLINE
) {
    val totalBps: Long get() = uplinkBps + downlinkBps
    val totalBytes: Long get() = uplinkTotal + downlinkTotal
}

@Immutable
data class TrafficSplitSnapshot(
    // 1. 中继 / 出口 (Exit Node / Peer Relay / Proxy-over-TS) — strictly excludes Tailnet Mesh
    val relayUpBps: Long = 0L,
    val relayDownBps: Long = 0L,
    val relayUpBytes: Long = 0L,
    val relayDownBytes: Long = 0L,
    val activeRelayConnections: Int = 0,
    val totalRelayConnections: Int = 0,
    val relayBpsHistory: List<Float> = List(30) { 0f },

    // 2. 组网直连 (Tailnet Mesh: 100.64.0.0/10, fd7a:115c:a1e0::/48, MagicDNS, advertised subnets)
    val meshUpBps: Long = 0L,
    val meshDownBps: Long = 0L,
    val meshUpBytes: Long = 0L,
    val meshDownBytes: Long = 0L,
    val activeMeshConnections: Int = 0,
    val totalMeshConnections: Int = 0,
    val meshBpsHistory: List<Float> = List(30) { 0f },

    // 3. 本地直连 (Physical ISP / LAN direct)
    val directUpBps: Long = 0L,
    val directDownBps: Long = 0L,
    val directUpBytes: Long = 0L,
    val directDownBytes: Long = 0L,
    val activeDirectConnections: Int = 0,
    val totalDirectConnections: Int = 0,
    val directBpsHistory: List<Float> = List(30) { 0f },

    val domains: List<DomainTrafficSummary> = emptyList(),
    val connections: List<TrackedConnectionItem> = emptyList(),
    val updatedAtMs: Long = 0L
) {
    val relayTotalBps: Long get() = relayUpBps + relayDownBps
    val meshTotalBps: Long get() = meshUpBps + meshDownBps
    val directTotalBps: Long get() = directUpBps + directDownBps
    val combinedUpBps: Long get() = relayUpBps + meshUpBps + directUpBps
    val combinedDownBps: Long get() = relayDownBps + meshDownBps + directDownBps
    val combinedBps: Long get() = relayTotalBps + meshTotalBps + directTotalBps

    val relayTotalBytes: Long get() = relayUpBytes + relayDownBytes
    val meshTotalBytes: Long get() = meshUpBytes + meshDownBytes
    val meshBytes: Long get() = meshTotalBytes
    val directTotalBytes: Long get() = directUpBytes + directDownBytes
    val combinedUpBytes: Long get() = relayUpBytes + meshUpBytes + directUpBytes
    val combinedDownBytes: Long get() = relayDownBytes + meshDownBytes + directDownBytes
    val combinedBytes: Long get() = relayTotalBytes + meshTotalBytes + directTotalBytes

    val totalActiveConnections: Int
        get() = activeRelayConnections + activeMeshConnections + activeDirectConnections

    val totalTrackedConnections: Int
        get() = totalRelayConnections + totalMeshConnections + totalDirectConnections

    val relayShareRatio: Float get() = computeRatio(relayTotalBytes, relayTotalBps)
    val meshShareRatio: Float get() = computeRatio(meshTotalBytes, meshTotalBps)
    val directShareRatio: Float get() = computeRatio(directTotalBytes, directTotalBps)

    val relaySharePercent: Float
        get() = if (combinedBytes > 0L) {
            ((relayTotalBytes.toDouble() * 100.0) / combinedBytes.toDouble()).toFloat().coerceIn(0f, 100f)
        } else 0f

    val meshSharePercent: Float
        get() = if (combinedBytes > 0L) {
            ((meshTotalBytes.toDouble() * 100.0) / combinedBytes.toDouble()).toFloat().coerceIn(0f, 100f)
        } else 0f

    val directSharePercent: Float
        get() = if (combinedBytes > 0L) {
            (100.0 - relaySharePercent.toDouble() - meshSharePercent.toDouble()).toFloat().coerceIn(0f, 100f)
        } else 0f

    private fun computeRatio(bucketBytes: Long, bucketBps: Long): Float = when {
        combinedBytes > 0L -> (bucketBytes.toDouble() / combinedBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        combinedBps > 0L -> (bucketBps.toDouble() / combinedBps.toDouble()).toFloat().coerceIn(0f, 1f)
        else -> 0f
    }
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
 * Real-time 3-way split traffic & per-domain/per-connection telemetry tracker powered by sing-box
 * `CommandConnections` stream, kernel-level `SingScaleGetTrafficSplitJSON` reconciliation,
 * and DNS reverse-resolution enrichment.
 *
 * Guarantees strict mutual exclusivity and sum conservation across:
 *   1. 中继 / 出口 ([TrafficRouteCategory.RELAY_EXIT])
 *   2. 组网直连 ([TrafficRouteCategory.TAILNET_MESH])
 *   3. 本地直连 ([TrafficRouteCategory.DIRECT])
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

    private data class BucketTotals(
        var upBytes: Long = 0L,
        var downBytes: Long = 0L,
        var totalConnections: Int = 0
    ) {
        fun add(up: Long, down: Long, conns: Int) {
            upBytes += max(0L, up)
            downBytes += max(0L, down)
            totalConnections += max(0, conns)
        }

        fun reset() {
            upBytes = 0L
            downBytes = 0L
            totalConnections = 0
        }
    }

    private fun newBucketMap(): EnumMap<TrafficRouteCategory, BucketTotals> =
        EnumMap<TrafficRouteCategory, BucketTotals>(TrafficRouteCategory::class.java).apply {
            for (cat in TrafficRouteCategory.entries) {
                put(cat, BucketTotals())
            }
        }

    private val lock = Any()
    private val connectionsMap = LinkedHashMap<String, RawConnData>()
    private val ipToDomainCache = object : LinkedHashMap<String, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > MAX_DNS_CACHE
        }
    }

    // Finalized totals from previous sing-box instances before hot-reloads
    private val priorEpochsArchived = newBucketMap()

    // Evicted closed-connection totals (from connectionsMap cap OR Go trafficcontrol eviction) for current epoch
    private val currentEpochEvicted = newBucketMap()
    private var lastInstanceEpoch: Int = 0

    private val relayHistory = ArrayDeque<Float>(HISTORY_POINTS).apply { repeat(HISTORY_POINTS) { addLast(0f) } }
    private val meshHistory = ArrayDeque<Float>(HISTORY_POINTS).apply { repeat(HISTORY_POINTS) { addLast(0f) } }
    private val directHistory = ArrayDeque<Float>(HISTORY_POINTS).apply { repeat(HISTORY_POINTS) { addLast(0f) } }

    private val _state = MutableStateFlow(TrafficSplitSnapshot())
    val state: StateFlow<TrafficSplitSnapshot> = _state.asStateFlow()

    /**
     * Computes sum-conserving 1-decimal percentage tenths (0..1000, summing to 1000 when total > 0)
     * for `(relay, mesh, direct)` using the largest-remainder method so UI labels ALWAYS sum to 100.0%.
     */
    fun computeDisplayTenths(relayBytes: Long, meshBytes: Long, directBytes: Long): Triple<Int, Int, Int> {
        val r = max(0L, relayBytes)
        val m = max(0L, meshBytes)
        val d = max(0L, directBytes)
        val total = r + m + d
        if (total <= 0L) return Triple(0, 0, 0)

        val values = longArrayOf(r, m, d)
        val floors = IntArray(3)
        val remainders = DoubleArray(3)
        var sumFloors = 0
        for (i in 0..2) {
            val exact = values[i].toDouble() * 1000.0 / total.toDouble()
            val f = floor(exact).toInt().coerceIn(0, 1000)
            floors[i] = f
            remainders[i] = exact - f
            sumFloors += f
        }
        var leftover = (1000 - sumFloors).coerceAtLeast(0)
        val order = listOf(0, 1, 2).sortedWith(
            compareByDescending<Int> { remainders[it] }
                .thenByDescending { values[it] }
        )
        var idx = 0
        while (leftover > 0 && idx < order.size) {
            val bucketIdx = order[idx]
            if (values[bucketIdx] > 0L) {
                floors[bucketIdx]++
                leftover--
            }
            idx++
        }
        return Triple(floors[0], floors[1], floors[2])
    }

    fun computeDisplayPercents(relayBytes: Long, meshBytes: Long, directBytes: Long): Triple<String, String, String> {
        val (rt, mt, dt) = computeDisplayTenths(relayBytes, meshBytes, directBytes)
        return Triple(
            String.format(Locale.US, "%.1f%%", rt / 10.0),
            String.format(Locale.US, "%.1f%%", mt / 10.0),
            String.format(Locale.US, "%.1f%%", dt / 10.0)
        )
    }

    fun resetSession() {
        synchronized(lock) {
            connectionsMap.clear()
            ipToDomainCache.clear()
            for (cat in TrafficRouteCategory.entries) {
                priorEpochsArchived[cat]!!.reset()
                currentEpochEvicted[cat]!!.reset()
            }
            lastInstanceEpoch = 0
            relayHistory.clear()
            meshHistory.clear()
            directHistory.clear()
            repeat(HISTORY_POINTS) {
                relayHistory.addLast(0f)
                meshHistory.addLast(0f)
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
            for (entry in connectionsMap.values) {
                if (entry.domain.isBlank() && extractHost(entry.destination) == cleanIp) {
                    entry.domain = cleanDomain
                }
            }
        }
    }

    /**
     * Reconciles exact kernel-level cumulative 3-way bucket totals from Go `trafficcontrol.Manager`
     * so connections evicted while UI telemetry was paused in background/screen-off are never lost
     * and never double-counted.
     */
    fun reconcileKernelTotals(
        kernelSplit: KernelTrafficSplit,
        uiState: SingScaleUiState,
        instanceEpoch: Int
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (lastInstanceEpoch == 0 && instanceEpoch != 0) {
                lastInstanceEpoch = instanceEpoch
            } else if (instanceEpoch != 0 && lastInstanceEpoch != 0 && instanceEpoch != lastInstanceEpoch) {
                // Ignore stale response from a previous instance epoch
                return
            }

            val mapTotals = computeMapTotalsLocked()
            val kernelByCat = mapOf(
                TrafficRouteCategory.DIRECT to kernelSplit.direct,
                TrafficRouteCategory.TAILNET_MESH to kernelSplit.mesh,
                TrafficRouteCategory.RELAY_EXIT to kernelSplit.relay
            )
            for (cat in TrafficRouteCategory.entries) {
                val kernel = kernelByCat[cat] ?: continue
                val mapB = mapTotals[cat]!!
                val evB = currentEpochEvicted[cat]!!
                val missingUp = max(0L, kernel.uplinkBytes - mapB.upBytes)
                val missingDown = max(0L, kernel.downlinkBytes - mapB.downBytes)
                val missingConns = max(0, kernel.totalConnections - mapB.totalConnections)
                if (missingUp > evB.upBytes) evB.upBytes = missingUp
                if (missingDown > evB.downBytes) evB.downBytes = missingDown
                if (missingConns > evB.totalConnections) evB.totalConnections = missingConns
            }
            publishSnapshotLocked(uiState, now, pushHistory = false)
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
            var sameEpochPreResetFloor: EnumMap<TrafficRouteCategory, BucketTotals>? = null

            if (events.reset) {
                if (lastInstanceEpoch != 0 && instanceEpoch != lastInstanceEpoch) {
                    finalizeCurrentEpochIntoPriorLocked()
                } else if (lastInstanceEpoch == instanceEpoch) {
                    // Stream reconnected on the same instance epoch (e.g., returning from background):
                    // snapshot monotonic floor so totals never dip before kernel reconciliation arrives.
                    sameEpochPreResetFloor = snapshotCurrentEpochTotalsLocked()
                    for (cat in TrafficRouteCategory.entries) {
                        currentEpochEvicted[cat]!!.reset()
                    }
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

            if (sameEpochPreResetFloor != null) {
                applyMonotonicFloorLocked(sameEpochPreResetFloor)
            }
            evictExcessClosedLocked()
            publishSnapshotLocked(uiState, now, pushHistory = false)
        }
    }

    /**
     * Called once every ~1.5s ONLY while app is foreground & screen is ON so live speeds decay
     * smoothly to 0 B/s when idle and the 30-point 3-way sparkline advances cleanly.
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
    internal fun ingestSyntheticRecordForTest(
        record: RawConnData,
        uiState: SingScaleUiState,
        pushHistory: Boolean = true,
        instanceEpoch: Int = 1
    ) {
        synchronized(lock) {
            if (lastInstanceEpoch == 0 && instanceEpoch != 0) {
                lastInstanceEpoch = instanceEpoch
            }
            connectionsMap[record.id] = record
            evictExcessClosedLocked()
            publishSnapshotLocked(
                uiState,
                record.lastSeenAtMs.takeIf { it > 0L } ?: System.currentTimeMillis(),
                pushHistory
            )
        }
    }

    /**
     * Pure-Kotlin helper used by unit tests to simulate `ConnectionEvents(reset = true)` across
     * same-epoch foreground reconnects and new-epoch hot-reloads.
     */
    internal fun simulateResetBatchForTest(
        newRecords: List<RawConnData>,
        uiState: SingScaleUiState,
        instanceEpoch: Int,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(lock) {
            var sameEpochFloor: EnumMap<TrafficRouteCategory, BucketTotals>? = null
            if (lastInstanceEpoch != 0 && instanceEpoch != lastInstanceEpoch) {
                finalizeCurrentEpochIntoPriorLocked()
            } else if (lastInstanceEpoch == instanceEpoch) {
                sameEpochFloor = snapshotCurrentEpochTotalsLocked()
                for (cat in TrafficRouteCategory.entries) {
                    currentEpochEvicted[cat]!!.reset()
                }
            }
            lastInstanceEpoch = instanceEpoch
            connectionsMap.clear()
            for (r in newRecords) {
                connectionsMap[r.id] = r
            }
            if (sameEpochFloor != null) {
                applyMonotonicFloorLocked(sameEpochFloor)
            }
            evictExcessClosedLocked()
            publishSnapshotLocked(uiState, nowMs, pushHistory = false)
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

    private fun computeMapTotalsLocked(): EnumMap<TrafficRouteCategory, BucketTotals> {
        val totals = newBucketMap()
        for (c in connectionsMap.values) {
            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination, c.rule)
            totals[cat]!!.add(c.uplinkTotal, c.downlinkTotal, 1)
        }
        return totals
    }

    private fun snapshotCurrentEpochTotalsLocked(): EnumMap<TrafficRouteCategory, BucketTotals> {
        val mapTotals = computeMapTotalsLocked()
        val combined = newBucketMap()
        for (cat in TrafficRouteCategory.entries) {
            val ev = currentEpochEvicted[cat]!!
            val mp = mapTotals[cat]!!
            combined[cat]!!.add(ev.upBytes + mp.upBytes, ev.downBytes + mp.downBytes, ev.totalConnections + mp.totalConnections)
        }
        return combined
    }

    private fun finalizeCurrentEpochIntoPriorLocked() {
        val current = snapshotCurrentEpochTotalsLocked()
        for (cat in TrafficRouteCategory.entries) {
            val c = current[cat]!!
            priorEpochsArchived[cat]!!.add(c.upBytes, c.downBytes, c.totalConnections)
            currentEpochEvicted[cat]!!.reset()
        }
    }

    private fun applyMonotonicFloorLocked(prevEpochTotals: EnumMap<TrafficRouteCategory, BucketTotals>) {
        val newMapTotals = computeMapTotalsLocked()
        for (cat in TrafficRouteCategory.entries) {
            val prev = prevEpochTotals[cat]!!
            val curMap = newMapTotals[cat]!!
            val ev = currentEpochEvicted[cat]!!
            ev.upBytes = max(ev.upBytes, max(0L, prev.upBytes - curMap.upBytes))
            ev.downBytes = max(ev.downBytes, max(0L, prev.downBytes - curMap.downBytes))
            ev.totalConnections = max(ev.totalConnections, max(0, prev.totalConnections - curMap.totalConnections))
        }
    }

    private fun evictExcessClosedLocked() {
        val closedEntries = connectionsMap.values.filter { it.closedAtMs != 0L }
        if (closedEntries.size <= MAX_CLOSED_RETENTION) return
        val toEvict = closedEntries
            .sortedBy { it.closedAtMs }
            .take(closedEntries.size - MAX_CLOSED_RETENTION)
        for (c in toEvict) {
            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination, c.rule)
            currentEpochEvicted[cat]!!.add(c.uplinkTotal, c.downlinkTotal, 1)
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
        var liveMeshUpBps = 0L
        var liveMeshDownBps = 0L
        var liveDirectUpBps = 0L
        var liveDirectDownBps = 0L

        val mapTotals = newBucketMap()
        var activeRelayCount = 0
        var activeMeshCount = 0
        var activeDirectCount = 0

        val items = ArrayList<TrackedConnectionItem>(connectionsMap.size)

        for (c in connectionsMap.values) {
            val destHost = extractHost(c.destination)
            val peerMatch = peerByIp[destHost]
            val cat = classifyRouteCategory(c.outboundTag, c.chain, c.destination, c.rule)
            val subnetPeer = if (cat == TrafficRouteCategory.TAILNET_MESH && peerMatch == null) {
                findSubnetRouterPeer(destHost, uiState.peers)
            } else null

            val enrichedDomain = when {
                c.domain.isNotBlank() -> c.domain
                peerMatch != null -> "${peerMatch.displayName} ($destHost)"
                else -> ipToDomainCache[destHost].orEmpty()
            }
            val effectiveHost = enrichedDomain.ifBlank { destHost.ifBlank { c.destination.ifBlank { "未知目标" } } }

            val nodeLabel = resolveRouteNodeLabel(cat, c.outboundTag, peerMatch, subnetPeer, uiState)
            val transportPath = resolveTransportPath(cat, peerMatch, subnetPeer, uiState)
            val chainLabel = if (c.chain.isNotEmpty()) {
                c.chain.asReversed().joinToString(" → ")
            } else {
                c.outboundTag.ifBlank { "direct" }
            }

            mapTotals[cat]!!.add(c.uplinkTotal, c.downlinkTotal, 1)
            when (cat) {
                TrafficRouteCategory.RELAY_EXIT -> {
                    liveRelayUpBps += c.uplinkBps
                    liveRelayDownBps += c.downlinkBps
                    if (c.closedAtMs == 0L) activeRelayCount++
                }
                TrafficRouteCategory.TAILNET_MESH -> {
                    liveMeshUpBps += c.uplinkBps
                    liveMeshDownBps += c.downlinkBps
                    if (c.closedAtMs == 0L) activeMeshCount++
                }
                TrafficRouteCategory.DIRECT -> {
                    liveDirectUpBps += c.uplinkBps
                    liveDirectDownBps += c.downlinkBps
                    if (c.closedAtMs == 0L) activeDirectCount++
                }
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
                    packageName = c.packageName,
                    transportPath = transportPath
                )
            )
        }

        // Sort connections: active with live traffic first, then active newest, then closed newest
        items.sortWith(
            compareByDescending<TrackedConnectionItem> { it.isActive }
                .thenByDescending { it.totalBps }
                .thenByDescending { max(it.createdAtMs, it.closedAtMs) }
        )

        // Aggregate by effectiveHost across all 3 mutually-exclusive categories
        val domainGroups = items.groupBy { it.effectiveHost.lowercase() }
        val domainSummaries = ArrayList<DomainTrafficSummary>(domainGroups.size)
        for ((_, group) in domainGroups) {
            val sample = group.first()
            var dUpBps = 0L
            var dDownBps = 0L
            var dUpTotal = 0L
            var dDownTotal = 0L
            var actCnt = 0
            val bytesByCat = EnumMap<TrafficRouteCategory, Long>(TrafficRouteCategory::class.java)
            val bpsByCat = EnumMap<TrafficRouteCategory, Long>(TrafficRouteCategory::class.java)
            var latestMs = 0L
            val protoSet = LinkedHashSet<String>()
            val addrSet = LinkedHashSet<String>()

            for (g in group) {
                dUpBps += g.uplinkBps
                dDownBps += g.downlinkBps
                dUpTotal += g.uplinkTotal
                dDownTotal += g.downlinkTotal
                if (g.isActive) actCnt++
                bytesByCat[g.routeCategory] = (bytesByCat[g.routeCategory] ?: 0L) + g.totalBytes
                bpsByCat[g.routeCategory] = (bpsByCat[g.routeCategory] ?: 0L) + g.totalBps
                val ts = max(g.createdAtMs, g.closedAtMs)
                if (ts > latestMs) latestMs = ts
                if (g.protocol.isNotBlank()) protoSet.add(g.protocol)
                if (g.network.isNotBlank()) protoSet.add(g.network)
                if (g.destination.isNotBlank()) addrSet.add(g.destination)
            }

            val dominantCategory = TrafficRouteCategory.entries
                .filter { cat -> group.any { it.routeCategory == cat } }
                .maxWithOrNull(
                    compareBy<TrafficRouteCategory> { bytesByCat[it] ?: 0L }
                        .thenBy { bpsByCat[it] ?: 0L }
                        .thenBy { if (it == sample.routeCategory) 1 else 0 }
                ) ?: sample.routeCategory
            val dominantItem = group.firstOrNull { it.routeCategory == dominantCategory } ?: sample

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
                    lastActiveAtMs = latestMs,
                    transportPath = dominantItem.transportPath
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

            if (meshHistory.size >= HISTORY_POINTS) meshHistory.removeFirst()
            meshHistory.addLast((liveMeshUpBps + liveMeshDownBps).toFloat())

            if (directHistory.size >= HISTORY_POINTS) directHistory.removeFirst()
            directHistory.addLast((liveDirectUpBps + liveDirectDownBps).toFloat())
        }

        fun finalUp(cat: TrafficRouteCategory): Long =
            priorEpochsArchived[cat]!!.upBytes + currentEpochEvicted[cat]!!.upBytes + mapTotals[cat]!!.upBytes

        fun finalDown(cat: TrafficRouteCategory): Long =
            priorEpochsArchived[cat]!!.downBytes + currentEpochEvicted[cat]!!.downBytes + mapTotals[cat]!!.downBytes

        fun finalConns(cat: TrafficRouteCategory, active: Int): Int =
            max(
                active,
                priorEpochsArchived[cat]!!.totalConnections +
                    currentEpochEvicted[cat]!!.totalConnections +
                    mapTotals[cat]!!.totalConnections
            )

        _state.value = TrafficSplitSnapshot(
            relayUpBps = liveRelayUpBps,
            relayDownBps = liveRelayDownBps,
            relayUpBytes = finalUp(TrafficRouteCategory.RELAY_EXIT),
            relayDownBytes = finalDown(TrafficRouteCategory.RELAY_EXIT),
            activeRelayConnections = activeRelayCount,
            totalRelayConnections = finalConns(TrafficRouteCategory.RELAY_EXIT, activeRelayCount),
            relayBpsHistory = relayHistory.toList(),

            meshUpBps = liveMeshUpBps,
            meshDownBps = liveMeshDownBps,
            meshUpBytes = finalUp(TrafficRouteCategory.TAILNET_MESH),
            meshDownBytes = finalDown(TrafficRouteCategory.TAILNET_MESH),
            activeMeshConnections = activeMeshCount,
            totalMeshConnections = finalConns(TrafficRouteCategory.TAILNET_MESH, activeMeshCount),
            meshBpsHistory = meshHistory.toList(),

            directUpBps = liveDirectUpBps,
            directDownBps = liveDirectDownBps,
            directUpBytes = finalUp(TrafficRouteCategory.DIRECT),
            directDownBytes = finalDown(TrafficRouteCategory.DIRECT),
            activeDirectConnections = activeDirectCount,
            totalDirectConnections = finalConns(TrafficRouteCategory.DIRECT, activeDirectCount),
            directBpsHistory = directHistory.toList(),

            domains = domainSummaries,
            connections = items,
            updatedAtMs = nowMs
        )
    }

    /**
     * Exact mirror of Go `trafficcontrol.ClassifySingScaleBucket` so Android UI and sing-box kernel
     * agree 100% on Local Direct vs Tailnet Mesh Direct vs Relay/Exit bucketing.
     */
    internal fun classifyRouteCategory(
        outboundTag: String,
        chain: List<String>,
        destination: String,
        rule: String = ""
    ): TrafficRouteCategory {
        val leaf = outboundTag.trim().lowercase()
        fun hasChainTag(target: String): Boolean =
            chain.any { it.trim().equals(target, ignoreCase = true) }

        if (leaf == ConfigBuilder.OUTBOUND_DIRECT || (leaf.isBlank() && hasChainTag(ConfigBuilder.OUTBOUND_DIRECT))) {
            return TrafficRouteCategory.DIRECT
        }
        if (leaf == ConfigBuilder.OUTBOUND_EXIT || leaf == ConfigBuilder.OUTBOUND_RELAY ||
            hasChainTag(ConfigBuilder.OUTBOUND_EXIT) || hasChainTag(ConfigBuilder.OUTBOUND_RELAY)
        ) {
            return TrafficRouteCategory.RELAY_EXIT
        }
        if (leaf == ConfigBuilder.OUTBOUND_MESH || hasChainTag(ConfigBuilder.OUTBOUND_MESH)) {
            return TrafficRouteCategory.TAILNET_MESH
        }
        if (leaf == ConfigBuilder.ENDPOINT_TAG || hasChainTag(ConfigBuilder.ENDPOINT_TAG)) {
            val host = extractHost(destination)
            if (isTailnetIp(host)) {
                return TrafficRouteCategory.TAILNET_MESH
            }
            if (hasChainTag(ConfigBuilder.OUTBOUND_SELECTOR)) {
                return TrafficRouteCategory.RELAY_EXIT
            }
            if (chain.isNotEmpty()) {
                // Directly routed to ts-ep without egress-select (e.g. advertised Tailnet subnet route)
                return TrafficRouteCategory.TAILNET_MESH
            }
            if (rule.contains("100.64.0.0/10") || rule.lowercase().contains("fd7a:115c:a1e0::/48")) {
                return TrafficRouteCategory.TAILNET_MESH
            }
            return TrafficRouteCategory.RELAY_EXIT
        }
        if (hasChainTag(ConfigBuilder.OUTBOUND_SELECTOR)) {
            return TrafficRouteCategory.RELAY_EXIT
        }
        return TrafficRouteCategory.DIRECT
    }

    private fun resolveRouteNodeLabel(
        category: TrafficRouteCategory,
        outboundTag: String,
        destPeer: TailnetPeer?,
        subnetPeer: TailnetPeer?,
        uiState: SingScaleUiState
    ): String {
        return when (category) {
            TrafficRouteCategory.DIRECT -> "本地直连"
            TrafficRouteCategory.TAILNET_MESH -> when {
                destPeer != null -> "组网直连 · ${destPeer.displayName}"
                subnetPeer != null -> "组网子网 · ${subnetPeer.displayName}"
                else -> "组网直连"
            }
            TrafficRouteCategory.RELAY_EXIT -> {
                val exitName = uiState.activeExitPeer?.displayName
                val relayName = uiState.activeRelayPeer?.displayName
                when {
                    outboundTag == ConfigBuilder.OUTBOUND_RELAY && !relayName.isNullOrBlank() -> "中继 · $relayName"
                    !relayName.isNullOrBlank() && !exitName.isNullOrBlank() && relayName != exitName -> "$relayName → $exitName"
                    !exitName.isNullOrBlank() -> "中继/出口 · $exitName"
                    !relayName.isNullOrBlank() -> "中继 · $relayName"
                    else -> "中继/出口"
                }
            }
        }
    }

    private fun resolveTransportPath(
        category: TrafficRouteCategory,
        destPeer: TailnetPeer?,
        subnetPeer: TailnetPeer?,
        uiState: SingScaleUiState
    ): TransportPathType = when (category) {
        TrafficRouteCategory.DIRECT -> TransportPathType.OFFLINE
        TrafficRouteCategory.TAILNET_MESH -> (destPeer ?: subnetPeer)?.effectivePathType ?: TransportPathType.OFFLINE
        TrafficRouteCategory.RELAY_EXIT -> (uiState.activeExitPeer ?: uiState.activeRelayPeer)?.effectivePathType
            ?: uiState.activeQuality.pathType
    }

    private fun humanizeRule(rawRule: String, category: TrafficRouteCategory): String {
        val r = rawRule.trim()
        if (r.isBlank()) {
            return when (category) {
                TrafficRouteCategory.DIRECT -> "默认本地直连规则 (final → direct)"
                TrafficRouteCategory.TAILNET_MESH -> "Tailnet 组网直连规则 (→ ts-mesh)"
                TrafficRouteCategory.RELAY_EXIT -> "默认中继/出口规则 (final → egress-select)"
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
            r.contains("100.64.0.0/10") || r.lowercase().contains("fd7a:115c:a1e0::/48") -> "Tailnet 组网直连 ($r)"
            category == TrafficRouteCategory.TAILNET_MESH -> "Tailnet 组网子网直连 ($r)"
            r.contains("ip_is_private") -> "局域网私网直连 ($r)"
            r.contains("tailscale.com") -> "Tailscale 控制面保活直连"
            r.contains("domain_suffix") && category == TrafficRouteCategory.DIRECT -> "自定义直连域名 ($r)"
            r.contains("domain_suffix") && category == TrafficRouteCategory.RELAY_EXIT -> "强制/规则中继域名分流 ($r)"
            else -> r
        }
    }

    private fun findSubnetRouterPeer(destHost: String, peers: List<TailnetPeer>): TailnetPeer? {
        val ipLong = parseIpv4ToLong(destHost) ?: return null
        var bestPeer: TailnetPeer? = null
        var bestPrefixLen = -1
        for (peer in peers) {
            for (cidr in peer.subnetRoutes) {
                val slash = cidr.indexOf('/')
                if (slash <= 0) continue
                val netLong = parseIpv4ToLong(cidr.substring(0, slash)) ?: continue
                val bits = cidr.substring(slash + 1).toIntOrNull() ?: continue
                if (bits !in 1..32) continue
                val mask = (-1L shl (32 - bits)) and 0xFFFFFFFFL
                if ((ipLong and mask) == (netLong and mask) && bits > bestPrefixLen) {
                    bestPrefixLen = bits
                    bestPeer = peer
                }
            }
        }
        return bestPeer
    }

    private fun parseIpv4ToLong(host: String): Long? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        var raw = 0L
        for (p in parts) {
            val v = p.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            raw = (raw shl 8) or v.toLong()
        }
        return raw
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
        if (host.contains(':')) return true
        val parts = host.split('.')
        return parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }
    }

    private fun isTailnetIp(host: String): Boolean {
        val h = host.trim().substringBefore('%').lowercase()
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return a == 100 && b in 64..127
    }
}
