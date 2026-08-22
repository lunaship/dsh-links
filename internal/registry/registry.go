package registry

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"sync"
	"sync/atomic"
	"time"
)

// Errors
var (
	ErrAgentAlreadyOnline = errors.New("agent already online")
	ErrAgentOffline       = errors.New("agent offline")
	ErrRouteBusy          = errors.New("route busy")
	ErrGenerationMismatch = errors.New("generation mismatch")
	ErrStreamNotFound     = errors.New("stream not found")
	ErrServerBusy         = errors.New("server busy")
	ErrStreamExists       = errors.New("stream already exists")
)

// AgentSender is interface to send OPEN to agent control connection.
type AgentSender interface {
	SendOpen(streamId string, generation uint64) error
	Close() error
}

// AgentSession represents an online agent.
type AgentSession struct {
	RouteIdRaw  []byte
	RouteIdStr  string // base64url
	HostID      string
	Generation  uint64
	MaxStreams  int
	HostPubKey  []byte
	Sender      AgentSender
	ConnectedAt time.Time
	LastPing    time.Time

	mu      sync.Mutex
	streams map[string]*PendingStream // streamId b64 -> pending
}

// PendingStream waiting for BIND
type PendingStream struct {
	StreamId   []byte
	StreamStr  string
	RouteId    []byte
	Generation uint64
	CreatedAt  time.Time
	ClientAddr string           // for logging
	BridgeCh   chan *BridgeInfo // for successful bridge pairing
	Bound      bool

	session      *AgentSession
	agentCloser  io.Closer
	clientCloser io.Closer
}

type BridgeInfo struct {
	AgentConn interface{}   // net.Conn
	AgentFR   interface{}   // *protocol.FrameReader
	Done      chan struct{} // closed by client when bridge finished
	Err       error
}

// Registry holds online agents.
type Registry struct {
	mu     sync.RWMutex
	agents map[string]*AgentSession // routeIdStr -> session

	// global limits
	maxTotalStreams int
	// pendingStreams tracks the number of in-flight CONNECT→BIND operations
	// across all routes. Used for the global stream budget check under sess.mu.
	pendingStreams atomic.Int64
	totalStreams   atomic.Int64
}

func New(maxTotalStreams int) *Registry {
	return &Registry{
		agents:          make(map[string]*AgentSession),
		maxTotalStreams: maxTotalStreams,
	}
}

// Register adds an agent session. If same routeId already exists, check generation.
// - If existing generation == new generation: replace? Should close old connection and replace.
// - If new generation > old: replace.
// - If new generation < old: reject with REVOKED? Actually older capability should be rejected.
// For safety, only allow if new generation >= old generation; if equal, replace connection (reconnect).
func (r *Registry) Register(sess *AgentSession) (bool, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	key := sess.RouteIdStr
	replaced := false
	if existing, ok := r.agents[key]; ok {
		if sess.Generation < existing.Generation {
			return false, fmt.Errorf("%w: stale generation %d < %d", ErrGenerationMismatch, sess.Generation, existing.Generation)
		}
		replaced = true
		if existing.Sender != nil {
			_ = existing.Sender.Close()
		}
		r.closeSession(existing, ErrAgentOffline)
	}
	r.agents[key] = sess
	return replaced, nil
}

// Unregister removes only the exact session that owns the control connection.
func (r *Registry) Unregister(sess *AgentSession) bool {
	r.mu.Lock()
	current, ok := r.agents[sess.RouteIdStr]
	if !ok || current != sess {
		r.mu.Unlock()
		return false
	}
	delete(r.agents, sess.RouteIdStr)
	r.mu.Unlock()
	if sess.Sender != nil {
		_ = sess.Sender.Close()
	}
	r.closeSession(sess, ErrAgentOffline)
	return true
}

func (r *Registry) closeSession(sess *AgentSession, reason error) {
	sess.mu.Lock()
	for _, p := range sess.streams {
		delete(sess.streams, p.StreamStr)
		if !p.Bound {
			r.pendingStreams.Add(-1)
		}
		r.totalStreams.Add(-1)
		closeStream(p)
		signalStream(p, reason)
	}
	sess.streams = nil
	sess.mu.Unlock()
}

// Get returns agent session.
func (r *Registry) Get(routeIdStr string) (*AgentSession, bool) {
	r.mu.RLock()
	s, ok := r.agents[routeIdStr]
	r.mu.RUnlock()
	return s, ok
}

// Count returns online count
func (r *Registry) Count() int {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return len(r.agents)
}

// List returns copy
func (r *Registry) List() []*AgentSession {
	r.mu.RLock()
	defer r.mu.RUnlock()
	out := make([]*AgentSession, 0, len(r.agents))
	for _, v := range r.agents {
		out = append(out, v)
	}
	return out
}

// TotalActiveStreams returns all reserved stream slots, pending plus active.
func (r *Registry) TotalActiveStreams() int {
	return int(r.totalStreams.Load())
}

// CreatePending creates a pending stream for CONNECT, sends OPEN to agent.
// Returns PendingStream and error if limits exceeded.
// Global budget check is performed under sess.mu to avoid TOCTOU race.
func (r *Registry) CreatePending(routeIdStr string, clientAddr string) (*PendingStream, error) {
	r.mu.RLock()
	sess, ok := r.agents[routeIdStr]
	if !ok {
		r.mu.RUnlock()
		return nil, ErrAgentOffline
	}
	sess.mu.Lock()
	r.mu.RUnlock()
	defer sess.mu.Unlock()

	// Check per-route limit
	if len(sess.streams) >= sess.MaxStreams {
		return nil, ErrRouteBusy
	}
	// Check global budget: active streams + pending binds must stay below maxTotalStreams.
	// The atomic total remains reserved after CompleteBind until Release/Cancel.
	if !r.reserveStream() {
		return nil, ErrServerBusy
	}

	// Generate streamId
	streamId := make([]byte, 16)
	if _, err := rand.Read(streamId); err != nil {
		r.totalStreams.Add(-1)
		return nil, err
	}
	streamStr := base64.RawURLEncoding.EncodeToString(streamId)
	pending := &PendingStream{
		StreamId:   streamId,
		StreamStr:  streamStr,
		RouteId:    sess.RouteIdRaw,
		Generation: sess.Generation,
		CreatedAt:  time.Now(),
		BridgeCh:   make(chan *BridgeInfo, 1),
		ClientAddr: clientAddr,
		session:    sess,
	}
	if sess.streams == nil {
		sess.streams = make(map[string]*PendingStream)
	}
	// Ensure uniqueness (very unlikely collision)
	if _, exists := sess.streams[streamStr]; exists {
		r.totalStreams.Add(-1)
		return nil, ErrStreamExists
	}
	sess.streams[streamStr] = pending
	// Increment global pending counter while still holding sess.mu.
	r.pendingStreams.Add(1)

	// Send OPEN to agent
	if err := sess.Sender.SendOpen(streamStr, sess.Generation); err != nil {
		// Rollback
		delete(sess.streams, streamStr)
		r.pendingStreams.Add(-1)
		r.totalStreams.Add(-1)
		return nil, fmt.Errorf("send open failed: %w", err)
	}
	return pending, nil
}

func (r *Registry) reserveStream() bool {
	for {
		current := r.totalStreams.Load()
		if current >= int64(r.maxTotalStreams) {
			return false
		}
		if r.totalStreams.CompareAndSwap(current, current+1) {
			return true
		}
	}
}

// CompleteBind marks a pending stream active while retaining its budget slot.
func (r *Registry) CompleteBind(routeIdStr, streamStr string, generation uint64, agentCloser io.Closer) (*PendingStream, error) {
	r.mu.RLock()
	sess, ok := r.agents[routeIdStr]
	if !ok {
		r.mu.RUnlock()
		return nil, ErrAgentOffline
	}
	if sess.Generation != generation {
		r.mu.RUnlock()
		return nil, ErrGenerationMismatch
	}
	sess.mu.Lock()
	r.mu.RUnlock()
	defer sess.mu.Unlock()
	pending, ok := sess.streams[streamStr]
	if !ok {
		return nil, ErrStreamNotFound
	}
	if pending.Generation != generation {
		return nil, ErrGenerationMismatch
	}
	if pending.Bound {
		return nil, ErrStreamExists
	}
	pending.Bound = true
	pending.agentCloser = agentCloser
	r.pendingStreams.Add(-1)
	return pending, nil
}

// AttachClient records the client side so revocation can close both ends.
func (r *Registry) AttachClient(p *PendingStream, clientCloser io.Closer) bool {
	sess := p.session
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if current, ok := sess.streams[p.StreamStr]; !ok || current != p {
		return false
	}
	p.clientCloser = clientCloser
	return true
}

func (r *Registry) PublishBridge(p *PendingStream, info *BridgeInfo) bool {
	sess := p.session
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if current, ok := sess.streams[p.StreamStr]; !ok || current != p {
		return false
	}
	select {
	case p.BridgeCh <- info:
		return true
	default:
		return false
	}
}

// Cancel removes a pending or active stream and releases its budget.
func (r *Registry) Cancel(p *PendingStream, reason error) {
	sess := p.session
	sess.mu.Lock()
	if current, ok := sess.streams[p.StreamStr]; ok && current == p {
		delete(sess.streams, p.StreamStr)
		if !p.Bound {
			r.pendingStreams.Add(-1)
		}
		r.totalStreams.Add(-1)
		closeStream(p)
		signalStream(p, reason)
	}
	sess.mu.Unlock()
}

// Release removes a completed bridge and releases its budget.
func (r *Registry) Release(p *PendingStream) {
	sess := p.session
	sess.mu.Lock()
	if current, ok := sess.streams[p.StreamStr]; ok && current == p {
		delete(sess.streams, p.StreamStr)
		r.totalStreams.Add(-1)
	}
	sess.mu.Unlock()
}

// Revoke closes and removes agent and fails pendings.
func (r *Registry) Revoke(routeIdStr string) {
	r.mu.Lock()
	sess, ok := r.agents[routeIdStr]
	if ok {
		delete(r.agents, routeIdStr)
	}
	r.mu.Unlock()
	if !ok {
		return
	}
	if sess.Sender != nil {
		_ = sess.Sender.Close()
	}
	r.closeSession(sess, errors.New("revoked"))
}

// UpdateHeartbeat updates last ping.
func (r *Registry) UpdateHeartbeat(routeIdStr string) {
	r.mu.RLock()
	s, ok := r.agents[routeIdStr]
	r.mu.RUnlock()
	if ok {
		s.mu.Lock()
		s.LastPing = time.Now()
		s.mu.Unlock()
	}
}

func signalStream(p *PendingStream, reason error) {
	select {
	case p.BridgeCh <- &BridgeInfo{Err: reason}:
	default:
	}
}

func closeStream(p *PendingStream) {
	if p.clientCloser != nil {
		_ = p.clientCloser.Close()
	}
	if p.agentCloser != nil {
		_ = p.agentCloser.Close()
	}
}
