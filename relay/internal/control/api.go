package control

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
	"github.com/lunaship/dsh-links/relay/internal/protocol"
	"github.com/lunaship/dsh-links/relay/internal/registry"
	"github.com/lunaship/dsh-links/relay/internal/store"
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

	// hostOnlineWindow treats a Host as live if Control saw a heartbeat
	// inside this interval. Relay's agent_dead_after default is 65s.
	hostOnlineWindow = 90 * time.Second
)

type principalKey struct{}

// Principal is the Control-plane subject. Admin sees every invite/host;
// a tenant only sees rows with their user_id. The Android App never logs in here.
type Principal struct {
	UserID string
	Admin  bool
}

type sessionRec struct {
	UserID  string
	Admin   bool
	Expires time.Time
}

// Admin API: only 127.0.0.1:8080 + Bearer token or session cookie

type Server struct {
	control          *Control
	adminToken       string // legacy token auth
	adminUser        string
	adminPassword    string
	secureCookies    bool
	tlsFingerprint   string
	publicHost       string
	publicControlURL string
	agentPort        string
	pinFingerprint   bool
	loginLimiter     *registry.RateLimiter
	sessionsMu       sync.Mutex
	sessions         map[[sha256.Size]byte]sessionRec
}

func NewServer(ctrl *Control, adminToken, adminUser, adminPassword string) *Server {
	return NewServerWithSecureCookies(ctrl, adminToken, adminUser, adminPassword, false)
}

func NewServerWithSecureCookies(ctrl *Control, adminToken, adminUser, adminPassword string, secureCookies bool) *Server {
	return &Server{
		control: ctrl, adminToken: adminToken, adminUser: adminUser, adminPassword: adminPassword,
		secureCookies: secureCookies,
		loginLimiter:  registry.NewRateLimiter(loginBurstAttempts, 5),
		sessions:      make(map[[sha256.Size]byte]sessionRec),
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

// SetPublicControlURL stores the HTTPS Control URL hosted tenants should open.
// Empty means self-host / loopback-only; never send 127.0.0.1 to a tenant.
func (s *Server) SetPublicControlURL(raw string) {
	s.publicControlURL = strings.TrimSpace(raw)
}

func (s *Server) enrollURI(invite string) string {
	fp := ""
	if s.pinFingerprint {
		fp = s.tlsFingerprint
	}
	return cryptoutil.BuildEnrollURI(s.publicHost, s.agentPort, invite, fp, s.publicControlURL)
}

func (s *Server) Handler() http.Handler {
	apiMux := http.NewServeMux()
	apiMux.HandleFunc("/v1/invites", s.handleInvites)
	apiMux.HandleFunc("/v1/invites/", s.handleInviteItem)
	apiMux.HandleFunc("/v1/hosts", s.handleHosts)
	apiMux.HandleFunc("/v1/hosts/", s.handleHostItem)
	apiMux.HandleFunc("/v1/devices", s.handleDevices)
	apiMux.HandleFunc("/v1/devices/", s.handleDeviceItem)
	apiMux.HandleFunc("/v1/settings/anonymous", s.handleAnonymousSetting)
	apiMux.HandleFunc("/v1/overview", s.handleOverview)
	apiMux.HandleFunc("/v1/events", s.handleEvents)
	apiMux.HandleFunc("/v1/tenants", s.handleTenants)
	apiMux.HandleFunc("/v1/tenants/", s.handleTenantItem)
	apiMux.HandleFunc("/v1/account/password", s.handleAccountPassword)

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
		p, ok := s.lookupPrincipal(r)
		if !ok {
			http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
			return
		}
		if s.passwordChangeRequired(p) && !passwordChangeAllowed(r) {
			writeControlError(w, store.ErrPasswordMustChange)
			return
		}
		ctx := context.WithValue(r.Context(), principalKey{}, p)
		s.checkContentType(w, r.WithContext(ctx), next)
	})
}

func (s *Server) lookupPrincipal(r *http.Request) (Principal, bool) {
	auth := r.Header.Get("Authorization")
	if token, ok := strings.CutPrefix(auth, "Bearer "); ok && secretEqual(token, s.adminToken) {
		return Principal{Admin: true}, true
	}
	cookie, err := r.Cookie(sessionCookieName)
	if err != nil {
		return Principal{}, false
	}
	p, ok := s.sessionPrincipal(cookie.Value, time.Now())
	if !ok {
		return Principal{}, false
	}
	if p.Admin {
		return p, true
	}
	if !s.control.TenantActive(p.UserID) {
		s.revokeSession(cookie.Value)
		return Principal{}, false
	}
	return p, true
}

func (s *Server) passwordChangeRequired(p Principal) bool {
	return s.control != nil && !p.Admin && p.UserID != "" && s.control.PasswordMustChange(p.UserID)
}

func passwordChangeAllowed(r *http.Request) bool {
	if r.Method == http.MethodGet {
		return true
	}
	return r.URL.Path == "/v1/account/password"
}

func principalOf(r *http.Request) Principal {
	p, _ := r.Context().Value(principalKey{}).(Principal)
	return p
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
	return strings.EqualFold(u.Scheme, requestScheme(r)) && strings.EqualFold(u.Host, r.Host)
}

func requestScheme(r *http.Request) string {
	if r.TLS != nil {
		return "https"
	}
	if remoteIsLoopback(r) && forwardedProto(r) == "https" {
		return "https"
	}
	return "http"
}

func forwardedProto(r *http.Request) string {
	proto := strings.ToLower(strings.TrimSpace(r.Header.Get("X-Forwarded-Proto")))
	if proto == "" {
		return ""
	}
	if i := strings.IndexByte(proto, ','); i >= 0 {
		proto = strings.TrimSpace(proto[:i])
	}
	return proto
}

func remoteIsLoopback(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		host = strings.Trim(r.RemoteAddr, "[]")
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
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
	if s.adminPassword == "" {
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	adminOK := secretEqual(form.User, s.adminUser) && secretEqual(form.Password, s.adminPassword)
	var tenant *store.User
	if s.control != nil && !adminOK {
		tenant, _ = s.control.AuthenticateTenant(form.User, form.Password)
	}
	var p Principal
	switch {
	case adminOK:
		p = Principal{Admin: true}
	case tenant != nil:
		p = Principal{UserID: tenant.ID, Admin: false}
	default:
		s.recordAuthFailure(form.User, clientIP(r))
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
		return
	}
	if err := s.issueSession(w, p, time.Now()); err != nil {
		http.Error(w, `{"error":"session unavailable"}`, http.StatusInternalServerError)
		return
	}
	s.recordAuthSuccess(p, clientIP(r))
	role := "tenant"
	if p.Admin {
		role = "admin"
	}
	out := map[string]any{"ok": true, "role": role}
	if tenant != nil && tenant.PasswordMustChange {
		out["mustChangePassword"] = true
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(out)
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
	p := principalOf(r)
	switch r.Method {
	case "POST":
		// create invite, body may contain ttl
		var req struct {
			TTL                 string `json:"ttl"`
			ReplaceOldestUnused bool   `json:"replaceOldestUnused"`
		}
		if err := decodeOneJSON(r.Body, &req, true); err != nil {
			http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
			return
		}
		ttl := DefaultInviteTTL
		if req.TTL != "" {
			if d, err := time.ParseDuration(req.TTL); err == nil {
				ttl = d
			}
		}
		ttl = ClampInviteTTL(ttl)
		code, rec, replaced, err := s.control.mintInvite(p.UserID, ttl, req.ReplaceOldestUnused)
		if err != nil {
			if errors.Is(err, store.ErrTenantInviteLimit) {
				writeControlError(w, err)
				return
			}
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if replaced != nil {
			s.recordEvent(p, store.ControlEventInviteRevoke, "invite", replaced.ID, replaced.UserID, "签发新码时作废最早未用码")
		}
		if rec != nil {
			s.recordEvent(p, store.ControlEventInviteCreate, "invite", rec.ID, rec.UserID, "")
		}
		out := map[string]any{"inviteCode": code, "expiresIn": ttl.String()}
		if rec != nil {
			out["expiresAt"] = rec.ExpiresAt
		}
		if replaced != nil {
			out["replacedInviteId"] = replaced.ID
		}
		if enroll := s.enrollURI(code); enroll != "" {
			out["enroll"] = enroll
		}
		if s.publicControlURL != "" {
			out["controlUrl"] = s.publicControlURL
		}
		json.NewEncoder(w).Encode(out)
	case "GET":
		list, err := s.control.ListInvitesFor(p.UserID, p.Admin)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		// Map to sanitized view (don't expose raw code_hash)
		type out struct {
			ID               string `json:"id"`
			UserID           string `json:"userId,omitempty"`
			LoginName        string `json:"loginName,omitempty"`
			ExpiresAt        int64  `json:"expiresAt"`
			ConsumedAt       *int64 `json:"consumedAt,omitempty"`
			ConsumedHostID   string `json:"consumedHostId,omitempty"`
			ConsumedHostName string `json:"consumedHostName,omitempty"`
			ConsumedHostLive bool   `json:"consumedHostLive,omitempty"`
			RevokedAt        *int64 `json:"revokedAt,omitempty"`
			CreatedAt        int64  `json:"createdAt"`
		}
		var res []out
		for _, iv := range list {
			row := out{
				ID: iv.ID, ExpiresAt: iv.ExpiresAt, ConsumedAt: iv.ConsumedAt,
				ConsumedHostID: iv.ConsumedHostID, ConsumedHostName: iv.ConsumedHostName,
				ConsumedHostLive: iv.ConsumedHostLive,
				RevokedAt:        iv.RevokedAt, CreatedAt: iv.CreatedAt,
			}
			if p.Admin {
				row.UserID = iv.UserID
				row.LoginName = iv.LoginName
			}
			res = append(res, row)
		}
		json.NewEncoder(w).Encode(res)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) issueSession(w http.ResponseWriter, p Principal, now time.Time) error {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return err
	}
	token := base64.RawURLEncoding.EncodeToString(raw)
	hash := sha256.Sum256([]byte(token))
	expires := now.Add(sessionTTL)
	s.sessionsMu.Lock()
	for key, rec := range s.sessions {
		if !rec.Expires.After(now) {
			delete(s.sessions, key)
		}
	}
	if len(s.sessions) >= maxAdminSessions {
		var oldestKey [sha256.Size]byte
		var oldest time.Time
		for key, rec := range s.sessions {
			if oldest.IsZero() || rec.Expires.Before(oldest) {
				oldestKey = key
				oldest = rec.Expires
			}
		}
		delete(s.sessions, oldestKey)
	}
	s.sessions[hash] = sessionRec{UserID: p.UserID, Admin: p.Admin, Expires: expires}
	s.sessionsMu.Unlock()
	http.SetCookie(w, &http.Cookie{
		Name: sessionCookieName, Value: token, HttpOnly: true, Secure: s.secureCookies, SameSite: http.SameSiteStrictMode,
		Path: "/", MaxAge: int(sessionTTL.Seconds()), Expires: expires,
	})
	return nil
}

func (s *Server) sessionPrincipal(token string, now time.Time) (Principal, bool) {
	if token == "" {
		return Principal{}, false
	}
	hash := sha256.Sum256([]byte(token))
	s.sessionsMu.Lock()
	defer s.sessionsMu.Unlock()
	rec, ok := s.sessions[hash]
	if !ok || !rec.Expires.After(now) {
		delete(s.sessions, hash)
		return Principal{}, false
	}
	return Principal{UserID: rec.UserID, Admin: rec.Admin}, true
}

func (s *Server) revokeSession(token string) {
	hash := sha256.Sum256([]byte(token))
	s.sessionsMu.Lock()
	delete(s.sessions, hash)
	s.sessionsMu.Unlock()
}

func (s *Server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if principalOf(r).Admin {
		return true
	}
	http.Error(w, `{"error":"forbidden"}`, http.StatusForbidden)
	return false
}

func writeControlError(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, store.ErrInviteNotFound), errors.Is(err, store.ErrHostNotFound), errors.Is(err, store.ErrUserNotFound):
		http.Error(w, `{"error":"not found"}`, http.StatusNotFound)
	case errors.Is(err, store.ErrInvalidLogin), errors.Is(err, store.ErrWeakPassword):
		http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusBadRequest)
	case errors.Is(err, store.ErrCurrentPassword):
		http.Error(w, `{"error":"unauthorized"}`, http.StatusUnauthorized)
	case errors.Is(err, store.ErrPasswordMustChange):
		http.Error(w, `{"error":"password change required"}`, http.StatusForbidden)
	case errors.Is(err, store.ErrSamePassword):
		http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusBadRequest)
	case errors.Is(err, store.ErrLoginTaken):
		http.Error(w, `{"error":"login name taken"}`, http.StatusConflict)
	case errors.Is(err, store.ErrTenantInviteLimit), errors.Is(err, store.ErrTenantHostLimit):
		http.Error(w, `{"error":"`+err.Error()+`"}`, http.StatusConflict)
	default:
		http.Error(w, err.Error(), http.StatusBadRequest)
	}
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
			if fwd := forwardedClientIP(r); fwd != "" {
				return fwd
			}
			// All of 127.0.0.0/8 and ::1 are one local trust source. Treating
			// each address separately lets a local attacker rotate source IPs and
			// obtain a fresh password-guessing bucket.
			return "loopback"
		}
		return ip.String()
	}
	return host
}

func forwardedClientIP(r *http.Request) string {
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		parts := strings.Split(xff, ",")
		cand := strings.TrimSpace(parts[len(parts)-1])
		if ip := net.ParseIP(cand); ip != nil && !ip.IsLoopback() {
			return ip.String()
		}
	}
	if realIP := strings.TrimSpace(r.Header.Get("X-Real-IP")); realIP != "" {
		if ip := net.ParseIP(realIP); ip != nil && !ip.IsLoopback() {
			return ip.String()
		}
	}
	return ""
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

func actionTarget(path, kind, action string) (string, bool) {
	p := strings.TrimSuffix(path, "/")
	parts := strings.Split(p, "/")
	if len(parts) != 5 || parts[1] != "v1" || parts[2] != kind || parts[4] != action {
		return "", false
	}
	if parts[3] == "" {
		return "", false
	}
	return parts[3], true
}

func (s *Server) handleDevices(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	devs, err := s.control.ListDevices()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSONOK(w, map[string]any{"devices": devs})
}

func (s *Server) handleDeviceItem(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if id, ok := actionTarget(r.URL.Path, "devices", "disable"); ok {
		if err := s.control.DisableDevice(id); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSONOK(w, map[string]any{"ok": true})
		return
	}
	if id, ok := actionTarget(r.URL.Path, "devices", "enable"); ok {
		if err := s.control.EnableDevice(id); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSONOK(w, map[string]any{"ok": true})
		return
	}
	if id, ok := actionTarget(r.URL.Path, "devices", "delete"); ok {
		if err := s.control.DeleteDevice(id); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSONOK(w, map[string]any{"ok": true})
		return
	}
	http.Error(w, "not found", http.StatusNotFound)
}

// handleAnonymousSetting toggles the anonymous-enrollment kill switch. The
// state persists in settings so a restart cannot silently re-open the door.
func (s *Server) handleAnonymousSetting(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	if r.Method == http.MethodGet {
		writeJSONOK(w, map[string]any{"anonymousEnroll": s.control.AnonymousEnabled()})
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	var body struct {
		Enabled bool `json:"enabled"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		http.Error(w, "invalid body", http.StatusBadRequest)
		return
	}
	if err := s.control.SetAnonymousEnabled(body.Enabled); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSONOK(w, map[string]any{"ok": true, "anonymousEnroll": body.Enabled})
}

// revokeTarget validates that a POST path is exactly /v1/<kind>/<id>/revoke
// and returns the id. Requiring the literal /revoke suffix keeps the catch-all
// routes below from treating an arbitrary trailing segment as a revoke trigger.
func revokeTarget(path, kind string) (string, bool) {
	return actionTarget(path, kind, "revoke")
}

func writeJSONOK(w http.ResponseWriter, extra map[string]any) {
	out := map[string]any{"ok": true}
	for k, v := range extra {
		out[k] = v
	}
	json.NewEncoder(w).Encode(out)
}

func hostIsOnline(revokedAt, lastSeenAt *int64, now time.Time) bool {
	if revokedAt != nil || lastSeenAt == nil {
		return false
	}
	seen := time.Unix(*lastSeenAt, 0)
	return !seen.After(now) && now.Sub(seen) <= hostOnlineWindow
}

func (s *Server) handleInviteItem(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	if strings.TrimSuffix(r.URL.Path, "/") == "/v1/invites/purge" {
		n, err := s.control.PurgeStaleInvitesOwned(p.UserID, p.Admin)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSONOK(w, map[string]any{"deleted": n})
		return
	}
	if id, ok := actionTarget(r.URL.Path, "invites", "revoke"); ok {
		inv, _ := s.control.GetInvite(id)
		if err := s.control.RevokeInviteOwned(id, p.UserID, p.Admin); err != nil {
			writeControlError(w, err)
			return
		}
		subject := ""
		if inv != nil {
			subject = inv.UserID
		}
		s.recordEvent(p, store.ControlEventInviteRevoke, "invite", id, subject, "")
		writeJSONOK(w, nil)
		return
	}
	if id, ok := actionTarget(r.URL.Path, "invites", "delete"); ok {
		inv, _ := s.control.GetInvite(id)
		if err := s.control.DeleteInviteOwned(id, p.UserID, p.Admin); err != nil {
			writeControlError(w, err)
			return
		}
		subject := ""
		if inv != nil {
			subject = inv.UserID
		}
		s.recordEvent(p, store.ControlEventInviteDelete, "invite", id, subject, "")
		writeJSONOK(w, nil)
		return
	}
	http.Error(w, "bad path", http.StatusBadRequest)
}

func (s *Server) handleHosts(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	list, err := s.control.ListHostsFor(p.UserID, p.Admin)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	type hostOut struct {
		ID             string `json:"id"`
		UserID         string `json:"userId,omitempty"`
		LoginName      string `json:"loginName,omitempty"`
		HostName       string `json:"hostName"`
		RouteIdHash    string `json:"routeIdHash"` // SHA256 of full routeId, not decodable to route
		Generation     int64  `json:"generation"`
		MaxStreams     int    `json:"maxStreams"`
		Version        string `json:"version"`
		LastSeenAt     *int64 `json:"lastSeenAt,omitempty"`
		RevokedAt      *int64 `json:"revokedAt,omitempty"`
		SuspendedUntil *int64 `json:"suspendedUntil,omitempty"`
		CreatedAt      int64  `json:"createdAt"`
		Online         bool   `json:"online"`
	}
	var res []hostOut
	for _, h := range list {
		routeHash := base64.RawURLEncoding.EncodeToString(cryptoutil.SHA256(h.RouteID)[:8]) + "..."
		row := hostOut{
			ID: h.ID, HostName: h.HostName, RouteIdHash: routeHash,
			Generation: h.Generation, MaxStreams: h.MaxStreams, Version: h.Version,
			LastSeenAt: h.LastSeenAt, RevokedAt: h.RevokedAt, CreatedAt: h.CreatedAt,
			SuspendedUntil: h.SuspendedUntil,
			Online:         hostIsOnline(h.RevokedAt, h.LastSeenAt, time.Now()),
		}
		if p.Admin {
			row.UserID = h.UserID
			row.LoginName = h.LoginName
		}
		res = append(res, row)
	}
	json.NewEncoder(w).Encode(res)
}

func (s *Server) handleHostItem(w http.ResponseWriter, r *http.Request) {
	if r.Method != "POST" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	if strings.TrimSuffix(r.URL.Path, "/") == "/v1/hosts/purge" {
		n, err := s.control.PurgeRevokedHostsOwned(p.UserID, p.Admin)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		writeJSONOK(w, map[string]any{"deleted": n})
		return
	}
	if id, ok := actionTarget(r.URL.Path, "hosts", "revoke"); ok {
		if !protocol.ValidHostID(id) {
			http.Error(w, "invalid host id", http.StatusBadRequest)
			return
		}
		delivered, acked, err := s.control.RevokeHostOwned(id, p.UserID, p.Admin)
		if err != nil {
			writeControlError(w, err)
			return
		}
		subject := ""
		detail := ""
		if h, herr := s.control.GetHost(id); herr == nil {
			subject = h.UserID
			detail = protocol.SanitizeHostName(h.HostName, h.ID)
		}
		s.recordEvent(p, store.ControlEventHostRevoke, "host", id, subject, detail)
		// Report how many relays provably closed the route. pending is the
		// number of relays that only received the push and will converge via
		// the reconciliation poll (reg.Revoke on next sync).
		writeJSONOK(w, map[string]any{
			"ok": true, "delivered": delivered, "acked": acked,
			"pending": delivered - acked,
		})
		return
	}
	if id, ok := actionTarget(r.URL.Path, "hosts", "delete"); ok {
		if !protocol.ValidHostID(id) {
			http.Error(w, "invalid host id", http.StatusBadRequest)
			return
		}
		h, _ := s.control.GetHost(id)
		delivered, acked, err := s.control.DeleteHostOwned(id, p.UserID, p.Admin)
		if err != nil {
			writeControlError(w, err)
			return
		}
		subject := ""
		detail := ""
		if h != nil {
			subject = h.UserID
			detail = protocol.SanitizeHostName(h.HostName, h.ID)
		}
		s.recordEvent(p, store.ControlEventHostDelete, "host", id, subject, detail)
		writeJSONOK(w, map[string]any{"ok": true, "delivered": delivered, "acked": acked})
		return
	}
	http.Error(w, "bad path", http.StatusBadRequest)
}

func (s *Server) handleOverview(w http.ResponseWriter, r *http.Request) {
	if r.Method != "GET" {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	hosts, _ := s.control.ListHostsFor(p.UserID, p.Admin)
	invites, _ := s.control.ListInvitesFor(p.UserID, p.Admin)
	role := "tenant"
	if p.Admin {
		role = "admin"
	}
	out := map[string]any{
		"hosts":   len(hosts),
		"invites": len(invites),
		"role":    role,
	}
	if len(s.tlsFingerprint) == 64 {
		out["tlsFingerprint"] = s.tlsFingerprint
	}
	if host := strings.TrimSpace(s.publicHost); host != "" {
		out["publicHost"] = host
		if p.Admin {
			out["anonymousEnroll"] = s.control.AnonymousEnabled()
			devs, _ := s.control.ListDevices()
			out["deviceCount"] = len(devs)
		}
	}
	if p.Admin {
		maxHosts, maxInvites := s.control.TenantLimits()
		out["tenantLimits"] = map[string]int{"maxLiveHosts": maxHosts, "maxUnusedInvites": maxInvites}
		if url := strings.TrimSpace(s.publicControlURL); url != "" {
			out["publicControlURL"] = url
		}
	} else if p.UserID != "" {
		if q, err := s.control.TenantQuota(p.UserID); err == nil && q != nil {
			out["quota"] = q
		}
		if s.control.PasswordMustChange(p.UserID) {
			out["mustChangePassword"] = true
		}
	}
	json.NewEncoder(w).Encode(out)
}

func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	if s.control == nil {
		json.NewEncoder(w).Encode([]any{})
		return
	}
	list, err := s.control.ListControlEvents(p.UserID, p.Admin)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	type out struct {
		ID            string `json:"id"`
		At            int64  `json:"at"`
		ActorLogin    string `json:"actorLogin"`
		Action        string `json:"action"`
		TargetKind    string `json:"targetKind"`
		TargetID      string `json:"targetId"`
		Detail        string `json:"detail,omitempty"`
		SubjectUserID string `json:"subjectUserId,omitempty"`
		SubjectLogin  string `json:"subjectLogin,omitempty"`
	}
	res := make([]out, 0, len(list))
	for _, ev := range list {
		row := out{
			ID: ev.ID, At: ev.At, ActorLogin: ev.ActorLogin, Action: ev.Action,
			TargetKind: ev.TargetKind, TargetID: ev.TargetID,
		}
		if p.Admin {
			row.SubjectUserID = ev.SubjectUserID
			row.SubjectLogin = ev.SubjectLogin
			row.Detail = ev.Detail
		} else if tenantEventShowsDetail(ev.Action) {
			row.Detail = ev.Detail
		}
		res = append(res, row)
	}
	json.NewEncoder(w).Encode(res)
}

func tenantEventShowsDetail(action string) bool {
	switch action {
	case store.ControlEventHostEnroll, store.ControlEventHostRevoke, store.ControlEventHostDelete,
		store.ControlEventInviteRevoke:
		return true
	default:
		return false
	}
}

func (s *Server) actorOf(p Principal) (id, login string) {
	if p.Admin {
		login = strings.TrimSpace(s.adminUser)
		if login == "" {
			login = "admin"
		}
		return p.UserID, login
	}
	id = p.UserID
	login = id
	if s.control != nil && id != "" {
		if name := s.control.UserLogin(id); name != "" {
			login = name
		}
	}
	return id, login
}

func (s *Server) recordEvent(p Principal, action, kind, targetID, subjectUserID, detail string) {
	if s.control == nil {
		return
	}
	actorID, actorLogin := s.actorOf(p)
	s.control.AppendControlEvent(store.ControlEvent{
		ActorID:       actorID,
		ActorLogin:    actorLogin,
		Action:        action,
		TargetKind:    kind,
		TargetID:      targetID,
		SubjectUserID: subjectUserID,
		Detail:        detail,
	})
}

func authEventDetail(ip string) string {
	ip = strings.TrimSpace(ip)
	if ip == "" {
		return ""
	}
	return "ip=" + ip
}

func (s *Server) recordAuthSuccess(p Principal, ip string) {
	if s.control == nil {
		return
	}
	detail := authEventDetail(ip)
	if p.Admin {
		_, login := s.actorOf(p)
		s.control.AppendControlEvent(store.ControlEvent{
			ActorLogin: login,
			Action:     store.ControlEventAuthLogin,
			TargetKind: "account",
			TargetID:   "admin",
			Detail:     detail,
		})
		return
	}
	s.recordEvent(p, store.ControlEventAuthLogin, "account", p.UserID, p.UserID, detail)
}

func (s *Server) recordAuthFailure(loginName, ip string) {
	if s.control == nil {
		return
	}
	detail := authEventDetail(ip)
	if secretEqual(loginName, s.adminUser) {
		_, login := s.actorOf(Principal{Admin: true})
		s.control.AppendControlEvent(store.ControlEvent{
			ActorLogin: login,
			Action:     store.ControlEventAuthLoginFail,
			TargetKind: "account",
			TargetID:   "admin",
			Detail:     detail,
		})
		return
	}
	u := s.control.LookupTenantByLogin(loginName)
	if u == nil {
		return
	}
	s.control.AppendControlEvent(store.ControlEvent{
		ActorID:       u.ID,
		ActorLogin:    u.LoginName,
		Action:        store.ControlEventAuthLoginFail,
		TargetKind:    "account",
		TargetID:      u.ID,
		SubjectUserID: u.ID,
		Detail:        detail,
	})
}

func (s *Server) handleTenants(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	p := principalOf(r)
	switch r.Method {
	case http.MethodGet:
		list, err := s.control.ListTenants()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		type out struct {
			ID                 string `json:"id"`
			LoginName          string `json:"loginName"`
			DisplayName        string `json:"displayName"`
			DisabledAt         *int64 `json:"disabledAt,omitempty"`
			CreatedAt          int64  `json:"createdAt"`
			LiveHosts          int    `json:"liveHosts"`
			UnusedInvites      int    `json:"unusedInvites"`
			MaxLiveHosts       int    `json:"maxLiveHosts"`
			MaxUnusedInvites   int    `json:"maxUnusedInvites"`
			HostFull           bool   `json:"hostFull,omitempty"`
			InviteFull         bool   `json:"inviteFull,omitempty"`
			PasswordMustChange bool   `json:"passwordMustChange,omitempty"`
		}
		maxHosts, maxInvites := s.control.TenantLimits()
		res := make([]out, 0, len(list))
		for _, u := range list {
			res = append(res, out{
				ID: u.ID, LoginName: u.LoginName, DisplayName: u.DisplayName, DisabledAt: u.DisabledAt, CreatedAt: u.CreatedAt,
				LiveHosts: u.LiveHosts, UnusedInvites: u.UnusedInvites, MaxLiveHosts: maxHosts, MaxUnusedInvites: maxInvites,
				HostFull:           maxHosts > 0 && u.LiveHosts >= maxHosts,
				InviteFull:         maxInvites > 0 && u.UnusedInvites >= maxInvites,
				PasswordMustChange: u.PasswordMustChange,
			})
		}
		json.NewEncoder(w).Encode(res)
	case http.MethodPost:
		var req struct {
			LoginName   string `json:"loginName"`
			DisplayName string `json:"displayName"`
			Password    string `json:"password"`
		}
		if err := decodeOneJSON(r.Body, &req, false); err != nil {
			http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
			return
		}
		u, err := s.control.CreateTenant(req.LoginName, req.DisplayName, req.Password, s.adminUser)
		if err != nil {
			writeControlError(w, err)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"ok": true, "id": u.ID, "loginName": u.LoginName, "displayName": u.DisplayName,
		})
		s.recordEvent(p, store.ControlEventTenantCreate, "tenant", u.ID, u.ID, u.LoginName)
	default:
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
	}
}

func (s *Server) handleTenantItem(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	if id, ok := actionTarget(r.URL.Path, "tenants", "disable"); ok {
		p := principalOf(r)
		if err := s.control.DisableTenant(id); err != nil {
			writeControlError(w, err)
			return
		}
		s.recordEvent(p, store.ControlEventTenantDisable, "tenant", id, id, "")
		writeJSONOK(w, nil)
		return
	}
	if id, ok := actionTarget(r.URL.Path, "tenants", "enable"); ok {
		p := principalOf(r)
		if err := s.control.EnableTenant(id); err != nil {
			writeControlError(w, err)
			return
		}
		s.recordEvent(p, store.ControlEventTenantEnable, "tenant", id, id, "")
		writeJSONOK(w, nil)
		return
	}
	if id, ok := actionTarget(r.URL.Path, "tenants", "password"); ok {
		var req struct {
			Password string `json:"password"`
		}
		if err := decodeOneJSON(r.Body, &req, false); err != nil {
			http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
			return
		}
		if err := s.control.SetTenantPassword(id, req.Password); err != nil {
			writeControlError(w, err)
			return
		}
		s.revokeUserSessions(id, "")
		s.recordEvent(principalOf(r), store.ControlEventTenantPassword, "tenant", id, id, "")
		writeJSONOK(w, nil)
		return
	}
	http.Error(w, "bad path", http.StatusBadRequest)
}

func (s *Server) handleAccountPassword(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	p := principalOf(r)
	if p.Admin || p.UserID == "" {
		http.Error(w, `{"error":"admin password is in config"}`, http.StatusBadRequest)
		return
	}
	var req struct {
		CurrentPassword string `json:"currentPassword"`
		NewPassword     string `json:"newPassword"`
	}
	if err := decodeOneJSON(r.Body, &req, false); err != nil {
		http.Error(w, `{"error":"bad request"}`, http.StatusBadRequest)
		return
	}
	if err := s.control.ChangeTenantPassword(p.UserID, req.CurrentPassword, req.NewPassword); err != nil {
		writeControlError(w, err)
		return
	}
	keep := ""
	if cookie, err := r.Cookie(sessionCookieName); err == nil {
		keep = cookie.Value
	}
	s.revokeUserSessions(p.UserID, keep)
	s.recordEvent(p, store.ControlEventAccountPassword, "account", p.UserID, p.UserID, "")
	writeJSONOK(w, nil)
}

func (s *Server) revokeUserSessions(userID, keepToken string) {
	if userID == "" {
		return
	}
	var keep [sha256.Size]byte
	if keepToken != "" {
		keep = sha256.Sum256([]byte(keepToken))
	}
	s.sessionsMu.Lock()
	defer s.sessionsMu.Unlock()
	for hash, rec := range s.sessions {
		if rec.Admin || rec.UserID != userID {
			continue
		}
		if keepToken != "" && hash == keep {
			continue
		}
		delete(s.sessions, hash)
	}
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
