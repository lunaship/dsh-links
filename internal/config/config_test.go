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

// The deploy examples must stay loadable with the current config schema.
func TestDeployExamplesLoad(t *testing.T) {
	for _, name := range []string{"config.toml.example", "config-control.toml.example", "config-relay.toml.example"} {
		cfg, err := Load(filepath.Join("..", "..", "deploy", name))
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		if cfg.ControlSocket == "" || cfg.Database == "" || cfg.AdminListen == "" {
			t.Fatalf("%s: required listen/socket/database fields missing", name)
		}
	}
}
