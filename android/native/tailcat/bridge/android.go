//go:build android

// Package bridge drives tailcat from the phone. On Android, an app's UID may not enumerate the
// network interfaces, so net.Interfaces fails and tailcat's netmon cannot start; this file
// registers the same synthetic-interface fallback tailscale's androidbin feature uses for raw
// binaries, which that feature skips in cgo builds (the ones an app links).
package bridge

import (
	"errors"
	"net"
	"net/netip"
	"time"

	"tailscale.com/feature"
	"tailscale.com/net/netmon"
)

func init() {
	if !feature.Register("androidbin") {
		return
	}
	netmon.HookInterfacesFallback.Set(fallbackInterfaces)
}

func fallbackInterfaces() ([]netmon.Interface, error) {
	return syntheticInterface()
}

// syntheticInterface is one made-up interface carrying the source addresses the kernel picks for
// outbound sockets: dialing UDP sends no packet and needs no permission.
func syntheticInterface() ([]netmon.Interface, error) {
	var addrs []net.Addr
	if ip, ok := outboundIP("udp4", "8.8.8.8:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(32, 32)})
	}
	if ip, ok := outboundIP("udp6", "[2001:4860:4860::8888]:53"); ok {
		addrs = append(addrs, &net.IPNet{IP: ip.AsSlice(), Mask: net.CIDRMask(128, 128)})
	}
	if len(addrs) == 0 {
		return nil, errors.New("tailcat: no outbound routes found")
	}
	return []netmon.Interface{{
		Interface: &net.Interface{
			MTU:   1500,
			Name:  "android",
			Flags: net.FlagUp | net.FlagRunning,
		},
		AltAddrs: addrs,
	}}, nil
}

func outboundIP(network, addr string) (netip.Addr, bool) {
	d := net.Dialer{Timeout: 2 * time.Second}
	c, err := d.Dial(network, addr)
	if err != nil {
		return netip.Addr{}, false
	}
	defer c.Close()
	ap, ok := c.LocalAddr().(*net.UDPAddr)
	if !ok {
		return netip.Addr{}, false
	}
	ip, ok := netip.AddrFromSlice(ap.IP)
	if !ok {
		return netip.Addr{}, false
	}
	return ip.Unmap(), true
}
