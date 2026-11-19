package route

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/sagernet/sing-box/adapter"
	C "github.com/sagernet/sing-box/constant"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

const (
	defaultMaxDirectRttMs     = 220
	defaultProbeTimeoutMs     = 1050
	directGoodCacheTTL        = 30 * time.Minute
	directPoorCacheTTL        = 5 * time.Minute
	maxDirectVerdictEntries   = 2048
	maxConcurrentDirectProbes = 8
)

type directVerdictEntry struct {
	useDirect bool
	rttMs     int64
	ruleDesc  string
	createdAt time.Time
	expiresAt time.Time
}

type inflightDirectProbe struct {
	done   chan struct{}
	result directVerdictEntry
}

type singScaleDirectFirstManager struct {
	enabled        atomic.Bool
	maxDirectRttMs atomic.Int64
	probeTimeoutMs atomic.Int64

	probeSem chan struct{}
	mu       sync.Mutex
	cache    map[string]directVerdictEntry
	inflight map[string]*inflightDirectProbe
}

var singScaleDirectFirstState = func() *singScaleDirectFirstManager {
	m := &singScaleDirectFirstManager{
		probeSem: make(chan struct{}, maxConcurrentDirectProbes),
		cache:    make(map[string]directVerdictEntry, 128),
		inflight: make(map[string]*inflightDirectProbe, 16),
	}
	m.enabled.Store(true)
	m.maxDirectRttMs.Store(defaultMaxDirectRttMs)
	m.probeTimeoutMs.Store(defaultProbeTimeoutMs)
	return m
}()

func SingScaleConfigureDirectFirst(enabled bool, maxDirectRttMs int32, probeTimeoutMs int32, clearCache bool) {
	if maxDirectRttMs < 60 {
		maxDirectRttMs = defaultMaxDirectRttMs
	}
	if probeTimeoutMs < 350 {
		probeTimeoutMs = defaultProbeTimeoutMs
	}
	prevMax := singScaleDirectFirstState.maxDirectRttMs.Swap(int64(maxDirectRttMs))
	singScaleDirectFirstState.probeTimeoutMs.Store(int64(probeTimeoutMs))
	prevEnabled := singScaleDirectFirstState.enabled.Swap(enabled)
	if clearCache || prevEnabled != enabled || prevMax != int64(maxDirectRttMs) {
		SingScaleClearDirectFirstCache()
	}
}

func SingScaleClearDirectFirstCache() {
	singScaleDirectFirstState.mu.Lock()
	clear(singScaleDirectFirstState.cache)
	singScaleDirectFirstState.mu.Unlock()
}

func (m *singScaleDirectFirstManager) isEnabled() bool {
	return m.enabled.Load()
}

func resolveLeafOutboundTag(ob adapter.Outbound, network string) string {
	cur := ob
	for i := 0; i < 8 && cur != nil; i++ {
		grp, isGrp := cur.(adapter.OutboundGroup)
		if !isGrp {
			return cur.Tag()
		}
		cur = grp.Selected(network)
	}
	if cur != nil {
		return cur.Tag()
	}
	return ""
}

func (r *Router) singScaleDecideUnmatchedOutbound(
	ctx context.Context,
	network string,
	metadata *adapter.InboundContext,
) (adapter.Outbound, string) {
	defaultOutbound := r.outbound.Default()
	if defaultOutbound == nil || !singScaleDirectFirstState.isEnabled() || r.ctx.Err() != nil || ctx.Err() != nil {
		return defaultOutbound, ""
	}

	leafTag := resolveLeafOutboundTag(defaultOutbound, network)
	if leafTag == "" || strings.EqualFold(leafTag, "direct") {
		return defaultOutbound, ""
	}

	directOutbound, ok := r.outbound.Outbound("direct")
	if !ok || directOutbound == nil {
		return defaultOutbound, ""
	}

	domain := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(metadata.Domain), "."))
	dest := metadata.Destination
	port := dest.Port
	if port == 0 {
		port = 443
	}

	// Non-QUIC raw UDP (WebRTC / STUN / voice / gaming without domain on non-HTTPS ports):
	// Prefer local direct path immediately for lowest jitter.
	if network == N.NetworkUDP && domain == "" && port != 443 && port != 8443 {
		return directOutbound, "direct-first(UDP实时直连) => direct"
	}

	var cacheKey string
	if domain != "" {
		cacheKey = domain + ":" + strconv.Itoa(int(port))
	} else if dest.IsValid() {
		cacheKey = dest.AddrString() + ":" + strconv.Itoa(int(port))
	} else {
		return defaultOutbound, ""
	}

	now := time.Now()
	singScaleDirectFirstState.mu.Lock()
	if cached, found := singScaleDirectFirstState.cache[cacheKey]; found && now.Before(cached.expiresAt) {
		singScaleDirectFirstState.mu.Unlock()
		if cached.useDirect {
			return directOutbound, cached.ruleDesc
		}
		rewriteProxyDestinationToDomain(metadata, leafTag, domain, port)
		return defaultOutbound, cached.ruleDesc
	}

	if flight, running := singScaleDirectFirstState.inflight[cacheKey]; running {
		singScaleDirectFirstState.mu.Unlock()
		select {
		case <-flight.done:
			if flight.result.useDirect {
				return directOutbound, flight.result.ruleDesc
			}
			rewriteProxyDestinationToDomain(metadata, leafTag, domain, port)
			return defaultOutbound, flight.result.ruleDesc
		case <-ctx.Done():
			return defaultOutbound, ""
		}
	}

	flight := &inflightDirectProbe{done: make(chan struct{})}
	singScaleDirectFirstState.inflight[cacheKey] = flight
	singScaleDirectFirstState.mu.Unlock()

	var verdict directVerdictEntry
	func() {
		defer func() {
			if rec := recover(); rec != nil {
				now := time.Now()
				verdict = directVerdictEntry{
					useDirect: false,
					rttMs:     -1,
					ruleDesc:  "direct-poor(探测异常) => relay",
					createdAt: now,
					expiresAt: now.Add(directPoorCacheTTL),
				}
			}
			singScaleDirectFirstState.mu.Lock()
			if len(singScaleDirectFirstState.cache) >= maxDirectVerdictEntries {
				evictExpiredOrOldestLocked(singScaleDirectFirstState.cache, time.Now())
			}
			singScaleDirectFirstState.cache[cacheKey] = verdict
			delete(singScaleDirectFirstState.inflight, cacheKey)
			flight.result = verdict
			close(flight.done)
			singScaleDirectFirstState.mu.Unlock()
		}()
		verdict = r.probeDirectTargetQuality(directOutbound, domain, dest, port, metadata.Protocol)
	}()

	if verdict.useDirect {
		return directOutbound, verdict.ruleDesc
	}
	rewriteProxyDestinationToDomain(metadata, leafTag, domain, port)
	return defaultOutbound, verdict.ruleDesc
}

func rewriteProxyDestinationToDomain(metadata *adapter.InboundContext, leafTag string, domain string, port uint16) {
	if domain != "" && (leafTag == "peer-exit" || leafTag == "peer-relay") && M.IsDomainName(domain) {
		metadata.Destination = M.Socksaddr{
			Fqdn: domain,
			Port: port,
		}
	}
}

func (r *Router) probeDirectTargetQuality(
	directOutbound adapter.Outbound,
	domain string,
	dest M.Socksaddr,
	port uint16,
	sniffedProto string,
) directVerdictEntry {
	maxRttMs := singScaleDirectFirstState.maxDirectRttMs.Load()
	timeoutMs := singScaleDirectFirstState.probeTimeoutMs.Load()
	if minBudget := maxRttMs*3 + 220; timeoutMs > minBudget && minBudget >= 550 {
		timeoutMs = minBudget
	}

	dialDest := dest
	if !dialDest.IsValid() && domain != "" {
		dialDest = M.ParseSocksaddrHostPort(domain, port)
	} else if dialDest.Port == 0 {
		dialDest.Port = port
	}

	probeCtx, cancel := context.WithTimeout(r.ctx, time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	// Cap concurrent direct TCP+TLS probes so burst app traffic never triggers cgo protect(fd) thread storms
	select {
	case singScaleDirectFirstState.probeSem <- struct{}{}:
		defer func() { <-singScaleDirectFirstState.probeSem }()
	case <-probeCtx.Done():
		now := time.Now()
		return directVerdictEntry{
			useDirect: false,
			rttMs:     -1,
			ruleDesc:  "direct-poor(探测繁忙超时) => relay",
			createdAt: now,
			expiresAt: now.Add(directPoorCacheTTL),
		}
	}

	t0 := time.Now()
	rawConn, err := directOutbound.DialContext(probeCtx, N.NetworkTCP, dialDest)
	tcpMs := time.Since(t0).Milliseconds()
	if err != nil {
		reason := summarizeDirectProbeError(err)
		now := time.Now()
		return directVerdictEntry{
			useDirect: false,
			rttMs:     -1,
			ruleDesc:  fmt.Sprintf("direct-poor(直连%s) => relay", reason),
			createdAt: now,
			expiresAt: now.Add(directPoorCacheTTL),
		}
	}

	needTLS := domain != "" && (sniffedProto == C.ProtocolTLS || sniffedProto == C.ProtocolQUIC || port == 443 || port == 8443)
	effectiveRttMs := tcpMs

	if needTLS {
		if deadline, ok := probeCtx.Deadline(); ok {
			_ = rawConn.SetDeadline(deadline)
		}
		tlsConn := tls.Client(rawConn, &tls.Config{
			ServerName:         domain,
			InsecureSkipVerify: true,
			NextProtos:         []string{"h2", "http/1.1"},
		})
		tTls := time.Now()
		tlsErr := tlsConn.HandshakeContext(probeCtx)
		tlsMs := time.Since(tTls).Milliseconds()
		_ = tlsConn.Close()
		_ = rawConn.Close()

		if tlsErr != nil {
			reason := summarizeDirectProbeError(tlsErr)
			now := time.Now()
			return directVerdictEntry{
				useDirect: false,
				rttMs:     -1,
				ruleDesc:  fmt.Sprintf("direct-poor(TLS%s) => relay", reason),
				createdAt: now,
				expiresAt: now.Add(directPoorCacheTTL),
			}
		}
		if halfTls := tlsMs / 2; halfTls > effectiveRttMs {
			effectiveRttMs = halfTls
		}
	} else {
		_ = rawConn.Close()
	}

	now := time.Now()
	if effectiveRttMs <= maxRttMs {
		return directVerdictEntry{
			useDirect: true,
			rttMs:     effectiveRttMs,
			ruleDesc:  fmt.Sprintf("direct-first(直连 %dms) => direct", effectiveRttMs),
			createdAt: now,
			expiresAt: now.Add(directGoodCacheTTL),
		}
	}

	return directVerdictEntry{
		useDirect: false,
		rttMs:     effectiveRttMs,
		ruleDesc:  fmt.Sprintf("direct-poor(直连偏慢 %dms>%dms) => relay", effectiveRttMs, maxRttMs),
		createdAt: now,
		expiresAt: now.Add(directPoorCacheTTL),
	}
}

func summarizeDirectProbeError(err error) string {
	if err == nil {
		return "未知异常"
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return "超时"
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "超时"
	}
	if errors.Is(err, syscall.ECONNRESET) {
		return "被重置"
	}
	if errors.Is(err, syscall.ECONNREFUSED) {
		return "被拒绝"
	}
	if errors.Is(err, syscall.ENETUNREACH) || errors.Is(err, syscall.EHOSTUNREACH) {
		return "不可达"
	}
	lower := strings.ToLower(err.Error())
	switch {
	case strings.Contains(lower, "reset"):
		return "被重置"
	case strings.Contains(lower, "timeout") || strings.Contains(lower, "deadline"):
		return "超时"
	case strings.Contains(lower, "eof"):
		return "握手中断"
	case strings.Contains(lower, "refused"):
		return "被拒绝"
	default:
		return "受阻"
	}
}

func evictExpiredOrOldestLocked(m map[string]directVerdictEntry, now time.Time) {
	var oldestKey string
	var oldestTime time.Time
	evicted := 0
	for k, v := range m {
		if now.After(v.expiresAt) {
			delete(m, k)
			evicted++
			continue
		}
		if oldestKey == "" || v.createdAt.Before(oldestTime) {
			oldestKey = k
			oldestTime = v.createdAt
		}
	}
	if evicted == 0 && oldestKey != "" {
		delete(m, oldestKey)
	}
}
