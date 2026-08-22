package ingress

import (
	"crypto/ed25519"
	"crypto/tls"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/bridge"
	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/logutil"
	"github.com/dsh-links/dsh-links-relay/internal/metrics"
	"github.com/dsh-links/dsh-links-relay/internal/protocol"
	"github.com/dsh-links/dsh-links-relay/internal/registry"
)

// ControlAPI abstracts control operations needed by ingress.
type ControlAPI interface {
	Enroll(req *EnrollProxyRequest) (*EnrollProxyResponse, error)
	LookupHostByRoute(routeId []byte) (hostID string, generation uint64, pubKey []byte, maxStreams int, revoked bool, err error)
	VerifyCapability(cap string) (*cryptoutil.CapabilityPayload, error)
	Renew(req *RenewProxyRequest) (string, error)
}

type RenewProxyRequest struct {
	RouteId       []byte
	HostId        string
	HostPubKey    []byte
	Ts            int64
	Nonce         []byte
	Challenge     []byte
	Proof         []byte
	OldCapability string
}

type EnrollProxyRequest struct {
	InviteCode    string
	HostId        string
	HostPublicKey []byte
	Ts            int64
	Nonce         []byte
	Challenge     []byte
	Proof         []byte
}
type EnrollProxyResponse struct {
	RouteId     []byte
	RouteSecret []byte
	Capability  string
	Generation  uint64
}

type Ingress struct {
	clientListen string
	agentListen  string
	tlsConfig    *tls.Config
	registry     *registry.Registry
	control      ControlAPI
	metrics      *metrics.Metrics

	routeMasterKey    []byte
	issuerPub         ed25519.PublicKey
	heartbeatInterval time.Duration
	bindTimeout       time.Duration
	agentDeadAfter    time.Duration
	maxTotalStreams   int

	ipLimiter        *registry.RateLimiter
	routeLimiter     *registry.RateLimiter
	enrollLimiter    *registry.RateLimiter
	admissionLimiter *registry.RateLimiter

	// challenge -> conn mapping? Actually challenge per conn stored locally
	// For HMAC verification, need to know challenge per conn
	// We'll store in connection context

	// Nonce replay protection per connection? Spec says replay rejection within connection/window. We'll track nonces per connection.
	// Simple: map nonce b64 -> bool per conn

	// For testability, allow plain TCP if tlsConfig nil
	plainMode bool

	listeners []net.Listener
	wg        sync.WaitGroup
	stopCh    chan struct{}
	logger    *log.Logger

	// maxConns is the hard limit on concurrently accepted connections.
	maxConns        int
	connMu          sync.Mutex
	connCount       int
	clientConnCount int
	agentConnCount  int
	connsByIP       map[string]int
	maxConnsPerIP   int
	// bridgeMaxLifetime is the maximum lifetime for a data-plane bridge.
	bridgeMaxLifetime time.Duration
}

// New creates ingress.
func New(clientListen, agentListen string, tlsConfig *tls.Config, reg *registry.Registry, ctrl ControlAPI, m *metrics.Metrics, routeMasterKey []byte, issuerPub ed25519.PublicKey, heartbeat, bindTimeout, deadAfter time.Duration, maxTotal, maxConns int, bridgeMaxLifetime time.Duration, logger *log.Logger) *Ingress {
	if logger == nil {
		logger = log.Default()
	}
	// Default maxConns to 2× maxTotalStreams so idle connections don't starve stream slots.
	if maxConns <= 0 {
		maxConns = maxTotal * 2
	}
	return &Ingress{
		clientListen:      clientListen,
		agentListen:       agentListen,
		tlsConfig:         tlsConfig,
		registry:          reg,
		control:           ctrl,
		metrics:           m,
		routeMasterKey:    routeMasterKey,
		issuerPub:         issuerPub,
		heartbeatInterval: heartbeat,
		bindTimeout:       bindTimeout,
		agentDeadAfter:    deadAfter,
		maxTotalStreams:   maxTotal,
		maxConns:          maxConns,
		// App 每个 HTTP 请求都会新建一条 CONNECT（进工作台约 8 条 + SSE）。
		// 单用户自托管按「一部手机」来留余量，仍在 MAC 校验之后按路由计。
		ipLimiter:         registry.NewRateLimiter(96, 60),
		routeLimiter:      registry.NewRateLimiter(96, 60),
		enrollLimiter:     registry.NewRateLimiter(6, 2),
		admissionLimiter:  registry.NewRateLimiter(120, 120),
		connsByIP:         make(map[string]int),
		maxConnsPerIP:     128,
		plainMode:         tlsConfig == nil,
		stopCh:            make(chan struct{}),
		logger:            logger,
		bridgeMaxLifetime: bridgeMaxLifetime,
	}
}

func (ing *Ingress) Start() error {
	// Start client listener
	cln, err := ing.listen(ing.clientListen)
	if err != nil {
		return fmt.Errorf("client listen: %w", err)
	}
	aln, err := ing.listen(ing.agentListen)
	if err != nil {
		cln.Close()
		return fmt.Errorf("agent listen: %w", err)
	}
	ing.listeners = []net.Listener{cln, aln}
	ing.wg.Add(2)
	go ing.serveListener(cln, true)  // client side
	go ing.serveListener(aln, false) // agent side
	return nil
}

func (ing *Ingress) listen(addr string) (net.Listener, error) {
	if ing.plainMode {
		return net.Listen("tcp", addr)
	}
	return tls.Listen("tcp", addr, ing.tlsConfig)
}

func (ing *Ingress) Close() error {
	close(ing.stopCh)
	for _, ln := range ing.listeners {
		ln.Close()
	}
	ing.wg.Wait()
	return nil
}

func (ing *Ingress) serveListener(ln net.Listener, isClient bool) {
	defer ing.wg.Done()
	for {
		conn, err := ln.Accept()
		if err != nil {
			select {
			case <-ing.stopCh:
				return
			default:
				ing.logger.Printf("accept error: %v", err)
				time.Sleep(100 * time.Millisecond)
				continue
			}
		}
		remoteIP := remoteIPFromConn(conn)
		if !ing.admissionLimiter.Allow(remoteIP) {
			ing.logger.Printf("connection admission rate reached, rejecting from %s", logutil.Value(remoteIP))
			_ = conn.Close()
			ing.metrics.RecordError()
			continue
		}
		if !ing.acquireConnection(remoteIP, isClient) {
			ing.logger.Printf("connection limit reached (%d), rejecting from %s", ing.maxConns, logutil.Value(remoteIP))
			_ = conn.Close()
			ing.metrics.RecordError()
			continue
		}
		go func(c net.Conn, ip string, client bool) {
			defer ing.releaseConnection(ip, client)
			ing.handleConn(c, client)
		}(conn, remoteIP, isClient)
	}
}

func (ing *Ingress) acquireConnection(remoteIP string, isClient bool) bool {
	ing.connMu.Lock()
	defer ing.connMu.Unlock()
	if ing.connCount >= ing.maxConns || ing.connsByIP[remoteIP] >= ing.maxConnsPerIP {
		return false
	}
	// Each public listener may consume at most 75% of the shared budget. This
	// reserves capacity for the other protocol role during a connection flood.
	listenerLimit := ing.maxConns
	if ing.maxConns >= 4 {
		listenerLimit -= ing.maxConns / 4
	}
	if (isClient && ing.clientConnCount >= listenerLimit) || (!isClient && ing.agentConnCount >= listenerLimit) {
		return false
	}
	ing.connCount++
	ing.connsByIP[remoteIP]++
	if isClient {
		ing.clientConnCount++
	} else {
		ing.agentConnCount++
	}
	return true
}

func (ing *Ingress) releaseConnection(remoteIP string, isClient bool) {
	ing.connMu.Lock()
	defer ing.connMu.Unlock()
	ing.connCount--
	if ing.connsByIP[remoteIP] <= 1 {
		delete(ing.connsByIP, remoteIP)
	} else {
		ing.connsByIP[remoteIP]--
	}
	if isClient {
		ing.clientConnCount--
	} else {
		ing.agentConnCount--
	}
}

// connContext holds per-connection state
type connContext struct {
	conn         net.Conn
	challenge    []byte
	challengeStr string
	fr           *protocol.FrameReader
	nonces       map[string]bool // replay protection per conn
	remoteIP     string
}

func (ing *Ingress) handleConn(rawConn net.Conn, isClient bool) {
	defer rawConn.Close()
	remoteIP := remoteIPFromConn(rawConn)
	ing.logger.Printf("new conn from %s client=%v", logutil.Value(remoteIP), isClient)

	// Set deadline for TLS handshake already handled; now HELLO send timeout 3s, first frame 5s
	// Generate challenge
	chal, err := cryptoutil.GenerateChallenge()
	if err != nil {
		return
	}
	chalStr := base64.RawURLEncoding.EncodeToString(chal)
	ctx := &connContext{
		conn:         rawConn,
		challenge:    chal,
		challengeStr: chalStr,
		nonces:       make(map[string]bool),
		remoteIP:     remoteIP,
	}

	// HELLO must be sent within 3s
	_ = rawConn.SetWriteDeadline(time.Now().Add(3 * time.Second))
	hello := protocol.HelloFrame{Type: protocol.TypeHello, V: 1, Challenge: chalStr}
	helloBytes, _ := json.Marshal(hello)
	helloBytes = append(helloBytes, '\n')
	if _, err := rawConn.Write(helloBytes); err != nil {
		ing.logger.Printf("write hello failed: %v", err)
		return
	}
	_ = rawConn.SetWriteDeadline(time.Time{}) // clear

	// First frame must arrive within 5s
	_ = rawConn.SetReadDeadline(time.Now().Add(5 * time.Second))
	ctx.fr = protocol.NewFrameReader(rawConn)

	// Determine max per type? We need to read generic first, but we enforce per-type after detect
	// Use large limit 2048 for first frame
	rawFrame, err := ctx.fr.ReadFrame(2048)
	if err != nil {
		ing.logger.Printf("read first frame failed %s: %v", logutil.Value(remoteIP), err)
		sendError(rawConn, protocol.ErrBadRequest, "first frame")
		return
	}
	_ = rawConn.SetReadDeadline(time.Time{}) // clear after first frame; subsequent handlers set their own

	typ, err := protocol.DetectType(rawFrame)
	if err != nil {
		sendError(rawConn, protocol.ErrBadRequest, "invalid json")
		return
	}
	if len(rawFrame) > protocol.MaxForType(typ) {
		ing.logger.Printf("frame too large %s len=%d max=%d", logutil.Value(typ), len(rawFrame), protocol.MaxForType(typ))
		sendError(rawConn, protocol.ErrBadRequest, "frame too large")
		return
	}

	// Dispatch
	if isClient {
		// Client side expects CONNECT
		if typ == protocol.TypeConnect {
			ing.handleConnect(ctx, rawFrame)
		} else {
			sendError(rawConn, protocol.ErrBadRequest, "expected CONNECT")
		}
	} else {
		// Agent side: ENROLL, REGISTER, BIND
		switch typ {
		case protocol.TypeEnroll:
			ing.handleEnroll(ctx, rawFrame)
		case protocol.TypeRegister:
			ing.handleRegister(ctx, rawFrame)
		case protocol.TypeBind:
			ing.handleBind(ctx, rawFrame)
		default:
			sendError(rawConn, protocol.ErrBadRequest, "expected ENROLL/REGISTER/BIND")
		}
	}
}

// handleEnroll processes ENROLL frame on agent connection.
// After success, this connection is expected to continue as REGISTER? Actually ENROLL is one-shot then agent should reconnect with REGISTER.
// Spec: Agent sends ENROLL, gets ENROLLED, then can use REGISTER on same or new connection? Best to treat ENROLL as terminal: close after ENROLLED.
// But we could keep connection open for subsequent REGISTER if client chooses.
func (ing *Ingress) handleEnroll(ctx *connContext, raw []byte) {
	enroll, err := protocol.ValidateEnroll(raw)
	if err != nil {
		// Log without secret
		ing.logger.Printf("enroll validation failed from %s: %v", ctx.remoteIP, err)
		sendError(ctx.conn, protocol.ErrBadRequest, "invalid enroll")
		return
	}
	if !ing.enrollLimiter.Allow(ctx.remoteIP) {
		sendError(ctx.conn, protocol.ErrRateLimited, "rate limited enroll")
		return
	}
	// Replay check per conn
	if ctx.nonces[enroll.Nonce] {
		sendError(ctx.conn, protocol.ErrReplayRejected, "replay")
		return
	}
	ctx.nonces[enroll.Nonce] = true

	// Decode proof fields
	hostPub, _ := base64.RawURLEncoding.DecodeString(enroll.HostPublicKey)
	nonce, _ := base64.RawURLEncoding.DecodeString(enroll.Nonce)
	proof, _ := base64.RawURLEncoding.DecodeString(enroll.Proof)

	// Call control
	if ing.control == nil {
		sendError(ctx.conn, protocol.ErrServerBusy, "no control")
		return
	}
	req := &EnrollProxyRequest{
		InviteCode:    enroll.InviteCode,
		HostId:        enroll.HostId,
		HostPublicKey: hostPub,
		Ts:            enroll.Ts,
		Nonce:         nonce,
		Challenge:     ctx.challenge,
		Proof:         proof,
	}
	resp, err := ing.control.Enroll(req)
	if err != nil {
		ing.logger.Printf("enroll failed for %s: %v", logutil.Value(enroll.HostId), err)
		// To avoid leaking existence, return AUTH_FAILED for many cases? For ENROLL we can return AUTH_FAILED
		sendError(ctx.conn, protocol.ErrAuthFailed, "enroll failed")
		return
	}
	enrolled := protocol.EnrolledFrame{
		Type:        protocol.TypeEnrolled,
		RouteId:     base64.RawURLEncoding.EncodeToString(resp.RouteId),
		RouteSecret: base64.RawURLEncoding.EncodeToString(resp.RouteSecret),
		Capability:  resp.Capability,
		Generation:  resp.Generation,
	}
	b, _ := json.Marshal(enrolled)
	b = append(b, '\n')
	_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, _ = ctx.conn.Write(b)
	ing.logger.Printf("enroll success host=%s route=%s", logutil.Value(enroll.HostId), enrolled.RouteId[:8])
	// Close after enroll, agent will reconnect with REGISTER
}

func (ing *Ingress) handleRegister(ctx *connContext, raw []byte) {
	reg, err := protocol.ValidateRegister(raw)
	if err != nil {
		ing.logger.Printf("register validation failed from %s: %v", ctx.remoteIP, err)
		sendError(ctx.conn, protocol.ErrBadRequest, "invalid register")
		return
	}
	if ctx.nonces[reg.Nonce] {
		sendError(ctx.conn, protocol.ErrReplayRejected, "replay")
		return
	}
	ctx.nonces[reg.Nonce] = true

	// Verify capability
	payload, err := cryptoutil.VerifyCapability(ing.issuerPub, reg.Capability)
	if err != nil {
		ing.logger.Printf("capability verify failed from %s: %s", logutil.Value(ctx.remoteIP), truncateForLog(reg.Capability))
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}
	// Check expiry
	now := time.Now().Unix()
	if payload.Exp < now {
		sendError(ctx.conn, protocol.ErrAuthFailed, "cap expired")
		return
	}
	// Check revocation via control lookup? Need to check host revoked and generation
	routeIdRaw, _ := base64.RawURLEncoding.DecodeString(payload.Route)
	// lookup host
	_, gen, pubKey, maxStreams, revoked, err := ing.control.LookupHostByRoute(routeIdRaw)
	if err != nil {
		// Route not found -> AUTH_FAILED uniform
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}
	if revoked {
		sendError(ctx.conn, protocol.ErrRevoked, "revoked")
		return
	}
	if payload.Generation != uint64(gen) {
		sendError(ctx.conn, protocol.ErrRevoked, "generation mismatch")
		return
	}
	// Verify host_pk matches stored
	hostPKRaw, _ := base64.RawURLEncoding.DecodeString(payload.HostPK)
	if string(hostPKRaw) != string(pubKey) {
		sendError(ctx.conn, protocol.ErrAuthFailed, "host_pk mismatch")
		return
	}
	// Verify proof
	nonce, _ := base64.RawURLEncoding.DecodeString(reg.Nonce)
	proof, _ := base64.RawURLEncoding.DecodeString(reg.Proof)
	transcript := cryptoutil.BuildRegisterTranscript(reg.Capability, reg.Ts, nonce, ctx.challenge)
	if !cryptoutil.Ed25519Verify(ed25519.PublicKey(hostPKRaw), transcript, proof) {
		sendError(ctx.conn, protocol.ErrAuthFailed, "proof failed")
		return
	}
	// Register in registry
	sender := &agentSender{conn: ctx.conn, fr: ctx.fr, challenge: ctx.challengeStr}
	sess := &registry.AgentSession{
		RouteIdRaw:  routeIdRaw,
		RouteIdStr:  payload.Route,
		HostID:      payload.Host,
		Generation:  payload.Generation,
		MaxStreams:  maxStreams,
		HostPubKey:  hostPKRaw,
		Sender:      sender,
		ConnectedAt: time.Now(),
		LastPing:    time.Now(),
	}
	if maxStreams == 0 {
		sess.MaxStreams = ing.maxTotalStreams // fallback
		if sess.MaxStreams > 8 {
			sess.MaxStreams = 8
		}
	}
	replaced, err := ing.registry.Register(sess)
	if err != nil {
		ing.logger.Printf("registry register failed: %v", err)
		sendError(ctx.conn, protocol.ErrAuthFailed, "register failed")
		return
	}
	if !replaced {
		ing.metrics.IncOnlineHosts(1)
	}
	ing.logger.Printf("agent registered route=%s host=%s gen=%d", payload.Route[:8], logutil.Value(payload.Host), payload.Generation)
	defer func() {
		if ing.registry.Unregister(sess) {
			ing.metrics.IncOnlineHosts(-1)
		}
		ing.logger.Printf("agent offline route=%s", sess.RouteIdStr[:8])
	}()

	// Send REGISTERED
	registered := protocol.RegisteredFrame{Type: protocol.TypeRegistered, Generation: payload.Generation, Heartbeat: int(ing.heartbeatInterval.Seconds())}
	b, _ := json.Marshal(registered)
	b = append(b, '\n')
	_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := ctx.conn.Write(b); err != nil {
		// Unregister + IncOnlineHosts(-1) handled by defer below
		return
	}

	// Now enter control loop: handle PING/PONG, RENEW, and idle detection
	// Heartbeat handling: expect PING every 20s, dead after 65s
	// This loop owns the control connection until closed.
	currentCap := reg.Capability
	// Set read deadline for heartbeat
	for {
		_ = ctx.conn.SetReadDeadline(time.Now().Add(ing.agentDeadAfter))
		// Use nonces map already tracks; need to read next frame with limit 2048
		raw, err := ctx.fr.ReadFrame(2048)
		if err != nil {
			if errors.Is(err, io.EOF) || strings.Contains(err.Error(), "timeout") || strings.Contains(err.Error(), "deadline") {
				ing.logger.Printf("agent heartbeat timeout route=%s", payload.Route[:8])
			} else {
				ing.logger.Printf("agent control read error route=%s: %v", payload.Route[:8], err)
			}
			return
		}
		typ, _ := protocol.DetectType(raw)
		if len(raw) > protocol.MaxForType(typ) {
			ing.logger.Printf("frame too large in control loop %s len=%d", logutil.Value(typ), len(raw))
			sendError(ctx.conn, protocol.ErrBadRequest, "frame too large")
			return
		}
		switch typ {
		case protocol.TypePing:
			pong := protocol.PongFrame{Type: protocol.TypePong}
			b, _ := json.Marshal(pong)
			b = append(b, '\n')
			_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
			_, _ = ctx.conn.Write(b)
			ing.registry.UpdateHeartbeat(payload.Route)
		case protocol.TypePong:
			sendError(ctx.conn, protocol.ErrBadRequest, "unexpected PONG")
			return
		case protocol.TypeRenew:
			newCap, err := ing.handleRenew(ctx, raw, payload, currentCap)
			if err == nil && newCap != "" {
				currentCap = newCap
				// Update payload for future renews (keep same generation but update exp/jti)
				if p, e := cryptoutil.VerifyCapability(ing.issuerPub, newCap); e == nil {
					payload = p
				}
			}
		case protocol.TypeError:
			// ignore?
		default:
			ing.logger.Printf("unknown control frame %s from %s", logutil.Value(typ), payload.Route[:8])
			sendError(ctx.conn, protocol.ErrBadRequest, "unexpected")
			return
		}
	}
}

func (ing *Ingress) handleRenew(ctx *connContext, raw []byte, currentPayload *cryptoutil.CapabilityPayload, currentCap string) (string, error) {
	renew, err := protocol.ValidateRenew(raw)
	if err != nil {
		sendError(ctx.conn, protocol.ErrBadRequest, "invalid renew")
		return "", err
	}
	if ctx.nonces[renew.Nonce] {
		sendError(ctx.conn, protocol.ErrReplayRejected, "replay")
		return "", fmt.Errorf("replay")
	}
	ctx.nonces[renew.Nonce] = true

	hostPubRaw, _ := base64.RawURLEncoding.DecodeString(currentPayload.HostPK)
	nonce, _ := base64.RawURLEncoding.DecodeString(renew.Nonce)
	proof, _ := base64.RawURLEncoding.DecodeString(renew.Proof)
	routeRaw, _ := base64.RawURLEncoding.DecodeString(currentPayload.Route)

	req := &RenewProxyRequest{
		RouteId:       routeRaw,
		HostId:        currentPayload.Host,
		HostPubKey:    hostPubRaw,
		Ts:            renew.Ts,
		Nonce:         nonce,
		Challenge:     ctx.challenge,
		Proof:         proof,
		OldCapability: currentCap,
	}
	newCap, err := ing.control.Renew(req)
	if err != nil {
		ing.logger.Printf("renew failed for %s: %v", logutil.Value(currentPayload.Host), err)
		sendError(ctx.conn, protocol.ErrAuthFailed, "renew failed")
		return "", err
	}
	renewed := protocol.RenewedFrame{Type: protocol.TypeRenewed, Capability: newCap}
	b, _ := json.Marshal(renewed)
	b = append(b, '\n')
	_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, _ = ctx.conn.Write(b)
	ing.logger.Printf("renew success host=%s route=%s", logutil.Value(currentPayload.Host), currentPayload.Route[:8])
	return newCap, nil
}

func (ing *Ingress) handleConnect(ctx *connContext, raw []byte) {
	if !ing.ipLimiter.Allow(ctx.remoteIP) {
		sendError(ctx.conn, protocol.ErrRateLimited, "rate limited ip")
		return
	}
	connFrame, err := protocol.ValidateConnect(raw)
	if err != nil {
		sendError(ctx.conn, protocol.ErrBadRequest, "invalid connect")
		return
	}
	if ctx.nonces[connFrame.Nonce] {
		sendError(ctx.conn, protocol.ErrReplayRejected, "replay")
		return
	}
	ctx.nonces[connFrame.Nonce] = true

	routeIdRaw, _ := base64.RawURLEncoding.DecodeString(connFrame.Route)
	nonce, _ := base64.RawURLEncoding.DecodeString(connFrame.Nonce)
	mac, _ := base64.RawURLEncoding.DecodeString(connFrame.Mac)

	secret, err := cryptoutil.DeriveRouteSecret(ing.routeMasterKey, routeIdRaw)
	if err != nil {
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}
	transcript := cryptoutil.BuildMACTranscript("CONNECT", routeIdRaw, nil, nil, connFrame.Ts, nonce, ctx.challenge)
	if !cryptoutil.VerifyMAC(secret, transcript, mac) {
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		ing.metrics.RecordError()
		return
	}
	// Only an authenticated route may allocate or consume a route bucket. This
	// prevents arbitrary pre-auth route strings from poisoning limiter state.
	if !ing.routeLimiter.Allow(connFrame.Route) {
		sendError(ctx.conn, protocol.ErrRateLimited, "rate limited route")
		return
	}
	_, generation, _, _, revoked, err := ing.control.LookupHostByRoute(routeIdRaw)
	if err != nil || revoked {
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}

	sess, ok := ing.registry.Get(connFrame.Route)
	if !ok {
		sendError(ctx.conn, protocol.ErrAgentOffline, "agent offline")
		return
	}
	if sess.Generation != generation {
		ing.registry.Revoke(connFrame.Route)
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}

	start := time.Now()
	pending, err := ing.registry.CreatePending(connFrame.Route, ctx.remoteIP)
	if err != nil {
		switch {
		case errors.Is(err, registry.ErrRouteBusy):
			sendError(ctx.conn, protocol.ErrRouteBusy, "route busy")
		case errors.Is(err, registry.ErrServerBusy):
			sendError(ctx.conn, protocol.ErrServerBusy, "server busy")
		case errors.Is(err, registry.ErrAgentOffline):
			sendError(ctx.conn, protocol.ErrAgentOffline, "agent offline")
		default:
			sendError(ctx.conn, protocol.ErrServerBusy, "busy")
		}
		return
	}
	ing.metrics.IncPendingBinds(1)
	defer ing.metrics.IncPendingBinds(-1)

	// Wait for BIND with timeout via BridgeCh
	var bridgeInfo *registry.BridgeInfo
	select {
	case bridgeInfo = <-pending.BridgeCh:
		if bridgeInfo == nil || bridgeInfo.Err != nil {
			errMsg := "bind failed"
			if bridgeInfo != nil && bridgeInfo.Err != nil {
				errMsg = bridgeInfo.Err.Error()
			}
			if strings.Contains(errMsg, "revoked") {
				sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
			} else if strings.Contains(errMsg, "offline") {
				sendError(ctx.conn, protocol.ErrAgentOffline, "agent offline")
			} else {
				sendError(ctx.conn, protocol.ErrBindTimeout, "bind failed")
			}
			return
		}
	case <-time.After(ing.bindTimeout):
		ing.registry.Cancel(pending, errors.New("bind timeout"))
		sendError(ctx.conn, protocol.ErrBindTimeout, "bind timeout")
		return
	case <-ing.stopCh:
		ing.registry.Cancel(pending, errors.New("relay stopping"))
		return
	}

	// Success: we have agentConn
	agentConn, ok := bridgeInfo.AgentConn.(net.Conn)
	if !ok {
		sendError(ctx.conn, protocol.ErrServerBusy, "bridge error")
		return
	}
	agentFR, _ := bridgeInfo.AgentFR.(*protocol.FrameReader)
	done := bridgeInfo.Done
	if !ing.registry.AttachClient(pending, ctx.conn) {
		_ = agentConn.Close()
		sendError(ctx.conn, protocol.ErrAuthFailed, "stream no longer active")
		return
	}
	defer ing.registry.Release(pending)

	// Send READY to client
	ready := protocol.ReadyFrame{Type: protocol.TypeReady, Stream: pending.StreamStr}
	b, _ := json.Marshal(ready)
	b = append(b, '\n')
	_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := ctx.conn.Write(b); err != nil {
		_ = agentConn.Close()
		if done != nil {
			close(done)
		}
		return
	}
	_ = ctx.conn.SetWriteDeadline(time.Time{})

	ing.metrics.RecordConnect()
	ing.metrics.ObserveLatency(time.Since(start))
	ing.logger.Printf("bridge start route=%s stream=%s", connFrame.Route[:8], pending.StreamStr[:8])

	// Capture carry bytes before bridge
	clientCarry := ctx.fr.DrainedBuffered()
	agentCarry := []byte(nil)
	if agentFR != nil {
		agentCarry = agentFR.DrainedBuffered()
	}
	if len(clientCarry) > 0 {
		_ = agentConn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		_, _ = agentConn.Write(clientCarry)
	}
	if len(agentCarry) > 0 {
		_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
		_, _ = ctx.conn.Write(agentCarry)
	}

	ing.metrics.IncActiveStreams(1)
	defer ing.metrics.IncActiveStreams(-1)
	defer func() {
		if done != nil {
			// signal agent waiters that bridge finished
			select {
			case <-done:
			default:
				close(done)
			}
		}
	}()
	// Bridge blocks until done; it will close both conns
	_ = bridge.Bridge(ctx.conn, agentConn,
		func(n int64) { ing.metrics.AddRx(n) },
		func(n int64) { ing.metrics.AddTx(n) },
		ing.bridgeMaxLifetime,
	)
}

func (ing *Ingress) handleBind(ctx *connContext, raw []byte) {
	bind, err := protocol.ValidateBind(raw)
	if err != nil {
		sendError(ctx.conn, protocol.ErrBadRequest, "invalid bind")
		return
	}
	if ctx.nonces[bind.Nonce] {
		sendError(ctx.conn, protocol.ErrReplayRejected, "replay")
		return
	}
	ctx.nonces[bind.Nonce] = true

	routeIdRaw, _ := base64.RawURLEncoding.DecodeString(bind.Route)
	streamIdRaw, _ := base64.RawURLEncoding.DecodeString(bind.Stream)
	nonce, _ := base64.RawURLEncoding.DecodeString(bind.Nonce)
	mac, _ := base64.RawURLEncoding.DecodeString(bind.Mac)

	secret, err := cryptoutil.DeriveRouteSecret(ing.routeMasterKey, routeIdRaw)
	if err != nil {
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}
	gen := bind.Generation
	transcript := cryptoutil.BuildMACTranscript("BIND", routeIdRaw, streamIdRaw, &gen, bind.Ts, nonce, ctx.challenge)
	if !cryptoutil.VerifyMAC(secret, transcript, mac) {
		sendError(ctx.conn, protocol.ErrAuthFailed, "auth failed")
		return
	}

	// Check pending (without removing yet? CompleteBind removes). We need to get pending before removing to signal.
	// Retrieve pending via registry internal? Use CompleteBind which deletes but returns pending with BridgeCh.
	pending, err := ing.registry.CompleteBind(bind.Route, bind.Stream, bind.Generation, ctx.conn)
	if err != nil {
		if errors.Is(err, registry.ErrGenerationMismatch) {
			sendError(ctx.conn, protocol.ErrAuthFailed, "generation mismatch")
		} else if errors.Is(err, registry.ErrStreamNotFound) {
			sendError(ctx.conn, protocol.ErrBadRequest, "stream not found")
		} else if errors.Is(err, registry.ErrAgentOffline) {
			sendError(ctx.conn, protocol.ErrAgentOffline, "agent offline")
		} else {
			sendError(ctx.conn, protocol.ErrAuthFailed, "bind failed")
		}
		return
	}

	// Send READY to agent before bridging
	ready := protocol.ReadyFrame{Type: protocol.TypeReady, Stream: bind.Stream}
	b, _ := json.Marshal(ready)
	b = append(b, '\n')
	_ = ctx.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := ctx.conn.Write(b); err != nil {
		ing.registry.Cancel(pending, err)
		return
	}
	_ = ctx.conn.SetWriteDeadline(time.Time{})

	// Signal client that BIND is ready; include this agentConn for bridging
	done := make(chan struct{})
	if !ing.registry.PublishBridge(pending, &registry.BridgeInfo{AgentConn: ctx.conn, AgentFR: ctx.fr, Done: done}) {
		ing.registry.Cancel(pending, errors.New("client no longer waiting"))
		return
	}

	ing.metrics.RecordBind()
	ing.logger.Printf("bind success route=%s stream=%s", bind.Route[:8], bind.Stream[:8])

	// Wait until client finishes bridging (closes done)
	select {
	case <-done:
		return
	case <-ing.stopCh:
		return
	}
}

type agentSender struct {
	conn      net.Conn
	fr        *protocol.FrameReader
	challenge string
	mu        sync.Mutex
}

func (s *agentSender) SendOpen(streamId string, generation uint64) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	open := protocol.OpenFrame{Type: protocol.TypeOpen, Stream: streamId, Generation: generation}
	b, _ := json.Marshal(open)
	b = append(b, '\n')
	_ = s.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err := s.conn.Write(b)
	return err
}
func (s *agentSender) Close() error { return s.conn.Close() }

func sendError(conn net.Conn, code, msg string) {
	b := protocol.MarshalError(code, msg)
	_ = conn.SetWriteDeadline(time.Now().Add(2 * time.Second))
	_, _ = conn.Write(b)
}

func remoteIPFromConn(conn net.Conn) string {
	addr := conn.RemoteAddr().String()
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	return host
}

func truncateForLog(s string) string {
	return logutil.Value(s)
}
