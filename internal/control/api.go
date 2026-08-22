package control

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

//go:embed web/*
var webFS embed.FS

const sessionCookieName = "dsh_session"

const (
	maxAdminBodyBytes = 64 << 10
	sessionTTL        = 24 * time.Hour
	maxAdminSessions  = 64
)

// Admin API: only 127.0.0.1:8080 + Bearer token or session cookie

type Server struct {
	control       *Control
	adminToken    string // legacy token auth
	adminUser     string
	adminPassword string
	sessionsMu    sync.Mutex
	sessions      map[[sha256.Size]byte]time.Time
}

func NewServer(ctrl *Control, adminToken, adminUser, adminPassword string) *Server {
	return &Server{
		control: ctrl, adminToken: adminToken, adminUser: adminUser, adminPassword: adminPassword,
		sessions: make(map[[sha256.Size]byte]time.Time),
	}
}

func (s *Server) Handler() http.Handler {
	apiMux := http.NewServeMux()
	apiMux.HandleFunc("/v1/invites", s.handleInvites)
	apiMux.HandleFunc("/v1/invites/", s.handleInviteRevoke)
	apiMux.HandleFunc("/v1/hosts", s.handleHosts)
	apiMux.HandleFunc("/v1/hosts/", s.handleHostRevoke)
	apiMux.HandleFunc("/v1/overview", s.handleOverview)

	// UI without auth (initial page), API with auth
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Body != nil {
			r.Body = http.MaxBytesReader(w, r.Body, maxAdminBodyBytes)
		}
		// Login/logout are public, everything else under /v1/ requires auth
		if r.URL.Path == "/login" || r.URL.Path == "/logout" {
			switch r.URL.Path {
			case "/login":
				s.handleLogin(w, r)
			case "/logout":
				s.handleLogout(w, r)
			}
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/") {
			s.authMiddleware(apiMux).ServeHTTP(w, r)
			return
		}
		s.handleUI(w, r)
	})
}

func (s *Server) authMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// Bearer token (legacy)
		auth := r.Header.Get("Authorization")
		if token, ok := strings.CutPrefix(auth, "Bearer "); ok && secretEqual(token, s.adminToken) {
			s.checkContentType(w, r, next)
			return
		}
		// Session cookie
		cookie, err := r.Cookie(sessionCookieName)
		if err == nil && s.validSession(cookie.Value, time.Now()) {
			s.checkContentType(w, r, next)
			return
		}
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
	})
}

func (s *Server) checkContentType(w http.ResponseWriter, r *http.Request, next http.Handler) {
	if r.Method == "POST" || r.Method == "PUT" || r.Method == "DELETE" {
		ct := r.Header.Get("Content-Type")
		if ct != "" && !strings.Contains(ct, "application/json") {
			http.Error(w, `{"error":"bad content type"}`, http.StatusBadRequest)
			return
		}
	}
	next.ServeHTTP(w, r)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}
	if s.adminPassword == "" {
		// No password configured — fall back to token check in header
		auth := r.Header.Get("Authorization")
		if token, ok := strings.CutPrefix(auth, "Bearer "); ok && secretEqual(token, s.adminToken) {
			if err := s.issueSession(w, time.Now()); err != nil {
				http.Error(w, `{"error":"session unavailable"}`, http.StatusInternalServerError)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
			return
		}
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	type req struct {
		User     string `json:"user"`
		Password string `json:"password"`
	}
	var form req
	if err := decodeOneJSON(r.Body, &form, false); err != nil {
		http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
		return
	}
	if form.User != s.adminUser || !secretEqual(form.Password, s.adminPassword) {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	if err := s.issueSession(w, time.Now()); err != nil {
		http.Error(w, `{"error":"session unavailable"}`, http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
}

func (s *Server) handleLogout(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}
	if cookie, err := r.Cookie(sessionCookieName); err == nil {
		s.revokeSession(cookie.Value)
	}
	http.SetCookie(w, &http.Cookie{Name: sessionCookieName, Value: "", HttpOnly: true, SameSite: http.SameSiteStrictMode, Path: "/", MaxAge: -1})
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(map[string]bool{"ok": true})
}

func (s *Server) handleInvites(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case "POST":
		// create invite, body may contain ttl
		var req struct {
			TTL string `json:"ttl"`
		}
		if err := decodeOneJSON(r.Body, &req, true); err != nil {
			http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
			return
		}
		ttl := 30 * time.Minute
		if req.TTL != "" {
			if d, err := time.ParseDuration(req.TTL); err == nil {
				ttl = d
			}
		}
		code, err := s.control.CreateInvite(ttl)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		json.NewEncoder(w).Encode(map[string]string{"inviteCode": code, "expiresIn": ttl.String()})
	case "GET":
		list, err := s.control.ListInvites()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		// Map to sanitized view (don't expose raw code_hash)
		type out struct {
			ID         string `json:"id"`
			ExpiresAt  int64  `json:"expiresAt"`
			ConsumedAt *int64 `json:"consumedAt,omitempty"`
			RevokedAt  *int64 `json:"revokedAt,omitempty"`
			CreatedAt  int64  `json:"createdAt"`
		}
		var res []out
		for _, iv := range list {
			hashB64 := base64.RawURLEncoding.EncodeToString(iv.CodeHash[:4]) + "..." // truncated
			_ = hashB64
			res = append(res, out{ID: iv.ID, ExpiresAt: iv.ExpiresAt, ConsumedAt: iv.ConsumedAt, RevokedAt: iv.RevokedAt, CreatedAt: iv.CreatedAt})
		}
		json.NewEncoder(w).Encode(res)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) issueSession(w http.ResponseWriter, now time.Time) error {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	hash := sha256.Sum256([]byte(token))
	expires := now.Add(sessionTTL)
	s.sessionsMu.Lock()
	for key, expiry := range s.sessions {
		if !expiry.After(now) {
			delete(s.sessions, key)
		}
	}
	if len(s.sessions) >= maxAdminSessions {
		var oldestKey [sha256.Size]byte
		var oldest time.Time
		for key, expiry := range s.sessions {
			if oldest.IsZero() || expiry.Before(oldest) {
				oldestKey = key
				oldest = expiry
			}
		}
		delete(s.sessions, oldestKey)
	}
	s.sessions[hash] = expires
	s.sessionsMu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookieName, Value: token, HttpOnly: true, SameSite: http.SameSiteStrictMode,
		Path: "/", MaxAge: int(sessionTTL.Seconds()), Expires: expires,
	})
	return nil
}

func (s *Server) validSession(token string, now time.Time) bool {
	if token == "" {
		return false
	}
	hash := sha256.Sum256([]byte(token))
	s.sessionsMu.Lock()
	defer s.sessionsMu.Unlock()
	expires, ok := s.sessions[hash]
	if !ok || !expires.After(now) {
		delete(s.sessions, hash)
		return false
	}
	return true
}

func (s *Server) revokeSession(token string) {
	hash := sha256.Sum256([]byte(token))
	s.sessionsMu.Lock()
	delete(s.sessions, hash)
	s.sessionsMu.Unlock()
}

func secretEqual(got, want string) bool {
	if len(got) != len(want) || len(want) == 0 {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(got), []byte(want)) == 1
}

func decodeOneJSON(r io.Reader, dst any, allowEmpty bool) error {
	dec := json.NewDecoder(r)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		if allowEmpty && err == io.EOF {
			return nil
		}
		return err
	}
	var extra any
	if err := dec.Decode(&extra); err != io.EOF {
		if err == nil {
			return io.ErrUnexpectedEOF
		}
		return err
	}
	return nil
}

func (s *Server) handleInviteRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// path /v1/invites/:id/revoke
	parts := strings.Split(r.URL.Path, "/")
	if len(parts) < 4 {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	id := parts[3]
	if err := s.control.RevokeInvite(id); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	w.Write([]byte(`{"ok":true}`))
}

func (s *Server) handleHosts(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	list, err := s.control.ListHosts()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	type hostOut struct {
		ID          string `json:"id"`
		HostName    string `json:"hostName"`
		RouteIdHash string `json:"routeIdHash"` // SHA256 of full routeId, not decodable to route
		Generation  int64  `json:"generation"`
		MaxStreams  int    `json:"maxStreams"`
		Version     string `json:"version"`
		LastSeenAt  *int64 `json:"lastSeenAt,omitempty"`
		RevokedAt   *int64 `json:"revokedAt,omitempty"`
		CreatedAt   int64  `json:"createdAt"`
	}
	var res []hostOut
	for _, h := range list {
		routeHash := base64.RawURLEncoding.EncodeToString(cryptoutil.SHA256(h.RouteID)[:8]) + "..."
		res = append(res, hostOut{
			ID: h.ID, HostName: h.HostName, RouteIdHash: routeHash,
			Generation: h.Generation, MaxStreams: h.MaxStreams, Version: h.Version,
			LastSeenAt: h.LastSeenAt, RevokedAt: h.RevokedAt, CreatedAt: h.CreatedAt,
		})
	}
	json.NewEncoder(w).Encode(res)
}

func (s *Server) handleHostRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	parts := strings.Split(r.URL.Path, "/")
	// /v1/hosts/:id/revoke
	if len(parts) < 4 {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
	id := parts[3]
	if err := s.control.RevokeHost(id); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	// Relay will see revoked_at via store poll (1s) and close Agent + streams; no extra IPC push required for correctness
	json.NewEncoder(w).Encode(map[string]bool{"ok": true})
}

func (s *Server) handleOverview(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	hosts, _ := s.control.ListHosts()
	invites, _ := s.control.ListInvites()
	json.NewEncoder(w).Encode(map[string]interface{}{
		"hosts":   len(hosts),
		"invites": len(invites),
	})
}

func (s *Server) handleUI(w http.ResponseWriter, r *http.Request) {
	assets := map[string]string{
		"/tokens.css": "text/css; charset=utf-8",
		"/app.css":    "text/css; charset=utf-8",
		"/app.js":     "text/javascript; charset=utf-8",
	}
	if contentType, ok := assets[r.URL.Path]; ok {
		w.Header().Set("Content-Type", contentType)
		data, err := webFS.ReadFile("web/" + strings.TrimPrefix(r.URL.Path, "/"))
		if err != nil {
			http.NotFound(w, r)
			return
		}
		_, _ = w.Write(data)
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	data, err := webFS.ReadFile("web/index.html")
	if err != nil {
		// fallback
		w.Write([]byte(`<!doctype html><html><head><meta charset="utf-8"><title>DSH Links Relay Control</title></head><body><h1>Control OK</h1><p>Use API: /v1/invites, /v1/hosts, /v1/overview</p></body></html>`))
		return
	}
	_, _ = w.Write(data)
}
