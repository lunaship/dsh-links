package control

import (
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/store"
)

// Control holds dependencies
type Control struct {
	store             *store.Store
	issuerPriv        ed25519.PrivateKey
	issuerPub         ed25519.PublicKey
	routeMasterKey    []byte // 32 bytes, if set; otherwise routeSecret derivation not available for direct enroll? But control derives secret
	defaultMaxStreams int

	// revokeFn is called after a successful RevokeHost (set by main.go to broadcast to relays).
	revokeFn func(routeId, hostId string)
}

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
	}, nil
}

func (c *Control) IssuerPublicKey() ed25519.PublicKey { return c.issuerPub }

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
}

// Enroll handles enrollment via control.
func (c *Control) Enroll(req *EnrollRequest) (*EnrollResult, error) {
	if len(req.HostId) == 0 || len([]byte(req.HostId)) > 64 || len(req.HostPublicKey) != ed25519.PublicKeySize || len(req.Nonce) != 16 || len(req.Challenge) != 32 || len(req.Proof) != ed25519.SignatureSize {
		return nil, errors.New("invalid enrollment fields")
	}
	if diff := req.Ts - time.Now().Unix(); diff < -60 || diff > 60 {
		return nil, errors.New("enroll timestamp outside allowed window")
	}
	// Reject unknown, expired, consumed, or revoked invites before signature
	// verification, random ID generation, capability signing, or writes. The
	// final atomic EnrollHost call revalidates and consumes the invite.
	if err := c.store.ValidateInvite(req.InviteCode); err != nil {
		return nil, errors.New("invite unavailable")
	}
	transcript := cryptoutil.BuildEnrollTranscript(req.InviteCode, req.HostId, req.HostPublicKey, req.Ts, req.Nonce, req.Challenge)
	pub := ed25519.PublicKey(req.HostPublicKey)
	if !ed25519.Verify(pub, transcript, req.Proof) {
		return nil, errors.New("enroll proof invalid")
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
	// Generation 1
	gen := uint64(1)
	// Sign capability
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
		Generation: gen,
		MaxStreams: c.defaultMaxStreams,
		Iat:        time.Now().Unix(),
		Exp:        time.Now().Add(30 * 24 * time.Hour).Unix(),
	}
	capStr, err := cryptoutil.SignCapability(c.issuerPriv, payload)
	if err != nil {
		return nil, err
	}
	hash := sha256.Sum256([]byte(capStr))
	host := &store.Host{
		ID: req.HostId, RouteID: routeId, HostName: req.HostId,
		HostPubKey: req.HostPublicKey, Generation: int64(gen),
		MaxStreams: c.defaultMaxStreams, Version: "v0.1.0",
	}
	if err := c.store.EnrollHost(req.InviteCode, host, hash[:], payload.Iat, payload.Exp); err != nil {
		return nil, fmt.Errorf("enroll transaction: %w", err)
	}
	return &EnrollResult{
		RouteId:     routeId,
		RouteSecret: secret,
		Capability:  capStr,
		Generation:  gen,
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
	transcript := cryptoutil.BuildRenewTranscript(req.OldCapability, req.Ts, req.Nonce, req.Challenge)
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
		Exp:        time.Now().Add(30 * 24 * time.Hour).Unix(),
	}
	capStr, err := cryptoutil.SignCapability(c.issuerPriv, payload)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(capStr))
	_, _ = c.store.CreateCredential(req.HostId, hash[:], int64(payload.Generation), payload.Iat, payload.Exp)
	return capStr, nil
}

func buildRenewTranscript(capability string, ts int64, nonce, challenge []byte) []byte {
	return cryptoutil.BuildRenewTranscript(capability, ts, nonce, challenge)
}

// RevokeHost revokes host and increments generation, then calls revokeFn if set.
func (c *Control) RevokeHost(hostId string) error {
	if err := c.store.RevokeHost(hostId); err != nil {
		return err
	}
	if c.revokeFn != nil {
		// Look up routeId for the broadcast message.
		h, err := c.store.GetHostByID(hostId)
		if err == nil && h != nil {
			routeStr := base64.RawURLEncoding.EncodeToString(h.RouteID)
			c.revokeFn(routeStr, hostId)
		}
	}
	return nil
}

// SetRevokeFn sets the post-revoke callback (called from main.go with IPC server).
func (c *Control) SetRevokeFn(fn func(routeId, hostId string)) {
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

func (c *Control) GetHostByRoute(routeId []byte) (*store.Host, error) {
	return c.store.GetHostByRoute(routeId)
}
