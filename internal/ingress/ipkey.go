package ingress

import (
	"net"
	"net/netip"
	"strings"
)

// ipKey normalizes a remote IP literal into a rate-limit key.
//
// IPv4 addresses are used verbatim. IPv6 addresses are masked to their
// v6PrefixLen prefix (default 64): without prefix aggregation a caller that
// rotates IPv6 addresses inside one /64 can multiply its per-IP budget by
// 2^64 and drain the global connection pool with ~17 addresses, which is
// exactly the pre-authentication flood the per-IP buckets are meant to stop.
//
// Unparsable inputs (e.g. zone-scoped literals) fall back to the raw string,
// which degrades to today's per-address behavior without ever rejecting.
func ipKey(ip string, v6PrefixLen int) string {
	addr, err := netip.ParseAddr(strings.TrimSpace(ip))
	if err != nil {
		return ip
	}
	if addr.Is4In6() {
		addr = addr.Unmap()
	}
	if !addr.Is6() {
		return addr.String()
	}
	if v6PrefixLen < 1 || v6PrefixLen > 128 {
		return addr.String()
	}
	if p, err := addr.Prefix(v6PrefixLen); err == nil {
		return p.Masked().Addr().String()
	}
	return addr.String()
}

// ipKeyFromConn is a convenience that converts a TCP remote address
// ("host:port" or a bare host) into the rate-limit key.
func ipKeyFromConn(remoteAddr string, v6PrefixLen int) string {
	host := remoteAddr
	if h, _, err := net.SplitHostPort(remoteAddr); err == nil {
		host = h
	}
	return ipKey(host, v6PrefixLen)
}