package tun

import (
	"net/netip"

	"github.com/sagernet/sing-tun"
)

var (
	singScaleCGNATPrefix     = netip.MustParsePrefix("100.64.0.0/10")
	singScaleMagicDNSV4      = netip.MustParseAddr("100.100.100.100")
	singScaleFallbackDNSV4   = netip.MustParseAddr("172.19.0.2")
	singScaleTailscaleULA    = netip.MustParsePrefix("fd7a:115c:a1e0::/48")
	singScaleMagicDNSV6      = netip.MustParseAddr("fd7a:115c:a1e0::53")
	singScaleFallbackDNSV6   = netip.MustParseAddr("fdfe:dcba:9876::2")
)

// ensureHostPrefixDNSFallback prevents sing-tun's Inet4DNSAddress/Inet6DNSAddress from
// failing or panicking on single-host prefixes (such as Tailscale 100.x.y.z/32 or fd7a:.../128)
// when no explicit dns_address was configured.
func ensureHostPrefixDNSFallback(opts *tun.Options) {
	if opts == nil || len(opts.DNSAddress) > 0 {
		return
	}
	v4NeedsFallback := len(opts.Inet4Address) > 0 && !tun.HasNextAddress(opts.Inet4Address[0], 1)
	v6NeedsFallback := len(opts.Inet6Address) > 0 && !tun.HasNextAddress(opts.Inet6Address[0], 1)
	if !v4NeedsFallback && !v6NeedsFallback {
		return
	}

	var resolved []netip.Addr
	if len(opts.Inet4Address) > 0 {
		p4 := opts.Inet4Address[0]
		switch {
		case tun.HasNextAddress(p4, 1):
			resolved = append(resolved, p4.Addr().Next())
		case singScaleCGNATPrefix.Contains(p4.Addr()):
			resolved = append(resolved, singScaleMagicDNSV4)
		default:
			resolved = append(resolved, singScaleFallbackDNSV4)
		}
	}
	if len(opts.Inet6Address) > 0 {
		p6 := opts.Inet6Address[0]
		switch {
		case tun.HasNextAddress(p6, 1):
			resolved = append(resolved, p6.Addr().Next())
		case singScaleTailscaleULA.Contains(p6.Addr()):
			resolved = append(resolved, singScaleMagicDNSV6)
		default:
			resolved = append(resolved, singScaleFallbackDNSV6)
		}
	}
	if len(resolved) > 0 {
		opts.DNSAddress = resolved
	}
}
