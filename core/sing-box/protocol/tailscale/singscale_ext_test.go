//go:build with_gvisor

package tailscale

import (
	"context"
	"net/netip"
	"testing"

	"github.com/sagernet/sing-box/adapter"
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
