//go:build with_gvisor

package tailscale

import (
	"context"
	"errors"
	"io"
	"net"
	"net/netip"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"github.com/sagernet/sing/common/logger"
	"github.com/sagernet/tailscale/net/tsaddr"
)

const (
	defaultMaxInboundLocalConns        = 64
	defaultMaxInboundLocalConnsPerPeer = 32
	defaultInboundLocalDialTimeout     = 3 * time.Second
	defaultInboundHalfCloseTimeout     = 30 * time.Second
	defaultInboundTCPKeepAlive         = 30 * time.Second
)

type tcpHalfCloser interface {
	CloseRead() error
	CloseWrite() error
}

type tailnetLocalForwarderOptions struct {
	Enabled          bool
	MaxTotalConns    int
	MaxPerPeerConns  int
	DialTimeout      time.Duration
	HalfCloseTimeout time.Duration
	Logger           logger.ContextLogger
	VerifyPeer       func(srcIP netip.Addr) bool
	IsSelfIP         func(dstIP netip.Addr) bool
	DialLoopback     func(ctx context.Context, network, address string) (net.Conn, error)
}

// tailnetLocalForwarder passively intercepts inbound TCP connections delivered by
// tsnet's userspace netstack to this node's own Tailscale IP (100.x.y.z:<port> or
// fd7a:...:<port>) from authenticated Tailnet peers and transparently proxies them
// directly to Android's OS loopback (127.0.0.1:<port>, falling back to [::1]:<port>).
//
// Design guarantees:
//   - Purely event-driven: zero background goroutines, zero timers, zero screen-off wakeups.
//   - Strictly loopback-only dialing: never routes back through sing-box or tun0.
//   - Defense-in-depth peer verification: accepts only authenticated, non-expired Tailnet peers.
//   - Bounded global and per-peer concurrency with clean CloseWrite half-close & teardown.
type tailnetLocalForwarder struct {
	ctx              context.Context
	cancel           context.CancelFunc
	enabled          atomic.Bool
	maxTotalConns    int
	maxPerPeerConns  int
	dialTimeout      time.Duration
	halfCloseTimeout time.Duration
	logger           logger.ContextLogger
	verifyPeer       func(srcIP netip.Addr) bool
	isSelfIP         func(dstIP netip.Addr) bool
	dialLoopback     func(ctx context.Context, network, address string) (net.Conn, error)

	mu            sync.Mutex
	activeTotal   int
	activeByPeer  map[netip.Addr]int
	activeConns   map[io.Closer]struct{}
	rejectedTotal atomic.Int64
}

func newTailnetLocalForwarder(parentCtx context.Context, opts tailnetLocalForwarderOptions) *tailnetLocalForwarder {
	if parentCtx == nil {
		parentCtx = context.Background()
	}
	ctx, cancel := context.WithCancel(parentCtx)
	if opts.MaxTotalConns <= 0 {
		opts.MaxTotalConns = defaultMaxInboundLocalConns
	}
	if opts.MaxPerPeerConns <= 0 {
		opts.MaxPerPeerConns = defaultMaxInboundLocalConnsPerPeer
	}
	if opts.MaxPerPeerConns > opts.MaxTotalConns {
		opts.MaxPerPeerConns = opts.MaxTotalConns
	}
	if opts.DialTimeout <= 0 {
		opts.DialTimeout = defaultInboundLocalDialTimeout
	}
	if opts.HalfCloseTimeout <= 0 {
		opts.HalfCloseTimeout = defaultInboundHalfCloseTimeout
	}
	dialFn := opts.DialLoopback
	if dialFn == nil {
		loopbackDialer := &net.Dialer{
			Timeout:   opts.DialTimeout,
			KeepAlive: defaultInboundTCPKeepAlive,
		}
		dialFn = loopbackDialer.DialContext
	}
	f := &tailnetLocalForwarder{
		ctx:              ctx,
		cancel:           cancel,
		maxTotalConns:    opts.MaxTotalConns,
		maxPerPeerConns:  opts.MaxPerPeerConns,
		dialTimeout:      opts.DialTimeout,
		halfCloseTimeout: opts.HalfCloseTimeout,
		logger:           opts.Logger,
		verifyPeer:       opts.VerifyPeer,
		isSelfIP:         opts.IsSelfIP,
		dialLoopback:     dialFn,
		activeByPeer:     make(map[netip.Addr]int),
		activeConns:      make(map[io.Closer]struct{}),
	}
	f.enabled.Store(opts.Enabled)
	return f
}

func (f *tailnetLocalForwarder) Enabled() bool {
	if f == nil {
		return false
	}
	return f.enabled.Load()
}

func (f *tailnetLocalForwarder) SetEnabled(enabled bool) {
	if f == nil {
		return
	}
	prev := f.enabled.Swap(enabled)
	if prev && !enabled {
		f.closeAllActiveConns()
	}
}

func (f *tailnetLocalForwarder) ActiveCount() int {
	if f == nil {
		return 0
	}
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.activeTotal
}

func (f *tailnetLocalForwarder) RejectedCount() int64 {
	if f == nil {
		return 0
	}
	return f.rejectedTotal.Load()
}

func (f *tailnetLocalForwarder) Close() error {
	if f == nil {
		return nil
	}
	f.cancel()
	f.closeAllActiveConns()
	return nil
}

func (f *tailnetLocalForwarder) closeAllActiveConns() {
	f.mu.Lock()
	closers := make([]io.Closer, 0, len(f.activeConns))
	for c := range f.activeConns {
		closers = append(closers, c)
	}
	f.mu.Unlock()
	for _, c := range closers {
		_ = c.Close()
	}
}

func isTailscaleReservedServiceIP(ip netip.Addr) bool {
	ip = ip.Unmap()
	return ip == tsaddr.TailscaleServiceIP() || ip == tsaddr.TailscaleServiceIPv6()
}

// ShouldInterceptSelfFlow returns true iff dstIP is one of this node's own
// Tailscale IPs (never Quad100 / MagicDNS service IPs).
func (f *tailnetLocalForwarder) ShouldInterceptSelfFlow(dstIP netip.Addr) bool {
	if f == nil || f.isSelfIP == nil {
		return false
	}
	dstIP = dstIP.Unmap()
	if !dstIP.IsValid() || isTailscaleReservedServiceIP(dstIP) {
		return false
	}
	return f.isSelfIP(dstIP)
}

// HandleFlow decides whether an inbound Tailnet TCP flow destined to this node's
// own Tailscale IP should be forwarded to Android local loopback or rejected with RST.
//
// Return semantics match netstack.Impl.GetTCPHandlerForFlow:
//   - (nil, false): destination is not our own Tailscale IP; let caller handle normally.
//   - (nil, true):  destination is our own Tailscale IP, reject immediately with TCP RST.
//   - (fn, true):   destination is our own Tailscale IP, accept and proxy via fn.
func (f *tailnetLocalForwarder) HandleFlow(src, dst netip.AddrPort) (handler func(net.Conn), intercept bool) {
	if !f.ShouldInterceptSelfFlow(dst.Addr()) {
		return nil, false
	}
	if !f.enabled.Load() || f.ctx.Err() != nil {
		f.rejectedTotal.Add(1)
		return nil, true
	}
	if dst.Port() == 0 {
		f.rejectedTotal.Add(1)
		return nil, true
	}

	srcIP := src.Addr().Unmap()
	if !srcIP.IsValid() || !tsaddr.IsTailscaleIP(srcIP) || isTailscaleReservedServiceIP(srcIP) {
		f.rejectedTotal.Add(1)
		if f.logger != nil {
			f.logger.Debug("tailnet local forward rejected non-tailnet source: ", src)
		}
		return nil, true
	}
	if f.verifyPeer == nil || !f.verifyPeer(srcIP) {
		f.rejectedTotal.Add(1)
		if f.logger != nil {
			f.logger.Debug("tailnet local forward rejected unauthenticated peer: ", srcIP)
		}
		return nil, true
	}

	if !f.hasCapacity(srcIP) {
		f.rejectedTotal.Add(1)
		if f.logger != nil {
			f.logger.Warn("tailnet local forward concurrency limit reached for peer ", srcIP, " -> :", dst.Port())
		}
		return nil, true
	}

	return func(peerConn net.Conn) {
		f.forwardConn(src, dst, peerConn)
	}, true
}

func (f *tailnetLocalForwarder) hasCapacity(peerIP netip.Addr) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.activeTotal >= f.maxTotalConns {
		return false
	}
	if f.activeByPeer[peerIP] >= f.maxPerPeerConns {
		return false
	}
	return true
}

func (f *tailnetLocalForwarder) acquireSlot(peerIP netip.Addr, peerConn io.Closer) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.enabled.Load() || f.ctx.Err() != nil {
		return false
	}
	if f.activeTotal >= f.maxTotalConns || f.activeByPeer[peerIP] >= f.maxPerPeerConns {
		return false
	}
	f.activeTotal++
	f.activeByPeer[peerIP]++
	f.activeConns[peerConn] = struct{}{}
	return true
}

func (f *tailnetLocalForwarder) releaseSlot(peerIP netip.Addr, peerConn io.Closer) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.activeConns, peerConn)
	if f.activeTotal > 0 {
		f.activeTotal--
	}
	if n := f.activeByPeer[peerIP]; n <= 1 {
		delete(f.activeByPeer, peerIP)
	} else {
		f.activeByPeer[peerIP] = n - 1
	}
}

func (f *tailnetLocalForwarder) trackConn(c io.Closer) bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	if !f.enabled.Load() || f.ctx.Err() != nil {
		return false
	}
	f.activeConns[c] = struct{}{}
	return true
}

func (f *tailnetLocalForwarder) untrackConn(c io.Closer) {
	f.mu.Lock()
	defer f.mu.Unlock()
	delete(f.activeConns, c)
}

func (f *tailnetLocalForwarder) forwardConn(src, dst netip.AddrPort, peerConn net.Conn) {
	if peerConn == nil {
		return
	}
	defer peerConn.Close()

	srcIP := src.Addr().Unmap()
	if !f.acquireSlot(srcIP, peerConn) {
		f.rejectedTotal.Add(1)
		return
	}
	defer f.releaseSlot(srcIP, peerConn)

	localConn, dialedTarget, err := f.dialLocalLoopback(dst)
	if err != nil {
		if f.logger != nil {
			f.logger.Debug("tailnet local forward dial ", dst.Port(), " from ", srcIP, " failed: ", err)
		}
		return
	}
	if !f.trackConn(localConn) {
		_ = localConn.Close()
		return
	}
	defer func() {
		f.untrackConn(localConn)
		_ = localConn.Close()
	}()

	if tcpConn, ok := localConn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}
	if f.logger != nil {
		f.logger.Info("tailnet inbound forward ", src, " -> ", dialedTarget)
	}

	f.bridgeConns(peerConn, localConn)
}

// dialLocalLoopback connects strictly to the OS loopback interface (127.0.0.1 / [::1])
// so traffic never re-enters sing-box or Android's tun0 interface.
// For IPv4 destinations (100.x.y.z), it dials 127.0.0.1:<port> first and falls back to
// [::1]:<port> on failure (covering Android daemons such as adbd/sshd that may bind
// IPv6-only or dual-stack wildcard sockets).
func (f *tailnetLocalForwarder) dialLocalLoopback(dst netip.AddrPort) (net.Conn, string, error) {
	portStr := strconv.Itoa(int(dst.Port()))
	type candidate struct {
		network string
		address string
	}
	var candidates [2]candidate
	if dst.Addr().Unmap().Is6() {
		candidates[0] = candidate{network: "tcp6", address: net.JoinHostPort("::1", portStr)}
		candidates[1] = candidate{network: "tcp4", address: net.JoinHostPort("127.0.0.1", portStr)}
	} else {
		candidates[0] = candidate{network: "tcp4", address: net.JoinHostPort("127.0.0.1", portStr)}
		candidates[1] = candidate{network: "tcp6", address: net.JoinHostPort("::1", portStr)}
	}

	var firstErr error
	for i, c := range candidates {
		if f.ctx.Err() != nil {
			return nil, "", f.ctx.Err()
		}
		attemptTimeout := f.dialTimeout
		if i == 1 && attemptTimeout > 1500*time.Millisecond {
			// Loopback fallback should complete in milliseconds when reachable.
			attemptTimeout = 1500 * time.Millisecond
		}
		dialCtx, cancel := context.WithTimeout(f.ctx, attemptTimeout)
		conn, err := f.dialLoopback(dialCtx, c.network, c.address)
		cancel()
		if err == nil {
			return conn, c.address, nil
		}
		if firstErr == nil {
			firstErr = err
		}
	}
	return nil, "", errors.Join(firstErr)
}

func (f *tailnetLocalForwarder) bridgeConns(peerConn, localConn net.Conn) {
	done := make(chan struct{})
	go func() {
		defer close(done)
		_, err := io.Copy(peerConn, localConn)
		if err != nil {
			_ = peerConn.Close()
			_ = localConn.Close()
			return
		}
		if hc, ok := peerConn.(tcpHalfCloser); ok {
			_ = hc.CloseWrite()
		} else {
			_ = peerConn.Close()
		}
		if hc, ok := localConn.(tcpHalfCloser); ok {
			_ = hc.CloseRead()
		}
		_ = peerConn.SetReadDeadline(time.Now().Add(f.halfCloseTimeout))
	}()

	_, err := io.Copy(localConn, peerConn)
	if err != nil {
		_ = localConn.Close()
		_ = peerConn.Close()
	} else {
		if hc, ok := localConn.(tcpHalfCloser); ok {
			_ = hc.CloseWrite()
		} else {
			_ = localConn.Close()
		}
		if hc, ok := peerConn.(tcpHalfCloser); ok {
			_ = hc.CloseRead()
		}
		_ = localConn.SetReadDeadline(time.Now().Add(f.halfCloseTimeout))
	}

	<-done
}

func (t *Endpoint) isSelfTailscaleIP(dstIP netip.Addr) bool {
	if t == nil || !t.started.Load() || t.server == nil {
		return false
	}
	dstIP = dstIP.Unmap()
	if !dstIP.IsValid() || isTailscaleReservedServiceIP(dstIP) {
		return false
	}
	addr4, addr6 := t.server.TailscaleIPs()
	return (addr4.IsValid() && dstIP == addr4.Unmap()) || (addr6.IsValid() && dstIP == addr6.Unmap())
}

func (t *Endpoint) verifyAuthenticatedTailnetPeer(srcIP netip.Addr) bool {
	lb := t.safeLocalBackend()
	if lb == nil {
		return false
	}
	srcIP = srcIP.Unmap()
	if !srcIP.IsValid() || !tsaddr.IsTailscaleIP(srcIP) || isTailscaleReservedServiceIP(srcIP) {
		return false
	}
	peer, ok := lb.PeerForIP(srcIP)
	if !ok || peer.IsSelf || !peer.Node.Valid() || peer.Node.Expired() {
		return false
	}
	if !peer.Route.IsValid() || peer.Route.Bits() != srcIP.BitLen() {
		return false
	}
	for _, addrPfx := range peer.Node.Addresses().All() {
		if addrPfx.Addr().Unmap() == srcIP {
			return true
		}
	}
	return false
}
