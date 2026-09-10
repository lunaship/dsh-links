package store

import (
	"errors"
	"testing"
	"time"
)

func TestEnsureDefaultUserIgnoresTenants(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	tenant, err := s.CreateTenant("alice", "Alice", "twelve-chars-min", "admin")
	if err != nil {
		t.Fatal(err)
	}
	def, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	if def != defaultUserID {
		t.Fatalf("default user=%q, want %q (not tenant %q)", def, defaultUserID, tenant.ID)
	}
	again, err := s.EnsureDefaultUser()
	if err != nil || again != defaultUserID {
		t.Fatalf("second EnsureDefaultUser=%q err=%v", again, err)
	}
}

func TestTenantAuthAndIsolation(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	if _, err := s.CreateTenant("ad", "x", "twelve-chars-min", "admin"); !errors.Is(err, ErrInvalidLogin) {
		t.Fatalf("short login: %v", err)
	}
	if _, err := s.CreateTenant("admin", "x", "twelve-chars-min", "admin"); !errors.Is(err, ErrInvalidLogin) {
		t.Fatalf("reserved admin login: %v", err)
	}
	if _, err := s.CreateTenant("alice", "Alice", "short", "admin"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("weak password: %v", err)
	}

	a, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	if !a.PasswordMustChange {
		t.Fatal("new tenant must change the maintainer password")
	}
	if _, err := s.CreateTenant("alice", "Alice 2", "alice-password-ok", "admin"); !errors.Is(err, ErrLoginTaken) {
		t.Fatalf("duplicate login: %v", err)
	}
	b, err := s.CreateTenant("bob-user", "Bob", "bobby-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}

	if _, err := s.AuthenticateTenant("alice", "wrong-password-x"); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("bad password: %v", err)
	}
	got, err := s.AuthenticateTenant("alice", "alice-password-ok")
	if err != nil || got.ID != a.ID || got.PasswordHash != "" {
		t.Fatalf("auth alice: %+v err=%v", got, err)
	}

	codeA, recA, err := s.CreateInvite(a.ID, time.Hour)
	if err != nil || codeA == "" {
		t.Fatal(err)
	}
	_, recB, err := s.CreateInvite(b.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	listA, err := s.ListInvitesByUser(a.ID)
	if err != nil || len(listA) != 1 || listA[0].ID != recA.ID {
		t.Fatalf("alice invites=%v err=%v", listA, err)
	}
	listB, err := s.ListInvitesByUser(b.ID)
	if err != nil || len(listB) != 1 || listB[0].ID != recB.ID {
		t.Fatalf("bob invites=%v err=%v", listB, err)
	}

	if err := s.DisableUser(a.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AuthenticateTenant("alice", "alice-password-ok"); !errors.Is(err, ErrUserDisabled) {
		t.Fatalf("disabled auth: %v", err)
	}
	if err := s.EnableUser(a.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := s.AuthenticateTenant("alice", "alice-password-ok"); err != nil {
		t.Fatalf("enabled auth: %v", err)
	}
	if err := s.EnableUser(a.ID); err != nil {
		t.Fatalf("idempotent enable: %v", err)
	}
}

func TestChangeTenantPassword(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	a, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ChangeTenantPassword(a.ID, "wrong-password-x", "newer-password-ok"); !errors.Is(err, ErrCurrentPassword) {
		t.Fatalf("wrong current: %v", err)
	}
	if err := s.ChangeTenantPassword(a.ID, "alice-password-ok", "short"); !errors.Is(err, ErrWeakPassword) {
		t.Fatalf("weak next: %v", err)
	}
	if err := s.ChangeTenantPassword(a.ID, "alice-password-ok", "alice-password-ok"); !errors.Is(err, ErrSamePassword) {
		t.Fatalf("same password: %v", err)
	}
	if err := s.ChangeTenantPassword(a.ID, "alice-password-ok", "newer-password-ok"); err != nil {
		t.Fatal(err)
	}
	after, err := s.GetUser(a.ID)
	if err != nil || after.PasswordMustChange {
		t.Fatalf("self-change should clear must-change: %+v err=%v", after, err)
	}
	if _, err := s.AuthenticateTenant("alice", "alice-password-ok"); !errors.Is(err, ErrUserNotFound) {
		t.Fatalf("old password still worked: %v", err)
	}
	if _, err := s.AuthenticateTenant("alice", "newer-password-ok"); err != nil {
		t.Fatal(err)
	}
	if err := s.SetTenantPassword(a.ID, "reset-password-ok"); err != nil {
		t.Fatal(err)
	}
	reset, err := s.GetUser(a.ID)
	if err != nil || !reset.PasswordMustChange {
		t.Fatalf("admin reset should require change: %+v err=%v", reset, err)
	}
}

func TestTenantQuotaCountsUnusedInvites(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.SetTenantLimits(8, 4)
	a, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	q, err := s.TenantQuota(a.ID)
	if err != nil || q == nil || q.UnusedInvites != 0 || q.MaxUnusedInvites != 4 || q.LiveHosts != 0 || q.MaxLiveHosts != 8 || q.HostFull || q.InviteFull {
		t.Fatalf("empty quota=%+v err=%v", q, err)
	}
	if _, _, err := s.CreateInvite(a.ID, time.Hour); err != nil {
		t.Fatal(err)
	}
	q, err = s.TenantQuota(a.ID)
	if err != nil || q.UnusedInvites != 1 {
		t.Fatalf("after mint quota=%+v err=%v", q, err)
	}
	if q.InviteFull {
		t.Fatal("one unused invite should not fill the default cap")
	}
	list, err := s.ListTenants()
	if err != nil || len(list) != 1 || list[0].UnusedInvites != 1 || list[0].LiveHosts != 0 {
		t.Fatalf("ledger=%+v err=%v", list, err)
	}
	adminID, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	adminQ, err := s.TenantQuota(adminID)
	if err != nil || adminQ != nil {
		t.Fatalf("admin quota=%+v err=%v", adminQ, err)
	}
}

func TestRevokeUnusedInvitesByUserLeavesOthers(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	a, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	b, err := s.CreateTenant("bob-user", "Bob", "bobby-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	_, recA, err := s.CreateInvite(a.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	_, recB, err := s.CreateInvite(b.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	n, err := s.RevokeUnusedInvitesByUser(a.ID)
	if err != nil || n != 1 {
		t.Fatalf("revoked=%d err=%v", n, err)
	}
	gotA, err := s.GetInvite(recA.ID)
	if err != nil || gotA.RevokedAt == nil {
		t.Fatalf("alice invite should be revoked: %+v err=%v", gotA, err)
	}
	gotB, err := s.GetInvite(recB.ID)
	if err != nil || gotB.RevokedAt != nil {
		t.Fatalf("bob invite should still be live: %+v err=%v", gotB, err)
	}
}

func TestIsHostQuotaError(t *testing.T) {
	if IsHostQuotaError(nil) || IsHostQuotaError(errors.New("invite unavailable")) {
		t.Fatal("non-quota errors must not match")
	}
	if !IsHostQuotaError(ErrTenantHostLimit) || !IsHostQuotaError(ErrDeviceHostLimit) {
		t.Fatal("quota sentinels must match")
	}
	if !IsHostQuotaError(errors.New(ErrTenantHostLimit.Error())) {
		t.Fatal("IPC string wrap must still match")
	}
}

func TestCreateInviteReplacesOldestUnusedAtCap(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.SetTenantLimits(8, 2)
	alice, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	bob, err := s.CreateTenant("bob-user", "Bob", "bobby-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	_, first, err := s.CreateInvite(alice.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	_, newer, err := s.CreateInvite(alice.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	_, bobLive, err := s.CreateInvite(bob.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.CreateInvite(alice.ID, time.Hour); !errors.Is(err, ErrTenantInviteLimit) {
		t.Fatalf("third mint without replace: %v", err)
	}
	_, third, replaced, err := s.CreateInviteReplacingOldestUnused(alice.ID, time.Hour)
	if err != nil || third == nil || replaced == nil {
		t.Fatalf("replace mint rec=%+v replaced=%+v err=%v", third, replaced, err)
	}
	if replaced.ID != first.ID {
		t.Fatalf("replaced=%q want oldest %q, not newer %q", replaced.ID, first.ID, newer.ID)
	}
	gotOld, err := s.GetInvite(first.ID)
	if err != nil || gotOld.RevokedAt == nil {
		t.Fatalf("oldest should be revoked: %+v err=%v", gotOld, err)
	}
	gotNewer, err := s.GetInvite(newer.ID)
	if err != nil || gotNewer.RevokedAt != nil || gotNewer.ConsumedAt != nil {
		t.Fatalf("newer unused invite must stay live: %+v err=%v", gotNewer, err)
	}
	gotNew, err := s.GetInvite(third.ID)
	if err != nil || gotNew.RevokedAt != nil || gotNew.ConsumedAt != nil {
		t.Fatalf("new invite should be live: %+v err=%v", gotNew, err)
	}
	gotBob, err := s.GetInvite(bobLive.ID)
	if err != nil || gotBob.RevokedAt != nil {
		t.Fatalf("other tenant invite must stay live: %+v err=%v", gotBob, err)
	}
	q, err := s.TenantQuota(alice.ID)
	if err != nil || q.UnusedInvites != 2 || !q.InviteFull {
		t.Fatalf("after replace quota=%+v err=%v", q, err)
	}
}

func TestCreateInviteReplaceFlagDoesNothingUnderCap(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	alice, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	_, first, err := s.CreateInvite(alice.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	_, second, replaced, err := s.CreateInviteReplacingOldestUnused(alice.ID, time.Hour)
	if err != nil || second == nil || replaced != nil {
		t.Fatalf("under-cap replace rec=%+v replaced=%+v err=%v", second, replaced, err)
	}
	gotFirst, err := s.GetInvite(first.ID)
	if err != nil || gotFirst.RevokedAt != nil {
		t.Fatalf("under-cap must not revoke: %+v err=%v", gotFirst, err)
	}
	q, err := s.TenantQuota(alice.ID)
	if err != nil || q.UnusedInvites != 2 || q.InviteFull {
		t.Fatalf("under-cap quota=%+v err=%v", q, err)
	}
}
