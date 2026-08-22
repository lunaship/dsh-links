package control

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"strings"
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
		Iat: now - 7200, Exp: now - 60,
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
