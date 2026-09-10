package store

import (
	"errors"
	"testing"
	"time"
)

func TestEnrollRecordsConsumedHostOnInvite(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	code, rec, err := s.CreateInvite(uid, time.Hour)
	if err != nil || rec == nil {
		t.Fatalf("create invite: %v", err)
	}
	unusedCode, unused, err := s.CreateInvite(uid, time.Hour)
	if err != nil || unused == nil || unusedCode == "" {
		t.Fatalf("create unused invite: %v", err)
	}
	now := time.Now().Unix()
	host := &Host{
		ID: "dsh-office", RouteID: make([]byte, 16), HostName: "办公 Mac",
		HostPubKey: make([]byte, 32), MaxStreams: 8, Version: "v0.1.0",
	}
	host.HostPubKey[0] = 9
	if _, err := s.EnrollHost(code, host, func(generation int64) (*EnrollMaterial, error) {
		return &EnrollMaterial{CapabilityHash: []byte{1, 2, 3, 8}, IssuedAt: now, ExpiresAt: now + 3600}, nil
	}); err != nil {
		t.Fatal(err)
	}

	got, err := s.GetInvite(rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.ConsumedAt == nil || got.ConsumedHostID != "dsh-office" || got.ConsumedHostName != "办公 Mac" || !got.ConsumedHostLive {
		t.Fatalf("get invite consumer %+v", got)
	}

	list, err := s.ListInvites()
	if err != nil {
		t.Fatal(err)
	}
	var consumed, live *Invite
	for i := range list {
		switch list[i].ID {
		case rec.ID:
			consumed = &list[i]
		case unused.ID:
			live = &list[i]
		}
	}
	if consumed == nil || consumed.ConsumedHostID != "dsh-office" || consumed.ConsumedHostName != "办公 Mac" || !consumed.ConsumedHostLive {
		t.Fatalf("list consumed %+v", consumed)
	}
	if live == nil || live.ConsumedAt != nil || live.ConsumedHostID != "" || live.ConsumedHostName != "" || live.ConsumedHostLive {
		t.Fatalf("unused invite should not name a computer %+v", live)
	}

	if err := s.RevokeHost("dsh-office"); err != nil {
		t.Fatal(err)
	}
	revoked, err := s.GetInvite(rec.ID)
	if err != nil {
		t.Fatal(err)
	}
	if revoked.ConsumedHostName != "办公 Mac" || revoked.ConsumedHostLive {
		t.Fatalf("revoked host should stay named and not live %+v", revoked)
	}

	if err := s.DeleteHost("dsh-office"); err != nil {
		t.Fatal(err)
	}
	after, err := s.ListInvitesByUser(uid)
	if err != nil {
		t.Fatal(err)
	}
	var kept *Invite
	for i := range after {
		if after[i].ID == rec.ID {
			kept = &after[i]
			break
		}
	}
	if kept == nil || kept.ConsumedHostID != "dsh-office" || kept.ConsumedHostName != "办公 Mac" || kept.ConsumedHostLive {
		t.Fatalf("deleted host lost invite consumer %+v", kept)
	}
}

func TestPurgeStaleInvitesKeepsConsumedOccupyingHost(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	uid, err := s.EnsureDefaultUser()
	if err != nil {
		t.Fatal(err)
	}
	code, rec, err := s.CreateInvite(uid, time.Hour)
	if err != nil || rec == nil {
		t.Fatalf("create invite: %v", err)
	}
	now := time.Now().Unix()
	host := &Host{
		ID: "dsh-keep", RouteID: make([]byte, 16), HostName: "书房",
		HostPubKey: make([]byte, 32), MaxStreams: 8, Version: "v0.1.0",
	}
	host.HostPubKey[0] = 3
	if _, err := s.EnrollHost(code, host, func(generation int64) (*EnrollMaterial, error) {
		return &EnrollMaterial{CapabilityHash: []byte{9, 8, 7, 6}, IssuedAt: now, ExpiresAt: now + 3600}, nil
	}); err != nil {
		t.Fatal(err)
	}
	n, err := s.PurgeStaleInvites(now)
	if err != nil {
		t.Fatal(err)
	}
	if n != 0 {
		t.Fatalf("purged occupying consumed invite n=%d", n)
	}
	got, err := s.GetInvite(rec.ID)
	if err != nil || !got.ConsumedHostLive {
		t.Fatalf("occupying consumed invite missing %+v err=%v", got, err)
	}
	if err := s.RevokeHost("dsh-keep"); err != nil {
		t.Fatal(err)
	}
	n, err = s.PurgeStaleInvites(now)
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("purged after host revoke n=%d, want 1", n)
	}
	if _, err := s.GetInvite(rec.ID); !errors.Is(err, ErrInviteNotFound) {
		t.Fatalf("consumed invite after host revoke err=%v", err)
	}
}

func TestInviteQuotaHoldUntil(t *testing.T) {
	now := int64(1_700_000_000)
	def := int64(DefaultInviteTTL / time.Second)
	max := int64(MaxInviteTTL / time.Second)
	if got := inviteQuotaHoldUntil(now-60, now+60, now); got != now+def {
		t.Fatalf("short leftover: %d want %d", got, now+def)
	}
	if got := inviteQuotaHoldUntil(now, now+def, now); got != 0 {
		t.Fatalf("already default: %d", got)
	}
	if got := inviteQuotaHoldUntil(now, now+max, now); got != 0 {
		t.Fatalf("already max: %d", got)
	}
	created := now - (max - 3600)
	if got := inviteQuotaHoldUntil(created, now+60, now); got != created+max {
		t.Fatalf("cap at created+max: %d want %d", got, created+max)
	}
}

func TestQuotaExceededExtendsShortInvite(t *testing.T) {
	s, err := OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	s.SetTenantLimits(1, 4)
	alice, err := s.CreateTenant("alice", "Alice", "alice-password-ok", "admin")
	if err != nil {
		t.Fatal(err)
	}
	now := time.Now().Unix()
	codeA, _, err := s.CreateInvite(alice.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	hostA := &Host{
		ID: "host-a", RouteID: make([]byte, 16), HostName: "已满",
		HostPubKey: make([]byte, 32), MaxStreams: 8, Version: "v0.1.0",
	}
	hostA.HostPubKey[0] = 1
	if _, err := s.EnrollHost(codeA, hostA, func(generation int64) (*EnrollMaterial, error) {
		return &EnrollMaterial{CapabilityHash: []byte{1}, IssuedAt: now, ExpiresAt: now + 3600}, nil
	}); err != nil {
		t.Fatal(err)
	}
	codeB, recB, err := s.CreateInvite(alice.ID, 2*time.Minute)
	if err != nil || recB == nil {
		t.Fatalf("short invite: %v", err)
	}
	before := recB.ExpiresAt
	hostB := &Host{
		ID: "host-b", RouteID: make([]byte, 16), HostName: "新电脑",
		HostPubKey: make([]byte, 32), MaxStreams: 8, Version: "v0.1.0",
	}
	hostB.HostPubKey[0] = 2
	if _, err := s.EnrollHost(codeB, hostB, func(generation int64) (*EnrollMaterial, error) {
		return &EnrollMaterial{CapabilityHash: []byte{2}, IssuedAt: now, ExpiresAt: now + 3600}, nil
	}); !errors.Is(err, ErrTenantHostLimit) {
		t.Fatalf("quota: %v", err)
	}
	got, err := s.GetInvite(recB.ID)
	if err != nil {
		t.Fatal(err)
	}
	if got.ConsumedAt != nil {
		t.Fatal("quota must not consume the invite")
	}
	if got.ExpiresAt <= before {
		t.Fatalf("short invite must be held: before=%d after=%d", before, got.ExpiresAt)
	}
	floor := time.Now().Unix() + int64(DefaultInviteTTL/time.Second) - 3
	if got.ExpiresAt < floor {
		t.Fatalf("hold until %d, want around now+default", got.ExpiresAt)
	}

	codeC, recC, err := s.CreateInvite(alice.ID, MaxInviteTTL)
	if err != nil {
		t.Fatal(err)
	}
	hostC := &Host{
		ID: "host-c", RouteID: make([]byte, 16), HostName: "第三台",
		HostPubKey: make([]byte, 32), MaxStreams: 8, Version: "v0.1.0",
	}
	hostC.HostPubKey[0] = 3
	if _, err := s.EnrollHost(codeC, hostC, func(generation int64) (*EnrollMaterial, error) {
		return &EnrollMaterial{CapabilityHash: []byte{3}, IssuedAt: now, ExpiresAt: now + 3600}, nil
	}); !errors.Is(err, ErrTenantHostLimit) {
		t.Fatalf("quota long: %v", err)
	}
	kept, err := s.GetInvite(recC.ID)
	if err != nil {
		t.Fatal(err)
	}
	if kept.ExpiresAt < recC.ExpiresAt-1 || kept.ExpiresAt > recC.ExpiresAt+1 {
		t.Fatalf("long invite must not be shortened: before=%d after=%d", recC.ExpiresAt, kept.ExpiresAt)
	}
}
