package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/pem"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

const minAdminPasswordLen = 12

func runInit(args []string) {
	dir := ".local"
	force := false
	listenAll := false
	var extraHosts []string
	for i := 0; i < len(args); i++ {
		arg := args[i]
		switch {
		case arg == "--dir" && i+1 < len(args):
			i++
			dir = args[i]
		case strings.HasPrefix(arg, "--dir="):
			dir = strings.TrimPrefix(arg, "--dir=")
		case arg == "--force":
			force = true
		case arg == "--listen-all":
			listenAll = true
		case arg == "--host" && i+1 < len(args):
			i++
			extraHosts = append(extraHosts, args[i])
		case strings.HasPrefix(arg, "--host="):
			extraHosts = append(extraHosts, strings.TrimPrefix(arg, "--host="))
		case arg == "--help" || arg == "-h":
			fmt.Print(`Usage:
  dsh-links-relay init --dir .local [--force] [--listen-all] [--host NAME]

Creates a self-host working directory: keys, a localhost TLS certificate,
config.toml, and a one-time admin password. Does not start the service.
`)
			return
		default:
			fmt.Fprintf(os.Stderr, "unknown init flag %q\n", arg)
			os.Exit(1)
		}
	}

	password, configPath, err := initLayout(dir, force, listenAll, extraHosts)
	if err != nil {
		logFatal("%v", err)
	}

	fmt.Printf("Wrote %s\n", configPath)
	fmt.Printf("Admin user: admin\n")
	fmt.Printf("Admin password: %s\n", password)
	fmt.Printf("Admin UI: http://127.0.0.1:8080/ (loopback only)\n")
	fmt.Printf("\nStart control first, then relay:\n")
	fmt.Printf("  dsh-links-relay control --config %s\n", configPath)
	fmt.Printf("  dsh-links-relay relay   --config %s\n", configPath)
}

func initLayout(dir string, force, listenAll bool, extraHosts []string) (password, configPath string, err error) {
	absDir, err := filepath.Abs(dir)
	if err != nil {
		return "", "", fmt.Errorf("init dir: %w", err)
	}
	configPath = filepath.Join(absDir, "config.toml")
	if err := prepareInitDir(absDir); err != nil {
		return "", "", fmt.Errorf("init dir: %w", err)
	}
	initFiles := []string{
		"route-master.key", "issuer.key", "issuer.pub", "admin.token",
		"admin.password", "ipc.auth", "relay.crt", "relay.key", "config.toml",
	}
	if err := preflightInitFiles(absDir, initFiles, force); err != nil {
		return "", "", err
	}

	password, err = randomToken(16)
	if err != nil {
		return "", "", fmt.Errorf("admin password: %w", err)
	}
	routeMaster, err := cryptoutil.RandomBytes(32)
	if err != nil {
		return "", "", fmt.Errorf("route-master.key: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "route-master.key"), routeMaster, 0600, force); err != nil {
		return "", "", fmt.Errorf("route-master.key: %w", err)
	}
	pub, priv, err := cryptoutil.GenerateEd25519KeyPair()
	if err != nil {
		return "", "", fmt.Errorf("issuer key: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "issuer.key"), priv.Seed(), 0600, force); err != nil {
		return "", "", fmt.Errorf("issuer.key: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "issuer.pub"), []byte(pub), 0644, force); err != nil {
		return "", "", fmt.Errorf("issuer.pub: %w", err)
	}
	adminToken, err := randomToken(32)
	if err != nil {
		return "", "", fmt.Errorf("admin.token: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "admin.token"), []byte(adminToken+"\n"), 0600, force); err != nil {
		return "", "", fmt.Errorf("admin.token: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "admin.password"), []byte(password+"\n"), 0600, force); err != nil {
		return "", "", fmt.Errorf("admin.password: %w", err)
	}
	ipcToken, err := randomToken(32)
	if err != nil {
		return "", "", fmt.Errorf("ipc.auth: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "ipc.auth"), []byte(ipcToken+"\n"), 0600, force); err != nil {
		return "", "", fmt.Errorf("ipc.auth: %w", err)
	}
	hosts := append([]string{"localhost", "127.0.0.1", "::1"}, extraHosts...)
	certPEM, keyPEM, err := selfSignedTLS(hosts, 365*24*time.Hour)
	if err != nil {
		return "", "", fmt.Errorf("tls cert: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "relay.crt"), certPEM, 0644, force); err != nil {
		return "", "", fmt.Errorf("relay.crt: %w", err)
	}
	if err := writeInitFile(filepath.Join(absDir, "relay.key"), keyPEM, 0600, force); err != nil {
		return "", "", fmt.Errorf("relay.key: %w", err)
	}

	clientListen := "127.0.0.1:8443"
	agentListen := "127.0.0.1:8444"
	adminListen := "127.0.0.1:8080"
	adminAllowNonLoopback := ""
	adminTLS := ""
	if listenAll {
		clientListen = "0.0.0.0:8443"
		agentListen = "0.0.0.0:8444"
		adminListen = "0.0.0.0:8080"
		adminAllowNonLoopback = "admin_allow_non_loopback = true\n"
		adminTLS = fmt.Sprintf("admin_tls_cert = %s\nadmin_tls_key = %s\n", tomlQuote(filepath.Join(absDir, "relay.crt")), tomlQuote(filepath.Join(absDir, "relay.key")))
	}

	cfgText := fmt.Sprintf(`# Generated by dsh-links-relay init. Do not commit this file.
client_listen = %s
agent_listen = %s
admin_listen = %s
%s%scontrol_socket = %s
ipc_auth_token_file = %s

tls_cert = %s
tls_key = %s

issuer_private_key = %s
issuer_public_key = %s
route_master_key = %s

admin_token_file = %s
admin_user = "admin"
admin_password = %s

database = %s

max_total_streams = 1000
default_max_streams_per_route = 8
max_conns = 2000
bridge_max_lifetime = "30m"
heartbeat_interval = "20s"
agent_dead_after = "65s"
bind_timeout = "10s"
`,
		tomlQuote(clientListen),
		tomlQuote(agentListen),
		tomlQuote(adminListen),
		adminAllowNonLoopback,
		adminTLS,
		tomlQuote(filepath.Join(absDir, "control.sock")),
		tomlQuote(filepath.Join(absDir, "ipc.auth")),
		tomlQuote(filepath.Join(absDir, "relay.crt")),
		tomlQuote(filepath.Join(absDir, "relay.key")),
		tomlQuote(filepath.Join(absDir, "issuer.key")),
		tomlQuote(filepath.Join(absDir, "issuer.pub")),
		tomlQuote(filepath.Join(absDir, "route-master.key")),
		tomlQuote(filepath.Join(absDir, "admin.token")),
		tomlQuote(password),
		tomlQuote(filepath.Join(absDir, "control.db")),
	)
	if err := writeInitFile(configPath, []byte(cfgText), 0600, force); err != nil {
		return "", "", fmt.Errorf("config.toml: %w", err)
	}
	return password, configPath, nil
}

func requireAdminPassword(password string) error {
	if strings.TrimSpace(password) == "" {
		return fmt.Errorf("admin_password is required; run `dsh-links-relay init` or set it in config.toml")
	}
	if len(password) < minAdminPasswordLen {
		return fmt.Errorf("admin_password must be at least %d characters", minAdminPasswordLen)
	}
	return nil
}

func prepareInitDir(path string) error {
	info, err := os.Lstat(path)
	if os.IsNotExist(err) {
		if err := os.Mkdir(path, 0700); err != nil {
			return err
		}
		return nil
	}
	if err != nil {
		return err
	}
	if info.Mode()&os.ModeSymlink != 0 || !info.IsDir() {
		return fmt.Errorf("%s must be a real directory", path)
	}
	if info.Mode().Perm()&0077 != 0 {
		return fmt.Errorf("%s must not be accessible by group or other users", path)
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || int(stat.Uid) != os.Geteuid() {
		return fmt.Errorf("%s must be owned by the current user", path)
	}
	return nil
}

func preflightInitFiles(dir string, names []string, force bool) error {
	for _, name := range names {
		path := filepath.Join(dir, name)
		info, err := os.Lstat(path)
		if os.IsNotExist(err) {
			continue
		}
		if err != nil {
			return fmt.Errorf("inspect %s: %w", path, err)
		}
		if !force {
			return fmt.Errorf("file already exists at %s (pass --force to overwrite)", path)
		}
		if err := validateOwnedRegularFile(path, info); err != nil {
			return err
		}
	}
	return nil
}

func validateOwnedRegularFile(path string, info os.FileInfo) error {
	if info.Mode()&os.ModeSymlink != 0 || !info.Mode().IsRegular() {
		return fmt.Errorf("refusing to overwrite non-regular file %s", path)
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || int(stat.Uid) != os.Geteuid() {
		return fmt.Errorf("refusing to overwrite file not owned by current user: %s", path)
	}
	if stat.Nlink != 1 {
		return fmt.Errorf("refusing to overwrite multiply-linked file %s", path)
	}
	return nil
}

func writeInitFile(path string, data []byte, mode os.FileMode, overwrite bool) error {
	flags := os.O_WRONLY | os.O_CREATE | syscall.O_NOFOLLOW
	if overwrite {
		flags |= os.O_TRUNC
	} else {
		flags |= os.O_EXCL
	}
	f, err := os.OpenFile(path, flags, mode)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}
	if err := validateOwnedRegularFile(path, info); err != nil {
		return err
	}
	if err := f.Chmod(mode); err != nil {
		return err
	}
	if _, err := f.Write(data); err != nil {
		return err
	}
	return f.Sync()
}

func tomlQuote(s string) string {
	return `"` + strings.ReplaceAll(strings.ReplaceAll(s, `\`, `\\`), `"`, `\"`) + `"`
}

func randomToken(n int) (string, error) {
	b, err := cryptoutil.RandomBytes(n)
	if err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}

func selfSignedTLS(hosts []string, valid time.Duration) (certPEM, keyPEM []byte, err error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 128))
	if err != nil {
		return nil, nil, err
	}
	tpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "dsh-links-relay"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(valid),
		KeyUsage:     x509.KeyUsageDigitalSignature | x509.KeyUsageKeyEncipherment,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		DNSNames:     nil,
		IPAddresses:  nil,
	}
	seen := map[string]bool{}
	for _, host := range hosts {
		host = strings.TrimSpace(host)
		if host == "" || seen[host] {
			continue
		}
		seen[host] = true
		if ip := net.ParseIP(host); ip != nil {
			tpl.IPAddresses = append(tpl.IPAddresses, ip)
			continue
		}
		tpl.DNSNames = append(tpl.DNSNames, host)
	}
	der, err := x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		return nil, nil, err
	}
	certPEM = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyBytes, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return nil, nil, err
	}
	keyPEM = pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyBytes})
	return certPEM, keyPEM, nil
}

func logFatal(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
	os.Exit(1)
}
