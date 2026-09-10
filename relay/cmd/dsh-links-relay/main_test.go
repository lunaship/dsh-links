package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/lunaship/dsh-links/relay/internal/config"
	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
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

func TestValidateAdminTransportRequiresTLSOffLoopback(t *testing.T) {
	if err := validateAdminTransport("127.0.0.1:8080", false, "", ""); err != nil {
		t.Fatalf("loopback HTTP rejected: %v", err)
	}
	if err := validateAdminTransport("0.0.0.0:8080", true, "", ""); err == nil {
		t.Fatal("non-loopback cleartext admin listener accepted")
	}
	if err := validateAdminTransport("0.0.0.0:8080", true, "/tmp/admin.crt", "/tmp/admin.key"); err != nil {
		t.Fatalf("non-loopback TLS listener rejected: %v", err)
	}
}

func TestControlSecureCookies(t *testing.T) {
	if controlSecureCookies(false, false) {
		t.Fatal("loopback HTTP must not force Secure cookies")
	}
	if !controlSecureCookies(true, false) {
		t.Fatal("Control TLS must mark cookies Secure")
	}
	if !controlSecureCookies(false, true) {
		t.Fatal("admin_secure_cookies must mark cookies Secure behind a TLS reverse proxy")
	}
	if !controlSecureCookies(true, true) {
		t.Fatal("TLS plus admin_secure_cookies must stay Secure")
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
	dir := secureTempDir(t)
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
	fp, err := cryptoutil.CertSHA256FingerprintFile(filepath.Join(dir, "relay.crt"))
	if err != nil {
		t.Fatal(err)
	}
	if len(fp) != 64 {
		t.Fatalf("tls fingerprint %q", fp)
	}
	if cfg.ClientListen != "127.0.0.1:8443" {
		t.Fatalf("client_listen = %q", cfg.ClientListen)
	}
}

func TestInitListenAllConfiguresAdminTLS(t *testing.T) {
	dir := secureTempDir(t)
	_, configPath, err := initLayout(dir, false, true, []string{"relay.example.com"})
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Load(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.AdminAllowNonLoopback || cfg.AdminTLSCert == "" || cfg.AdminTLSKey == "" {
		t.Fatalf("listen-all admin transport is not TLS protected: %+v", cfg)
	}
	if err := validateAdminTransport(cfg.AdminListen, cfg.AdminAllowNonLoopback, cfg.AdminTLSCert, cfg.AdminTLSKey); err != nil {
		t.Fatal(err)
	}
	if cfg.PublicHost != "relay.example.com" {
		t.Fatalf("public_host = %q", cfg.PublicHost)
	}
}

func TestInitRejectsInsecurePreparedDirectory(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "prepared")
	if err := os.Mkdir(dir, 0777); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(dir, 0777); err != nil {
		t.Fatal(err)
	}
	if _, _, err := initLayout(dir, false, false, nil); err == nil || !strings.Contains(err.Error(), "group or other") {
		t.Fatalf("insecure directory error=%v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, "config.toml")); !os.IsNotExist(err) {
		t.Fatalf("insecure directory received output: %v", err)
	}
}

func TestInitDoesNotOverwritePreplantedFileWithoutForce(t *testing.T) {
	dir := secureTempDir(t)
	path := filepath.Join(dir, "route-master.key")
	if err := os.WriteFile(path, []byte("attacker-controlled"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, _, err := initLayout(dir, false, false, nil); err == nil {
		t.Fatal("init overwrote a pre-existing file without --force")
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "attacker-controlled" {
		t.Fatalf("pre-existing content changed to %q", got)
	}
}

func TestInitForceRejectsSymlinkAndPreservesTarget(t *testing.T) {
	dir := secureTempDir(t)
	target := filepath.Join(t.TempDir(), "target")
	if err := os.WriteFile(target, []byte("preserve me"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, filepath.Join(dir, "issuer.key")); err != nil {
		t.Fatal(err)
	}
	if _, _, err := initLayout(dir, true, false, nil); err == nil {
		t.Fatal("--force accepted a pre-planted symlink")
	}
	got, err := os.ReadFile(target)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "preserve me" {
		t.Fatalf("symlink target changed to %q", got)
	}
}

func TestInitForceOverwritesOwnedRegularLayout(t *testing.T) {
	dir := secureTempDir(t)
	firstPassword, _, err := initLayout(dir, false, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	secondPassword, configPath, err := initLayout(dir, true, false, nil)
	if err != nil {
		t.Fatal(err)
	}
	if firstPassword == secondPassword {
		t.Fatal("--force did not rotate generated credentials")
	}
	info, err := os.Stat(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("config mode=%o, want 600", info.Mode().Perm())
	}
}

func secureTempDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.Chmod(dir, 0700); err != nil {
		t.Fatal(err)
	}
	return dir
}
