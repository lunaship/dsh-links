package ingress

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"strings"
	"testing"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/control"
	"github.com/dsh-links/dsh-links-relay/internal/metrics"
	"github.com/dsh-links/dsh-links-relay/internal/registry"
	"github.com/dsh-links/dsh-links-relay/internal/store"
	"github.com/dsh-links/dsh-links-relay/internal/testkit"
)

func setupIngress(t *testing.T) (*Ingress, *control.Control, func()) {
	t.Helper()
	st, err := store.OpenMemory()
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	routeMaster := make([]byte, 32)
	if _, err := rand.Read(routeMaster); err != nil {
		t.Fatal(err)
	}
	issuerSeed := sha256.Sum256([]byte("test issuer seed for ingress"))
	ctrl, err := control.New(st, issuerSeed[:], routeMaster, 8)
	if err != nil {
		t.Fatalf("new control: %v", err)
	}
	issuerPub := ed25519.NewKeyFromSeed(issuerSeed[:]).Public().(ed25519.PublicKey)
	reg := registry.New(1000)
	m := metrics.New()
	ctrl.SetRevokeFn(func(routeID, _ string) (int, int) { reg.Revoke(routeID); return 1, 1 })
	ing := New("127.0.0.1:0", "127.0.0.1:0", nil, reg, NewInProcessControl(ctrl), m, issuerPub, 20*time.Second, 10*time.Second, 65*time.Second, 1000, 2000, 64, 0, nil)
	if err := ing.Start(); err != nil {
		t.Fatalf("start ingress: %v", err)
	}
	cleanup := func() {
		ing.Close()
		st.Close()
	}
	t.Logf("client %s agent %s", ing.listeners[0].Addr().String(), ing.listeners[1].Addr().String())
	return ing, ctrl, cleanup
}

func getAddrs(ing *Ingress) (string, string) {
	return ing.listeners[0].Addr().String(), ing.listeners[1].Addr().String()
}

func TestIntegrationEcho(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, err := ctrl.CreateInvite(30 * time.Minute)
	if err != nil {
		t.Fatalf("create invite: %v", err)
	}
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-test-1"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()

	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	go func() {
		if err := agent.Register(); err != nil {
			t.Logf("agent register exited: %v", err)
		}
	}()
	time.Sleep(500 * time.Millisecond)

	app := testkit.NewSimApp(clientAddr, agent.RouteId, agent.RouteSecret)
	payload := []byte("hello relay echo test marker 12345")
	resp, err := app.ConnectAndEcho(payload)
	if err != nil {
		t.Fatalf("echo: %v", err)
	}
	if string(resp) != string(payload) {
		t.Fatalf("echo mismatch: got %q want %q", resp, payload)
	}
	agent.Close()
	time.Sleep(200 * time.Millisecond)
}

func TestIntegrationBadMAC(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-badmac"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	go agent.Register()
	time.Sleep(300 * time.Millisecond)

	app := testkit.NewSimApp(clientAddr, agent.RouteId, agent.RouteSecret)
	if err := app.ConnectWithTamperedMAC(); err != nil {
		t.Fatalf("expected tampered to be rejected but got err=%v", err)
	}
	agent.Close()
	time.Sleep(200 * time.Millisecond)
}

func TestIntegrationAgentOffline(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)
	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	agent := testkit.NewSimAgent(agentAddr, "host-offline", priv, "127.0.0.1:9")
	if err := agent.Enroll(invite); err != nil {
		t.Fatal(err)
	}
	app := testkit.NewSimApp(clientAddr, agent.RouteId, agent.RouteSecret)
	if err := app.ConnectExpectAgentOffline(); err != nil {
		t.Fatalf("expected offline: %v", err)
	}
}

func TestTwoHostsConcurrent(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	inviteA, _ := ctrl.CreateInvite(30 * time.Minute)
	_, privA, _ := ed25519.GenerateKey(rand.Reader)
	hostA := "host-A"
	echoA, closeA := testkit.StartEchoServer()
	defer closeA()
	agentA := testkit.NewSimAgent(agentAddr, hostA, privA, echoA)
	if err := agentA.Enroll(inviteA); err != nil {
		t.Fatalf("enroll A: %v", err)
	}
	go agentA.Register()

	inviteB, _ := ctrl.CreateInvite(30 * time.Minute)
	_, privB, _ := ed25519.GenerateKey(rand.Reader)
	hostB := "host-B"
	echoB, closeB := testkit.StartEchoServer()
	defer closeB()
	agentB := testkit.NewSimAgent(agentAddr, hostB, privB, echoB)
	if err := agentB.Enroll(inviteB); err != nil {
		t.Fatalf("enroll B: %v", err)
	}
	go agentB.Register()

	time.Sleep(500 * time.Millisecond)

	payloadA := []byte("payload for A unique marker")
	payloadB := []byte("payload for B unique marker different")

	errCh := make(chan error, 2)
	go func() {
		app := testkit.NewSimApp(clientAddr, agentA.RouteId, agentA.RouteSecret)
		resp, err := app.ConnectAndEcho(payloadA)
		if err != nil {
			errCh <- err
			return
		}
		if string(resp) != string(payloadA) {
			errCh <- &testError{"A mismatch"}
			return
		}
		errCh <- nil
	}()
	go func() {
		app := testkit.NewSimApp(clientAddr, agentB.RouteId, agentB.RouteSecret)
		resp, err := app.ConnectAndEcho(payloadB)
		if err != nil {
			errCh <- err
			return
		}
		if string(resp) != string(payloadB) {
			errCh <- &testError{"B mismatch"}
			return
		}
		errCh <- nil
	}()

	for i := 0; i < 2; i++ {
		if err := <-errCh; err != nil {
			t.Fatalf("concurrent echo failed: %v", err)
		}
	}
	agentA.Close()
	agentB.Close()
	time.Sleep(200 * time.Millisecond)
}

type testError struct{ s string }

func (e *testError) Error() string { return e.s }

// Route byte/connect counters flow to control's stats_daily through the
// periodic stats_report flush.
func TestStatsFlushReachesControl(t *testing.T) {
	_, ctrl, cleanup := setupIngress(t)
	// Shorten the flush interval for the test; the reporter reads it when the
	// ticker is created, so a new ingress is started with the short value.
	cleanup()
	st, err := store.OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	routeMaster := make([]byte, 32)
	if _, err := rand.Read(routeMaster); err != nil {
		t.Fatal(err)
	}
	issuerSeed := sha256.Sum256([]byte("stats test issuer"))
	ctrl, err = control.New(st, issuerSeed[:], routeMaster, 8)
	if err != nil {
		t.Fatal(err)
	}
	issuerPub := ed25519.NewKeyFromSeed(issuerSeed[:]).Public().(ed25519.PublicKey)
	reg := registry.New(1000)
	ctrl.SetRevokeFn(func(routeID, _ string) (int, int) { reg.Revoke(routeID); return 1, 1 })
	ing := New("127.0.0.1:0", "127.0.0.1:0", nil, reg, NewInProcessControl(ctrl), metrics.New(), issuerPub, 20*time.Second, 10*time.Second, 65*time.Second, 1000, 2000, 64, 0, nil)
	ing.statsInterval = 100 * time.Millisecond
	if err := ing.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { ing.Close(); st.Close() }()
	clientAddr, agentAddr := getAddrs(ing)

	invite, err := ctrl.CreateInvite(30 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, "stats-host-1", priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatal(err)
	}
	go func() { _ = agent.Register() }()
	time.Sleep(400 * time.Millisecond)

	app := testkit.NewSimApp(clientAddr, agent.RouteId, agent.RouteSecret)
	payload := []byte("stats flush marker payload 4096 bytes " + strings.Repeat("x", 4000))
	if _, err := app.ConnectAndEcho(payload); err != nil {
		t.Fatalf("echo: %v", err)
	}
	agent.Close()

	// Wait for a flush cycle to land in stats_daily.
	deadline := time.Now().Add(3 * time.Second)
	for {
		u, err := st.GetDailyUsage("stats-host-1", store.Today())
		if err != nil {
			t.Fatal(err)
		}
		if u.RXBytes > 0 && u.TXBytes > 0 && u.ConnectCount > 0 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("usage never landed: %+v (err %v)", u, err)
		}
		time.Sleep(50 * time.Millisecond)
	}
	u, _ := st.GetDailyUsage("stats-host-1", store.Today())
	if u.RXBytes < int64(len(payload)) || u.TXBytes < int64(len(payload)) {
		t.Fatalf("usage too small: %+v payload=%d", u, len(payload))
	}
}
