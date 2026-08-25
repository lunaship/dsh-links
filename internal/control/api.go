package control

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/json"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/registry"
)

//go:embed web/*
var webFS embed.FS

const sessionCookieName = "dsh_session"

const (
	maxAdminBodyBytes = 64 << 10
	sessionTTL        = 24 * time.Hour
	maxAdminSessions  = 64

	// loginBurstAttempts bounds password-guessing per source IP. The admin
	// panel is loopback-only, but tunnels and reverse proxies are common in
	// self-hosting, so the endpoint must survive exposure.
	loginBurstAttempts = 10
)

// Admin API: only 127.0.0.1:8080 + Bearer token or session cookie

type Server struct {
	control        *Control
	adminToken     string // legacy token auth
	adminUser      string
	adminPassword  string
	secureCookies  bool
	tlsFingerprint string
	publicHost     string
	agentPort      string
	pinFingerprint bool
	loginLimiter   *registry.RateLimiter
	sessionsMu     sync.Mutex
	sessions       map[[sha256.Size]byte]time.Time
}

func NewServer(ctrl *Control, adminToken, adminUser, adminPassword string) *Server {
	return NewServerWithSecureCookies(ctrl, adminToken, adminUser, adminPassword, false)
}

func NewServerWithSecureCookies(ctrl *Control, adminToken, adminUser, adminPassword string, secureCookies bool) *Server {
	return &Server{
		control: ctrl, adminToken: adminToken, adminUser: adminUser, adminPassword: adminPassword,
		secureCookies: secureCookies,
		loginLimiter:  registry.NewRateLimiter(loginBurstAttempts, 5),
		sessions:      make(map[[sha256.Size]byte]time.Time),
	}
}

// SetTLSFingerprint stores the Relay data-plane certificate SHA-256 (64 hex
// chars). The control UI shows it so operators can paste it into the plugin
// when using a self-signed Relay certificate. It is not a secret.
func (s *Server) SetTLSFingerprint(fp string) {
	s.tlsFingerprint = strings.ToLower(strings.TrimSpace(fp))
}

// SetEnrollMeta records the public Agent endpoint used to mint one-paste
// enroll URIs. pinFingerprint includes the TLS SHA-256 only for self-signed
// Relay certificates; public-CA deployments omit it so the plugin uses the
// system trust store.
func (s *Server) SetEnrollMeta(host, agentPort string, pinFingerprint bool) {
	s.publicHost = strings.TrimSpace(host)
	s.agentPort = strings.TrimSpace(agentPort)
	s.pinFingerprint = pinFingerprint
}

func (s *Server) enrollURI(invite string) string {
	fp := ""
	if s.pinFingerprint {
		fp = s.tlsFingerprint
	}
	return cryptoutil.BuildEnrollURI(s.publicHost, s.agentPort, invite, fp)
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
		// Baseline security headers for every response.
		h := w.Header()
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("X-Frame-Options", "DENY")
		h.Set("Referrer-Policy", "no-referrer")
		if r.URL.Path == "/login" || r.URL.Path == "/logout" || strings.HasPrefix(r.URL.Path, "/v1/") {
			// API bodies carry invite codes and route data; never cacheable.
			h.Set("Cache-Control", "no-store")
		}
		// Login/logout are public but must go through the same content-type
		// gate as /v1/ so a cross-site text/plain POST cannot smuggle JSON.
		if r.URL.Path == "/login" || r.URL.Path == "/logout" {
			s.checkContentType(w, r, http.HandlerFunc(s.handleAuthRoutes))
			return
		}
		if strings.HasPrefix(r.URL.Path, "/v1/") {
			s.authMiddleware(apiMux).ServeHTTP(w, r)
			return
		}
		s.handleUI(w, r)
	})
}

func (s *Server) handleAuthRoutes(w http.ResponseWriter, r *http.Request) {
	switch r.URL.Path {
	case "/login":
		s.handleLogin(w, r)
	case "/logout":
		s.handleLogout(w, r)
	}
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
	if isUnsafeMethod(r.Method) {
		mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || mediaType != "application/json" {
			http.Error(w, `{"error":"bad content type"}`, http.StatusBadRequest)
			return
		}
		if origin := r.Header.Get("Origin"); origin != "" && !sameOrigin(origin, r) {
			http.Error(w, `{"error":"cross-origin request rejected"}`, http.StatusForbidden)
			return
		}
	}
	next.ServeHTTP(w, r)
}

func isUnsafeMethod(method string) bool {
	return method == http.MethodPost || method == http.MethodPut || method == http.MethodPatch || method == http.MethodDelete
}

func sameOrigin(origin string, r *http.Request) bool {
	u, err := url.Parse(origin)
	if err != nil || u.Scheme == "" || u.Host == "" || u.User != nil || u.Path != "" || u.RawQuery != "" || u.Fragment != "" {
		return false
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return strings.EqualFold(u.Scheme, scheme) && strings.EqualFold(u.Host, r.Host)
}

func (s *Server) handleLogin(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, `{"error":"method not allowed"}`, http.StatusMethodNotAllowed)
		return
	}
	if !s.loginLimiter.Allow(clientIP(r)) {
		w.Header().Set("Retry-After", "12")
		http.Error(w, `{"error":"rate limited"}`, http.StatusTooManyRequests)
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
	// Both comparisons are constant-time: user first would otherwise let a
	// timing oracle confirm the username before the password is touched.
	if s.adminPassword == "" || !secretEqual(form.User, s.adminUser) || !secretEqual(form.Password, s.adminPassword) {
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
	http.SetCookie(w, &http.Cookie{Name: sessionCookieName, Value: "", HttpOnly: true, Secure: s.secureCookies, SameSite: http.SameSiteStrictMode, Path: "/", MaxAge: -1})
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
		out := map[string]string{"inviteCode": code, "expiresIn": ttl.String()}
		if enroll := s.enrollURI(code); enroll != "" {
			out["enroll"] = enroll
		}
		json.NewEncoder(w).Encode(out)
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
		Name: sessionCookieName, Value: token, HttpOnly: true, Secure: s.secureCookies, SameSite: http.SameSiteStrictMode,
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

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = strings.Trim(r.RemoteAddr, "[]")
	}
	if ip := net.ParseIP(host); ip != nil {
		if ip.IsLoopback() {
			// All of 127.0.0.0/8 and ::1 are one local trust source. Treating
			// each address separately lets a local attacker rotate source IPs and
			// obtain a fresh password-guessing bucket.
			return "loopback"
		}
		return ip.String()
	}
	return host
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

// revokeTarget validates that a POST path is exactly /v1/<kind>/<id>/revoke
// and returns the id. Requiring the literal /revoke suffix keeps the catch-all
// routes below from treating an arbitrary trailing segment as a revoke trigger.
func revokeTarget(path, kind string) (string, bool) {
	p := strings.TrimSuffix(path, "/")
	parts := strings.Split(p, "/")
	// ["", "v1", kind, id, "revoke"]
	if len(parts) != 5 || parts[1] != "v1" || parts[2] != kind || parts[4] != "revoke" {
		return "", false
	}
	if parts[3] == "" {
		return "", false
	}
	return parts[3], true
}

func (s *Server) handleInviteRevoke(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// path /v1/invites/:id/revoke
	id, ok := revokeTarget(r.URL.Path, "invites")
	if !ok {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
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
	// /v1/hosts/:id/revoke
	id, ok := revokeTarget(r.URL.Path, "hosts")
	if !ok {
		http.Error(w, "bad path", http.StatusBadRequest)
		return
	}
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
	out := map[string]any{
		"hosts":   len(hosts),
		"invites": len(invites),
	}
	if len(s.tlsFingerprint) == 64 {
		out["tlsFingerprint"] = s.tlsFingerprint
	}
	if host := strings.TrimSpace(s.publicHost); host != "" {
		out["publicHost"] = host
	}
	json.NewEncoder(w).Encode(out)
}

func (s *Server) handleUI(w http.ResponseWriter, r *http.Request) {
	// The UI loads only same-origin script/style and renders via textContent,
	// so CSP can stay strict without unsafe-inline.
	w.Header().Set("Content-Security-Policy", "default-src 'self'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'")
	w.Header().Set("Cache-Control", "no-store")
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
