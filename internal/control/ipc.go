package control

import (
	"bufio"
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
)

const (
	maxIPCFrameBytes       = 16 << 10
	maxIPCConnections      = 32
	ipcPartialFrameTimeout = 5 * time.Second
	ipcWriteTimeout        = 5 * time.Second
	// ipcAuthTotalTimeout is an absolute wall-clock bound on the whole auth
	// frame. Per-segment timeouts alone allowed a byte-trickling peer to hold
	// an unauthenticated connection slot indefinitely.
	ipcAuthTotalTimeout = 10 * time.Second
)

var errIPCFrameTooLarge = errors.New("ipc frame too large")

// IPC via Unix socket for relay<->control
// Messages are LF-delimited JSON with {type, payload}
// Types: enroll, renew, revoke, metrics

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
}

type EnrollIPCResponse struct {
	RouteId     string `json:"routeId"`
	RouteSecret string `json:"routeSecret"`
	Capability  string `json:"capability"`
	Generation  uint64 `json:"generation"`
	Error       string `json:"error,omitempty"`
}

type RevokeIPCRequest struct {
	RouteId string `json:"routeId"`
	HostId  string `json:"hostId"`
}

// IPCServer runs on control side
type IPCServer struct {
	control        *Control
	socket         string
	listener       net.Listener
	mu             sync.Mutex
	writeMu        sync.Mutex
	clients        map[net.Conn]struct{}
	connections    map[net.Conn]struct{}
	revokeHandlers []func(routeId string)
	authToken      string // empty = no auth; otherwise HMAC-SHA256 keyed
}

func NewIPCServer(ctrl *Control, socketPath string, authToken string) *IPCServer {
	return &IPCServer{
		control: ctrl, socket: socketPath, authToken: authToken,
		clients: make(map[net.Conn]struct{}), connections: make(map[net.Conn]struct{}),
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
		if len(s.connections) >= maxIPCConnections {
			s.mu.Unlock()
			_ = conn.Close()
			continue
		}
		s.connections[conn] = struct{}{}
		s.mu.Unlock()
		go s.handleConn(conn)
	}
}

func (s *IPCServer) handleConn(conn net.Conn) {
	defer func() {
		s.mu.Lock()
		delete(s.clients, conn)
		delete(s.connections, conn)
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
	s.clients[conn] = struct{}{}
	s.mu.Unlock()
	for {
		line, err := readIPCFrame(conn, reader, 0)
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
		case "renew":
			s.handleRenew(conn, msg.Payload)
		case "revoke_notify":
			s.handleRevokeNotify(conn, msg.Payload)
		default:
			s.sendIPCError(conn, "bad_request", "unknown type")
		}
	}
}

// BroadcastRevoke sends a revoke notification to all connected relay clients.
// Returns the number of clients that received the message.
func (s *IPCServer) BroadcastRevoke(routeId, hostId string) int {
	msg := IPCMessage{
		Type:    "revoke_notify",
		Payload: json.RawMessage(`{"routeId":` + jsonString(routeId) + `,` + `"hostId":` + jsonString(hostId) + `}`),
	}
	line, _ := json.Marshal(msg)
	line = append(line, '\n')
	s.mu.Lock()
	clients := make([]net.Conn, 0, len(s.clients))
	for c := range s.clients {
		clients = append(clients, c)
	}
	s.mu.Unlock()
	count := 0
	for _, c := range clients {
		s.writeMu.Lock()
		_ = c.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
		if _, err := c.Write(line); err == nil {
			count++
		} else {
			_ = c.Close()
		}
		_ = c.SetWriteDeadline(time.Time{})
		s.writeMu.Unlock()
	}
	return count
}

func (s *IPCServer) handleRevokeNotify(conn net.Conn, payload json.RawMessage) {
	// Relay acknowledges; no action needed on control side.
	_ = conn
	_ = payload
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
	s.writeMu.Lock()
	_ = conn.SetWriteDeadline(time.Now().Add(ipcWriteTimeout))
	_, _ = conn.Write(line)
	_ = conn.SetWriteDeadline(time.Time{})
	s.writeMu.Unlock()
}

func (s *IPCServer) sendIPCError(conn net.Conn, code, msg string) {
	s.sendIPCResponse(conn, "error", map[string]string{"code": code, "message": msg})
}

// IPCClient for relay side
type IPCClient struct {
	socket    string
	mu        sync.Mutex
	requestMu sync.Mutex
	conn      net.Conn
	reader    *bufio.Reader
	// closed is closed when the client is explicitly Closed; prevents reconnect after shutdown
	closed chan struct{}
	// revokeFn is called when control pushes a revoke_notify message.
	revokeFn  func(routeId, hostId string)
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
	// Send auth frame if token is configured
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
			c.mu.Unlock()
			if fn != nil {
				type notify struct {
					RouteId string `json:"routeId"`
					HostId  string `json:"hostId"`
				}
				var n notify
				if err := json.Unmarshal(msg.Payload, &n); err == nil && n.RouteId != "" {
					fn(n.RouteId, n.HostId)
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
		return nil, fmt.Errorf("%s", resp.Error)
	}
	return &resp, nil
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
	_ = conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := conn.Write(line); err != nil {
		c.invalidate(conn)
		return IPCMessage{}, err
	}
	_ = conn.SetWriteDeadline(time.Time{})
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

// readIPCFrame waits indefinitely for an idle authenticated peer's first byte,
// but once a frame starts it must finish quickly and stay within the fixed
// buffer. firstByteTimeout is used for the unauthenticated auth frame.
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
