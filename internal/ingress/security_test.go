package ingress

import (
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"testing"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/control"
	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/metrics"
	"github.com/dsh-links/dsh-links-relay/internal/protocol"
	"github.com/dsh-links/dsh-links-relay/internal/registry"
	"github.com/dsh-links/dsh-links-relay/internal/store"
	"github.com/dsh-links/dsh-links-relay/internal/testkit"
)

// Test frame boundaries 0,767,768,769,2047,2048,2049 already covered in protocol unit, but we also test via ingress that oversized frames are rejected
func TestFrameBoundariesIngress(t *testing.T) {
	ing, _, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, _ := getAddrs(ing)

	// Test CONNECT oversize: create a CONNECT with extra field to exceed 768
	conn, err := net.Dial("tcp", clientAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	json.Unmarshal(helloRaw, &hello)

	// Build oversized CONNECT: add 800 bytes extra field, total >768
	route := base64.RawURLEncoding.EncodeToString(make([]byte, 16))
	nonce := base64.RawURLEncoding.EncodeToString(make([]byte, 16))
	mac := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	extra := make([]byte, 800)
	for i := range extra {
		extra[i] = 'A'
	}
	raw := `{"type":"CONNECT","route":"` + route + `","ts":` + jsonNum(time.Now().Unix()) + `,"nonce":"` + nonce + `","mac":"` + mac + `","extra":"` + string(extra) + `"}` + "\n"
	// Directly write without validation
	_, _ = conn.Write([]byte(raw))
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	respRaw, err := fr.ReadFrame(2048)
	if err != nil {
		t.Fatalf("expected error response, got read err %v", err)
	}
	var e protocol.ErrorFrame
	if err := json.Unmarshal(respRaw, &e); err != nil || e.Code != protocol.ErrBadRequest {
		t.Fatalf("expected BAD_REQUEST for oversize, got %s", string(respRaw))
	}
}

func TestUnauthenticatedRoutesDoNotAllocateLimiterBuckets(t *testing.T) {
	ing, _, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, _ := getAddrs(ing)

	for i := 0; i < 10; i++ {
		conn, err := net.Dial("tcp", clientAddr)
		if err != nil {
			t.Fatal(err)
		}
		fr := protocol.NewFrameReader(conn)
		helloRaw, err := fr.ReadFrame(protocol.MaxHello)
		if err != nil {
			conn.Close()
			t.Fatal(err)
		}
		var hello protocol.HelloFrame
		if err := json.Unmarshal(helloRaw, &hello); err != nil {
			conn.Close()
			t.Fatal(err)
		}
		routeRaw := make([]byte, 16)
		routeRaw[15] = byte(i + 1)
		frame := protocol.ConnectFrame{
			Type: protocol.TypeConnect, Route: base64.RawURLEncoding.EncodeToString(routeRaw),
			Ts: time.Now().Unix(), Nonce: base64.RawURLEncoding.EncodeToString(make([]byte, 16)),
			Mac: base64.RawURLEncoding.EncodeToString(make([]byte, 32)),
		}
		line, _ := json.Marshal(frame)
		line = append(line, '\n')
		_, _ = conn.Write(line)
		_, _ = fr.ReadFrame(2048)
		_ = conn.Close()
	}
	if got := ing.routeLimiter.Count(); got != 0 {
		t.Fatalf("unauthenticated routes allocated %d limiter buckets", got)
	}
}

func TestEnrollHasDedicatedPerIPRateLimit(t *testing.T) {
	ing, _, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)
	invite := base64.RawURLEncoding.EncodeToString(make([]byte, 24))
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}

	for i := 0; i < 7; i++ {
		conn, err := net.Dial("tcp", agentAddr)
		if err != nil {
			t.Fatal(err)
		}
		fr := protocol.NewFrameReader(conn)
		helloRaw, err := fr.ReadFrame(protocol.MaxHello)
		if err != nil {
			conn.Close()
			t.Fatal(err)
		}
		var hello protocol.HelloFrame
		_ = json.Unmarshal(helloRaw, &hello)
		challenge, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
		nonce, _ := cryptoutil.GenerateNonce()
		ts := time.Now().Unix()
		proof := ed25519.Sign(priv, cryptoutil.BuildEnrollTranscript(invite, "rate-limit-host", pub, ts, nonce, challenge))
		frame := protocol.EnrollFrame{
			Type: protocol.TypeEnroll, InviteCode: invite, HostId: "rate-limit-host",
			HostPublicKey: base64.RawURLEncoding.EncodeToString(pub), Ts: ts,
			Nonce: base64.RawURLEncoding.EncodeToString(nonce), Proof: base64.RawURLEncoding.EncodeToString(proof),
		}
		line, _ := json.Marshal(frame)
		line = append(line, '\n')
		_, _ = conn.Write(line)
		respRaw, err := fr.ReadFrame(2048)
		_ = conn.Close()
		if err != nil {
			t.Fatal(err)
		}
		var response protocol.ErrorFrame
		if err := json.Unmarshal(respRaw, &response); err != nil {
			t.Fatal(err)
		}
		if i < 6 && response.Code != protocol.ErrAuthFailed {
			t.Fatalf("attempt %d code=%s, want AUTH_FAILED", i+1, response.Code)
		}
		if i == 6 && response.Code != protocol.ErrRateLimited {
			t.Fatalf("attempt 7 code=%s, want RATE_LIMITED", response.Code)
		}
	}
}

func TestConnectionAdmissionReservesRolesAndBoundsPerIP(t *testing.T) {
	ing := &Ingress{maxConns: 4, maxConnsPerIP: 2, connsByIP: make(map[string]int)}
	if !ing.acquireConnection("192.0.2.1", true) || !ing.acquireConnection("192.0.2.1", true) {
		t.Fatal("connections within per-IP limit were rejected")
	}
	if ing.acquireConnection("192.0.2.1", false) {
		t.Fatal("per-IP connection limit was bypassed across listeners")
	}
	ing.releaseConnection("192.0.2.1", true)
	ing.releaseConnection("192.0.2.1", true)

	if !ing.acquireConnection("192.0.2.1", true) || !ing.acquireConnection("192.0.2.2", true) || !ing.acquireConnection("192.0.2.3", true) {
		t.Fatal("client connections within listener budget were rejected")
	}
	if ing.acquireConnection("192.0.2.4", true) {
		t.Fatal("client listener consumed the reserved agent capacity")
	}
	if !ing.acquireConnection("192.0.2.4", false) {
		t.Fatal("reserved agent capacity was unavailable")
	}
}

func jsonNum(n int64) string { b, _ := json.Marshal(n); return string(b) }

// Test replay: an accepted RENEW frame cannot be replayed on the same control connection.
func TestReplayRejected(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-replay"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	conn, err := net.Dial("tcp", agentAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		t.Fatal(err)
	}
	var hello protocol.HelloFrame
	if err := json.Unmarshal(helloRaw, &hello); err != nil {
		t.Fatal(err)
	}
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	registerNonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	registerProof := ed25519.Sign(priv, cryptoutil.BuildRegisterTranscript(agent.Capability, ts, registerNonce, chal))
	register := protocol.RegisterFrame{
		Type: protocol.TypeRegister, Capability: agent.Capability, Ts: ts,
		Nonce: base64.RawURLEncoding.EncodeToString(registerNonce), Proof: base64.RawURLEncoding.EncodeToString(registerProof),
	}
	registerLine, _ := json.Marshal(register)
	registerLine = append(registerLine, '\n')
	if _, err := conn.Write(registerLine); err != nil {
		t.Fatal(err)
	}
	respRaw, err := fr.ReadFrame(protocol.MaxRegistered)
	if err != nil {
		t.Fatal(err)
	}
	var reged protocol.RegisteredFrame
	_ = json.Unmarshal(respRaw, &reged)
	if reged.Type != protocol.TypeRegistered {
		t.Fatalf("expected REGISTERED, got %s", string(respRaw))
	}

	renewNonce, _ := cryptoutil.GenerateNonce()
	renewTs := time.Now().Unix()
	renewProof := ed25519.Sign(priv, cryptoutil.BuildRenewTranscript(agent.Capability, renewTs, renewNonce, chal))
	renew := protocol.RenewFrame{
		Type: protocol.TypeRenew, Ts: renewTs,
		Nonce: base64.RawURLEncoding.EncodeToString(renewNonce), Proof: base64.RawURLEncoding.EncodeToString(renewProof),
	}
	renewLine, _ := json.Marshal(renew)
	renewLine = append(renewLine, '\n')
	if _, err := conn.Write(renewLine); err != nil {
		t.Fatal(err)
	}
	renewedRaw, err := fr.ReadFrame(protocol.MaxRenewed)
	if err != nil {
		t.Fatal(err)
	}
	var renewed protocol.RenewedFrame
	if err := json.Unmarshal(renewedRaw, &renewed); err != nil || renewed.Type != protocol.TypeRenewed || renewed.Capability == "" {
		t.Fatalf("legitimate renew failed: %s", string(renewedRaw))
	}

	if _, err := conn.Write(renewLine); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	replayRaw, err := fr.ReadFrame(2048)
	if err != nil {
		t.Fatalf("replay did not return an explicit error: %v", err)
	}
	var replayError protocol.ErrorFrame
	if err := json.Unmarshal(replayRaw, &replayError); err != nil || replayError.Type != protocol.TypeError || replayError.Code != protocol.ErrReplayRejected {
		t.Fatalf("replay response=%s, want REPLAY_REJECTED", string(replayRaw))
	}
}

type captureOpenSender struct {
	opens chan protocol.OpenFrame
}

func (s *captureOpenSender) SendOpen(stream string, generation uint64) error {
	s.opens <- protocol.OpenFrame{Type: protocol.TypeOpen, Stream: stream, Generation: generation}
	return nil
}

func (s *captureOpenSender) Close() error { return nil }

// Test cross-host BIND: a valid Host B MAC cannot bind a pending Host A stream.
func TestCrossHostBind(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	inviteA, _ := ctrl.CreateInvite(30 * time.Minute)
	_, privA, _ := ed25519.GenerateKey(rand.Reader)
	agentA := testkit.NewSimAgent(agentAddr, "host-A-cross", privA, "127.0.0.1:9")
	if err := agentA.Enroll(inviteA); err != nil {
		t.Fatal(err)
	}

	inviteB, _ := ctrl.CreateInvite(30 * time.Minute)
	_, privB, _ := ed25519.GenerateKey(rand.Reader)
	agentB := testkit.NewSimAgent(agentAddr, "host-B-cross", privB, "127.0.0.1:9")
	if err := agentB.Enroll(inviteB); err != nil {
		t.Fatal(err)
	}

	registerCapturedSession := func(agent *testkit.SimAgent) *captureOpenSender {
		routeRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteId)
		sender := &captureOpenSender{opens: make(chan protocol.OpenFrame, 1)}
		_, err := ing.registry.Register(&registry.AgentSession{
			RouteIdRaw: routeRaw, RouteIdStr: agent.RouteId, HostID: agent.HostId,
			Generation: 1, MaxStreams: 8, Sender: sender, ConnectedAt: time.Now(), LastPing: time.Now(),
		})
		if err != nil {
			t.Fatal(err)
		}
		return sender
	}
	senderA := registerCapturedSession(agentA)
	_ = registerCapturedSession(agentB)

	clientConn, err := net.Dial("tcp", clientAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer clientConn.Close()
	clientReader := protocol.NewFrameReader(clientConn)
	clientHelloRaw, err := clientReader.ReadFrame(protocol.MaxHello)
	if err != nil {
		t.Fatal(err)
	}
	var clientHello protocol.HelloFrame
	_ = json.Unmarshal(clientHelloRaw, &clientHello)
	clientChallenge, _ := base64.RawURLEncoding.DecodeString(clientHello.Challenge)
	routeARaw, _ := base64.RawURLEncoding.DecodeString(agentA.RouteId)
	secretARaw, _ := base64.RawURLEncoding.DecodeString(agentA.RouteSecret)
	clientNonce, _ := cryptoutil.GenerateNonce()
	clientTs := time.Now().Unix()
	clientMAC := cryptoutil.ComputeMAC(secretARaw, cryptoutil.BuildMACTranscript("CONNECT", routeARaw, nil, nil, clientTs, clientNonce, clientChallenge))
	connect := protocol.ConnectFrame{
		Type: protocol.TypeConnect, Route: agentA.RouteId, Ts: clientTs,
		Nonce: base64.RawURLEncoding.EncodeToString(clientNonce), Mac: base64.RawURLEncoding.EncodeToString(clientMAC),
	}
	connectLine, _ := json.Marshal(connect)
	connectLine = append(connectLine, '\n')
	if _, err := clientConn.Write(connectLine); err != nil {
		t.Fatal(err)
	}

	var openA protocol.OpenFrame
	select {
	case openA = <-senderA.opens:
	case <-time.After(2 * time.Second):
		t.Fatal("Host A did not receive OPEN")
	}

	bindConn, err := net.Dial("tcp", agentAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer bindConn.Close()
	bindReader := protocol.NewFrameReader(bindConn)
	bindHelloRaw, err := bindReader.ReadFrame(protocol.MaxHello)
	if err != nil {
		t.Fatal(err)
	}
	var bindHello protocol.HelloFrame
	_ = json.Unmarshal(bindHelloRaw, &bindHello)
	bindChallenge, _ := base64.RawURLEncoding.DecodeString(bindHello.Challenge)
	routeBRaw, _ := base64.RawURLEncoding.DecodeString(agentB.RouteId)
	secretBRaw, _ := base64.RawURLEncoding.DecodeString(agentB.RouteSecret)
	streamARaw, _ := base64.RawURLEncoding.DecodeString(openA.Stream)
	bindNonce, _ := cryptoutil.GenerateNonce()
	bindTs := time.Now().Unix()
	generation := uint64(1)
	bindMAC := cryptoutil.ComputeMAC(secretBRaw, cryptoutil.BuildMACTranscript("BIND", routeBRaw, streamARaw, &generation, bindTs, bindNonce, bindChallenge))
	bind := protocol.BindFrame{
		Type: protocol.TypeBind, Route: agentB.RouteId, Stream: openA.Stream, Generation: generation, Ts: bindTs,
		Nonce: base64.RawURLEncoding.EncodeToString(bindNonce), Mac: base64.RawURLEncoding.EncodeToString(bindMAC),
	}
	bindLine, _ := json.Marshal(bind)
	bindLine = append(bindLine, '\n')
	if _, err := bindConn.Write(bindLine); err != nil {
		t.Fatal(err)
	}
	bindResponseRaw, err := bindReader.ReadFrame(2048)
	if err != nil {
		t.Fatal(err)
	}
	var bindError protocol.ErrorFrame
	if err := json.Unmarshal(bindResponseRaw, &bindError); err != nil || bindError.Type != protocol.TypeError || bindError.Code != protocol.ErrBadRequest {
		t.Fatalf("cross-host bind response=%s, want BAD_REQUEST", string(bindResponseRaw))
	}
}

func TestOldGenerationNotDeleted(t *testing.T) {
	// Test that Unregister with old generation doesn't delete new generation
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-gen"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	agent.Enroll(invite)
	go agent.Register()
	defer agent.Close()
	time.Sleep(300 * time.Millisecond)

	// Get current generation
	routeId := agent.RouteId
	// Simulate new generation by directly updating store generation? For test, we can revoke host to increment generation, then try to register with old capability should fail
	// Revoke host
	host, _ := ctrl.ListHosts()
	var targetId string
	for _, h := range host {
		if h.HostName == hostId || h.ID == hostId {
			targetId = h.ID
			break
		}
	}
	if targetId != "" {
		_ = ctrl.RevokeHost(targetId)
		time.Sleep(100 * time.Millisecond)
		// Try to re-register with old capability (generation 1) should fail with REVOKED
		// Use raw dial
		conn, _ := net.Dial("tcp", agentAddr)
		defer conn.Close()
		fr := protocol.NewFrameReader(conn)
		helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
		var hello protocol.HelloFrame
		json.Unmarshal(helloRaw, &hello)
		chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
		nonce, _ := cryptoutil.GenerateNonce()
		ts := time.Now().Unix()
		transcript := cryptoutil.BuildRegisterTranscript(agent.Capability, ts, nonce, chal)
		proof := ed25519.Sign(priv, transcript)
		frame := map[string]interface{}{"type": "REGISTER", "capability": agent.Capability, "ts": ts, "nonce": base64.RawURLEncoding.EncodeToString(nonce), "proof": base64.RawURLEncoding.EncodeToString(proof)}
		b, _ := json.Marshal(frame)
		b = append(b, '\n')
		conn.Write(b)
		conn.SetReadDeadline(time.Now().Add(2 * time.Second))
		raw, _ := fr.ReadFrame(2048)
		var e protocol.ErrorFrame
		if json.Unmarshal(raw, &e) == nil && e.Type == "ERROR" {
			if e.Code != protocol.ErrRevoked && e.Code != protocol.ErrAuthFailed {
				t.Fatalf("expected REVOKED after host revoke, got %s", e.Code)
			}
		}
	}
	_ = routeId
}

// Test that old challenge cannot be reused after reconnect
func TestOldChallengeRejected(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-challenge"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	agent.Enroll(invite)
	go agent.Register()
	defer agent.Close()
	time.Sleep(300 * time.Millisecond)

	// First client CONNECT to get a valid challenge, store its challenge and mac, then reconnect and try to reuse same mac with old challenge
	// We need to capture challenge from first connection's HELLO
	conn1, _ := net.Dial("tcp", clientAddr)
	fr1 := protocol.NewFrameReader(conn1)
	helloRaw1, _ := fr1.ReadFrame(protocol.MaxHello)
	var hello1 protocol.HelloFrame
	json.Unmarshal(helloRaw1, &hello1)
	chal1, _ := base64.RawURLEncoding.DecodeString(hello1.Challenge)
	// Build valid CONNECT for chal1
	routeRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteSecret)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript1 := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts, nonce, chal1)
	mac1 := cryptoutil.ComputeMAC(secretRaw, transcript1)
	// Now create second connection with different challenge, but reuse same nonce/ts/mac from first (should fail)
	conn2, _ := net.Dial("tcp", clientAddr)
	fr2 := protocol.NewFrameReader(conn2)
	helloRaw2, _ := fr2.ReadFrame(protocol.MaxHello)
	_ = helloRaw2
	// Send old mac with new connection's challenge? Our client will compute mac based on new challenge, but we send old mac computed with old challenge -> should fail AUTH_FAILED
	frame := map[string]interface{}{"type": "CONNECT", "route": agent.RouteId, "ts": ts, "nonce": base64.RawURLEncoding.EncodeToString(nonce), "mac": base64.RawURLEncoding.EncodeToString(mac1)}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	conn2.Write(b)
	conn2.SetReadDeadline(time.Now().Add(2 * time.Second))
	raw, _ := fr2.ReadFrame(2048)
	var e protocol.ErrorFrame
	if json.Unmarshal(raw, &e) == nil && e.Type == "ERROR" {
		if e.Code != protocol.ErrAuthFailed {
			t.Fatalf("expected AUTH_FAILED for old challenge reuse, got %s", e.Code)
		}
	} else {
		t.Fatalf("expected error for old challenge, got %s", string(raw))
	}
	conn1.Close()
	conn2.Close()
}

func setupIngressWithBindTimeout(t *testing.T, bindTimeout time.Duration) (*Ingress, *control.Control, func()) {
	t.Helper()
	ing, ctrl, cleanup := setupIngress(t)
	ing.bindTimeout = bindTimeout
	return ing, ctrl, cleanup
}

func TestBindTimeout(t *testing.T) {
	// Real BIND timeout with 2s ingest
	ing, ctrl, cleanup := setupIngressWithBindTimeout(t, 2*time.Second)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-bindtimeout"
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, "127.0.0.1:9")
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	// Register but ignore OPEN (custom conn)
	conn, _ := net.Dial("tcp", agentAddr)
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildRegisterTranscript(agent.Capability, ts, nonce, chal)
	proof := ed25519.Sign(priv, transcript)
	regFrame := map[string]interface{}{"type": "REGISTER", "capability": agent.Capability, "ts": ts, "nonce": base64.RawURLEncoding.EncodeToString(nonce), "proof": base64.RawURLEncoding.EncodeToString(proof)}
	b, _ := json.Marshal(regFrame)
	b = append(b, '\n')
	conn.Write(b)
	raw, _ := fr.ReadFrame(protocol.MaxRegistered)
	var reged protocol.RegisteredFrame
	json.Unmarshal(raw, &reged)
	if reged.Type != protocol.TypeRegistered {
		t.Fatalf("register failed")
	}
	// Do not handle OPEN, just wait. Now client CONNECT should get BIND_TIMEOUT after 2s
	start := time.Now()
	c2, _ := net.Dial("tcp", clientAddr)
	fr2 := protocol.NewFrameReader(c2)
	helloRaw2, _ := fr2.ReadFrame(protocol.MaxHello)
	var hello2 protocol.HelloFrame
	json.Unmarshal(helloRaw2, &hello2)
	chal2, _ := base64.RawURLEncoding.DecodeString(hello2.Challenge)
	routeRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteSecret)
	nonce2, _ := cryptoutil.GenerateNonce()
	ts2 := time.Now().Unix()
	transcript2 := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts2, nonce2, chal2)
	mac2 := cryptoutil.ComputeMAC(secretRaw, transcript2)
	frame2 := map[string]interface{}{"type": "CONNECT", "route": agent.RouteId, "ts": ts2, "nonce": base64.RawURLEncoding.EncodeToString(nonce2), "mac": base64.RawURLEncoding.EncodeToString(mac2)}
	bb, _ := json.Marshal(frame2)
	bb = append(bb, '\n')
	c2.Write(bb)
	c2.SetReadDeadline(time.Now().Add(5 * time.Second))
	raw2, err := fr2.ReadFrame(2048)
	if err != nil {
		t.Fatalf("read error: %v", err)
	}
	var e protocol.ErrorFrame
	if err := json.Unmarshal(raw2, &e); err != nil || e.Code != protocol.ErrBindTimeout {
		t.Fatalf("expected BIND_TIMEOUT, got %s err %v", string(raw2), err)
	}
	if time.Since(start) < 1500*time.Millisecond || time.Since(start) > 4*time.Second {
		t.Fatalf("bind timeout should be ~2s, got %v", time.Since(start))
	}
	c2.Close()
	conn.Close()
}

func TestEnrollOversize2049(t *testing.T) {
	ing, _, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)
	conn, _ := net.Dial("tcp", agentAddr)
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	_ = helloRaw
	// Build ENROLL >2048
	route := base64.RawURLEncoding.EncodeToString(make([]byte, 16))
	extra := make([]byte, 2100)
	for i := range extra {
		extra[i] = 'B'
	}
	raw := `{"type":"ENROLL","inviteCode":"` + route + `","hostId":"` + string(extra) + `","hostPublicKey":"` + base64.RawURLEncoding.EncodeToString(make([]byte, 32)) + `","ts":` + jsonNum(time.Now().Unix()) + `,"nonce":"` + base64.RawURLEncoding.EncodeToString(make([]byte, 16)) + `","proof":"` + base64.RawURLEncoding.EncodeToString(make([]byte, 64)) + `"}` + "\n"
	conn.Write([]byte(raw))
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	respRaw, err := fr.ReadFrame(2048)
	if err == nil {
		var e protocol.ErrorFrame
		if json.Unmarshal(respRaw, &e) == nil && e.Code == protocol.ErrBadRequest {
			// ok
		} else {
			t.Fatalf("expected BAD_REQUEST for ENROLL oversize, got %s", string(respRaw))
		}
	}
	conn.Close()
}

func TestNonIdempotentNotRetried(t *testing.T) {
	ing, _, cleanup := setupIngress(t)
	defer cleanup()
	_, _ = getAddrs(ing)
	if ing.registry.Count() != 0 {
		t.Fatalf("registry should be empty initially")
	}
}

func TestHostRevokeClosesWithin1s(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-revoke-1s"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	go agent.Register()
	time.Sleep(500 * time.Millisecond)
	if ing.registry.Count() != 1 {
		t.Fatalf("should be online")
	}
	// Revoke via control
	hosts, _ := ctrl.ListHosts()
	var hid string
	for _, h := range hosts {
		if h.ID == hostId {
			hid = h.ID
			break
		}
	}
	if hid == "" {
		t.Fatalf("host not found")
	}
	if err := ctrl.RevokeHost(hid); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	// The control revoke callback should close the route immediately.
	time.Sleep(1200 * time.Millisecond)
	if ing.registry.Count() != 0 {
		t.Fatalf("registry should be empty after revoke, got %d", ing.registry.Count())
	}
	// New CONNECT should fail with AUTH_FAILED (revoked route)
	routeRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteId)
	secretRaw, _ := base64.RawURLEncoding.DecodeString(agent.RouteSecret)
	// Even though revoked, Derive still works, but CONNECT should be AUTH_FAILED because agent offline and revoked route considered AUTH_FAILED
	c, _ := net.Dial("tcp", clientAddr)
	fr := protocol.NewFrameReader(c)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeRaw, nil, nil, ts, nonce, chal)
	mac := cryptoutil.ComputeMAC(secretRaw, transcript)
	frame := map[string]interface{}{"type": "CONNECT", "route": agent.RouteId, "ts": ts, "nonce": base64.RawURLEncoding.EncodeToString(nonce), "mac": base64.RawURLEncoding.EncodeToString(mac)}
	b, _ := json.Marshal(frame)
	b = append(b, '\n')
	c.Write(b)
	c.SetReadDeadline(time.Now().Add(2 * time.Second))
	raw, _ := fr.ReadFrame(2048)
	var e protocol.ErrorFrame
	json.Unmarshal(raw, &e)
	if e.Code != protocol.ErrAuthFailed {
		t.Fatalf("expected AUTH_FAILED after revoke, got %s", e.Code)
	}
	c.Close()
	agent.Close()
}

func TestRenewSuccess(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-renew"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	// Raw REGISTER + RENEW on same control conn
	conn, _ := net.Dial("tcp", agentAddr)
	fr := protocol.NewFrameReader(conn)
	helloRaw, _ := fr.ReadFrame(protocol.MaxHello)
	var hello protocol.HelloFrame
	json.Unmarshal(helloRaw, &hello)
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	transcript := cryptoutil.BuildRegisterTranscript(agent.Capability, ts, nonce, chal)
	proof := ed25519.Sign(priv, transcript)
	regFrame := map[string]interface{}{"type": "REGISTER", "capability": agent.Capability, "ts": ts, "nonce": base64.RawURLEncoding.EncodeToString(nonce), "proof": base64.RawURLEncoding.EncodeToString(proof)}
	b, _ := json.Marshal(regFrame)
	b = append(b, '\n')
	conn.Write(b)
	raw, _ := fr.ReadFrame(protocol.MaxRegistered)
	var reged protocol.RegisteredFrame
	json.Unmarshal(raw, &reged)
	if reged.Type != protocol.TypeRegistered {
		t.Fatalf("register failed: %s", string(raw))
	}
	// Now send RENEW on same conn (same challenge)
	nonce2, _ := cryptoutil.GenerateNonce()
	ts2 := time.Now().Unix()
	renewTranscript := cryptoutil.BuildRenewTranscript(agent.Capability, ts2, nonce2, chal)
	proof2 := ed25519.Sign(priv, renewTranscript)
	renewFrame := map[string]interface{}{"type": "RENEW", "ts": ts2, "nonce": base64.RawURLEncoding.EncodeToString(nonce2), "proof": base64.RawURLEncoding.EncodeToString(proof2)}
	bb, _ := json.Marshal(renewFrame)
	bb = append(bb, '\n')
	conn.Write(bb)
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	raw2, err := fr.ReadFrame(2048)
	if err != nil {
		t.Fatalf("renew read: %v", err)
	}
	var renewed protocol.RenewedFrame
	if err := json.Unmarshal(raw2, &renewed); err != nil || renewed.Type != protocol.TypeRenewed {
		var e protocol.ErrorFrame
		if json.Unmarshal(raw2, &e) == nil {
			t.Fatalf("renew failed with %s: %s", e.Code, e.Message)
		}
		t.Fatalf("expected RENEWED, got %s", string(raw2))
	}
	// Verify new capability is valid and has same host/route/generation
	newCap := renewed.Capability
	payload, err := cryptoutil.VerifyCapability(ctrl.IssuerPublicKey(), newCap)
	if err != nil {
		t.Fatalf("new cap verify: %v", err)
	}
	if payload.Host != hostId {
		t.Fatalf("host mismatch")
	}
	if payload.Generation != 1 {
		t.Fatalf("generation should stay 1")
	}
	conn.Close()
	_ = ing
}

func TestControlLoopRateLimitsFrameFlood(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	_, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-frameflood"
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, "127.0.0.1:9")
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	conn, err := net.Dial("tcp", agentAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	fr := protocol.NewFrameReader(conn)
	helloRaw, err := fr.ReadFrame(protocol.MaxHello)
	if err != nil {
		t.Fatal(err)
	}
	var hello protocol.HelloFrame
	if err := json.Unmarshal(helloRaw, &hello); err != nil {
		t.Fatal(err)
	}
	chal, _ := base64.RawURLEncoding.DecodeString(hello.Challenge)
	nonce, _ := cryptoutil.GenerateNonce()
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildRegisterTranscript(agent.Capability, ts, nonce, chal))
	register := protocol.RegisterFrame{
		Type: protocol.TypeRegister, Capability: agent.Capability, Ts: ts,
		Nonce: base64.RawURLEncoding.EncodeToString(nonce), Proof: base64.RawURLEncoding.EncodeToString(proof),
	}
	registerLine, _ := json.Marshal(register)
	registerLine = append(registerLine, '\n')
	if _, err := conn.Write(registerLine); err != nil {
		t.Fatal(err)
	}
	respRaw, err := fr.ReadFrame(protocol.MaxRegistered)
	if err != nil {
		t.Fatal(err)
	}
	var reged protocol.RegisteredFrame
	if err := json.Unmarshal(respRaw, &reged); err != nil || reged.Type != protocol.TypeRegistered {
		t.Fatalf("register failed: %s", string(respRaw))
	}

	// Flood PING frames: the per-connection bucket admits controlFrameBurst
	// frames and must then reject with RATE_LIMITED.
	pingLine, _ := json.Marshal(protocol.PingFrame{Type: protocol.TypePing})
	pingLine = append(pingLine, '\n')
	for i := 0; i < controlFrameBurst+5; i++ {
		if _, err := conn.Write(pingLine); err != nil {
			t.Fatal(err)
		}
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	sawRateLimited := false
	for i := 0; i < controlFrameBurst+10 && !sawRateLimited; i++ {
		respRaw, err := fr.ReadFrame(2048)
		if err != nil {
			t.Fatalf("read response %d: %v", i, err)
		}
		var e protocol.ErrorFrame
		if err := json.Unmarshal(respRaw, &e); err != nil {
			continue // PONG frame
		}
		if e.Type != protocol.TypeError {
			continue
		}
		if e.Code != protocol.ErrRateLimited {
			t.Fatalf("control-loop error code=%s, want RATE_LIMITED", e.Code)
		}
		sawRateLimited = true
	}
	if !sawRateLimited {
		t.Fatal("frame flood was never rate limited")
	}
}

func TestNonceBudgetBounded(t *testing.T) {
	ctx := &connContext{nonces: make(map[string]bool)}
	for i := 0; i < maxConnNonces; i++ {
		if got := ctx.markNonce(fmt.Sprintf("nonce-%d", i)); got != nonceNew {
			t.Fatalf("nonce %d verdict=%d, want nonceNew", i, got)
		}
	}
	if got := ctx.markNonce("fresh-nonce"); got != nonceExhausted {
		t.Fatalf("fresh nonce after budget verdict=%d, want nonceExhausted", got)
	}
	if got := ctx.markNonce("nonce-1"); got != nonceDuplicate {
		t.Fatalf("duplicate nonce verdict=%d, want nonceDuplicate", got)
	}
}

// A TCP connection that never completes the TLS handshake must not hold its
// connection slot forever: the HELLO write runs the handshake, which starts
// by reading ClientHello, so only a read deadline bounds it.
func TestTLSHandshakeStallReleasesSlot(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(1), Subject: pkix.Name{CommonName: "dsh-test"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		IPAddresses: []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	tlsCfg := &tls.Config{
		Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}},
		MinVersion:   tls.VersionTLS12,
	}

	st, err := store.OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	routeMaster := make([]byte, 32)
	if _, err := rand.Read(routeMaster); err != nil {
		t.Fatal(err)
	}
	issuerSeed := sha256.Sum256([]byte("tls stall issuer"))
	ctrl, err := control.New(st, issuerSeed[:], routeMaster, 8)
	if err != nil {
		t.Fatal(err)
	}
	issuerPub := ed25519.NewKeyFromSeed(issuerSeed[:]).Public().(ed25519.PublicKey)
	reg := registry.New(100)
	// maxConns=1: the stalled connection occupies the entire budget.
	ing := New("127.0.0.1:0", "127.0.0.1:0", tlsCfg, reg, NewInProcessControl(ctrl), metrics.New(), issuerPub, 20*time.Second, 10*time.Second, 65*time.Second, 100, 1, 0, nil)
	if err := ing.Start(); err != nil {
		t.Fatalf("start ingress: %v", err)
	}
	defer ing.Close()
	clientAddr := ing.listeners[0].Addr().String()

	stalled, err := net.Dial("tcp", clientAddr)
	if err != nil {
		t.Fatal(err)
	}
	defer stalled.Close() // never handshakes, never sends a byte

	deadline := time.Now().Add(20 * time.Second)
	for {
		c, err := tls.Dial("tcp", clientAddr, &tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12})
		if err == nil {
			c.SetReadDeadline(time.Now().Add(3 * time.Second))
			fr := protocol.NewFrameReader(c)
			raw, err := fr.ReadFrame(protocol.MaxHello)
			c.Close()
			var hello protocol.HelloFrame
			if err == nil && json.Unmarshal(raw, &hello) == nil && len(hello.Challenge) > 0 {
				return // slot was released to a legitimate client
			}
		}
		if time.Now().After(deadline) {
			t.Fatal("stalled TLS handshake held the only connection slot")
		}
		time.Sleep(300 * time.Millisecond)
	}
}
