package bridge

import (
	"io"
	"net"
	"testing"
	"time"
)

func TestUnidirectionalTrafficDoesNotIdleOppositeSide(t *testing.T) {
	client, agent := net.Pipe()
	defer client.Close()
	defer agent.Close()

	done := make(chan error, 1)
	go func() {
		done <- BridgeWithTimeouts(client, agent, nil, nil, 0, 80*time.Millisecond, 200*time.Millisecond)
	}()

	payload := []byte("sse-data\n")
	deadline := time.Now().Add(300 * time.Millisecond)
	for time.Now().Before(deadline) {
		if _, err := agent.Write(payload); err != nil {
			t.Fatalf("unidirectional write: %v", err)
		}
		buf := make([]byte, len(payload))
		if _, err := io.ReadFull(client, buf); err != nil {
			t.Fatalf("unidirectional read: %v", err)
		}
		time.Sleep(15 * time.Millisecond)
	}

	select {
	case err := <-done:
		t.Fatalf("bridge closed while downstream was active: %v", err)
	case <-time.After(40 * time.Millisecond):
	}

	_ = client.Close()
	_ = agent.Close()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("bridge did not exit after close")
	}
}

func TestBidirectionalIdleCloses(t *testing.T) {
	client, agent := net.Pipe()
	defer client.Close()
	defer agent.Close()

	done := make(chan error, 1)
	go func() {
		done <- BridgeWithTimeouts(client, agent, nil, nil, 0, 60*time.Millisecond, 30*time.Millisecond)
	}()

	select {
	case <-done:
	case <-time.After(400 * time.Millisecond):
		t.Fatal("idle bridge was not reclaimed")
	}
}

func TestSlowWriteStillHasDeadline(t *testing.T) {
	client, agent := net.Pipe()
	defer client.Close()
	defer agent.Close()

	done := make(chan error, 1)
	go func() {
		done <- BridgeWithTimeouts(client, agent, nil, nil, 0, time.Second, 40*time.Millisecond)
	}()

	block := make([]byte, 32*1024)
	_, _ = agent.Write(block)

	select {
	case err := <-done:
		if err == nil {
			t.Fatal("expected write timeout or close, got nil")
		}
	case <-time.After(time.Second):
		t.Fatal("slow write did not unblock")
	}
}

func TestUplinkOnlyTrafficAlsoKeepsBridge(t *testing.T) {
	client, agent := net.Pipe()
	defer client.Close()
	defer agent.Close()

	done := make(chan error, 1)
	go func() {
		done <- BridgeWithTimeouts(client, agent, nil, nil, 0, 80*time.Millisecond, 200*time.Millisecond)
	}()

	payload := []byte("ping")
	deadline := time.Now().Add(250 * time.Millisecond)
	for time.Now().Before(deadline) {
		if _, err := client.Write(payload); err != nil {
			t.Fatalf("uplink write: %v", err)
		}
		buf := make([]byte, len(payload))
		if _, err := io.ReadFull(agent, buf); err != nil {
			t.Fatalf("uplink read: %v", err)
		}
		time.Sleep(15 * time.Millisecond)
	}

	select {
	case err := <-done:
		t.Fatalf("bridge closed while uplink was active: %v", err)
	default:
	}
	_ = client.Close()
	_ = agent.Close()
	<-done
}
