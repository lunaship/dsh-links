package main

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/dsh-links/dsh-links-relay/internal/config"
)

func TestIsLoopbackListen(t *testing.T) {
	tests := []struct {
		addr string
		want bool
	}{
		{"127.0.0.1:8080", true},
		{"[::1]:8080", true},
		{"localhost:8080", true},
		{"0.0.0.0:8080", false},
		{"127.evil.example:8080", false},
		{"example.com:8080", false},
		{"missing-port", false},
	}
	for _, tt := range tests {
		t.Run(tt.addr, func(t *testing.T) {
			if got := isLoopbackListen(tt.addr); got != tt.want {
				t.Fatalf("isLoopbackListen(%q)=%v want %v", tt.addr, got, tt.want)
			}
		})
	}
}

func TestRequireAdminPassword(t *testing.T) {
	if err := requireAdminPassword(""); err == nil {
		t.Fatal("empty password accepted")
	}
	if err := requireAdminPassword("short"); err == nil {
		t.Fatal("short password accepted")
	}
	if err := requireAdminPassword("change-me-now"); err != nil {
		t.Fatal(err)
	}
}

func TestInitWritesLoadableConfig(t *testing.T) {
	dir := t.TempDir()
	password, configPath, err := initLayout(dir, false, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if password == "" {
		t.Fatal("init did not return an admin password")
	}
	if _, _, err := initLayout(dir, false, false, nil); err == nil {
		t.Fatal("second init without --force succeeded")
	}
	cfg, err := config.Load(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if err := requireAdminPassword(cfg.AdminPassword); err != nil {
		t.Fatal(err)
	}
	if cfg.AdminPassword != password {
		t.Fatalf("config password %q != printed password", cfg.AdminPassword)
	}
	if _, err := cfg.LoadRouteMasterKey(); err != nil {
		t.Fatal(err)
	}
	if _, err := cfg.LoadIssuerPrivateKey(); err != nil {
		t.Fatal(err)
	}
	if _, err := cfg.LoadAdminToken(); err != nil {
		t.Fatal(err)
	}
	if _, err := cfg.LoadIPCAuthToken(); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(dir, "relay.crt")); err != nil {
		t.Fatal(err)
	}
	if cfg.ClientListen != "127.0.0.1:8443" {
		t.Fatalf("client_listen = %q", cfg.ClientListen)
	}
}
