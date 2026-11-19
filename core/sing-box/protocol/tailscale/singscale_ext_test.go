//go:build with_gvisor

package tailscale

import (
	"context"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/tailscale/ipn/ipnstate"
)

func TestSingScaleEndpointConcurrentTeardownNilSafety(t *testing.T) {
	ep := &Endpoint{}
	// Simulate transient race where started is still true while server is nil during teardown
	ep.started.Store(true)

	ctx := context.Background()
	if _, err := ep.SingScaleGetSnapshotJSON(ctx); err == nil {
		t.Fatal("expected error when server is nil")
	}
	if _, err := ep.SingScaleStartInteractiveLogin(ctx); err == nil {
		t.Fatal("expected error when server is nil")
	}
	if err := ep.SingScaleApplyPrefs(ctx, "100.64.0.1", true, true, 0, ""); err == nil {
		t.Fatal("expected error when server is nil")
	}
	if raw, err := ep.SingScaleBatchProbePeersJSON(ctx, "100.64.0.1,100.64.0.2", 2, 200); err == nil || raw != "[]" {
		t.Fatalf("expected empty JSON array and error, got %q, %v", raw, err)
	}
	if _, err := ep.SingScaleProbePeerGatewaysJSON(ctx, "100.64.0.1", "1080,7890", 200); err == nil {
		t.Fatal("expected error when server is nil")
	}
	if err := ep.Logout(ctx); err == nil {
		t.Fatal("expected error on Logout when server is nil")
	}
	if err := ep.SetTailscaleExitNode(ctx, "node-1"); err == nil {
		t.Fatal("expected error on SetTailscaleExitNode when server is nil")
	}

	// Must never panic when server/backend is nil
	ep.SingScaleSetPowerMode(true, false)
	ep.SingScaleSetInboundLocalForward(false)
	ep.SingScaleSetInboundLocalForward(true)
	ep.InterfaceUpdated(ctx)
	if ep.PreferredAddress(&adapter.InboundContext{}, netip.MustParseAddr("100.64.0.1")) {
		t.Fatal("expected false PreferredAddress when backend is nil")
	}
}

func TestClassifyPeerTransportPathSeparatesDirectPeerRelayAndDERP(t *testing.T) {
	if got := classifyPeerTransportPath(&ipnstate.PeerStatus{Online: true, CurAddr: "203.0.113.1:41641", Relay: "hkg"}); got != "DIRECT" {
		t.Fatalf("expected DIRECT when CurAddr is set, got %q", got)
	}
	if got := classifyPeerTransportPath(&ipnstate.PeerStatus{Online: true, PeerRelay: "198.51.100.2:41641:vni:1", Relay: "hkg"}); got != "PEER_RELAY" {
		t.Fatalf("expected PEER_RELAY when PeerRelay is set without CurAddr, got %q", got)
	}
	if got := classifyPeerTransportPath(&ipnstate.PeerStatus{Online: true, Relay: "hkg"}); got != "DERP" {
		t.Fatalf("expected DERP when online with only DERP relay, got %q", got)
	}
	if got := classifyPeerTransportPath(&ipnstate.PeerStatus{Online: false, Relay: "hkg"}); got != "OFFLINE" {
		t.Fatalf("expected OFFLINE when peer is offline without direct endpoint, got %q", got)
	}
	if got := classifyPeerTransportPath(nil); got != "OFFLINE" {
		t.Fatalf("expected OFFLINE for nil peer, got %q", got)
	}
}
