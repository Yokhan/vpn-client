package hysteria2

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	aTLS "github.com/sagernet/sing/common/tls"
)

type recoveryStandardTLS struct{ config *tls.Config }

func (c *recoveryStandardTLS) ServerName() string           { return c.config.ServerName }
func (c *recoveryStandardTLS) SetServerName(s string)       { c.config.ServerName = s }
func (c *recoveryStandardTLS) NextProtos() []string         { return c.config.NextProtos }
func (c *recoveryStandardTLS) SetNextProtos(p []string)     { c.config.NextProtos = p }
func (c *recoveryStandardTLS) Config() (*tls.Config, error) { return c.config, nil }
func (c *recoveryStandardTLS) Client(n net.Conn) (aTLS.Conn, error) {
	return tls.Client(n, c.config), nil
}
func (c *recoveryStandardTLS) Server(n net.Conn) (aTLS.Conn, error) {
	return tls.Server(n, c.config), nil
}
func (c *recoveryStandardTLS) Clone() aTLS.Config { return &recoveryStandardTLS{c.config.Clone()} }
func (*recoveryStandardTLS) Start() error         { return nil }
func (*recoveryStandardTLS) Close() error         { return nil }

type recoveryLoopbackDialer struct {
	calls    atomic.Int32
	canceled atomic.Int32
	notify   chan struct{}
}

func (d *recoveryLoopbackDialer) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	if network != "udp" || !destination.Addr.IsLoopback() {
		return nil, errors.New("test forbids non-loopback networking")
	}
	d.calls.Add(1)
	if ctx.Err() != nil {
		d.canceled.Add(1)
	}
	if d.notify != nil {
		select {
		case d.notify <- struct{}{}:
		default:
		}
	}
	return (&net.Dialer{}).DialContext(ctx, "udp4", destination.String())
}

func (*recoveryLoopbackDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errors.New("test does not use UDP forwarding")
}

func TestHYRecoveryLoopbackPublicationReuseAndReset(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{SerialNumber: big.NewInt(1),
		Subject: pkix.Name{CommonName: "recovery.invalid"}, DNSNames: []string{"recovery.invalid"},
		NotBefore: time.Now().Add(-time.Minute), NotAfter: time.Now().Add(time.Hour),
		KeyUsage:    x509.KeyUsageDigitalSignature | x509.KeyUsageCertSign,
		ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IsCA: true, BasicConstraintsValid: true}
	der, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	certificate, err := x509.ParseCertificate(der)
	if err != nil {
		t.Fatal(err)
	}
	pool := x509.NewCertPool()
	pool.AddCert(certificate)
	serverTLS := &recoveryStandardTLS{&tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}, NextProtos: []string{"h3"}}}
	clientTLS := &recoveryStandardTLS{&tls.Config{RootCAs: pool, ServerName: "recovery.invalid", NextProtos: []string{"h3"}}}
	base, cancel := context.WithCancel(context.Background())
	defer cancel()
	socket, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer socket.Close()
	service, err := NewService[string](ServiceOptions{Context: base, Logger: logger.NOP(), TLSConfig: serverTLS, UDPDisabled: true})
	if err != nil {
		t.Fatal(err)
	}
	service.UpdateUsers([]string{"local-test"}, []string{"ephemeral-test-only"})
	if err = service.Start(socket); err != nil {
		t.Fatal(err)
	}
	defer service.Close()
	dialer := &recoveryLoopbackDialer{}
	client, err := NewClient(ClientOptions{Context: base, Logger: logger.NOP(), Dialer: dialer,
		TLSConfig: clientTLS, Password: "ephemeral-test-only", UDPDisabled: true,
		ServerAddress: M.ParseSocksaddr(socket.LocalAddr().String())})
	if err != nil {
		t.Fatal(err)
	}
	defer client.CloseWithError(net.ErrClosed)
	ctx, stop := context.WithTimeout(base, 5*time.Second)
	defer stop()
	first, err := client.offer(ctx)
	if err != nil || first == nil || !first.active() {
		t.Fatalf("first offer: %v", err)
	}
	second, err := client.offer(ctx)
	if err != nil || second != first || dialer.calls.Load() != 1 {
		t.Fatalf("connection was not reused: %v", err)
	}
	_ = client.CloseWithError(errors.New("test interface reset"))
	if first.active() {
		t.Fatal("established connection survived explicit reset")
	}
	fresh, err := client.offer(ctx)
	if err != nil || fresh == nil || fresh == first || !fresh.active() || dialer.calls.Load() != 2 {
		t.Fatalf("fresh connection after reset: %v", err)
	}

	// Completing authentication must not cancel subsequent hop socket creation.
	hopDialer := &recoveryLoopbackDialer{notify: make(chan struct{}, 8)}
	address := M.ParseSocksaddr(socket.LocalAddr().String())
	port := strconv.Itoa(int(address.Port))
	hopping, err := NewClient(ClientOptions{Context: base, Logger: logger.NOP(), Dialer: hopDialer,
		TLSConfig: clientTLS.Clone(), Password: "ephemeral-test-only", UDPDisabled: true,
		ServerAddress: address, ServerPorts: []string{port + ":" + port},
		HopInterval: 50 * time.Millisecond})
	if err != nil {
		t.Fatal(err)
	}
	defer hopping.CloseWithError(net.ErrClosed)
	hopConnection, err := hopping.offer(ctx)
	if err != nil || hopConnection == nil || !hopConnection.active() {
		t.Fatalf("hopping authentication: %v", err)
	}
	for hopDialer.calls.Load() < 4 {
		select {
		case <-hopDialer.notify:
		case <-ctx.Done():
			t.Fatal("later hops were not attempted")
		}
	}
	if hopDialer.canceled.Load() != 0 {
		t.Fatal("later hops inherited canceled authentication context")
	}
}
