package control

import (
	"bytes"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/store"
)

func newTestControl(t *testing.T) (*Control, *store.Store) {
	t.Helper()
	st, err := store.OpenMemory()
	if err != nil {
		t.Fatal(err)
	}
	seed := sha256.Sum256([]byte(t.Name()))
	ctrl, err := New(st, seed[:], make([]byte, 32), 8)
	if err != nil {
		st.Close()
		t.Fatal(err)
	}
	return ctrl, st
}

func enrollRequest(t *testing.T, invite, hostID string, priv ed25519.PrivateKey) *EnrollRequest {
	t.Helper()
	nonce, err := cryptoutil.RandomBytes(16)
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := cryptoutil.RandomBytes(32)
	if err != nil {
		t.Fatal(err)
	}
	ts := time.Now().Unix()
	pub := priv.Public().(ed25519.PublicKey)
	proof := ed25519.Sign(priv, cryptoutil.BuildEnrollTranscript(invite, hostID, pub, ts, nonce, challenge))
	return &EnrollRequest{InviteCode: invite, HostId: hostID, HostPublicKey: pub, Ts: ts, Nonce: nonce, Challenge: challenge, Proof: proof}
}

func TestInvalidProofDoesNotConsumeInvite(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	req := enrollRequest(t, invite, "host-1", priv)
	req.Proof[0] ^= 1
	if _, err := ctrl.Enroll(req); err == nil {
		t.Fatal("invalid proof was accepted")
	}
	if _, err := ctrl.Enroll(enrollRequest(t, invite, "host-1", priv)); err != nil {
		t.Fatalf("invite was consumed by invalid proof: %v", err)
	}
}

func TestFailedHostInsertRollsBackInviteConsumption(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	firstInvite, _ := ctrl.CreateInvite(time.Minute)
	_, firstPriv, _ := ed25519.GenerateKey(rand.Reader)
	if _, err := ctrl.Enroll(enrollRequest(t, firstInvite, "duplicate-host", firstPriv)); err != nil {
		t.Fatal(err)
	}
	secondInvite, _ := ctrl.CreateInvite(time.Minute)
	_, duplicatePriv, _ := ed25519.GenerateKey(rand.Reader)
	if _, err := ctrl.Enroll(enrollRequest(t, secondInvite, "duplicate-host", duplicatePriv)); err == nil {
		t.Fatal("duplicate host insert unexpectedly succeeded")
	}
	_, replacementPriv, _ := ed25519.GenerateKey(rand.Reader)
	if _, err := ctrl.Enroll(enrollRequest(t, secondInvite, "replacement-host", replacementPriv)); err != nil {
		t.Fatalf("invite was consumed by rolled back host insert: %v", err)
	}
}

func TestEnrollSameHostAndKeyRebinds(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	firstInvite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	first, err := ctrl.Enroll(enrollRequest(t, firstInvite, "same-host", priv))
	if err != nil {
		t.Fatal(err)
	}
	secondInvite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	second, err := ctrl.Enroll(enrollRequest(t, secondInvite, "same-host", priv))
	if err != nil {
		t.Fatalf("same host+key re-enroll: %v", err)
	}
	if second.Generation != first.Generation+1 {
		t.Fatalf("generation=%d want %d", second.Generation, first.Generation+1)
	}
	if bytes.Equal(first.RouteId, second.RouteId) {
		t.Fatal("rebind reused route id")
	}
	host, err := st.GetHostByID("same-host")
	if err != nil {
		t.Fatal(err)
	}
	if host.Generation != int64(second.Generation) {
		t.Fatalf("stored generation=%d want %d", host.Generation, second.Generation)
	}
}

func TestReenrollRevokesPriorRouteImmediately(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	firstInvite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	first, err := ctrl.Enroll(enrollRequest(t, firstInvite, "same-host-revoke", priv))
	if err != nil {
		t.Fatal(err)
	}
	var revokedRoute string
	ctrl.SetRevokeFn(func(routeId, id string) (int, int) {
		revokedRoute = routeId
		if id != "same-host-revoke" {
			t.Fatalf("revoke host=%q", id)
		}
		return 1, 1
	})
	secondInvite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	second, err := ctrl.Enroll(enrollRequest(t, secondInvite, "same-host-revoke", priv))
	if err != nil {
		t.Fatal(err)
	}
	wantOld := base64.RawURLEncoding.EncodeToString(first.RouteId)
	if revokedRoute != wantOld {
		t.Fatalf("revoked route=%q want %q", revokedRoute, wantOld)
	}
	if _, err := st.GetHostByRoute(first.RouteId); err == nil {
		t.Fatal("old route still persisted")
	}
	if host, err := st.GetHostByRoute(second.RouteId); err != nil || host.Generation != int64(second.Generation) {
		t.Fatalf("new route missing: host=%v err=%v", host, err)
	}
}

func TestConcurrentReenrollDistinctGenerations(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.DB().SetMaxOpenConns(8)
	if _, err := st.DB().Exec(`PRAGMA busy_timeout=5000`); err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	bootstrap, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.Enroll(enrollRequest(t, bootstrap, "race-host", priv)); err != nil {
		t.Fatal(err)
	}
	invites := make([]string, 8)
	for i := range invites {
		code, err := ctrl.CreateInvite(time.Minute)
		if err != nil {
			t.Fatal(err)
		}
		invites[i] = code
	}
	results := make([]*EnrollResult, len(invites))
	errs := make([]error, len(invites))
	var wg sync.WaitGroup
	for i, invite := range invites {
		wg.Add(1)
		go func(i int, invite string) {
			defer wg.Done()
			results[i], errs[i] = ctrl.Enroll(enrollRequest(t, invite, "race-host", priv))
		}(i, invite)
	}
	wg.Wait()
	seenGen := map[uint64]struct{}{}
	seenRoute := map[string]struct{}{}
	ok := 0
	for i, err := range errs {
		if err != nil {
			continue
		}
		ok++
		if _, dup := seenGen[results[i].Generation]; dup {
			t.Fatalf("duplicate generation %d", results[i].Generation)
		}
		seenGen[results[i].Generation] = struct{}{}
		route := base64.RawURLEncoding.EncodeToString(results[i].RouteId)
		if _, dup := seenRoute[route]; dup {
			t.Fatalf("duplicate route %s", route)
		}
		seenRoute[route] = struct{}{}
	}
	if ok != len(invites) {
		t.Fatalf("concurrent enrolls succeeded=%d, want %d (errs=%v)", ok, len(invites), errs)
	}
	host, err := st.GetHostByID("race-host")
	if err != nil {
		t.Fatal(err)
	}
	if _, exists := seenGen[uint64(host.Generation)]; !exists {
		t.Fatalf("stored generation %d was not returned by a winner", host.Generation)
	}
}

func TestInvalidInviteRejectedBeforeProofVerification(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	req := enrollRequest(t, "not-a-real-invite", "host-invalid-invite", priv)
	req.Proof[0] ^= 1
	if _, err := ctrl.Enroll(req); err == nil || err.Error() != "invite unavailable" {
		t.Fatalf("invalid invite did not hit the early availability gate: %v", err)
	}
}

func TestRenewRejectsExpiredCapability(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()

	invite, err := ctrl.CreateInvite(30 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostID := "host-expired-renew"
	enrollReq := enrollRequest(t, invite, hostID, priv)
	enrolled, err := ctrl.Enroll(enrollReq)
	if err != nil {
		t.Fatalf("enroll: %v", err)
	}

	// Forge an already-expired capability for the same host, properly signed
	// by the issuer: renewal must still be refused.
	jti, _ := cryptoutil.RandomBytes(16)
	now := time.Now().Unix()
	expired, err := cryptoutil.SignCapability(ctrl.issuerPriv, cryptoutil.CapabilityPayload{
		Iss: "dsh-links-relay", Jti: base64.RawURLEncoding.EncodeToString(jti),
		Host: hostID, Route: base64.RawURLEncoding.EncodeToString(enrolled.RouteId),
		HostPK:     base64.RawURLEncoding.EncodeToString(enrollReq.HostPublicKey),
		Generation: enrolled.Generation, MaxStreams: 8,
		Iat: now - 7200, Exp: now - 3600, // far beyond the 60s renewal grace
	})
	if err != nil {
		t.Fatal(err)
	}
	nonce, _ := cryptoutil.RandomBytes(16)
	chal, _ := cryptoutil.RandomBytes(32)
	proof := ed25519.Sign(priv, cryptoutil.BuildRenewTranscript(expired, now, nonce, chal))
	_, err = ctrl.Renew(&RenewRequest{
		RouteId: enrolled.RouteId, HostId: hostID, HostPubKey: enrollReq.HostPublicKey,
		Ts: now, Nonce: nonce, Challenge: chal, Proof: proof, OldCapability: expired,
	})
	if err == nil || !strings.Contains(err.Error(), "expired") {
		t.Fatalf("expired capability renewed: err=%v", err)
	}
}

func TestRenewAllowsWithinGrace(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	invite, err := ctrl.CreateInvite(30 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	req := enrollRequest(t, invite, "host-grace", priv)
	enrolled, err := ctrl.Enroll(req)
	if err != nil {
		t.Fatal(err)
	}

	// A capability that lapsed by a few seconds inside cryptoutil.CapExpiryGrace
	// must still be renewable: the relay's control loop keeps such a session
	// alive up to Exp+CapExpiryGrace, so the renewal controller must not refuse it.
	now := time.Now().Unix()
	jti, _ := cryptoutil.RandomBytes(16)
	late, err := cryptoutil.SignCapability(ctrl.issuerPriv, cryptoutil.CapabilityPayload{
		Iss: "dsh-links-relay", Jti: base64.RawURLEncoding.EncodeToString(jti),
		Host: "host-grace", Route: base64.RawURLEncoding.EncodeToString(enrolled.RouteId),
		HostPK:     base64.RawURLEncoding.EncodeToString(req.HostPublicKey),
		Generation: enrolled.Generation, MaxStreams: 8,
		Iat: now - 7200, Exp: now - 30, // inside the 60s grace
	})
	if err != nil {
		t.Fatal(err)
	}
	nonce, _ := cryptoutil.RandomBytes(16)
	chal, _ := cryptoutil.RandomBytes(32)
	proof := ed25519.Sign(priv, cryptoutil.BuildRenewTranscript(late, now, nonce, chal))
	newCap, err := ctrl.Renew(&RenewRequest{
		RouteId: enrolled.RouteId, HostId: "host-grace", HostPubKey: req.HostPublicKey,
		Ts: now, Nonce: nonce, Challenge: chal, Proof: proof, OldCapability: late,
	})
	if err != nil {
		t.Fatalf("renewal within grace refused: %v", err)
	}
	if newCap == "" {
		t.Fatal("renewal returned empty capability")
	}
}

func TestRenewRejectsExactReplay(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	invite, err := ctrl.CreateInvite(30 * time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostID := "host-renew-replay"
	enrollReq := enrollRequest(t, invite, hostID, priv)
	enrolled, err := ctrl.Enroll(enrollReq)
	if err != nil {
		t.Fatal(err)
	}
	nonce, _ := cryptoutil.RandomBytes(16)
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildRenewTranscript(enrolled.Capability, ts, nonce, challenge))
	req := &RenewRequest{
		RouteId: enrolled.RouteId, HostId: hostID, HostPubKey: enrollReq.HostPublicKey,
		Ts: ts, Nonce: nonce, Challenge: challenge, Proof: proof, OldCapability: enrolled.Capability,
	}
	if _, err := ctrl.Renew(req); err != nil {
		t.Fatalf("first renewal failed: %v", err)
	}
	if _, err := ctrl.Renew(req); err == nil || !strings.Contains(err.Error(), "replay") {
		t.Fatalf("exact renewal replay accepted: %v", err)
	}
	credentials, err := st.ListCredentials(hostID)
	if err != nil {
		t.Fatal(err)
	}
	if len(credentials) != 2 {
		t.Fatalf("replay changed credential count to %d, want enrollment plus one renewal", len(credentials))
	}
}

func TestDeleteHostRemovesRecordAndAllowsReenroll(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostID := "host-delete-me"
	if _, err := ctrl.Enroll(enrollRequest(t, invite, hostID, priv)); err != nil {
		t.Fatal(err)
	}
	called := false
	ctrl.SetRevokeFn(func(routeId, id string) (int, int) {
		called = true
		if id != hostID || routeId == "" {
			t.Fatalf("revokeFn route=%q id=%q", routeId, id)
		}
		return 1, 1
	})
	if _, _, err := ctrl.DeleteHost(hostID); err != nil {
		t.Fatal(err)
	}
	if !called {
		t.Fatal("delete did not notify relay")
	}
	hosts, err := ctrl.ListHosts()
	if err != nil {
		t.Fatal(err)
	}
	if len(hosts) != 0 {
		t.Fatalf("hosts leftover=%d", len(hosts))
	}
	next, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.Enroll(enrollRequest(t, next, hostID, priv)); err != nil {
		t.Fatalf("re-enroll after delete: %v", err)
	}
}

func TestPurgeStaleInvitesLeavesLiveCode(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	if _, err := ctrl.CreateInvite(time.Hour); err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.CreateInvite(time.Hour); err != nil {
		t.Fatal(err)
	}
	list, err := ctrl.ListInvites()
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 {
		t.Fatalf("invites=%d", len(list))
	}
	if err := ctrl.RevokeInvite(list[0].ID); err != nil {
		t.Fatal(err)
	}
	n, err := ctrl.PurgeStaleInvites()
	if err != nil {
		t.Fatal(err)
	}
	if n != 1 {
		t.Fatalf("purged=%d want 1", n)
	}
	left, err := ctrl.ListInvites()
	if err != nil {
		t.Fatal(err)
	}
	if len(left) != 1 {
		t.Fatalf("left=%d", len(left))
	}
	if err := ctrl.DeleteInvite(left[0].ID); err != nil {
		t.Fatal(err)
	}
}

func TestEnrollRejectsUnaddressableHostID(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	invite, err := ctrl.CreateInvite(time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	_, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	for _, bad := range []string{"a/b", "a.b", "..", "a b", "p%2Fq", strings.Repeat("x", 65)} {
		req := enrollRequest(t, invite, bad, priv)
		if _, err := ctrl.Enroll(req); err == nil {
			t.Fatalf("Enroll accepted hostId %q, want rejection", bad)
		}
	}
	// The invite must remain consumable by a legitimate host after all rejects.
	req := enrollRequest(t, invite, "legit-host-1", priv)
	if _, err := ctrl.Enroll(req); err != nil {
		t.Fatalf("legitimate enroll after rejects failed: %v", err)
	}
}
