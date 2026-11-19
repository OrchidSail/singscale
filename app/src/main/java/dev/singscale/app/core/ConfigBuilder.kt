package dev.singscale.app.core

import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URI

/**
 * Generates a strict sing-box v1.14.4 runtime JSON configuration tailored to:
 * 1. Tailscale userspace endpoint (`ts-ep`) with persistent state, OAuth/AuthKey, Headscale control URL,
 *    Exit Node, Subnet Route acceptance, and Peer Relay configuration.
 * 2. Multi-role Exit & Relay chaining:
 *    - Native Tailscale Exit Node (`ts-ep` exit_node)
 *    - Proxy-over-Tailscale Exit (`peer-exit` -> detour `ts-ep` or `peer-relay`)
 *    - Multi-hop Tailnet Relay (`peer-relay` -> detour `ts-ep`) + Native Peer Relay endpoints
 * 3. Quality-adaptive dynamic routing policies (`SMART_QUALITY`, `GLOBAL_EXIT`, `RULE_SPLIT`, `MESH_ONLY`)
 *    and automatic fail-safe downgrade rules (`DynamicTier`).
 */
object ConfigBuilder {

    const val ENDPOINT_TAG = "ts-ep"
    const val OUTBOUND_MESH = "ts-mesh"
    const val OUTBOUND_DIRECT = "direct"
    const val OUTBOUND_RELAY = "peer-relay"
    const val OUTBOUND_EXIT = "peer-exit"
    const val OUTBOUND_SELECTOR = "egress-select"

    const val FALLBACK_TUN_IPV4_CIDR = "172.19.0.1/30"
    const val FALLBACK_TUN_IPV6_CIDR = "fdfe:dcba:9876::1/126"
    const val TAILSCALE_MAGIC_DNS_IPV4 = "100.100.100.100"
    const val TAILSCALE_MAGIC_DNS_IPV6 = "fd7a:115c:a1e0::53"

    private val prettyJson = Json {
        prettyPrint = true
        encodeDefaults = false
    }

    data class BuildPlan(
        val configJson: String,
        val reloadFingerprint: String,
        val primaryEgressTag: String,
        val nativeExitNodeTarget: String,
        val usesProxyExit: Boolean,
        val usesProxyRelay: Boolean,
        val directFirstActive: Boolean,
        val tunIpv4Address: String = FALLBACK_TUN_IPV4_CIDR,
        val usesTailscaleTunIp: Boolean = false
    )

    /**
     * Validates and canonicalizes a Tailscale CGNAT IPv4 address (`100.64.0.0/10`),
     * rejecting malformed strings, non-CGNAT IPs, network/broadcast edges, and
     * Tailscale's reserved Quad100 MagicDNS address (`100.100.100.100`).
     */
    fun normalizeTailscaleIpv4(raw: String?): String? {
        val cleaned = raw?.trim()?.substringBefore('/')?.substringBefore('%').orEmpty()
        if (cleaned.isEmpty()) return null
        val octets = cleaned.split('.')
        if (octets.size != 4) return null
        val parsed = IntArray(4)
        for (i in 0 until 4) {
            val part = octets[i]
            if (part.isEmpty() || part.length > 3 || !part.all { it.isDigit() }) return null
            val v = part.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            parsed[i] = v
        }
        // Must fall inside Tailscale CGNAT range 100.64.0.0/10 (100.64.0.0 - 100.127.255.255)
        if (parsed[0] != 100 || parsed[1] !in 64..127) return null
        // Exclude reserved Quad100 service IP and CGNAT range network/broadcast boundaries
        if (parsed[1] == 100 && parsed[2] == 100 && parsed[3] == 100) return null
        if (parsed[1] == 64 && parsed[2] == 0 && parsed[3] == 0) return null
        if (parsed[1] == 127 && parsed[2] == 255 && parsed[3] == 255) return null
        return "${parsed[0]}.${parsed[1]}.${parsed[2]}.${parsed[3]}"
    }

    /**
     * Validates a Tailscale node ULA IPv6 address (`fd7a:115c:a1e0::/48`),
     * excluding Tailscale's reserved IPv6 MagicDNS service address (`fd7a:115c:a1e0::53`).
     */
    fun normalizeTailscaleIpv6(raw: String?): String? {
        val cleaned = raw?.trim()?.substringBefore('/')?.substringBefore('%')?.lowercase().orEmpty()
        if (cleaned.isEmpty() || !cleaned.contains(':')) return null
        if (!cleaned.startsWith("fd7a:115c:a1e0:")) return null
        if (cleaned == TAILSCALE_MAGIC_DNS_IPV6) return null
        if (!cleaned.all { it in '0'..'9' || it in 'a'..'f' || it == ':' }) return null
        return cleaned
    }

    fun resolveTunIpv4Cidr(selfIpv4Raw: String?): String {
        val validIp = normalizeTailscaleIpv4(selfIpv4Raw)
        return if (validIp != null) "$validIp/32" else FALLBACK_TUN_IPV4_CIDR
    }

    fun build(
        state: SingScaleUiState,
        defaultDeviceHostname: String,
        enableIpv6: Boolean = false,
        stateDirectory: String = "tailscale"
    ): BuildPlan {
        val auth = state.authConfig
        val thresholds = state.thresholds

        val hostname = auth.hostname.trim().ifBlank {
            defaultDeviceHostname.ifBlank { "singscale-${Build.MODEL.lowercase().replace(Regex("[^a-z0-9]+"), "-")}" }
        }

        val exitPeer = state.peers.firstOrNull { it.stableId == state.activeExitPeerId }
        val relayPeer = state.peers.firstOrNull {
            it.stableId == state.activeRelayPeerId && it.stableId != state.activeExitPeerId
        }

        val exitCustom = exitPeer?.let { state.peerConfigs[it.stableId] }
        val relayCustom = relayPeer?.let { state.peerConfigs[it.stableId] }

        // Determine whether Relay Peer provides a proxy hop over Tailscale or acts as a Native Peer Relay
        val relayHasProxyHop = relayPeer != null &&
            relayPeer.primaryIpv4.isNotBlank() &&
            (relayCustom?.proxyPort ?: 0) > 0 &&
            relayCustom?.gatewayMode != PeerGatewayMode.NATIVE_ONLY

        // Determine whether Exit Peer uses Native Tailscale Exit Node vs Proxy-over-Tailscale outbound
        val exitProxyPort = exitCustom?.proxyPort ?: 0
        val useProxyExit = exitPeer != null &&
            exitPeer.primaryIpv4.isNotBlank() &&
            exitProxyPort > 0 &&
            (exitCustom?.gatewayMode == PeerGatewayMode.PROXY_OVER_TS ||
                (!exitPeer.exitNodeOption && exitCustom?.gatewayMode != PeerGatewayMode.NATIVE_ONLY))

        val useNativeExit = exitPeer != null &&
            !useProxyExit &&
            state.policyMode != RoutingPolicyMode.MESH_ONLY &&
            state.dynamicTier != DynamicTier.FAILSAFE_DIRECT

        // Native exit node identifier (prefer primary Tailscale IP or HostName for initial config, StableID for runtime RPC)
        val nativeExitTarget = if (useNativeExit) {
            exitPeer?.primaryIpv4?.ifBlank { exitPeer.hostName } ?: ""
        } else {
            ""
        }

        // Static Peer Relay endpoints collected from selected Relay peer (if configured)
        val staticRelayEndpoints = buildList {
            relayCustom?.staticRelayEndpoint?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            relayPeer?.peerRelay?.trim()?.takeIf { it.isNotBlank() && it.contains(':') }?.let { add(it) }
        }.distinct()

        // Control plane bypass domains so Tailscale coordination never loops back into tunnel
        val controlDomains = mutableListOf(
            "tailscale.com",
            "tailscale.io",
            "login.tailscale.com",
            "controlplane.tailscale.com"
        )
        if (auth.controlUrl.isNotBlank()) {
            runCatching {
                URI(auth.controlUrl.trim()).host?.takeIf { it.isNotBlank() }?.let { host ->
                    if (! host.all { it.isDigit() || it == '.' || it == ':' }) {
                        controlDomains.add(host)
                    }
                }
            }
        }
        val controlHostIp = runCatching {
            if (auth.controlUrl.isNotBlank()) {
                val h = URI(auth.controlUrl.trim()).host.orEmpty()
                if (h.isNotBlank() && h.all { it.isDigit() || it == '.' }) "$h/32" else null
            } else null
        }.getOrNull()

        // Outbounds construction
        val outboundsList = mutableListOf<JsonObject>()
        val selectorMembers = mutableListOf<String>()

        if (relayHasProxyHop && relayCustom != null) {
            outboundsList.add(
                buildProxyOutbound(
                    tag = OUTBOUND_RELAY,
                    protocol = relayCustom.proxyProtocol,
                    serverIp = relayPeer!!.primaryIpv4,
                    port = relayCustom.proxyPort,
                    detourTag = ENDPOINT_TAG
                )
            )
            selectorMembers.add(OUTBOUND_RELAY)
        }

        if (useProxyExit && exitCustom != null) {
            val upstreamDetour = if (relayHasProxyHop) OUTBOUND_RELAY else ENDPOINT_TAG
            outboundsList.add(
                buildProxyOutbound(
                    tag = OUTBOUND_EXIT,
                    protocol = exitCustom.proxyProtocol,
                    serverIp = exitPeer!!.primaryIpv4,
                    port = exitCustom.proxyPort,
                    detourTag = upstreamDetour
                )
            )
            selectorMembers.add(0, OUTBOUND_EXIT)
        }

        selectorMembers.add(ENDPOINT_TAG)
        selectorMembers.add(OUTBOUND_DIRECT)

        val preferredPrimaryTag = when {
            state.policyMode == RoutingPolicyMode.MESH_ONLY -> OUTBOUND_DIRECT
            state.dynamicTier == DynamicTier.FAILSAFE_DIRECT -> OUTBOUND_DIRECT
            useProxyExit -> OUTBOUND_EXIT
            useNativeExit -> ENDPOINT_TAG
            relayHasProxyHop -> OUTBOUND_RELAY
            exitPeer != null -> ENDPOINT_TAG
            relayPeer != null -> ENDPOINT_TAG
            else -> OUTBOUND_DIRECT
        }

        outboundsList.add(
            buildJsonObject {
                put("type", "selector")
                put("tag", OUTBOUND_MESH)
                put("outbounds", JsonArray(listOf(JsonPrimitive(ENDPOINT_TAG))))
                put("default", ENDPOINT_TAG)
                put("interrupt_exist_connections", false)
            }
        )

        outboundsList.add(
            buildJsonObject {
                put("type", "selector")
                put("tag", OUTBOUND_SELECTOR)
                put("outbounds", JsonArray(selectorMembers.distinct().map { JsonPrimitive(it) }))
                put("default", preferredPrimaryTag)
                put("interrupt_exist_connections", false)
            }
        )

        outboundsList.add(
            buildJsonObject {
                put("type", "direct")
                put("tag", OUTBOUND_DIRECT)
            }
        )

        val tunnelInternetActive = preferredPrimaryTag != OUTBOUND_DIRECT
        val directFirstActive = state.policyMode == RoutingPolicyMode.SMART_QUALITY &&
            thresholds.directFirstPreferred &&
            tunnelInternetActive &&
            state.dynamicTier != DynamicTier.DERP_CONSERVATIVE &&
            state.dynamicTier != DynamicTier.FAILSAFE_DIRECT

        val forcedRelaySet = thresholds.forcedRelayDomains
            .map { it.trim().lowercase().trimStart('.') }
            .filter { it.isNotBlank() }
            .distinct()

        val effectiveDirectDomains = thresholds.customDirectDomains
            .map { it.trim().lowercase().trimStart('.') }
            .filter { it.isNotBlank() && it !in forcedRelaySet }
            .distinct()

        // Collect advertised subnet routes from peers if acceptSubnetRoutes is enabled
        val peerSubnets = if (thresholds.acceptSubnetRoutes) {
            state.peers
                .flatMap { it.subnetRoutes }
                .filter { it.contains('/') }
                .distinct()
        } else {
            emptyList()
        }

        // Resolve TUN IPv4/IPv6 addresses: bind the node's real Tailscale 100.x/32 IP when known
        // so Android Wireless Debugging displays 100.x.y.z:<port>, falling back safely to 172.19.0.1/30
        // before login or after logout.
        val selfTailscaleIpv4 = normalizeTailscaleIpv4(state.selfPeer?.primaryIpv4)
        val tunIpv4Cidr = if (selfTailscaleIpv4 != null) "$selfTailscaleIpv4/32" else FALLBACK_TUN_IPV4_CIDR
        val selfTailscaleIpv6 = normalizeTailscaleIpv6(state.selfPeer?.primaryIpv6)
        val tunIpv6Cidr = if (selfTailscaleIpv6 != null) "$selfTailscaleIpv6/128" else FALLBACK_TUN_IPV6_CIDR
        val usesTailscaleTunIp = selfTailscaleIpv4 != null

        // Root sing-box 1.14 JSON
        val root = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("disabled", false)
                    put("level", "warn")
                    put("timestamp", true)
                }
            )

            put(
                "dns",
                buildDnsBlock(
                    tunnelInternetActive = tunnelInternetActive,
                    directFirstActive = directFirstActive,
                    egressTag = if (tunnelInternetActive) OUTBOUND_SELECTOR else OUTBOUND_DIRECT,
                    magicDnsSuffix = state.magicDnsSuffix,
                    controlDomains = controlDomains,
                    directDomains = effectiveDirectDomains,
                    forcedRelayDomains = forcedRelaySet,
                    enableIpv6 = enableIpv6
                )
            )

            put(
                "endpoints",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "tailscale")
                            put("tag", ENDPOINT_TAG)
                            put("state_directory", stateDirectory.ifBlank { "tailscale" })
                            put("hostname", hostname)
                            put("ephemeral", auth.ephemeral)
                            put("accept_routes", thresholds.acceptSubnetRoutes)
                            if (auth.controlUrl.isNotBlank()) {
                                put("control_url", auth.controlUrl.trim())
                            }
                            if (auth.authKey.isNotBlank()) {
                                put("auth_key", auth.authKey.trim())
                            }
                            if (nativeExitTarget.isNotBlank()) {
                                put("exit_node", nativeExitTarget)
                                put("exit_node_allow_lan_access", thresholds.allowLanAccess)
                            }
                            if (auth.localRelayServerPort in 1..65535) {
                                put("relay_server_port", auth.localRelayServerPort)
                            }
                            if (staticRelayEndpoints.isNotEmpty()) {
                                put(
                                    "relay_server_static_endpoints",
                                    JsonArray(staticRelayEndpoints.map { JsonPrimitive(it) })
                                )
                            }
                        }
                    )
                }
            )

            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "tun")
                            put("tag", "tun-in")
                            put("interface_name", "tun0")
                            put(
                                "address",
                                buildJsonArray {
                                    add(JsonPrimitive(tunIpv4Cidr))
                                    if (enableIpv6) {
                                        add(JsonPrimitive(tunIpv6Cidr))
                                    }
                                }
                            )
                            if (usesTailscaleTunIp) {
                                put(
                                    "dns_address",
                                    buildJsonArray {
                                        add(JsonPrimitive(TAILSCALE_MAGIC_DNS_IPV4))
                                        if (enableIpv6) {
                                            add(JsonPrimitive(TAILSCALE_MAGIC_DNS_IPV6))
                                        }
                                    }
                                )
                            }
                            put("mtu", 1400)
                            put("auto_route", true)
                            put("strict_route", !thresholds.allowLanAccess)
                            if (thresholds.allowLanAccess) {
                                val lanExcludeCidrs = buildLanExcludeCidrs(peerSubnets, enableIpv6)
                                if (lanExcludeCidrs.isNotEmpty()) {
                                    put(
                                        "route_exclude_address",
                                        JsonArray(lanExcludeCidrs.map { JsonPrimitive(it) })
                                    )
                                }
                            }
                            put("stack", "gvisor")
                        }
                    )
                }
            )

            put("outbounds", JsonArray(outboundsList))

            put(
                "route",
                buildRouteBlock(
                    state = state,
                    tunnelInternetActive = tunnelInternetActive,
                    controlDomains = controlDomains,
                    controlHostIp = controlHostIp,
                    peerSubnets = peerSubnets,
                    effectiveDirectDomains = effectiveDirectDomains,
                    forcedRelayDomains = forcedRelaySet,
                    enableIpv6 = enableIpv6
                )
            )

            put(
                "experimental",
                buildJsonObject {
                    put(
                        "cache_file",
                        buildJsonObject {
                            put("enabled", true)
                            put("path", "singscale_cache.db")
                        }
                    )
                }
            )
        }

        // Structural fingerprint excluding live RPC-managed Tailscale prefs & selector default,
        // so switching native Exit/Relay or selector outbound happens in <1ms without restarting tsnet!
        val reloadFingerprintObj = buildJsonObject {
            put("dns", root["dns"]!!)
            put("inbounds", root["inbounds"]!!)
            put(
                "endpoint_core",
                buildJsonObject {
                    put("state_directory", stateDirectory.ifBlank { "tailscale" })
                    put("hostname", hostname)
                    put("ephemeral", auth.ephemeral)
                    put("control_url", auth.controlUrl.trim())
                    put("auth_key", auth.authKey.trim())
                }
            )
            put(
                "outbounds_core",
                JsonArray(
                    outboundsList.map { ob ->
                        if (ob["tag"]?.toString()?.trim('"') == OUTBOUND_SELECTOR) {
                            buildJsonObject {
                                put("type", "selector")
                                put("tag", OUTBOUND_SELECTOR)
                                put("outbounds", ob["outbounds"]!!)
                            }
                        } else {
                            ob
                        }
                    }
                )
            )
            put("route", root["route"]!!)
        }

        return BuildPlan(
            configJson = prettyJson.encodeToString(JsonElement.serializer(), root),
            reloadFingerprint = Json.encodeToString(JsonElement.serializer(), reloadFingerprintObj),
            primaryEgressTag = preferredPrimaryTag,
            nativeExitNodeTarget = nativeExitTarget,
            usesProxyExit = useProxyExit,
            usesProxyRelay = relayHasProxyHop,
            directFirstActive = directFirstActive,
            tunIpv4Address = tunIpv4Cidr,
            usesTailscaleTunIp = usesTailscaleTunIp
        )
    }

    private fun buildProxyOutbound(
        tag: String,
        protocol: String,
        serverIp: String,
        port: Int,
        detourTag: String
    ): JsonObject = buildJsonObject {
        val normalized = if (protocol.lowercase() == "http") "http" else "socks"
        put("type", normalized)
        put("tag", tag)
        put("server", serverIp)
        put("server_port", port)
        put("detour", detourTag)
    }

    private fun buildDnsBlock(
        tunnelInternetActive: Boolean,
        directFirstActive: Boolean,
        egressTag: String,
        magicDnsSuffix: String,
        controlDomains: List<String>,
        directDomains: List<String>,
        forcedRelayDomains: List<String>,
        enableIpv6: Boolean
    ): JsonObject = buildJsonObject {
        val dnsStrategy = if (enableIpv6) "prefer_ipv4" else "ipv4_only"
        put(
            "servers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "tailscale")
                        put("tag", "dns-tailscale")
                        put("endpoint", ENDPOINT_TAG)
                        put("accept_default_resolvers", false)
                    }
                )
                add(
                    buildJsonObject {
                        put("type", "https")
                        put("tag", "dns-remote")
                        put("server", "1.1.1.1")
                        put("server_port", 443)
                        if (tunnelInternetActive) {
                            put("detour", egressTag)
                        }
                    }
                )
                add(
                    buildJsonObject {
                        put("type", "local")
                        put("tag", "dns-local")
                    }
                )
            }
        )

        val magicSuffixes = buildList {
            add(".ts.net")
            add("ts.net")
            val normalizedMagic = magicDnsSuffix.trim().trim('.').lowercase()
            if (normalizedMagic.isNotBlank() &&
                normalizedMagic != "ts.net" &&
                !normalizedMagic.endsWith(".ts.net")
            ) {
                add(normalizedMagic)
            }
        }.distinct()

        put(
            "rules",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("domain_suffix", JsonArray(controlDomains.distinct().map { JsonPrimitive(it) }))
                        put("server", "dns-local")
                    }
                )
                add(
                    buildJsonObject {
                        put("domain_suffix", JsonArray(magicSuffixes.map { JsonPrimitive(it) }))
                        put("server", "dns-tailscale")
                    }
                )
                if (forcedRelayDomains.isNotEmpty() && tunnelInternetActive) {
                    add(
                        buildJsonObject {
                            put("domain_suffix", JsonArray(forcedRelayDomains.distinct().map { JsonPrimitive(it) }))
                            put("server", "dns-remote")
                        }
                    )
                }
                if (directDomains.isNotEmpty()) {
                    add(
                        buildJsonObject {
                            put("domain_suffix", JsonArray(directDomains.distinct().map { JsonPrimitive(it) }))
                            put("server", "dns-local")
                        }
                    )
                }
            }
        )

        // When Direct-First is active (default regardless of region/country), ALWAYS resolve via local ISP DNS
        // (`dns-local`) so local CDNs (googleapis.com, youtube.com, cloudflare, etc.) hit the nearest city edge!
        // If a domain fails/degrades on direct, Go rewrites proxy destination to FQDN so the Relay/Exit resolves remotely.
        val defaultDnsServer = if (!tunnelInternetActive || directFirstActive) "dns-local" else "dns-remote"
        put("final", defaultDnsServer)
        put("strategy", dnsStrategy)
        put("independent_cache", true)
        put("reverse_mapping", true)
    }

    private fun buildRouteBlock(
        state: SingScaleUiState,
        tunnelInternetActive: Boolean,
        controlDomains: List<String>,
        controlHostIp: String?,
        peerSubnets: List<String>,
        effectiveDirectDomains: List<String>,
        forcedRelayDomains: List<String>,
        enableIpv6: Boolean
    ): JsonObject = buildJsonObject {
        val thresholds = state.thresholds
        val rules = mutableListOf<JsonObject>()

        // 1. Sniff protocol metadata for domain-based smart routing
        rules.add(
            buildJsonObject {
                put("action", "sniff")
            }
        )

        // 2. Hijack DNS queries to sing-box DNS router
        rules.add(
            buildJsonObject {
                put("protocol", "dns")
                put("action", "hijack-dns")
            }
        )

        // 3. Always keep Tailscale control plane traffic direct to avoid tunnel deadlocks
        rules.add(
            buildJsonObject {
                put("domain_suffix", JsonArray(controlDomains.distinct().map { JsonPrimitive(it) }))
                put("outbound", OUTBOUND_DIRECT)
            }
        )
        if (controlHostIp != null) {
            rules.add(
                buildJsonObject {
                    put("ip_cidr", JsonArray(listOf(JsonPrimitive(controlHostIp))))
                    put("outbound", OUTBOUND_DIRECT)
                }
            )
        }

        // 4. Tailscale CGNAT (100.64.0.0/10) & ULA (fd7a:115c:a1e0::/48) ALWAYS route via dedicated ts-mesh -> ts-ep
        rules.add(
            buildJsonObject {
                put(
                    "ip_cidr",
                    buildJsonArray {
                        add(JsonPrimitive("100.64.0.0/10"))
                        add(JsonPrimitive("fd7a:115c:a1e0::/48"))
                    }
                )
                put("outbound", OUTBOUND_MESH)
            }
        )

        // 5. Advertised Tailnet subnet routes via dedicated ts-mesh -> ts-ep
        if (peerSubnets.isNotEmpty()) {
            rules.add(
                buildJsonObject {
                    put("ip_cidr", JsonArray(peerSubnets.map { JsonPrimitive(it) }))
                    put("outbound", OUTBOUND_MESH)
                }
            )
        }

        // 6. Private LAN access
        if (thresholds.allowLanAccess) {
            rules.add(
                buildJsonObject {
                    put("ip_is_private", true)
                    put("outbound", OUTBOUND_DIRECT)
                }
            )
        }

        // 7. Dynamic Quality-Aware & Mode-Specific Routing Rules
        val finalOutbound: String = when {
            !tunnelInternetActive || state.policyMode == RoutingPolicyMode.MESH_ONLY ||
                state.dynamicTier == DynamicTier.FAILSAFE_DIRECT -> {
                OUTBOUND_DIRECT
            }

            state.policyMode == RoutingPolicyMode.GLOBAL_EXIT -> {
                OUTBOUND_SELECTOR
            }

            state.policyMode == RoutingPolicyMode.RULE_SPLIT -> {
                val splitProxyDomains = (forcedRelayDomains + thresholds.customProxyDomains).distinct()
                if (splitProxyDomains.isNotEmpty()) {
                    rules.add(
                        buildJsonObject {
                            put(
                                "domain_suffix",
                                JsonArray(splitProxyDomains.map { JsonPrimitive(it) })
                            )
                            put("outbound", OUTBOUND_SELECTOR)
                        }
                    )
                }
                if (effectiveDirectDomains.isNotEmpty()) {
                    rules.add(
                        buildJsonObject {
                            put(
                                "domain_suffix",
                                JsonArray(effectiveDirectDomains.map { JsonPrimitive(it) })
                            )
                            put("outbound", OUTBOUND_DIRECT)
                        }
                    )
                }
                OUTBOUND_SELECTOR
            }

            // RoutingPolicyMode.SMART_QUALITY (Adaptive by real link quality + Direct-First domain probing!)
            else -> {
                when (state.dynamicTier) {
                    DynamicTier.FULL_ACCELERATION, DynamicTier.BALANCED_SPLIT -> {
                        // 1. User-explicitly forced relay domains always take tunnel
                        val activeForced = if (thresholds.directFirstPreferred) {
                            forcedRelayDomains
                        } else {
                            (forcedRelayDomains + thresholds.customProxyDomains).distinct()
                        }
                        if (activeForced.isNotEmpty()) {
                            rules.add(
                                buildJsonObject {
                                    put(
                                        "domain_suffix",
                                        JsonArray(activeForced.map { JsonPrimitive(it) })
                                    )
                                    put("outbound", OUTBOUND_SELECTOR)
                                }
                            )
                        }
                        // 2. Known domestic / user-pinned direct domains fast-path straight to direct
                        if (effectiveDirectDomains.isNotEmpty()) {
                            rules.add(
                                buildJsonObject {
                                    put(
                                        "domain_suffix",
                                        JsonArray(effectiveDirectDomains.map { JsonPrimitive(it) })
                                    )
                                    put("outbound", OUTBOUND_DIRECT)
                                }
                            )
                        }
                        // 3. Unmatched domains (e.g. googleapis.com, github.com, CDNs):
                        //    When directFirstPreferred=true, Go's SingScaleDirectFirstJudge probes physical
                        //    direct TCP/TLS quality first -> uses `direct` if healthy (<= threshold),
                        //    and seamlessly falls back to `OUTBOUND_SELECTOR` (Relay/Exit) only when blocked/poor!
                        OUTBOUND_SELECTOR
                    }

                    DynamicTier.DERP_CONSERVATIVE -> {
                        val derpProxy = (forcedRelayDomains + thresholds.customProxyDomains).distinct()
                        if (derpProxy.isNotEmpty()) {
                            rules.add(
                                buildJsonObject {
                                    put(
                                        "domain_suffix",
                                        JsonArray(derpProxy.map { JsonPrimitive(it) })
                                    )
                                    put("outbound", OUTBOUND_SELECTOR)
                                }
                            )
                        }
                        OUTBOUND_DIRECT
                    }

                    DynamicTier.FAILSAFE_DIRECT -> OUTBOUND_DIRECT
                }
            }
        }

        put("rules", JsonArray(rules))
        put("final", finalOutbound)
        put("auto_detect_interface", true)
        put(
            "default_domain_resolver",
            buildJsonObject {
                put("server", "dns-local")
                put("strategy", if (enableIpv6) "prefer_ipv4" else "ipv4_only")
            }
        )
    }

    private data class Ipv4Cidr(val ip: Long, val bits: Int) {
        override fun toString(): String =
            "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}/$bits"
    }

    private fun maskForBits(bits: Int): Long =
        if (bits <= 0) 0L else (-1L shl (32 - bits)) and 0xFFFFFFFFL

    private fun parseIpv4Cidr(cidr: String): Ipv4Cidr? {
        val slash = cidr.indexOf('/')
        if (slash <= 0) return null
        val ipPart = cidr.substring(0, slash).trim()
        val bits = cidr.substring(slash + 1).trim().toIntOrNull() ?: return null
        if (bits !in 0..32) return null
        val octets = ipPart.split('.')
        if (octets.size != 4) return null
        var raw = 0L
        for (o in octets) {
            val v = o.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            raw = (raw shl 8) or v.toLong()
        }
        return Ipv4Cidr(raw and maskForBits(bits), bits)
    }

    private fun subtractIpv4Cidr(base: Ipv4Cidr, sub: Ipv4Cidr): List<Ipv4Cidr> {
        val minBits = minOf(base.bits, sub.bits)
        val commonMask = maskForBits(minBits)
        if ((base.ip and commonMask) != (sub.ip and commonMask)) {
            return listOf(base)
        }
        if (sub.bits <= base.bits) {
            return emptyList()
        }
        val nextBits = base.bits + 1
        val left = Ipv4Cidr(base.ip, nextBits)
        val right = Ipv4Cidr(base.ip or (1L shl (32 - nextBits)), nextBits)
        return subtractIpv4Cidr(left, sub) + subtractIpv4Cidr(right, sub)
    }

    /**
     * Excludes local physical LAN / link-local / multicast ranges from `tun0` when `allowLanAccess`
     * is enabled so incoming LAN traffic (e.g. Wireless ADB `adbd`, mDNS, LAN cast/NAS) is never
     * hijacked by Android's VPN routing table, while keeping `172.19.0.0/16` (`tun0` + DNS hijack)
     * and any accepted Tailnet subnet routes inside `tun0`.
     */
    private fun buildLanExcludeCidrs(peerSubnets: List<String>, enableIpv6: Boolean): List<String> {
        val baseV4 = listOf(
            "10.0.0.0/8",
            "172.16.0.0/15",
            "172.18.0.0/16",
            "172.20.0.0/14",
            "172.24.0.0/13",
            "192.168.0.0/16",
            "169.254.0.0/16",
            "224.0.0.0/4",
            "255.255.255.255/32"
        ).mapNotNull(::parseIpv4Cidr)

        val peerV4 = peerSubnets.mapNotNull(::parseIpv4Cidr)
        val remainingV4 = peerV4.fold(baseV4) { acc, sub ->
            acc.flatMap { subtractIpv4Cidr(it, sub) }
        }.map { it.toString() }

        return if (enableIpv6) {
            remainingV4 + listOf("fe80::/10", "ff00::/8")
        } else {
            remainingV4
        }
    }
}
