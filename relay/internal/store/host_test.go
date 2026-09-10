package store

import (
	"testing"
	"time"
)

func TestUpdateHostHeartbeatByRoute(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	liveRoute := []byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}
	deadRoute := []byte{16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1}
	liveKey := make([]byte, 32)
	deadKey := make([]byte, 32)
	liveKey[0] = 1
	deadKey[0] = 2
	if _, err := s.CreateHost(uid, "host-live", liveRoute, liveKey, "live", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateHost(uid, "host-dead", deadRoute, deadKey, "dead", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeHost("host-dead"); err != nil {
		t.Fatal(err)
	}

	before := time.Now().Unix()
	if err := s.UpdateHostHeartbeatByRoute(liveRoute); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateHostHeartbeatByRoute([]byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
	if err := s.UpdateHostHeartbeatByRoute(deadRoute); err != nil {
		t.Fatal(err)
	}

	live, err := s.GetHostByID("host-live")
	if err != nil {
		t.Fatal(err)
	}
	if live.LastSeenAt == nil || *live.LastSeenAt < before {
		t.Fatalf("live last_seen=%v", live.LastSeenAt)
	}
	dead, err := s.GetHostByID("host-dead")
	if err != nil {
		t.Fatal(err)
	}
	if dead.LastSeenAt != nil {
		t.Fatalf("revoked host last_seen=%v", dead.LastSeenAt)
	}

	if err := s.ClearHostHeartbeatByRoute(liveRoute); err != nil {
		t.Fatal(err)
	}
	live, err = s.GetHostByID("host-live")
	if err != nil {
		t.Fatal(err)
	}
	if live.LastSeenAt != nil {
		t.Fatalf("cleared last_seen=%v", live.LastSeenAt)
	}
	if err := s.ClearHostHeartbeatByRoute(deadRoute); err != nil {
		t.Fatal(err)
	}
	if err := s.ClearHostHeartbeatByRoute([]byte{1, 2, 3}); err != nil {
		t.Fatal(err)
	}
}
