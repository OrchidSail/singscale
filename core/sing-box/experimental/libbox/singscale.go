package libbox

import (
	"context"
	"time"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/urltest"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/protocol/group"
	"github.com/sagernet/sing-box/route"
	E "github.com/sagernet/sing/common/exceptions"
)

func (s *CommandServer) resolveSingScaleTailscale(endpointTag string) (adapter.SingScaleTailscaleEndpoint, error) {
	instance := s.StartedService.Instance()
	if instance == nil || instance.Box() == nil {
		return nil, E.New("sing-box service is not running")
	}
	if endpointTag == "" {
		endpointTag = "ts-ep"
	}
	ep, loaded := instance.Box().Endpoint().Get(endpointTag)
	if !loaded {
		// Fallback to the first Tailscale endpoint if tag differs
		for _, candidate := range instance.Box().Endpoint().Endpoints() {
			if candidate.Type() == C.TypeTailscale {
				ep = candidate
				loaded = true
				break
			}
		}
	}
	if !loaded || ep == nil {
		return nil, E.New("Tailscale endpoint not found: ", endpointTag)
	}
	tsEp, ok := ep.(adapter.SingScaleTailscaleEndpoint)
	if !ok {
		return nil, E.New("endpoint does not implement SingScaleTailscaleEndpoint")
	}
	return tsEp, nil
}

func (s *CommandServer) SingScaleGetTailnetSnapshot(endpointTag string) (string, error) {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return tsEp.SingScaleGetSnapshotJSON(ctx)
}

func (s *CommandServer) SingScaleStartInteractiveLogin(endpointTag string) (string, error) {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 12*time.Second)
	defer cancel()
	return tsEp.SingScaleStartInteractiveLogin(ctx)
}

func (s *CommandServer) SingScaleApplyTailscalePrefs(
	endpointTag string,
	exitNodeIDOrIP string,
	allowLanAccess bool,
	acceptRoutes bool,
	relayServerPort int32,
	staticRelayEndpointsCSV string,
) error {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return tsEp.SingScaleApplyPrefs(
		ctx,
		exitNodeIDOrIP,
		allowLanAccess,
		acceptRoutes,
		relayServerPort,
		staticRelayEndpointsCSV,
	)
}

func (s *CommandServer) SingScaleBatchProbePeers(
	endpointTag string,
	peerIPsCSV string,
	samples int32,
	timeoutMs int32,
) (string, error) {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return "[]", err
	}
	totalTimeout := time.Duration(int(samples+1)*int(timeoutMs)+2000) * time.Millisecond
	if totalTimeout < 4*time.Second {
		totalTimeout = 4 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), totalTimeout)
	defer cancel()
	return tsEp.SingScaleBatchProbePeersJSON(ctx, peerIPsCSV, samples, timeoutMs)
}

func (s *CommandServer) SingScaleProbePeerGateways(
	endpointTag string,
	peerIP string,
	portsCSV string,
	timeoutMs int32,
) (string, error) {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs+2500)*time.Millisecond)
	defer cancel()
	return tsEp.SingScaleProbePeerGatewaysJSON(ctx, peerIP, portsCSV, timeoutMs)
}

func (s *CommandServer) SingScaleTestOutboundURL(outboundTag string, probeURL string, timeoutMs int32) (int32, error) {
	instance := s.StartedService.Instance()
	if instance == nil || instance.Box() == nil {
		return -1, E.New("sing-box service is not running")
	}
	if probeURL == "" {
		probeURL = "https://cp.cloudflare.com/generate_204"
	}
	if timeoutMs <= 0 {
		timeoutMs = 4000
	}
	var detour adapter.Outbound
	if outboundTag != "" {
		if ob, ok := instance.Box().Outbound().Outbound(outboundTag); ok {
			detour = ob
		} else if ep, ok := instance.Box().Endpoint().Get(outboundTag); ok {
			detour = ep
		} else {
			return -1, E.New("outbound or endpoint not found: ", outboundTag)
		}
	} else {
		detour = instance.Box().Outbound().Default()
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()
	rtt, err := urltest.URLTest(ctx, probeURL, detour)
	if err != nil {
		return -1, err
	}
	return int32(rtt), nil
}

func (s *CommandServer) SingScaleSelectOutbound(selectorTag string, targetTag string) bool {
	instance := s.StartedService.Instance()
	if instance == nil || instance.Box() == nil {
		return false
	}
	if selectorTag == "" {
		selectorTag = "egress-select"
	}
	ob, ok := instance.Box().Outbound().Outbound(selectorTag)
	if !ok || ob == nil {
		return false
	}
	sel, ok := ob.(*group.Selector)
	if !ok || sel == nil {
		return false
	}
	return sel.SelectOutbound(targetTag)
}

func (s *CommandServer) SingScaleLogoutTailscale(endpointTag string) error {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer cancel()
	return tsEp.Logout(ctx)
}

func (s *CommandServer) SingScaleConfigureDirectFirst(
	enabled bool,
	maxDirectRttMs int32,
	probeTimeoutMs int32,
	clearCache bool,
) {
	route.SingScaleConfigureDirectFirst(enabled, maxDirectRttMs, probeTimeoutMs, clearCache)
}

func (s *CommandServer) SingScaleSetPowerMode(endpointTag string, sleeping bool, networkUp bool) {
	tsEp, err := s.resolveSingScaleTailscale(endpointTag)
	if err != nil {
		return
	}
	tsEp.SingScaleSetPowerMode(sleeping, networkUp)
}
