package cryptoutil

import (
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
)

// Base64URL without padding
var b64url = base64.RawURLEncoding

func EncodeBase64URL(b []byte) string {
	return b64url.EncodeToString(b)
}

func DecodeBase64URL(s string) ([]byte, error) {
	return b64url.DecodeString(s)
}

// Must be exactly n bytes after decoding.
func DecodeBase64URLLen(s string, want int) ([]byte, error) {
	b, err := b64url.DecodeString(s)
	if err != nil {
		return nil, err
	}
	if len(b) != want {
		return nil, fmt.Errorf("invalid length: got %d want %d", len(b), want)
	}
	return b, nil
}

// RandomBytes returns n cryptographically random bytes.
func RandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return nil, err
	}
	return b, nil
}

// GenerateRouteID creates a random 16-byte routeId.
func GenerateRouteID() ([]byte, error) {
	return RandomBytes(16)
}

// GenerateNonce 16 bytes.
func GenerateNonce() ([]byte, error) {
	return RandomBytes(16)
}

// GenerateChallenge 32 bytes.
func GenerateChallenge() ([]byte, error) {
	return RandomBytes(32)
}

// GenerateInviteCode 192-bit (24 bytes) base64url raw -> 32 chars.
func GenerateInviteCode() (string, error) {
	b, err := RandomBytes(24)
	if err != nil {
		return "", err
	}
	return b64url.EncodeToString(b), nil
}

// Ed25519

func GenerateEd25519KeyPair() (ed25519.PublicKey, ed25519.PrivateKey, error) {
	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	return pub, priv, err
}

func Ed25519PublicKeyFromPrivate(priv ed25519.PrivateKey) ed25519.PublicKey {
	return priv.Public().(ed25519.PublicKey)
}

// Sign signs msg with priv.
func Ed25519Sign(priv ed25519.PrivateKey, msg []byte) []byte {
	return ed25519.Sign(priv, msg)
}

// Verify checks sig.
func Ed25519Verify(pub ed25519.PublicKey, msg, sig []byte) bool {
	return ed25519.Verify(pub, msg, sig)
}

// HKDF-SHA256 per RFC 5869
// routeSecret = HKDF-SHA256(ikm=routeMasterKey, salt=routeId, info="DLR/1 route secret", length=32)

func HKDFSHA256(ikm, salt, info []byte, length int) []byte {
	// Extract
	prk := hkdfExtract(salt, ikm)
	// Expand
	return hkdfExpand(prk, info, length)
}

func hkdfExtract(salt, ikm []byte) []byte {
	if salt == nil {
		salt = make([]byte, sha256.Size)
	}
	mac := hmacSHA256(salt, ikm)
	return mac
}

func hkdfExpand(prk, info []byte, length int) []byte {
	hashLen := sha256.Size
	n := (length + hashLen - 1) / hashLen
	var okm []byte
	var t []byte
	for i := 1; i <= n; i++ {
		// T(i) = HMAC-SHA256(PRK, T(i-1) | info | 0x01)
		data := make([]byte, 0, len(t)+len(info)+1)
		data = append(data, t...)
		data = append(data, info...)
		data = append(data, byte(i))
		t = hmacSHA256(prk, data)
		okm = append(okm, t...)
	}
	return okm[:length]
}

func hmacSHA256(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	_, _ = m.Write(data)
	return m.Sum(nil)
}

// DeriveRouteSecret derives 32-byte routeSecret from master key and routeId raw.
func DeriveRouteSecret(routeMasterKey, routeId []byte) ([]byte, error) {
	if len(routeMasterKey) != 32 {
		return nil, errors.New("routeMasterKey must be 32 bytes")
	}
	if len(routeId) != 16 {
		return nil, errors.New("routeId must be 16 bytes")
	}
	info := []byte("DLR/1 route secret")
	return HKDFSHA256(routeMasterKey, routeId, info, 32), nil
}

// CapExpiryGrace (seconds) bounds how far past a capability's expiry both the
// relay control loop and the renewal controller still accept a late RENEW.
// The two MUST agree: the relay's control loop lets a live session renew up to
// `exp + CapExpiryGrace` to absorb clock skew, so the renewal controller must
// not reject an old capability that is still inside that same window. If they
// drift, an agent that lets its capability lapse by a moment is refused on one
// side while being welcomed on the other.
const CapExpiryGrace = 60

// Hash helpers

func SHA256(b []byte) []byte {
	h := sha256.Sum256(b)
	return h[:]
}
