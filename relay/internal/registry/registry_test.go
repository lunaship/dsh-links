package registry

import (
	"errors"
	"io"
	"sync/atomic"
	"testing"
	"time"
)

type testSender struct {
	closed  atomic.Bool
	revoked atomic.Bool
}

func (s *testSender) SendOpen(string, uint64) error { return nil }
func (s *testSender) NotifyRevoked() error {
	s.revoked.Store(true)
	return nil
}
func (s *testSender) Close() error {
	s.closed.Store(true)
	return nil
}

type testCloser struct{ closed atomic.Bool }

func (c *testCloser) Close() error {
	c.closed.Store(true)
	return nil
}

func testSession(route string, maxStreams int) *AgentSession {
	return &AgentSession{
		RouteIdStr: route,
		Generation: 1,
		MaxStreams: maxStreams,
		Sender:     &testSender{},
		streams:    make(map[string]*PendingStream),
	}
}

func TestReconnectCannotUnregisterReplacement(t *testing.T) {
	r := New(8)
	oldSession := testSession("route", 8)
	if replaced, err := r.Register(oldSession); err != nil || replaced {
		t.Fatalf("first register: replaced=%v err=%v", replaced, err)
	}
	newSession := testSession("route", 8)
	if replaced, err := r.Register(newSession); err != nil || !replaced {
		t.Fatalf("replacement register: replaced=%v err=%v", replaced, err)
	}
	if r.Unregister(oldSession) {
		t.Fatal("old session unregistered its replacement")
	}
	if got, ok := r.Get("route"); !ok || got != newSession {
		t.Fatal("replacement session is not registered")
	}
}

func TestBoundStreamRetainsBudgetsAndRevocationOwnership(t *testing.T) {
	r := New(1)
	sess := testSession("route", 1)
	if _, err := r.Register(sess); err != nil {
		t.Fatal(err)
	}
	pending, err := r.CreatePending("route", "127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	agentConn := &testCloser{}
	bound, err := r.CompleteBind("route", pending.StreamStr, 1, agentConn)
	if err != nil || bound != pending {
		t.Fatalf("bind: %v", err)
	}
	clientConn := &testCloser{}
	if !r.AttachClient(pending, clientConn) {
		t.Fatal("attach client failed")
	}
	if _, err := r.CreatePending("route", "127.0.0.1"); !errors.Is(err, ErrRouteBusy) {
		t.Fatalf("active stream must retain per-route budget, got %v", err)
	}
	other := testSession("other", 1)
	if _, err := r.Register(other); err != nil {
		t.Fatal(err)
	}
	if _, err := r.CreatePending("other", "127.0.0.1"); !errors.Is(err, ErrServerBusy) {
		t.Fatalf("active stream must retain global budget, got %v", err)
	}
	r.Revoke("route")
	if !agentConn.closed.Load() || !clientConn.closed.Load() {
		t.Fatal("revocation did not close both active stream connections")
	}
	if sender, ok := sess.Sender.(*testSender); !ok || !sender.revoked.Load() {
		t.Fatal("revocation did not notify the Agent control connection")
	}
	if got := r.TotalActiveStreams(); got != 0 {
		t.Fatalf("stream budget leaked after revoke: %d", got)
	}
}

func TestEvictStreamsKeepsAgent(t *testing.T) {
	r := New(1)
	sess := testSession("route", 1)
	if _, err := r.Register(sess); err != nil {
		t.Fatal(err)
	}
	pending, err := r.CreatePending("route", "127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	agentConn := &testCloser{}
	if _, err := r.CompleteBind("route", pending.StreamStr, 1, agentConn); err != nil {
		t.Fatal(err)
	}
	clientConn := &testCloser{}
	if !r.AttachClient(pending, clientConn) {
		t.Fatal("attach client failed")
	}
	r.EvictStreams("route")
	if !agentConn.closed.Load() || !clientConn.closed.Load() {
		t.Fatal("hold did not close active stream connections")
	}
	if sender, ok := sess.Sender.(*testSender); !ok || sender.revoked.Load() || sender.closed.Load() {
		t.Fatal("hold must not revoke or close the Agent control connection")
	}
	if got, ok := r.Get("route"); !ok || got != sess {
		t.Fatal("agent must stay registered during a budget hold")
	}
	if _, err := r.CreatePending("route", "127.0.0.1"); err != nil {
		t.Fatalf("agent must accept a new stream after eviction: %v", err)
	}
}

func TestRevocationAfterPublishSignalsBridgeCompletion(t *testing.T) {
	r := New(1)
	sess := testSession("route", 1)
	if _, err := r.Register(sess); err != nil {
		t.Fatal(err)
	}
	pending, err := r.CreatePending("route", "127.0.0.1")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.CompleteBind("route", pending.StreamStr, 1, &testCloser{}); err != nil {
		t.Fatal(err)
	}
	done := make(chan struct{})
	if !r.PublishBridge(pending, &BridgeInfo{Done: done}) {
		t.Fatal("publish bridge failed")
	}

	r.Revoke("route")
	if r.AttachClient(pending, &testCloser{}) {
		t.Fatal("client attached after revocation")
	}
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("BIND completion was not signaled after revocation")
	}
	if got := r.TotalActiveStreams(); got != 0 {
		t.Fatalf("stream budget leaked after revocation: %d", got)
	}
	// The client path releases defensively after every received BridgeInfo.
	// Completion must remain idempotent when revocation won the race.
	r.Release(pending)
}

var _ io.Closer = (*testCloser)(nil)
