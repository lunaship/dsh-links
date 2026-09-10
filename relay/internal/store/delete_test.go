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

func TestPurgeByUserLeavesOthers(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	alice, err := s.CreateTenant("alice", "Alice", "twelve-chars-min", "admin")
	if err != nil {
		t.Fatal(err)
	}
	bob, err := s.CreateTenant("bob-user", "Bob", "twelve-chars-min", "admin")
	if err != nil {
		t.Fatal(err)
	}
	_, stale, err := s.CreateInvite(alice.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeInvite(stale.ID); err != nil {
		t.Fatal(err)
	}
	_, live, err := s.CreateInvite(bob.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	n, err := s.PurgeStaleInvitesByUser(alice.ID, time.Now().Unix())
	if err != nil || n != 1 {
		t.Fatalf("alice invite purge n=%d err=%v", n, err)
	}
	bobInvites, err := s.ListInvitesByUser(bob.ID)
	if err != nil || len(bobInvites) != 1 || bobInvites[0].ID != live.ID {
		t.Fatalf("bob invites after alice purge: %+v err=%v", bobInvites, err)
	}
	aliceInvites, err := s.ListInvitesByUser(alice.ID)
	if err != nil || len(aliceInvites) != 0 {
		t.Fatalf("alice invites after purge: %+v", aliceInvites)
	}

	aliceRoute := make([]byte, 16)
	aliceRoute[0] = 1
	aliceKey := make([]byte, 32)
	aliceKey[0] = 1
	if _, err := s.CreateHost(alice.ID, "alice-drop", aliceRoute, aliceKey, "Alice Mac", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	if err := s.RevokeHost("alice-drop"); err != nil {
		t.Fatal(err)
	}
	bobRoute := make([]byte, 16)
	bobRoute[0] = 2
	bobKey := make([]byte, 32)
	bobKey[0] = 2
	if _, err := s.CreateHost(bob.ID, "bob-keep", bobRoute, bobKey, "Bob Mac", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	n, err = s.PurgeRevokedHostsByUser(alice.ID)
	if err != nil || n != 1 {
		t.Fatalf("alice host purge n=%d err=%v", n, err)
	}
	if _, err := s.GetHostByID("alice-drop"); err == nil {
		t.Fatal("alice revoked host still present")
	}
	if _, err := s.GetHostByID("bob-keep"); err != nil {
		t.Fatalf("bob live host removed: %v", err)
	}
}
