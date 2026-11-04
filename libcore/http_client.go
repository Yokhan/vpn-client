package libcore

import (
	"context"
	"net"
	"net/http"
	"time"

	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing/common/metadata"
)

func createProxyHTTPClient(boxInstance *box.Box, tracker adapter.ConnectionTracker) *http.Client {
	transport := &http.Transport{
		TLSHandshakeTimeout:   3 * time.Second,
		ResponseHeaderTimeout: 3 * time.Second,
	}
	if boxInstance != nil {
		transport.DialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return dialContext(ctx, boxInstance, tracker, network, addr)
		}
	}
	return &http.Client{Transport: transport}
}

func dialContext(ctx context.Context, boxInstance *box.Box, tracker adapter.ConnectionTracker, network, addr string) (net.Conn, error) {
	_ = tracker
	defaultOutboundTag := boxInstance.Outbound().Default().Tag()
	return dialer.NewDetour(boxInstance.Outbound(), defaultOutboundTag, true).
		DialContext(ctx, network, metadata.ParseSocksaddr(addr))
}
