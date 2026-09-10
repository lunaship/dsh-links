package testkit

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
	"github.com/lunaship/dsh-links/relay/internal/protocol"
)

// SimApp simulates mobile app
type SimApp struct {
	ClientAddr  string
	RouteId     string
	RouteSecret string
}

func NewSimApp(clientAddr, routeId, routeSecret string) *SimApp {
	return &SimApp{ClientAddr: clientAddr, RouteId: routeId, RouteSecret: routeSecret}
}

// ConnectAndEcho performs CONNECT, waits READY, then sends payload and expects echo.
func (a *SimApp) ConnectAndEcho(payload []byte) ([]byte, error) {
	conn, err := net.DialTimeout("tcp", a.ClientAddr, 5*time.Second)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	// HELLO
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		return nil, fmt.Errorf("hello: %w", err)
	}
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)

	routeRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteSecret)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts, nonce, chal)
	mac := cryptoutil.ComputeMAC(secretRaw, transcript)

	frame := protocol.ConnectFrame{
		Type:  protocol.TypeConnect,
		Route: a.RouteId,
		Ts:    ts,
		Nonce: base64.RawURLEncoding.EncodeToString(nonce),
		Mac:   base64.RawURLEncoding.EncodeToString(mac),
	}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(b); err != nil {
		return nil, err
	}
	// Wait READY or ERROR
	conn.SetReadDeadline(time.Now().Add(10 * time.Second))
	raw, err := fr.ReadFrame(protocol.MaxReady)
	if err != nil {
		return nil, fmt.Errorf("ready: %w", err)
	}
	var ready protocol.ReadyFrame
	if err := json.Unmarshal(raw, &ready); err != nil {
		var e protocol.ErrorFrame
		if json.Unmarshal(raw, &e) == nil && e.Type == "ERROR" {
			return nil, fmt.Errorf("error %s: %s", e.Code, e.Message)
		}
		return nil, err
	}
	if ready.Type != protocol.TypeReady {
		return nil, fmt.Errorf("expected READY got %s", ready.Type)
	}
	// Carry bytes? None
	_ = fr.DrainedBuffered()
	// Now bridge: send payload, read echo
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(payload); err != nil {
		return nil, err
	}
	// Set read deadline for echo
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, len(payload))
	total := 0
	for total < len(payload) {
		n, err := conn.Read(buf[total:])
		if err != nil {
			return nil, fmt.Errorf("echo read: %w", err)
		}
		total += n
	}
	return buf, nil
}

// ConnectWithTamperedMAC tries to connect with bad MAC for negative tests
func (a *SimApp) ConnectWithTamperedMAC() error {
	conn, err := net.DialTimeout("tcp", a.ClientAddr, 5*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	routeRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteSecret)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts, nonce, chal)
	mac := cryptoutil.ComputeMAC(secretRaw, transcript)
	// tamper
	mac[0] ^= 0xFF
	frame := protocol.ConnectFrame{
		Type:  protocol.TypeConnect,
		Route: a.RouteId,
		Ts:    ts,
		Nonce: base64.RawURLEncoding.EncodeToString(nonce),
		Mac:   base64.RawURLEncoding.EncodeToString(mac),
	}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	_, _ = conn.Write(b)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	raw, err := fr.ReadFrame(2048)
	if err != nil {
		return err
	}
	var e protocol.ErrorFrame
	if json.Unmarshal(raw, &e) == nil && e.Type == "ERROR" {
		if e.Code == protocol.ErrAuthFailed {
			return nil // expected
		}
		return fmt.Errorf("unexpected error code %s", e.Code)
	}
	return fmt.Errorf("expected AUTH_FAILED but got %s", string(raw))
}

// ConnectAndCloseWithoutBind tests BIND timeout: connects but agent not binding? Actually agent will bind, but we can test client cancel
func (a *SimApp) ConnectExpectAgentOffline() error {
	return a.ConnectExpectCode(protocol.ErrAgentOffline)
}

func (a *SimApp) ConnectExpectCode(code string) error {
	conn, err := net.DialTimeout("tcp", a.ClientAddr, 5*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	routeRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteSecret)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts, nonce, chal)
	mac := cryptoutil.ComputeMAC(secretRaw, transcript)
	frame := protocol.ConnectFrame{
		Type:  protocol.TypeConnect,
		Route: a.RouteId,
		Ts:    ts,
		Nonce: base64.RawURLEncoding.EncodeToString(nonce),
		Mac:   base64.RawURLEncoding.EncodeToString(mac),
	}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	_, _ = conn.Write(b)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	raw, _ := fr.ReadFrame(2048)
	var e protocol.ErrorFrame
	if json.Unmarshal(raw, &e) == nil && e.Type == "ERROR" {
		if e.Code == code {
			return nil
		}
		return fmt.Errorf("expected %s got %s", code, e.Code)
	}
	return fmt.Errorf("expected %s", code)
}

// Replay test: send same CONNECT twice on same connection? Actually CONNECT is per connection, need to test replay within same connection is impossible (only one frame). But we can test replay across connections using same nonce/challenge? Challenge changes per connection, so replay with same nonce but different challenge should still be rejected? Spec says replay within connection/window nonce duplicate. For cross-connection, challenge changes, so MAC will differ. To test replay, we send same nonce+ts+mac with same challenge on same connection - but we only send one CONNECT per conn, so replay test is for BIND/CONNECT nonce reuse on same conn (duplicate frame). We'll implement a test that sends two CONNECT frames sequentially on same conn (protocol violation) and expects REPLAY.
// Simpler: test that second CONNECT with same nonce on same conn after first CONNECT? But first CONNECT already transitions to pending; we won't get second frame chance. So we test that ingress rejects duplicate nonce within same BIND connection.
