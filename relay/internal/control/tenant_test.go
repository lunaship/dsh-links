package control

import (
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
	"github.com/lunaship/dsh-links/relay/internal/store"
)

const (
	testAdminToken  = "legacy-token-0123456789"
	testAdminPass   = "correct horse battery staple"
	tenantPass      = "twelve-chars-min"
	tenantReadyPass = "twelve-chars-own"
)

func TestAdminLoginUnchanged(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()

	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"`+testAdminPass+`"}`))
	req.Header.Set("Content-Type", "application/json")
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("admin login status=%d body=%s", rec.Code, rec.Body.String())
	}
	var body map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
		t.Fatal(err)
	}
	if body["ok"] != true || body["role"] != "admin" {
		t.Fatalf("login body=%v", body)
	}
}

func TestBearerTokenRemainsAdmin(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	alice, err := ctrl.CreateTenant("alice", "Alice", tenantPass, "admin")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.CreateInviteFor(alice.ID, time.Hour); err != nil {
		t.Fatal(err)
	}
	if _, err := ctrl.CreateInvite(time.Hour); err != nil {
		t.Fatal(err)
	}

	srv := NewServer(ctrl, testAdminToken, "admin", testAdminPass)
	req := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	req.Header.Set("Authorization", "Bearer "+testAdminToken)
	rec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("bearer invites status=%d body=%s", rec.Code, rec.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	if len(list) < 2 {
		t.Fatalf("admin bearer should see every invite, got %d", len(list))
	}

	ov := httptest.NewRequest(http.MethodGet, "/v1/overview", nil)
	ov.Header.Set("Authorization", "Bearer "+testAdminToken)
	ovRec := httptest.NewRecorder()
	srv.Handler().ServeHTTP(ovRec, ov)
	var overview map[string]any
	if err := json.Unmarshal(ovRec.Body.Bytes(), &overview); err != nil {
		t.Fatal(err)
	}
	if overview["role"] != "admin" {
		t.Fatalf("bearer overview role=%v", overview["role"])
	}
	if overview["quota"] != nil {
		t.Fatal("admin overview should not include tenant quota")
	}
	if overview["tenantLimits"] == nil {
		t.Fatal("admin overview missing tenantLimits")
	}
	if overview["publicControlURL"] != nil {
		t.Fatal("admin overview leaked publicControlURL when unset")
	}
}

func TestPublicControlURLIsAdminOnlyHandoff(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	srv := NewServer(ctrl, testAdminToken, "admin", testAdminPass)
	srv.SetPublicControlURL("https://control.example.com/panel")
	handler := srv.Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	alice := mustReadyLogin(t, handler, "alice")
	admin := mustLogin(t, handler, "admin", testAdminPass)

	adminOV := mustOverview(t, handler, admin)
	if adminOV["publicControlURL"] != "https://control.example.com/panel" {
		t.Fatalf("admin publicControlURL=%v", adminOV["publicControlURL"])
	}
	tenantOV := mustOverview(t, handler, alice)
	if tenantOV["publicControlURL"] != nil {
		t.Fatalf("tenant overview leaked publicControlURL=%v", tenantOV["publicControlURL"])
	}

	srv.SetEnrollMeta("relay.example.com", "8444", false)
	req := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(alice)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("tenant mint status=%d body=%s", rec.Code, rec.Body.String())
	}
	var minted map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &minted); err != nil {
		t.Fatal(err)
	}
	enroll, _ := minted["enroll"].(string)
	if minted["controlUrl"] != "https://control.example.com/panel" {
		t.Fatalf("tenant mint controlUrl=%v", minted["controlUrl"])
	}
	if !strings.Contains(enroll, "c=") || !strings.Contains(enroll, "control.example.com") {
		t.Fatalf("tenant enroll missing control URL: %s", enroll)
	}
	if strings.Contains(enroll, "127.0.0.1") {
		t.Fatalf("enroll leaked loopback: %s", enroll)
	}
}

func TestTenantQuotaVisibleOnOverviewAndLedger(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.SetTenantLimits(8, 4)
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	alice := mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")
	admin := mustLogin(t, handler, "admin", testAdminPass)

	tenantOV := mustOverview(t, handler, cookie)
	if tenantOV["role"] != "tenant" {
		t.Fatalf("tenant overview role=%v", tenantOV["role"])
	}
	if tenantOV["tenantLimits"] != nil {
		t.Fatal("tenant overview leaked tenantLimits")
	}
	quota, _ := tenantOV["quota"].(map[string]any)
	if quota == nil {
		t.Fatal("tenant overview missing quota")
	}
	if asInt(quota["unusedInvites"]) != 0 || asInt(quota["maxUnusedInvites"]) != 4 {
		t.Fatalf("unused quota=%v", quota)
	}
	if asInt(quota["liveHosts"]) != 0 || asInt(quota["maxLiveHosts"]) != 8 {
		t.Fatalf("host quota=%v", quota)
	}
	if quota["hostFull"] != false {
		t.Fatalf("empty ledger hostFull=%v, want false", quota["hostFull"])
	}
	if quota["inviteFull"] != false {
		t.Fatalf("empty ledger inviteFull=%v, want false", quota["inviteFull"])
	}

	adminOV := mustOverview(t, handler, admin)
	if adminOV["quota"] != nil {
		t.Fatal("admin overview should not include tenant quota")
	}
	limits, _ := adminOV["tenantLimits"].(map[string]any)
	if limits == nil || asInt(limits["maxLiveHosts"]) != 8 || asInt(limits["maxUnusedInvites"]) != 4 {
		t.Fatalf("admin tenantLimits=%v", adminOV["tenantLimits"])
	}

	mustCreateInvite(t, handler, cookie)
	tenantOV = mustOverview(t, handler, cookie)
	quota, _ = tenantOV["quota"].(map[string]any)
	if asInt(quota["unusedInvites"]) != 1 {
		t.Fatalf("unused after mint=%v", quota["unusedInvites"])
	}

	req := httptest.NewRequest(http.MethodGet, "/v1/tenants", nil)
	req.AddCookie(admin)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("list tenants status=%d body=%s", rec.Code, rec.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	var row map[string]any
	for _, item := range list {
		if item["id"] == alice.ID {
			row = item
			break
		}
	}
	if row == nil {
		t.Fatalf("alice missing from tenant ledger: %v", list)
	}
	if asInt(row["unusedInvites"]) != 1 || asInt(row["liveHosts"]) != 0 {
		t.Fatalf("ledger usage=%v", row)
	}
	if asInt(row["maxLiveHosts"]) != 8 || asInt(row["maxUnusedInvites"]) != 4 {
		t.Fatalf("ledger limits=%v", row)
	}
	if row["hostFull"] == true || row["inviteFull"] == true {
		t.Fatalf("empty occupying tenant marked full: %v", row)
	}
}

func TestControlEventsScopedAndOmitSecrets(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")
	aliceCookie := mustReadyLogin(t, handler, "alice")
	bobCookie := mustReadyLogin(t, handler, "bob-user")
	adminCookie := mustLogin(t, handler, "admin", testAdminPass)

	aliceCode := mustCreateInvite(t, handler, aliceCookie)
	bobCode := mustCreateInvite(t, handler, bobCookie)

	unauth := httptest.NewRequest(http.MethodGet, "/v1/events", nil)
	unauthRec := httptest.NewRecorder()
	handler.ServeHTTP(unauthRec, unauth)
	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("unauth events status=%d, want 401", unauthRec.Code)
	}

	aliceEvents, aliceRaw := mustListEvents(t, handler, aliceCookie)
	if eventCount(aliceEvents, store.ControlEventInviteCreate) != 1 {
		t.Fatalf("alice invite.create events=%v", aliceEvents)
	}
	if aliceEvents[0]["subjectUserId"] != nil {
		t.Fatal("tenant events leaked subjectUserId")
	}
	if !eventHasActor(aliceEvents, store.ControlEventInviteCreate, "alice") {
		t.Fatalf("alice actor missing: %v", aliceEvents)
	}
	if strings.Contains(aliceRaw, aliceCode) || strings.Contains(aliceRaw, bobCode) {
		t.Fatalf("event list contained invite code: %s", aliceRaw)
	}

	bobEvents, _ := mustListEvents(t, handler, bobCookie)
	if eventCount(bobEvents, store.ControlEventInviteCreate) != 1 {
		t.Fatalf("bob invite.create events=%v", bobEvents)
	}

	aliceInvites := mustListInvites(t, handler, aliceCookie)
	foreign := httptest.NewRequest(http.MethodPost, "/v1/invites/"+aliceInvites[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	foreign.Header.Set("Content-Type", "application/json")
	foreign.AddCookie(bobCookie)
	foreignRec := httptest.NewRecorder()
	handler.ServeHTTP(foreignRec, foreign)
	if foreignRec.Code != http.StatusNotFound {
		t.Fatalf("cross-tenant revoke status=%d, want 404", foreignRec.Code)
	}
	bobAfter, _ := mustListEvents(t, handler, bobCookie)
	if eventCount(bobAfter, store.ControlEventInviteRevoke) != 0 {
		t.Fatalf("failed cross-tenant revoke wrote an event: %v", bobAfter)
	}

	_, priv, _ := ed25519.GenerateKey(nil)
	req := enrollRequest(t, aliceCode, "host-alice-evt", priv)
	req.HostName = "办公 Mac"
	if _, err := ctrl.Enroll(req); err != nil {
		t.Fatal(err)
	}
	aliceEvents, aliceRaw = mustListEvents(t, handler, aliceCookie)
	if !strings.Contains(aliceRaw, store.ControlEventHostEnroll) {
		t.Fatalf("alice missing host.enroll: %s", aliceRaw)
	}
	if !strings.Contains(aliceRaw, `"detail":"办公 Mac"`) {
		t.Fatalf("alice enroll missing hostName detail: %s", aliceRaw)
	}
	aliceInvitesAfter := mustListInvites(t, handler, aliceCookie)
	if len(aliceInvitesAfter) != 1 || aliceInvitesAfter[0]["consumedHostName"] != "办公 Mac" || aliceInvitesAfter[0]["consumedHostId"] != "host-alice-evt" || aliceInvitesAfter[0]["consumedHostLive"] != true {
		t.Fatalf("alice invite missing consumed host: %v", aliceInvitesAfter)
	}
	if strings.Contains(aliceRaw, aliceCode) {
		t.Fatalf("enroll event leaked invite code: %s", aliceRaw)
	}
	bobEvents, _ = mustListEvents(t, handler, bobCookie)
	for _, ev := range bobEvents {
		if ev["action"] == store.ControlEventHostEnroll {
			t.Fatalf("bob saw alice enroll: %v", ev)
		}
	}

	hosts := mustListHosts(t, handler, aliceCookie)
	if len(hosts) != 1 {
		t.Fatalf("alice hosts=%d", len(hosts))
	}
	rev := httptest.NewRequest(http.MethodPost, "/v1/hosts/"+hosts[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	rev.Header.Set("Content-Type", "application/json")
	rev.AddCookie(aliceCookie)
	revRec := httptest.NewRecorder()
	handler.ServeHTTP(revRec, rev)
	if revRec.Code != http.StatusOK {
		t.Fatalf("revoke host status=%d body=%s", revRec.Code, revRec.Body.String())
	}
	aliceInvitesRevoked := mustListInvites(t, handler, aliceCookie)
	if len(aliceInvitesRevoked) != 1 || aliceInvitesRevoked[0]["consumedHostName"] != "办公 Mac" || aliceInvitesRevoked[0]["consumedHostLive"] == true {
		t.Fatalf("revoked host still live on invite: %v", aliceInvitesRevoked)
	}
	aliceEvents, _ = mustListEvents(t, handler, aliceCookie)
	foundRevoke := false
	for _, ev := range aliceEvents {
		if ev["action"] == store.ControlEventHostRevoke {
			foundRevoke = true
			if ev["detail"] != "办公 Mac" {
				t.Fatalf("revoke detail=%v, want 办公 Mac", ev["detail"])
			}
		}
	}
	if !foundRevoke {
		t.Fatalf("alice missing host.revoke: %v", aliceEvents)
	}

	adminEvents, adminRaw := mustListEvents(t, handler, adminCookie)
	if len(adminEvents) < 4 {
		t.Fatalf("admin events=%d body=%s", len(adminEvents), adminRaw)
	}
	if !strings.Contains(adminRaw, `"subjectUserId"`) {
		t.Fatal("admin events missing subjectUserId")
	}
	if strings.Contains(adminRaw, aliceCode) || strings.Contains(adminRaw, bobCode) {
		t.Fatalf("admin events contained invite code: %s", adminRaw)
	}
}

func TestAuthEventsRecordLoginAndFailures(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	alice := mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")
	aliceCookie := mustReadyLogin(t, handler, "alice")
	bobCookie := mustReadyLogin(t, handler, "bob-user")
	adminCookie := mustLogin(t, handler, "admin", testAdminPass)

	aliceEvents, aliceRaw := mustListEvents(t, handler, aliceCookie)
	if eventCount(aliceEvents, store.ControlEventAuthLogin) < 1 {
		t.Fatalf("alice missing auth.login: %v", aliceEvents)
	}
	if strings.Contains(aliceRaw, `"detail"`) || strings.Contains(aliceRaw, "ip=") {
		t.Fatalf("tenant events leaked login detail: %s", aliceRaw)
	}
	bobEvents, _ := mustListEvents(t, handler, bobCookie)
	if eventCount(bobEvents, store.ControlEventAuthLogin) < 1 {
		t.Fatalf("bob missing own auth.login: %v", bobEvents)
	}
	for _, ev := range bobEvents {
		if ev["action"] == store.ControlEventAuthLogin && ev["actorLogin"] == "alice" {
			t.Fatalf("bob saw alice login: %v", ev)
		}
	}

	const failPass = "hunter2-not-stored-xx"
	fail := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"alice","password":"`+failPass+`"}`))
	fail.Header.Set("Content-Type", "application/json")
	fail.RemoteAddr = "203.0.113.50:54321"
	failRec := httptest.NewRecorder()
	handler.ServeHTTP(failRec, fail)
	if failRec.Code != http.StatusUnauthorized {
		t.Fatalf("alice fail status=%d", failRec.Code)
	}
	aliceAfter, aliceAfterRaw := mustListEvents(t, handler, aliceCookie)
	if eventCount(aliceAfter, store.ControlEventAuthLoginFail) != 1 {
		t.Fatalf("alice missing auth.login.fail: %v", aliceAfter)
	}
	if strings.Contains(aliceAfterRaw, failPass) || strings.Contains(failRec.Body.String(), failPass) {
		t.Fatal("failed login stored or echoed the password")
	}
	bobAfter, _ := mustListEvents(t, handler, bobCookie)
	if eventCount(bobAfter, store.ControlEventAuthLoginFail) != 0 {
		t.Fatalf("bob saw alice login fail: %v", bobAfter)
	}

	unknown := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"eve","password":"`+failPass+`"}`))
	unknown.Header.Set("Content-Type", "application/json")
	unknownRec := httptest.NewRecorder()
	handler.ServeHTTP(unknownRec, unknown)
	if unknownRec.Code != http.StatusUnauthorized {
		t.Fatalf("unknown login status=%d", unknownRec.Code)
	}
	adminEvents, adminRaw := mustListEvents(t, handler, adminCookie)
	if eventCount(adminEvents, store.ControlEventAuthLoginFail) < 1 {
		t.Fatalf("admin missing known-account fail: %v", adminEvents)
	}
	if strings.Contains(adminRaw, failPass) {
		t.Fatal("admin events contained password")
	}
	foundAliceFail := false
	foundEve := false
	foundAdminDetail := false
	for _, ev := range adminEvents {
		if ev["action"] == store.ControlEventAuthLoginFail && ev["actorLogin"] == "alice" {
			foundAliceFail = true
			if ev["subjectUserId"] != alice.ID {
				t.Fatalf("alice fail subject=%v", ev["subjectUserId"])
			}
			if ev["detail"] != "ip=203.0.113.50" {
				t.Fatalf("alice fail detail=%v", ev["detail"])
			}
			foundAdminDetail = true
		}
		if ev["actorLogin"] == "eve" {
			foundEve = true
		}
	}
	if !foundAliceFail || !foundAdminDetail {
		t.Fatalf("admin missing alice fail with ip: %s", adminRaw)
	}
	if foundEve {
		t.Fatal("unknown login name was written to the audit log")
	}

	adminFail := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"admin","password":"`+failPass+`"}`))
	adminFail.Header.Set("Content-Type", "application/json")
	adminFailRec := httptest.NewRecorder()
	handler.ServeHTTP(adminFailRec, adminFail)
	if adminFailRec.Code != http.StatusUnauthorized {
		t.Fatalf("admin fail status=%d", adminFailRec.Code)
	}
	aliceFinal, _ := mustListEvents(t, handler, aliceCookie)
	for _, ev := range aliceFinal {
		if ev["action"] == store.ControlEventAuthLoginFail && ev["actorLogin"] == "admin" {
			t.Fatalf("tenant saw admin login fail: %v", ev)
		}
	}
}

func TestTenantsCannotSeeEachOthersInvites(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()

	mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")

	aliceCookie := mustReadyLogin(t, handler, "alice")
	bobCookie := mustReadyLogin(t, handler, "bob-user")

	aliceInvite := mustCreateInvite(t, handler, aliceCookie)
	bobInvite := mustCreateInvite(t, handler, bobCookie)
	if aliceInvite == bobInvite {
		t.Fatal("tenants minted the same invite code")
	}

	aliceList := mustListInvites(t, handler, aliceCookie)
	if len(aliceList) != 1 {
		t.Fatalf("alice invites=%d, want 1", len(aliceList))
	}
	bobList := mustListInvites(t, handler, bobCookie)
	if len(bobList) != 1 {
		t.Fatalf("bob invites=%d, want 1", len(bobList))
	}
	if aliceList[0]["userId"] != nil {
		t.Fatal("tenant invite list leaked userId")
	}
	if aliceList[0]["id"] == bobList[0]["id"] {
		t.Fatal("tenants saw the same invite id")
	}

	// Cross-tenant revoke must look like a missing row.
	foreign := httptest.NewRequest(http.MethodPost, "/v1/invites/"+aliceList[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	foreign.Header.Set("Content-Type", "application/json")
	foreign.AddCookie(bobCookie)
	foreignRec := httptest.NewRecorder()
	handler.ServeHTTP(foreignRec, foreign)
	if foreignRec.Code != http.StatusNotFound {
		t.Fatalf("cross-tenant revoke status=%d, want 404", foreignRec.Code)
	}

	adminCookie := mustLogin(t, handler, "admin", testAdminPass)
	adminList := mustListInvites(t, handler, adminCookie)
	if len(adminList) != 2 {
		t.Fatalf("admin invites=%d, want 2", len(adminList))
	}
	seenOwner := false
	names := map[string]bool{}
	for _, row := range adminList {
		if row["userId"] == nil || row["userId"] == "" {
			t.Fatalf("admin invite missing userId: %v", row)
		}
		if login, _ := row["loginName"].(string); login != "" {
			names[login] = true
		}
		if row["userId"] != "user-default" {
			seenOwner = true
		}
	}
	if !seenOwner {
		t.Fatal("admin list should include a tenant-owned invite")
	}
	if !names["alice"] || !names["bob-user"] {
		t.Fatalf("admin list loginName=%v, want alice and bob-user", names)
	}
}

func TestTenantCannotToggleAnonymousOrListDevices(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")

	anon := httptest.NewRequest(http.MethodPost, "/v1/settings/anonymous", strings.NewReader(`{"enabled":true}`))
	anon.Header.Set("Content-Type", "application/json")
	anon.AddCookie(cookie)
	anonRec := httptest.NewRecorder()
	handler.ServeHTTP(anonRec, anon)
	if anonRec.Code != http.StatusForbidden {
		t.Fatalf("tenant anonymous toggle status=%d, want 403", anonRec.Code)
	}

	devs := httptest.NewRequest(http.MethodGet, "/v1/devices", nil)
	devs.AddCookie(cookie)
	devsRec := httptest.NewRecorder()
	handler.ServeHTTP(devsRec, devs)
	if devsRec.Code != http.StatusForbidden {
		t.Fatalf("tenant devices status=%d, want 403", devsRec.Code)
	}

	create := httptest.NewRequest(http.MethodPost, "/v1/tenants", strings.NewReader(`{"loginName":"eve","password":"twelve-chars-min"}`))
	create.Header.Set("Content-Type", "application/json")
	create.AddCookie(cookie)
	createRec := httptest.NewRecorder()
	handler.ServeHTTP(createRec, create)
	if createRec.Code != http.StatusForbidden {
		t.Fatalf("tenant create-tenant status=%d, want 403 (no public signup)", createRec.Code)
	}

	unauth := httptest.NewRequest(http.MethodPost, "/v1/tenants", strings.NewReader(`{"loginName":"eve","password":"twelve-chars-min"}`))
	unauth.Header.Set("Content-Type", "application/json")
	unauthRec := httptest.NewRecorder()
	handler.ServeHTTP(unauthRec, unauth)
	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous create-tenant status=%d, want 401", unauthRec.Code)
	}
}

func TestTenantHostIsolation(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")
	aliceCookie := mustReadyLogin(t, handler, "alice")
	bobCookie := mustReadyLogin(t, handler, "bob-user")

	code := mustCreateInvite(t, handler, aliceCookie)
	_, priv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, code, "host-alice", priv)); err != nil {
		t.Fatal(err)
	}

	aliceHosts := mustListHosts(t, handler, aliceCookie)
	if len(aliceHosts) != 1 {
		t.Fatalf("alice hosts=%d, want 1", len(aliceHosts))
	}
	bobHosts := mustListHosts(t, handler, bobCookie)
	if len(bobHosts) != 0 {
		t.Fatalf("bob hosts=%d, want 0", len(bobHosts))
	}

	rev := httptest.NewRequest(http.MethodPost, "/v1/hosts/"+aliceHosts[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	rev.Header.Set("Content-Type", "application/json")
	rev.AddCookie(bobCookie)
	revRec := httptest.NewRecorder()
	handler.ServeHTTP(revRec, rev)
	if revRec.Code != http.StatusNotFound {
		t.Fatalf("cross-tenant host revoke status=%d, want 404", revRec.Code)
	}
}

func TestDisableTenantKillsSessionInvitesAndHosts(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	alice := mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")
	aliceCookie := mustReadyLogin(t, handler, "alice")
	bobCookie := mustReadyLogin(t, handler, "bob-user")
	bobInvite := mustCreateInvite(t, handler, bobCookie)

	unused := mustCreateInvite(t, handler, aliceCookie)
	used := mustCreateInvite(t, handler, aliceCookie)
	_, priv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, used, "host-alice", priv)); err != nil {
		t.Fatal(err)
	}

	admin := mustLogin(t, handler, "admin", testAdminPass)
	dis := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/disable", strings.NewReader(`{}`))
	dis.Header.Set("Content-Type", "application/json")
	dis.AddCookie(admin)
	disRec := httptest.NewRecorder()
	handler.ServeHTTP(disRec, dis)
	if disRec.Code != http.StatusOK {
		t.Fatalf("disable status=%d body=%s", disRec.Code, disRec.Body.String())
	}

	again := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/disable", strings.NewReader(`{}`))
	again.Header.Set("Content-Type", "application/json")
	again.AddCookie(admin)
	againRec := httptest.NewRecorder()
	handler.ServeHTTP(againRec, again)
	if againRec.Code != http.StatusOK {
		t.Fatalf("idempotent disable status=%d body=%s", againRec.Code, againRec.Body.String())
	}

	stale := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	stale.AddCookie(aliceCookie)
	staleRec := httptest.NewRecorder()
	handler.ServeHTTP(staleRec, stale)
	if staleRec.Code != http.StatusUnauthorized {
		t.Fatalf("disabled session status=%d, want 401", staleRec.Code)
	}

	login := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"alice","password":"`+tenantPass+`"}`))
	login.Header.Set("Content-Type", "application/json")
	loginRec := httptest.NewRecorder()
	handler.ServeHTTP(loginRec, login)
	if loginRec.Code != http.StatusUnauthorized {
		t.Fatalf("disabled login status=%d, want 401", loginRec.Code)
	}

	_, unusedPriv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, unused, "host-unused", unusedPriv)); err == nil {
		t.Fatal("unused invite still enrolled after tenant disable")
	}

	hosts, err := ctrl.ListHostsFor(alice.ID, false)
	if err != nil || len(hosts) != 1 || hosts[0].RevokedAt == nil {
		t.Fatalf("alice host should be revoked: %+v err=%v", hosts, err)
	}

	bobList := mustListInvites(t, handler, bobCookie)
	if len(bobList) != 1 {
		t.Fatalf("bob invites=%d after alice disable", len(bobList))
	}
	_, bobPriv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, bobInvite, "host-bob", bobPriv)); err != nil {
		t.Fatalf("bob invite should still enroll: %v", err)
	}

	en := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/enable", strings.NewReader(`{}`))
	en.Header.Set("Content-Type", "application/json")
	en.AddCookie(admin)
	enRec := httptest.NewRecorder()
	handler.ServeHTTP(enRec, en)
	if enRec.Code != http.StatusOK {
		t.Fatalf("enable status=%d body=%s", enRec.Code, enRec.Body.String())
	}
	againEn := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/enable", strings.NewReader(`{}`))
	againEn.Header.Set("Content-Type", "application/json")
	againEn.AddCookie(admin)
	againEnRec := httptest.NewRecorder()
	handler.ServeHTTP(againEnRec, againEn)
	if againEnRec.Code != http.StatusOK {
		t.Fatalf("idempotent enable status=%d", againEnRec.Code)
	}

	restored := mustLogin(t, handler, "alice", tenantReadyPass)
	fresh := mustCreateInvite(t, handler, restored)
	if fresh == "" {
		t.Fatal("enabled tenant should mint a new invite")
	}
	hosts, err = ctrl.ListHostsFor(alice.ID, false)
	if err != nil || len(hosts) != 1 || hosts[0].RevokedAt == nil {
		t.Fatalf("enable must not revive revoked hosts: %+v err=%v", hosts, err)
	}
}

func TestTenantQuotasDoNotCapAdmin(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.SetTenantLimits(1, 1)
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")
	code := mustCreateInvite(t, handler, cookie)

	blocked := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	blocked.Header.Set("Content-Type", "application/json")
	blocked.AddCookie(cookie)
	blockedRec := httptest.NewRecorder()
	handler.ServeHTTP(blockedRec, blocked)
	if blockedRec.Code != http.StatusConflict {
		t.Fatalf("tenant unused-invite cap status=%d body=%s, want 409", blockedRec.Code, blockedRec.Body.String())
	}
	if !strings.Contains(blockedRec.Body.String(), "invite limit") {
		t.Fatalf("409 body=%s, want invite limit", blockedRec.Body.String())
	}
	admin := mustLogin(t, handler, "admin", testAdminPass)
	ledgers := httptest.NewRequest(http.MethodGet, "/v1/tenants", nil)
	ledgers.AddCookie(admin)
	ledgersRec := httptest.NewRecorder()
	handler.ServeHTTP(ledgersRec, ledgers)
	if ledgersRec.Code != http.StatusOK {
		t.Fatalf("tenant ledger status=%d body=%s", ledgersRec.Code, ledgersRec.Body.String())
	}
	var ledger []map[string]any
	if err := json.Unmarshal(ledgersRec.Body.Bytes(), &ledger); err != nil {
		t.Fatal(err)
	}
	var aliceRow map[string]any
	for _, item := range ledger {
		if item["loginName"] == "alice" {
			aliceRow = item
			break
		}
	}
	if aliceRow == nil || aliceRow["inviteFull"] != true {
		t.Fatalf("admin ledger missing inviteFull at unused-invite cap: %v", ledger)
	}

	replace := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{"replaceOldestUnused":true}`))
	replace.Header.Set("Content-Type", "application/json")
	replace.AddCookie(cookie)
	replaceRec := httptest.NewRecorder()
	handler.ServeHTTP(replaceRec, replace)
	if replaceRec.Code != http.StatusOK {
		t.Fatalf("replace-oldest mint status=%d body=%s", replaceRec.Code, replaceRec.Body.String())
	}
	var replacedOut map[string]any
	if err := json.Unmarshal(replaceRec.Body.Bytes(), &replacedOut); err != nil {
		t.Fatal(err)
	}
	if replacedOut["replacedInviteId"] == nil || replacedOut["inviteCode"] == "" {
		t.Fatalf("replace mint body=%s, want new code and replacedInviteId", replaceRec.Body.String())
	}
	quota := mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["unusedInvites"]) != 1 || quota["inviteFull"] != true {
		t.Fatalf("after replace quota=%v", quota)
	}
	events, _ := mustListEvents(t, handler, cookie)
	if eventCount(events, store.ControlEventInviteCreate) != 2 {
		t.Fatalf("after replace invite.create events=%v", events)
	}
	if eventCount(events, store.ControlEventInviteRevoke) != 1 {
		t.Fatalf("after replace invite.revoke events=%v", events)
	}
	foundReplace := false
	for _, ev := range events {
		if ev["action"] == store.ControlEventInviteRevoke && ev["detail"] == "签发新码时作废最早未用码" {
			foundReplace = true
			break
		}
	}
	if !foundReplace {
		t.Fatalf("replace revoke missing detail: %v", events)
	}
	_, priv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, code, "host-stale", priv)); err == nil {
		t.Fatal("replaced unused invite must not enroll")
	}

	for i := 0; i < 3; i++ {
		mustCreateInvite(t, handler, admin)
	}

	newCode, _ := replacedOut["inviteCode"].(string)
	if _, err := ctrl.Enroll(enrollRequest(t, newCode, "host-a", priv)); err != nil {
		t.Fatal(err)
	}
	next := mustCreateInvite(t, handler, cookie)
	_, priv2, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, next, "host-b", priv2)); !errors.Is(err, store.ErrTenantHostLimit) {
		t.Fatalf("second tenant host: %v", err)
	}
}

func TestTenantSameHostReenrollDoesNotConsumeSlot(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.SetTenantLimits(1, 4)
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")
	code := mustCreateInvite(t, handler, cookie)
	_, priv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, code, "host-a", priv)); err != nil {
		t.Fatal(err)
	}
	quota := mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 {
		t.Fatalf("after enroll liveHosts=%v", quota["liveHosts"])
	}
	if quota["hostFull"] != true {
		t.Fatalf("at cap hostFull=%v, want true", quota["hostFull"])
	}
	admin := mustLogin(t, handler, "admin", testAdminPass)
	ledgers := httptest.NewRequest(http.MethodGet, "/v1/tenants", nil)
	ledgers.AddCookie(admin)
	ledgersRec := httptest.NewRecorder()
	handler.ServeHTTP(ledgersRec, ledgers)
	if ledgersRec.Code != http.StatusOK {
		t.Fatalf("tenant ledger status=%d body=%s", ledgersRec.Code, ledgersRec.Body.String())
	}
	var ledger []map[string]any
	if err := json.Unmarshal(ledgersRec.Body.Bytes(), &ledger); err != nil {
		t.Fatal(err)
	}
	var aliceRow map[string]any
	for _, item := range ledger {
		if item["loginName"] == "alice" {
			aliceRow = item
			break
		}
	}
	if aliceRow == nil || aliceRow["hostFull"] != true {
		t.Fatalf("admin ledger missing hostFull at cap: %v", ledger)
	}
	adminHosts := mustListHosts(t, handler, admin)
	if len(adminHosts) != 1 || adminHosts[0]["loginName"] != "alice" {
		t.Fatalf("admin host list for tenant hosts CLI: %v", adminHosts)
	}
	if adminHosts[0]["hostName"] == "" {
		t.Fatal("admin host list missing hostName")
	}
	if mustListHosts(t, handler, cookie)[0]["loginName"] != nil {
		t.Fatal("tenant host list leaked loginName")
	}
	next := mustCreateInvite(t, handler, cookie)
	if _, err := ctrl.Enroll(enrollRequest(t, next, "host-a", priv)); err != nil {
		t.Fatalf("same-host re-enroll: %v", err)
	}
	quota = mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 {
		t.Fatalf("rebind liveHosts=%v, want 1", quota["liveHosts"])
	}
	if quota["hostFull"] != true {
		t.Fatalf("rebind hostFull=%v, want true", quota["hostFull"])
	}
	extra := mustCreateInvite(t, handler, cookie)
	_, priv2, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, extra, "host-b", priv2)); !errors.Is(err, store.ErrTenantHostLimit) {
		t.Fatalf("second host after rebind: %v", err)
	}
}

func TestQuotaExceededDoesNotConsumeInvite(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.SetTenantLimits(1, 4)
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")
	first := mustCreateInvite(t, handler, cookie)
	_, priv, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, first, "host-a", priv)); err != nil {
		t.Fatal(err)
	}
	blocked := mustCreateInvite(t, handler, cookie)
	_, priv2, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, blocked, "host-b", priv2)); !errors.Is(err, store.ErrTenantHostLimit) {
		t.Fatalf("quota: %v", err)
	}
	quota := mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 || asInt(quota["unusedInvites"]) != 1 {
		t.Fatalf("quota-exceeded must keep unused invite: %v", quota)
	}
	var unused map[string]any
	for _, row := range mustListInvites(t, handler, cookie) {
		if row["consumedAt"] == nil && row["revokedAt"] == nil {
			unused = row
			break
		}
	}
	if unused == nil {
		t.Fatal("blocked invite missing from unused list")
	}
	rev := httptest.NewRequest(http.MethodPost, "/v1/hosts/host-a/revoke", strings.NewReader(`{}`))
	rev.Header.Set("Content-Type", "application/json")
	rev.AddCookie(cookie)
	revRec := httptest.NewRecorder()
	handler.ServeHTTP(revRec, rev)
	if revRec.Code != http.StatusOK {
		t.Fatalf("revoke host-a status=%d body=%s", revRec.Code, revRec.Body.String())
	}
	if _, err := ctrl.Enroll(enrollRequest(t, blocked, "host-b", priv2)); err != nil {
		t.Fatalf("retry same invite after revoke: %v", err)
	}
	quota = mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 || asInt(quota["unusedInvites"]) != 0 {
		t.Fatalf("after retry quota=%v", quota)
	}
}

func TestTenantRevokeSelfFreesQuota(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	st.SetTenantLimits(1, 4)
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustReadyLogin(t, handler, "alice")
	code := mustCreateInvite(t, handler, cookie)
	_, priv, _ := ed25519.GenerateKey(nil)
	res, err := ctrl.Enroll(enrollRequest(t, code, "host-a", priv))
	if err != nil {
		t.Fatal(err)
	}
	quota := mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 {
		t.Fatalf("after enroll liveHosts=%v", quota["liveHosts"])
	}

	nonce, err := cryptoutil.RandomBytes(16)
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := cryptoutil.RandomBytes(32)
	if err != nil {
		t.Fatal(err)
	}
	ts := time.Now().Unix()
	proof := ed25519.Sign(priv, cryptoutil.BuildRevokeSelfTranscript(res.RouteId, ts, nonce, challenge))
	if _, err := ctrl.RevokeSelf(res.RouteId, ts, nonce, challenge, proof); err != nil {
		t.Fatal(err)
	}
	if _, err := st.GetHostByID(res.HostId); err == nil {
		t.Fatal("self-revoked host should be deleted so the slot and pubkey are free")
	}
	quota = mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 0 || quota["hostFull"] != false {
		t.Fatalf("after revoke-self quota=%v", quota)
	}

	next := mustCreateInvite(t, handler, cookie)
	if _, err := ctrl.Enroll(enrollRequest(t, next, "host-b", priv)); err != nil {
		t.Fatalf("enroll after self-revoke: %v", err)
	}
	quota = mustOverview(t, handler, cookie)["quota"].(map[string]any)
	if asInt(quota["liveHosts"]) != 1 {
		t.Fatalf("re-enroll liveHosts=%v", quota["liveHosts"])
	}
	events, raw := mustListEvents(t, handler, cookie)
	if !eventHasActor(events, store.ControlEventHostRevoke, "plugin") {
		t.Fatalf("tenant events missing plugin host.revoke: %s", raw)
	}
}

func TestTenantMustChangePasswordBeforeMint(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	cookie := mustLogin(t, handler, "alice", tenantPass)

	overview := mustOverview(t, handler, cookie)
	if overview["mustChangePassword"] != true {
		t.Fatalf("overview mustChangePassword=%v", overview["mustChangePassword"])
	}

	mint := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	mint.Header.Set("Content-Type", "application/json")
	mint.AddCookie(cookie)
	mintRec := httptest.NewRecorder()
	handler.ServeHTTP(mintRec, mint)
	if mintRec.Code != http.StatusForbidden {
		t.Fatalf("mint before password change status=%d, want 403", mintRec.Code)
	}

	ready := mustReadyLogin(t, handler, "alice")
	overview = mustOverview(t, handler, ready)
	if overview["mustChangePassword"] != nil && overview["mustChangePassword"] != false {
		t.Fatalf("overview after change mustChangePassword=%v", overview["mustChangePassword"])
	}
	if mustCreateInvite(t, handler, ready) == "" {
		t.Fatal("expected invite after password change")
	}
}

func TestTenantPasswordChangeAndAdminReset(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	alice := mustCreateTenant(t, handler, "alice", "Alice")
	first := mustLogin(t, handler, "alice", tenantPass)
	second := mustLogin(t, handler, "alice", tenantPass)

	ch := httptest.NewRequest(http.MethodPost, "/v1/account/password", strings.NewReader(`{"currentPassword":"`+tenantPass+`","newPassword":"rotated-pass-ok"}`))
	ch.Header.Set("Content-Type", "application/json")
	ch.AddCookie(first)
	chRec := httptest.NewRecorder()
	handler.ServeHTTP(chRec, ch)
	if chRec.Code != http.StatusOK {
		t.Fatalf("change password status=%d body=%s", chRec.Code, chRec.Body.String())
	}

	stale := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	stale.AddCookie(second)
	staleRec := httptest.NewRecorder()
	handler.ServeHTTP(staleRec, stale)
	if staleRec.Code != http.StatusUnauthorized {
		t.Fatalf("other session after password change status=%d, want 401", staleRec.Code)
	}

	kept := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	kept.AddCookie(first)
	keptRec := httptest.NewRecorder()
	handler.ServeHTTP(keptRec, kept)
	if keptRec.Code != http.StatusOK {
		t.Fatalf("current session after password change status=%d", keptRec.Code)
	}

	oldLogin := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"alice","password":"`+tenantPass+`"}`))
	oldLogin.Header.Set("Content-Type", "application/json")
	oldRec := httptest.NewRecorder()
	handler.ServeHTTP(oldRec, oldLogin)
	if oldRec.Code != http.StatusUnauthorized {
		t.Fatalf("old password login status=%d, want 401", oldRec.Code)
	}

	admin := mustLogin(t, handler, "admin", testAdminPass)
	adminPw := httptest.NewRequest(http.MethodPost, "/v1/account/password", strings.NewReader(`{"currentPassword":"x","newPassword":"rotated-pass-ok"}`))
	adminPw.Header.Set("Content-Type", "application/json")
	adminPw.AddCookie(admin)
	adminPwRec := httptest.NewRecorder()
	handler.ServeHTTP(adminPwRec, adminPw)
	if adminPwRec.Code != http.StatusBadRequest {
		t.Fatalf("admin self-password status=%d, want 400", adminPwRec.Code)
	}

	reset := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/password", strings.NewReader(`{"password":"reset-password1"}`))
	reset.Header.Set("Content-Type", "application/json")
	reset.AddCookie(admin)
	resetRec := httptest.NewRecorder()
	handler.ServeHTTP(resetRec, reset)
	if resetRec.Code != http.StatusOK {
		t.Fatalf("admin reset status=%d body=%s", resetRec.Code, resetRec.Body.String())
	}

	afterReset := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	afterReset.AddCookie(first)
	afterResetRec := httptest.NewRecorder()
	handler.ServeHTTP(afterResetRec, afterReset)
	if afterResetRec.Code != http.StatusUnauthorized {
		t.Fatalf("session after admin reset status=%d, want 401", afterResetRec.Code)
	}

	mustLogin(t, handler, "alice", "reset-password1")

	blocked := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	blocked.Header.Set("Content-Type", "application/json")
	blocked.AddCookie(mustLogin(t, handler, "alice", "reset-password1"))
	blockedRec := httptest.NewRecorder()
	handler.ServeHTTP(blockedRec, blocked)
	if blockedRec.Code != http.StatusForbidden {
		t.Fatalf("mint after admin reset status=%d, want 403", blockedRec.Code)
	}

	tenantReset := httptest.NewRequest(http.MethodPost, "/v1/tenants/"+alice.ID+"/password", strings.NewReader(`{"password":"hijack-password"}`))
	tenantReset.Header.Set("Content-Type", "application/json")
	tenantReset.AddCookie(mustLogin(t, handler, "alice", "reset-password1"))
	tenantResetRec := httptest.NewRecorder()
	handler.ServeHTTP(tenantResetRec, tenantReset)
	if tenantResetRec.Code != http.StatusForbidden {
		t.Fatalf("tenant reset-other status=%d, want 403", tenantResetRec.Code)
	}
}

func asInt(v any) int {
	switch n := v.(type) {
	case float64:
		return int(n)
	case int:
		return n
	case json.Number:
		i, _ := n.Int64()
		return int(i)
	default:
		return -1
	}
}

func mustOverview(t *testing.T, handler http.Handler, cookie *http.Cookie) map[string]any {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/v1/overview", nil)
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("overview status=%d body=%s", rec.Code, rec.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return out
}

func mustCreateTenant(t *testing.T, handler http.Handler, login, display string) *store.User {
	t.Helper()
	admin := mustLogin(t, handler, "admin", testAdminPass)
	body := `{"loginName":"` + login + `","displayName":"` + display + `","password":"` + tenantPass + `"}`
	req := httptest.NewRequest(http.MethodPost, "/v1/tenants", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(admin)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("create tenant %s status=%d body=%s", login, rec.Code, rec.Body.String())
	}
	var out struct {
		ID          string `json:"id"`
		LoginName   string `json:"loginName"`
		DisplayName string `json:"displayName"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	return &store.User{ID: out.ID, LoginName: out.LoginName, DisplayName: out.DisplayName, Role: store.RoleTenant}
}

func mustLogin(t *testing.T, handler http.Handler, user, password string) *http.Cookie {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/login", strings.NewReader(`{"user":"`+user+`","password":"`+password+`"}`))
	req.Header.Set("Content-Type", "application/json")
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("login %s status=%d body=%s", user, rec.Code, rec.Body.String())
	}
	cookies := rec.Result().Cookies()
	if len(cookies) != 1 {
		t.Fatalf("login %s cookies=%d", user, len(cookies))
	}
	return cookies[0]
}

func mustReadyLogin(t *testing.T, handler http.Handler, login string) *http.Cookie {
	t.Helper()
	cookie := mustLogin(t, handler, login, tenantPass)
	req := httptest.NewRequest(http.MethodPost, "/v1/account/password", strings.NewReader(`{"currentPassword":"`+tenantPass+`","newPassword":"`+tenantReadyPass+`"}`))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("ready password %s status=%d body=%s", login, rec.Code, rec.Body.String())
	}
	return cookie
}

func mustCreateInvite(t *testing.T, handler http.Handler, cookie *http.Cookie) string {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(`{}`))
	req.Header.Set("Content-Type", "application/json")
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("create invite status=%d body=%s", rec.Code, rec.Body.String())
	}
	var out map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	code, _ := out["inviteCode"].(string)
	if code == "" {
		t.Fatal("missing inviteCode")
	}
	return code
}

func eventCount(list []map[string]any, action string) int {
	n := 0
	for _, ev := range list {
		if ev["action"] == action {
			n++
		}
	}
	return n
}

func eventHasActor(list []map[string]any, action, login string) bool {
	for _, ev := range list {
		if ev["action"] == action && ev["actorLogin"] == login {
			return true
		}
	}
	return false
}

func mustListEvents(t *testing.T, handler http.Handler, cookie *http.Cookie) ([]map[string]any, string) {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/v1/events", nil)
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("list events status=%d body=%s", rec.Code, rec.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	return list, rec.Body.String()
}

func mustListInvites(t *testing.T, handler http.Handler, cookie *http.Cookie) []map[string]any {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/v1/invites", nil)
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("list invites status=%d body=%s", rec.Code, rec.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	return list
}

func mustListHosts(t *testing.T, handler http.Handler, cookie *http.Cookie) []map[string]any {
	t.Helper()
	req := httptest.NewRequest(http.MethodGet, "/v1/hosts", nil)
	req.AddCookie(cookie)
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("list hosts status=%d body=%s", rec.Code, rec.Body.String())
	}
	var list []map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &list); err != nil {
		t.Fatal(err)
	}
	return list
}

func TestControlUIWarnsWhenHostQuotaFull(t *testing.T) {
	html, err := webFS.ReadFile("web/index.html")
	if err != nil {
		t.Fatal(err)
	}
	js, err := webFS.ReadFile("web/app.js")
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(html), `id="hostQuotaFull"`) {
		t.Fatal("control UI missing hostQuotaFull banner")
	}
	if !strings.Contains(string(js), "hostQuotaFull()") {
		t.Fatal("control UI missing hostQuotaFull gate")
	}
	if !strings.Contains(string(js), "离线（占名额）") {
		t.Fatal("tenant offline hosts must show they still occupy a slot")
	}
	if !strings.Contains(string(js), "suggestedRevokeHost") || !strings.Contains(string(js), "建议吊销") {
		t.Fatal("at live-host cap the UI must mark the stalest offline host to revoke")
	}
	if !strings.Contains(string(js), "sortHostsForDisplay") || !strings.Contains(string(js), "从未心跳") {
		t.Fatal("host list must put offline occupying machines first and show relative last-seen")
	}
	if !strings.Contains(string(js), "revoked-hosts") || !strings.Contains(string(js), "已吊销 ${revoked.length} 台") {
		t.Fatal("host list must keep occupying machines in the main table and fold revoked records")
	}
	if !strings.Contains(string(js), "没有占名额的电脑") {
		t.Fatal("an all-revoked ledger must not look like there are no Hosts at all")
	}
	if !strings.Contains(string(js), "hostQuotaBannerCopy") || !strings.Contains(string(js), "列表会排在前面") {
		t.Fatal("quota banner must say offline occupying hosts sort first")
	}
	if !strings.Contains(string(js), "已贴过的未用码仍有效") || !strings.Contains(string(js), "不必再签发") {
		t.Fatal("at live-host cap Control must not tell tenants to mint another code")
	}
	if !strings.Contains(string(js), "confirmMintWhenCapped") || !strings.Contains(string(js), "同一电脑换路由") {
		t.Fatal("at live-host cap with unused codes the mint button must be for same-host re-enroll only")
	}
	if !strings.Contains(string(js), "新电脑可能已经贴过") {
		t.Fatal("minting at both caps must warn it would void a code a new computer may already have pasted")
	}
	if !strings.Contains(string(js), "张未用码") {
		t.Fatal("forge copy at live-host cap must name the unused codes to retry")
	}
	if !strings.Contains(string(js), "updateHostQuotaBanner") || !strings.Contains(string(js), "吊销这台") {
		t.Fatal("at live-host cap the banner must name the suggested host and revoke it in one click")
	}
	if !strings.Contains(string(js), "revokeLiveHost") {
		t.Fatal("banner and host row must share the same Control revoke path")
	}
	if strings.Contains(string(js), "请签发新接入码。") {
		t.Fatal("revoking to free a slot must not tell the operator to mint immediately")
	}
	if !strings.Contains(string(js), "revokeHostFollowUp") || !strings.Contains(string(js), "让新电脑再点接入") {
		t.Fatal("after revoke with unused codes Control must tell the new computer to retry the paste")
	}
	if !strings.Contains(string(js), "passwordChangeRequired") {
		t.Fatal("quota banner must not offer revoke before the tenant sets a password")
	}
	if strings.Contains(string(html), "换到别的 Relay 请先吊销") || strings.Contains(string(js), "换到别的 Relay 请先吊销") {
		t.Fatal("Control must not tell tenants to revoke before switching Relays")
	}
	if !strings.Contains(string(html), "换到别的 Relay 不必先在这里吊销") {
		t.Fatal("host list must say switching Relays does not require a Control revoke first")
	}
	if !strings.Contains(string(js), "consumedHostLabel") || !strings.Contains(string(js), "consumedHostName") {
		t.Fatal("invite list must name the computer that consumed a code")
	}
	if !strings.Contains(string(js), "consumedHostLive") || !strings.Contains(string(js), "吊销电脑") {
		t.Fatal("a consumed invite must revoke that computer without hunting the Host list")
	}
	if !strings.Contains(string(html), `id="statHostsLabel"`) || !strings.Contains(string(js), "上限 ${maxH}") {
		t.Fatal("tenant overview must show occupying host slots against the cap, not revoked-inclusive totals")
	}
	if !strings.Contains(string(js), "statInvitesLabel") || !strings.Contains(string(js), "未用邀请") {
		t.Fatal("tenant overview must show unused invites against the cap")
	}
	if !strings.Contains(string(js), "passwordChangeRequired") || !strings.Contains(string(js), "invite.consumedHostLive") {
		t.Fatal("invite-row host revoke must stay gated on password change like the quota banner")
	}
	if !strings.Contains(string(js), "接入电脑") {
		t.Fatal("invite table must show a consumed-computer column")
	}
	if !strings.Contains(string(js), "stale-invites") || !strings.Contains(string(js), "失效 ${stale.length} 条") {
		t.Fatal("invite list must keep unused and occupying consumed codes in the main tables and fold stale records")
	}
	if !strings.Contains(string(js), "没有未用或已接入的邀请") {
		t.Fatal("an all-stale invite ledger must not look like there are no invites at all")
	}
	if !strings.Contains(string(js), "仍占名额的已接入记录会保留") {
		t.Fatal("invite purge must keep consumed codes whose computer still occupies a slot")
	}
	if !strings.Contains(string(js), "compareTenants") || !strings.Contains(string(js), "已接入已满") {
		t.Fatal("admin tenant ledger must surface and sort tenants at the live-host cap")
	}
	if !strings.Contains(string(js), "tenantHostFull") || !strings.Contains(string(js), "hostFull") {
		t.Fatal("admin tenant ledger must read hostFull from GET /v1/tenants")
	}
	if !strings.Contains(string(js), "未用码已满") || !strings.Contains(string(js), "tenantInviteFull") {
		t.Fatal("admin tenant ledger must mark tenants at the unused-invite cap")
	}
	if !strings.Contains(string(html), "已接入或未用码满额") {
		t.Fatal("admin tenant panel must say full tenants sort first")
	}
	if strings.Contains(string(js), "\nfunc ") {
		t.Fatal("control UI JS must parse in the browser; Go func syntax breaks the whole console")
	}
	if !strings.Contains(string(js), "focusAdminTenant") || !strings.Contains(string(js), "查看电脑") {
		t.Fatal("admin tenant ledger must open that tenant's hosts like a user-scoped node list")
	}
	if !strings.Contains(string(js), "ownedByAdminFocus") || !strings.Contains(string(js), "显示全部") {
		t.Fatal("admin host/invite/event lists must filter to the focused tenant")
	}
	if !strings.Contains(string(js), "优先让对方自己登录处理") {
		t.Fatal("admin tenant focus must still tell the maintainer tenants self-serve revoke")
	}
	if !strings.Contains(string(html), "查看电脑") {
		t.Fatal("host list copy must mention viewing one tenant from the ledger")
	}
	if !strings.Contains(string(js), "复制入口") || !strings.Contains(string(js), "copyText(url)") {
		t.Fatal("admin tenant ledger must re-copy the Control URL without re-creating the tenant")
	}
	if !strings.Contains(string(js), "这张码只适合同一电脑换新路由") {
		t.Fatal("minting at cap must warn that the code is for same-host re-enroll")
	}
	if !strings.Contains(string(js), "挂起（日流量）") {
		t.Fatal("daily budget hold must not look like a Control revoke")
	}
	if !strings.Contains(string(js), "['devicesSection', 'tenantsSection', 'railTenants']") {
		t.Fatal("admin-only chrome must not hide tenant purge")
	}
	if !strings.Contains(string(js), "别人的记录不会动") {
		t.Fatal("tenant purge must say it does not touch other tenants")
	}
	if !strings.Contains(string(js), "hostActionLabel") {
		t.Fatal("revoke confirm must include Host id so same-named computers can be told apart")
	}
	if !strings.Contains(string(js), "inviteExpiryCopy") || !strings.Contains(string(html), `id="inviteExpiryNote"`) {
		t.Fatal("minted invite must show when it expires")
	}
	if !strings.Contains(string(js), "接入串含控制台地址") {
		t.Fatal("hosted enroll paste must say the token carries the Control URL")
	}
	if !strings.Contains(string(html), `id="inviteTTL"`) || !strings.Contains(string(js), "inviteTTLValue") {
		t.Fatal("control UI must let tenants pick invite lifetime")
	}
	if !strings.Contains(string(html), `value="24h"`) || !strings.Contains(string(js), "最长 24 小时") {
		t.Fatal("invite lifetime picker must cap at 24h")
	}
	if !strings.Contains(string(js), "tenantHandoffCopy") || !strings.Contains(string(js), "不要把 127.0.0.1 发给租户") {
		t.Fatal("tenant provisioning must not hand a loopback Control URL to hosted users")
	}
	if !strings.Contains(string(js), "configuredControlURL") || !strings.Contains(string(js), "publicControlURL") {
		t.Fatal("tenant handoff must prefer operator-configured public_control_url")
	}
	if !strings.Contains(string(html), `id="publicControlURLMissing"`) || !strings.Contains(string(html), "public_control_url") {
		t.Fatal("admin tenant panel must warn when public_control_url is unset")
	}
	if !strings.Contains(string(js), "replaceOldestUnused") || !strings.Contains(string(js), "confirmReplaceOldestUnused") {
		t.Fatal("unused-invite cap must confirm before voiding the oldest live unused code")
	}
	if !strings.Contains(string(js), "再签发会作废最早未用码") {
		t.Fatal("quota line must say minting at cap voids the oldest unused code")
	}
	if strings.Contains(string(js), "btn.disabled = full") {
		t.Fatal("unused-invite cap must not disable mint; confirm replace instead")
	}
	if !strings.Contains(string(js), "有效 · 再签发会作废") {
		t.Fatal("invite list must mark the oldest unused code that a full-cap mint would void")
	}
	if !strings.Contains(string(html), "未用满额时再签发会先确认作废最早那张") {
		t.Fatal("mint help must say a full-cap mint confirms before voiding the oldest unused code")
	}
	if !strings.Contains(string(html), `value="8h" selected`) {
		t.Fatal("invite TTL default must be 8 hours so a quota-blocked paste survives revoke")
	}
	if !strings.Contains(string(js), "sel.value || '8h'") {
		t.Fatal("invite TTL picker fallback must be 8h")
	}
}

func TestTenantInviteTTLIsClamped(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	alice := mustReadyLogin(t, handler, "alice")

	post := func(ttl string) map[string]any {
		t.Helper()
		body := `{}`
		if ttl != "" {
			body = `{"ttl":"` + ttl + `"}`
		}
		req := httptest.NewRequest(http.MethodPost, "/v1/invites", strings.NewReader(body))
		req.Header.Set("Content-Type", "application/json")
		req.AddCookie(alice)
		rec := httptest.NewRecorder()
		handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			t.Fatalf("ttl=%s status=%d body=%s", ttl, rec.Code, rec.Body.String())
		}
		var out map[string]any
		if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
			t.Fatal(err)
		}
		for _, row := range mustListInvites(t, handler, alice) {
			if row["revokedAt"] != nil || row["consumedAt"] != nil {
				continue
			}
			id, _ := row["id"].(string)
			if id == "" {
				continue
			}
			rev := httptest.NewRequest(http.MethodPost, "/v1/invites/"+id+"/revoke", strings.NewReader(`{}`))
			rev.Header.Set("Content-Type", "application/json")
			rev.AddCookie(alice)
			revRec := httptest.NewRecorder()
			handler.ServeHTTP(revRec, rev)
			if revRec.Code != http.StatusOK {
				t.Fatalf("revoke after mint status=%d body=%s", revRec.Code, revRec.Body.String())
			}
		}
		return out
	}

	year := post("8760h")
	if year["expiresIn"] != MaxInviteTTL.String() {
		t.Fatalf("year expiresIn=%v, want %s", year["expiresIn"], MaxInviteTTL)
	}
	exp, _ := year["expiresAt"].(float64)
	now := time.Now().Unix()
	want := now + int64(MaxInviteTTL.Seconds())
	if int64(exp) < want-5 || int64(exp) > want+5 {
		t.Fatalf("year expiresAt=%v, want ~%d", exp, want)
	}

	short := post("1s")
	if short["expiresIn"] != MinInviteTTL.String() {
		t.Fatalf("1s expiresIn=%v, want %s", short["expiresIn"], MinInviteTTL)
	}

	two := post("2h")
	if two["expiresIn"] != (2 * time.Hour).String() {
		t.Fatalf("2h expiresIn=%v", two["expiresIn"])
	}

	bogus := post("not-a-duration")
	if bogus["expiresIn"] != DefaultInviteTTL.String() {
		t.Fatalf("bogus expiresIn=%v, want default", bogus["expiresIn"])
	}

	def := post("")
	if def["expiresIn"] != DefaultInviteTTL.String() {
		t.Fatalf("default expiresIn=%v, want %s", def["expiresIn"], DefaultInviteTTL)
	}
}

func TestTenantPurgeOwnStaleInvitesAndRevokedHosts(t *testing.T) {
	ctrl, st := newTestControl(t)
	defer st.Close()
	handler := NewServer(ctrl, testAdminToken, "admin", testAdminPass).Handler()
	mustCreateTenant(t, handler, "alice", "Alice")
	mustCreateTenant(t, handler, "bob-user", "Bob")
	alice := mustReadyLogin(t, handler, "alice")
	bob := mustReadyLogin(t, handler, "bob-user")

	aliceStale := mustCreateInvite(t, handler, alice)
	aliceInvites := mustListInvites(t, handler, alice)
	rev := httptest.NewRequest(http.MethodPost, "/v1/invites/"+aliceInvites[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	rev.Header.Set("Content-Type", "application/json")
	rev.AddCookie(alice)
	revRec := httptest.NewRecorder()
	handler.ServeHTTP(revRec, rev)
	if revRec.Code != http.StatusOK {
		t.Fatalf("alice revoke invite status=%d body=%s", revRec.Code, revRec.Body.String())
	}
	bobLive := mustCreateInvite(t, handler, bob)

	purgeInv := httptest.NewRequest(http.MethodPost, "/v1/invites/purge", strings.NewReader(`{}`))
	purgeInv.Header.Set("Content-Type", "application/json")
	purgeInv.AddCookie(alice)
	purgeInvRec := httptest.NewRecorder()
	handler.ServeHTTP(purgeInvRec, purgeInv)
	if purgeInvRec.Code != http.StatusOK {
		t.Fatalf("alice invite purge status=%d body=%s", purgeInvRec.Code, purgeInvRec.Body.String())
	}
	if len(mustListInvites(t, handler, alice)) != 0 {
		t.Fatal("alice stale invite should be purged")
	}
	bobInvites := mustListInvites(t, handler, bob)
	if len(bobInvites) != 1 {
		t.Fatalf("bob invites after alice purge=%d", len(bobInvites))
	}

	_, privA, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, aliceStale, "host-alice-purge", privA)); err == nil {
		t.Fatal("revoked alice invite should not enroll")
	}
	aliceFresh := mustCreateInvite(t, handler, alice)
	if _, err := ctrl.Enroll(enrollRequest(t, aliceFresh, "host-alice-purge", privA)); err != nil {
		t.Fatalf("alice enroll: %v", err)
	}
	_, privB, _ := ed25519.GenerateKey(nil)
	if _, err := ctrl.Enroll(enrollRequest(t, bobLive, "host-bob-purge", privB)); err != nil {
		t.Fatalf("bob enroll: %v", err)
	}
	aliceHosts := mustListHosts(t, handler, alice)
	if len(aliceHosts) != 1 {
		t.Fatalf("alice hosts=%d", len(aliceHosts))
	}
	hostRev := httptest.NewRequest(http.MethodPost, "/v1/hosts/"+aliceHosts[0]["id"].(string)+"/revoke", strings.NewReader(`{}`))
	hostRev.Header.Set("Content-Type", "application/json")
	hostRev.AddCookie(alice)
	hostRevRec := httptest.NewRecorder()
	handler.ServeHTTP(hostRevRec, hostRev)
	if hostRevRec.Code != http.StatusOK {
		t.Fatalf("alice revoke host status=%d", hostRevRec.Code)
	}

	purgeHosts := httptest.NewRequest(http.MethodPost, "/v1/hosts/purge", strings.NewReader(`{}`))
	purgeHosts.Header.Set("Content-Type", "application/json")
	purgeHosts.AddCookie(alice)
	purgeHostsRec := httptest.NewRecorder()
	handler.ServeHTTP(purgeHostsRec, purgeHosts)
	if purgeHostsRec.Code != http.StatusOK {
		t.Fatalf("alice host purge status=%d body=%s", purgeHostsRec.Code, purgeHostsRec.Body.String())
	}
	if len(mustListHosts(t, handler, alice)) != 0 {
		t.Fatal("alice revoked host should be purged")
	}
	if len(mustListHosts(t, handler, bob)) != 1 {
		t.Fatalf("bob host after alice purge=%d, want 1", len(mustListHosts(t, handler, bob)))
	}

	unauth := httptest.NewRequest(http.MethodPost, "/v1/invites/purge", strings.NewReader(`{}`))
	unauth.Header.Set("Content-Type", "application/json")
	unauthRec := httptest.NewRecorder()
	handler.ServeHTTP(unauthRec, unauth)
	if unauthRec.Code != http.StatusUnauthorized {
		t.Fatalf("unauth purge status=%d, want 401", unauthRec.Code)
	}
}
