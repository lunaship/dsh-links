package config

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoadMissingFileFails(t *testing.T) {
	_, err := Load(filepath.Join(t.TempDir(), "missing.toml"))
	if err == nil || !strings.Contains(err.Error(), "not found") {
		t.Fatalf("missing config error = %v", err)
	}
}

func TestLoadEmptyPathFails(t *testing.T) {
	if _, err := Load(""); err == nil {
		t.Fatal("empty path succeeded")
	}
}

func TestLoadDecodesAdminPassword(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.toml")
	body := `
client_listen = "127.0.0.1:8443"
agent_listen = "127.0.0.1:8444"
admin_listen = "127.0.0.1:8080"
control_socket = "/tmp/control.sock"
tls_cert = "/tmp/relay.crt"
tls_key = "/tmp/relay.key"
issuer_private_key = "/tmp/issuer.key"
issuer_public_key = "/tmp/issuer.pub"
route_master_key = "/tmp/route-master.key"
admin_token_file = "/tmp/admin.token"
admin_user = "admin"
admin_password = "change-me-now"
database = "/tmp/control.db"
ipc_auth_token_file = "/tmp/ipc.auth"
`
	if err := os.WriteFile(path, []byte(body), 0600); err != nil {
		t.Fatal(err)
	}
	cfg, err := Load(path)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AdminPassword != "change-me-now" {
		t.Fatalf("admin_password = %q", cfg.AdminPassword)
	}
}

func TestLoadAdminPasswordFromFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "admin.password")
	if err := os.WriteFile(path, []byte("correct horse battery staple\n"), 0600); err != nil {
		t.Fatal(err)
	}
	cfg := DefaultConfig()
	cfg.AdminPasswordFile = path
	got, err := cfg.LoadAdminPassword()
	if err != nil {
		t.Fatal(err)
	}
	if got != "correct horse battery staple" {
		t.Fatalf("password=%q", got)
	}
	cfg.AdminPassword = "inline-conflict"
	if _, err := cfg.LoadAdminPassword(); err == nil {
		t.Fatal("inline and file passwords were both accepted")
	}
}

func TestValidateRequiresCompleteAdminTLSKeypair(t *testing.T) {
	cfg := DefaultConfig()
	cfg.AdminTLSCert = "/tmp/admin.crt"
	if err := cfg.Validate(); err == nil || !strings.Contains(err.Error(), "configured together") {
		t.Fatalf("partial admin TLS config error=%v", err)
	}
	cfg.AdminTLSKey = "/tmp/admin.key"
	if err := cfg.Validate(); err != nil {
		t.Fatalf("complete admin TLS config rejected: %v", err)
	}
}

// The deploy examples must stay loadable with the current config schema.
func TestDeployExamplesLoad(t *testing.T) {
	for _, name := range []string{
		"config.toml.example",
		"config-control.toml.example",
		"config-relay.toml.example",
		filepath.Join("docker", "control.toml"),
		filepath.Join("docker", "relay.toml"),
	} {
		cfg, err := Load(filepath.Join("..", "..", "deploy", name))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if cfg.ControlSocket == "" || cfg.Database == "" || cfg.AdminListen == "" {
			t.Fatalf("%s: required listen/socket/database fields missing", name)
		}
	}
}

func TestIPv6PrefixLenValidation(t *testing.T) {
	base := DefaultConfig()
	base.IPv6PrefixLen = 64
	if err := base.Validate(); err != nil {
		t.Fatalf("default 64 should validate: %v", err)
	}
	for _, bad := range []int{31, 129, -1} {
		c := DefaultConfig()
		c.IPv6PrefixLen = bad
		if err := c.Validate(); err == nil {
			t.Errorf("ipv6_prefix_len=%d should fail validation", bad)
		}
	}
	// 0 explicitly disables aggregation (the DefaultConfig always carries 64).
	c := DefaultConfig()
	c.IPv6PrefixLen = 0
	if err := c.Validate(); err != nil {
		t.Errorf("ipv6_prefix_len=0 (disable aggregation) should validate: %v", err)
	}
	for _, good := range []int{32, 56, 64, 96, 128} {
		c := DefaultConfig()
		c.IPv6PrefixLen = good
		if err := c.Validate(); err != nil {
			t.Errorf("ipv6_prefix_len=%d should validate: %v", good, err)
		}
	}
}
