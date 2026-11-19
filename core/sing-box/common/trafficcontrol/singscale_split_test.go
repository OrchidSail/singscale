package trafficcontrol

import (
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing-box/adapter"
	M "github.com/sagernet/sing/common/metadata"

	"github.com/gofrs/uuid/v5"
)

type fakeTracker struct {
	meta TrackerMetadata
}

func (f *fakeTracker) Metadata() *TrackerMetadata {
	return &f.meta
}

func (f *fakeTracker) Close() error {
	return nil
}

func newFakeTracker(outbound string, chain []string, dest string, rule string, up, down int64) *fakeTracker {
	id, _ := uuid.NewV4()
	u := new(atomic.Int64)
	d := new(atomic.Int64)
	u.Store(up)
	d.Store(down)
	return &fakeTracker{
		meta: TrackerMetadata{
			ID: id,
			Metadata: adapter.InboundContext{
				Destination: M.ParseSocksaddr(dest),
				RouteRule:   rule,
			},
			CreatedAt: time.Now(),
			Upload:    u,
			Download:  d,
			Chain:     chain,
			Outbound:  outbound,
		},
	}
}

func TestClassifySingScaleBucketMutuallyExclusiveCases(t *testing.T) {
	cases := []struct {
		name     string
		outbound string
		chain    []string
		dest     string
		rule     string
		want     SingScaleTrafficBucket
	}{
		{
			name:     "local direct outbound",
			outbound: "direct",
			chain:    []string{"direct"},
			dest:     "192.0.2.29:443",
			want:     SingScaleBucketDirect,
		},
		{
			name:     "egress selector currently pointing to direct stays direct",
			outbound: "direct",
			chain:    []string{"direct", "egress-select"},
			dest:     "198.51.100.106:443",
			want:     SingScaleBucketDirect,
		},
		{
			name:     "tailnet 100.x peer via ts-mesh selector",
			outbound: "ts-ep",
			chain:    []string{"ts-ep", "ts-mesh"},
			dest:     "100.64.0.51:22",
			want:     SingScaleBucketMesh,
		},
		{
			name:     "tailnet advertised subnet route via ts-mesh selector",
			outbound: "ts-ep",
			chain:    []string{"ts-ep", "ts-mesh"},
			dest:     "198.51.100.10:80",
			want:     SingScaleBucketMesh,
		},
		{
			name:     "tailnet IPv6 ULA peer via legacy direct ts-ep chain",
			outbound: "ts-ep",
			chain:    []string{"ts-ep"},
			dest:     "[fd7a:115c:a1e0::1234]:443",
			want:     SingScaleBucketMesh,
		},
		{
			name:     "tailnet subnet route via legacy direct ts-ep chain without egress-select",
			outbound: "ts-ep",
			chain:    []string{"ts-ep"},
			dest:     "203.0.113.40:8080",
			want:     SingScaleBucketMesh,
		},
		{
			name:     "native tailscale exit node routed via egress-select to ts-ep",
			outbound: "ts-ep",
			chain:    []string{"ts-ep", "egress-select"},
			dest:     "203.0.113.6:443",
			want:     SingScaleBucketRelay,
		},
		{
			name:     "proxy exit peer-exit via egress-select",
			outbound: "peer-exit",
			chain:    []string{"peer-exit", "egress-select"},
			dest:     "192.0.2.1:443",
			want:     SingScaleBucketRelay,
		},
		{
			name:     "proxy relay peer-relay via egress-select",
			outbound: "peer-relay",
			chain:    []string{"peer-relay", "egress-select"},
			dest:     "198.51.100.8:443",
			want:     SingScaleBucketRelay,
		},
		{
			name:     "minimal ts-ep metadata to public IP defaults to relay",
			outbound: "ts-ep",
			chain:    nil,
			dest:     "203.0.113.6:443",
			want:     SingScaleBucketRelay,
		},
		{
			name:     "minimal ts-ep metadata to 100.x IP classifies as mesh",
			outbound: "ts-ep",
			chain:    nil,
			dest:     "100.64.0.100:5555",
			want:     SingScaleBucketMesh,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ClassifySingScaleBucket(tc.outbound, tc.chain, tc.dest, tc.rule)
			if got != tc.want {
				t.Fatalf("ClassifySingScaleBucket(%q, %v, %q, %q) = %v, want %v", tc.outbound, tc.chain, tc.dest, tc.rule, got, tc.want)
			}
		})
	}
}

func TestManagerSingScaleTrafficSplitConservationAcrossEvictionAndClear(t *testing.T) {
	m := NewManager()

	// 1. Create and close > closedConnectionsLimit connections across all 3 buckets to verify eviction accounting!
	const closedPerBucket = 100 // 300 > 256 closedConnectionsLimit -> 44 evicted
	for i := 0; i < closedPerBucket; i++ {
		trDirect := newFakeTracker("direct", []string{"direct"}, "192.0.2.29:443", "", 100, 400)
		m.join(trDirect)
		m.leave(trDirect)

		trMesh := newFakeTracker("ts-ep", []string{"ts-ep", "ts-mesh"}, "100.64.0.51:22", "", 200, 300)
		m.join(trMesh)
		m.leave(trMesh)

		trRelay := newFakeTracker("ts-ep", []string{"ts-ep", "egress-select"}, "203.0.113.6:443", "", 500, 1500)
		m.join(trRelay)
		m.leave(trRelay)
	}

	// 2. Keep active connections in all 3 buckets
	actDirect := newFakeTracker("direct", []string{"direct"}, "192.0.2.55:443", "", 1_000, 9_000)
	actMesh1 := newFakeTracker("ts-ep", []string{"ts-ep", "ts-mesh"}, "100.64.0.52:5555", "", 2_000, 8_000)
	actMesh2 := newFakeTracker("ts-ep", []string{"ts-ep", "ts-mesh"}, "198.51.100.10:80", "", 3_000, 7_000)
	actRelay := newFakeTracker("peer-exit", []string{"peer-exit", "egress-select"}, "192.0.2.1:443", "", 4_000, 16_000)
	m.join(actDirect)
	m.join(actMesh1)
	m.join(actMesh2)
	m.join(actRelay)

	split := m.SingScaleTrafficSplit()
	totalUp, totalDown := m.Total()

	// Exact per-bucket values (no mesh merged into relay!)
	if split.Direct.UplinkBytes != 100*100+1_000 || split.Direct.DownlinkBytes != 100*400+9_000 {
		t.Fatalf("unexpected direct bucket bytes: %+v", split.Direct)
	}
	if split.Mesh.UplinkBytes != 100*200+5_000 || split.Mesh.DownlinkBytes != 100*300+15_000 {
		t.Fatalf("unexpected mesh bucket bytes: %+v", split.Mesh)
	}
	if split.Relay.UplinkBytes != 100*500+4_000 || split.Relay.DownlinkBytes != 100*1500+16_000 {
		t.Fatalf("unexpected relay bucket bytes: %+v", split.Relay)
	}

	// Exact connection counts
	if split.Direct.ActiveConnections != 1 || split.Mesh.ActiveConnections != 2 || split.Relay.ActiveConnections != 1 {
		t.Fatalf("unexpected active connection split: direct=%d mesh=%d relay=%d",
			split.Direct.ActiveConnections, split.Mesh.ActiveConnections, split.Relay.ActiveConnections)
	}
	if split.Direct.TotalConnections != 101 || split.Mesh.TotalConnections != 102 || split.Relay.TotalConnections != 101 {
		t.Fatalf("unexpected total connection split: direct=%d mesh=%d relay=%d",
			split.Direct.TotalConnections, split.Mesh.TotalConnections, split.Relay.TotalConnections)
	}

	// Strict conservation against Manager.Total()
	if split.TotalUplinkBytes != totalUp || split.TotalDownlinkBytes != totalDown {
		t.Fatalf("split totals (%d, %d) diverged from Manager.Total() (%d, %d)",
			split.TotalUplinkBytes, split.TotalDownlinkBytes, totalUp, totalDown)
	}
	if split.TotalBytes != split.Direct.TotalBytes+split.Mesh.TotalBytes+split.Relay.TotalBytes {
		t.Fatalf("total bytes conservation violated: %+v", split)
	}

	// 3. Calling Clear() archives retained closed connections into evictedBuckets without losing a single byte
	m.Clear()
	afterClear := m.SingScaleTrafficSplit()
	if afterClear.Direct != split.Direct || afterClear.Mesh != split.Mesh || afterClear.Relay != split.Relay {
		t.Fatalf("Clear() altered split totals: before=%+v after=%+v", split, afterClear)
	}
}
