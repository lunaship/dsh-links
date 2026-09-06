package ingress

import (
	"crypto/ed25519"
	"crypto/rand"
	"testing"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/testkit"
)

func TestSequentialEcho(t *testing.T) {
	ing, ctrl, cleanup := setupIngress(t)
	defer cleanup()
	clientAddr, agentAddr := getAddrs(ing)

	invite, _ := ctrl.CreateInvite(30 * time.Minute)
	_, priv, _ := ed25519.GenerateKey(rand.Reader)
	hostId := "host-seq"
	echoAddr, closeEcho := testkit.StartEchoServer()
	defer closeEcho()
	agent := testkit.NewSimAgent(agentAddr, hostId, priv, echoAddr)
	if err := agent.Enroll(invite); err != nil {
		t.Fatalf("enroll: %v", err)
	}
	go agent.Register()
	time.Sleep(500 * time.Millisecond)

	for i := 0; i < 3; i++ {
		app := testkit.NewSimApp(clientAddr, agent.RouteId, agent.RouteSecret)
		payload := []byte("seq payload " + string(rune('A'+i)))
		resp, err := app.ConnectAndEcho(payload)
		if err != nil {
			t.Fatalf("seq %d echo failed: %v", i, err)
		}
		if string(resp) != string(payload) {
			t.Fatalf("seq %d mismatch", i)
		}
		t.Logf("seq %d ok", i)
		time.Sleep(200 * time.Millisecond)
	}
	agent.Close()
}
