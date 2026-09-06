package control

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
	"github.com/lunaship/dsh-links/relay/internal/store"
)

func TestHandlerServesWebAssets(t *testing.T) {
	t.Parallel()

	handler := NewServer(nil, "", "", "").Handler()
	tests := []struct {
		path        string
		contentType string
		marker      string
	}{
		{path: "/", contentType: "text/html", marker: "DSH Links Relay — Control"},
		{path: "/tokens.css", contentType: "text/css", marker: "--color-paper"},
		{path: "/app.css", contentType: "text/css", marker: "macrostructure: Stat-Led"},
		{path: "/app.js", contentType: "text/javascript", marker: "'/v1/overview'"},
	}

	for _, test := range tests {
		t.Run(test.path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			request := httptest.NewRequest(http.MethodGet, test.path, nil)
			handler.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusOK {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusOK)
			}
			if got := recorder.Header().Get("Content-Type"); !strings.HasPrefix(got, test.contentType) {
				t.Fatalf("Content-Type = %q, want prefix %q", got, test.contentType)
			}
			if !strings.Contains(recorder.Body.String(), test.marker) {
				t.Fatalf("response for %s does not contain %q", test.path, test.marker)
			}
			if test.path == "/" && !strings.Contains(recorder.Body.String(), "接入码") {
				t.Fatal("control UI is missing the invite copy surface")
			}
			if test.path == "/" && !strings.Contains(recorder.Body.String(), "清理失效") {
				t.Fatal("control UI is missing invite record cleanup")
			}
		})
	}
}

func TestLoginUsesOpaqueRevocableSession(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	handler := server.Handler()

	login := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRecorder := httptest.NewRecorder()
	handler.ServeHTTP(loginRecorder, login)
	if loginRecorder.Code != http.StatusOK {
		t.Fatalf("login status=%d body=%s", loginRecorder.Code, loginRecorder.Body.String())
	}
	result := loginRecorder.Result()
	cookies := result.Cookies()
	if len(cookies) != 1 {
		t.Fatalf("session cookies=%d, want 1", len(cookies))
	}
	session := cookies[0]
	if session.Value == "correct horse battery staple" || len(session.Value) < 40 {
		t.Fatalf("session cookie is not an opaque random token: %q", session.Value)
	}

	authorized := httptest.NewRequest(http.MethodGet, "/v1/not-found", nil)
	authorized.AddCookie(session)
	authorizedRecorder := httptest.NewRecorder()
	handler.ServeHTTP(authorizedRecorder, authorized)
	if authorizedRecorder.Code == http.StatusUnauthorized {
		t.Fatal("issued session did not authorize request")
	}

	passwordCookie := httptest.NewRequest(http.MethodGet, "/v1/not-found", nil)
	passwordCookie.AddCookie(&http.Cookie{Name: sessionCookieName, Value: "correct horse battery staple"})
	passwordRecorder := httptest.NewRecorder()
	handler.ServeHTTP(passwordRecorder, passwordCookie)
	if passwordRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("raw password cookie status=%d, want 401", passwordRecorder.Code)
	}

	logout := httptest.NewRequest(http.MethodPost, "/logout", nil)
	logout.AddCookie(session)
	logout.Header.Set("Content-Type", "application/json")
	logoutRecorder := httptest.NewRecorder()
	handler.ServeHTTP(logoutRecorder, logout)
	if logoutRecorder.Code != http.StatusOK {
		t.Fatalf("logout status=%d", logoutRecorder.Code)
	}
	afterLogout := httptest.NewRequest(http.MethodGet, "/v1/not-found", nil)
	afterLogout.AddCookie(session)
	afterLogoutRecorder := httptest.NewRecorder()
	handler.ServeHTTP(afterLogoutRecorder, afterLogout)
	if afterLogoutRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("revoked session status=%d, want 401", afterLogoutRecorder.Code)
	}
}

func TestTLSServerMarksSessionCookieSecure(t *testing.T) {
	server := NewServerWithSecureCookies(nil, "legacy-token-0123456789", "admin", "correct horse battery staple", true)
	req := httptest.NewRequest(http.MethodPost, "https://127.0.0.1:8080/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("login status=%d body=%s", recorder.Code, recorder.Body.String())
	}
	cookies := recorder.Result().Cookies()
	if len(cookies) != 1 || !cookies[0].Secure {
		t.Fatalf("TLS session cookie is not Secure: %+v", cookies)
	}
}

func TestAdminRequestBodyIsBounded(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "password")
	body, err := json.Marshal(map[string]string{"user": "admin", "password": strings.Repeat("x", maxAdminBodyBytes)})
	if err != nil {
		t.Fatal(err)
	}
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(string(body)))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("oversized body status=%d, want 400", recorder.Code)
	}
}

func TestLoginRejectsEmptyPassword(t *testing.T) {
	token := "legacy-token-0123456789"
	server := NewServer(nil, token, "admin", "")
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":""}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("empty password login status=%d, want 401", recorder.Code)
	}
}

func TestLoginRateLimitedAfterBurst(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	handler := server.Handler()

	for i := 0; i < loginBurstAttempts; i++ {
		req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"wrong password"}`))
		req.Header.Set("Content-Type", "application/json")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d status=%d, want 401", i+1, recorder.Code)
		}
	}

	// Even the correct password is refused once the burst is spent.
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "application/json")
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("post-burst login status=%d, want 429", recorder.Code)
	}
	if recorder.Header().Get("Retry-After") == "" {
		t.Fatal("429 response is missing Retry-After")
	}
}

func TestLoginRejectsNonJSONContentType(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "text/plain")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("text/plain login status=%d, want 400", recorder.Code)
	}
}

func TestMutationRejectsMissingOrMisleadingContentType(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	for _, contentType := range []string{"", "text/plain; application/json"} {
		req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
		if contentType != "" {
			req.Header.Set("Content-Type", contentType)
		}
		recorder := httptest.NewRecorder()
		server.Handler().ServeHTTP(recorder, req)
		if recorder.Code != http.StatusBadRequest {
			t.Fatalf("Content-Type %q status=%d, want 400", contentType, recorder.Code)
		}
	}
}

func TestMutationRejectsDifferentLoopbackOriginPort(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8080/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "http://127.0.0.1:9090")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusForbidden {
		t.Fatalf("cross-port Origin status=%d, want 403", recorder.Code)
	}
}

func TestMutationAllowsExactOrigin(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	req := httptest.NewRequest(http.MethodPost, "http://127.0.0.1:8080/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", "http://127.0.0.1:8080")
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK {
		t.Fatalf("same-origin status=%d body=%s", recorder.Code, recorder.Body.String())
	}
}

func TestLoginLimiterAggregatesLoopbackAddressRotation(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	handler := server.Handler()
	for i := 0; i < loginBurstAttempts; i++ {
		req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"wrong password"}`))
		req.Header.Set("Content-Type", "application/json")
		req.RemoteAddr = fmt.Sprintf("127.0.0.%d:12345", i+1)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d status=%d, want 401", i+1, recorder.Code)
		}
	}
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	req.Header.Set("Content-Type", "application/json")
	req.RemoteAddr = "127.0.0.99:12345"
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusTooManyRequests {
		t.Fatalf("rotated loopback status=%d, want 429", recorder.Code)
	}
}

func TestSecurityAndCachingHeaders(t *testing.T) {
	server := NewServer(nil, "legacy-token-0123456789", "admin", "correct horse battery staple")
	handler := server.Handler()

	apiRecorder := httptest.NewRecorder()
	handler.ServeHTTP(apiRecorder, httptest.NewRequest(http.MethodGet, "/v1/overview", nil))
	if apiRecorder.Code != http.StatusUnauthorized {
		t.Fatalf("unauthenticated overview status=%d, want 401", apiRecorder.Code)
	}
	apiHeader := apiRecorder.Header()
	if got := apiHeader.Get("Cache-Control"); got != "no-store" {
		t.Fatalf("API Cache-Control=%q, want no-store", got)
	}
	if got := apiHeader.Get("X-Content-Type-Options"); got != "nosniff" {
		t.Fatalf("X-Content-Type-Options=%q, want nosniff", got)
	}
	if got := apiHeader.Get("X-Frame-Options"); got != "DENY" {
		t.Fatalf("X-Frame-Options=%q, want DENY", got)
	}

	uiRecorder := httptest.NewRecorder()
	handler.ServeHTTP(uiRecorder, httptest.NewRequest(http.MethodGet, "/", nil))
	csp := uiRecorder.Header().Get("Content-Security-Policy")
	if !strings.Contains(csp, "default-src 'self'") || !strings.Contains(csp, "frame-ancestors 'none'") {
		t.Fatalf("UI CSP=%q, want strict self-only policy", csp)
	}
	if got := uiRecorder.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("UI Cache-Control=%q, want no-store", got)
	}
}

func TestOverviewIncludesTLSFingerprint(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	server := NewServer(ctrl, "legacy-token-0123456789", "admin", "correct horse battery staple")
	fp := strings.Repeat("ab", 32)
	server.SetTLSFingerprint("  " + strings.ToUpper(fp) + "  ")
	handler := server.Handler()

	login := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRecorder := httptest.NewRecorder()
	handler.ServeHTTP(loginRecorder, login)
	if loginRecorder.Code != http.StatusOK {
		t.Fatalf("login status=%d body=%s", loginRecorder.Code, loginRecorder.Body.String())
	}
	cookies := loginRecorder.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("session cookies=%d, want 1", len(cookies))
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/overview", nil)
	req.AddCookie(cookies[0])
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("overview status=%d body=%s", rec.Code, rec.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["tlsFingerprint"] != fp {
		t.Fatalf("tlsFingerprint=%v want %s", body["tlsFingerprint"], fp)
	}

	plain := NewServer(ctrl, "legacy-token-0123456789", "admin", "correct horse battery staple")
	plainHandler := plain.Handler()
	login2 := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	login2.Header.Set("Content-Type", "application/json")
	login2Rec := httptest.NewRecorder()
	plainHandler.ServeHTTP(login2Rec, login2)
	plainReq := httptest.NewRequest(http.MethodGet, "/v1/overview", nil)
	plainReq.AddCookie(login2Rec.Result().Cookies()[0])
	plainRec := httptest.NewRecorder()
	plainHandler.ServeHTTP(plainRec, plainReq)
	var plainBody map[string]any
	if err := json.Unmarshal(plainRec.Body.Bytes(), &plainBody); err != nil {
		t.Fatal(err)
	}
	if _, ok := plainBody["tlsFingerprint"]; ok {
		t.Fatalf("overview leaked tlsFingerprint=%v", plainBody["tlsFingerprint"])
	}
}

func TestCreateInviteReturnsEnrollURI(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	server := NewServer(ctrl, "legacy-token-0123456789", "admin", "correct horse battery staple")
	fp := strings.Repeat("cd", 32)
	server.SetTLSFingerprint(fp)
	server.SetEnrollMeta("relay.dshlinks.com", "8444", true)
	handler := server.Handler()

	login := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRec := httptest.NewRecorder()
	handler.ServeHTTP(loginRec, login)
	if loginRec.Code != http.StatusOK {
		t.Fatalf("login status=%d body=%s", loginRec.Code, loginRec.Body.String())
	}

	req := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(loginRec.Result().Cookies()[0])
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("invite status=%d body=%s", rec.Code, rec.Body.String())
	}
	var body map[string]string
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["inviteCode"] == "" {
		t.Fatal("missing inviteCode")
	}
	if body["enroll"] == "" || !strings.Contains(body["enroll"], body["inviteCode"]) || !strings.Contains(body["enroll"], "dsh-relay://relay.dshlinks.com/") {
		t.Fatalf("enroll=%q", body["enroll"])
	}
	if !strings.Contains(body["enroll"], fp) {
		t.Fatalf("self-signed enroll missing fingerprint: %s", body["enroll"])
	}

	server.SetEnrollMeta("relay.dshlinks.com", "8444", false)
	req2 := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	req2.Header.Set("Content-Type", "application/json")
	req2.AddCookie(loginRec.Result().Cookies()[0])
	rec2 := httptest.NewRecorder()
	handler.ServeHTTP(rec2, req2)
	var body2 map[string]string
	if err := json.Unmarshal(rec2.Body.Bytes(), &body2); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(body2["enroll"], "fp=") {
		t.Fatalf("public-CA enroll leaked fingerprint: %s", body2["enroll"])
	}
}

func TestRevokeTargetRequiresRevokeSuffix(t *testing.T) {
	t.Parallel()
	valid := []struct{ path, kind, id string }{
		{"/v1/hosts/h1/revoke", "hosts", "h1"},
		{"/v1/invites/abc/revoke", "invites", "abc"},
		{"/v1/hosts/h1/revoke/", "hosts", "h1"}, // trailing slash tolerated
		{"/v1/hosts/h1/delete", "hosts", "h1"},
		{"/v1/invites/abc/delete", "invites", "abc"},
	}
	for _, tc := range valid {
		action := "revoke"
		if strings.HasSuffix(strings.TrimSuffix(tc.path, "/"), "delete") {
			action = "delete"
		}
		id, ok := actionTarget(tc.path, tc.kind, action)
		if !ok || id != tc.id {
			t.Fatalf("actionTarget(%q, %q, %q) = (%q,%v), want (%q,true)", tc.path, tc.kind, action, id, ok, tc.id)
		}
		if action == "revoke" {
			id, ok := revokeTarget(tc.path, tc.kind)
			if !ok || id != tc.id {
				t.Fatalf("revokeTarget(%q, %q) = (%q,%v), want (%q,true)", tc.path, tc.kind, id, ok, tc.id)
			}
		}
	}
	// These paths must NOT be treated as a revocation trigger, even though the
	// /v1/<kind>/ handler is a catch-all: only the literal /revoke suffix counts.
	invalid := []string{
		"/v1/hosts/h1",
		"/v1/hosts/h1/",
		"/v1/hosts/h1/other",
		"/v1/hosts//revoke",
		"/v1/other/h1/revoke",
		"/v1/hosts/h1/revoke/x",
		"/hosts/h1/revoke",
		"/v1/invites//revoke",
	}
	for _, p := range invalid {
		for _, kind := range []string{"hosts", "invites"} {
			if id, ok := revokeTarget(p, kind); ok {
				t.Fatalf("revokeTarget(%q, %q) = (%q,true), want rejected", p, kind, id)
			}
		}
	}
}

func TestDeleteAndPurgeInviteAPI(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	server := NewServer(ctrl, "legacy-token-0123456789", "admin", "correct horse battery staple")
	handler := server.Handler()
	login := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"correct horse battery staple"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRec := httptest.NewRecorder()
	handler.ServeHTTP(loginRec, login)
	cookie := loginRec.Result().Cookies()[0]

	if _, err := ctrl.CreateInvite(time.Hour); err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.CreateInvite(time.Hour); err != nil {
		t.Fatal(err)
	}
	list, err := ctrl.ListInvites()
	if err != nil || len(list) != 2 {
		t.Fatalf("invites: %v count=%d", err, len(list))
	}

	del := httptest.NewRequest(http.MethodPost, "/v1/invites/"+list[0].ID+"/delete", strings.NewReader(`{}`))
	del.Header.Set("Content-Type", "application/json")
	del.AddCookie(cookie)
	delRec := httptest.NewRecorder()
	handler.ServeHTTP(delRec, del)
	if delRec.Code != http.StatusOK {
		t.Fatalf("delete invite status=%d body=%s", delRec.Code, delRec.Body.String())
	}

	left, err := ctrl.ListInvites()
	if err != nil || len(left) != 1 {
		t.Fatalf("after delete: %v count=%d", err, len(left))
	}
	if err := ctrl.RevokeInvite(left[0].ID); err != nil {
		t.Fatal(err)
	}

	purge := httptest.NewRequest(http.MethodPost, "/v1/invites/purge", strings.NewReader(`{}`))
	purge.Header.Set("Content-Type", "application/json")
	purge.AddCookie(cookie)
	purgeRec := httptest.NewRecorder()
	handler.ServeHTTP(purgeRec, purge)
	if purgeRec.Code != http.StatusOK {
		t.Fatalf("purge invites status=%d body=%s", purgeRec.Code, purgeRec.Body.String())
	}
	final, err := ctrl.ListInvites()
	if err != nil || len(final) != 0 {
		t.Fatalf("after purge: %v count=%d", err, len(final))
	}
}

func newAdminTestServer(t *testing.T) (*Server, *Control, *store.Store) {
	t.Helper()
	ctrl, st := newTestControl(t)
	ctrl.ApplyAnonymousPolicy(AnonymousPolicy{Enabled: true, MaxHosts: 2, MaxStreams: 2, DailyBytes: 0})
	ctrl.SetCapabilityTTL(48 * time.Hour)
	srv := NewServer(ctrl, "test-admin-token", "admin", "pw")
	return srv, ctrl, st
}

func adminCall(t *testing.T, srv *Server, method, path, body string) map[string]any {
	t.Helper()
	req := httptest.NewRequest(method, path, strings.NewReader(body))
	req.Header.Set("Authorization", "Bearer test-admin-token")
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	var out map[string]any
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	if rec.Code != http.StatusOK {
		t.Fatalf("%s %s -> %d: %s", method, path, rec.Code, rec.Body.String())
	}
	return out
}

// Devices are listed, disabled (cascading revoke), re-enabled and deleted via
// the admin API; the anonymous kill switch round-trips through settings.
func TestDevicesAndAnonymousSwitchAPI(t *testing.T) {
	srv, ctrl, st := newAdminTestServer(t)
	defer st.Close()
	ctrl.SetAnonymousEnabled(true)

	hostPub, hostPriv, _ := ed25519.GenerateKey(rand.Reader)
	nonce, _ := cryptoutil.GenerateNonce()
	challenge, _ := cryptoutil.RandomBytes(32)
	ts := time.Now().Unix()
	proof := ed25519.Sign(hostPriv, cryptoutil.BuildBootstrapTranscript(hostPub, ts, nonce, challenge))
	token, err := ctrl.Bootstrap(hostPub, ts, nonce, challenge, proof)
	if err != nil {
		t.Fatal(err)
	}
	nonce2, _ := cryptoutil.GenerateNonce()
	enrollProof := ed25519.Sign(hostPriv, cryptoutil.BuildEnrollTranscript(token, "h-a", hostPub, ts, nonce2, challenge))
	res, err := ctrl.Enroll(&EnrollRequest{InviteCode: token, HostId: "h-a", HostPublicKey: hostPub, Ts: ts, Nonce: nonce2, Proof: enrollProof, Challenge: challenge})
	if err != nil {
		t.Fatal(err)
	}

	list := adminCall(t, srv, "GET", "/v1/devices", "")
	devs, ok := list["devices"].([]any)
	if !ok || len(devs) != 1 {
		t.Fatalf("devices: %v", list)
	}
	dev := devs[0].(map[string]any)
	id := dev["id"].(string)
	if dev["enabled"] != true || dev["hostCount"].(float64) != 1 {
		t.Fatalf("device info: %v", dev)
	}

	adminCall(t, srv, "POST", "/v1/devices/"+id+"/disable", `{}`)
	h, err := st.GetHostByRoute(res.RouteId)
	if err != nil || h.RevokedAt == nil {
		t.Fatalf("host not revoked after device disable (err %v)", err)
	}
	if _, _, _, _, revoked, _ := ctrl.LookupRouteStatus(res.RouteId); !revoked {
		t.Fatal("route live after device disable")
	}

	adminCall(t, srv, "POST", "/v1/devices/"+id+"/enable", `{}`)
	d, err := st.GetDevice(id)
	if err != nil || !d.Enabled {
		t.Fatalf("device not re-enabled: %v", err)
	}

	// kill switch
	sw := adminCall(t, srv, "GET", "/v1/settings/anonymous", "")
	if sw["anonymousEnroll"] != true {
		t.Fatalf("switch read: %v", sw)
	}
	sw = adminCall(t, srv, "POST", "/v1/settings/anonymous", `{"enabled":false}`)
	if sw["anonymousEnroll"] != false || ctrl.AnonymousEnabled() {
		t.Fatalf("switch not off: %v", sw)
	}
	// persisted
	if v, _ := st.GetSetting(SettingAnonymousEnroll); v != "0" {
		t.Fatalf("persisted switch = %q", v)
	}

	adminCall(t, srv, "POST", "/v1/devices/"+id+"/delete", `{}`)
	if _, err := st.GetDevice(id); err == nil {
		t.Fatal("device still present after delete")
	}
}
