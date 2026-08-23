package control

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
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
		{path: "/app.css", contentType: "text/css", marker: "macrostructure: Workbench"},
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
	if got := uiRecorder.Header().Get("Cache-Control"); got == "no-store" {
		t.Fatal("static UI assets must remain cacheable")
	}
}
