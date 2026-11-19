package tun

import (
	"net/netip"
	"testing"

	"github.com/sagernet/sing-tun"
)

func TestEnsureHostPrefixDNSFallbackForTailscale32(t *testing.T) {
	// 1. Tailscale /32 IPv4 without IPv6 and without explicit dns_address MUST NOT panic
	//    and MUST resolve MagicDNS 100.100.100.100.
	opts := tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("100.64.0.100/32")},
		AutoRoute:    true,
	}
	ensureHostPrefixDNSFallback(&opts)
	dnsAddrs, err := opts.DNSServerAddress()
	if err != nil {
		t.Fatalf("DNSServerAddress failed on /32 Tailscale prefix: %v", err)
	}
	if len(dnsAddrs) != 1 || dnsAddrs[0] != netip.MustParseAddr("100.100.100.100") {
		t.Fatalf("expected [100.100.100.100], got %v", dnsAddrs)
	}

	// 2. Fallback /30 prefix preserves classic sing-tun next-address derivation (172.19.0.2).
	fallbackOpts := tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("172.19.0.1/30")},
		AutoRoute:    true,
	}
	ensureHostPrefixDNSFallback(&fallbackOpts)
	if len(fallbackOpts.DNSAddress) != 0 {
		t.Fatalf("expected /30 prefix to leave DNSAddress empty, got %v", fallbackOpts.DNSAddress)
	}
	fallbackDNS, err := fallbackOpts.DNSServerAddress()
	if err != nil || len(fallbackDNS) != 1 || fallbackDNS[0] != netip.MustParseAddr("172.19.0.2") {
		t.Fatalf("expected [172.19.0.2], got %v, err=%v", fallbackDNS, err)
	}

	// 3. Dual-stack Tailscale /32 + /128 derives both MagicDNS IPv4 and IPv6 cleanly.
	dualOpts := tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("100.64.0.100/32")},
		Inet6Address: []netip.Prefix{netip.MustParsePrefix("fd7a:115c:a1e0::1234/128")},
		AutoRoute:    true,
	}
	ensureHostPrefixDNSFallback(&dualOpts)
	dualDNS, err := dualOpts.DNSServerAddress()
	if err != nil {
		t.Fatalf("DNSServerAddress failed on dual-stack /32+/128: %v", err)
	}
	if len(dualDNS) != 2 || dualDNS[0] != netip.MustParseAddr("100.100.100.100") || dualDNS[1] != netip.MustParseAddr("fd7a:115c:a1e0::53") {
		t.Fatalf("expected [100.100.100.100 fd7a:115c:a1e0::53], got %v", dualDNS)
	}

	// 4. Explicit DNSAddress is preserved verbatim.
	explicitOpts := tun.Options{
		Inet4Address: []netip.Prefix{netip.MustParsePrefix("100.64.0.100/32")},
		DNSAddress:   []netip.Addr{netip.MustParseAddr("100.100.100.100")},
		AutoRoute:    true,
	}
	ensureHostPrefixDNSFallback(&explicitOpts)
	explicitDNS, err := explicitOpts.DNSServerAddress()
	if err != nil || len(explicitDNS) != 1 || explicitDNS[0] != netip.MustParseAddr("100.100.100.100") {
		t.Fatalf("expected explicit DNS [100.100.100.100], got %v, err=%v", explicitDNS, err)
	}
}
