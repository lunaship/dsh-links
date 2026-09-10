package store

import (
	"testing"
	"time"
)

func TestControlEventsOmitSecretsAndScopeByTenant(t *testing.T) {
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
	code, rec, err := s.CreateInvite(a.ID, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.AppendControlEvent(ControlEvent{
		ActorID: a.ID, ActorLogin: "alice", Action: ControlEventInviteCreate,
		TargetKind: "invite", TargetID: rec.ID, SubjectUserID: a.ID,
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.AppendControlEvent(ControlEvent{
		ActorID: b.ID, ActorLogin: "bob-user", Action: ControlEventInviteCreate,
		TargetKind: "invite", TargetID: "other", SubjectUserID: b.ID,
	}); err != nil {
		t.Fatal(err)
	}
	alice, err := s.ListControlEvents(a.ID, false)
	if err != nil || len(alice) != 1 || alice[0].TargetID != rec.ID {
		t.Fatalf("alice events=%+v err=%v", alice, err)
	}
	bob, err := s.ListControlEvents(b.ID, false)
	if err != nil || len(bob) != 1 || bob[0].SubjectUserID != b.ID {
		t.Fatalf("bob events=%+v err=%v", bob, err)
	}
	all, err := s.ListControlEvents("", true)
	if err != nil || len(all) != 2 {
		t.Fatalf("admin events=%+v err=%v", all, err)
	}
	for _, ev := range all {
		if ev.TargetID == code || ev.Detail == code || ev.ActorLogin == code {
			t.Fatalf("invite code stored in event: %+v", ev)
		}
	}
}
