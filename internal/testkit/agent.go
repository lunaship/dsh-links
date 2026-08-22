package testkit

import (
	"bufio"
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/protocol"
)

// SimAgent simulates a Relay Agent
type SimAgent struct {
	AgentAddr   string
	HostId      string
	Priv        ed25519.PrivateKey
	Pub         ed25519.PublicKey
	RouteId     string // b64u
	RouteSecret string // b64u
	Capability  string
	Generation  uint64

	// Echo target (where agent bridges BIND to)
	EchoAddr string // e.g., "127.0.0.1:0" for ephemeral echo server

	// control conn
	mu         sync.Mutex
	ctrlConn   net.Conn
	ctrlReader *protocol.FrameReader
	challenge  []byte

	// for test control
	StopCh   chan struct{}
	stopOnce sync.Once
	ErrCh    chan error

	// dial timeouts
	DialTimeout time.Duration
}

func NewSimAgent(agentAddr, hostId string, priv ed25519.PrivateKey, echoAddr string) *SimAgent {
	pub := priv.Public().(ed25519.PublicKey)
	return &SimAgent{
		AgentAddr:   agentAddr,
		HostId:      hostId,
		Priv:        priv,
		Pub:         pub,
		EchoAddr:    echoAddr,
		StopCh:      make(chan struct{}),
		ErrCh:       make(chan error, 1),
		DialTimeout: 5 * time.Second,
	}
}

// Enroll performs ENROLL and stores result. Returns routeId/secret/cap.
func (a *SimAgent) Enroll(inviteCode string) error {
	conn, err := net.DialTimeout("tcp", a.AgentAddr, a.DialTimeout)
	if err != nil {
		return err
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(5 * time.Second))
	fr := protocol.NewFrameReader(conn)
	// read HELLO
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		return fmt.Errorf("read hello: %w", err)
	}
	var hello protocol.HelloFrame
	if err := json.Unmarshal(helloRaw, &hello); err != nil {
		return err
	}
	chal, err := base64.RawURLEncoding.DecodeString(hello.Challenge)
	if err != nil {
		return err
	}
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildEnrollTranscript(inviteCode, a.HostId, a.Pub, ts, nonce, chal)
	proof := ed25519.Sign(a.Priv, transcript)
	frame := protocol.EnrollFrame{
		Type:          protocol.TypeEnroll,
		InviteCode:    inviteCode,
		HostId:        a.HostId,
		HostPublicKey: base64.RawURLEncoding.EncodeToString(a.Pub),
		Ts:            ts,
		Nonce:         base64.RawURLEncoding.EncodeToString(nonce),
		Proof:         base64.RawURLEncoding.EncodeToString(proof),
	}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	if _, err := conn.Write(b); err != nil {
		return err
	}
	// read ENROLLED
	enrolledRaw, err := fr.ReadFrame(protocol.MaxEnrolled)
	if err != nil {
		return fmt.Errorf("read enrolled: %w", err)
	}
	var enrolled protocol.EnrolledFrame
	if err := json.Unmarshal(enrolledRaw, &enrolled); err != nil {
		// maybe error frame
		var e protocol.ErrorFrame
		if json.Unmarshal(enrolledRaw, &e) == nil && e.Type == "ERROR" {
			return fmt.Errorf("enroll error %s: %s", e.Code, e.Message)
		}
		return err
	}
	if enrolled.Type != protocol.TypeEnrolled {
		return fmt.Errorf("expected ENROLLED got %s", enrolled.Type)
	}
	a.RouteId = enrolled.RouteId
	a.RouteSecret = enrolled.RouteSecret
	a.Capability = enrolled.Capability
	a.Generation = enrolled.Generation
	return nil
}

// Register connects control channel and handles OPEN/BIND loop. Blocks until StopCh.
func (a *SimAgent) Register() error {
	conn, err := net.DialTimeout("tcp", a.AgentAddr, a.DialTimeout)
	if err != nil {
		return err
	}
	// Don't close immediately; keep for life
	a.mu.Lock()
	a.ctrlConn = conn
	fr := protocol.NewFrameReader(conn)
	a.ctrlReader = fr
	a.mu.Unlock()
	// HELLO
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		conn.Close()
		return fmt.Errorf("hello: %w", err)
	}
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	a.challenge = chal

	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildRegisterTranscript(a.Capability, ts, nonce, chal)
	proof := ed25519.Sign(a.Priv, transcript)
	reg := protocol.RegisterFrame{
		Type:       protocol.TypeRegister,
		Capability: a.Capability,
		Ts:         ts,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Proof:      base64.RawURLEncoding.EncodeToString(proof),
	}
	b, _ := json.Marshal(reg)
	b = append(b, '\n')
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(b); err != nil {
		conn.Close()
		return err
	}
	// wait REGISTERED
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	regedRaw, err := fr.ReadFrame(protocol.MaxRegistered)
	if err != nil {
		conn.Close()
		return fmt.Errorf("registered: %w", err)
	}
	var reged protocol.RegisteredFrame
	if err := json.Unmarshal(regedRaw, &reged); err != nil {
		var e protocol.ErrorFrame
		if json.Unmarshal(regedRaw, &e) == nil && e.Type == "ERROR" {
			conn.Close()
			return fmt.Errorf("register error %s", e.Code)
		}
		conn.Close()
		return err
	}
	if reged.Type != protocol.TypeRegistered {
		conn.Close()
		return fmt.Errorf("expected REGISTERED")
	}

	// Enter loop handling OPEN and heartbeats
	// Send PING periodically
	go func() {
		ticker := time.NewTicker(20 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-a.StopCh:
				return
			case <-ticker.C:
				ping := protocol.PingFrame{Type: protocol.TypePing}
				b, _ := json.Marshal(ping)
				b = append(b, '\n')
				_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
				_, _ = conn.Write(b)
			}
		}
	}()

	for {
		select {
		case <-a.StopCh:
			conn.Close()
			return nil
		default:
		}
		_ = conn.SetReadDeadline(time.Now().Add(65 * time.Second))
		raw, err := fr.ReadFrame(2048)
		if err != nil {
			if err == io.EOF {
				return nil
			}
			return err
		}
		typ, _ := protocol.DetectType(raw)
		switch typ {
		case protocol.TypeOpen:
			var open protocol.OpenFrame
			_ = json.Unmarshal(raw, &open)
			// Must BIND within 10s using new connection
			go a.handleOpen(open)
		case protocol.TypePing:
			// reply PONG
			pong := protocol.PongFrame{Type: protocol.TypePong}
			b, _ := json.Marshal(pong)
			b = append(b, '\n')
			_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			_, _ = conn.Write(b)
		case protocol.TypePong:
			// ignore
		case protocol.TypeError:
			var e protocol.ErrorFrame
			_ = json.Unmarshal(raw, &e)
			return fmt.Errorf("control error %s", e.Code)
		default:
			// unknown
		}
	}
}

func (a *SimAgent) handleOpen(open protocol.OpenFrame) {
	// New connection for BIND
	conn, err := net.DialTimeout("tcp", a.AgentAddr, a.DialTimeout)
	if err != nil {
		return
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		return
	}
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)

	routeIdRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteId)
	routeSecretRaw, _ := base64.RawURLEncoding.DecodeString(a.RouteSecret)
	streamRaw, _ := base64.RawURLEncoding.DecodeString(open.Stream)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("BIND", routeIdRaw, streamRaw, &open.Generation, ts, nonce, chal)
	mac := cryptoutil.ComputeMAC(routeSecretRaw, transcript)
	bind := protocol.BindFrame{
		Type:       protocol.TypeBind,
		Route:      a.RouteId,
		Stream:     open.Stream,
		Generation: open.Generation,
		Ts:         ts,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Mac:        base64.RawURLEncoding.EncodeToString(mac),
	}
	b, _ := json.Marshal(bind)
	b = append(b, '\n')
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(b); err != nil {
		return
	}
	// Wait READY
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	readyRaw, err := fr.ReadFrame(protocol.MaxReady)
	if err != nil {
		return
	}
	var ready protocol.ReadyFrame
	_ = json.Unmarshal(readyRaw, &ready)
	if ready.Type != protocol.TypeReady {
		// maybe error
		return
	}
	// After READY, bridge to echo target
	// Drained buffered bytes? For test, no
	carry := fr.DrainedBuffered()
	// Dial echo target
	echoConn, err := net.DialTimeout("tcp", a.EchoAddr, 5*time.Second)
	if err != nil {
		return
	}
	defer echoConn.Close()
	if len(carry) > 0 {
		_, _ = echoConn.Write(carry)
	}
	// Bridge bidirectional
	bridgeBidirectional(conn, echoConn, fr)
}

func bridgeBidirectional(a, b net.Conn, aFR *protocol.FrameReader) {
	// Capture any buffered bytes after READY already handled via carry
	// Use simple io.Copy both directions with 32k buf and 5min idle timeout handled elsewhere
	// For testkit, we just copy without idle timeout, using goroutines
	done := make(chan struct{}, 2)
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 32*1024)
		for {
			_ = a.SetReadDeadline(time.Now().Add(5 * time.Minute))
			n, err := a.Read(buf)
			if n > 0 {
				_ = b.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, werr := b.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	go func() {
		defer func() { done <- struct{}{} }()
		buf := make([]byte, 32*1024)
		for {
			_ = b.SetReadDeadline(time.Now().Add(5 * time.Minute))
			n, err := b.Read(buf)
			if n > 0 {
				_ = a.SetWriteDeadline(time.Now().Add(5 * time.Second))
				if _, werr := a.Write(buf[:n]); werr != nil {
					return
				}
			}
			if err != nil {
				return
			}
		}
	}()
	<-done
	_ = a.Close()
	_ = b.Close()
	<-done
}

func (a *SimAgent) Close() {
	a.stopOnce.Do(func() { close(a.StopCh) })
	a.mu.Lock()
	if a.ctrlConn != nil {
		_ = a.ctrlConn.Close()
	}
	a.mu.Unlock()
}

// For tests that need to send bad proof
func (a *SimAgent) RegisterWithBadProof() error {
	conn, err := net.DialTimeout("tcp", a.AgentAddr, 5*time.Second)
	if err != nil {
		return err
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	_ = json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	// Use wrong proof (random)
	proof := make([]byte, 64)
	badCap := a.Capability + "bad"
	_ = chal
	_ = cryptoutil.BuildRegisterTranscript
	transcript := cryptoutil.BuildRegisterTranscript(badCap, ts, nonce, chal)
	_ = transcript
	// Sign with same priv but over bad transcript? Or sign wrong bytes
	badProof := make([]byte, 64)
	copy(badProof, proof)
	reg := protocol.RegisterFrame{
		Type:       protocol.TypeRegister,
		Capability: a.Capability,
		Ts:         ts,
		Nonce:      base64.RawURLEncoding.EncodeToString(nonce),
		Proof:      base64.RawURLEncoding.EncodeToString(badProof),
	}
	b, _ := json.Marshal(reg)
	b = append(b, '\n')
	_, _ = conn.Write(b)
	raw, err := fr.ReadFrame(2048)
	if err != nil {
		return err
	}
	var e protocol.ErrorFrame
	if err := json.Unmarshal(raw, &e); err == nil && e.Type == "ERROR" {
		return fmt.Errorf("error %s", e.Code)
	}
	return fmt.Errorf("expected error but got %s", string(raw))
}

func (a *SimAgent) GetRouteId() string     { return a.RouteId }
func (a *SimAgent) GetRouteSecret() string { return a.RouteSecret }

// StartEchoServer creates a TCP echo server that echos back whatever it receives.
func StartEchoServer() (addr string, closeFn func()) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		panic(err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				_, _ = io.Copy(c, c)
			}(conn)
		}
	}()
	return ln.Addr().String(), func() { ln.Close() }
}

// StartTLSEcho simulates inner TLS? For test we just use plain echo.
func StartBufferedEcho() (net.Listener, string) {
	ln, _ := net.Listen("tcp", "127.0.0.1:0")
	return ln, ln.Addr().String()
}

var _ = bufio.NewReader
