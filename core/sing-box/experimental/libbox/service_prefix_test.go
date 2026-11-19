package libbox

import (
	"testing"
)

func TestParseInterfacePrefixesNeverPanicsOnMalformedMobileAddresses(t *testing.T) {
	raw := []string{
		"198.51.100.10/24",
		"203.0.113.9/-1",
		"192.0.0.4/128",
		"null/24",
		"",
		"   ",
		"fe80::1234:abcd%wlan0/64",
		"2001:db8:1234::88/64",
		"invalid-cidr",
	}

	got := parseInterfacePrefixes(raw)
	if len(got) != 3 {
		t.Fatalf("expected 3 valid prefixes, got %d: %v", len(got), got)
	}
	if got[0].String() != "198.51.100.10/24" {
		t.Fatalf("unexpected first prefix: %s", got[0])
	}
	if got[1].String() != "fe80::1234:abcd/64" {
		t.Fatalf("expected zone-stripped IPv6 prefix, got %s", got[1])
	}
	if got[2].String() != "2001:db8:1234::88/64" {
		t.Fatalf("unexpected third prefix: %s", got[2])
	}
}
