package cryptoutil

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"
)

// BootstrapTokenTTL limits how long a bootstrap token stays valid. The token
// only authorizes one anonymous ENROLL; it is deliberately short so a leaked
// QR or token cannot mint hosts for long.
const BootstrapTokenTTL = 10 * time.Minute

// BootstrapScope marks a token as usable solely for anonymous enrollment.
const BootstrapScope = "bootstrap"

// BootstrapTokenPayload is the JWS payload of a bootstrap token. Sub is the
// hex fingerprint of the device public key that must sign the subsequent
// ENROLL; Scope restricts the token to enrollment.
type BootstrapTokenPayload struct {
	Iss   string `json:"iss"`
	Sub   string `json:"sub"`
	Scope string `json:"scope"`
	Iat   int64  `json:"iat"`
	Exp   int64  `json:"exp"`
}

// SignBootstrapToken creates a JWS compact token bound to the device key
// fingerprint. Uses the same issuer key and header as capabilities.
func SignBootstrapToken(priv ed25519.PrivateKey, sub string) (string, error) {
	payload := BootstrapTokenPayload{
		Iss:   "dsh-links-relay",
		Sub:   sub,
		Scope: BootstrapScope,
		Iat:   time.Now().Unix(),
		Exp:   time.Now().Add(BootstrapTokenTTL).Unix(),
	}
	payloadBytes, err := json.Marshal(payload)
	if err != nil {
		return "", err
	}
	payloadB64 := base64.RawURLEncoding.EncodeToString(payloadBytes)
	signingInput := headerB64 + "." + payloadB64
	sig := ed25519.Sign(priv, []byte(signingInput))
	sigB64 := base64.RawURLEncoding.EncodeToString(sig)
	return signingInput + "." + sigB64, nil
}

// VerifyBootstrapToken verifies the JWS signature, scope and expiry.
func VerifyBootstrapToken(pub ed25519.PublicKey, token string) (*BootstrapTokenPayload, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, errors.New("invalid JWS compact: not 3 parts")
	}
	if parts[0] != headerB64 {
		return nil, fmt.Errorf("invalid header: got %s want %s", parts[0], headerB64)
	}
	signingInput := parts[0] + "." + parts[1]
	sig, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return nil, fmt.Errorf("invalid signature b64: %w", err)
	}
	if len(sig) != ed25519.SignatureSize {
		return nil, errors.New("invalid signature length")
	}
	if !ed25519.Verify(pub, []byte(signingInput), sig) {
		return nil, errors.New("signature verification failed")
	}
	payloadBytes, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return nil, fmt.Errorf("invalid payload b64: %w", err)
	}
	var p BootstrapTokenPayload
	if err := json.Unmarshal(payloadBytes, &p); err != nil {
		return nil, fmt.Errorf("invalid payload json: %w", err)
	}
	if p.Scope != BootstrapScope {
		return nil, errors.New("token scope is not bootstrap")
	}
	if p.Exp < time.Now().Unix() {
		return nil, errors.New("bootstrap token expired")
	}
	return &p, nil
}