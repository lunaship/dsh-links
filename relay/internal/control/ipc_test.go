package control

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
)

func TestIPCClientKeepsIdleConnectionAndReceivesResponsesAndPushes(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "0123456789abcdef0123456789abcdef")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	client := NewIPCClient(socket, "0123456789abcdef0123456789abcdef")
	push := make(chan string, 1)
	client.SetRevokeFn(func(routeID, _ string) { push <- routeID })
	if err := client.Connect(); err != nil {
		t.Fatal(err)
	}
	client.StartReconnect()
	defer client.Close()

	// The previous implementation destroyed every healthy idle connection after
	// its 500 ms read deadline. Keep this connection idle beyond that boundary.
	time.Sleep(750 * time.Millisecond)
	if got := server.BroadcastRevoke("route-push", "host-push"); got != 1 {
		t.Fatalf("broadcast reached %d clients, want 1", got)
	}
	select {
	case got := <-push:
		if got != "route-push" {
			t.Fatalf("push route=%q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("revoke push not received")
	}

	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	nonce, _ := cryptoutil.RandomBytes(16)
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildEnrollTranscript(invite, "ipc-host", pub, ts, nonce, challenge))
	resp, err := client.Enroll(EnrollIPCRequest{
		InviteCode:    invite,
		HostId:        "ipc-host",
		HostPublicKey: base64.RawURLEncoding.EncodeToString(pub),
		Ts:            ts,
		Nonce:         base64.RawURLEncoding.EncodeToString(nonce),
		Proof:         base64.RawURLEncoding.EncodeToString(proof),
		Challenge:     base64.RawURLEncoding.EncodeToString(challenge),
	})
	if err != nil {
		t.Fatalf("enroll over IPC: %v", err)
	}
	if resp.RouteId == "" || resp.RouteSecret == "" || resp.Capability == "" {
		t.Fatalf("incomplete enroll response: %+v", resp)
	}
	routeID, _ := base64.RawURLEncoding.DecodeString(resp.RouteId)
	routeSecret, _ := base64.RawURLEncoding.DecodeString(resp.RouteSecret)
	lookup, err := client.LookupHostByRoute(routeID)
	if err != nil {
		t.Fatalf("lookup over IPC: %v", err)
	}
	if lookup.HostID != "ipc-host" || lookup.Revoked || lookup.Generation != 1 {
		t.Fatalf("unexpected lookup response: %+v", lookup)
	}
	connectNonce, _ := cryptoutil.RandomBytes(16)
	connectChallenge, _ := cryptoutil.RandomBytes(32)
	connectTs := time.Now().Unix()
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeID, nil, nil, connectTs, connectNonce, connectChallenge)
	mac := cryptoutil.ComputeMAC(routeSecret, transcript)
	macReq := VerifyRouteMACIPCRequest{
		Operation: "CONNECT", RouteID: resp.RouteId, Ts: connectTs,
		Nonce:     base64.RawURLEncoding.EncodeToString(connectNonce),
		Challenge: base64.RawURLEncoding.EncodeToString(connectChallenge),
		MAC:       base64.RawURLEncoding.EncodeToString(mac),
	}
	if err := client.VerifyRouteMAC(macReq); err != nil {
		t.Fatalf("valid route MAC rejected over IPC: %v", err)
	}
	macReq.MAC = base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	if err := client.VerifyRouteMAC(macReq); err == nil {
		t.Fatal("invalid route MAC accepted over IPC")
	}
}

func TestIPCServerRejectsOversizedPostAuthFrame(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-frame-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	conn, err := net.Dial("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if _, err := conn.Write([]byte(strings.Repeat("x", maxIPCFrameBytes+1) + "\n")); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	buf := make([]byte, 1)
	if _, err := conn.Read(buf); err == nil {
		t.Fatal("oversized IPC frame did not close the connection")
	}
}

func TestIPCServerCapsConcurrentClients(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-cap-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	connections := make([]net.Conn, 0, maxIPCConnections)
	defer func() {
		for _, conn := range connections {
			_ = conn.Close()
		}
	}()
	for i := 0; i < maxIPCConnections; i++ {
		conn, err := net.Dial("unix", socket)
		if err != nil {
			t.Fatal(err)
		}
		connections = append(connections, conn)
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		server.mu.Lock()
		count := len(server.connections)
		server.mu.Unlock()
		if count == maxIPCConnections {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("server tracked %d connections, want %d", count, maxIPCConnections)
		}
		time.Sleep(10 * time.Millisecond)
	}

	extra, err := net.Dial("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer extra.Close()
	_ = extra.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := extra.Write([]byte("x")); err != nil {
		return // immediate rejection is also acceptable
	}
	buf := make([]byte, 1)
	if _, err := extra.Read(buf); err == nil {
		t.Fatal("connection above IPC client cap remained open")
	}
}

func TestIPCServerCapsUnauthenticatedPeersSeparately(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-preauth-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "0123456789abcdef0123456789abcdef")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	connections := make([]net.Conn, 0, maxIPCPreAuthConnections)
	defer func() {
		for _, conn := range connections {
			_ = conn.Close()
		}
	}()
	for i := 0; i < maxIPCPreAuthConnections; i++ {
		conn, err := net.Dial("unix", socket)
		if err != nil {
			t.Fatal(err)
		}
		connections = append(connections, conn)
	}
	deadline := time.Now().Add(2 * time.Second)
	for {
		server.mu.Lock()
		count := server.preAuthConnections
		server.mu.Unlock()
		if count == maxIPCPreAuthConnections {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("server tracked %d pre-auth peers, want %d", count, maxIPCPreAuthConnections)
		}
		time.Sleep(10 * time.Millisecond)
	}
	extra, err := net.Dial("unix", socket)
	if err != nil {
		t.Fatal(err)
	}
	defer extra.Close()
	_ = extra.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := extra.Write([]byte("x")); err != nil {
		return
	}
	buf := make([]byte, 1)
	if _, err := extra.Read(buf); err == nil {
		t.Fatal("connection above pre-auth IPC cap remained open")
	}
}

func TestIPCWritesAreIsolatedPerConnection(t *testing.T) {
	server := &IPCServer{writers: make(map[net.Conn]*sync.Mutex)}
	blockedServer, blockedClient := net.Pipe()
	defer blockedServer.Close()
	defer blockedClient.Close()
	healthyServer, healthyClient := net.Pipe()
	defer healthyServer.Close()
	defer healthyClient.Close()
	server.writers[blockedServer] = &sync.Mutex{}
	server.writers[healthyServer] = &sync.Mutex{}

	blockedStarted := make(chan struct{})
	go func() {
		close(blockedStarted)
		server.writeIPC(blockedServer, []byte("blocked\n"))
	}()
	<-blockedStarted
	time.Sleep(20 * time.Millisecond)

	healthyRead := make(chan error, 1)
	go func() {
		buf := make([]byte, len("healthy\n"))
		_, err := io.ReadFull(healthyClient, buf)
		healthyRead <- err
	}()
	if !server.writeIPC(healthyServer, []byte("healthy\n")) {
		t.Fatal("healthy connection write failed while another peer was blocked")
	}
	select {
	case err := <-healthyRead:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("blocked peer serialized an unrelated IPC write")
	}
	_ = blockedClient.Close()
}

func TestIPCClientReconnectsAfterServerRestart(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	token := "0123456789abcdef0123456789abcdef"
	server := NewIPCServer(ctrl, socket, token)
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	client := NewIPCClient(socket, token)
	if err := client.Connect(); err != nil {
		t.Fatal(err)
	}
	client.StartReconnect()
	defer client.Close()
	if err := server.Close(); err != nil {
		t.Fatal(err)
	}

	restarted := NewIPCServer(ctrl, socket, token)
	if err := restarted.Start(); err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if restarted.BroadcastRevoke("after-restart", "host") == 1 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("client did not reconnect after server restart")
}

// TestIPCServerPushesRevocationSetOnConnect verifies that a relay connecting
// (or reconnecting) after a revocation is told about it immediately, so a
// control restart or a missed broadcast converges without waiting for the
// relay's slow best-effort poll.
func TestIPCServerPushesRevocationSetOnConnect(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-revsync-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	opts := "0123456789abcdef0123456789abcdef"
	server := NewIPCServer(ctrl, socket, opts)
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	// Enroll a host, then revoke it, all before any relay is connected.
	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	req := enrollRequest(t, invite, "rev-sync-host", priv)
	enrolled, err := ctrl.Enroll(req)
	if err != nil {
		t.Fatal(err)
	}
	routeB64 := base64.RawURLEncoding.EncodeToString(enrolled.RouteId)
	if _, _, err := ctrl.RevokeHost("rev-sync-host"); err != nil {
		t.Fatal(err)
	}

	// A fresh relay connecting after the revocation must be pushed the set.
	client := NewIPCClient(socket, opts)
	push := make(chan string, 4)
	client.SetRevokeFn(func(routeID, _ string) { push <- routeID })
	if err := client.Connect(); err != nil {
		t.Fatal(err)
	}
	client.StartReconnect()
	defer client.Close()

	select {
	case got := <-push:
		if got != routeB64 {
			t.Fatalf("sync pushed route=%q want %q", got, routeB64)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("revocation set not pushed to freshly connected relay")
	}
}

// The relay confirms each revoke_notify with revoke_acked after applying it
// locally, so BroadcastRevokeAndWait can report a provable ack count.
func TestRevokeAckRoundTrip(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-ack-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	client := NewIPCClient(socket, "")
	push := make(chan string, 1)
	client.SetRevokeFn(func(routeID, _ string) {
		// Non-blocking: the waitFor probe re-broadcasts while this channel may
		// still hold a previous value, and a blocked fn would stall the ack.
		select {
		case push <- routeID:
		default:
		}
	})
	if err := client.Connect(); err != nil {
		t.Fatal(err)
	}
	client.StartReconnect()
	defer client.Close()

	// Wait for the server goroutine to register this connection in its
	// clients set after the auth frame (Connect returns before that).
	waitFor := func(want int) {
		t.Helper()
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			delivered, acked := server.BroadcastRevokeAndWait("route-ack-1", "host-ack-1", 200*time.Millisecond)
			if delivered == want && acked == want {
				return
			}
			time.Sleep(30 * time.Millisecond)
		}
	}
	waitFor(1)

	// The client is connected: broadcast must be delivered and acked within 1s.
	delivered, acked := server.BroadcastRevokeAndWait("route-ack-1", "host-ack-1", time.Second)
	if delivered != 1 || acked != 1 {
		t.Fatalf("delivered=%d acked=%d, want both 1 (connected relay)", delivered, acked)
	}
	select {
	case got := <-push:
		if got != "route-ack-1" {
			t.Fatalf("push route=%q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("revoke push not received")
	}

	// No clients connected: nothing is delivered and nothing can be acked.
	client.Close()
	time.Sleep(50 * time.Millisecond)
	delivered, acked = server.BroadcastRevokeAndWait("route-ack-2", "host-ack-2", 200*time.Millisecond)
	if delivered != 0 || acked != 0 {
		t.Fatalf("disconnected: delivered=%d acked=%d, want 0/0", delivered, acked)
	}
}

// An ack for the wrong route does not count toward the revoked route.
func TestRevokeAckMismatchedRouteNotCounted(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	tempDir, err := os.MkdirTemp("", "dlr-ipc-ack2-")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(tempDir)
	socket := filepath.Join(tempDir, "control.sock")
	server := NewIPCServer(ctrl, socket, "")
	if err := server.Start(); err != nil {
		t.Fatal(err)
	}
	defer server.Close()

	client := NewIPCClient(socket, "")
	client.SetRevokeFn(func(routeID, _ string) {
		// Reply with a mismatched route on purpose (peer bug or misuse).
		ack := IPCMessage{Type: "revoke_acked", Payload: jsonRaw(`{"routeId":"other-route","hostId":"x"}`)}
		line, _ := json.Marshal(ack)
		line = append(line, '\n')
		client.writeMu.Lock()
		_, _ = client.conn.Write(line)
		client.writeMu.Unlock()
	})
	if err := client.Connect(); err != nil {
		t.Fatal(err)
	}
	client.StartReconnect()
	defer client.Close()

	time.Sleep(100 * time.Millisecond)
	delivered, acked := server.BroadcastRevokeAndWait("route-ack-3", "host-ack-3", 300*time.Millisecond)
	if delivered != 1 || acked != 0 {
		t.Fatalf("delivered=%d acked=%d, want 1/0 (mismatched ack ignored)", delivered, acked)
	}
}

func jsonRaw(s string) json.RawMessage { return json.RawMessage(s) }
