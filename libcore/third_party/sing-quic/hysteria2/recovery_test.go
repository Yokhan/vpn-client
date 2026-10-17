package hysteria2

import (
	"context"
	"crypto/tls"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	M "github.com/sagernet/sing/common/metadata"
	aTLS "github.com/sagernet/sing/common/tls"
)

var errRecoveryDial = errors.New("controlled dial failure; no socket opened")

type recoveryAttempt struct {
	ctx     context.Context
	release chan struct{}
	once    sync.Once
}

func (a *recoveryAttempt) finish() { a.once.Do(func() { close(a.release) }) }

type recoveryDialer struct {
	ignoreCancel bool
	called       chan *recoveryAttempt
	mu           sync.Mutex
	attempts     []*recoveryAttempt
}

func (d *recoveryDialer) DialContext(ctx context.Context, _ string, _ M.Socksaddr) (net.Conn, error) {
	a := &recoveryAttempt{ctx: ctx, release: make(chan struct{})}
	d.mu.Lock()
	d.attempts = append(d.attempts, a)
	d.mu.Unlock()
	d.called <- a
	if d.ignoreCancel {
		<-a.release
	} else {
		select {
		case <-a.release:
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return nil, errRecoveryDial
}

func (*recoveryDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	return nil, errRecoveryDial
}

// No transport is created: all tests stop inside the controlled dialer.
type recoveryTLS struct{ protos []string }

func (*recoveryTLS) ServerName() string                 { return "invalid.example" }
func (*recoveryTLS) SetServerName(string)               {}
func (c *recoveryTLS) NextProtos() []string             { return c.protos }
func (c *recoveryTLS) SetNextProtos(p []string)         { c.protos = p }
func (*recoveryTLS) Config() (*tls.Config, error)       { return nil, errRecoveryDial }
func (*recoveryTLS) Client(net.Conn) (aTLS.Conn, error) { return nil, errRecoveryDial }
func (c *recoveryTLS) Clone() aTLS.Config               { return &recoveryTLS{protos: c.protos} }

func newRecoveryClient(t *testing.T, ignoreCancel bool) (*Client, *recoveryDialer, context.CancelFunc) {
	t.Helper()
	base, cancel := context.WithCancel(context.Background())
	d := &recoveryDialer{ignoreCancel: ignoreCancel, called: make(chan *recoveryAttempt, 16)}
	c, err := NewClient(ClientOptions{Context: base, Dialer: d,
		TLSConfig: &recoveryTLS{protos: []string{"h3"}}, ServerAddress: M.ParseSocksaddr("127.0.0.1:1")})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = c.CloseWithError(net.ErrClosed)
		cancel()
		d.mu.Lock()
		defer d.mu.Unlock()
		for _, a := range d.attempts {
			a.finish()
		}
	})
	return c, d, cancel
}

func startRecoveryOffer(c *Client, ctx context.Context) <-chan error {
	done := make(chan error, 1)
	go func() { _, err := c.offer(ctx); done <- err }()
	return done
}

func nextRecoveryAttempt(t *testing.T, d *recoveryDialer) *recoveryAttempt {
	t.Helper()
	select {
	case a := <-d.called:
		return a
	case <-time.After(time.Second):
		t.Fatal("dial did not start")
		return nil
	}
}

func expectRecoveryError(t *testing.T, done <-chan error, want error) {
	t.Helper()
	select {
	case got := <-done:
		if !errors.Is(got, want) {
			t.Fatalf("got %v, want %v", got, want)
		}
	case <-time.After(time.Second):
		t.Fatal("caller remained blocked")
	}
}

func TestHYRecoveryQueuedCallerDeadline(t *testing.T) {
	c, d, _ := newRecoveryClient(t, false)
	first := startRecoveryOffer(c, context.Background())
	a := nextRecoveryAttempt(t, d)
	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()
	started := time.Now()
	_, err := c.offer(ctx)
	if !errors.Is(err, context.DeadlineExceeded) || time.Since(started) > 250*time.Millisecond {
		t.Fatalf("caller deadline blocked: elapsed=%v error=%v", time.Since(started), err)
	}
	if a.ctx.Err() != nil {
		t.Fatal("waiting caller canceled the shared dial")
	}
	a.finish()
	expectRecoveryError(t, first, errRecoveryDial)
}

func TestHYRecoveryLeadingCallerCancellation(t *testing.T) {
	c, d, _ := newRecoveryClient(t, false)
	ctx, cancel := context.WithCancel(context.Background())
	first := startRecoveryOffer(c, ctx)
	a := nextRecoveryAttempt(t, d)
	cancel()
	expectRecoveryError(t, first, context.Canceled)
	if a.ctx.Err() != nil {
		t.Fatal("first caller owns the background dial lifetime")
	}
	c.connAccess.Lock()
	pending := c.pending
	c.connAccess.Unlock()
	a.finish()
	select {
	case <-pending.done:
		if !errors.Is(pending.err, errRecoveryDial) {
			t.Fatalf("shared offer result: %v", pending.err)
		}
	case <-time.After(time.Second):
		t.Fatal("shared offer did not finish after caller cancellation")
	}
}

func TestHYRecoveryResetCancelsPending(t *testing.T) {
	c, d, _ := newRecoveryClient(t, false)
	done := startRecoveryOffer(c, context.Background())
	a := nextRecoveryAttempt(t, d)
	reason := errors.New("network generation changed")
	_ = c.CloseWithError(reason)
	expectRecoveryError(t, done, reason)
	if a.ctx.Err() == nil {
		t.Fatal("reset did not cancel pending socket creation")
	}
}

func TestHYRecoveryLateResetCannotClearNewAttempt(t *testing.T) {
	c, d, _ := newRecoveryClient(t, true)
	first := startRecoveryOffer(c, context.Background())
	old := nextRecoveryAttempt(t, d)
	reason := errors.New("old network disappeared")
	_ = c.CloseWithError(reason)
	second := startRecoveryOffer(c, context.Background())
	fresh := nextRecoveryAttempt(t, d)
	c.connAccess.Lock()
	newPending := c.pending
	c.connAccess.Unlock()
	old.finish()
	expectRecoveryError(t, first, reason)
	c.connAccess.Lock()
	preserved := c.pending == newPending && c.conn == nil
	c.connAccess.Unlock()
	if !preserved || fresh.ctx.Err() != nil {
		t.Fatal("old completion changed the new network attempt")
	}
	fresh.finish()
	expectRecoveryError(t, second, errRecoveryDial)
}

func TestHYRecoveryNilResetIsNotSuccess(t *testing.T) {
	c, d, _ := newRecoveryClient(t, false)
	done := startRecoveryOffer(c, context.Background())
	_ = nextRecoveryAttempt(t, d)
	_ = c.CloseWithError(nil)
	expectRecoveryError(t, done, net.ErrClosed)
}

func TestHYRecoveryLifetimeCancellation(t *testing.T) {
	c, d, cancel := newRecoveryClient(t, false)
	first := startRecoveryOffer(c, context.Background())
	_ = nextRecoveryAttempt(t, d)
	second := startRecoveryOffer(c, context.Background())
	cancel()
	expectRecoveryError(t, first, context.Canceled)
	expectRecoveryError(t, second, context.Canceled)
}

func TestHYRecoveryResetReasonRemainsStable(t *testing.T) {
	c, d, _ := newRecoveryClient(t, true)
	done := startRecoveryOffer(c, context.Background())
	a := nextRecoveryAttempt(t, d)
	firstReason := errors.New("first reset")
	_ = c.CloseWithError(firstReason)
	_ = c.CloseWithError(errors.New("later reset"))
	a.finish()
	expectRecoveryError(t, done, firstReason)
}
