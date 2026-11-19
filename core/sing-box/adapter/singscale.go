package adapter

import "context"

// SingScaleTailscaleEndpoint extends TailscaleEndpoint with rich Tailnet telemetry,
// fast parallel Disco quality probing, dynamic Exit/Relay preference updates,
// and over-Tailnet gateway/relay port discovery for SingScale.
type SingScaleTailscaleEndpoint interface {
	TailscaleEndpoint
	SingScaleGetSnapshotJSON(ctx context.Context) (string, error)
	SingScaleStartInteractiveLogin(ctx context.Context) (string, error)
	SingScaleApplyPrefs(
		ctx context.Context,
		exitNodeIDOrIP string,
		allowLanAccess bool,
		acceptRoutes bool,
		relayServerPort int32,
		staticRelayEndpointsCSV string,
	) error
	SingScaleBatchProbePeersJSON(
		ctx context.Context,
		peerIPsCSV string,
		samples int32,
		timeoutMs int32,
	) (string, error)
	SingScaleProbePeerGatewaysJSON(
		ctx context.Context,
		peerIP string,
		portsCSV string,
		timeoutMs int32,
	) (string, error)
	SingScaleSetPowerMode(sleeping bool, networkUp bool)
}
