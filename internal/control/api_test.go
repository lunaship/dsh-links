package control

import (
	"encoding/json"
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

func TestBearerLoginCanIssueSessionWithoutPassword(t *testing.T) {
	token := "legacy-token-0123456789"
	server := NewServer(nil, token, "admin", "")
	req := httptest.NewRequest(http.MethodPost, "/login", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	recorder := httptest.NewRecorder()
	server.Handler().ServeHTTP(recorder, req)
	if recorder.Code != http.StatusOK || len(recorder.Result().Cookies()) != 1 {
		t.Fatalf("bearer login status=%d cookies=%d", recorder.Code, len(recorder.Result().Cookies()))
	}
}
