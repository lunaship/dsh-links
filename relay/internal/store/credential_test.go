package store

import (
	"errors"
	"testing"
	"time"
)

func TestPruneCredentialsKeepsNewest(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateHost(uid, "host-prune", make([]byte, 16), make([]byte, 32), "host-prune", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	for i := 1; i <= 5; i++ {
		if _, err := s.CreateCredential("host-prune", []byte{byte(i)}, 1, int64(1000+i), int64(2000+i)); err != nil {
			t.Fatal(err)
		}
	}
	if err := s.PruneCredentials("host-prune", 2); err != nil {
		t.Fatal(err)
	}
	creds, err := s.ListCredentials("host-prune")
	if err != nil {
		t.Fatal(err)
	}
	if len(creds) != 2 {
		t.Fatalf("pruned credentials=%d, want 2", len(creds))
	}
	for _, c := range creds {
		if c.IssuedAt != 1004 && c.IssuedAt != 1005 {
			t.Fatalf("kept credential issued_at=%d, want 1004 or 1005", c.IssuedAt)
		}
	}
}

func TestRecordRenewalAtomicallyRejectsReplay(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateHost(uid, "host-renew", make([]byte, 16), make([]byte, 32), "host-renew", 8, "v0.1.0", 1); err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()
	digest := []byte("signed-renewal-transcript")
	if err := s.RecordRenewal("host-renew", digest, []byte("capability-one"), 1, now, now+3600, now+120, 8); err != nil {
		t.Fatal(err)
	}
	if err := s.RecordRenewal("host-renew", digest, []byte("capability-two"), 1, now+1, now+3601, now+120, 8); !errors.Is(err, ErrRenewalReplay) {
		t.Fatalf("replayed renewal error=%v", err)
	}
	creds, err := s.ListCredentials("host-renew")
	if err != nil {
		t.Fatal(err)
	}
	if len(creds) != 1 {
		t.Fatalf("replay created %d credentials, want 1", len(creds))
	}
}
