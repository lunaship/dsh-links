package protocol

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

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
	if len(f.HostId) == 0 || len(f.HostId) > 64 {
		return nil, fmt.Errorf("%s: hostId length", ErrBadRequest)
	}
	if _, err := cryptoutil.DecodeBase64URLLen(f.HostPublicKey, 32); err != nil {
		return nil, fmt.Errorf("%s: hostPublicKey: %w", ErrBadRequest, err)
	}
	if len(f.InviteCode) == 0 {
		return nil, fmt.Errorf("%s: missing inviteCode", ErrBadRequest)
	}
	// inviteCode is base64url 32 chars (24 bytes) but we just check decode possible
	if _, err := base64.RawURLEncoding.DecodeString(f.InviteCode); err != nil {
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
