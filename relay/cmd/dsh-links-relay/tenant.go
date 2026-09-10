package main

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/config"
	"github.com/lunaship/dsh-links/relay/internal/store"
)

func runTenant(configPath string, args []string) {
	if len(args) == 0 || args[0] == "help" || args[0] == "--help" || args[0] == "-h" {
		fmt.Print(`Usage:
  dsh-links-relay tenant list     --config FILE
  dsh-links-relay tenant hosts    --config FILE [--login NAME] [--all]
  dsh-links-relay tenant create   --config FILE --login NAME [--name DISPLAY] --password PASS
  dsh-links-relay tenant disable  --config FILE --login NAME
  dsh-links-relay tenant enable   --config FILE --login NAME
  dsh-links-relay tenant password --config FILE --login NAME --password PASS

Talks to the running Control on admin_listen (loopback). The Android App
never uses these commands. There is no public signup.
hosts lists occupying computers (like Control 查看电脑). Tenants revoke
their own machines; --all includes revoked records. Do not send 127.0.0.1.
`)
		return
	}
	cmd := args[0]
	flags := parseTenantFlags(args[1:])
	if flags.passwordFile != "" {
		b, err := os.ReadFile(flags.passwordFile)
		if err != nil {
			logFatal("read password file: %v", err)
		}
		flags.password = strings.TrimSpace(string(b))
	}
	cfg, err := config.Load(configPath)
	if err != nil {
		logFatal("load config: %v", err)
	}
	token, err := cfg.LoadAdminToken()
	if err != nil {
		logFatal("load admin token: %v", err)
	}
	client := newTenantClient(cfg, token)
	switch cmd {
	case "list":
		if err := client.list(); err != nil {
			logFatal("%v", err)
		}
	case "hosts":
		if err := client.listHosts(flags.login, flags.all); err != nil {
			logFatal("%v", err)
		}
	case "create":
		if flags.login == "" || flags.password == "" {
			logFatal("create requires --login and --password (or --password-file)")
		}
		if err := client.create(flags.login, flags.name, flags.password); err != nil {
			logFatal("%v", err)
		}
		fmt.Println(tenantControlHandoff(cfg.PublicControlURL))
	case "disable", "enable":
		if flags.login == "" {
			logFatal("%s requires --login", cmd)
		}
		if err := client.setDisabled(flags.login, cmd == "disable"); err != nil {
			logFatal("%v", err)
		}
	case "password":
		if flags.login == "" || flags.password == "" {
			logFatal("password requires --login and --password (or --password-file)")
		}
		if err := client.setPassword(flags.login, flags.password); err != nil {
			logFatal("%v", err)
		}
	default:
		fmt.Fprintf(os.Stderr, "unknown tenant command %q\n", cmd)
		os.Exit(1)
	}
}

type tenantFlags struct {
	login        string
	name         string
	password     string
	passwordFile string
	all          bool
}

func parseTenantFlags(args []string) tenantFlags {
	var f tenantFlags
	for i := 0; i < len(args); i++ {
		arg := args[i]
		switch {
		case arg == "--config" && i+1 < len(args):
			i++
		case strings.HasPrefix(arg, "--config="):
		case arg == "--login" && i+1 < len(args):
			i++
			f.login = args[i]
		case strings.HasPrefix(arg, "--login="):
			f.login = strings.TrimPrefix(arg, "--login=")
		case arg == "--name" && i+1 < len(args):
			i++
			f.name = args[i]
		case strings.HasPrefix(arg, "--name="):
			f.name = strings.TrimPrefix(arg, "--name=")
		case arg == "--password" && i+1 < len(args):
			i++
			f.password = args[i]
		case strings.HasPrefix(arg, "--password="):
			f.password = strings.TrimPrefix(arg, "--password=")
		case arg == "--password-file" && i+1 < len(args):
			i++
			f.passwordFile = args[i]
		case strings.HasPrefix(arg, "--password-file="):
			f.passwordFile = strings.TrimPrefix(arg, "--password-file=")
		case arg == "--all":
			f.all = true
		default:
			fmt.Fprintf(os.Stderr, "unknown tenant flag %q\n", arg)
			os.Exit(1)
		}
	}
	return f
}

type tenantClient struct {
	base  string
	token string
	http  *http.Client
}

func newTenantClient(cfg *config.Config, token string) *tenantClient {
	return &tenantClient{
		base:  adminBaseURL(cfg),
		token: token,
		http:  newTenantHTTPClient(cfg),
	}
}

func rewriteListen(addr string) string {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return addr
	}
	if host == "0.0.0.0" || host == "::" || host == "[::]" {
		return net.JoinHostPort("127.0.0.1", port)
	}
	return addr
}

func adminBaseURL(cfg *config.Config) string {
	scheme := "http"
	if strings.TrimSpace(cfg.AdminTLSCert) != "" {
		scheme = "https"
	}
	return scheme + "://" + rewriteListen(cfg.AdminListen)
}

func newTenantHTTPClient(cfg *config.Config) *http.Client {
	tr := &http.Transport{}
	if strings.TrimSpace(cfg.AdminTLSCert) != "" {
		tlsCfg := &tls.Config{MinVersion: tls.VersionTLS12}
		dial := rewriteListen(cfg.AdminListen)
		if isLoopbackListen(dial) {
			tlsCfg.InsecureSkipVerify = true
		} else if host := strings.TrimSpace(cfg.PublicHost); host != "" {
			tlsCfg.ServerName = host
		}
		tr.TLSClientConfig = tlsCfg
	}
	return &http.Client{Timeout: 15 * time.Second, Transport: tr}
}

func (c *tenantClient) do(method, path, body string) (int, []byte, error) {
	req, err := http.NewRequest(method, strings.TrimRight(c.base, "/")+path, strings.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", "application/json")
	if body != "" || method == http.MethodPost {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http.Do(req)
	if err != nil {
		return 0, nil, fmt.Errorf("control unreachable at %s (is it running?): %w", c.base, err)
	}
	defer res.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(res.Body, 1<<20))
	if err != nil {
		return res.StatusCode, nil, err
	}
	return res.StatusCode, raw, nil
}

func (c *tenantClient) list() error {
	code, raw, err := c.do(http.MethodGet, "/v1/tenants", "")
	if err != nil {
		return err
	}
	if code != http.StatusOK {
		return fmt.Errorf("list tenants: HTTP %d %s", code, bytes.TrimSpace(raw))
	}
	var rows []map[string]any
	if err := json.Unmarshal(raw, &rows); err != nil {
		return err
	}
	if len(rows) == 0 {
		fmt.Println("(no tenants)")
		return nil
	}
	sortTenantRows(rows)
	for _, row := range rows {
		fmt.Printf("%s\t%s\t%s\t%s/%s enrolled\t%s/%s unused\n",
			str(row["loginName"]), str(row["displayName"]), tenantListStatus(row),
			str(row["liveHosts"]), str(row["maxLiveHosts"]),
			str(row["unusedInvites"]), str(row["maxUnusedInvites"]))
	}
	return nil
}

func (c *tenantClient) listHosts(login string, includeRevoked bool) error {
	code, raw, err := c.do(http.MethodGet, "/v1/hosts", "")
	if err != nil {
		return err
	}
	if code != http.StatusOK {
		return fmt.Errorf("list hosts: HTTP %d %s", code, bytes.TrimSpace(raw))
	}
	var rows []map[string]any
	if err := json.Unmarshal(raw, &rows); err != nil {
		return err
	}
	filtered := filterHostRows(rows, login, includeRevoked)
	if len(filtered) == 0 {
		if login != "" {
			fmt.Printf("(no hosts for %s)\n", login)
		} else {
			fmt.Println("(no hosts)")
		}
		return nil
	}
	for _, row := range filtered {
		fmt.Println(formatHostRow(row))
	}
	return nil
}

func hostLoginName(row map[string]any) string {
	if name := str(row["loginName"]); name != "" {
		return name
	}
	id := str(row["userId"])
	if id == "" || id == "user-default" {
		return "admin"
	}
	return id
}

func hostRevoked(row map[string]any) bool {
	return row["revokedAt"] != nil
}

func hostConnState(row map[string]any) string {
	if hostRevoked(row) {
		return "revoked"
	}
	if tenantJSONBool(row["online"]) {
		return "online"
	}
	return "offline"
}

func hostSlotState(row map[string]any) string {
	if hostRevoked(row) {
		return "revoked"
	}
	return "occupying"
}

func formatHostRow(row map[string]any) string {
	return fmt.Sprintf("%s\t%s\t%s\t%s\t%s",
		hostLoginName(row),
		str(row["hostName"]),
		str(row["id"]),
		hostConnState(row),
		hostSlotState(row),
	)
}

func compareHostRows(a, b map[string]any) int {
	aRev := hostRevoked(a)
	bRev := hostRevoked(b)
	if aRev != bRev {
		if aRev {
			return 1
		}
		return -1
	}
	if c := strings.Compare(hostLoginName(a), hostLoginName(b)); c != 0 {
		return c
	}
	if c := strings.Compare(str(a["hostName"]), str(b["hostName"])); c != 0 {
		return c
	}
	return strings.Compare(str(a["id"]), str(b["id"]))
}

func filterHostRows(rows []map[string]any, login string, includeRevoked bool) []map[string]any {
	want := strings.TrimSpace(login)
	out := make([]map[string]any, 0, len(rows))
	for _, row := range rows {
		if row == nil {
			continue
		}
		if !includeRevoked && hostRevoked(row) {
			continue
		}
		if want != "" && hostLoginName(row) != want {
			continue
		}
		out = append(out, row)
	}
	sort.SliceStable(out, func(i, j int) bool {
		return compareHostRows(out[i], out[j]) < 0
	})
	return out
}

func tenantJSONBool(v any) bool {
	b, _ := v.(bool)
	return b
}

func tenantJSONInt(v any) int {
	switch t := v.(type) {
	case int:
		return t
	case int64:
		return int(t)
	case float64:
		return int(t)
	case json.Number:
		n, _ := t.Int64()
		return int(n)
	default:
		return 0
	}
}

func tenantRowAtCap(row map[string]any, flagKey, liveKey, maxKey string) bool {
	if tenantJSONBool(row[flagKey]) {
		return true
	}
	max := tenantJSONInt(row[maxKey])
	return max > 0 && tenantJSONInt(row[liveKey]) >= max
}

func tenantListStatus(row map[string]any) string {
	if row["disabledAt"] != nil {
		return "disabled"
	}
	if tenantJSONBool(row["passwordMustChange"]) {
		return "password-change"
	}
	if tenantRowAtCap(row, "hostFull", "liveHosts", "maxLiveHosts") {
		return "host-full"
	}
	if tenantRowAtCap(row, "inviteFull", "unusedInvites", "maxUnusedInvites") {
		return "invite-full"
	}
	return "ok"
}

func compareTenantRows(a, b map[string]any) int {
	aDis := a["disabledAt"] != nil
	bDis := b["disabledAt"] != nil
	if aDis != bDis {
		if aDis {
			return 1
		}
		return -1
	}
	aHost := tenantRowAtCap(a, "hostFull", "liveHosts", "maxLiveHosts")
	bHost := tenantRowAtCap(b, "hostFull", "liveHosts", "maxLiveHosts")
	if aHost != bHost {
		if aHost {
			return -1
		}
		return 1
	}
	aInv := tenantRowAtCap(a, "inviteFull", "unusedInvites", "maxUnusedInvites")
	bInv := tenantRowAtCap(b, "inviteFull", "unusedInvites", "maxUnusedInvites")
	if aInv != bInv {
		if aInv {
			return -1
		}
		return 1
	}
	return strings.Compare(str(a["loginName"]), str(b["loginName"]))
}

func sortTenantRows(rows []map[string]any) {
	sort.SliceStable(rows, func(i, j int) bool {
		return compareTenantRows(rows[i], rows[j]) < 0
	})
}

func (c *tenantClient) create(login, name, password string) error {
	if len(password) < store.MinPasswordLen {
		return fmt.Errorf("password must be at least %d characters", store.MinPasswordLen)
	}
	payload, _ := json.Marshal(map[string]string{
		"loginName": login, "displayName": name, "password": password,
	})
	code, raw, err := c.do(http.MethodPost, "/v1/tenants", string(payload))
	if err != nil {
		return err
	}
	if code != http.StatusOK {
		return fmt.Errorf("create tenant: HTTP %d %s", code, bytes.TrimSpace(raw))
	}
	var out map[string]any
	_ = json.Unmarshal(raw, &out)
	fmt.Printf("created %s (%s); they must change the password before minting invites\n", str(out["loginName"]), str(out["id"]))
	return nil
}

func tenantControlHandoff(controlURL string) string {
	url := strings.TrimSpace(controlURL)
	if url != "" {
		return fmt.Sprintf("Give them %s and the login over a private channel. They use Control, not the Android App.", url)
	}
	return "Give them the HTTPS reverse-proxy Control URL and login over a private channel. Do not send 127.0.0.1. Set public_control_url or tell them that address. They use Control, not the Android App."
}

func (c *tenantClient) setDisabled(login string, disable bool) error {
	id, err := c.lookupID(login)
	if err != nil {
		return err
	}
	action := "enable"
	if disable {
		action = "disable"
	}
	code, raw, err := c.do(http.MethodPost, "/v1/tenants/"+id+"/"+action, "{}")
	if err != nil {
		return err
	}
	if code != http.StatusOK {
		return fmt.Errorf("%s tenant: HTTP %d %s", action, code, bytes.TrimSpace(raw))
	}
	fmt.Printf("%s %s\n", action+"d", login)
	return nil
}

func (c *tenantClient) setPassword(login, password string) error {
	if len(password) < store.MinPasswordLen {
		return fmt.Errorf("password must be at least %d characters", store.MinPasswordLen)
	}
	id, err := c.lookupID(login)
	if err != nil {
		return err
	}
	payload, _ := json.Marshal(map[string]string{"password": password})
	code, raw, err := c.do(http.MethodPost, "/v1/tenants/"+id+"/password", string(payload))
	if err != nil {
		return err
	}
	if code != http.StatusOK {
		return fmt.Errorf("set password: HTTP %d %s", code, bytes.TrimSpace(raw))
	}
	fmt.Printf("password updated for %s; they must change it on next login\n", login)
	return nil
}

func (c *tenantClient) lookupID(login string) (string, error) {
	code, raw, err := c.do(http.MethodGet, "/v1/tenants", "")
	if err != nil {
		return "", err
	}
	if code != http.StatusOK {
		return "", fmt.Errorf("list tenants: HTTP %d %s", code, bytes.TrimSpace(raw))
	}
	var rows []map[string]any
	if err := json.Unmarshal(raw, &rows); err != nil {
		return "", err
	}
	for _, row := range rows {
		if str(row["loginName"]) == login {
			id := str(row["id"])
			if id == "" {
				return "", fmt.Errorf("tenant %s has empty id", login)
			}
			return id, nil
		}
	}
	return "", fmt.Errorf("tenant %s not found", login)
}

func str(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case float64:
		return fmt.Sprintf("%.0f", t)
	case json.Number:
		return t.String()
	case nil:
		return ""
	default:
		return fmt.Sprint(t)
	}
}
