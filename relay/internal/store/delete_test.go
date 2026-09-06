package store

import (
	"errors"
	"testing"
	"time"
)

func TestDeleteHostRemovesChildren(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateHost(uid, "host-del", make([]byte, 16), make([]byte, 32), "host-del", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateCredential("host-del", []byte{1}, 1, 1000, 2000); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteHost("host-del"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.GetHostByID("host-del"); err == nil {
		t.Fatal("deleted host still lookupable")
	}
	creds, err := s.ListCredentials("host-del")
	if err != nil {
		t.Fatal(err)
	}
	if len(creds) != 0 {
		t.Fatalf("credentials leftover=%d", len(creds))
	}
	if err := s.DeleteHost("host-del"); !errors.Is(err, ErrHostNotFound) {
		t.Fatalf("second delete error=%v", err)
	}
}

func TestPurgeStaleInvitesKeepsValid(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	live, rec, err := s.CreateInvite(uid, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if live == "" || rec == nil {
		t.Fatal("missing live invite")
	}
	_, stale, err := s.CreateInvite(uid, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeInvite(stale.ID); err != nil {
		t.Fatal(err)
	}
	n, err := s.PurgeStaleInvites(time.Now().Unix())
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("purged=%d, want 1", n)
	}
	list, err := s.ListInvites()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 1 || list[0].ID != rec.ID {
		t.Fatalf("kept %+v", list)
	}
	if err := s.DeleteInvite(rec.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteInvite(rec.ID); !errors.Is(err, ErrInviteNotFound) {
		t.Fatalf("second invite delete error=%v", err)
	}
}

func TestPurgeRevokedHosts(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	liveRoute := make([]byte, 16)
	liveRoute[0] = 1
	liveKey := make([]byte, 32)
	liveKey[0] = 1
	if _, err := s.CreateHost(uid, "keep", liveRoute, liveKey, "keep", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	dropRoute := make([]byte, 16)
	dropRoute[0] = 2
	dropKey := make([]byte, 32)
	dropKey[0] = 2
	if _, err := s.CreateHost(uid, "drop", dropRoute, dropKey, "drop", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeHost("drop"); err != nil {
		t.Fatal(err)
	}
	n, err := s.PurgeRevokedHosts()
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("purged hosts=%d, want 1", n)
	}
	if _, err := s.GetHostByID("keep"); err != nil {
		t.Fatalf("live host removed: %v", err)
	}
	if _, err := s.GetHostByID("drop"); err == nil {
		t.Fatal("revoked host still present")
	}
}
