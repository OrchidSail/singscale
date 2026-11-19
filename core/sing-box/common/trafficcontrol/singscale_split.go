package trafficcontrol

import (
	"net/netip"
	"strings"

	"github.com/gofrs/uuid/v5"
)

// SingScaleTrafficBucket represents the three mutually-exclusive traffic categories
// tracked by SingScale so Local Direct, Tailnet Mesh Direct, and Exit/Relay egress
// are always accounted separately with strict sum conservation.
type SingScaleTrafficBucket int

const (
	SingScaleBucketDirect SingScaleTrafficBucket = iota
	SingScaleBucketMesh
	SingScaleBucketRelay
)

const singScaleBucketCount = 3

var (
	singScaleCGNATPrefix = netip.MustParsePrefix("100.64.0.0/10")
	singScaleULAPrefix   = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
)

type singScaleEvictedBucket struct {
	uplinkBytes   int64
	downlinkBytes int64
}

type SingScaleBucketStats struct {
	UplinkBytes       int64 `json:"uplinkBytes"`
	DownlinkBytes     int64 `json:"downlinkBytes"`
	TotalBytes        int64 `json:"totalBytes"`
	ActiveConnections int   `json:"activeConnections"`
	TotalConnections  int64 `json:"totalConnections"`
}

type SingScaleTrafficSplit struct {
	Direct                 SingScaleBucketStats `json:"direct"`
	Mesh                   SingScaleBucketStats `json:"mesh"`
	Relay                  SingScaleBucketStats `json:"relay"`
	TotalUplinkBytes       int64                `json:"totalUplinkBytes"`
	TotalDownlinkBytes     int64                `json:"totalDownlinkBytes"`
	TotalBytes             int64                `json:"totalBytes"`
	TotalActiveConnections int                  `json:"totalActiveConnections"`
	TotalConnections       int64                `json:"totalConnections"`
}

// ClassifySingScaleBucket maps a routed connection into exactly one of:
//   - SingScaleBucketDirect: physical ISP / LAN direct outbound (`direct`)
//   - SingScaleBucketMesh:   Tailnet internal mesh (`ts-mesh`, `100.64.0.0/10`, `fd7a:115c:a1e0::/48`,
//     or advertised Tailnet subnet routes sent directly to `ts-ep` without `egress-select`)
//   - SingScaleBucketRelay:  internet egress through Relay/Exit (`peer-exit`, `peer-relay`,
//     or native Tailscale Exit Node routed via `egress-select` -> `ts-ep`)
func ClassifySingScaleBucket(outboundTag string, chain []string, destination string, rule string) SingScaleTrafficBucket {
	leaf := strings.ToLower(strings.TrimSpace(outboundTag))

	hasChainTag := func(target string) bool {
		for _, tag := range chain {
			if strings.EqualFold(strings.TrimSpace(tag), target) {
				return true
			}
		}
		return false
	}

	if leaf == "direct" || (leaf == "" && hasChainTag("direct")) {
		return SingScaleBucketDirect
	}

	if leaf == "peer-exit" || leaf == "peer-relay" || hasChainTag("peer-exit") || hasChainTag("peer-relay") {
		return SingScaleBucketRelay
	}

	if leaf == "ts-mesh" || hasChainTag("ts-mesh") {
		return SingScaleBucketMesh
	}

	if leaf == "ts-ep" || hasChainTag("ts-ep") {
		if isSingScaleTailnetHost(extractDestinationHost(destination)) {
			return SingScaleBucketMesh
		}
		if hasChainTag("egress-select") {
			return SingScaleBucketRelay
		}
		if len(chain) > 0 {
			// Directly targeted ts-ep without passing through egress-select (e.g. Tailnet subnet route).
			return SingScaleBucketMesh
		}
		if strings.Contains(rule, "100.64.0.0/10") || strings.Contains(strings.ToLower(rule), "fd7a:115c:a1e0::/48") {
			return SingScaleBucketMesh
		}
		return SingScaleBucketRelay
	}

	if hasChainTag("egress-select") {
		return SingScaleBucketRelay
	}
	return SingScaleBucketDirect
}

func classifyTrackerMetadata(metadata *TrackerMetadata) SingScaleTrafficBucket {
	if metadata == nil {
		return SingScaleBucketDirect
	}
	var rule string
	if metadata.Rule != nil {
		rule = metadata.Rule.String()
	} else if metadata.Metadata.RouteRule != "" {
		rule = metadata.Metadata.RouteRule
	}
	return ClassifySingScaleBucket(
		metadata.Outbound,
		metadata.Chain,
		metadata.Metadata.Destination.String(),
		rule,
	)
}

func extractDestinationHost(addrPort string) string {
	s := strings.TrimSpace(addrPort)
	if s == "" {
		return ""
	}
	if strings.HasPrefix(s, "[") {
		if end := strings.IndexByte(s, ']'); end > 1 {
			return s[1:end]
		}
	}
	firstColon := strings.IndexByte(s, ':')
	lastColon := strings.LastIndexByte(s, ':')
	if lastColon > 0 && firstColon == lastColon {
		return s[:lastColon]
	}
	return s
}

func isSingScaleTailnetHost(host string) bool {
	if host == "" {
		return false
	}
	if idx := strings.IndexByte(host, '%'); idx > 0 {
		host = host[:idx]
	}
	addr, err := netip.ParseAddr(host)
	if err != nil {
		return false
	}
	addr = addr.Unmap()
	return singScaleCGNATPrefix.Contains(addr) || singScaleULAPrefix.Contains(addr)
}

// SingScaleTrafficSplit returns an atomic, sum-conserved 3-way split across
// evicted closed connections, retained closed connections, and currently active connections.
func (m *Manager) SingScaleTrafficSplit() SingScaleTrafficSplit {
	if m == nil {
		return SingScaleTrafficSplit{}
	}
	var buckets [singScaleBucketCount]SingScaleBucketStats

	m.closedConnectionsAccess.Lock()
	for i := 0; i < singScaleBucketCount; i++ {
		buckets[i].UplinkBytes = m.evictedBuckets[i].uplinkBytes
		buckets[i].DownlinkBytes = m.evictedBuckets[i].downlinkBytes
	}
	for element := m.closedConnections.Front(); element != nil; element = element.Next() {
		b := classifyTrackerMetadata(&element.Value)
		if up := element.Value.Upload.Load(); up > 0 {
			buckets[b].UplinkBytes += up
		}
		if down := element.Value.Download.Load(); down > 0 {
			buckets[b].DownlinkBytes += down
		}
	}
	m.connections.Range(func(_ uuid.UUID, tracker Tracker) bool {
		metadata := tracker.Metadata()
		b := classifyTrackerMetadata(metadata)
		if up := metadata.Upload.Load(); up > 0 {
			buckets[b].UplinkBytes += up
		}
		if down := metadata.Download.Load(); down > 0 {
			buckets[b].DownlinkBytes += down
		}
		buckets[b].ActiveConnections++
		return true
	})
	m.closedConnectionsAccess.Unlock()

	for i := 0; i < singScaleBucketCount; i++ {
		buckets[i].TotalBytes = buckets[i].UplinkBytes + buckets[i].DownlinkBytes
		totalConns := m.totalConnsByBucket[i].Load()
		if totalConns < int64(buckets[i].ActiveConnections) {
			totalConns = int64(buckets[i].ActiveConnections)
		}
		buckets[i].TotalConnections = totalConns
	}

	direct := buckets[SingScaleBucketDirect]
	mesh := buckets[SingScaleBucketMesh]
	relay := buckets[SingScaleBucketRelay]

	return SingScaleTrafficSplit{
		Direct:                 direct,
		Mesh:                   mesh,
		Relay:                  relay,
		TotalUplinkBytes:       direct.UplinkBytes + mesh.UplinkBytes + relay.UplinkBytes,
		TotalDownlinkBytes:     direct.DownlinkBytes + mesh.DownlinkBytes + relay.DownlinkBytes,
		TotalBytes:             direct.TotalBytes + mesh.TotalBytes + relay.TotalBytes,
		TotalActiveConnections: direct.ActiveConnections + mesh.ActiveConnections + relay.ActiveConnections,
		TotalConnections:       direct.TotalConnections + mesh.TotalConnections + relay.TotalConnections,
	}
}
