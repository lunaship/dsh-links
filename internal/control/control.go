package control

import (
	"bytes"
	"crypto/ed25519"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"sync/atomic"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/protocol"
	"github.com/dsh-links/dsh-links-relay/internal/store"
)

// Control holds dependencies
type Control struct {
	store             *store.Store
	issuerPriv        ed25519.PrivateKey
	issuerPub         ed25519.PublicKey
	routeMasterKey    []byte // 32 bytes, if set; otherwise routeSecret derivation not available for direct enroll? But control derives secret
	defaultMaxStreams int

	// revokeFn is called after a successful RevokeHost (set by main.go to
	// broadcast to relays). It reports how many relays received the push and
	// how many confirmed the route was closed locally.
	revokeFn func(routeId, hostId string) (delivered, acked int)

	// capTTL is the lifetime of newly issued capabilities (config
	// capability_ttl). Set explicitly by main.go; New defaults to 30 days to
	// preserve behavior for tests and existing deployments that do not
	// configure it.
	capTTL time.Duration
	// anonymous policy: anonymousMaxHosts caps hosts per device; anonymous-
	// enrolled routes get anonymousDailyBytes daily budget (0 = unlimited);
	// anonymousMaxStreams caps simultaneous streams per anonymous route.
	anonymousMaxHosts   int
	anonymousMaxStreams int
	anonymousDailyBytes int64
	// anonymousEnabled is the runtime anonymous-enrollment kill switch
	// (initialized from config, toggleable via admin API, persisted in
	// settings).
	anonymousEnabled atomic.Bool
}

// maxCredentialsPerHost caps stored credentials per host so repeated renewals
// (legitimate or abusive) cannot grow the table without bound.
const maxCredentialsPerHost = 8

// New creates control.
func New(st *store.Store, issuerPriv []byte, routeMasterKey []byte, defaultMaxStreams int) (*Control, error) {
	var priv ed25519.PrivateKey
	if len(issuerPriv) == 32 {
		priv = ed25519.NewKeyFromSeed(issuerPriv)
	} else if len(issuerPriv) == 64 {
		priv = ed25519.PrivateKey(issuerPriv)
	} else {
		return nil, errors.New("issuer priv must be 32 or 64 bytes")
	}
	pub := priv.Public().(ed25519.PublicKey)
	if len(routeMasterKey) != 32 {
		return nil, errors.New("routeMasterKey must be 32 bytes")
	}
	return &Control{
		store:             st,
		issuerPriv:        priv,
		issuerPub:         pub,
		routeMasterKey:    routeMasterKey,
		defaultMaxStreams: defaultMaxStreams,
		capTTL:            30 * 24 * time.Hour,
	}, nil
}

// SetCapabilityTTL changes the lifetime of newly issued capabilities.
func (c *Control) SetCapabilityTTL(ttl time.Duration) {
	if ttl <= 0 {
		return
	}
	c.capTTL = ttl
}

// AnonymousPolicy carries the config-derived anonymous enrollment limits.
type AnonymousPolicy struct {
	Enabled    bool
	MaxHosts   int
	MaxStreams int
	DailyBytes int64
}

// ApplyAnonymousPolicy sets the policy limits and the runtime kill switch.
func (c *Control) ApplyAnonymousPolicy(p AnonymousPolicy) {
	if p.MaxHosts > 0 {
		c.anonymousMaxHosts = p.MaxHosts
	}
	if p.MaxStreams > 0 {
		c.anonymousMaxStreams = p.MaxStreams
	}
	c.anonymousDailyBytes = p.DailyBytes
	c.anonymousEnabled.Store(p.Enabled)
}

// AnonymousEnabled reports whether self-service enrollment is currently
// accepting bootstrap tokens.
func (c *Control) AnonymousEnabled() bool {
	return c.anonymousEnabled.Load()
}

// SetAnonymousEnabled flips the runtime kill switch (persisted to settings so
// it survives restarts).
func (c *Control) SetAnonymousEnabled(enabled bool) error {
	c.anonymousEnabled.Store(enabled)
	v := "0"
	if enabled {
		v = "1"
	}
	return c.store.SetSetting(SettingAnonymousEnroll, v)
}

// SettingAnonymousEnroll is the persistent settings key for the kill switch.
const SettingAnonymousEnroll = "anonymous_enroll"

func (c *Control) IssuerPublicKey() ed25519.PublicKey { return c.issuerPub }

// RouteMACRequest contains the complete, bounded CONNECT/BIND transcript that
// Control authenticates on behalf of Relay. Keeping routeMasterKey behind this
// method prevents an internet-facing Relay process from deriving credentials
// for every route after a compromise.
type RouteMACRequest struct {
	Operation  string
	RouteID    []byte
	StreamID   []byte
	Generation *uint64
	Ts         int64
	Nonce      []byte
	Challenge  []byte
	MAC        []byte
}

func (c *Control) VerifyRouteMAC(req RouteMACRequest) error {
	if len(req.RouteID) != 16 || len(req.Nonce) != 16 || len(req.Challenge) != 32 || len(req.MAC) != sha256.Size {
		return errors.New("invalid route MAC fields")
	}
	switch req.Operation {
	case "CONNECT":
		if len(req.StreamID) != 0 || req.Generation != nil {
			return errors.New("invalid CONNECT transcript")
		}
	case "BIND":
		if len(req.StreamID) != 16 || req.Generation == nil {
			return errors.New("invalid BIND transcript")
		}
	default:
		return errors.New("invalid route MAC operation")
	}
	if diff := req.Ts - time.Now().Unix(); diff < -60 || diff > 60 {
		return errors.New("route MAC timestamp outside allowed window")
	}
	secret, err := cryptoutil.DeriveRouteSecret(c.routeMasterKey, req.RouteID)
	if err != nil {
		return err
	}
	transcript := cryptoutil.BuildMACTranscript(req.Operation, req.RouteID, req.StreamID, req.Generation, req.Ts, req.Nonce, req.Challenge)
	if !cryptoutil.VerifyMAC(secret, transcript, req.MAC) {
		return errors.New("route MAC invalid")
	}
	return nil
}

// EnrollRequest from agent via relay
type EnrollRequest struct {
	InviteCode    string
	HostId        string
	HostPublicKey []byte // 32 bytes
	Ts            int64
	Nonce         []byte // 16
	Challenge     []byte // 32
	Proof         []byte // 64
}

// EnrollResult
type EnrollResult struct {
	RouteId     []byte
	RouteSecret []byte
	Capability  string
	Generation  uint64
	// HostId is the stored host identifier. For anonymous bootstrap
	// enrollment the server assigns it (client-side hostId is a placeholder);
	// invite enrollment returns the requested hostId unchanged.
	HostId string
}

// Enroll handles enrollment via control.
func (c *Control) Enroll(req *EnrollRequest) (*EnrollResult, error) {
	if !protocol.ValidHostID(req.HostId) || len(req.HostPublicKey) != ed25519.PublicKeySize || len(req.Nonce) != 16 || len(req.Challenge) != 32 || len(req.Proof) != ed25519.SignatureSize {
		return nil, errors.New("invalid enrollment fields")
	}
	if diff := req.Ts - time.Now().Unix(); diff < -60 || diff > 60 {
		return nil, errors.New("enroll timestamp outside allowed window")
	}
	// Reject unknown, expired, consumed, or revoked invites before signature
	// verification, random ID generation, capability signing, or writes. The
	// final atomic EnrollHost call revalidates and consumes the invite.
	if isBootstrapToken(req.InviteCode) {
		return c.enrollWithBootstrap(req)
	}
	if err := c.store.ValidateInvite(req.InviteCode); err != nil {
		return nil, errors.New("invite unavailable")
	}
	transcript := cryptoutil.BuildEnrollTranscript(req.InviteCode, req.HostId, req.HostPublicKey, req.Ts, req.Nonce, req.Challenge)
	pub := ed25519.PublicKey(req.HostPublicKey)
	if !ed25519.Verify(pub, transcript, req.Proof) {
		return nil, errors.New("enroll proof invalid")
	}
	if existing, err := c.store.GetHostByID(req.HostId); err == nil {
		if !bytes.Equal(existing.HostPubKey, req.HostPublicKey) {
			return nil, errors.New("host id already registered")
		}
	} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	if byPub, err := c.store.GetHostByPubKey(req.HostPublicKey); err == nil {
		if byPub.ID != req.HostId {
			return nil, errors.New("host key already registered")
		}
	} else if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	// Generate routeId
	routeId, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return nil, err
	}
	// Derive routeSecret
	secret, err := cryptoutil.DeriveRouteSecret(c.routeMasterKey, routeId)
	if err != nil {
		return nil, err
	}
	host := &store.Host{
		ID: req.HostId, RouteID: routeId, HostName: req.HostId,
		HostPubKey: req.HostPublicKey,
		MaxStreams: c.defaultMaxStreams, Version: "v0.1.0",
	}
	var capStr string
	replacedRouteID, err := c.store.EnrollHost(req.InviteCode, host, func(generation int64) (*store.EnrollMaterial, error) {
		jti, err := cryptoutil.RandomBytes(16)
		if err != nil {
			return nil, err
		}
		payload := cryptoutil.CapabilityPayload{
			Iss:        "dsh-links-relay",
			Jti:        base64.RawURLEncoding.EncodeToString(jti),
			Host:       req.HostId,
			Route:      base64.RawURLEncoding.EncodeToString(routeId),
			HostPK:     base64.RawURLEncoding.EncodeToString(req.HostPublicKey),
			Generation: uint64(generation),
			MaxStreams: c.defaultMaxStreams,
			Iat:        time.Now().Unix(),
			Exp:        time.Now().Add(c.capTTL).Unix(),
		}
		signed, err := cryptoutil.SignCapability(c.issuerPriv, payload)
		if err != nil {
			return nil, err
		}
		capStr = signed
		hash := sha256.Sum256([]byte(signed))
		return &store.EnrollMaterial{CapabilityHash: hash[:], IssuedAt: payload.Iat, ExpiresAt: payload.Exp}, nil
	})
	if err != nil {
		return nil, fmt.Errorf("enroll transaction: %w", err)
	}
	if len(replacedRouteID) > 0 && c.revokeFn != nil {
		c.revokeFn(base64.RawURLEncoding.EncodeToString(replacedRouteID), req.HostId)
	}
	return &EnrollResult{
		RouteId:     routeId,
		RouteSecret: secret,
		Capability:  capStr,
		Generation:  uint64(host.Generation),
		HostId:      host.ID,
	}, nil
}

// RenewRequest for capability renewal via authenticated control connection.
type RenewRequest struct {
	RouteId       []byte
	HostId        string
	HostPubKey    []byte
	Ts            int64
	Nonce         []byte
	Challenge     []byte
	Proof         []byte
	OldCapability string
}

// Renew issues new capability with same routeId/generation? Actually generation stays same unless revoked.
// Spec: renewal keeps same generation but new exp/jti.
func (c *Control) Renew(req *RenewRequest) (string, error) {
	if len(req.HostId) == 0 || len([]byte(req.HostId)) > 64 || len(req.RouteId) != 16 || len(req.HostPubKey) != ed25519.PublicKeySize || len(req.Nonce) != 16 || len(req.Challenge) != 32 || len(req.Proof) != ed25519.SignatureSize {
		return "", errors.New("invalid renewal fields")
	}
	if diff := req.Ts - time.Now().Unix(); diff < -60 || diff > 60 {
		return "", errors.New("renew timestamp outside allowed window")
	}
	// Verify host exists and not revoked
	host, err := c.store.GetHostByID(req.HostId)
	if err != nil {
		return "", err
	}
	if host.RevokedAt != nil {
		return "", errors.New("host revoked")
	}
	if string(host.RouteID) != string(req.RouteId) {
		return "", errors.New("route mismatch")
	}
	if string(host.HostPubKey) != string(req.HostPubKey) {
		return "", errors.New("pubkey mismatch")
	}
	// The old capability itself must verify: signature, binding to this
	// host/route, and unexpired. Without the expiry check a holder of the
	// host key could renew forever past every capability deadline.
	// The expiry gate intentionally mirrors the relay control loop's grace
	// (cryptoutil.CapExpiryGrace): an agent that lets its capability lapse by
	// a few seconds inside that window is still allowed to renew. Otherwise the
	// relay would keep the session alive for the grace while this controller
	// refused the renewal, disconnecting an agent that made the deadline on one
	// side but not the other.
	oldPayload, err := cryptoutil.VerifyCapability(c.issuerPub, req.OldCapability)
	if err != nil {
		return "", errors.New("old capability invalid")
	}
	if oldPayload.Exp+cryptoutil.CapExpiryGrace < time.Now().Unix() {
		return "", errors.New("old capability expired")
	}
	if oldPayload.Host != req.HostId || oldPayload.Route != base64.RawURLEncoding.EncodeToString(req.RouteId) {
		return "", errors.New("old capability mismatch")
	}
	transcript := cryptoutil.BuildRenewTranscript(req.OldCapability, req.Ts, req.Nonce, req.Challenge)
	replayDigest := sha256.Sum256(transcript)
	replayed, err := c.store.IsRenewalReplay(replayDigest[:], time.Now().Unix())
	if err != nil {
		return "", fmt.Errorf("check renewal replay: %w", err)
	}
	if replayed {
		return "", errors.New("renew request replayed")
	}
	if !ed25519.Verify(ed25519.PublicKey(req.HostPubKey), transcript, req.Proof) {
		return "", errors.New("renew proof invalid")
	}
	// Issue new capability with fresh jti and exp
	jti, _ := cryptoutil.RandomBytes(16)
	payload := cryptoutil.CapabilityPayload{
		Iss:        "dsh-links-relay",
		Jti:        base64.RawURLEncoding.EncodeToString(jti),
		Host:       req.HostId,
		Route:      base64.RawURLEncoding.EncodeToString(req.RouteId),
		HostPK:     base64.RawURLEncoding.EncodeToString(req.HostPubKey),
		Generation: uint64(host.Generation),
		MaxStreams: host.MaxStreams,
		Iat:        time.Now().Unix(),
		Exp:        time.Now().Add(c.capTTL).Unix(),
	}
	capStr, err := cryptoutil.SignCapability(c.issuerPriv, payload)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(capStr))
	if err := c.store.RecordRenewal(req.HostId, replayDigest[:], hash[:], int64(payload.Generation), payload.Iat, payload.Exp, time.Now().Add(2*time.Minute).Unix(), maxCredentialsPerHost); err != nil {
		if errors.Is(err, store.ErrRenewalReplay) {
			return "", errors.New("renew request replayed")
		}
		// A capability that is not recorded must not be handed out: revocation
		// audits and credential tracking would silently diverge.
		return "", fmt.Errorf("record credential: %w", err)
	}
	return capStr, nil
}

func buildRenewTranscript(capability string, ts int64, nonce, challenge []byte) []byte {
	return cryptoutil.BuildRenewTranscript(capability, ts, nonce, challenge)
}

// RevokeHost revokes host and increments generation, then calls revokeFn if
// set. It returns the relay push delivery/ack counts observed by revokeFn
// (0/0 when no callback is configured).
func (c *Control) RevokeHost(hostId string) (delivered, acked int, err error) {
	if err := c.store.RevokeHost(hostId); err != nil {
		return 0, 0, err
	}
	if c.revokeFn != nil {
		// Look up routeId for the broadcast message.
		h, err := c.store.GetHostByID(hostId)
		if err == nil && h != nil {
			routeStr := base64.RawURLEncoding.EncodeToString(h.RouteID)
			delivered, acked = c.revokeFn(routeStr, hostId)
		}
	}
	return delivered, acked, nil
}

// DeleteHost disconnects a live host if needed, then removes the record.
func (c *Control) DeleteHost(hostId string) (delivered, acked int, err error) {
	h, err := c.store.GetHostByID(hostId)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return 0, 0, store.ErrHostNotFound
		}
		return 0, 0, err
	}
	routeStr := base64.RawURLEncoding.EncodeToString(h.RouteID)
	if h.RevokedAt == nil {
		if err := c.store.RevokeHost(hostId); err != nil {
			return 0, 0, err
		}
	}
	if c.revokeFn != nil {
		delivered, acked = c.revokeFn(routeStr, hostId)
	}
	if err := c.store.DeleteHost(hostId); err != nil {
		return delivered, acked, err
	}
	return delivered, acked, nil
}

func (c *Control) PurgeRevokedHosts() (int64, error) {
	list, err := c.store.ListRevokedHosts()
	if err != nil {
		return 0, err
	}
	if c.revokeFn != nil {
		for _, h := range list {
			c.revokeFn(base64.RawURLEncoding.EncodeToString(h.RouteID), h.ID)
		}
	}
	return c.store.PurgeRevokedHosts()
}

// ReportUsage accumulates routed byte/connect counters into stats_daily for
// the host owning the route. Unknown routes are ignored (they may have been
// purged since the relay last synced). Anonymous hosts whose accumulated
// daily usage crossed the budget get suspended until the next UTC midnight
// and their routes are pushed revoked immediately.
func (c *Control) ReportUsage(routeID []byte, rx, tx int64, connects int) error {
	h, err := c.store.GetHostByRoute(routeID)
	if err != nil || h == nil {
		return nil
	}
	if err := c.store.AddRouteUsage(h.ID, store.Today(), rx, tx, connects); err != nil {
		return err
	}
	if c.anonymousDailyBytes <= 0 || h.DeviceID == "" {
		return nil
	}
	// Budget check only applies to anonymous (device-linked) hosts.
	u, err := c.store.GetDailyUsage(h.ID, store.Today())
	if err != nil {
		return err
	}
	if u.RXBytes+u.TXBytes < c.anonymousDailyBytes {
		return nil
	}
	if h.SuspendedUntil != nil && *h.SuspendedUntil > time.Now().Unix() {
		return nil // already suspended
	}
	until := nextMidnightUTC()
	if err := c.store.SuspendHostUntil(h.ID, until); err != nil {
		return err
	}
	routeStr := base64.RawURLEncoding.EncodeToString(h.RouteID)
	if c.revokeFn != nil {
		c.revokeFn(routeStr, h.ID)
	}
	return nil
}

// SetRevokeFn sets the post-revoke callback (called from main.go with IPC server).
func (c *Control) SetRevokeFn(fn func(routeId, hostId string) (int, int)) {
	c.revokeFn = fn
}

// ListHosts returns hosts for admin API
func (c *Control) ListHosts() ([]store.Host, error) {
	return c.store.ListHosts()
}

// CreateInvite admin
func (c *Control) CreateInvite(ttl time.Duration) (string, error) {
	uid, err := c.store.EnsureDefaultUser()
	if err != nil {
		return "", err
	}
	code, _, err := c.store.CreateInvite(uid, ttl)
	return code, err
}

func (c *Control) ListInvites() ([]store.Invite, error) {
	return c.store.ListInvites()
}

func (c *Control) RevokeInvite(id string) error {
	return c.store.RevokeInvite(id)
}

func (c *Control) DeleteInvite(id string) error {
	return c.store.DeleteInvite(id)
}

func (c *Control) PurgeStaleInvites() (int64, error) {
	return c.store.PurgeStaleInvites(time.Now().Unix())
}

func (c *Control) GetHostByRoute(routeId []byte) (*store.Host, error) {
	return c.store.GetHostByRoute(routeId)
}

// LookupRouteStatus is the data-plane route status: a host whose device was
// disabled or whose daily budget was exhausted is immediately treated as
// revoked so relays stop serving its streams without waiting for an explicit
// revoke. Expired suspensions are cleared lazily here.
func (c *Control) LookupRouteStatus(routeId []byte) (hostID string, generation uint64, pubKey []byte, maxStreams int, revoked bool, err error) {
	l, err := c.store.GetHostByRouteWithDevice(routeId)
	if err != nil {
		return "", 0, nil, 0, false, err
	}
	h := l.Host
	revoked = h.RevokedAt != nil || !l.DeviceEnabled
	if h.SuspendedUntil != nil {
		now := time.Now().Unix()
		if *h.SuspendedUntil > now {
			revoked = true
		} else {
			// Window elapsed: budget resets, remove the flag lazily.
			_ = c.store.ClearSuspendedUntil(h.ID)
		}
	}
	return h.ID, uint64(h.Generation), h.HostPubKey, h.MaxStreams, revoked, nil
}

// nextMidnightUTC returns the unix time of the next 00:00 UTC, when a new
// daily budget begins.
func nextMidnightUTC() int64 {
	now := time.Now().UTC()
	next := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, time.UTC).Add(24 * time.Hour)
	return next.Unix()
}

// RevokedHosts returns hosts that are currently revoked. A (re)connecting
// relay uses this set to reconcile its in-memory registry with persisted
// revocations immediately, instead of polling every online session each second.
func (c *Control) RevokedHosts() ([]store.Host, error) {
	return c.store.ListRevokedHosts()
}

// isBootstrapToken distinguishes a JWS bootstrap token (two dots) from a
// plain invite code (URL-safe base64, no dots).
func isBootstrapToken(code string) bool {
	dots := 0
	for i := 0; i < len(code); i++ {
		if code[i] == '.' {
			dots++
		}
	}
	return dots == 2
}

// NewAnonymousHostID generates the server-assigned host identifier for
// self-service enrollment ("h-" + 16 random bytes in hex); the client-supplied
// placeholder is discarded per the anonymous model.
func NewAnonymousHostID() (string, error) {
	randBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return "", err
	}
	return "h-" + hex.EncodeToString(randBytes), nil
}

// Bootstrap issues a short-lived bootstrap token for a device public key
// after proving possession. The caller (ingress) is responsible for
// per-IP/prefix and global rate limiting; this method enforces the
// anonymous-enrollment kill switch and signs the token.
func (c *Control) Bootstrap(pubKey ed25519.PublicKey, ts int64, nonce, challenge, proof []byte) (string, error) {
	if len(pubKey) != ed25519.PublicKeySize || len(nonce) != 16 || len(challenge) != 32 || len(proof) != ed25519.SignatureSize {
		return "", errors.New("invalid bootstrap fields")
	}
	if diff := ts - time.Now().Unix(); diff < -60 || diff > 60 {
		return "", errors.New("bootstrap timestamp outside allowed window")
	}
	if !c.AnonymousEnabled() {
		return "", store.ErrDeviceNotAllowed
	}
	transcript := cryptoutil.BuildBootstrapTranscript(pubKey, ts, nonce, challenge)
	if !ed25519.Verify(pubKey, transcript, proof) {
		return "", errors.New("bootstrap proof invalid")
	}
	return cryptoutil.SignBootstrapToken(c.issuerPriv, store.DeviceFingerprint(pubKey))
}

// enrollWithBootstrap is the anonymous counterpart of Enroll: the inviteCode
// carries a bootstrap token, the client's hostId is only a placeholder for
// proof binding, the real hostId is server-assigned, and quota/device checks
// replace invite consumption.
func (c *Control) enrollWithBootstrap(req *EnrollRequest) (*EnrollResult, error) {
	if !c.AnonymousEnabled() {
		return nil, store.ErrDeviceNotAllowed
	}
	payload, err := cryptoutil.VerifyBootstrapToken(c.issuerPub, req.InviteCode)
	if err != nil {
		return nil, fmt.Errorf("bootstrap token: %w", err)
	}
	if payload.Sub != store.DeviceFingerprint(req.HostPublicKey) {
		return nil, errors.New("bootstrap token device mismatch")
	}
	device, err := c.store.EnsureDevice(ed25519.PublicKey(req.HostPublicKey), c.anonymousMaxHosts)
	if err != nil {
		return nil, err
	}
	if !device.Enabled {
		return nil, store.ErrDeviceDisabled
	}
	// Server-assigned hostId; the placeholder in the request is discarded.
	hostID, err := NewAnonymousHostID()
	if err != nil {
		return nil, err
	}
	routeId, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return nil, err
	}
	secret, err := cryptoutil.DeriveRouteSecret(c.routeMasterKey, routeId)
	if err != nil {
		return nil, err
	}
	streams := c.defaultMaxStreams
	if c.anonymousMaxStreams > 0 && c.anonymousMaxStreams < streams {
		streams = c.anonymousMaxStreams
	}
	host := &store.Host{
		ID: hostID, RouteID: routeId, HostName: hostID,
		HostPubKey: req.HostPublicKey,
		MaxStreams: streams, Version: "v0.1.0",
	}
	var capStr string
	err = c.store.EnrollAnonymousHost(device.ID, host, device.MaxHosts, func(generation int64) (*store.EnrollMaterial, error) {
		jti, err := cryptoutil.RandomBytes(16)
		if err != nil {
			return nil, err
		}
		payload := cryptoutil.CapabilityPayload{
			Iss:        "dsh-links-relay",
			Jti:        base64.RawURLEncoding.EncodeToString(jti),
			Host:       hostID,
			Route:      base64.RawURLEncoding.EncodeToString(routeId),
			HostPK:     base64.RawURLEncoding.EncodeToString(req.HostPublicKey),
			Generation: uint64(generation),
			MaxStreams: streams,
			Iat:        time.Now().Unix(),
			Exp:        time.Now().Add(c.capTTL).Unix(),
		}
		signed, err := cryptoutil.SignCapability(c.issuerPriv, payload)
		if err != nil {
			return nil, err
		}
		capStr = signed
		hash := sha256.Sum256([]byte(signed))
		return &store.EnrollMaterial{CapabilityHash: hash[:], IssuedAt: payload.Iat, ExpiresAt: payload.Exp}, nil
	})
	if err != nil {
		return nil, fmt.Errorf("anonymous enroll: %w", err)
	}
	return &EnrollResult{
		RouteId:     routeId,
		RouteSecret: secret,
		Capability:  capStr,
		Generation:  uint64(host.Generation),
		HostId:      hostID,
	}, nil
}

// RevokeSelf revokes the host owning routeId after verifying that the caller
// holds the host private key. It returns the revoked host ID ("" when the
// route is unknown, which the caller may treat as already-gone).
func (c *Control) RevokeSelf(routeId []byte, ts int64, nonce, challenge, proof []byte) (string, error) {
	if len(routeId) != 16 || len(nonce) != 16 || len(challenge) != 32 || len(proof) != ed25519.SignatureSize {
		return "", errors.New("invalid revoke-self fields")
	}
	h, err := c.store.GetHostByRoute(routeId)
	if err != nil {
		return "", nil // unknown route: nothing to revoke
	}
	if h.RevokedAt != nil {
		return h.ID, nil // idempotent
	}
	transcript := cryptoutil.BuildRevokeSelfTranscript(routeId, ts, nonce, challenge)
	if !ed25519.Verify(ed25519.PublicKey(h.HostPubKey), transcript, proof) {
		return "", errors.New("revoke-self proof invalid")
	}
	if _, _, err := c.RevokeHost(h.ID); err != nil {
		return "", err
	}
	return h.ID, nil
}

// DeviceInfo is the admin-visible device row with current host count.
type DeviceInfo struct {
	ID         string   `json:"id"`
	Enabled    bool     `json:"enabled"`
	MaxHosts   int      `json:"maxHosts"`
	HostCount  int      `json:"hostCount"`
	CreatedAt  int64    `json:"createdAt"`
	DisabledAt *int64   `json:"disabledAt,omitempty"`
	HostIDs    []string `json:"hostIds,omitempty"`
}

// ListDevices returns all anonymous devices with live host counts.
func (c *Control) ListDevices() ([]DeviceInfo, error) {
	devs, err := c.store.ListDevices()
	if err != nil {
		return nil, err
	}
	out := make([]DeviceInfo, 0, len(devs))
	for _, d := range devs {
		info := DeviceInfo{
			ID: d.ID, Enabled: d.Enabled, MaxHosts: d.MaxHosts,
			CreatedAt: d.CreatedAt.Unix(),
		}
		if d.DisabledAt != nil {
			v := d.DisabledAt.Unix()
			info.DisabledAt = &v
		}
		hosts, err := c.store.ListHostsByDevice(d.ID)
		if err == nil {
			info.HostCount = len(hosts)
			for _, h := range hosts {
				if h.RevokedAt == nil {
					info.HostIDs = append(info.HostIDs, h.ID)
				}
			}
		}
		out = append(out, info)
	}
	return out, nil
}

// DisableDevice disables an anonymous device and cascades: every live host of
// the device is revoked and pushed to relays immediately.
func (c *Control) DisableDevice(id string) error {
	hosts, err := c.store.ListHostsByDevice(id)
	if err != nil {
		return err
	}
	for _, h := range hosts {
		if h.RevokedAt == nil {
			if _, _, err := c.RevokeHost(h.ID); err != nil {
				return err
			}
		}
	}
	return c.store.SetDeviceEnabled(id, false)
}

// EnableDevice re-enables a previously disabled device. Its hosts stay
// revoked; the device may enroll fresh hosts up to its quota.
func (c *Control) EnableDevice(id string) error {
	return c.store.SetDeviceEnabled(id, true)
}

// DeleteDevice revokes all hosts and removes the device identity.
func (c *Control) DeleteDevice(id string) error {
	hosts, err := c.store.ListHostsByDevice(id)
	if err != nil {
		return err
	}
	for _, h := range hosts {
		if h.RevokedAt == nil {
			if _, _, err := c.RevokeHost(h.ID); err != nil {
				return err
			}
		}
	}
	return c.store.DeleteDevice(id)
}
