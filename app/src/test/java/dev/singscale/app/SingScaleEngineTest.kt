package dev.singscale.app

import dev.singscale.app.core.ConfigBuilder
import dev.singscale.app.core.DynamicTier
import dev.singscale.app.core.GatewayProbeStatus
import dev.singscale.app.core.KernelBucketTotals
import dev.singscale.app.core.KernelTrafficSplit
import dev.singscale.app.core.LinkQualityMetrics
import dev.singscale.app.core.PeerEndpointConfig
import dev.singscale.app.core.PeerGatewayMode
import dev.singscale.app.core.PolicyThresholds
import dev.singscale.app.core.QualityEngine
import dev.singscale.app.core.QualityGrade
import dev.singscale.app.core.RoutingPolicyMode
import dev.singscale.app.core.RuntimePowerMode
import dev.singscale.app.core.RuntimePowerState
import dev.singscale.app.core.SingScaleRepository
import dev.singscale.app.core.SingScaleUiState
import dev.singscale.app.core.TailnetPeer
import dev.singscale.app.core.TrafficRouteCategory
import dev.singscale.app.core.TrafficSplitTracker
import dev.singscale.app.core.TransportPathType
import dev.singscale.app.core.TunAddressRebindGuard
import dev.singscale.app.core.TunRebindDecision
import dev.singscale.app.service.InterfaceSyncSignature
import dev.singscale.app.service.LocalResolver
import dev.singscale.app.service.formatValidatedCidrPrefix
import dev.singscale.app.service.shouldDispatchInterfaceSync
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingScaleEngineTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `config builder produces valid sing-box 1_14 multi-hop relay and exit chain`() {
        val relayPeer = TailnetPeer(
            stableId = "node-relay",
            hostName = "hk-relay",
            tailscaleIPs = listOf("100.64.0.10"),
            online = true,
            hasPeerRelayCap = true
        )
        val exitPeer = TailnetPeer(
            stableId = "node-exit",
            hostName = "us-exit",
            tailscaleIPs = listOf("100.64.0.20"),
            online = true,
            exitNodeOption = false
        )
        val state = SingScaleUiState(
            peers = listOf(relayPeer, exitPeer),
            activeRelayPeerId = "node-relay",
            activeExitPeerId = "node-exit",
            policyMode = RoutingPolicyMode.GLOBAL_EXIT,
            peerConfigs = mapOf(
                "node-relay" to PeerEndpointConfig(
                    stableId = "node-relay",
                    gatewayMode = PeerGatewayMode.PROXY_OVER_TS,
                    proxyProtocol = "socks",
                    proxyPort = 1080
                ),
                "node-exit" to PeerEndpointConfig(
                    stableId = "node-exit",
                    gatewayMode = PeerGatewayMode.PROXY_OVER_TS,
                    proxyProtocol = "http",
                    proxyPort = 7890
                )
            )
        )

        val plan = ConfigBuilder.build(state, "singscale-test")
        assertEquals(ConfigBuilder.OUTBOUND_EXIT, plan.primaryEgressTag)
        assertTrue(plan.usesProxyExit)
        assertTrue(plan.usesProxyRelay)

        val root = json.parseToJsonElement(plan.configJson).jsonObject
        val endpoints = root["endpoints"]!!.jsonArray
        assertEquals("tailscale", endpoints[0].jsonObject["type"]!!.jsonPrimitive.content)

        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val relayOutbound = outbounds.first { it["tag"]?.jsonPrimitive?.content == ConfigBuilder.OUTBOUND_RELAY }
        val exitOutbound = outbounds.first { it["tag"]?.jsonPrimitive?.content == ConfigBuilder.OUTBOUND_EXIT }

        assertEquals(ConfigBuilder.ENDPOINT_TAG, relayOutbound["detour"]!!.jsonPrimitive.content)
        assertEquals(ConfigBuilder.OUTBOUND_RELAY, exitOutbound["detour"]!!.jsonPrimitive.content)
        assertTrue(root["route"]!!.jsonObject.containsKey("default_domain_resolver"))
    }

    @Test
    fun `quality engine selects direct p2p node, downgrades on high DERP rtt, and fails safe when unreachable`() {
        val now = System.currentTimeMillis()
        val peerDerp = TailnetPeer(
            stableId = "peer-derp",
            hostName = "derp-box",
            tailscaleIPs = listOf("100.64.0.11"),
            online = true,
            exitNodeOption = true,
            quality = LinkQualityMetrics(
                peerIp = "100.64.0.11",
                reachable = true,
                latencyMs = 210.0,
                pathType = TransportPathType.DERP,
                score = 58,
                grade = QualityGrade.B,
                updatedAtMs = now
            )
        )
        val peerDirect = TailnetPeer(
            stableId = "peer-direct",
            hostName = "p2p-box",
            tailscaleIPs = listOf("100.64.0.12"),
            online = true,
            exitNodeOption = true,
            quality = LinkQualityMetrics(
                peerIp = "100.64.0.12",
                reachable = true,
                latencyMs = 19.0,
                pathType = TransportPathType.DIRECT,
                score = 96,
                grade = QualityGrade.S,
                updatedAtMs = now
            )
        )

        // 1. Smart auto-pick prefers high-score Direct P2P node with FULL_ACCELERATION
        val autoDecision = QualityEngine.evaluate(
            SingScaleUiState(
                peers = listOf(peerDerp, peerDirect),
                smartAutoSelectNode = true,
                policyMode = RoutingPolicyMode.SMART_QUALITY
            )
        )
        assertEquals("peer-direct", autoDecision.recommendedExitPeerId)
        assertEquals(DynamicTier.FULL_ACCELERATION, autoDecision.recommendedTier)

        // 2. When pinned to high-latency DERP node, smart policy downgrades to DERP_CONSERVATIVE
        val derpDecision = QualityEngine.evaluate(
            SingScaleUiState(
                peers = listOf(peerDerp),
                selectedExitPeerId = "peer-derp",
                smartAutoSelectNode = false,
                policyMode = RoutingPolicyMode.SMART_QUALITY
            )
        )
        assertEquals(DynamicTier.DERP_CONSERVATIVE, derpDecision.recommendedTier)

        // 3. When exit node becomes unreachable, Fail-Safe direct fallback activates
        val deadPeer = peerDirect.copy(
            quality = peerDirect.quality.copy(reachable = false, score = 0)
        )
        val failSafeDecision = QualityEngine.evaluate(
            SingScaleUiState(
                peers = listOf(deadPeer),
                selectedExitPeerId = "peer-direct",
                smartAutoSelectNode = false,
                policyMode = RoutingPolicyMode.SMART_QUALITY
            )
        )
        assertEquals(DynamicTier.FAILSAFE_DIRECT, failSafeDecision.recommendedTier)
    }

    @Test
    fun `unauthenticated tsnet placeholder self peer does not falsely report logged in`() {
        val unauthState = SingScaleUiState(
            backendState = "Stopped",
            selfPeer = TailnetPeer(
                stableId = "self-placeholder",
                hostName = "localhost",
                tailscaleIPs = emptyList()
            )
        )
        assertFalse(unauthState.isLoggedIn)

        val authState = SingScaleUiState(
            backendState = "Running",
            selfPeer = TailnetPeer(
                stableId = "self-real",
                hostName = "android-device",
                tailscaleIPs = listOf("100.64.0.100")
            )
        )
        assertTrue( authState.isLoggedIn )
    }

    @Test
    fun `config builder disables tun ipv6 and enforces ipv4_only dns when upstream lacks global ipv6`() {
        val state = SingScaleUiState()

        val ipv4Plan = ConfigBuilder.build(state, "singscale-v4", enableIpv6 = false)
        val ipv4Root = json.parseToJsonElement(ipv4Plan.configJson).jsonObject
        val ipv4Addrs = ipv4Root["inbounds"]!!.jsonArray[0].jsonObject["address"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("172.19.0.1/30"), ipv4Addrs)
        assertEquals("ipv4_only", ipv4Root["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
        val ipv4Resolver = ipv4Root["route"]!!.jsonObject["default_domain_resolver"]!!.jsonObject
        assertEquals("dns-local", ipv4Resolver["server"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", ipv4Resolver["strategy"]!!.jsonPrimitive.content)

        val dualPlan = ConfigBuilder.build(state, "singscale-v6", enableIpv6 = true)
        val dualRoot = json.parseToJsonElement(dualPlan.configJson).jsonObject
        val dualAddrs = dualRoot["inbounds"]!!.jsonArray[0].jsonObject["address"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertEquals(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126"), dualAddrs)
        assertEquals("prefer_ipv4", dualRoot["dns"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `auto orchestration dynamically inserts relay on DERP exit and bypasses relay on fast direct exit`() {
        val now = System.currentTimeMillis()
        val fastRelay = TailnetPeer(
            stableId = "relay-sz",
            hostName = "sz-relay",
            tailscaleIPs = listOf("100.64.0.30"),
            online = true,
            hasPeerRelayCap = true,
            quality = LinkQualityMetrics(
                peerIp = "100.64.0.30",
                reachable = true,
                latencyMs = 14.0,
                pathType = TransportPathType.DIRECT,
                score = 97,
                grade = QualityGrade.S,
                updatedAtMs = now
            )
        )
        val derpExit = TailnetPeer(
            stableId = "exit-us",
            hostName = "us-exit",
            tailscaleIPs = listOf("100.64.0.31"),
            online = true,
            exitNodeOption = true,
            quality = LinkQualityMetrics(
                peerIp = "100.64.0.31",
                reachable = true,
                latencyMs = 195.0,
                pathType = TransportPathType.DERP,
                score = 62,
                grade = QualityGrade.B,
                updatedAtMs = now
            )
        )

        // 1. Exit on DERP -> Auto Relay automatically cuts in without manual pinning!
        val derpAuto = QualityEngine.evaluate(
            SingScaleUiState(
                peers = listOf(fastRelay, derpExit),
                smartAutoSelectNode = true
            )
        )
        assertEquals("exit-us", derpAuto.recommendedExitPeerId)
        assertEquals("relay-sz", derpAuto.recommendedRelayPeerId)

        // 2. Exit recovers to clean P2P Direct -> Auto Relay automatically bypasses unnecessary hop!
        val directExit = derpExit.copy(
            quality = derpExit.quality.copy(
                latencyMs = 28.0,
                pathType = TransportPathType.DIRECT,
                score = 94,
                grade = QualityGrade.S
            )
        )
        val directAuto = QualityEngine.evaluate(
            SingScaleUiState(
                peers = listOf(fastRelay, directExit),
                smartAutoSelectNode = true
            )
        )
        assertEquals("exit-us", directAuto.recommendedExitPeerId)
        assertEquals("", directAuto.recommendedRelayPeerId)

        // 3. Auto-discovered SOCKS5 proxy peer without native Exit flag automatically enters Exit & Relay pools
        val proxyPeer = TailnetPeer(
            stableId = "proxy-hk",
            hostName = "hk-box",
            tailscaleIPs = listOf("100.64.0.32"),
            online = true,
            exitNodeOption = false,
            hasPeerRelayCap = false,
            customConfig = PeerEndpointConfig(
                stableId = "proxy-hk",
                proxyProtocol = "socks",
                proxyPort = 1080
            ),
            gatewayProbeStatus = GatewayProbeStatus.PROBED
        )
        assertTrue(proxyPeer.canActAsExit)
        assertTrue(proxyPeer.canActAsRelay)
        assertEquals("出口+Relay", proxyPeer.shortRoleTag)
    }

    @Test
    fun `persisted login credentials keep session logged in across transient startup states without hiding NeedsLogin`() {
        val startingWithSavedKey = SingScaleUiState(
            backendState = "NoState",
            hasSavedLogin = true
        )
        assertTrue(startingWithSavedKey.isLoggedIn)

        val stoppedWithSavedKey = SingScaleUiState(
            backendState = "Stopped",
            hasSavedLogin = true
        )
        assertTrue(stoppedWithSavedKey.isLoggedIn)

        val expiredNodeKey = SingScaleUiState(
            backendState = "NeedsLogin",
            hasSavedLogin = true,
            authUrl = "https://login.tailscale.com/a/example-auth-token"
        )
        assertFalse(expiredNodeKey.isLoggedIn)
    }

    @Test
    fun `reload fingerprint stays stable across live tailscale pref updates and magicDnsSuffix discovery`() {
        val exitUs = TailnetPeer(
            stableId = "exit-us",
            hostName = "us-exit",
            tailscaleIPs = listOf("100.64.0.41"),
            online = true,
            exitNodeOption = true
        )
        val exitJp = TailnetPeer(
            stableId = "exit-jp",
            hostName = "jp-exit",
            tailscaleIPs = listOf("100.64.0.42"),
            online = true,
            exitNodeOption = true
        )
        val relaySg = TailnetPeer(
            stableId = "relay-sg",
            hostName = "sg-relay",
            tailscaleIPs = listOf("100.64.0.43"),
            online = true,
            hasPeerRelayCap = true,
            peerRelay = "203.0.113.10:41641"
        )

        // 1. Right after Tailscale login completes, discovering .ts.net magicDnsSuffix does NOT trigger reload
        val preLoginPlan = ConfigBuilder.build(
            state = SingScaleUiState(magicDnsSuffix = ""),
            defaultDeviceHostname = "singscale-test",
            enableIpv6 = false,
            stateDirectory = "/data/user/0/dev.singscale.app/files/tailscale"
        )
        val postLoginPlan = ConfigBuilder.build(
            state = SingScaleUiState(magicDnsSuffix = "example.ts.net"),
            defaultDeviceHostname = "singscale-test",
            enableIpv6 = false,
            stateDirectory = "/data/user/0/dev.singscale.app/files/tailscale"
        )
        assertEquals(preLoginPlan.reloadFingerprint, postLoginPlan.reloadFingerprint)

        // 2. Switching native Exit Nodes and native Peer Relay endpoints keeps reloadFingerprint identical
        val baseExitState = SingScaleUiState(
            peers = listOf(exitUs, exitJp, relaySg),
            activeExitPeerId = "exit-jp",
            policyMode = RoutingPolicyMode.SMART_QUALITY,
            dynamicTier = DynamicTier.FULL_ACCELERATION
        )
        val initialPlan = ConfigBuilder.build(
            state = baseExitState,
            defaultDeviceHostname = "singscale-test",
            enableIpv6 = false,
            stateDirectory = "/data/user/0/dev.singscale.app/files/tailscale"
        )

        val liveUpdatedState = baseExitState.copy(
            magicDnsSuffix = "example.ts.net",
            activeExitPeerId = "exit-us",
            activeRelayPeerId = "relay-sg",
            thresholds = baseExitState.thresholds.copy(acceptSubnetRoutes = false),
            authConfig = baseExitState.authConfig.copy(localRelayServerPort = 41642)
        )
        val liveUpdatedPlan = ConfigBuilder.build(
            state = liveUpdatedState,
            defaultDeviceHostname = "singscale-test",
            enableIpv6 = false,
            stateDirectory = "/data/user/0/dev.singscale.app/files/tailscale"
        )

        assertEquals("100.64.0.42", initialPlan.nativeExitNodeTarget)
        assertEquals("100.64.0.41", liveUpdatedPlan.nativeExitNodeTarget)
        assertEquals(initialPlan.reloadFingerprint, liveUpdatedPlan.reloadFingerprint)

        // 3. Adding a structural SOCKS5 proxy outbound DOES change reloadFingerprint so sing-box reloads outbounds
        val proxyExitState = liveUpdatedState.copy(
            peerConfigs = mapOf(
                "exit-us" to PeerEndpointConfig(
                    stableId = "exit-us",
                    gatewayMode = PeerGatewayMode.PROXY_OVER_TS,
                    proxyProtocol = "socks",
                    proxyPort = 1080
                )
            )
        )
        val proxyPlan = ConfigBuilder.build(
            state = proxyExitState,
            defaultDeviceHostname = "singscale-test",
            enableIpv6 = false,
            stateDirectory = "/data/user/0/dev.singscale.app/files/tailscale"
        )
        assertNotEquals(initialPlan.reloadFingerprint, proxyPlan.reloadFingerprint)
    }

    @Test
    fun `traffic split tracker accurately separates relay, mesh, and direct, aggregates domains, enriches dns, and decays idle speeds`() {
        TrafficSplitTracker.resetSession()
        val now = 1_700_000_000_000L

        val sampleExit = TailnetPeer(
            stableId = "exit-1",
            hostName = "exit-node-1",
            tailscaleIPs = listOf("100.64.0.50"),
            online = true,
            exitNodeOption = true,
            curAddr = "203.0.113.50:41641"
        )
        val meshPeer = TailnetPeer(
            stableId = "mesh-1",
            hostName = "mesh-peer-1",
            tailscaleIPs = listOf("100.64.0.51"),
            online = true,
            curAddr = "198.51.100.51:41641"
        )
        val uiState = SingScaleUiState(
            peers = listOf(sampleExit, meshPeer),
            activeExitPeerId = "exit-1"
        )

        // 1. Two relayed connections to api.github.com (one with SNI, one raw IP enriched via DNS log) -> 800,000 bytes (40%)
        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-gh-1",
                domain = "api.github.com",
                destination = "203.0.113.6:443",
                source = "172.19.0.1:52301",
                network = "TCP",
                protocol = "TLS",
                outboundTag = ConfigBuilder.OUTBOUND_EXIT,
                chain = listOf(ConfigBuilder.OUTBOUND_EXIT, ConfigBuilder.OUTBOUND_SELECTOR),
                rule = "final",
                createdAtMs = now - 3000L,
                uplinkTotal = 100_000L,
                downlinkTotal = 500_000L,
                uplinkBps = 20_000L,
                downlinkBps = 180_000L,
                lastRateAtMs = now,
                lastSeenAtMs = now
            ),
            uiState = uiState
        )

        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-gh-2",
                domain = "",
                destination = "203.0.113.6:443",
                source = "172.19.0.1:52302",
                network = "UDP",
                protocol = "QUIC",
                outboundTag = ConfigBuilder.OUTBOUND_EXIT,
                chain = listOf(ConfigBuilder.OUTBOUND_EXIT, ConfigBuilder.OUTBOUND_SELECTOR),
                rule = "final",
                createdAtMs = now - 2000L,
                uplinkTotal = 50_000L,
                downlinkTotal = 150_000L,
                uplinkBps = 10_000L,
                downlinkBps = 40_000L,
                lastRateAtMs = now,
                lastSeenAtMs = now
            ),
            uiState = uiState
        )

        // Enrich raw IP 203.0.113.6 -> api.github.com
        TrafficSplitTracker.onDnsResolved("api.github.com.", "203.0.113.6")

        // 2. One Tailnet mesh direct connection to 100.64.0.51:22 -> 200,000 bytes (10%) in dedicated TAILNET_MESH bucket!
        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-mesh-1",
                domain = "",
                destination = "100.64.0.51:22",
                source = "100.64.0.100:52310",
                network = "TCP",
                protocol = "SSH",
                outboundTag = ConfigBuilder.ENDPOINT_TAG,
                chain = listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH),
                rule = "ip_cidr=100.64.0.0/10 => ts-mesh",
                createdAtMs = now - 1500L,
                uplinkTotal = 50_000L,
                downlinkTotal = 150_000L,
                uplinkBps = 5_000L,
                downlinkBps = 25_000L,
                lastRateAtMs = now,
                lastSeenAtMs = now
            ),
            uiState = uiState
        )

        // 3. One direct connection to www.example.com -> 1,000,000 bytes (50%)
        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-direct-1",
                domain = "www.example.com",
                destination = "192.0.2.29:443",
                source = "100.64.0.100:52320",
                network = "TCP",
                protocol = "TLS",
                outboundTag = ConfigBuilder.OUTBOUND_DIRECT,
                chain = listOf(ConfigBuilder.OUTBOUND_DIRECT),
                rule = "domain_suffix=.example.com => direct",
                createdAtMs = now - 1000L,
                uplinkTotal = 200_000L,
                downlinkTotal = 800_000L,
                uplinkBps = 50_000L,
                downlinkBps = 350_000L,
                lastRateAtMs = now,
                lastSeenAtMs = now
            ),
            uiState = uiState
        )

        val snap = TrafficSplitTracker.state.value
        // Relay/Exit strictly excludes Tailnet Mesh!
        assertEquals(150_000L, snap.relayUpBytes)
        assertEquals(650_000L, snap.relayDownBytes)
        assertEquals(800_000L, snap.relayTotalBytes)
        assertEquals(250_000L, snap.relayTotalBps)
        assertEquals(2, snap.activeRelayConnections)

        // Tailnet Mesh has its own independent up/down/total/bps/connections!
        assertEquals(50_000L, snap.meshUpBytes)
        assertEquals(150_000L, snap.meshDownBytes)
        assertEquals(200_000L, snap.meshTotalBytes)
        assertEquals(200_000L, snap.meshBytes)
        assertEquals(30_000L, snap.meshTotalBps)
        assertEquals(1, snap.activeMeshConnections)

        // Local Direct is strictly independent!
        assertEquals(200_000L, snap.directUpBytes)
        assertEquals(800_000L, snap.directDownBytes)
        assertEquals(1_000_000L, snap.directTotalBytes)
        assertEquals(400_000L, snap.directTotalBps)
        assertEquals(1, snap.activeDirectConnections)

        // Exact 3-way sum conservation (no double-counting, no omissions)
        assertEquals(2_000_000L, snap.combinedBytes)
        assertEquals(snap.relayUpBytes + snap.meshUpBytes + snap.directUpBytes, snap.combinedUpBytes)
        assertEquals(snap.relayDownBytes + snap.meshDownBytes + snap.directDownBytes, snap.combinedDownBytes)
        assertEquals(680_000L, snap.combinedBps)
        assertEquals(4, snap.totalActiveConnections)
        assertEquals(40f, snap.relaySharePercent, 0.05f)
        assertEquals(10f, snap.meshSharePercent, 0.05f)
        assertEquals(50f, snap.directSharePercent, 0.05f)
        assertEquals(100f, snap.relaySharePercent + snap.meshSharePercent + snap.directSharePercent, 0.01f)

        // Both conn-gh-1 and conn-gh-2 must merge into a single DomainTrafficSummary for api.github.com
        assertEquals(3, snap.domains.size)
        val ghSummary = snap.domains.first { it.host == "api.github.com" }
        assertEquals(TrafficRouteCategory.RELAY_EXIT, ghSummary.primaryCategory)
        assertEquals(800_000L, ghSummary.totalBytes)
        assertEquals(2, ghSummary.totalConnections)
        assertTrue(ghSummary.routeNodeLabel.contains("exit-node-1"))

        val meshSummary = snap.domains.first { it.primaryCategory == TrafficRouteCategory.TAILNET_MESH }
        assertTrue(meshSummary.host.contains("mesh-peer-1"))
        assertEquals(TransportPathType.DIRECT, meshSummary.transportPath)

        // 4. Advance clock past RATE_STALE_MS via tick1Hz -> idle socket speeds decay to 0 B/s while cumulative totals remain intact
        TrafficSplitTracker.tick1Hz(uiState = uiState, nowMs = now + 3_000L)
        val decayed = TrafficSplitTracker.state.value
        assertEquals(0L, decayed.relayTotalBps)
        assertEquals(0L, decayed.meshTotalBps)
        assertEquals(0L, decayed.directTotalBps)
        assertEquals(800_000L, decayed.relayTotalBytes)
        assertEquals(200_000L, decayed.meshTotalBytes)
        assertEquals(1_000_000L, decayed.directTotalBytes)
        assertEquals(2_000_000L, decayed.combinedBytes)
    }

    @Test
    fun `smart quality mode enables direct-first probing by default so healthy domains like googleapis stay direct unless forced`() {
        val sampleExit = TailnetPeer(
            stableId = "exit-1",
            hostName = "exit-node-1",
            tailscaleIPs = listOf("100.64.0.50"),
            online = true,
            exitNodeOption = true
        )
        val baseState = SingScaleUiState(
            peers = listOf(sampleExit),
            activeExitPeerId = "exit-1",
            policyMode = RoutingPolicyMode.SMART_QUALITY,
            dynamicTier = DynamicTier.FULL_ACCELERATION
        )

        val smartPlan = ConfigBuilder.build(baseState, "singscale-test", enableIpv6 = false)
        assertTrue(smartPlan.directFirstActive)

        // Region-neutral: zero hardcoded domain assumptions & local ISP DNS (`dns-local`) used by default
        // so overseas users get local city CDN IPs (googleapis.com, youtube.com, etc.) with lowest direct RTT!
        val parsedSmart = json.parseToJsonElement(smartPlan.configJson).jsonObject
        val dnsFinal = parsedSmart["dns"]!!.jsonObject["final"]!!.jsonPrimitive.content
        assertEquals("dns-local", dnsFinal)
        val rulesJson = parsedSmart["route"]!!.jsonObject["rules"]!!.jsonArray.toString()
        assertFalse(rulesJson.contains("github.com"))
        assertFalse(rulesJson.contains("baidu.com"))

        // Explicitly user-forced relay domain IS pinned to egress-select even when directFirstPreferred=true
        val forcedState = baseState.copy(
            thresholds = baseState.thresholds.copy(forcedRelayDomains = listOf("openai.com"))
        )
        val forcedPlan = ConfigBuilder.build(forcedState, "singscale-test", enableIpv6 = false)
        val forcedRulesJson = json.parseToJsonElement(forcedPlan.configJson)
            .jsonObject["route"]!!.jsonObject["rules"]!!.jsonArray.toString()
        assertTrue(forcedRulesJson.contains("openai.com"))

        // Global Exit disables directFirstActive so all traffic tunnels through exit
        val globalPlan = ConfigBuilder.build(
            baseState.copy(policyMode = RoutingPolicyMode.GLOBAL_EXIT),
            "singscale-test",
            enableIpv6 = false
        )
        assertFalse(globalPlan.directFirstActive)

        // Verify TrafficSplitTracker humanizes Go's direct-first / direct-poor probe verdicts clearly
        TrafficSplitTracker.resetSession()
        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-gapi-direct",
                domain = "play.googleapis.com",
                destination = "142.250.199.106:443",
                source = "172.19.0.1:53001",
                network = "TCP",
                protocol = "TLS",
                outboundTag = ConfigBuilder.OUTBOUND_DIRECT,
                chain = listOf(ConfigBuilder.OUTBOUND_DIRECT),
                rule = "direct-first(直连 44ms) => direct",
                createdAtMs = 1_700_000_010_000L,
                uplinkTotal = 12_000L,
                downlinkTotal = 88_000L,
                lastRateAtMs = 1_700_000_010_000L,
                lastSeenAtMs = 1_700_000_010_000L
            ),
            uiState = baseState
        )
        val dom = TrafficSplitTracker.state.value.domains.first { it.host == "play.googleapis.com" }
        assertEquals(TrafficRouteCategory.DIRECT, dom.primaryCategory)
        assertTrue(dom.matchedRule.contains("实测直连良好"))
        assertTrue(dom.matchedRule.contains("44ms"))
    }

    @Test
    fun `lan route exclusion keeps local lan out of tun0 while preserving advertised tailnet subnets`() {
        val subnetRouter = TailnetPeer(
            stableId = "office-router",
            hostName = "office-gw",
            tailscaleIPs = listOf("100.64.0.99"),
            online = true,
            primaryRoutes = listOf("192.168.50.0/24")
        )
        val state = SingScaleUiState(
            peers = listOf(subnetRouter)
        )

        val plan = ConfigBuilder.build(state, "singscale-test", enableIpv6 = false)
        val tunIn = json.parseToJsonElement(plan.configJson)
            .jsonObject["inbounds"]!!.jsonArray[0].jsonObject

        assertFalse(tunIn["strict_route"]!!.jsonPrimitive.content.toBoolean())
        val excluded = tunIn["route_exclude_address"]!!.jsonArray.map { it.jsonPrimitive.content }

        // 192.168.0.0/16 was split around advertised 192.168.50.0/24 so local LAN traffic
        // is excluded from tun0 while remote 192.168.50.0/24 enters tun0 -> ts-ep!
        assertFalse("192.168.0.0/16" in excluded)
        assertFalse("192.168.50.0/24" in excluded)
        assertTrue("192.168.0.0/19" in excluded)
        // 172.19.0.0/16 (tun0 172.19.0.1/30 + DNS hijack 172.19.0.2) is never excluded
        assertTrue(excluded.none { it.startsWith("172.19.") })
    }

    @Test
    fun `power state scheduler suspends all polling and active radio probes when screen is off and throttles background`() {
        val defaultThresholds = PolicyThresholds()
        assertEquals(45, defaultThresholds.probeIntervalSec)

        // 1. Screen OFF -> Zero UI telemetry, 0ms Tailnet poll (suspended), 0s Disco/HTTP probe (suspended)
        val dozeState = RuntimePowerState(
            isScreenInteractive = false,
            isAppForeground = true,
            isBatterySaverEnabled = false
        )
        assertEquals(RuntimePowerMode.DOZE_SCREEN_OFF, dozeState.mode)
        assertFalse(dozeState.shouldStreamLiveUiTelemetry)
        assertEquals(0L, dozeState.tailnetPollIntervalMs(isWaitingForAuth = false, withinStartupBootstrapWindow = false))
        assertEquals(0, dozeState.qualityProbeIntervalSec(45, RoutingPolicyMode.SMART_QUALITY))

        // 2. Screen ON in Background -> UI telemetry stream paused, steady poll slowed to 30s, probe slowed to 180s,
        //    while OAuth Custom Tabs login still keeps responsive 2.5s detection!
        val bgState = RuntimePowerState(
            isScreenInteractive = true,
            isAppForeground = false,
            isBatterySaverEnabled = false
        )
        assertEquals(RuntimePowerMode.BACKGROUND_SCREEN_ON, bgState.mode)
        assertFalse(bgState.shouldStreamLiveUiTelemetry)
        assertEquals(30_000L, bgState.tailnetPollIntervalMs(isWaitingForAuth = false, withinStartupBootstrapWindow = false))
        assertEquals(2_500L, bgState.tailnetPollIntervalMs(isWaitingForAuth = true, withinStartupBootstrapWindow = false))
        assertEquals(180, bgState.qualityProbeIntervalSec(45, RoutingPolicyMode.SMART_QUALITY))
        assertEquals(0, bgState.qualityProbeIntervalSec(45, RoutingPolicyMode.MESH_ONLY))

        // 3. Foreground Interactive -> Live UI telemetry active, 6s steady poll, 45s probe (90s under Battery Saver)
        val fgState = RuntimePowerState(
            isScreenInteractive = true,
            isAppForeground = true,
            isBatterySaverEnabled = false
        )
        assertEquals(RuntimePowerMode.FOREGROUND_INTERACTIVE, fgState.mode)
        assertTrue(fgState.shouldStreamLiveUiTelemetry)
        assertEquals(6_000L, fgState.tailnetPollIntervalMs(isWaitingForAuth = false, withinStartupBootstrapWindow = false))
        assertEquals(45, fgState.qualityProbeIntervalSec(45, RoutingPolicyMode.SMART_QUALITY))

        val fgSaverState = fgState.copy(isBatterySaverEnabled = true)
        assertEquals(90, fgSaverState.qualityProbeIntervalSec(45, RoutingPolicyMode.SMART_QUALITY))
    }

    @Test
    fun `config builder uses warn log level and native dns reverse_mapping to eliminate per-packet info log overhead`() {
        val plan = ConfigBuilder.build(SingScaleUiState(), "singscale-power-test")
        val root = json.parseToJsonElement(plan.configJson).jsonObject

        assertEquals("warn", root["log"]!!.jsonObject["level"]!!.jsonPrimitive.content)
        assertTrue(root["dns"]!!.jsonObject["reverse_mapping"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `cidr prefix sanitizer filters malformed cellular handoff prefixes that previously panicked netip MustParsePrefix`() {
        assertNull(formatValidatedCidrPrefix("198.51.100.9", isIpv6 = false, prefixLen = -1))
        assertNull(formatValidatedCidrPrefix(null, isIpv6 = false, prefixLen = 24))
        assertNull(formatValidatedCidrPrefix("null", isIpv6 = false, prefixLen = 24))
        assertNull(formatValidatedCidrPrefix("198.51.100.5", isIpv6 = false, prefixLen = 64))
        // Android CLAT / IPv4-mapped bug reporting 128 on IPv4 normalized safely to /32
        assertEquals("192.0.0.4/32", formatValidatedCidrPrefix("192.0.0.4", isIpv6 = false, prefixLen = 128))
        // Scoped IPv6 zone stripped cleanly
        assertEquals("fe80::1234/64", formatValidatedCidrPrefix("fe80::1234%wlan0", isIpv6 = true, prefixLen = 64))
        assertEquals("203.0.113.60/24", formatValidatedCidrPrefix("203.0.113.60", isIpv6 = false, prefixLen = 24))
    }

    @Test
    fun `interface sync deduplication suppresses cellular signal-bar churn while dispatching real link changes`() {
        val initialCellSig = InterfaceSyncSignature(
            networkHandle = 100001L,
            ifName = "rmnet_data7",
            expensive = true,
            dnsServers = listOf("8.8.8.8", "8.8.4.4"),
            linkAddresses = listOf("203.0.113.2/30")
        )
        assertTrue(shouldDispatchInterfaceSync(null, initialCellSig))
        // Repeated RSRP / bandwidth onCapabilitiesChanged callbacks with identical link properties must NOT re-trigger JNI netlink sync
        assertFalse(shouldDispatchInterfaceSync(initialCellSig, initialCellSig.copy()))

        // Switching to Wi-Fi or changing IP/DNS/metered state MUST dispatch immediately
        val wifiSig = initialCellSig.copy(
            networkHandle = 100002L,
            ifName = "wlan0",
            expensive = false,
            linkAddresses = listOf("198.51.100.12/24")
        )
        assertTrue(shouldDispatchInterfaceSync(initialCellSig, wifiSig))
    }

    @Test
    fun `local dns concurrency cap and event-driven widget xml prevent background thread and cached wakeup storms`() {
        assertEquals(16, LocalResolver.MAX_CONCURRENT_LOCAL_DNS)

        val widgetXml = File("src/main/res/xml/singscale_widget_info.xml")
        val miniWidgetXml = File("src/main/res/xml/singscale_mini_widget_info.xml")
        if (widgetXml.isFile && miniWidgetXml.isFile) {
            assertTrue(widgetXml.readText().contains("android:updatePeriodMillis=\"0\""))
            assertTrue(miniWidgetXml.readText().contains("android:updatePeriodMillis=\"0\""))
        }
    }

    @Test
    fun `config builder falls back to 172_19_0_1_30 before login and binds real tailscale 100_x_32 with magic dns once known`() {
        // 1. Strict CGNAT 100.64.0.0/10 validator rejects non-Tailscale & reserved Quad100 addresses
        assertNull(ConfigBuilder.normalizeTailscaleIpv4(null))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4(""))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("172.19.0.1"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("198.51.100.50"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("100.63.255.255"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("100.128.0.1"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("100.100.100.100"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("100.64.0.0"))
        assertNull(ConfigBuilder.normalizeTailscaleIpv4("100.127.255.255"))
        assertEquals("100.64.0.100", ConfigBuilder.normalizeTailscaleIpv4("100.64.0.100/32"))
        assertEquals("100.64.0.1", ConfigBuilder.normalizeTailscaleIpv4(" 100.64.0.1 "))
        assertEquals("172.19.0.1/30", ConfigBuilder.resolveTunIpv4Cidr(""))
        assertEquals("100.64.0.100/32", ConfigBuilder.resolveTunIpv4Cidr("100.64.0.100"))

        // 2. Unauthenticated state safely falls back to 172.19.0.1/30 without overriding dns_address
        val fallbackPlan = ConfigBuilder.build(SingScaleUiState(), "singscale-test", enableIpv6 = false)
        assertEquals("172.19.0.1/30", fallbackPlan.tunIpv4Address)
        assertFalse(fallbackPlan.usesTailscaleTunIp)
        val fallbackTun = json.parseToJsonElement(fallbackPlan.configJson)
            .jsonObject["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("172.19.0.1/30"), fallbackTun["address"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertFalse(fallbackTun.containsKey("dns_address"))

        // 3. Authenticated state with real Tailscale 100.x IPv4 binds 100.x/32 + MagicDNS 100.100.100.100
        val selfPeer = TailnetPeer(
            stableId = "self-phone",
            hostName = "android-device",
            tailscaleIPs = listOf("100.64.0.100", "fd7a:115c:a1e0::abc1")
        )
        val loggedInState = SingScaleUiState(
            backendState = "Running",
            hasSavedLogin = true,
            selfPeer = selfPeer
        )
        val v4Plan = ConfigBuilder.build(loggedInState, "singscale-test", enableIpv6 = false)
        assertEquals("100.64.0.100/32", v4Plan.tunIpv4Address)
        assertTrue(v4Plan.usesTailscaleTunIp)
        assertNotEquals(fallbackPlan.reloadFingerprint, v4Plan.reloadFingerprint)

        val v4Root = json.parseToJsonElement(v4Plan.configJson).jsonObject
        val v4Tun = v4Root["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(listOf("100.64.0.100/32"), v4Tun["address"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("100.100.100.100"), v4Tun["dns_address"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Verify 100.64.0.0/10 is NOT excluded from tun0 and routes to ts-ep without conflict
        val excluded = v4Tun["route_exclude_address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(excluded.none { it.startsWith("100.") })
        val routeRules = v4Root["route"]!!.jsonObject["rules"]!!.jsonArray.toString()
        assertTrue(routeRules.contains("100.64.0.0/10"))
        assertTrue(routeRules.contains("hijack-dns"))

        // 4. Dual-stack binds both 100.x/32 + fd7a:.../128 and both MagicDNS v4 + v6
        val dualPlan = ConfigBuilder.build(loggedInState, "singscale-test", enableIpv6 = true)
        val dualTun = json.parseToJsonElement(dualPlan.configJson)
            .jsonObject["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(
            listOf("100.64.0.100/32", "fd7a:115c:a1e0::abc1/128"),
            dualTun["address"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            listOf("100.100.100.100", "fd7a:115c:a1e0::53"),
            dualTun["dns_address"]!!.jsonArray.map { it.jsonPrimitive.content }
        )

        // 5. Toggling allowTailnetInboundLocalForward is live-RPC managed and does NOT force TUN reload
        assertTrue(PolicyThresholds().allowTailnetInboundLocalForward)
        val disabledInboundPlan = ConfigBuilder.build(
            loggedInState.copy(thresholds = loggedInState.thresholds.copy(allowTailnetInboundLocalForward = false)),
            "singscale-test",
            enableIpv6 = false
        )
        assertEquals(v4Plan.reloadFingerprint, disabledInboundPlan.reloadFingerprint)
    }

    @Test
    fun `tun address rebind guard performs one-shot upgrade without reentrancy or flapping loops`() {
        val guard = TunAddressRebindGuard(
            minRebindIntervalMs = 10_000L,
            failureBackoffMs = 30_000L,
            maxFailuresPerTarget = 2,
            windowMs = 120_000L,
            maxRebindsPerWindow = 3
        )
        var clock = 1_000_000L

        // Before login / non-Running / invalid IP -> never triggers rebind
        assertEquals(TunRebindDecision.SKIP_NOT_RUNNING, guard.evaluateAndBegin("NeedsLogin", "100.64.0.100", clock).first)
        assertEquals(TunRebindDecision.SKIP_INVALID_IP, guard.evaluateAndBegin("Running", "100.100.100.100", clock).first)
        assertEquals(TunRebindDecision.SKIP_INVALID_IP, guard.evaluateAndBegin("Running", "", clock).first)

        // First login acquisition: fallback 172.19.0.1/30 -> 100.64.0.100/32 runs immediately
        val (d1, target1) = guard.evaluateAndBegin("Running", "100.64.0.100", clock)
        assertEquals(TunRebindDecision.REBIND_REQUIRED, d1)
        assertEquals("100.64.0.100/32", target1)

        // Concurrent/duplicate snapshot while in flight is strictly suppressed (reentrancy guard)
        assertEquals(TunRebindDecision.SKIP_IN_FLIGHT, guard.evaluateAndBegin("Running", "100.64.0.100", clock + 200L).first)

        // Rebind completes -> subsequent snapshots with same IP are strict NO_CHANGE
        guard.markRebindSucceeded(target1, clock + 500L)
        assertEquals("100.64.0.100/32", guard.currentAppliedCidr())
        assertEquals(TunRebindDecision.NO_CHANGE, guard.evaluateAndBegin("Running", "100.64.0.100", clock + 1_000L).first)

        // Rapid IP change within cooldown window is blocked
        assertEquals(TunRebindDecision.SKIP_COOLDOWN, guard.evaluateAndBegin("Running", "100.64.0.101", clock + 3_000L).first)

        // After cooldown, 2nd & 3rd changes succeed, then 4th within 120s window hits loop cap!
        clock += 15_000L
        assertEquals(TunRebindDecision.REBIND_REQUIRED, guard.evaluateAndBegin("Running", "100.64.0.101", clock).first)
        guard.markRebindSucceeded("100.64.0.101/32", clock + 100L)

        clock += 15_000L
        assertEquals(TunRebindDecision.REBIND_REQUIRED, guard.evaluateAndBegin("Running", "100.64.0.102", clock).first)
        guard.markRebindSucceeded("100.64.0.102/32", clock + 100L)

        clock += 15_000L
        assertEquals(TunRebindDecision.SKIP_LOOP_LIMIT_REACHED, guard.evaluateAndBegin("Running", "100.64.0.103", clock).first)

        // Failure backoff and per-target retry cap prevent crash-retry storms
        guard.reset()
        val failClock = 2_000_000L
        assertEquals(TunRebindDecision.REBIND_REQUIRED, guard.evaluateAndBegin("Running", "100.64.1.2", failClock).first)
        guard.markRebindFailed("100.64.1.2/32", failClock + 100L)
        assertEquals(TunRebindDecision.SKIP_COOLDOWN, guard.evaluateAndBegin("Running", "100.64.1.2", failClock + 5_000L).first)
        assertEquals(TunRebindDecision.REBIND_REQUIRED, guard.evaluateAndBegin("Running", "100.64.1.2", failClock + 35_000L).first)
        guard.markRebindFailed("100.64.1.2/32", failClock + 35_100L)
        assertEquals(TunRebindDecision.SKIP_LOOP_LIMIT_REACHED, guard.evaluateAndBegin("Running", "100.64.1.2", failClock + 90_000L).first)
    }

    @Test
    fun `repository preserves cached self tailscale ip across transient startup placeholder snapshots`() {
        SingScaleRepository.clearAuthStateOnLogout()
        SingScaleRepository.updateTailnetSnapshot(
            backendState = "Running",
            authUrl = "",
            hasSavedLogin = true,
            tailnetName = "example.ts.net",
            magicDnsSuffix = "example.ts.net",
            selfPeer = TailnetPeer(
                stableId = "self-1",
                hostName = "android-device",
                tailscaleIPs = listOf("100.64.0.100")
            ),
            peers = emptyList(),
            reportedExitNodeId = ""
        )
        assertEquals("100.64.0.100", SingScaleRepository.state.value.selfPeer?.primaryIpv4)

        // Transient "Starting" placeholder with empty tailscaleIPs MUST NOT wipe cached 100.x IP
        SingScaleRepository.updateTailnetSnapshot(
            backendState = "Starting",
            authUrl = "",
            hasSavedLogin = true,
            tailnetName = "example.ts.net",
            magicDnsSuffix = "example.ts.net",
            selfPeer = TailnetPeer(
                stableId = "self-placeholder",
                hostName = "localhost",
                tailscaleIPs = emptyList()
            ),
            peers = emptyList(),
            reportedExitNodeId = ""
        )
        assertEquals("100.64.0.100", SingScaleRepository.state.value.selfPeer?.primaryIpv4)

        // Explicit logout clears selfPeer so next start falls back to 172.19.0.1/30
        SingScaleRepository.clearAuthStateOnLogout()
        assertNull(SingScaleRepository.state.value.selfPeer)
    }

    @Test
    fun `oom process rss budget leaves headroom for single-process compose ui and libbox without false triggers`() {
        val gib = 1024L * 1024L * 1024L
        val mib = 1024L * 1024L

        assertEquals(384L * mib, SingScaleApp.computeOomProcessRssBudgetBytes(0L))
        assertEquals(384L * mib, SingScaleApp.computeOomProcessRssBudgetBytes(4L * gib))
        assertEquals(512L * mib, SingScaleApp.computeOomProcessRssBudgetBytes(6L * gib))
        assertEquals(640L * mib, SingScaleApp.computeOomProcessRssBudgetBytes(8L * gib))
        assertEquals(640L * mib, SingScaleApp.computeOomProcessRssBudgetBytes(12L * gib))
    }

    @Test
    fun `config builder isolates tailnet mesh rules onto dedicated ts-mesh outbound separate from egress-select`() {
        val subnetRouter = TailnetPeer(
            stableId = "office-gw",
            hostName = "office-gw",
            tailscaleIPs = listOf("100.64.0.99"),
            online = true,
            primaryRoutes = listOf("198.51.100.0/24")
        )
        val exitPeer = TailnetPeer(
            stableId = "exit-node-1",
            hostName = "exit-node-1",
            tailscaleIPs = listOf("100.64.0.55"),
            online = true,
            exitNodeOption = true
        )
        val plan = ConfigBuilder.build(
            SingScaleUiState(
                peers = listOf(subnetRouter, exitPeer),
                activeExitPeerId = "exit-node-1",
                policyMode = RoutingPolicyMode.GLOBAL_EXIT
            ),
            defaultDeviceHostname = "singscale-mesh-split"
        )
        val root = json.parseToJsonElement(plan.configJson).jsonObject
        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        val meshSelector = outbounds.first { it["tag"]?.jsonPrimitive?.content == ConfigBuilder.OUTBOUND_MESH }
        assertEquals("selector", meshSelector["type"]!!.jsonPrimitive.content)
        assertEquals(listOf(ConfigBuilder.ENDPOINT_TAG), meshSelector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })

        val rules = root["route"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        val cgnatRule = rules.first { it["ip_cidr"]?.toString()?.contains("100.64.0.0/10") == true }
        val subnetRule = rules.first { it["ip_cidr"]?.toString()?.contains("198.51.100.0/24") == true }
        assertEquals(ConfigBuilder.OUTBOUND_MESH, cgnatRule["outbound"]!!.jsonPrimitive.content)
        assertEquals(ConfigBuilder.OUTBOUND_MESH, subnetRule["outbound"]!!.jsonPrimitive.content)
        assertEquals(ConfigBuilder.OUTBOUND_SELECTOR, root["route"]!!.jsonObject["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `three-way route classification distinguishes tailnet mesh, advertised subnets, native exit, proxy relay, and local direct`() {
        // Local direct (including egress-select pointing to direct)
        assertEquals(
            TrafficRouteCategory.DIRECT,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.OUTBOUND_DIRECT, listOf(ConfigBuilder.OUTBOUND_DIRECT), "192.0.2.29:443")
        )
        assertEquals(
            TrafficRouteCategory.DIRECT,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.OUTBOUND_DIRECT, listOf(ConfigBuilder.OUTBOUND_DIRECT, ConfigBuilder.OUTBOUND_SELECTOR), "198.51.100.106:443")
        )

        // Tailnet Mesh: 100.x, fd7a::, advertised subnet via ts-mesh AND legacy direct ts-ep without egress-select
        assertEquals(
            TrafficRouteCategory.TAILNET_MESH,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.ENDPOINT_TAG, listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH), "100.64.0.51:22")
        )
        assertEquals(
            TrafficRouteCategory.TAILNET_MESH,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.ENDPOINT_TAG, listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH), "198.51.100.10:80")
        )
        assertEquals(
            TrafficRouteCategory.TAILNET_MESH,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.ENDPOINT_TAG, listOf(ConfigBuilder.ENDPOINT_TAG), "[fd7a:115c:a1e0::abcd]:443")
        )
        assertEquals(
            TrafficRouteCategory.TAILNET_MESH,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.ENDPOINT_TAG, listOf(ConfigBuilder.ENDPOINT_TAG), "203.0.113.40:8080")
        )

        // Relay / Exit: Native Exit via egress-select -> ts-ep, proxy peer-exit, proxy peer-relay
        assertEquals(
            TrafficRouteCategory.RELAY_EXIT,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.ENDPOINT_TAG, listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_SELECTOR), "203.0.113.6:443")
        )
        assertEquals(
            TrafficRouteCategory.RELAY_EXIT,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.OUTBOUND_EXIT, listOf(ConfigBuilder.OUTBOUND_EXIT, ConfigBuilder.OUTBOUND_SELECTOR), "192.0.2.1:443")
        )
        assertEquals(
            TrafficRouteCategory.RELAY_EXIT,
            TrafficSplitTracker.classifyRouteCategory(ConfigBuilder.OUTBOUND_RELAY, listOf(ConfigBuilder.OUTBOUND_RELAY, ConfigBuilder.OUTBOUND_SELECTOR), "198.51.100.8:443")
        )

        // Advertised subnet route connection resolves subnet router peer label & path
        TrafficSplitTracker.resetSession()
        val subnetRouter = TailnetPeer(
            stableId = "office-gw",
            hostName = "office-gw",
            tailscaleIPs = listOf("100.64.0.99"),
            online = true,
            primaryRoutes = listOf("198.51.100.0/24"),
            derpRegion = "hkg"
        )
        TrafficSplitTracker.ingestSyntheticRecordForTest(
            TrafficSplitTracker.RawConnData(
                id = "conn-subnet-1",
                domain = "",
                destination = "198.51.100.10:8080",
                source = "100.64.0.100:50001",
                network = "TCP",
                protocol = "HTTP",
                outboundTag = ConfigBuilder.ENDPOINT_TAG,
                chain = listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH),
                rule = "ip_cidr=198.51.100.0/24",
                createdAtMs = 1_700_000_050_000L,
                uplinkTotal = 10_000L,
                downlinkTotal = 40_000L,
                lastRateAtMs = 1_700_000_050_000L,
                lastSeenAtMs = 1_700_000_050_000L
            ),
            uiState = SingScaleUiState(peers = listOf(subnetRouter))
        )
        val snap = TrafficSplitTracker.state.value
        assertEquals(50_000L, snap.meshTotalBytes)
        assertEquals(0L, snap.relayTotalBytes)
        assertEquals(0L, snap.directTotalBytes)
        val conn = snap.connections.first()
        assertEquals(TrafficRouteCategory.TAILNET_MESH, conn.routeCategory)
        assertTrue(conn.routeNodeLabel.contains("office-gw"))
        assertEquals(TransportPathType.DERP, conn.transportPath)
    }

    @Test
    fun `three-way display percentages strictly conserve 100_0 percent across tricky boundary ratios`() {
        assertEquals(Triple(0, 0, 0), TrafficSplitTracker.computeDisplayTenths(0L, 0L, 0L))
        assertEquals(Triple("0.0%", "0.0%", "0.0%"), TrafficSplitTracker.computeDisplayPercents(0L, 0L, 0L))

        val cases = listOf(
            Triple(1L, 1L, 1L),
            Triple(100L, 0L, 0L),
            Triple(0L, 100L, 0L),
            Triple(0L, 0L, 100L),
            Triple(7L, 13L, 19L),
            Triple(999_999_999L, 1L, 0L),
            Triple(333L, 333L, 334L),
            Triple(2L, 3L, 7L)
        )
        for ((r, m, d) in cases) {
            val (rt, mt, dt) = TrafficSplitTracker.computeDisplayTenths(r, m, d)
            assertEquals("Sum of tenths must be 1000 for ($r, $m, $d)", 1000, rt + mt + dt)
            assertTrue(rt in 0..1000 && mt in 0..1000 && dt in 0..1000)
        }
    }

    @Test
    fun `traffic split tracker reconciles kernel evicted totals and archives hot-reload epochs without double counting or dips`() {
        TrafficSplitTracker.resetSession()
        val uiState = SingScaleUiState()
        val now = 1_700_001_000_000L

        // Epoch 1 initial active connections across all 3 buckets
        val cRelay = TrafficSplitTracker.RawConnData(
            id = "e1-relay", domain = "github.com", destination = "203.0.113.4:443", source = "100.64.0.1:40001",
            network = "TCP", protocol = "TLS", outboundTag = ConfigBuilder.ENDPOINT_TAG,
            chain = listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_SELECTOR), rule = "final",
            createdAtMs = now, uplinkTotal = 10_000L, downlinkTotal = 90_000L, lastRateAtMs = now, lastSeenAtMs = now
        )
        val cMesh = TrafficSplitTracker.RawConnData(
            id = "e1-mesh", domain = "", destination = "100.64.0.2:22", source = "100.64.0.1:40002",
            network = "TCP", protocol = "SSH", outboundTag = ConfigBuilder.ENDPOINT_TAG,
            chain = listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH), rule = "ip_cidr=100.64.0.0/10",
            createdAtMs = now, uplinkTotal = 20_000L, downlinkTotal = 30_000L, lastRateAtMs = now, lastSeenAtMs = now
        )
        val cDirect = TrafficSplitTracker.RawConnData(
            id = "e1-direct", domain = "example.com", destination = "192.0.2.55:443", source = "100.64.0.1:40003",
            network = "TCP", protocol = "TLS", outboundTag = ConfigBuilder.OUTBOUND_DIRECT,
            chain = listOf(ConfigBuilder.OUTBOUND_DIRECT), rule = "direct",
            createdAtMs = now, uplinkTotal = 5_000L, downlinkTotal = 45_000L, lastRateAtMs = now, lastSeenAtMs = now
        )
        TrafficSplitTracker.ingestSyntheticRecordForTest(cRelay, uiState, instanceEpoch = 1)
        TrafficSplitTracker.ingestSyntheticRecordForTest(cMesh, uiState, instanceEpoch = 1)
        TrafficSplitTracker.ingestSyntheticRecordForTest(cDirect, uiState, instanceEpoch = 1)

        // Simulate background -> foreground stream reconnect on SAME epoch 1 where cMesh was already evicted from closed ring
        TrafficSplitTracker.simulateResetBatchForTest(
            newRecords = listOf(cRelay.copy(downlinkTotal = 110_000L), cDirect),
            uiState = uiState,
            instanceEpoch = 1,
            nowMs = now + 2000L
        )
        val afterReconnect = TrafficSplitTracker.state.value
        // Monotonic floor prevents mesh bytes from dipping to 0 before kernel sync arrives!
        assertEquals(50_000L, afterReconnect.meshTotalBytes)
        assertEquals(120_000L, afterReconnect.relayTotalBytes)
        assertEquals(50_000L, afterReconnect.directTotalBytes)

        // Kernel reconciliation reports additional evicted bytes accumulated while app was in background
        TrafficSplitTracker.reconcileKernelTotals(
            kernelSplit = KernelTrafficSplit(
                direct = KernelBucketTotals(uplinkBytes = 15_000L, downlinkBytes = 85_000L, activeConnections = 1, totalConnections = 12),
                mesh = KernelBucketTotals(uplinkBytes = 40_000L, downlinkBytes = 160_000L, activeConnections = 0, totalConnections = 5),
                relay = KernelBucketTotals(uplinkBytes = 30_000L, downlinkBytes = 270_000L, activeConnections = 1, totalConnections = 20)
            ),
            uiState = uiState,
            instanceEpoch = 1
        )
        val afterReconcile = TrafficSplitTracker.state.value
        assertEquals(100_000L, afterReconcile.directTotalBytes)
        assertEquals(200_000L, afterReconcile.meshTotalBytes)
        assertEquals(300_000L, afterReconcile.relayTotalBytes)
        assertEquals(600_000L, afterReconcile.combinedBytes)
        assertEquals(12, afterReconcile.totalDirectConnections)
        assertEquals(5, afterReconcile.totalMeshConnections)
        assertEquals(20, afterReconcile.totalRelayConnections)

        // Hot-reload to epoch 2 archives epoch 1 cleanly and adds new epoch 2 traffic without double counting
        val e2Mesh = TrafficSplitTracker.RawConnData(
            id = "e2-mesh", domain = "", destination = "100.64.0.9:5555", source = "100.64.0.1:50009",
            network = "TCP", protocol = "", outboundTag = ConfigBuilder.ENDPOINT_TAG,
            chain = listOf(ConfigBuilder.ENDPOINT_TAG, ConfigBuilder.OUTBOUND_MESH), rule = "ip_cidr=100.64.0.0/10",
            createdAtMs = now + 5000L, uplinkTotal = 10_000L, downlinkTotal = 40_000L, lastRateAtMs = now + 5000L, lastSeenAtMs = now + 5000L
        )
        TrafficSplitTracker.simulateResetBatchForTest(
            newRecords = listOf(e2Mesh),
            uiState = uiState,
            instanceEpoch = 2,
            nowMs = now + 5000L
        )
        val afterEpoch2 = TrafficSplitTracker.state.value
        assertEquals(100_000L, afterEpoch2.directTotalBytes)
        assertEquals(250_000L, afterEpoch2.meshTotalBytes)
        assertEquals(300_000L, afterEpoch2.relayTotalBytes)
        assertEquals(650_000L, afterEpoch2.combinedBytes)
        assertEquals(6, afterEpoch2.totalMeshConnections)
    }

    @Test
    fun `legacy v1_0_4 persisted settings JSON deserializes cleanly with full backward compatibility`() {
        val legacyV104Json = """
            {
              "selectedExitPeerId": "node-exit-01",
              "selectedRelayPeerId": "node-relay-02",
              "smartAutoSelectNode": false,
              "policyMode": "SMART_QUALITY",
              "thresholds": {
                "probeIntervalSec": 15,
                "directFirstPreferred": true,
                "directAcceptableLatencyMs": 300,
                "allowTailnetInboundLocalForward": true,
                "customDirectDomains": ["cn", "baidu.com", "qq.com", "taobao.com", "bilibili.com", "163.com"],
                "customProxyDomains": ["google.com", "github.com", "youtube.com", "openai.com", "anthropic.com", "wikipedia.org"],
                "deprecatedLegacyFlag": true
              },
              "rulesMigratedV2": false,
              "powerMigratedV3": false,
              "hasSavedLogin": true,
              "cachedTailnetName": "example.ts.net",
              "cachedSelfName": "android-device",
              "cachedSelfIp": "100.64.0.100",
              "cachedSelfIpv6": "fd7a:115c:a1e0::abc1",
              "cachedPeers": [
                {
                  "stableId": "node-exit-01",
                  "hostName": "exit-node-1",
                  "tailscaleIPs": ["100.64.0.50"],
                  "online": true,
                  "active": true,
                  "exitNodeOption": true,
                  "curAddr": "203.0.113.50:41641",
                  "rxBytes": 12345,
                  "txBytes": 6789
                }
              ],
              "unknownFutureField": "should-be-ignored"
            }
        """.trimIndent()

        val decoded = SingScaleRepository.decodePersistedSettings(legacyV104Json)
        val hydrated = SingScaleRepository.hydrateStateFromPersisted(
            base = SingScaleUiState(),
            saved = decoded,
            diskHasLogin = true
        )

        assertEquals("node-exit-01", hydrated.selectedExitPeerId)
        assertEquals("node-relay-02", hydrated.selectedRelayPeerId)
        assertFalse(hydrated.smartAutoSelectNode)
        assertEquals("example.ts.net", hydrated.tailnetName)
        assertEquals("100.64.0.100", hydrated.selfPeer?.primaryIpv4)
        assertEquals(1, hydrated.peers.size)
        // Cached peers start offline/inactive until live sync confirms reachability
        assertFalse(hydrated.peers[0].online)
        assertFalse(hydrated.peers[0].active)
        assertEquals(TransportPathType.DIRECT, hydrated.peers[0].effectivePathType)
        // v2 & v3 threshold migrations still apply cleanly
        assertEquals(emptyList<String>(), hydrated.thresholds.customDirectDomains)
        assertEquals(emptyList<String>(), hydrated.thresholds.customProxyDomains)
        assertEquals(220, hydrated.thresholds.directAcceptableLatencyMs)
        assertEquals(45, hydrated.thresholds.probeIntervalSec)
        assertTrue(hydrated.thresholds.allowTailnetInboundLocalForward)
    }
}
