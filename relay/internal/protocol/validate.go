package protocol

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
)

// ValidHostID reports whether s is an accepted host identifier.
//
// hostId must be a 1..64 byte label drawn from the URL-safe alphabet
// [A-Za-z0-9_-]. This keeps every enrolled host addressable through the admin
// API URL path segment /v1/hosts/{id}/revoke (a slash, dot or other character
// would either break path routing or shadow the purge endpoint). The plugin
// currently generates dsh-<hex> ids, which satisfies the rule.
func ValidHostID(s string) bool {
	if len(s) == 0 || len(s) > 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= 'a' && c <= 'z', c >= 'A' && c <= 'Z', c >= '0' && c <= '9', c == '-', c == '_':
		default:
			return false
		}
	}
	return true
}

// SanitizeHostName turns an optional ENROLL display label into a short
// operator-facing name. It is not identity: hostId remains the stable key.
// Empty or garbage input falls back to fallback (typically hostId).
func SanitizeHostName(name, fallback string) string {
	var b strings.Builder
	n := 0
	for _, r := range strings.TrimSpace(name) {
		if r < 32 || r == 127 || unicode.IsControl(r) {
			continue
		}
		n++
		if n > 64 {
			break
		}
		b.WriteRune(r)
	}
	out := strings.TrimSpace(b.String())
	if out == "" {
		return fallback
	}
	return out
}

func ValidateHello(raw []byte) (*HelloFrame, error) {
	var f HelloFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeHello {
		return nil, fmt.Errorf("%s: not HELLO", ErrBadRequest)
	}
	if f.V != 1 {
		return nil, fmt.Errorf("%s: unsupported version %d", ErrBadRequest, f.V)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Challenge, 32); err != nil {
		return nil, fmt.Errorf("%s: invalid challenge: %w", ErrBadRequest, err)
	}
	return &f, nil
}

func ValidateEnroll(raw []byte) (*EnrollFrame, error) {
	var f EnrollFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeEnroll {
		return nil, fmt.Errorf("%s: not ENROLL", ErrBadRequest)
	}
	if !ValidHostID(f.HostId) {
		return nil, fmt.Errorf("%s: invalid hostId", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.HostPublicKey, 32); err != nil {
		return nil, fmt.Errorf("%s: hostPublicKey: %w", ErrBadRequest, err)
	}
	if len(f.InviteCode) == 0 {
		return nil, fmt.Errorf("%s: missing inviteCode", ErrBadRequest)
	}
	// inviteCode is either the invite code (base64url 32 chars) or a JWS
	// bootstrap token (two dots); a JWS bypasses the base64 check — control
	// decides which path applies.
	if strings.Count(f.InviteCode, ".") == 2 {
		parts := strings.Split(f.InviteCode, ".")
		for _, p := range parts {
			if p == "" {
				return nil, fmt.Errorf("%s: inviteCode jws malformed", ErrBadRequest)
			}
		}
	} else if _, err := base64.RawURLEncoding.DecodeString(f.InviteCode); err != nil {
		return nil, fmt.Errorf("%s: inviteCode b64: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Nonce, 16); err != nil {
		return nil, fmt.Errorf("%s: nonce: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Proof, 64); err != nil {
		return nil, fmt.Errorf("%s: proof: %w", ErrBadRequest, err)
	}
	if err := ValidateTs(f.Ts, time.Now()); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	return &f, nil
}

func ValidateRegister(raw []byte) (*RegisterFrame, error) {
	var f RegisterFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeRegister {
		return nil, fmt.Errorf("%s: not REGISTER", ErrBadRequest)
	}
	if len(f.Capability) == 0 {
		return nil, fmt.Errorf("%s: missing capability", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Nonce, 16); err != nil {
		return nil, fmt.Errorf("%s: nonce: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Proof, 64); err != nil {
		return nil, fmt.Errorf("%s: proof: %w", ErrBadRequest, err)
	}
	if err := ValidateTs(f.Ts, time.Now()); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	return &f, nil
}

func ValidateConnect(raw []byte) (*ConnectFrame, error) {
	var f ConnectFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeConnect {
		return nil, fmt.Errorf("%s: not CONNECT", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Route, 16); err != nil {
		return nil, fmt.Errorf("%s: route: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Nonce, 16); err != nil {
		return nil, fmt.Errorf("%s: nonce: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Mac, 32); err != nil {
		return nil, fmt.Errorf("%s: mac: %w", ErrBadRequest, err)
	}
	if err := ValidateTs(f.Ts, time.Now()); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	return &f, nil
}

func ValidateBind(raw []byte) (*BindFrame, error) {
	var f BindFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeBind {
		return nil, fmt.Errorf("%s: not BIND", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Route, 16); err != nil {
		return nil, fmt.Errorf("%s: route: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Stream, 16); err != nil {
		return nil, fmt.Errorf("%s: stream: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Nonce, 16); err != nil {
		return nil, fmt.Errorf("%s: nonce: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Mac, 32); err != nil {
		return nil, fmt.Errorf("%s: mac: %w", ErrBadRequest, err)
	}
	if err := ValidateTs(f.Ts, time.Now()); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	// generation is uint64, no extra check
	return &f, nil
}

func ValidateRenew(raw []byte) (*RenewFrame, error) {
	var f RenewFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeRenew {
		return nil, fmt.Errorf("%s: not RENEW", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Nonce, 16); err != nil {
		return nil, fmt.Errorf("%s: nonce: %w", ErrBadRequest, err)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.Proof, 64); err != nil {
		return nil, fmt.Errorf("%s: proof: %w", ErrBadRequest, err)
	}
	if err := ValidateTs(f.Ts, time.Now()); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	return &f, nil
}

// Helper to emit Error frame bytes
func MarshalError(code, msg string) []byte {
	f := ErrorFrame{Type: TypeError, Code: code, Message: msg}
	b, _ := json.Marshal(f)
	b = append(b, '\n')
	return b
}

// Strict check for message not containing secrets: caller must ensure.
var ErrInvalidFrame = errors.New("invalid frame")

// ValidateBootstrap verifies a BOOTSTRAP frame. ts freshness is enforced by
// the ingress handler (same window as ENROLL).
func ValidateBootstrap(raw []byte) (*BootstrapFrame, error) {
	var f BootstrapFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeBootstrap {
		return nil, fmt.Errorf("%s: type", ErrBadRequest)
	}
	pub, err := base64.RawURLEncoding.DecodeString(f.PubKey)
	if err != nil || len(pub) != ed25519.PublicKeySize {
		return nil, fmt.Errorf("%s: pubkey", ErrBadRequest)
	}
	nonce, err := base64.RawURLEncoding.DecodeString(f.Nonce)
	if err != nil || len(nonce) != 16 {
		return nil, fmt.Errorf("%s: nonce", ErrBadRequest)
	}
	proof, err := base64.RawURLEncoding.DecodeString(f.Proof)
	if err != nil || len(proof) != ed25519.SignatureSize {
		return nil, fmt.Errorf("%s: proof", ErrBadRequest)
	}
	return &f, nil
}

// ValidateRevokeSelf verifies a REVOKE_SELF frame. ts freshness is enforced
// by the ingress handler (same window as ENROLL).
func ValidateRevokeSelf(raw []byte) (*RevokeSelfFrame, error) {
	var f RevokeSelfFrame
	if err := json.Unmarshal(raw, &f); err != nil {
		return nil, fmt.Errorf("%s: %w", ErrBadRequest, err)
	}
	if f.Type != TypeRevokeSelf {
		return nil, fmt.Errorf("%s: type", ErrBadRequest)
	}
	route, err := base64.RawURLEncoding.DecodeString(f.RouteId)
	if err != nil || len(route) != 16 {
		return nil, fmt.Errorf("%s: routeId", ErrBadRequest)
	}
	nonce, err := base64.RawURLEncoding.DecodeString(f.Nonce)
	if err != nil || len(nonce) != 16 {
		return nil, fmt.Errorf("%s: nonce", ErrBadRequest)
	}
	proof, err := base64.RawURLEncoding.DecodeString(f.Proof)
	if err != nil || len(proof) != ed25519.SignatureSize {
		return nil, fmt.Errorf("%s: proof", ErrBadRequest)
	}
	return &f, nil
}
