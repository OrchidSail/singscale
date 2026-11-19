//go:build with_gvisor

package tailscale

import (
	"bytes"
	"context"
	"io"
	"net"
	"net/netip"
	"sync"
	"testing"
	"time"
)

// newLoopbackTCPPair returns a connected pair of real *net.TCPConn endpoints over
// OS loopback so tests exercise true TCP half-close (CloseWrite/CloseRead).
func newLoopbackTCPPair(t *testing.T) (*net.TCPConn, *net.TCPConn) {
	t.Helper()
	ln, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen loopback pair: %v", err)
	}
	defer ln.Close()

	var (
		serverConn net.Conn
		acceptErr  error
		wg         sync.WaitGroup
	)
	wg.Add(1)
	go func() {
		defer wg.Done()
		serverConn, acceptErr = ln.Accept()
	}()

	clientConn, err := net.DialTimeout("tcp4", ln.Addr().String(), 2*time.Second)
	if err != nil {
		t.Fatalf("dial loopback pair: %v", err)
	}
	wg.Wait()
	if acceptErr != nil {
		clientConn.Close()
		t.Fatalf("accept loopback pair: %v", acceptErr)
	}
	return clientConn.(*net.TCPConn), serverConn.(*net.TCPConn)
}

func TestTailnetLocalForwarderBidirectionalPassthroughAndHalfClose(t *testing.T) {
	// Mock local Android service (e.g. adbd / Termux sshd) on 127.0.0.1:<ephemeral>
	localLn, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen local loopback: %v", err)
	}
	defer localLn.Close()
	localPort := uint16(localLn.Addr().(*net.TCPAddr).Port)

	const clientPayload = "CNXN\x00\x00\x00\x01singscale-adb-test"
	const serverReply = "CNXN-OK-FROM-ANDROID-ADBD"

	serverDone := make(chan error, 1)
	go func() {
		c, err := localLn.Accept()
		if err != nil {
			serverDone <- err
			return
		}
		defer c.Close()
		// Read until peer half-closes via CloseWrite()!
		got, err := io.ReadAll(c)
		if err != nil {
			serverDone <- err
			return
		}
		if string(got) != clientPayload {
			serverDone <- io.ErrUnexpectedEOF
			return
		}
		_, err = c.Write([]byte(serverReply))
		serverDone <- err
	}()

	selfIP := netip.MustParseAddr("100.64.0.100")
	peerIP := netip.MustParseAddr("100.64.0.5")

	fwd := newTailnetLocalForwarder(context.Background(), tailnetLocalForwarderOptions{
		Enabled:          true,
		MaxTotalConns:    8,
		MaxPerPeerConns:  4,
		DialTimeout:      2 * time.Second,
		HalfCloseTimeout: 2 * time.Second,
		VerifyPeer: func(ip netip.Addr) bool {
			return ip == peerIP
		},
		IsSelfIP: func(ip netip.Addr) bool {
			return ip == selfIP
		},
	})
	defer fwd.Close()

	src := netip.AddrPortFrom(peerIP, 54321)
	dst := netip.AddrPortFrom(selfIP, localPort)

	handler, intercept := fwd.HandleFlow(src, dst)
	if !intercept || handler == nil {
		t.Fatalf("expected authenticated peer flow to be intercepted and handled, got intercept=%v handler=%v", intercept, handler != nil)
	}

	remoteClient, netstackSide := newLoopbackTCPPair(t)
	defer remoteClient.Close()

	handlerDone := make(chan struct{})
	go func() {
		defer close(handlerDone)
		handler(netstackSide)
	}()

	if _, err := remoteClient.Write([]byte(clientPayload)); err != nil {
		t.Fatalf("write from mock peer: %v", err)
	}
	// Half-close write side: server MUST observe EOF and still flush serverReply back!
	if err := remoteClient.CloseWrite(); err != nil {
		t.Fatalf("peer CloseWrite: %v", err)
	}

	reply, err := io.ReadAll(remoteClient)
	if err != nil {
		t.Fatalf("read reply on mock peer: %v", err)
	}
	if string(reply) != serverReply {
		t.Fatalf("unexpected reply: got %q, want %q", string(reply), serverReply)
	}

	select {
	case err := <-serverDone:
		if err != nil {
			t.Fatalf("local server error: %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for local server")
	}

	select {
	case <-handlerDone:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for forwarder handler to exit")
	}

	if active := fwd.ActiveCount(); active != 0 {
		t.Fatalf("expected 0 active connections after clean teardown, got %d", active)
	}
}

func TestTailnetLocalForwarderFallbackToIPv6Loopback(t *testing.T) {
	// Bind strictly on IPv6 loopback [::1] only so 127.0.0.1:<port> fails and fallback triggers.
	localLn, err := net.Listen("tcp6", "[::1]:0")
	if err != nil {
		t.Skipf("IPv6 loopback unavailable in test environment: %v", err)
	}
	defer localLn.Close()
	localPort := uint16(localLn.Addr().(*net.TCPAddr).Port)

	go func() {
		c, err := localLn.Accept()
		if err != nil {
			return
		}
		defer c.Close()
		buf := make([]byte, 32)
		n, _ := c.Read(buf)
		_, _ = c.Write(bytes.ToUpper(buf[:n]))
	}()

	selfIP := netip.MustParseAddr("100.64.0.100")
	peerIP := netip.MustParseAddr("100.64.0.7")

	var attempted []string
	var attemptMu sync.Mutex
	baseDialer := &net.Dialer{Timeout: 1 * time.Second}

	fwd := newTailnetLocalForwarder(context.Background(), tailnetLocalForwarderOptions{
		Enabled: true,
		VerifyPeer: func(ip netip.Addr) bool {
			return ip == peerIP
		},
		IsSelfIP: func(ip netip.Addr) bool {
			return ip == selfIP
		},
		DialLoopback: func(ctx context.Context, network, address string) (net.Conn, error) {
			attemptMu.Lock()
			attempted = append(attempted, network+":"+address)
			attemptMu.Unlock()
			return baseDialer.DialContext(ctx, network, address)
		},
	})
	defer fwd.Close()

	handler, intercept := fwd.HandleFlow(
		netip.AddrPortFrom(peerIP, 40001),
		netip.AddrPortFrom(selfIP, localPort),
	)
	if !intercept || handler == nil {
		t.Fatalf("expected handled flow, got intercept=%v handler=%v", intercept, handler != nil)
	}

	clientSide, netstackSide := newLoopbackTCPPair(t)
	defer clientSide.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		handler(netstackSide)
	}()

	_, _ = clientSide.Write([]byte("hello-v6"))
	_ = clientSide.CloseWrite()
	got, err := io.ReadAll(clientSide)
	if err != nil {
		t.Fatalf("read fallback response: %v", err)
	}
	if string(got) != "HELLO-V6" {
		t.Fatalf("unexpected fallback response: got %q, want %q", string(got), "HELLO-V6")
	}

	<-done

	attemptMu.Lock()
	defer attemptMu.Unlock()
	if len(attempted) != 2 {
		t.Fatalf("expected 2 dial attempts (127.0.0.1 then [::1]), got %v", attempted)
	}
	if attempted[0][:5] != "tcp4:" || attempted[1][:5] != "tcp6:" {
		t.Fatalf("expected tcp4 -> tcp6 fallback order, got %v", attempted)
	}
}

func TestTailnetLocalForwarderPeerAuthAndTogglePolicy(t *testing.T) {
	selfIP := netip.MustParseAddr("100.64.0.100")
	authedPeer := netip.MustParseAddr("100.64.0.10")
	unknownTailnetIP := netip.MustParseAddr("100.64.0.99")
	publicIP := netip.MustParseAddr("203.0.113.5")
	quad100 := netip.MustParseAddr("100.100.100.100")
	subnetTarget := netip.MustParseAddr("198.51.100.50")

	fwd := newTailnetLocalForwarder(context.Background(), tailnetLocalForwarderOptions{
		Enabled: true,
		VerifyPeer: func(ip netip.Addr) bool {
			return ip == authedPeer
		},
		IsSelfIP: func(ip netip.Addr) bool {
			return ip == selfIP
		},
	})
	defer fwd.Close()

	// 1. Public IP must be rejected with RST (handler == nil, intercept == true)
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(publicIP, 12345), netip.AddrPortFrom(selfIP, 5555)); !ok || h != nil {
		t.Fatalf("expected public IP rejected with RST, got handler=%v intercept=%v", h != nil, ok)
	}

	// 2. Unauthenticated/unknown Tailnet IP must be rejected with RST
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(unknownTailnetIP, 12345), netip.AddrPortFrom(selfIP, 5555)); !ok || h != nil {
		t.Fatalf("expected unauthenticated Tailnet IP rejected with RST, got handler=%v intercept=%v", h != nil, ok)
	}

	// 3. Quad100 destination is never intercepted by local loopback forwarder
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(authedPeer, 12345), netip.AddrPortFrom(quad100, 53)); ok || h != nil {
		t.Fatalf("expected Quad100 not intercepted, got handler=%v intercept=%v", h != nil, ok)
	}

	// 4. Non-self subnet target is not intercepted by local loopback forwarder
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(authedPeer, 12345), netip.AddrPortFrom(subnetTarget, 80)); ok || h != nil {
		t.Fatalf("expected subnet target not intercepted, got handler=%v intercept=%v", h != nil, ok)
	}

	// 5. Port 0 is rejected
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(authedPeer, 12345), netip.AddrPortFrom(selfIP, 0)); !ok || h != nil {
		t.Fatalf("expected port 0 rejected with RST, got handler=%v intercept=%v", h != nil, ok)
	}

	// 6. Disabling toggle rejects even authenticated peers immediately
	fwd.SetEnabled(false)
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(authedPeer, 12345), netip.AddrPortFrom(selfIP, 5555)); !ok || h != nil {
		t.Fatalf("expected disabled forwarder to reject with RST, got handler=%v intercept=%v", h != nil, ok)
	}

	// 7. Re-enabling toggle restores forwarding
	fwd.SetEnabled(true)
	if h, ok := fwd.HandleFlow(netip.AddrPortFrom(authedPeer, 12345), netip.AddrPortFrom(selfIP, 5555)); !ok || h == nil {
		t.Fatalf("expected re-enabled forwarder to accept authenticated peer, got handler=%v intercept=%v", h != nil, ok)
	}
}

func TestTailnetLocalForwarderConcurrencyCapsAndCleanShutdown(t *testing.T) {
	// Local server holds connections open until client/forwarder closes them
	localLn, err := net.Listen("tcp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen loopback: %v", err)
	}
	defer localLn.Close()
	localPort := uint16(localLn.Addr().(*net.TCPAddr).Port)

	var srvConnsMu sync.Mutex
	var srvConns []net.Conn
	go func() {
		for {
			c, err := localLn.Accept()
			if err != nil {
				return
			}
			srvConnsMu.Lock()
			srvConns = append(srvConns, c)
			srvConnsMu.Unlock()
			go func(conn net.Conn) {
				defer conn.Close()
				_, _ = io.Copy(io.Discard, conn)
			}(c)
		}
	}()
	defer func() {
		srvConnsMu.Lock()
		for _, c := range srvConns {
			_ = c.Close()
		}
		srvConnsMu.Unlock()
	}()

	selfIP := netip.MustParseAddr("100.64.0.100")
	peerA := netip.MustParseAddr("100.64.0.11")
	peerB := netip.MustParseAddr("100.64.0.12")

	fwd := newTailnetLocalForwarder(context.Background(), tailnetLocalForwarderOptions{
		Enabled:          true,
		MaxTotalConns:    3,
		MaxPerPeerConns:  2,
		DialTimeout:      2 * time.Second,
		HalfCloseTimeout: 1 * time.Second,
		VerifyPeer: func(ip netip.Addr) bool {
			return ip == peerA || ip == peerB
		},
		IsSelfIP: func(ip netip.Addr) bool {
			return ip == selfIP
		},
	})

	var handlerWG sync.WaitGroup
	var clientConns []*net.TCPConn
	openForwarded := func(peer netip.Addr, sport uint16) bool {
		h, intercept := fwd.HandleFlow(netip.AddrPortFrom(peer, sport), netip.AddrPortFrom(selfIP, localPort))
		if !intercept || h == nil {
			return false
		}
		clientSide, netstackSide := newLoopbackTCPPair(t)
		clientConns = append(clientConns, clientSide)
		handlerWG.Add(1)
		go func() {
			defer handlerWG.Done()
			h(netstackSide)
		}()
		return true
	}
	defer func() {
		for _, c := range clientConns {
			_ = c.Close()
		}
	}()

	waitForActive := func(want int) {
		t.Helper()
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if fwd.ActiveCount() == want {
				return
			}
			time.Sleep(10 * time.Millisecond)
		}
		t.Fatalf("timed out waiting for active count %d (current=%d)", want, fwd.ActiveCount())
	}

	if !openForwarded(peerA, 41001) {
		t.Fatal("peerA #1 should be accepted")
	}
	waitForActive(1)
	if !openForwarded(peerA, 41002) {
		t.Fatal("peerA #2 should be accepted")
	}
	waitForActive(2)

	// PeerA #3 exceeds MaxPerPeerConns (2) -> must be rejected
	if openForwarded(peerA, 41003) {
		t.Fatal("peerA #3 should be rejected by per-peer concurrency cap")
	}

	// PeerB #1 brings total to MaxTotalConns (3) -> must be accepted
	if !openForwarded(peerB, 42001) {
		t.Fatal("peerB #1 should be accepted")
	}
	waitForActive(3)

	// PeerB #2 exceeds MaxTotalConns (3) -> must be rejected
	if openForwarded(peerB, 42002) {
		t.Fatal("peerB #2 should be rejected by global concurrency cap")
	}

	// Closing forwarder must immediately tear down all active bridged connections without leaks
	_ = fwd.Close()

	doneCh := make(chan struct{})
	go func() {
		handlerWG.Wait()
		close(doneCh)
	}()
	select {
	case <-doneCh:
	case <-time.After(3 * time.Second):
		t.Fatal("timed out waiting for active forwarded connections to close on shutdown")
	}

	if active := fwd.ActiveCount(); active != 0 {
		t.Fatalf("expected 0 active connections after Close(), got %d", active)
	}
}
