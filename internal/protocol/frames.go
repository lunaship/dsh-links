package protocol

import (
	"encoding/json"
	"fmt"
)

// Frame type constants
const (
	TypeHello      = "HELLO"
	TypeEnroll     = "ENROLL"
	TypeEnrolled   = "ENROLLED"
	TypeBootstrap  = "BOOTSTRAP"
	TypeBootstrapped = "BOOTSTRAPPED"
	TypeRevokeSelf = "REVOKE_SELF"
	TypeRevoked     = "REVOKED"
	TypeRegister   = "REGISTER"
	TypeRegistered = "REGISTERED"
	TypeConnect    = "CONNECT"
	TypeOpen       = "OPEN"
	TypeBind       = "BIND"
	TypeReady      = "READY"
	TypeError      = "ERROR"
	TypePing       = "PING"
	TypePong       = "PONG"
	TypeRenew      = "RENEW"
	TypeRenewed    = "RENEWED"
)

// Max frame bytes (JSON without LF)
const (
	MaxHello      = 768
	MaxConnect    = 768
	MaxBind       = 768
	MaxReady      = 768
	MaxError      = 768
	MaxRegister   = 2048
	MaxEnroll     = 2048
	MaxRenew      = 2048
	MaxOpen       = 768
	MaxPingPong   = 768
	MaxEnrolled   = 2048
	MaxRegistered = 768
	MaxRenewed    = 2048
)

// Frames

type HelloFrame struct {
	Type      string `json:"type"`
	V         int    `json:"v"`
	Challenge string `json:"challenge"` // base64url 32B
	Ts        int64  `json:"ts,omitempty"`
}

type EnrollFrame struct {
	Type          string `json:"type"`
	InviteCode    string `json:"inviteCode"`
	HostId        string `json:"hostId"`
	HostPublicKey string `json:"hostPublicKey"` // base64url 32B
	Ts            int64  `json:"ts"`
	Nonce         string `json:"nonce"` // base64url 16B
	Proof         string `json:"proof"` // base64url 64B
}

// BootstrapFrame requests a short-lived bootstrap token. The device submits
// its public key with a proof of possession; the relay signs a token that
// authorizes exactly one anonymous ENROLL (inviteCode field = token).
type BootstrapFrame struct {
	Type   string `json:"type"`
	PubKey string `json:"pubkey"` // base64url 32B
	Ts     int64  `json:"ts"`
	Nonce  string `json:"nonce"` // base64url 16B
	Proof  string `json:"proof"` // base64url 64B
}

type BootstrappedFrame struct {
	Type  string `json:"type"`
	Token string `json:"token"` // JWS compact, ~10 min TTL
}

// RevokeSelfFrame asks the relay to revoke the host owning this route. The
// device proves possession of the host key; no admin involvement needed.
type RevokeSelfFrame struct {
	Type    string `json:"type"`
	RouteId string `json:"routeId"` // base64url 16B
	Ts      int64  `json:"ts"`
	Nonce   string `json:"nonce"` // base64url 16B
	Proof   string `json:"proof"` // base64url 64B
}

type RevokedFrame struct {
	Type string `json:"type"`
}

type EnrolledFrame struct {
	Type        string `json:"type"`
	RouteId     string `json:"routeId"`     // base64url 16B
	RouteSecret string `json:"routeSecret"` // base64url 32B
	Capability  string `json:"capability"`  // JWS
	Generation  uint64 `json:"generation"`
	// HostId is the stored host identifier; for anonymous bootstrap enrollment
	// it is the server-assigned id (the client's placeholder is discarded).
	HostId string `json:"hostId"`
}

type RegisterFrame struct {
	Type       string `json:"type"`
	Capability string `json:"capability"`
	Ts         int64  `json:"ts"`
	Nonce      string `json:"nonce"`
	Proof      string `json:"proof"`
}

type RegisteredFrame struct {
	Type       string `json:"type"`
	Generation uint64 `json:"generation"`
	Heartbeat  int    `json:"heartbeat"`
}

type ConnectFrame struct {
	Type  string `json:"type"`
	Route string `json:"route"` // routeId b64u 16B
	Ts    int64  `json:"ts"`
	Nonce string `json:"nonce"`
	Mac   string `json:"mac"` // b64u 32B
}

type OpenFrame struct {
	Type       string `json:"type"`
	Stream     string `json:"stream"` // b64u 16B
	Generation uint64 `json:"generation"`
}

type BindFrame struct {
	Type       string `json:"type"`
	Route      string `json:"route"`
	Stream     string `json:"stream"`
	Generation uint64 `json:"generation"`
	Ts         int64  `json:"ts"`
	Nonce      string `json:"nonce"`
	Mac        string `json:"mac"`
}

type ReadyFrame struct {
	Type   string `json:"type"`
	Stream string `json:"stream"`
}

type ErrorFrame struct {
	Type    string `json:"type"`
	Code    string `json:"code"`
	Message string `json:"message"`
}

type PingFrame struct {
	Type string `json:"type"`
}

type PongFrame struct {
	Type  string `json:"type"`
	Nonce string `json:"nonce,omitempty"`
}

type RenewFrame struct {
	Type  string `json:"type"`
	Ts    int64  `json:"ts"`
	Nonce string `json:"nonce"`
	Proof string `json:"proof"`
}

type RenewedFrame struct {
	Type       string `json:"type"`
	Capability string `json:"capability"`
}

// Generic detection

type genericFrame struct {
	Type string `json:"type"`
}

// Marshal helpers
func MarshalFrame(v interface{}) ([]byte, error) {
	b, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	b = append(b, '\n')
	return b, nil
}

func DetectType(data []byte) (string, error) {
	var g genericFrame
	if err := json.Unmarshal(data, &g); err != nil {
		return "", fmt.Errorf("invalid json: %w", err)
	}
	if g.Type == "" {
		return "", fmt.Errorf("missing type")
	}
	return g.Type, nil
}
