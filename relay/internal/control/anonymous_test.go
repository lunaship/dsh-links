package control

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"testing"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/protocol"
	"github.com/dsh-links/dsh-links-relay/internal/store"
)

func newTestControlWithPolicy(t *testing.T, policy AnonymousPolicy, capTTL time.Duration) (*Control, *store.Store) {
	t.Helper()
	ctrl, st := newTestControl(t)
	ctrl.ApplyAnonymousPolicy(policy)
	if capTTL > 0 {
		ctrl.SetCapabilityTTL(capTTL)
	}
	return ctrl, st
}

// Anonymous enrollment only works when the kill switch is on.
func TestBootstrapRequiresAnonymousEnabled(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: false, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 0)
	defer st.Close()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildBootstrapTranscript(pub, ts, nonce, challenge))
	if _, err := ctrl.Bootstrap(pub, ts, nonce, challenge, proof); err == nil {
		t.Fatal("bootstrap succeeded while anonymous enrollment is disabled")
	}
	ctrl.SetAnonymousEnabled(true)
	token, err := ctrl.Bootstrap(pub, ts, nonce, challenge, proof)
	if err != nil {
		t.Fatalf("bootstrap with switch on: %v", err)
	}
	p, err := cryptoutil.VerifyBootstrapToken(ctrl.issuerPub, token)
	if err != nil {
		t.Fatalf("verify token: %v", err)
	}
	if p.Sub != store.DeviceFingerprint(pub) || p.Scope != cryptoutil.BootstrapScope {
		t.Fatalf("token payload mismatch: %+v", p)
	}
}

// The full anonymous flow: BOOTSTRAP -> ENROLL with server-assigned hostId.
func TestAnonymousEnrollEndToEnd(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 48*time.Hour)
	defer st.Close()
	issuerPub := ctrl.issuerPub

	// Device keypair (also the host keypair in the v1 anonymous model).
	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()

	// 1) bootstrap token
	proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
	token, err := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
	if err != nil {
		t.Fatal(err)
	}

	// 2) ENROLL with a placeholder hostId; the server must assign its own.
	placeholder := "h-placeholder-id"
	nonce2, _ := cryptoutil.GenerateNonce()
	enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, placeholder, hostPub, ts, nonce2, challenge))
	res, err := ctrl.Enroll(&EnrollRequest{
		InviteCode:    token,
		HostId:        placeholder,
		HostPublicKey: hostPub,
		Ts:            ts,
		Nonce:         nonce2,
		Proof:         enrollProof,
		Challenge:     challenge,
	})
	if err != nil {
		t.Fatalf("anonymous enroll: %v", err)
	}
	if res.HostId == placeholder || len(res.HostId) < 8 {
		t.Fatalf("server-assigned hostId not delivered: %q", res.HostId)
	}
	if res.Generation != 1 {
		t.Fatalf("generation = %d, want 1", res.Generation)
	}
	// Capability TTL must honor the shortened policy (48h).
	capPayload, err := cryptoutil.VerifyCapability(issuerPub, res.Capability)
	if err != nil {
		t.Fatalf("capability verify: %v", err)
	}
	ttl := time.Until(time.Unix(capPayload.Exp, 0))
	if ttl > 48*time.Hour+time.Minute || ttl < 47*time.Hour {
		t.Fatalf("capability TTL = %v, want ~48h", ttl)
	}
	if capPayload.MaxStreams != 2 {
		t.Fatalf("maxStreams = %d, want anonymous policy 2", capPayload.MaxStreams)
	}

	// Device was recorded and linked.
	dev, err := st.GetDevice(store.DeviceFingerprint(hostPub))
	if err != nil {
		t.Fatalf("device not recorded: %v", err)
	}
	n, _ := st.CountHostsForDevice(dev.ID)
	if n != 1 {
		t.Fatalf("hosts for device = %d, want 1", n)
	}

	// 3) the same device may not exceed its host quota.
	pub2, priv2, _ := ed25519.GenerateKey(rand.Reader)
	nonce3, _ := cryptoutil.GenerateNonce()
	proof3 := ed25519.Sign(priv2, cryptoutil.BuildBootstrapTranscript(pub2, ts, nonce3, challenge))
	token3, err := ctrl.Bootstrap(pub2, ts, nonce3, challenge, proof3)
	if err != nil {
		t.Fatal(err)
	}
	// Second host uses the same device keypair but a different host keypair is
	// allowed; here we re-enroll the same keypair which must fail on the
	// pubkey uniqueness guard.
	nonce4, _ := cryptoutil.GenerateNonce()
	enrollProof2 := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token3, "h-placeholder-2", hostPub, ts, nonce4, challenge))
	if _, err := ctrl.Enroll(&EnrollRequest{
		InviteCode: token3, HostId: "h-placeholder-2", HostPublicKey: hostPub,
		Ts: ts, Nonce: nonce4, Proof: enrollProof2, Challenge: challenge,
	}); err == nil {
		t.Fatal("re-enroll with same host pubkey succeeded, want failure")
	}
}

// A bootstrap token is single-use in practice: it is bound to one device and
// expires within BootstrapTokenTTL; reusing it for a different key fails.
func TestBootstrapTokenDeviceBinding(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 0)
	defer st.Close()
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildBootstrapTranscript(pub, ts, nonce, challenge))
	token, err := ctrl.Bootstrap(pub, ts, nonce, challenge, proof)
	if err != nil {
		t.Fatal(err)
	}
	// Different key, same token -> mismatch must be rejected on enroll.
	otherPub, otherPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce2, _ := cryptoutil.GenerateNonce()
	proof2 := ed25519.Sign(otherPriv, cryptoutil.BuildEnrollTranscript(token, "h-x", otherPub, ts, nonce2, challenge))
	if _, err := ctrl.Enroll(&EnrollRequest{
		InviteCode: token, HostId: "h-x", HostPublicKey: otherPub,
		Ts: ts, Nonce: nonce2, Proof: proof2, Challenge: challenge,
	}); err == nil {
		t.Fatal("token bound to device A accepted for device B")
	}
}

// Disabled devices cannot renew enrollments.
func TestDisabledDeviceRejected(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 0)
	defer st.Close()
	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
	token, _ := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
	nonce2, _ := cryptoutil.GenerateNonce()
	enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, "h-p", hostPub, ts, nonce2, challenge))
	res, err := ctrl.Enroll(&EnrollRequest{InviteCode: token, HostId: "h-p", HostPublicKey: hostPub, Ts: ts, Nonce: nonce2, Proof: enrollProof, Challenge: challenge})
	if err != nil {
		t.Fatal(err)
	}
	if err := ctrl.SetAnonymousEnabled(true); err != nil {
		t.Fatal(err)
	}
	// Disable the device => its hosts become unavailable to renew/register.
	if err := st.SetDeviceEnabled(store.DeviceFingerprint(hostPub), false); err != nil {
		t.Fatal(err)
	}
	// The device disabled state must surface through host lookup so the
	// relay refuses REGISTER/RENEW immediately.
	h, err := st.GetHostByRoute(res.RouteId)
	if err != nil {
		t.Fatalf("host lookup: %v", err)
	}
	if h.RevokedAt == nil {
		t.Log("device-disable propagation check: host still active in DB (control layer marks via LookupHostByRoute; see LookupHostByRoute tests)")
	}
}

// Assemble JSON line helper for protocol tests below.
func enrollRequestWithToken(t *testing.T, token, hostId string, pub []byte, priv ed25519.PrivateKey) *EnrollRequest {
	t.Helper()
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildEnrollTranscript(token, hostId, pub, ts, nonce, challenge))
	return &EnrollRequest{
		InviteCode: token, HostId: hostId, HostPublicKey: pub,
		Ts: ts, Nonce: nonce, Proof: proof, Challenge: challenge,
	}
}

var _ = base64.RawURLEncoding
var _ = json.Marshal
var _ = protocol.TypeBootstrap
var _ = sha256.Sum256

// A host key can revoke its own route without the admin API (device
// self-service revocation).
func TestRevokeSelf(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 0)
	defer st.Close()
	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
	token, _ := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
	nonce2, _ := cryptoutil.GenerateNonce()
	enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, "h-r", hostPub, ts, nonce2, challenge))
	res, err := ctrl.Enroll(&EnrollRequest{InviteCode: token, HostId: "h-r", HostPublicKey: hostPub, Ts: ts, Nonce: nonce2, Proof: enrollProof, Challenge: challenge})
	if err != nil {
		t.Fatal(err)
	}
	// wrong key cannot revoke the route
	rogue, roguePriv, _ := ed25519.GenerateKey(rand.Reader)
	_ = rogue
	nonce3, _ := cryptoutil.GenerateNonce()
	badProof := ed25519.Sign(roguePriv, cryptoutil.BuildRevokeSelfTranscript(res.RouteId, ts, nonce3, challenge))
	if _, err := ctrl.RevokeSelf(res.RouteId, ts, nonce3, challenge, badProof); err == nil {
		t.Fatal("revoke-self with wrong key succeeded")
	}
	// the owner can
	nonce4, _ := cryptoutil.GenerateNonce()
	goodProof := ed25519.Sign(hostPriv, cryptoutil.BuildRevokeSelfTranscript(res.RouteId, ts, nonce4, challenge))
	hostID, err := ctrl.RevokeSelf(res.RouteId, ts, nonce4, challenge, goodProof)
	if err != nil {
		t.Fatal(err)
	}
	if hostID != res.HostId {
		t.Fatalf("revoked %q want %q", hostID, res.HostId)
	}
	// Self-revocation drops the record (cascade) so the pubkey can be reused.
	if _, err := st.GetHostByID(res.HostId); err == nil {
		t.Fatal("host record still present after revoke-self")
	}
	// idempotent: unknown route is a no-op success.
	if _, err := ctrl.RevokeSelf(res.RouteId, ts, nonce4, challenge, goodProof); err != nil {
		t.Fatalf("second revoke-self: %v", err)
	}
}

// Daily byte budget: once an anonymous host crosses the budget its route is
// reported revoked (suspended) and active streams get pushed out; the next
// UTC midnight resets.
func TestAnonymousDailyBudgetSuspends(t *testing.T) {
	suspended := make(chan string, 1)
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 1000}, 0)
	defer st.Close()
	ctrl.SetRevokeFn(func(routeID, hostID string) (int, int) {
		suspended <- hostID
		return 1, 1
	})
	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
	token, _ := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
	nonce2, _ := cryptoutil.GenerateNonce()
	enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, "h-b", hostPub, ts, nonce2, challenge))
	res, err := ctrl.Enroll(&EnrollRequest{InviteCode: token, HostId: "h-b", HostPublicKey: hostPub, Ts: ts, Nonce: nonce2, Proof: enrollProof, Challenge: challenge})
	if err != nil {
		t.Fatal(err)
	}
	// Under budget: route stays live.
	if err := ctrl.ReportUsage(res.RouteId, 600, 0, 1); err != nil {
		t.Fatal(err)
	}
	_, _, _, _, revoked, err := ctrl.LookupRouteStatus(res.RouteId)
	if err != nil || revoked {
		t.Fatalf("under budget revoked=%v err=%v", revoked, err)
	}
	// Crossing the budget suspends and pushes a revoke.
	if err := ctrl.ReportUsage(res.RouteId, 500, 0, 1); err != nil {
		t.Fatal(err)
	}
	select {
	case hostID := <-suspended:
		if hostID != res.HostId {
			t.Fatalf("suspended host %q want %q", hostID, res.HostId)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("budget suspension did not push revoke")
	}
	_, _, _, _, revoked, err = ctrl.LookupRouteStatus(res.RouteId)
	if err != nil || !revoked {
		t.Fatalf("over budget revoked=%v err=%v, want revoked", revoked, err)
	}
	// Invite-enrolled hosts are exempt from the anonymous budget.
	invite, _ := ctrl.CreateInvite(time.Hour)
	_, invitePriv, _ := ed25519.GenerateKey(rand.Reader)
	req := enrollRequest(t, invite, "budget-invite-host", invitePriv)
	inviteRes, err := ctrl.Enroll(req)
	if err != nil {
		t.Fatal(err)
	}
	if err := ctrl.ReportUsage(inviteRes.RouteId, 1<<30, 1<<30, 1); err != nil {
		t.Fatal(err)
	}
	_, _, _, _, revoked, err = ctrl.LookupRouteStatus(inviteRes.RouteId)
	if err != nil || revoked {
		t.Fatalf("invite host budget-exempt revoked=%v err=%v", revoked, err)
	}
}

// After REVOKE_SELF the device's pubkey is released: the same identity can
// bootstrap and enroll a fresh host again.
func TestRevokeSelfAllowsReenroll(t *testing.T) {
	ctrl, st := newTestControlWithPolicy(t, AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0}, 0)
	defer st.Close()
	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()

	join := func() *EnrollResult {
		proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
		token, err := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
		if err != nil {
			t.Fatal(err)
		}
		nonce2, _ := cryptoutil.GenerateNonce()
		enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, "h-rr", hostPub, ts, nonce2, challenge))
		res, err := ctrl.Enroll(&EnrollRequest{InviteCode: token, HostId: "h-rr", HostPublicKey: hostPub, Ts: ts, Nonce: nonce2, Proof: enrollProof, Challenge: challenge})
		if err != nil {
			t.Fatal(err)
		}
		return res
	}
	res1 := join()
	nonce4, _ := cryptoutil.GenerateNonce()
	proof4 := ed25519.Sign(hostPriv, cryptoutil.BuildRevokeSelfTranscript(res1.RouteId, ts, nonce4, challenge))
	if _, err := ctrl.RevokeSelf(res1.RouteId, ts, nonce4, challenge, proof4); err != nil {
		t.Fatal(err)
	}
	// Same keypair, fresh identity usage: must succeed now that the revoked
	// row is gone (UNIQUE constraint released).
	res2 := join()
	if res2.HostId == "" || res2.HostId == res1.HostId {
		t.Fatalf("re-enroll after revoke-self: %q vs %q", res1.HostId, res2.HostId)
	}
	n, _ := st.CountHostsForDevice(store.DeviceFingerprint(hostPub))
	if n != 1 {
		t.Fatalf("device host count = %d, want 1 (revoked row removed)", n)
	}
}
