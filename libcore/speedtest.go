package libcore

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptrace"
	"time"
)

const (
	urlTestStandardRTT           = 0
	urlTestStandardHandshake     = 1
	urlTestStandardFirstHandshake = 2
)

var errNoRedirect = errors.New("no redirect")

// urlTest is the local replacement for the Matsuri speedtest helper.
func urlTest(client *http.Client, link string, timeout int32, standard int) (int32, error) {
	if client == nil {
		return 0, fmt.Errorf("no client")
	}
	defer client.CloseIdleConnections()

	client.CheckRedirect = func(req *http.Request, via []*http.Request) error {
		return errNoRedirect
	}

	var timeStart time.Time
	var handshakeEnd time.Time
	var timeEnd time.Time
	var times int

	switch standard {
	case urlTestStandardFirstHandshake:
		times = 1
	case urlTestStandardHandshake:
		times = 2
		if transport, ok := client.Transport.(*http.Transport); ok {
			transport.DisableKeepAlives = true
		}
	case urlTestStandardRTT:
		times = 2
	default:
		return 0, errors.New("unknown urltest standard")
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}

	trace := &httptrace.ClientTrace{
		TLSHandshakeDone: func(cs tls.ConnectionState, err error) {
			handshakeEnd = time.Now()
		},
		GotFirstResponseByte: func() {
			timeEnd = time.Now()
		},
		WroteHeaders: func() {
			handshakeEnd = time.Now()
		},
	}
	req = req.WithContext(httptrace.WithClientTrace(req.Context(), trace))

	for i := 0; i < times; i++ {
		timeStart = time.Now()
		resp, err := client.Do(req)
		if err != nil {
			if errors.Is(err, errNoRedirect) {
				err = nil
			} else {
				return 0, err
			}
		}
		if resp != nil {
			_ = resp.Body.Close()
		}
	}

	if timeEnd.IsZero() {
		timeEnd = time.Now()
	}
	if standard == urlTestStandardRTT {
		timeStart = handshakeEnd
	}
	return int32(timeEnd.Sub(timeStart).Milliseconds()), nil
}

func tcpPing(address string, timeout int32) (int32, error) {
	startTime := time.Now()
	conn, err := net.DialTimeout("tcp", address, time.Duration(timeout)*time.Millisecond)
	endTime := time.Now()
	if err != nil {
		return 0, err
	}
	_ = conn.Close()
	return int32(endTime.Sub(startTime).Milliseconds()), nil
}
