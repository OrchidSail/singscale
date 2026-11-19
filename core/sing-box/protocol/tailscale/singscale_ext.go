//go:build with_gvisor

package tailscale

import (
	"context"
	"encoding/json"
	"math"
	"net/netip"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/ipn/ipnlocal"
	"github.com/sagernet/tailscale/ipn/ipnstate"
	"github.com/sagernet/tailscale/net/tsaddr"
	"github.com/sagernet/tailscale/tailcfg"
)

var _ adapter.SingScaleTailscaleEndpoint = (*Endpoint)(nil)

type singScaleSnapshot struct {
	EndpointTag    string               `json:"endpointTag"`
	BackendState   string               `json:"backendState"`
	AuthURL        string               `json:"authUrl"`
	HasSavedLogin  bool                 `json:"hasSavedLogin"`
	NetworkName    string               `json:"networkName"`
	MagicDNSSuffix string               `json:"magicDnsSuffix"`
	CertDomains    []string             `json:"certDomains,omitempty"`
	KeyAuth        bool                 `json:"keyAuth"`
	ExitNodeID     string               `json:"exitNodeId,omitempty"`
	Self           *singScalePeerEntry  `json:"self,omitempty"`
	Peers          []singScalePeerEntry `json:"peers"`
}

func (t *Endpoint) safeLocalBackend() *ipnlocal.LocalBackend {
	if t == nil || !t.started.Load() || t.server == nil {
		return nil
	}
	return t.server.ExportLocalBackend()
}

func (t *Endpoint) hasSavedCredentials() bool {
	localBackend := t.safeLocalBackend()
	if localBackend == nil {
		return false
	}
	if localBackend.NodeKey().IsZero() {
		return false
	}
	prefs := localBackend.Prefs()
	if prefs.Valid() && prefs.LoggedOut() {
		return false
	}
	return true
}

type singScalePeerEntry struct {
	StableID        string   `json:"stableId"`
	HostName        string   `json:"hostName"`
	DNSName         string   `json:"dnsName"`
	UserID          int64    `json:"userId"`
	UserLogin       string   `json:"userLogin"`
	UserDisplayName string   `json:"userDisplayName"`
	OS              string   `json:"os"`
	TailscaleIPs    []string `json:"tailscaleIPs"`
	Online          bool     `json:"online"`
	Active          bool     `json:"active"`
	Expired         bool     `json:"expired"`
	ExitNode        bool     `json:"exitNode"`
	ExitNodeOption  bool     `json:"exitNodeOption"`
	CurAddr         string   `json:"curAddr"`
	PeerRelay       string   `json:"peerRelay"`
	DERPRegion      string   `json:"derpRegion"`
	PathType        string   `json:"pathType,omitempty"`
	Addrs           []string `json:"addrs,omitempty"`
	PrimaryRoutes   []string `json:"primaryRoutes,omitempty"`
	AllowedIPs      []string `json:"allowedIPs,omitempty"`
	HasPeerRelayCap bool     `json:"hasPeerRelayCap"`
	RxBytes         int64    `json:"rxBytes"`
	TxBytes         int64    `json:"txBytes"`
	LastSeen        int64    `json:"lastSeen"`
	LastHandshake   int64    `json:"lastHandshake"`
}

type singScaleProbeSummary struct {
	PeerIP         string    `json:"peerIp"`
	Reachable      bool      `json:"reachable"`
	LatencyMs      float64   `json:"latencyMs"`
	MinLatencyMs   float64   `json:"minLatencyMs"`
	JitterMs       float64   `json:"jitterMs"`
	LossRate       float64   `json:"lossRate"`
	PathType       string    `json:"pathType"`
	IsDirect       bool      `json:"isDirect"`
	Endpoint       string    `json:"endpoint"`
	PeerRelay      string    `json:"peerRelay"`
	DERPRegionID   int32     `json:"derpRegionId"`
	DERPRegionCode string    `json:"derpRegionCode"`
	QualityScore   int       `json:"qualityScore"`
	Samples        []float64 `json:"samples,omitempty"`
	Error          string    `json:"error,omitempty"`
}

type singScaleGatewayPort struct {
	Port        int     `json:"port"`
	Protocol    string  `json:"protocol"`
	HandshakeMs float64 `json:"handshakeMs"`
}

type singScaleGatewayProbeResponse struct {
	PeerIP         string                 `json:"peerIp"`
	BestProtocol   string                 `json:"bestProtocol"`
	BestPort       int                    `json:"bestPort"`
	BestLatencyMs  float64                `json:"bestLatencyMs"`
	ReachablePorts []singScaleGatewayPort `json:"reachablePorts"`
}

func (t *Endpoint) SingScaleGetSnapshotJSON(ctx context.Context) (string, error) {
	localBackend := t.safeLocalBackend()
	if localBackend == nil {
		return "", E.New("Tailscale endpoint is starting")
	}
	status := localBackend.Status()
	if status == nil {
		return "", E.New("Tailscale status unavailable")
	}

	hasSaved := t.hasSavedCredentials()
	if hasSaved && status.BackendState == ipn.Stopped.String() {
		// Resume saved authenticated session with LoginDefault (never regenerating NodeKey).
		_, _ = localBackend.EditPrefs(&ipn.MaskedPrefs{
			Prefs: ipn.Prefs{
				WantRunning: true,
			},
			WantRunningSet: true,
		})
	}

	snap := singScaleSnapshot{
		EndpointTag:   t.Tag(),
		BackendState:  status.BackendState,
		AuthURL:       status.AuthURL,
		HasSavedLogin: hasSaved,
		KeyAuth:       t.keyAuth,
		CertDomains:   t.server.CertDomains(),
		Peers:         make([]singScalePeerEntry, 0, len(status.Peer)),
	}
	if status.CurrentTailnet != nil {
		snap.NetworkName = status.CurrentTailnet.Name
		snap.MagicDNSSuffix = status.CurrentTailnet.MagicDNSSuffix
	}
	if status.Self != nil {
		selfEntry := convertDetailedPeer(status.Self, status.User)
		snap.Self = &selfEntry
	}
	for _, key := range status.Peers() {
		peer := status.Peer[key]
		if peer == nil {
			continue
		}
		entry := convertDetailedPeer(peer, status.User)
		if entry.ExitNode && snap.ExitNodeID == "" {
			snap.ExitNodeID = entry.StableID
		}
		snap.Peers = append(snap.Peers, entry)
	}
	if snap.ExitNodeID == "" && status.ExitNodeStatus != nil {
		snap.ExitNodeID = string(status.ExitNodeStatus.ID)
	}
	slices.SortStableFunc(snap.Peers, func(a, b singScalePeerEntry) int {
		if a.Online != b.Online {
			if a.Online {
				return -1
			}
			return 1
		}
		return strings.Compare(strings.ToLower(a.HostName), strings.ToLower(b.HostName))
	})

	encoded, err := json.Marshal(snap)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func convertDetailedPeer(peer *ipnstate.PeerStatus, users map[tailcfg.UserID]tailcfg.UserProfile) singScalePeerEntry {
	ips := make([]string, 0, len(peer.TailscaleIPs))
	for _, ip := range peer.TailscaleIPs {
		ips = append(ips, ip.String())
	}
	var primaryRoutes []string
	if peer.PrimaryRoutes != nil {
		for _, pfx := range peer.PrimaryRoutes.AsSlice() {
			primaryRoutes = append(primaryRoutes, pfx.String())
		}
	}
	var allowedIPs []string
	if peer.AllowedIPs != nil {
		for _, pfx := range peer.AllowedIPs.AsSlice() {
			allowedIPs = append(allowedIPs, pfx.String())
		}
	}
	hasRelayCap := peer.PeerRelay != ""
	if !hasRelayCap && len(peer.CapMap) > 0 {
		for capKey := range peer.CapMap {
			k := strings.ToLower(string(capKey))
			if strings.Contains(k, "relay") {
				hasRelayCap = true
				break
			}
		}
	}
	var userLogin, userDisplay string
	if profile, ok := users[peer.UserID]; ok {
		userLogin = profile.LoginName
		userDisplay = profile.DisplayName
	}
	var lastSeen, lastHandshake int64
	if !peer.LastSeen.IsZero() {
		lastSeen = peer.LastSeen.Unix()
	}
	if !peer.LastHandshake.IsZero() {
		lastHandshake = peer.LastHandshake.Unix()
	}
	pathType := classifyPeerTransportPath(peer)
	return singScalePeerEntry{
		StableID:        string(peer.ID),
		HostName:        peer.HostName,
		DNSName:         peer.DNSName,
		UserID:          int64(peer.UserID),
		UserLogin:       userLogin,
		UserDisplayName: userDisplay,
		OS:              peer.OS,
		TailscaleIPs:    ips,
		Online:          peer.Online,
		Active:          peer.Active,
		Expired:         peer.Expired,
		ExitNode:        peer.ExitNode,
		ExitNodeOption:  peer.ExitNodeOption,
		CurAddr:         peer.CurAddr,
		PeerRelay:       peer.PeerRelay,
		DERPRegion:      peer.Relay,
		PathType:        pathType,
		Addrs:           peer.Addrs,
		PrimaryRoutes:   primaryRoutes,
		AllowedIPs:      allowedIPs,
		HasPeerRelayCap: hasRelayCap,
		RxBytes:         peer.RxBytes,
		TxBytes:         peer.TxBytes,
		LastSeen:        lastSeen,
		LastHandshake:   lastHandshake,
	}
}

func classifyPeerTransportPath(peer *ipnstate.PeerStatus) string {
	if peer == nil {
		return "OFFLINE"
	}
	switch {
	case strings.TrimSpace(peer.CurAddr) != "":
		return "DIRECT"
	case strings.TrimSpace(peer.PeerRelay) != "":
		return "PEER_RELAY"
	case strings.TrimSpace(peer.Relay) != "" && peer.Online:
		return "DERP"
	default:
		return "OFFLINE"
	}
}

func (t *Endpoint) SingScaleStartInteractiveLogin(ctx context.Context) (string, error) {
	localBackend := t.safeLocalBackend()
	if localBackend == nil {
		return "", E.New("Tailscale endpoint is starting")
	}
	status := localBackend.StatusWithoutPeers()
	if status != nil && status.BackendState == ipn.Running.String() && status.Self != nil && len(status.Self.TailscaleIPs) > 0 {
		return "Running", nil
	}
	if status != nil && status.AuthURL != "" {
		return status.AuthURL, nil
	}

	// CRITICAL PERSISTENCE GUARD:
	// In Tailscale controlclient, StartLoginInteractive sets LoginInteractive -> regen=true,
	// which unconditionally generates a brand-new unauthenticated NodeKey and wipes any saved session!
	// When a valid PrivateNodeKey already exists in tailscaled.state and BackendState != NeedsLogin,
	// resume the saved session via WantRunning=true (LoginDefault, regen=false) instead of regenerating!
	if t.hasSavedCredentials() && (status == nil || status.BackendState != ipn.NeedsLogin.String()) {
		_, _ = localBackend.EditPrefs(&ipn.MaskedPrefs{
			Prefs: ipn.Prefs{
				WantRunning: true,
			},
			WantRunningSet: true,
		})
		resumeDeadline := time.Now().Add(6 * time.Second)
		for time.Now().Before(resumeDeadline) {
			st := localBackend.StatusWithoutPeers()
			if st != nil {
				if st.BackendState == ipn.Running.String() && st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
					return "Running", nil
				}
				if st.AuthURL != "" {
					return st.AuthURL, nil
				}
				if st.BackendState == ipn.NeedsLogin.String() {
					break
				}
			}
			select {
			case <-ctx.Done():
				return "", ctx.Err()
			case <-time.After(150 * time.Millisecond):
			}
		}
		latest := localBackend.StatusWithoutPeers()
		if t.hasSavedCredentials() && latest != nil && latest.BackendState != ipn.NeedsLogin.String() {
			if latest.AuthURL != "" {
				return latest.AuthURL, nil
			}
			return "", nil
		}
	}

	_, _ = localBackend.EditPrefs(&ipn.MaskedPrefs{
		Prefs: ipn.Prefs{
			WantRunning: true,
			LoggedOut:   false,
		},
		WantRunningSet: true,
		LoggedOutSet:   true,
	})
	if err := localBackend.StartLoginInteractive(ctx); err != nil {
		return "", E.Cause(err, "start interactive login")
	}
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		st := localBackend.StatusWithoutPeers()
		if st != nil {
			if st.BackendState == ipn.Running.String() && st.Self != nil && len(st.Self.TailscaleIPs) > 0 {
				return "Running", nil
			}
			if st.AuthURL != "" {
				return st.AuthURL, nil
			}
		}
		select {
		case <-ctx.Done():
			return "", ctx.Err()
		case <-time.After(120 * time.Millisecond):
		}
	}
	st := localBackend.StatusWithoutPeers()
	if st != nil && st.AuthURL != "" {
		return st.AuthURL, nil
	}
	return "", nil
}

func (t *Endpoint) SingScaleApplyPrefs(
	ctx context.Context,
	exitNodeIDOrIP string,
	allowLanAccess bool,
	acceptRoutes bool,
	relayServerPort int32,
	staticRelayEndpointsCSV string,
) error {
	localBackend := t.safeLocalBackend()
	if localBackend == nil {
		return E.New("Tailscale is not ready yet")
	}
	t.exitNodeAllowLANAccess = allowLanAccess
	t.acceptRoutes = acceptRoutes
	masked := &ipn.MaskedPrefs{
		Prefs: ipn.Prefs{
			RouteAll:               acceptRoutes,
			ExitNodeAllowLANAccess: allowLanAccess,
			AdvertiseRoutes:        t.advertiseRoutes,
		},
		RouteAllSet:               true,
		ExitNodeAllowLANAccessSet: true,
		ExitNodeIDSet:             true,
		ExitNodeIPSet:             true,
	}
	if t.hasSavedCredentials() {
		curPrefs := localBackend.Prefs()
		if !curPrefs.Valid() || !curPrefs.WantRunning() {
			masked.Prefs.WantRunning = true
			masked.WantRunningSet = true
		}
	}
	if t.advertiseExitNode && exitNodeIDOrIP == "" {
		masked.Prefs.AdvertiseRoutes = append(masked.Prefs.AdvertiseRoutes, tsaddr.ExitRoutes()...)
		masked.AdvertiseRoutesSet = true
	}

	if relayServerPort > 0 && relayServerPort <= 65535 {
		p := uint16(relayServerPort)
		t.relayServerPort = &p
		masked.Prefs.RelayServerPort = &p
		masked.RelayServerPortSet = true
	} else if relayServerPort == 0 {
		t.relayServerPort = nil
		masked.Prefs.RelayServerPort = nil
		masked.RelayServerPortSet = true
	}

	if staticRelayEndpointsCSV != "" {
		var eps []netip.AddrPort
		for _, part := range strings.Split(staticRelayEndpointsCSV, ",") {
			part = strings.TrimSpace(part)
			if part == "" {
				continue
			}
			if ap, err := netip.ParseAddrPort(part); err == nil {
				eps = append(eps, ap)
			}
		}
		t.relayServerStaticEndpoints = eps
		masked.Prefs.RelayServerStaticEndpoints = eps
		masked.RelayServerStaticEndpointsSet = true
	} else {
		t.relayServerStaticEndpoints = nil
		masked.Prefs.RelayServerStaticEndpoints = nil
		masked.RelayServerStaticEndpointsSet = true
	}

	trimmedExit := strings.TrimSpace(exitNodeIDOrIP)
	if trimmedExit != "" {
		status := localBackend.Status()
		matchedByID := false
		if status != nil {
			for _, peer := range status.Peer {
				if peer == nil {
					continue
				}
				if string(peer.ID) == trimmedExit {
					masked.Prefs.ExitNodeID = peer.ID
					matchedByID = true
					break
				}
			}
			if !matchedByID {
				if err := masked.Prefs.SetExitNodeIP(trimmedExit, status); err == nil {
					matchedByID = true
				}
			}
		}
		if !matchedByID {
			if addr, err := netip.ParseAddr(trimmedExit); err == nil {
				masked.Prefs.ExitNodeIP = addr
			} else {
				masked.Prefs.ExitNodeID = tailcfg.StableNodeID(trimmedExit)
			}
		}
	}

	_, err := localBackend.EditPrefs(masked)
	if err != nil {
		return E.Cause(err, "update tailscale prefs")
	}
	return nil
}

func (t *Endpoint) SingScaleBatchProbePeersJSON(
	ctx context.Context,
	peerIPsCSV string,
	samples int32,
	timeoutMs int32,
) (string, error) {
	if !t.started.Load() || t.server == nil {
		return "[]", E.New("Tailscale is not ready yet")
	}
	localClient, err := t.server.LocalClient()
	if err != nil || localClient == nil {
		return "[]", err
	}
	if samples <= 0 {
		samples = 2
	}
	if samples > 5 {
		samples = 5
	}
	if timeoutMs <= 0 {
		timeoutMs = 1500
	}
	perSampleTimeout := time.Duration(timeoutMs) * time.Millisecond

	var ips []netip.Addr
	seen := make(map[netip.Addr]bool)
	for _, raw := range strings.Split(peerIPsCSV, ",") {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		if addr, parseErr := netip.ParseAddr(raw); parseErr == nil && !seen[addr] {
			seen[addr] = true
			ips = append(ips, addr)
		}
	}

	results := make([]singScaleProbeSummary, len(ips))
	var wg sync.WaitGroup
	for idx, ip := range ips {
		wg.Add(1)
		go func(i int, target netip.Addr) {
			defer func() {
				if rec := recover(); rec != nil {
					results[i] = singScaleProbeSummary{
						PeerIP:   target.String(),
						PathType: "OFFLINE",
					}
				}
				wg.Done()
			}()
			summary := singScaleProbeSummary{
				PeerIP:   target.String(),
				PathType: "OFFLINE",
			}
			var rtts []float64
			var lastErr string
			for s := int32(0); s < samples; s++ {
				pingCtx, cancel := context.WithTimeout(ctx, perSampleTimeout)
				res, pingErr := localClient.Ping(pingCtx, target, tailcfg.PingDisco)
				cancel()
				if pingErr != nil {
					lastErr = pingErr.Error()
					continue
				}
				if res == nil {
					continue
				}
				if res.Err != "" {
					lastErr = res.Err
					continue
				}
				ms := res.LatencySeconds * 1000.0
				if ms <= 0 {
					ms = 0.5
				}
				rtts = append(rtts, ms)
				summary.IsDirect = res.Endpoint != ""
				summary.Endpoint = res.Endpoint
				summary.PeerRelay = res.PeerRelay
				summary.DERPRegionID = int32(res.DERPRegionID)
				summary.DERPRegionCode = res.DERPRegionCode
			}

			total := float64(samples)
			failed := float64(int(samples) - len(rtts))
			summary.LossRate = failed / total
			if len(rtts) == 0 {
				summary.Reachable = false
				summary.Error = lastErr
				summary.QualityScore = 0
				results[i] = summary
				return
			}

			summary.Reachable = true
			summary.Samples = rtts
			sum := 0.0
			minRTT := rtts[0]
			for _, v := range rtts {
				sum += v
				if v < minRTT {
					minRTT = v
				}
			}
			mean := sum / float64(len(rtts))
			variance := 0.0
			for _, v := range rtts {
				diff := v - mean
				variance += diff * diff
			}
			jitter := math.Sqrt(variance / float64(len(rtts)))

			summary.LatencyMs = math.Round(mean*10) / 10
			summary.MinLatencyMs = math.Round(minRTT*10) / 10
			summary.JitterMs = math.Round(jitter*10) / 10

			switch {
			case summary.Endpoint != "":
				summary.PathType = "DIRECT"
			case summary.PeerRelay != "":
				summary.PathType = "PEER_RELAY"
			default:
				summary.PathType = "DERP"
			}
			summary.QualityScore = computeLinkQualityScore(
				summary.LatencyMs,
				summary.JitterMs,
				summary.LossRate,
				summary.PathType,
			)
			results[i] = summary
		}(idx, ip)
	}
	wg.Wait()

	encoded, err := json.Marshal(results)
	if err != nil {
		return "[]", err
	}
	return string(encoded), nil
}

func computeLinkQualityScore(latencyMs, jitterMs, lossRate float64, pathType string) int {
	score := 100.0
	// Latency penalty: gentle below 40ms, steeper above 100ms
	if latencyMs > 15 {
		score -= math.Min(48.0, (latencyMs-15.0)*0.22)
	}
	// Jitter penalty
	score -= math.Min(18.0, jitterMs*0.45)
	// Loss penalty
	score -= lossRate * 45.0
	// Transport path adjustment
	switch pathType {
	case "DIRECT":
		score += 4.0
	case "PEER_RELAY":
		score -= 4.0
	case "DERP":
		score -= 14.0
	default:
		return 0
	}
	clamped := int(math.Round(score))
	if clamped > 100 {
		return 100
	}
	if clamped < 1 {
		return 1
	}
	return clamped
}

func (t *Endpoint) SingScaleProbePeerGatewaysJSON(
	ctx context.Context,
	peerIP string,
	portsCSV string,
	timeoutMs int32,
) (string, error) {
	if !t.started.Load() || t.server == nil {
		return "", E.New("Tailscale is not ready yet")
	}
	if timeoutMs <= 0 {
		timeoutMs = 1200
	}
	if strings.TrimSpace(portsCSV) == "" {
		portsCSV = "1080,2080,7890,7891,10808,10809,8080,8888"
	}
	var ports []int
	seen := make(map[int]bool)
	for _, raw := range strings.Split(portsCSV, ",") {
		p, err := strconv.Atoi(strings.TrimSpace(raw))
		if err == nil && p > 0 && p <= 65535 && !seen[p] {
			seen[p] = true
			ports = append(ports, p)
		}
	}

	var mu sync.Mutex
	var reachable []singScaleGatewayPort
	var wg sync.WaitGroup
	sem := make(chan struct{}, 6)

	for _, port := range ports {
		wg.Add(1)
		go func(targetPort int) {
			defer func() {
				_ = recover()
				wg.Done()
			}()
			select {
			case sem <- struct{}{}:
				defer func() { <-sem }()
			case <-ctx.Done():
				return
			}
			dialCtx, cancel := context.WithTimeout(ctx, time.Duration(timeoutMs)*time.Millisecond)
			defer cancel()

			start := time.Now()
			dest := M.ParseSocksaddrHostPort(peerIP, uint16(targetPort))
			conn, err := t.DialContext(dialCtx, "tcp", dest)
			if err != nil {
				return
			}
			defer conn.Close()
			_ = conn.SetDeadline(time.Now().Add(800 * time.Millisecond))

			// Try SOCKS5 greeting: 0x05 0x01 0x00
			_, writeErr := conn.Write([]byte{0x05, 0x01, 0x00})
			buf := make([]byte, 4)
			if writeErr == nil {
				n, readErr := conn.Read(buf)
				if readErr == nil && n >= 2 && buf[0] == 0x05 {
					elapsed := float64(time.Since(start).Microseconds()) / 1000.0
					mu.Lock()
					reachable = append(reachable, singScaleGatewayPort{
						Port:        targetPort,
						Protocol:    "socks",
						HandshakeMs: math.Round(elapsed*10) / 10,
					})
					mu.Unlock()
					return
				}
			}

			// Re-dial and probe HTTP proxy response
			conn2, err2 := t.DialContext(dialCtx, "tcp", dest)
			if err2 != nil {
				return
			}
			defer conn2.Close()
			_ = conn2.SetDeadline(time.Now().Add(800 * time.Millisecond))
			_, _ = conn2.Write([]byte("HEAD http://cp.cloudflare.com/ HTTP/1.1\r\nHost: cp.cloudflare.com\r\nConnection: close\r\n\r\n"))
			httpBuf := make([]byte, 12)
			n2, readErr2 := conn2.Read(httpBuf)
			if readErr2 == nil && n2 >= 4 && strings.HasPrefix(string(httpBuf[:n2]), "HTTP") {
				elapsed := float64(time.Since(start).Microseconds()) / 1000.0
				mu.Lock()
				reachable = append(reachable, singScaleGatewayPort{
					Port:        targetPort,
					Protocol:    "http",
					HandshakeMs: math.Round(elapsed*10) / 10,
				})
				mu.Unlock()
			}
		}(port)
	}
	wg.Wait()

	slices.SortStableFunc(reachable, func(a, b singScaleGatewayPort) int {
		if a.Protocol != b.Protocol {
			if a.Protocol == "socks" {
				return -1
			}
			return 1
		}
		if a.HandshakeMs < b.HandshakeMs {
			return -1
		}
		if a.HandshakeMs > b.HandshakeMs {
			return 1
		}
		return 0
	})

	resp := singScaleGatewayProbeResponse{
		PeerIP:         peerIP,
		ReachablePorts: reachable,
	}
	if len(reachable) > 0 {
		resp.BestProtocol = reachable[0].Protocol
		resp.BestPort = reachable[0].Port
		resp.BestLatencyMs = reachable[0].HandshakeMs
	}
	encoded, err := json.Marshal(resp)
	if err != nil {
		return "", err
	}
	return string(encoded), nil
}

func (t *Endpoint) SingScaleSetPowerMode(sleeping bool, networkUp bool) {
	localBackend := t.safeLocalBackend()
	if localBackend == nil {
		return
	}
	mc := localBackend.MagicConn()
	if mc == nil {
		return
	}
	mc.SetProbeUDPLifetime(false)
	if C.IsAndroid || C.IsIos || sleeping {
		mc.SetSilentDisco(true)
	}
	mc.SetNetworkUp(networkUp)
}

func (t *Endpoint) SingScaleSetInboundLocalForward(enabled bool) {
	if t == nil {
		return
	}
	t.allowLocalPortForward.Store(enabled)
	if fwd := t.inboundForwarder.Load(); fwd != nil {
		fwd.SetEnabled(enabled)
	}
}
