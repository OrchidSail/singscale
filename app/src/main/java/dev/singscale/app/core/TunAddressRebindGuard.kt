package dev.singscale.app.core

/**
 * Reentrancy-safe, rate-limited state machine governing smooth TUN IPv4 address rebinding
 * (e.g. transitioning from fallback `172.19.0.1/30` to the authenticated node's real
 * Tailscale CGNAT address `<selfIP>/32`, or adapting if Tailnet reassigns the IP).
 *
 * Guarantees:
 * - Immediate one-shot upgrade from fallback `172.19.0.1/30` to `<selfIP>/32` on first login.
 * - Strictly no-op when the live TUN address already matches `<selfIP>/32`.
 * - Single-flight (reentrancy-guarded) so overlapping snapshots cannot stack reloads.
 * - Cooldown, failure backoff, and sliding-window cap prevent any oscillation / crash loop.
 */
enum class TunRebindDecision {
    NO_CHANGE,
    SKIP_NOT_RUNNING,
    SKIP_INVALID_IP,
    SKIP_IN_FLIGHT,
    SKIP_COOLDOWN,
    SKIP_LOOP_LIMIT_REACHED,
    REBIND_REQUIRED
}

class TunAddressRebindGuard(
    private val minRebindIntervalMs: Long = 10_000L,
    private val failureBackoffMs: Long = 30_000L,
    private val maxFailuresPerTarget: Int = 2,
    private val windowMs: Long = 120_000L,
    private val maxRebindsPerWindow: Int = 3
) {
    private val lock = Any()

    private var appliedTunIpv4Cidr: String = ConfigBuilder.FALLBACK_TUN_IPV4_CIDR
    private var inFlight: Boolean = false
    private var lastRebindCompletedMs: Long = 0L
    private var lastFailedTargetCidr: String = ""
    private var lastFailureAtMs: Long = 0L
    private var consecutiveFailuresForTarget: Int = 0
    private val rebindTimestampsMs = ArrayDeque<Long>()

    fun currentAppliedCidr(): String = synchronized(lock) { appliedTunIpv4Cidr }

    fun recordAppliedExternal(appliedCidr: String) = synchronized(lock) {
        if (appliedCidr.isNotBlank()) {
            appliedTunIpv4Cidr = appliedCidr
            if (appliedCidr == lastFailedTargetCidr) {
                lastFailedTargetCidr = ""
                consecutiveFailuresForTarget = 0
            }
        }
    }

    fun evaluateAndBegin(
        backendState: String,
        rawSelfIpv4: String?,
        nowMs: Long
    ): Pair<TunRebindDecision, String> = synchronized(lock) {
        if (backendState != "Running") {
            return TunRebindDecision.SKIP_NOT_RUNNING to ""
        }
        val normalizedIp = ConfigBuilder.normalizeTailscaleIpv4(rawSelfIpv4)
            ?: return TunRebindDecision.SKIP_INVALID_IP to ""
        val targetCidr = "$normalizedIp/32"

        if (targetCidr == appliedTunIpv4Cidr) {
            return TunRebindDecision.NO_CHANGE to targetCidr
        }
        if (inFlight) {
            return TunRebindDecision.SKIP_IN_FLIGHT to targetCidr
        }

        if (targetCidr == lastFailedTargetCidr) {
            if (consecutiveFailuresForTarget >= maxFailuresPerTarget) {
                return TunRebindDecision.SKIP_LOOP_LIMIT_REACHED to targetCidr
            }
            if (lastFailureAtMs > 0L && nowMs - lastFailureAtMs < failureBackoffMs) {
                return TunRebindDecision.SKIP_COOLDOWN to targetCidr
            }
        }

        while (rebindTimestampsMs.isNotEmpty() && nowMs - rebindTimestampsMs.first() > windowMs) {
            rebindTimestampsMs.removeFirst()
        }
        if (rebindTimestampsMs.size >= maxRebindsPerWindow) {
            return TunRebindDecision.SKIP_LOOP_LIMIT_REACHED to targetCidr
        }

        // First upgrade after login (from fallback 172.19.0.1/30 with no prior rebind) proceeds immediately.
        val isInitialUpgradeFromFallback =
            appliedTunIpv4Cidr == ConfigBuilder.FALLBACK_TUN_IPV4_CIDR && lastRebindCompletedMs == 0L
        if (!isInitialUpgradeFromFallback &&
            lastRebindCompletedMs > 0L &&
            nowMs - lastRebindCompletedMs < minRebindIntervalMs
        ) {
            return TunRebindDecision.SKIP_COOLDOWN to targetCidr
        }

        inFlight = true
        return TunRebindDecision.REBIND_REQUIRED to targetCidr
    }

    fun markRebindSucceeded(appliedCidr: String, nowMs: Long) = synchronized(lock) {
        inFlight = false
        if (appliedCidr.isNotBlank()) {
            appliedTunIpv4Cidr = appliedCidr
        }
        lastRebindCompletedMs = nowMs
        rebindTimestampsMs.addLast(nowMs)
        lastFailedTargetCidr = ""
        lastFailureAtMs = 0L
        consecutiveFailuresForTarget = 0
    }

    fun markRebindFailed(targetCidr: String, nowMs: Long) = synchronized(lock) {
        inFlight = false
        if (targetCidr == lastFailedTargetCidr) {
            consecutiveFailuresForTarget++
        } else {
            lastFailedTargetCidr = targetCidr
            consecutiveFailuresForTarget = 1
        }
        lastFailureAtMs = nowMs
    }

    fun reset() = synchronized(lock) {
        appliedTunIpv4Cidr = ConfigBuilder.FALLBACK_TUN_IPV4_CIDR
        inFlight = false
        lastRebindCompletedMs = 0L
        lastFailedTargetCidr = ""
        lastFailureAtMs = 0L
        consecutiveFailuresForTarget = 0
        rebindTimestampsMs.clear()
    }
}
