package main

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/lunaship/dsh-links/relay/internal/config"
)

func TestRewriteListenAndAdminBaseURL(t *testing.T) {
	if got := rewriteListen("0.0.0.0:8080"); got != "127.0.0.1:8080" {
		t.Fatalf("rewriteListen=%q", got)
	}
	httpCfg := &config.Config{AdminListen: "127.0.0.1:8080"}
	if got := adminBaseURL(httpCfg); got != "http://127.0.0.1:8080" {
		t.Fatalf("http base=%q", got)
	}
	tlsCfg := &config.Config{AdminListen: "0.0.0.0:8080", AdminTLSCert: "/tmp/admin.crt"}
	if got := adminBaseURL(tlsCfg); got != "https://127.0.0.1:8080" {
		t.Fatalf("tls base=%q", got)
	}
}

func TestTenantClientCreateListEnable(t *testing.T) {
	disabled := false
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/tenants", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		switch r.Method {
		case http.MethodPost:
			raw, _ := io.ReadAll(r.Body)
			if !strings.Contains(string(raw), `"loginName":"alice"`) {
				t.Fatalf("create body=%s", raw)
			}
			json.NewEncoder(w).Encode(map[string]any{"ok": true, "id": "user-1", "loginName": "alice"})
		case http.MethodGet:
			row := map[string]any{"id": "user-1", "loginName": "alice", "displayName": "Alice", "liveHosts": 0, "maxLiveHosts": 8, "unusedInvites": 0, "maxUnusedInvites": 4}
			if disabled {
				row["disabledAt"] = 1
			}
			json.NewEncoder(w).Encode([]map[string]any{row})
		default:
			http.Error(w, "method", http.StatusMethodNotAllowed)
		}
	})
	mux.HandleFunc("/v1/tenants/user-1/disable", func(w http.ResponseWriter, r *http.Request) {
		disabled = true
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})
	mux.HandleFunc("/v1/tenants/user-1/enable", func(w http.ResponseWriter, r *http.Request) {
		disabled = false
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	c := &tenantClient{base: srv.URL, token: "tok", http: srv.Client()}
	if err := c.create("alice", "Alice", "twelve-chars-min"); err != nil {
		t.Fatal(err)
	}
	if err := c.list(); err != nil {
		t.Fatal(err)
	}
	if err := c.setDisabled("alice", true); err != nil {
		t.Fatal(err)
	}
	if !disabled {
		t.Fatal("disable not applied")
	}
	if err := c.setDisabled("alice", false); err != nil {
		t.Fatal(err)
	}
	if disabled {
		t.Fatal("enable not applied")
	}
}

func TestTenantListStatusAndSort(t *testing.T) {
	if got := tenantListStatus(map[string]any{"hostFull": true}); got != "host-full" {
		t.Fatalf("flag hostFull status=%s", got)
	}
	if got := tenantListStatus(map[string]any{"liveHosts": 8.0, "maxLiveHosts": 8.0}); got != "host-full" {
		t.Fatalf("count host-full status=%s", got)
	}
	if got := tenantListStatus(map[string]any{"inviteFull": true}); got != "invite-full" {
		t.Fatalf("flag inviteFull status=%s", got)
	}
	if got := tenantListStatus(map[string]any{"disabledAt": float64(1), "hostFull": true}); got != "disabled" {
		t.Fatalf("disabled wins over host-full: %s", got)
	}
	if got := tenantListStatus(map[string]any{"passwordMustChange": true, "inviteFull": true}); got != "password-change" {
		t.Fatalf("password-change wins over invite-full: %s", got)
	}
	rows := []map[string]any{
		{"loginName": "zoe", "liveHosts": 0.0, "maxLiveHosts": 8.0},
		{"loginName": "alice", "hostFull": true},
		{"loginName": "bob", "disabledAt": float64(1)},
		{"loginName": "cara", "inviteFull": true},
	}
	sortTenantRows(rows)
	want := []string{"alice", "cara", "zoe", "bob"}
	for i, name := range want {
		if str(rows[i]["loginName"]) != name {
			t.Fatalf("sort[%d]=%v, want %s", i, rows[i]["loginName"], name)
		}
	}
}

func TestFilterHostRowsAndFormat(t *testing.T) {
	rows := []map[string]any{
		{"id": "h-bob", "hostName": "bob-box", "loginName": "bob", "online": true},
		{"id": "h-alice-rev", "hostName": "gone", "loginName": "alice", "revokedAt": float64(1), "online": false, "routeSecret": "nope"},
		{"id": "h-alice-off", "hostName": "old", "loginName": "alice", "online": false},
		{"id": "h-admin", "hostName": "ops", "userId": "user-default", "online": true},
	}
	alice := filterHostRows(rows, "alice", false)
	if len(alice) != 1 || str(alice[0]["id"]) != "h-alice-off" {
		t.Fatalf("occupying alice=%v", alice)
	}
	if got := formatHostRow(alice[0]); got != "alice\told\th-alice-off\toffline\toccupying" {
		t.Fatalf("format occupying=%q", got)
	}
	aliceAll := filterHostRows(rows, "alice", true)
	if len(aliceAll) != 2 || str(aliceAll[0]["id"]) != "h-alice-off" || str(aliceAll[1]["id"]) != "h-alice-rev" {
		t.Fatalf("alice --all=%v", aliceAll)
	}
	revoked := formatHostRow(aliceAll[1])
	if revoked != "alice\tgone\th-alice-rev\trevoked\trevoked" {
		t.Fatalf("format revoked=%q", revoked)
	}
	if strings.Contains(revoked, "nope") {
		t.Fatal("host list must not print route secrets")
	}
	admin := filterHostRows(rows, "admin", false)
	if len(admin) != 1 || str(admin[0]["id"]) != "h-admin" {
		t.Fatalf("admin hosts=%v", admin)
	}
	all := filterHostRows(rows, "", false)
	if len(all) != 3 {
		t.Fatalf("all occupying=%d, want 3", len(all))
	}
	if hostLoginName(all[0]) != "admin" || hostLoginName(all[1]) != "alice" || hostLoginName(all[2]) != "bob" {
		t.Fatalf("sort logins=%s %s %s", hostLoginName(all[0]), hostLoginName(all[1]), hostLoginName(all[2]))
	}
	if n := filterHostRows(rows, "eve", false); len(n) != 0 {
		t.Fatalf("unknown tenant=%v", n)
	}
}

func TestParseTenantFlagsAll(t *testing.T) {
	f := parseTenantFlags([]string{"--login", "alice", "--all"})
	if f.login != "alice" || !f.all {
		t.Fatalf("%+v", f)
	}
}

func TestTenantClientListHosts(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/v1/hosts", func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer tok" {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		json.NewEncoder(w).Encode([]map[string]any{
			{"id": "h1", "hostName": "studio", "loginName": "alice", "online": false},
			{"id": "h2", "hostName": "gone", "loginName": "alice", "revokedAt": 1},
		})
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	c := &tenantClient{base: srv.URL, token: "tok", http: srv.Client()}
	if err := c.listHosts("alice", false); err != nil {
		t.Fatal(err)
	}
	if err := c.listHosts("eve", false); err != nil {
		t.Fatal(err)
	}
}

func TestTenantControlHandoff(t *testing.T) {
	withURL := tenantControlHandoff("https://control.example.com")
	if !strings.Contains(withURL, "https://control.example.com") || !strings.Contains(withURL, "not the Android App") {
		t.Fatalf("handoff with URL: %s", withURL)
	}
	if strings.Contains(withURL, "127.0.0.1") {
		t.Fatalf("configured handoff still mentions loopback: %s", withURL)
	}
	empty := tenantControlHandoff("")
	if !strings.Contains(empty, "Do not send 127.0.0.1") || !strings.Contains(empty, "public_control_url") {
		t.Fatalf("empty handoff: %s", empty)
	}
}
