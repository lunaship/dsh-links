package registry

import (
	"errors"
	"io"
	"sync/atomic"
	"testing"
)

type testSender struct{ closed atomic.Bool }

func (s *testSender) SendOpen(string, uint64) error { return nil }
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
	if got := r.TotalActiveStreams(); got != 0 {
		t.Fatalf("stream budget leaked after revoke: %d", got)
	}
}

var _ io.Closer = (*testCloser)(nil)
