package control

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"io"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
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
