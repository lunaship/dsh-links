package config

import (
	"bytes"
	"encoding/base64"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/BurntSushi/toml"
)

type Config struct {
	ClientListen  string `toml:"client_listen"`
	AgentListen   string `toml:"agent_listen"`
	AdminListen   string `toml:"admin_listen"`
	ControlSocket string `toml:"control_socket"`

	TLSCert string `toml:"tls_cert"`
	TLSKey  string `toml:"tls_key"`

	IssuerPrivateKey string `toml:"issuer_private_key"`
	IssuerPublicKey  string `toml:"issuer_public_key"`
	RouteMasterKey   string `toml:"route_master_key"`
	AdminTokenFile   string `toml:"admin_token_file"`
	AdminUser        string `toml:"admin_user"`
	AdminPassword    string `toml:"admin_password"`
	Database         string `toml:"database"`
	IPCAuthTokenFile string `toml:"ipc_auth_token_file"`

	MaxTotalStreams           int    `toml:"max_total_streams"`
	DefaultMaxStreamsPerRoute int    `toml:"default_max_streams_per_route"`
	MaxConns                  int    `toml:"max_conns"`
	BridgeMaxLifetime         string `toml:"bridge_max_lifetime"`
	HeartbeatInterval         string `toml:"heartbeat_interval"`
	AgentDeadAfter            string `toml:"agent_dead_after"`
	BindTimeout               string `toml:"bind_timeout"`

	// Parsed durations
	HeartbeatIntervalDur time.Duration `toml:"-"`
	AgentDeadAfterDur    time.Duration `toml:"-"`
	BindTimeoutDur       time.Duration `toml:"-"`
	BridgeMaxLifetimeDur time.Duration `toml:"-"`
}

func DefaultConfig() *Config {
	return &Config{
		ClientListen:              "0.0.0.0:8443",
		AgentListen:               "0.0.0.0:8444",
		AdminListen:               "127.0.0.1:8080",
		ControlSocket:             "/run/dsh-links-relay/control.sock",
		TLSCert:                   "/etc/dsh-links-relay/relay.crt",
		TLSKey:                    "/etc/dsh-links-relay/relay.key",
		IssuerPrivateKey:          "/etc/dsh-links-relay/issuer.key",
		IssuerPublicKey:           "/etc/dsh-links-relay/issuer.pub",
		RouteMasterKey:            "/etc/dsh-links-relay/route-master.key",
		AdminTokenFile:            "/etc/dsh-links-relay/admin.token",
		AdminUser:                 "admin",
		AdminPassword:             "", // empty = require admin_token_file; set to bypass
		Database:                  "/var/lib/dsh-links-relay/control.db",
		IPCAuthTokenFile:          "/etc/dsh-links-relay/ipc.auth",
		MaxTotalStreams:           1000,
		DefaultMaxStreamsPerRoute: 8,
		MaxConns:                  2000,
		BridgeMaxLifetime:         "30m",
		HeartbeatInterval:         "20s",
		AgentDeadAfter:            "65s",
		BindTimeout:               "10s",
	}
}

func Load(path string) (*Config, error) {
	cfg := DefaultConfig()
	if path != "" {
		if _, err := os.Stat(path); err == nil {
			if _, err := toml.DecodeFile(path, cfg); err != nil {
				return nil, fmt.Errorf("decode config: %w", err)
			}
		} else if !os.IsNotExist(err) {
			return nil, err
		}
	}
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) Validate() error {
	var err error
	if c.HeartbeatIntervalDur, err = time.ParseDuration(c.HeartbeatInterval); err != nil {
		return fmt.Errorf("heartbeat_interval: %w", err)
	}
	if c.AgentDeadAfterDur, err = time.ParseDuration(c.AgentDeadAfter); err != nil {
		return fmt.Errorf("agent_dead_after: %w", err)
	}
	if c.BindTimeoutDur, err = time.ParseDuration(c.BindTimeout); err != nil {
		return fmt.Errorf("bind_timeout: %w", err)
	}
	if c.BridgeMaxLifetimeDur, err = time.ParseDuration(c.BridgeMaxLifetime); err != nil {
		return fmt.Errorf("bridge_max_lifetime: %w", err)
	}
	if c.BridgeMaxLifetimeDur <= 0 {
		return fmt.Errorf("bridge_max_lifetime >0")
	}
	if c.MaxTotalStreams <= 0 {
		return fmt.Errorf("max_total_streams must be >0")
	}
	if c.DefaultMaxStreamsPerRoute <= 0 || c.DefaultMaxStreamsPerRoute > 32 {
		return fmt.Errorf("default_max_streams_per_route 1..32")
	}
	if c.HeartbeatIntervalDur <= 0 {
		return fmt.Errorf("heartbeat_interval >0")
	}
	if c.AgentDeadAfterDur <= c.HeartbeatIntervalDur {
		return fmt.Errorf("agent_dead_after must exceed heartbeat_interval")
	}
	if c.BindTimeoutDur <= 0 {
		return fmt.Errorf("bind_timeout >0")
	}
	if c.MaxConns <= 0 {
		return fmt.Errorf("max_conns must be >0")
	}
	return nil
}

// LoadRouteMasterKey reads 32-byte raw key from file (raw bytes, not base64)
// If file contains base64, we try decode; else raw.
func (c *Config) LoadRouteMasterKey() ([]byte, error) {
	b, err := os.ReadFile(c.RouteMasterKey)
	if err != nil {
		return nil, err
	}
	if len(b) == 32 {
		return bytes.Clone(b), nil
	}
	trimmed := bytes.TrimSpace(b)
	if len(trimmed) == 32 {
		return trimmed, nil
	}
	if decoded, err := decodeBase64Variants(string(trimmed)); err == nil && len(decoded) == 32 {
		return decoded, nil
	}
	return nil, fmt.Errorf("routeMasterKey must be 32 bytes raw or base64url-encoded 32 bytes, got %d", len(trimmed))
}

func decodeBase64Variants(s string) ([]byte, error) {
	s = strings.TrimSpace(s)
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.RawStdEncoding, base64.StdEncoding, base64.URLEncoding} {
		if b, err := enc.DecodeString(s); err == nil {
			return b, nil
		}
	}
	return nil, fmt.Errorf("invalid base64")
}

// LoadIPCAuthToken reads IPC auth token (required, min 16 bytes)
func (c *Config) LoadIPCAuthToken() (string, error) {
	b, err := os.ReadFile(c.IPCAuthTokenFile)
	if err != nil {
		return "", err
	}
	t := strings.TrimSpace(string(b))
	if len(t) < 16 {
		return "", fmt.Errorf("ipc_auth_token must be >= 16 bytes")
	}
	return t, nil
}
func (c *Config) LoadAdminToken() (string, error) {
	b, err := os.ReadFile(c.AdminTokenFile)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(b)), nil
}

// LoadIssuerPrivateKey reads ed25519 private key (32 seed or 64 private)
func (c *Config) LoadIssuerPrivateKey() ([]byte, error) {
	b, err := os.ReadFile(c.IssuerPrivateKey)
	if err != nil {
		return nil, err
	}
	if len(b) == 32 || len(b) == 64 {
		return bytes.Clone(b), nil
	}
	trimmed := bytesTrimSpace(b)
	if len(trimmed) == 64 {
		return trimmed, nil
	}
	if len(trimmed) == 32 {
		// seed
		return trimmed, nil
	}
	if decoded, err := decodeBase64Variants(string(trimmed)); err == nil {
		if len(decoded) == 32 || len(decoded) == 64 {
			return decoded, nil
		}
	}
	return nil, fmt.Errorf("issuer private key must be 32 or 64 bytes, got %d", len(trimmed))
}

func bytesTrimSpace(b []byte) []byte {
	return []byte(strings.TrimSpace(string(b)))
}
