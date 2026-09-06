package cryptoutil

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

var headerB64 = base64.RawURLEncoding.EncodeToString([]byte(`{"alg":"EdDSA","typ":"DLR-CAP","v":1}`))

// CapabilityPayload as per spec
type CapabilityPayload struct {
	Iss        string `json:"iss"`
	Jti        string `json:"jti"`     // base64url 16B
	Host       string `json:"host"`    // hostId
	Route      string `json:"route"`   // base64url 16B
	HostPK     string `json:"host_pk"` // base64url 32B
	Generation uint64 `json:"generation"`
	MaxStreams int    `json:"max_streams"`
	Iat        int64  `json:"iat"`
	Exp        int64  `json:"exp"`
}

// SignCapability creates JWS Compact Serialization.
func SignCapability(priv ed25519.PrivateKey, payload CapabilityPayload) (string, error) {
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

// VerifyCapability verifies JWS and returns payload.
func VerifyCapability(pub ed25519.PublicKey, token string) (*CapabilityPayload, error) {
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
	var payload CapabilityPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		return nil, fmt.Errorf("invalid payload json: %w", err)
	}
	// Length checks
	if _, err := DecodeBase64URLLen(payload.Route, 16); err != nil {
		return nil, fmt.Errorf("invalid route: %w", err)
	}
	if _, err := DecodeBase64URLLen(payload.Jti, 16); err != nil {
		return nil, fmt.Errorf("invalid jti: %w", err)
	}
	if _, err := DecodeBase64URLLen(payload.HostPK, 32); err != nil {
		return nil, fmt.Errorf("invalid host_pk: %w", err)
	}
	if len(payload.Host) == 0 || len(payload.Host) > 64 {
		return nil, errors.New("host length invalid")
	}
	if payload.MaxStreams < 1 || payload.MaxStreams > 32 {
		return nil, errors.New("max_streams out of range")
	}
	if payload.Exp <= payload.Iat {
		return nil, errors.New("exp must be > iat")
	}
	return &payload, nil
}

// ExtractHeaderPayload splits without verifying sig, for testing.
func ParseJWSParts(token string) (headerB64, payloadB64, sigB64 string, err error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return "", "", "", errors.New("not 3 parts")
	}
	return parts[0], parts[1], parts[2], nil
}

// CapabilityHash returns SHA256 of compact bytes for DB unique constraint.
func CapabilityHash(compact string) []byte {
	h := SHA256([]byte(compact))
	return h
}
