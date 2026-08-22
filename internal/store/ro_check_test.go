package store

import (
	"path/filepath"
	"testing"
)

func TestReadOnlyOpenAfterDriverUpgrade(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "control.db")
	rw, err := Open(dbPath)
	if err != nil {
		t.Fatalf("open rw: %v", err)
	}
	uid, err := rw.EnsureDefaultUser()
	if err != nil {
		t.Fatalf("seed: %v", err)
	}
	code, _, err := rw.CreateInvite(uid, 60e9)
	if err != nil {
		t.Fatalf("invite: %v", err)
	}
	if err := rw.ValidateInvite(code); err != nil {
		t.Fatalf("validate in rw conn: %v", err)
	}
	rw.Close()

	ro, err := OpenReadOnly(dbPath)
	if err != nil {
		t.Fatalf("open ro (dsn broken after driver upgrade?): %v", err)
	}
	defer ro.Close()
	if err := ro.ValidateInvite(code); err != nil {
		t.Fatalf("validate via ro conn: %v", err)
	}
	// Write attempts must fail on the read-only handle.
	if err := ro.RevokeInvite("nonexistent"); err == nil {
		t.Fatal("write succeeded on read-only store")
	}
}
