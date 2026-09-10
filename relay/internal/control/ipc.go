package control

import (
	"bufio"
	"crypto/ed25519"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/store"
)

const (
	maxIPCFrameBytes            = 16 << 10
	maxIPCConnections           = 32
	maxIPCPreAuthConnections    = 8
	ipcPartialFrameTimeout      = 5 * time.Second
	ipcWriteTimeout             = 5 * time.Second
	ipcAuthenticatedIdleTimeout = 5 * time.Minute
	// ipcAuthTotalTimeout is an absolute wall-clock bound on the whole auth
	// frame. Per-segment timeouts alone allowed a byte-trickling peer to hold
	// an unauthenticated connection slot indefinitely.
	ipcAuthTotalTimeout = 10 * time.Second
)

var errIPCFrameTooLarge = errors.New("ipc frame too large")

// IPC via Unix socket for relay<->control
// Messages are LF-delimited JSON with {type, payload}
// Types: enroll, renew, lookup_host, verify_route_mac, revoke_notify,
// stats_report, host_heartbeat, host_offline

type IPCMessage struct {
	Type    string          `json:"type"`
	Payload json.RawMessage `json:"payload"`
}

type EnrollIPCRequest struct {
	InviteCode    string `json:"inviteCode"`
	HostId        string `json:"hostId"`
	HostPublicKey string `json:"hostPublicKey"` // b64u
	Ts            int64  `json:"ts"`
	Nonce         string `json:"nonce"`
	Proof         string `json:"proof"`
	Challenge     string `json:"challenge"`
	HostName      string `json:"hostName,omitempty"`
}

type EnrollIPCResponse struct {
	RouteId     string `json:"routeId"`
	RouteSecret string `json:"routeSecret"`
	Capability  string `json:"capability"`
	Generation  uint64 `json:"generation"`
	HostId      string `json:"hostId,omitempty"`
	Error       string `json:"error,omitempty"`
}

type RevokeIPCRequest struct {
	RouteId string `json:"routeId"`
	HostId  string `json:"hostId"`
}

type LookupHostIPCResponse struct {
	HostID        string `json:"hostId"`
	Generation    uint64 `json:"generation"`
	HostPublicKey string `json:"hostPublicKey"`
	MaxStreams    int    `json:"maxStreams"`
	Revoked       bool   `json:"revoked"`
	Suspended     bool   `json:"suspended,omitempty"`
	Error         string `json:"error,omitempty"`
}

type VerifyRouteMACIPCRequest struct {
	Operation  string  `json:"operation"`
	RouteID    string  `json:"routeId"`
	StreamID   string  `json:"streamId,omitempty"`
	Generation *uint64 `json:"generation,omitempty"`
	Ts         int64   `json:"ts"`
	Nonce      string  `json:"nonce"`
	Challenge  string  `json:"challenge"`
	MAC        string  `json:"mac"`
}

// IPCServer runs on control side
type IPCServer struct {
	control            *Control
	socket             string
	listener           net.Listener
	mu                 sync.Mutex
	clients            map[net.Conn]struct{}
	connections        map[net.Conn]struct{}
	writers            map[net.Conn]*sync.Mutex
	ackChans           map[net.Conn]chan RevokeAck
	preAuthConnections int
	revokeHandlers     []func(routeId string)
	// authToken is the shared IPC secret. The real trust boundary is the Unix
	// socket file permissions (0770, dedicated shared group) plus the container
	// network isolation between the control and relay roles; the handshake below
	// is only a shared-secret possession proof between those two trusted
	// processes. Note it is NOT encryption and NOT a replay defense against a
	// peer that already holds the token or a socket observer inside the allowed
	// group — the token and its derived HMAC both transit this socket in the
	// clear (key == data in the current scheme), so any party that can read the
	// socket stream can impersonate the relay. Keep the socket and container
	// boundaries as the authoritative control, not this frame.
	authToken string // empty = no auth; otherwise HMAC-SHA256 keyed
}

func NewIPCServer(ctrl *Control, socketPath string, authToken string) *IPCServer {
	return &IPCServer{
		control: ctrl, socket: socketPath, authToken: authToken,
		clients: make(map[net.Conn]struct{}), connections: make(map[net.Conn]struct{}), writers: make(map[net.Conn]*sync.Mutex),
		ackChans: make(map[net.Conn]chan RevokeAck),
	}
}

func (s *IPCServer) Start() error {
	dir := filepath.Dir(s.socket)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return err
	}
	_ = os.Remove(s.socket)
	ln, err := net.Listen("unix", s.socket)
	if err != nil {
		return err
	}
	// perm 0770 for group access
	_ = os.Chmod(s.socket, 0770)
	s.listener = ln
	go s.acceptLoop()
	return nil
}

func (s *IPCServer) Close() error {
	if s.listener != nil {
		s.listener.Close()
		_ = os.Remove(s.socket)
	}
	s.mu.Lock()
	clients := make([]net.Conn, 0, len(s.connections))
	for conn := range s.connections {
		clients = append(clients, conn)
	}
	s.mu.Unlock()
	for _, conn := range clients {
		_ = conn.Close()
	}
	return nil
}

func (s *IPCServer) OnRevoke(fn func(routeId string)) {
	s.mu.Lock()
	s.revokeHandlers = append(s.revokeHandlers, fn)
	s.mu.Unlock()
}

func (s *IPCServer) acceptLoop() {
	for {
		conn, err := s.listener.Accept()
		if err != nil {
			return
		}
		s.mu.Lock()
		if len(s.connections) >= maxIPCConnections || (s.authToken != "" && s.preAuthConnections >= maxIPCPreAuthConnections) {
			s.mu.Unlock()
			_ = conn.Close()
			continue
		}
		s.connections[conn] = struct{}{}
		s.writers[conn] = &sync.Mutex{}
		if s.authToken != "" {
			s.preAuthConnections++
		}
		s.mu.Unlock()
		go s.handleConn(conn)
	}
}

// RevokeAck is a relay-side confirmation that a revoke_notify was applied
// (the route was removed from the in-memory registry and its streams closed).
type RevokeAck struct {
	RouteId string `json:"routeId"`
	HostId  string `json:"hostId"`
}

func (s *IPCServer) handleConn(conn net.Conn) {
	authenticated := s.authToken == ""
	defer func() {
		s.mu.Lock()
		if !authenticated && s.authToken != "" {
			s.preAuthConnections--
		}
		delete(s.clients, conn)
		delete(s.connections, conn)
		delete(s.writers, conn)
		delete(s.ackChans, conn)
		s.mu.Unlock()
		conn.Close()
	}()
	reader := bufio.NewReaderSize(conn, maxIPCFrameBytes)
	// Auth check: first message must be an auth frame with valid HMAC.
	if s.authToken != "" {
		_ = conn.SetDeadline(time.Now().Add(ipcAuthTotalTimeout))
		authLine, err := readIPCFrame(conn, reader, ipcAuthTotalTimeout)
		if err != nil {
			return
		}
		var authMsg IPCMessage
		if err := json.Unmarshal(authLine, &authMsg); err != nil || authMsg.Type != "ipc_auth" {
			return
		}
		type authPayload struct {
			Token string `json:"token"`
			Hmac  string `json:"hmac"`
		}
		var ap authPayload
		if err := json.Unmarshal(authMsg.Payload, &ap); err != nil {
			return
		}
		expectedHmac := hex.EncodeToString(hmacSHA256([]byte(s.authToken), []byte(ap.Token)))
		if !hmac.Equal([]byte(ap.Hmac), []byte(expectedHmac)) {
			return
		}
		_ = conn.SetDeadline(time.Time{})
	}
	s.mu.Lock()
	if !authenticated {
		s.preAuthConnections--
		authenticated = true
	}
	s.clients[conn] = struct{}{}
	s.ackChans[conn] = make(chan RevokeAck, 16)
	s.mu.Unlock()
	// Reconcile persisted revocations before serving requests: a relay that
	// (re)connects (or that missed a BroadcastRevoke while offline) drops any
	// stale sessions immediately. This is the incremental/subscription half of
	// revocation delivery; the relay's slow poll is now only a belt-and-suspenders
	// fallback for a dropped push.
	if err := s.pushCurrentRevocations(conn); err != nil {
		return
	}
	for {
		line, err := readIPCFrame(conn, reader, ipcAuthenticatedIdleTimeout)
		if err != nil {
			return
		}
		var msg IPCMessage
		if err := json.Unmarshal(line, &msg); err != nil {
			s.sendIPCError(conn, "bad_request", err.Error())
			continue
		}
		switch msg.Type {
		case "enroll":
			s.handleEnroll(conn, msg.Payload)
		case "bootstrap":
			s.handleBootstrap(conn, msg.Payload)
		case "revoke_self":
			s.handleRevokeSelf(conn, msg.Payload)
		case "renew":
			s.handleRenew(conn, msg.Payload)
		case "lookup_host":
			s.handleLookupHost(conn, msg.Payload)
		case "verify_route_mac":
			s.handleVerifyRouteMAC(conn, msg.Payload)
		case "revoke_notify":
			s.handleRevokeNotify(conn, msg.Payload)
		case "revoke_acked":
			// The relay confirmed that the route was revoked in its local
			// registry. Deliver it to the waiting BroadcastRevokeAndWait.
			var ack RevokeAck
			if err := json.Unmarshal(msg.Payload, &ack); err != nil || ack.RouteId == "" {
				continue
			}
			s.mu.Lock()
			ch := s.ackChans[conn]
			s.mu.Unlock()
			if ch != nil {
				select {
				case ch <- ack:
				default:
				}
			}
		case "stats_report":
			// Relay pushes per-route byte/connect counters periodically.
			var sr struct {
				RouteID  string `json:"routeId"`
				RX       int64  `json:"rx"`
				TX       int64  `json:"tx"`
				Connects int    `json:"connects"`
			}
			if err := json.Unmarshal(msg.Payload, &sr); err != nil || sr.RouteID == "" {
				continue
			}
			routeRaw, err := base64.RawURLEncoding.DecodeString(sr.RouteID)
			if err != nil {
				continue
			}
			if err := s.control.ReportUsage(routeRaw, sr.RX, sr.TX, sr.Connects); err != nil {
				// Non-fatal: stats lag is acceptable.
				continue
			}
		case "host_heartbeat":
			var hb struct {
				RouteID string `json:"routeId"`
			}
			if err := json.Unmarshal(msg.Payload, &hb); err != nil || hb.RouteID == "" {
				continue
			}
			routeRaw, err := base64.RawURLEncoding.DecodeString(hb.RouteID)
			if err != nil {
				continue
			}
			_ = s.control.TouchHostByRoute(routeRaw)
		case "host_offline":
			var off struct {
				RouteID string `json:"routeId"`
			}
			if err := json.Unmarshal(msg.Payload, &off); err != nil || off.RouteID == "" {
				continue
			}
			routeRaw, err := base64.RawURLEncoding.DecodeString(off.RouteID)
			if err != nil {
				continue
			}
			_ = s.control.ClearHostHeartbeatByRoute(routeRaw)
		default:
			s.sendIPCError(conn, "bad_request", "unknown type")
		}
	}
}

func (s *IPCServer) handleBootstrap(conn net.Conn, payload json.RawMessage) {
	var req struct {
		PubKey    string `json:"pubkey"`
		Ts        int64  `json:"ts"`
		Nonce     string `json:"nonce"`
		Proof     string `json:"proof"`
		Challenge string `json:"challenge"`
	}
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", err.Error())
		return
	}
	pub, err := base64.RawURLEncoding.DecodeString(req.PubKey)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "pubkey b64")
		return
	}
	nonce, err := base64.RawURLEncoding.DecodeString(req.Nonce)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "nonce b64")
		return
	}
	proof, err := base64.RawURLEncoding.DecodeString(req.Proof)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "proof b64")
		return
	}
	challenge, err := base64.RawURLEncoding.DecodeString(req.Challenge)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "challenge b64")
		return
	}
	token, err := s.control.Bootstrap(ed25519.PublicKey(pub), req.Ts, nonce, challenge, proof)
	if err != nil {
		s.sendIPCError(conn, "unauthorized", err.Error())
		return
	}
	resp, _ := json.Marshal(map[string]string{"token": token})
	s.sendIPCResponse(conn, "bootstrap_response", json.RawMessage(resp))
}

func (s *IPCServer) handleLookupHost(conn net.Conn, payload json.RawMessage) {
	var req struct {
		RouteID string `json:"routeId"`
	}
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", "invalid lookup request")
		return
	}
	routeID, err := base64.RawURLEncoding.DecodeString(req.RouteID)
	if err != nil || len(routeID) != 16 {
		s.sendIPCError(conn, "bad_request", "invalid routeId")
		return
	}
	hostID, generation, pubKey, maxStreams, revoked, suspended, err := s.control.LookupRouteStatus(routeID)
	if err != nil {
		s.sendIPCResponse(conn, "lookup_host_resp", LookupHostIPCResponse{Error: "route unavailable"})
		return
	}
	s.sendIPCResponse(conn, "lookup_host_resp", LookupHostIPCResponse{
		HostID: hostID, Generation: generation,
		HostPublicKey: base64.RawURLEncoding.EncodeToString(pubKey),
		MaxStreams:    maxStreams, Revoked: revoked, Suspended: suspended,
	})
}

func (s *IPCServer) handleVerifyRouteMAC(conn net.Conn, payload json.RawMessage) {
	var req VerifyRouteMACIPCRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", "invalid MAC request")
		return
	}
	decode := func(value string, size int) ([]byte, bool) {
		decoded, err := base64.RawURLEncoding.DecodeString(value)
		return decoded, err == nil && len(decoded) == size
	}
	routeID, ok := decode(req.RouteID, 16)
	if !ok {
		s.sendIPCError(conn, "bad_request", "invalid routeId")
		return
	}
	nonce, ok := decode(req.Nonce, 16)
	if !ok {
		s.sendIPCError(conn, "bad_request", "invalid nonce")
		return
	}
	challenge, ok := decode(req.Challenge, 32)
	if !ok {
		s.sendIPCError(conn, "bad_request", "invalid challenge")
		return
	}
	mac, ok := decode(req.MAC, sha256.Size)
	if !ok {
		s.sendIPCError(conn, "bad_request", "invalid mac")
		return
	}
	var streamID []byte
	if req.StreamID != "" {
		streamID, ok = decode(req.StreamID, 16)
		if !ok {
			s.sendIPCError(conn, "bad_request", "invalid streamId")
			return
		}
	}
	err := s.control.VerifyRouteMAC(RouteMACRequest{
		Operation: req.Operation, RouteID: routeID, StreamID: streamID,
		Generation: req.Generation, Ts: req.Ts, Nonce: nonce,
		Challenge: challenge, MAC: mac,
	})
	if err != nil {
		s.sendIPCResponse(conn, "verify_route_mac_resp", map[string]any{"ok": false})
		return
	}
	s.sendIPCResponse(conn, "verify_route_mac_resp", map[string]any{"ok": true})
}

// BroadcastRevoke sends a revoke notification to all connected relay clients.
// Returns the number of clients that received the message.
func (s *IPCServer) BroadcastRevoke(routeId, hostId string) int {
	delivered, _ := s.BroadcastRevokeAndWait(routeId, hostId, 0)
	return delivered
}

// BroadcastRevokeAndWait pushes a revoke notification to every connected
// relay client and collects their revoke_acked confirmations until wait
// elapses. The returned acked count is the number of relays that provably
// removed the route from their local registry (and therefore closed or
// rejected its streams); the gap delivered-acked is the number that only
// received the push and will converge via the reconciliation poll. A wait of
// zero skips the ack collection entirely.
func (s *IPCServer) BroadcastRevokeAndWait(routeId, hostId string, wait time.Duration) (int, int) {
	return s.broadcastRouteNotify(routeId, hostId, "", wait)
}

// BroadcastSuspend tells relays to drop live streams without REVOKED.
func (s *IPCServer) BroadcastSuspend(routeId, hostId string) int {
	n, _ := s.broadcastRouteNotify(routeId, hostId, "suspend", 0)
	return n
}

func (s *IPCServer) broadcastRouteNotify(routeId, hostId, action string, wait time.Duration) (int, int) {
	payload := map[string]string{"routeId": routeId, "hostId": hostId}
	if action != "" {
		payload["action"] = action
	}
	raw, _ := json.Marshal(payload)
	msg := IPCMessage{
		Type:    "revoke_notify",
		Payload: raw,
	}
	line, _ := json.Marshal(msg)
	line = append(line, '\n')
	s.mu.Lock()
	clients := make([]net.Conn, 0, len(s.clients))
	ackChans := make([]chan RevokeAck, 0, len(s.clients))
	for c := range s.clients {
		clients = append(clients, c)
		ackChans = append(ackChans, s.ackChans[c])
	}
	s.mu.Unlock()
	delivered := make(chan bool, len(clients))
	for _, c := range clients {
		go func(conn net.Conn) {
			delivered <- s.writeIPC(conn, line)
		}(c)
	}
	count := 0
	for range clients {
		if <-delivered {
			count++
		}
	}
	acked := 0
	if wait > 0 && len(ackChans) > 0 {
		deadline := time.After(wait)
	collect:
		for _, ch := range ackChans {
			select {
			case ack := <-ch:
				// Only count the ack for the route we revoked; a peer that
				// answers with a mismatched routeId does not prove closure.
				if ack.RouteId == routeId {
					acked++
				}
			case <-deadline:
				break collect
			}
		}
	}
	return count, acked
}

func (s *IPCServer) handleRevokeNotify(conn net.Conn, payload json.RawMessage) {
	// Relay acknowledges; no action needed on control side.
	_ = conn
	_ = payload
}

// pushCurrentRevocations sends a revoke_notify for every currently revoked
// host so a freshly connected (or reconnected) relay reconciles its in-memory
// registry with persisted revocations immediately. This is what lets a control
// restart — or a missed BroadcastRevoke while the relay was offline — converge
// without waiting for the relay's slow best-effort poll.
func (s *IPCServer) pushCurrentRevocations(conn net.Conn) error {
	hosts, err := s.control.RevokedHosts()
	if err != nil {
		return err
	}
	for _, h := range hosts {
		routeStr := base64.RawURLEncoding.EncodeToString(h.RouteID)
		payload, _ := json.Marshal(map[string]string{"routeId": routeStr, "hostId": h.ID})
		msg := IPCMessage{Type: "revoke_notify", Payload: payload}
		line, _ := json.Marshal(msg)
		line = append(line, '\n')
		if !s.writeIPC(conn, line) {
			return errors.New("write revocation state failed")
		}
	}
	return nil
}

func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

func (s *IPCServer) handleEnroll(conn net.Conn, payload json.RawMessage) {
	var req EnrollIPCRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", err.Error())
		return
	}
	// Decode fields
	hostPub, err := base64.RawURLEncoding.DecodeString(req.HostPublicKey)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "hostPublicKey")
		return
	}
	nonce, err := base64.RawURLEncoding.DecodeString(req.Nonce)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "nonce")
		return
	}
	proof, err := base64.RawURLEncoding.DecodeString(req.Proof)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "proof")
		return
	}
	chal, err := base64.RawURLEncoding.DecodeString(req.Challenge)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "challenge")
		return
	}
	res, err := s.control.Enroll(&EnrollRequest{
		InviteCode:    req.InviteCode,
		HostId:        req.HostId,
		HostPublicKey: hostPub,
		Ts:            req.Ts,
		Nonce:         nonce,
		Challenge:     chal,
		Proof:         proof,
		HostName:      req.HostName,
	})
	if err != nil {
		s.sendIPCResponse(conn, "enroll_resp", EnrollIPCResponse{Error: err.Error()})
		return
	}
	resp := EnrollIPCResponse{
		RouteId:     base64.RawURLEncoding.EncodeToString(res.RouteId),
		RouteSecret: base64.RawURLEncoding.EncodeToString(res.RouteSecret),
		Capability:  res.Capability,
		Generation:  res.Generation,
		HostId:      res.HostId,
	}
	s.sendIPCResponse(conn, "enroll_resp", resp)
}

func (s *IPCServer) handleRenew(conn net.Conn, payload json.RawMessage) {
	// similar to enroll, but renew
	type renewReq struct {
		RouteId       string `json:"routeId"`
		HostId        string `json:"hostId"`
		HostPublicKey string `json:"hostPublicKey"`
		Ts            int64  `json:"ts"`
		Nonce         string `json:"nonce"`
		Proof         string `json:"proof"`
		Challenge     string `json:"challenge"`
		OldCapability string `json:"oldCapability"`
	}
	var req renewReq
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", err.Error())
		return
	}
	routeId, err := base64.RawURLEncoding.DecodeString(req.RouteId)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "routeId")
		return
	}
	hostPub, err := base64.RawURLEncoding.DecodeString(req.HostPublicKey)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "hostPublicKey")
		return
	}
	nonce, err := base64.RawURLEncoding.DecodeString(req.Nonce)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "nonce")
		return
	}
	proof, err := base64.RawURLEncoding.DecodeString(req.Proof)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "proof")
		return
	}
	chal, err := base64.RawURLEncoding.DecodeString(req.Challenge)
	if err != nil {
		s.sendIPCError(conn, "bad_request", "challenge")
		return
	}
	capStr, err := s.control.Renew(&RenewRequest{
		RouteId: routeId, HostId: req.HostId, HostPubKey: hostPub, Ts: req.Ts, Nonce: nonce, Challenge: chal, Proof: proof, OldCapability: req.OldCapability,
	})
	if err != nil {
		s.sendIPCResponse(conn, "renew_resp", map[string]string{"error": err.Error()})
		return
	}
	s.sendIPCResponse(conn, "renew_resp", map[string]string{"capability": capStr})
}

func (s *IPCServer) sendIPCResponse(conn net.Conn, typ string, payload interface{}) {
	b, _ := json.Marshal(payload)
	msg := IPCMessage{Type: typ, Payload: b}
	line, _ := json.Marshal(msg)
	line = append(line, '\n')
	s.writeIPC(conn, line)
}

func (s *IPCServer) writeIPC(conn net.Conn, line []byte) bool {
	s.mu.Lock()
	writer := s.writers[conn]
	s.mu.Unlock()
	if writer == nil {
		return false
	}
	writer.Lock()
	defer writer.Unlock()
	_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
	_, err := conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	if err != nil {
		_ = conn.Close()
		return false
	}
	return true
}

func (s *IPCServer) sendIPCError(conn net.Conn, code, msg string) {
	s.sendIPCResponse(conn, "error", map[string]string{"code": code, "message": msg})
}

// IPCClient for relay side
type IPCClient struct {
	socket    string
	mu        sync.Mutex
	requestMu sync.Mutex
	// writeMu serializes control-frame writes from the request path and the
	// recvLoop's revoke ack, which can run concurrently.
	writeMu sync.Mutex
	conn    net.Conn
	reader  *bufio.Reader
	// closed is closed when the client is explicitly Closed; prevents reconnect after shutdown
	closed chan struct{}
	// revokeFn is called when control pushes a revoke_notify message.
	revokeFn  func(routeId, hostId string)
	suspendFn func(routeId, hostId string)
	authToken string
	responses chan ipcClientResult
	waiting   bool
	startOnce sync.Once
	closeOnce sync.Once
}

type ipcClientResult struct {
	msg IPCMessage
	err error
}

func NewIPCClient(socket string, authToken string) *IPCClient {
	return &IPCClient{
		socket:    socket,
		closed:    make(chan struct{}),
		authToken: authToken,
		responses: make(chan ipcClientResult, 1),
	}
}

// SetRevokeFn registers a callback for revoke notifications pushed by control.
func (c *IPCClient) SetRevokeFn(fn func(routeId, hostId string)) {
	c.mu.Lock()
	c.revokeFn = fn
	c.mu.Unlock()
}

// SetSuspendFn registers a callback for daily-budget holds (evict streams only).
func (c *IPCClient) SetSuspendFn(fn func(routeId, hostId string)) {
	c.mu.Lock()
	c.suspendFn = fn
	c.mu.Unlock()
}

func (c *IPCClient) Connect() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	select {
	case <-c.closed:
		return fmt.Errorf("ipc client closed")
	default:
	}
	if c.conn != nil {
		return nil
	}
	conn, err := net.Dial("unix", c.socket)
	if err != nil {
		return err
	}
	c.conn = conn
	c.reader = bufio.NewReaderSize(conn, maxIPCFrameBytes)
	// Send auth frame if token is configured. The proof is HMAC(key=token,
	// data=token): possession of the shared token is what matters, and both
	// sides already hold it, so the HMAC adds no authentication the token
	// itself does not. It is intentionally not a challenge-response — see the
	// IPCServer.authToken note for where the real trust boundary lives.
	if c.authToken != "" {
		hmacStr := hex.EncodeToString(hmacSHA256([]byte(c.authToken), []byte(c.authToken)))
		authMsg := IPCMessage{
			Type:    "ipc_auth",
			Payload: json.RawMessage(fmt.Sprintf(`{"token":%s,"hmac":%s}`, jsonString(c.authToken), jsonString(hmacStr))),
		}
		authLine, _ := json.Marshal(authMsg)
		authLine = append(authLine, '\n')
		_ = conn.SetWriteDeadline(time.Now().Add(3 * time.Second))
		if _, err := conn.Write(authLine); err != nil {
			_ = conn.Close()
			c.conn = nil
			c.reader = nil
			return err
		}
		_ = conn.SetWriteDeadline(time.Time{})
	}
	return nil
}

// StartReconnect launches the single IPC read loop. That loop is the only
// goroutine allowed to read from the connection and reconnects after failures.
func (c *IPCClient) StartReconnect() {
	c.startOnce.Do(func() { go c.recvLoop() })
}

// recvLoop owns all reads and dispatches pushes separately from the serialized
// request-response channel.
func (c *IPCClient) recvLoop() {
	backoff := 100 * time.Millisecond
	for {
		select {
		case <-c.closed:
			return
		default:
		}
		if err := c.Connect(); err != nil {
			select {
			case <-c.closed:
				return
			case <-time.After(backoff):
			}
			backoff = min(backoff*2, 5*time.Second)
			continue
		}
		backoff = 100 * time.Millisecond
		c.mu.Lock()
		conn := c.conn
		reader := c.reader
		c.mu.Unlock()
		line, err := readIPCFrame(conn, reader, 0)
		if err != nil {
			c.invalidate(conn)
			c.deliver(ipcClientResult{err: err})
			continue
		}
		var msg IPCMessage
		if err := json.Unmarshal(line, &msg); err != nil {
			continue
		}
		switch msg.Type {
		case "revoke_notify":
			c.mu.Lock()
			fn := c.revokeFn
			sf := c.suspendFn
			c.mu.Unlock()
			if fn != nil || sf != nil {
				type notify struct {
					RouteId string `json:"routeId"`
					HostId  string `json:"hostId"`
					Action  string `json:"action,omitempty"`
				}
				var n notify
				if err := json.Unmarshal(msg.Payload, &n); err == nil && n.RouteId != "" {
					if n.Action == "suspend" {
						if sf != nil {
							sf(n.RouteId, n.HostId)
						}
					} else if fn != nil {
						fn(n.RouteId, n.HostId)
					}
					// Confirm to control that the route was actually revoked
					// locally (registry removal + stream teardown), so the admin
					// API can report a provable ack count instead of "pushed".
					ack := IPCMessage{Type: "revoke_acked", Payload: msg.Payload}
					ackLine, _ := json.Marshal(ack)
					ackLine = append(ackLine, '\n')
					c.writeMu.Lock()
					_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
					_, _ = conn.Write(ackLine)
					_ = conn.SetWriteDeadline(time.Time{})
					c.writeMu.Unlock()
				}
			}
		default:
			c.deliver(ipcClientResult{msg: msg})
		}
	}
}

func (c *IPCClient) deliver(result ipcClientResult) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.waiting {
		return
	}
	select {
	case c.responses <- result:
	default:
	}
}

func (c *IPCClient) invalidate(conn net.Conn) {
	c.mu.Lock()
	if c.conn == conn {
		_ = c.conn.Close()
		c.conn = nil
		c.reader = nil
	}
	c.mu.Unlock()
}

func (c *IPCClient) Close() error {
	c.closeOnce.Do(func() { close(c.closed) })
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		c.conn.Close()
		c.conn = nil
		c.reader = nil
	}
	return nil
}

func (c *IPCClient) Enroll(req EnrollIPCRequest) (*EnrollIPCResponse, error) {
	payload, _ := json.Marshal(req)
	respMsg, err := c.request(IPCMessage{Type: "enroll", Payload: payload})
	if err != nil {
		return nil, err
	}
	if respMsg.Type == "error" {
		return nil, fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp EnrollIPCResponse
	if err := json.Unmarshal(respMsg.Payload, &resp); err != nil {
		return nil, err
	}
	if resp.Error != "" {
		return nil, enrollControlError(resp.Error)
	}
	return &resp, nil
}

func enrollControlError(msg string) error {
	switch msg {
	case store.ErrTenantHostLimit.Error():
		return store.ErrTenantHostLimit
	case store.ErrDeviceHostLimit.Error():
		return store.ErrDeviceHostLimit
	default:
		return errors.New(msg)
	}
}

// BootstrapIPCRequest asks control to sign a bootstrap token for a device
// public key (proof of possession included; challenge comes from the relay's
// HELLO).
type BootstrapIPCRequest struct {
	PubKey    string `json:"pubkey"`
	Ts        int64  `json:"ts"`
	Nonce     string `json:"nonce"`
	Proof     string `json:"proof"`
	Challenge string `json:"challenge"`
}

func (c *IPCClient) Bootstrap(req BootstrapIPCRequest) (string, error) {
	payload, _ := json.Marshal(req)
	respMsg, err := c.request(IPCMessage{Type: "bootstrap", Payload: payload})
	if err != nil {
		return "", err
	}
	if respMsg.Type == "error" {
		return "", fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp struct {
		Token string `json:"token"`
	}
	if err := json.Unmarshal(respMsg.Payload, &resp); err != nil {
		return "", err
	}
	if resp.Token == "" {
		return "", errors.New("empty bootstrap token")
	}
	return resp.Token, nil
}

func (c *IPCClient) Renew(req RenewIPCRequest) (string, error) {
	// RenewIPCRequest is defined as local struct in handleRenew, need to create map
	type renewIPCReq struct {
		RouteId       string `json:"routeId"`
		HostId        string `json:"hostId"`
		HostPublicKey string `json:"hostPublicKey"`
		Ts            int64  `json:"ts"`
		Nonce         string `json:"nonce"`
		Proof         string `json:"proof"`
		Challenge     string `json:"challenge"`
		OldCapability string `json:"oldCapability"`
	}
	r := renewIPCReq{
		RouteId:       base64.RawURLEncoding.EncodeToString(req.RouteId),
		HostId:        req.HostId,
		HostPublicKey: base64.RawURLEncoding.EncodeToString(req.HostPublicKey),
		Ts:            req.Ts,
		Nonce:         base64.RawURLEncoding.EncodeToString(req.Nonce),
		Proof:         base64.RawURLEncoding.EncodeToString(req.Proof),
		Challenge:     base64.RawURLEncoding.EncodeToString(req.Challenge),
		OldCapability: req.OldCapability,
	}
	payload, _ := json.Marshal(r)
	respMsg, err := c.request(IPCMessage{Type: "renew", Payload: payload})
	if err != nil {
		return "", err
	}
	if respMsg.Type == "error" {
		return "", fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp map[string]string
	if err := json.Unmarshal(respMsg.Payload, &resp); err != nil {
		return "", err
	}
	if e, ok := resp["error"]; ok && e != "" {
		return "", fmt.Errorf("%s", e)
	}
	capStr, ok := resp["capability"]
	if !ok {
		return "", fmt.Errorf("no capability in response")
	}
	return capStr, nil
}

func (c *IPCClient) LookupHostByRoute(routeID []byte) (*LookupHostIPCResponse, error) {
	if len(routeID) != 16 {
		return nil, fmt.Errorf("routeId must be 16 bytes")
	}
	payload, _ := json.Marshal(map[string]string{"routeId": base64.RawURLEncoding.EncodeToString(routeID)})
	respMsg, err := c.request(IPCMessage{Type: "lookup_host", Payload: payload})
	if err != nil {
		return nil, err
	}
	if respMsg.Type == "error" {
		return nil, fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp LookupHostIPCResponse
	if err := json.Unmarshal(respMsg.Payload, &resp); err != nil {
		return nil, err
	}
	if resp.Error != "" {
		return nil, errors.New(resp.Error)
	}
	return &resp, nil
}

// ReportUsage pushes per-route byte/connect counters to control. It is
// fire-and-forget: a dropped report is fine because the relay keeps its own
// counters and the next interval re-reports the same totals.
func (c *IPCClient) ReportUsage(routeID []byte, rx, tx int64, connects int) error {
	if len(routeID) != 16 {
		return fmt.Errorf("routeId must be 16 bytes")
	}
	c.StartReconnect()
	if err := c.Connect(); err != nil {
		return err
	}
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("ipc not connected")
	}
	payload, _ := json.Marshal(map[string]any{
		"routeId":  base64.RawURLEncoding.EncodeToString(routeID),
		"rx":       rx,
		"tx":       tx,
		"connects": connects,
	})
	line, _ := json.Marshal(IPCMessage{Type: "stats_report", Payload: payload})
	line = append(line, '\n')
	c.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
	_, err := conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	c.writeMu.Unlock()
	return err
}

// TouchHost pushes an Agent liveness stamp to control. Fire-and-forget:
// a dropped heartbeat is fine because the next REGISTER or throttled PING
// rewrites last_seen_at. Control never stores session content.
func (c *IPCClient) TouchHost(routeID []byte) error {
	if len(routeID) != 16 {
		return fmt.Errorf("routeId must be 16 bytes")
	}
	c.StartReconnect()
	if err := c.Connect(); err != nil {
		return err
	}
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("ipc not connected")
	}
	payload, _ := json.Marshal(map[string]string{
		"routeId": base64.RawURLEncoding.EncodeToString(routeID),
	})
	line, _ := json.Marshal(IPCMessage{Type: "host_heartbeat", Payload: payload})
	line = append(line, '\n')
	c.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
	_, err := conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	c.writeMu.Unlock()
	return err
}

// ClearHostHeartbeat tells Control the Agent for this route dropped its
// control connection. Fire-and-forget like TouchHost: the next REGISTER
// rewrites last_seen_at. Only call this when Unregister removed this session.
func (c *IPCClient) ClearHostHeartbeat(routeID []byte) error {
	if len(routeID) != 16 {
		return fmt.Errorf("routeId must be 16 bytes")
	}
	c.StartReconnect()
	if err := c.Connect(); err != nil {
		return err
	}
	c.mu.Lock()
	conn := c.conn
	c.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("ipc not connected")
	}
	payload, _ := json.Marshal(map[string]string{
		"routeId": base64.RawURLEncoding.EncodeToString(routeID),
	})
	line, _ := json.Marshal(IPCMessage{Type: "host_offline", Payload: payload})
	line = append(line, '\n')
	c.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
	_, err := conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	c.writeMu.Unlock()
	return err
}

func (c *IPCClient) VerifyRouteMAC(req VerifyRouteMACIPCRequest) error {
	payload, _ := json.Marshal(req)
	respMsg, err := c.request(IPCMessage{Type: "verify_route_mac", Payload: payload})
	if err != nil {
		return err
	}
	if respMsg.Type == "error" {
		return fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp struct {
		OK bool `json:"ok"`
	}
	if err := json.Unmarshal(respMsg.Payload, &resp); err != nil {
		return err
	}
	if !resp.OK {
		return errors.New("route MAC rejected")
	}
	return nil
}

func (c *IPCClient) request(msg IPCMessage) (IPCMessage, error) {
	c.requestMu.Lock()
	defer c.requestMu.Unlock()
	c.StartReconnect()
	if err := c.Connect(); err != nil {
		return IPCMessage{}, err
	}
	c.mu.Lock()
	conn := c.conn
	c.waiting = true
	c.mu.Unlock()
	defer func() {
		c.mu.Lock()
		c.waiting = false
		c.mu.Unlock()
		for {
			select {
			case <-c.responses:
			default:
				return
			}
		}
	}()
	line, err := json.Marshal(msg)
	if err != nil {
		return IPCMessage{}, err
	}
	line = append(line, '\n')
	c.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, werr := conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	c.writeMu.Unlock()
	if werr != nil {
		c.invalidate(conn)
		return IPCMessage{}, werr
	}
	select {
	case result := <-c.responses:
		return result.msg, result.err
	case <-time.After(10 * time.Second):
		c.invalidate(conn)
		return IPCMessage{}, fmt.Errorf("ipc response timeout")
	case <-c.closed:
		return IPCMessage{}, fmt.Errorf("ipc client closed")
	}
}

type RenewIPCRequest struct {
	RouteId       []byte
	HostId        string
	HostPublicKey []byte
	Ts            int64
	Nonce         []byte
	Challenge     []byte
	Proof         []byte
	OldCapability string
}

func hmacSHA256(key, data []byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(data)
	return m.Sum(nil)
}

// readIPCFrame applies a caller-selected first-byte deadline. Once a frame
// starts it must finish quickly and stay within the fixed buffer.
func readIPCFrame(conn net.Conn, reader *bufio.Reader, firstByteTimeout time.Duration) ([]byte, error) {
	if firstByteTimeout > 0 {
		_ = conn.SetReadDeadline(time.Now().Add(firstByteTimeout))
	}
	first, err := reader.ReadByte()
	if err != nil {
		_ = conn.SetReadDeadline(time.Time{})
		return nil, err
	}
	if first == '\n' {
		_ = conn.SetReadDeadline(time.Time{})
		return nil, nil
	}
	_ = conn.SetReadDeadline(time.Now().Add(ipcPartialFrameTimeout))
	rest, err := reader.ReadSlice('\n')
	_ = conn.SetReadDeadline(time.Time{})
	if errors.Is(err, bufio.ErrBufferFull) {
		return nil, errIPCFrameTooLarge
	}
	if err != nil {
		return nil, err
	}
	if len(rest) > maxIPCFrameBytes || len(rest)+1 > maxIPCFrameBytes {
		return nil, errIPCFrameTooLarge
	}
	frame := make([]byte, 1, len(rest))
	frame[0] = first
	frame = append(frame, rest[:len(rest)-1]...)
	return frame, nil
}

// RevokeSelfIPCRequest asks control to revoke the host owning a route after
// verifying the host-key proof of possession.
type RevokeSelfIPCRequest struct {
	RouteId   string `json:"routeId"`
	Ts        int64  `json:"ts"`
	Nonce     string `json:"nonce"`
	Proof     string `json:"proof"`
	Challenge string `json:"challenge"`
}

func (c *IPCClient) RevokeSelf(req RevokeSelfIPCRequest) (string, error) {
	payload, _ := json.Marshal(req)
	respMsg, err := c.request(IPCMessage{Type: "revoke_self", Payload: payload})
	if err != nil {
		return "", err
	}
	if respMsg.Type == "error" {
		return "", fmt.Errorf("ipc error: %s", string(respMsg.Payload))
	}
	var resp struct {
		HostId string `json:"hostId"`
	}
	_ = json.Unmarshal(respMsg.Payload, &resp)
	return resp.HostId, nil
}

func (s *IPCServer) handleRevokeSelf(conn net.Conn, payload json.RawMessage) {
	var req RevokeSelfIPCRequest
	if err := json.Unmarshal(payload, &req); err != nil {
		s.sendIPCError(conn, "bad_request", "invalid revoke-self request")
		return
	}
	routeID, err := base64.RawURLEncoding.DecodeString(req.RouteId)
	if err != nil || len(routeID) != 16 {
		s.sendIPCError(conn, "bad_request", "invalid routeId")
		return
	}
	nonce, err := base64.RawURLEncoding.DecodeString(req.Nonce)
	if err != nil || len(nonce) != 16 {
		s.sendIPCError(conn, "bad_request", "invalid nonce")
		return
	}
	proof, err := base64.RawURLEncoding.DecodeString(req.Proof)
	if err != nil || len(proof) != 64 {
		s.sendIPCError(conn, "bad_request", "invalid proof")
		return
	}
	challenge, err := base64.RawURLEncoding.DecodeString(req.Challenge)
	if err != nil || len(challenge) != 32 {
		s.sendIPCError(conn, "bad_request", "invalid challenge")
		return
	}
	hostID, err := s.control.RevokeSelf(routeID, req.Ts, nonce, challenge, proof)
	if err != nil {
		s.sendIPCError(conn, "unauthorized", err.Error())
		return
	}
	resp, _ := json.Marshal(map[string]string{"hostId": hostID})
	s.sendIPCResponse(conn, "revoke_self_resp", resp)
}
