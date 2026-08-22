package main

import (
	"context"
	"crypto/ed25519"
	"crypto/tls"
	"encoding/base64"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/config"
	"github.com/dsh-links/dsh-links-relay/internal/control"
	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
	"github.com/dsh-links/dsh-links-relay/internal/ingress"
	"github.com/dsh-links/dsh-links-relay/internal/logutil"
	"github.com/dsh-links/dsh-links-relay/internal/metrics"
	"github.com/dsh-links/dsh-links-relay/internal/registry"
	"github.com/dsh-links/dsh-links-relay/internal/store"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(1)
	}
	cmd := os.Args[1]
	// parse --config flag
	configPath := "/etc/dsh-links-relay/config.toml"
	for i, arg := range os.Args {
		if arg == "--config" && i+1 < len(os.Args) {
			configPath = os.Args[i+1]
		}
		if strings.HasPrefix(arg, "--config=") {
			configPath = strings.TrimPrefix(arg, "--config=")
		}
	}
	switch cmd {
	case "init":
		runInit(os.Args[2:])
	case "control":
		runControl(configPath)
	case "relay":
		runRelay(configPath)
	case "help", "--help", "-h":
		usage()
	default:
		fmt.Fprintf(os.Stderr, "unknown command %q\n", cmd)
		usage()
		os.Exit(1)
	}
}

func usage() {
	fmt.Print(`dsh-links-relay - DSH Links Relay

Usage:
  dsh-links-relay init    --dir .local
  dsh-links-relay control --config .local/config.toml
  dsh-links-relay relay   --config .local/config.toml
  dsh-links-relay help

Commands:
  init     create keys, a localhost TLS certificate, and config.toml
  control  run control plane (SQLite, invites, admin API, Unix socket)
  relay    run data plane (8443/8444 TLS, registry, bridge)
`)
}

func runControl(configPath string) {
	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	// Load keys
	issuerPrivBytes, err := cfg.LoadIssuerPrivateKey()
	if err != nil {
		log.Fatalf("load issuer key: %v", err)
	}
	routeMaster, err := cfg.LoadRouteMasterKey()
	if err != nil {
		log.Fatalf("load route master: %v", err)
	}
	if err := requireAdminPassword(cfg.AdminPassword); err != nil {
		log.Fatalf("%v", err)
	}
	adminToken, err := cfg.LoadAdminToken()
	if err != nil {
		log.Fatalf("load admin token: %v", err)
	}
	if len(adminToken) < 16 {
		log.Fatalf("admin token too short")
	}
	st, err := store.Open(cfg.Database)
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	defer st.Close()

	ctrl, err := control.New(st, issuerPrivBytes, routeMaster, cfg.DefaultMaxStreamsPerRoute)
	if err != nil {
		log.Fatalf("new control: %v", err)
	}

	// Start IPC server (with auth token if configured)
	ipcAuthToken, err := cfg.LoadIPCAuthToken()
	if err != nil {
		log.Fatalf("load ipc auth token: %v", err)
	}
	ipcSrv := control.NewIPCServer(ctrl, cfg.ControlSocket, ipcAuthToken)
	if err := ipcSrv.Start(); err != nil {
		log.Fatalf("ipc start: %v", err)
	}
	defer ipcSrv.Close()
	log.Printf("control IPC listening on %s", cfg.ControlSocket)

	// Inject revoke push callback so RevokeHost immediately notifies relays.
	ctrl.SetRevokeFn(func(routeId, hostId string) {
		n := ipcSrv.BroadcastRevoke(routeId, hostId)
		log.Printf("host revoked %s host=%s broadcast to %d relay(s)", routeId[:min(len(routeId), 8)], logutil.Value(hostId), n)
	})

	// Start HTTP admin
	adminSrv := control.NewServer(ctrl, adminToken, cfg.AdminUser, cfg.AdminPassword)
	httpSrv := &http.Server{
		Addr:              cfg.AdminListen,
		Handler:           adminSrv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	if !isLoopbackListen(cfg.AdminListen) {
		log.Fatalf("admin_listen must be loopback, got %s", cfg.AdminListen)
	}
	go func() {
		log.Printf("admin API listening on %s", cfg.AdminListen)
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("admin http: %v", err)
		}
	}()

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
	log.Println("shutting down control...")
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = httpSrv.Shutdown(ctx)
}

func runRelay(configPath string) {
	cfg, err := config.Load(configPath)
	if err != nil {
		log.Fatalf("load config: %v", err)
	}
	// Load TLS
	tlsConfig, err := loadTLS(cfg.TLSCert, cfg.TLSKey)
	if err != nil {
		log.Fatalf("load tls: %v", err)
	}
	// Load route master
	routeMaster, err := cfg.LoadRouteMasterKey()
	if err != nil {
		log.Fatalf("load route master: %v", err)
	}
	// Load issuer public key
	issuerPubBytes, err := loadIssuerPub(cfg.IssuerPublicKey)
	if err != nil {
		log.Fatalf("load issuer pub: %v", err)
	}
	issuerPub := ed25519.PublicKey(issuerPubBytes)

	// For relay, we need control client via Unix socket for enroll
	ipcAuthToken, err := cfg.LoadIPCAuthToken()
	if err != nil {
		log.Fatalf("load ipc auth token: %v", err)
	}
	ipcClient := control.NewIPCClient(cfg.ControlSocket, ipcAuthToken)
	if err := ipcClient.Connect(); err != nil {
		log.Printf("warning: initial IPC connect failed: %v (will retry in background)", err)
	}
	// Start background reconnection; will reconnect automatically if control restarts.
	ipcClient.StartReconnect()

	// Open store read-only for host lookup (shared DB with control).
	relayStore, err := store.OpenReadOnly(cfg.Database)
	if err != nil {
		log.Fatalf("open relay store: %v (start control first so the database exists)", err)
	}
	defer relayStore.Close()

	reg := registry.New(cfg.MaxTotalStreams)
	m := metrics.New()
	revokePollStop := make(chan struct{})
	var revokePollWG sync.WaitGroup
	revokePollWG.Add(1)
	go func() {
		defer revokePollWG.Done()
		ticker := time.NewTicker(time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				for _, sess := range reg.List() {
					host, err := relayStore.GetHostByRoute(sess.RouteIdRaw)
					if err != nil || host.RevokedAt != nil || uint64(host.Generation) != sess.Generation {
						reg.Revoke(sess.RouteIdStr)
					}
				}
			case <-revokePollStop:
				return
			}
		}
	}()

	// Set revoke push callback after reg is created.
	ipcClient.SetRevokeFn(func(routeId, hostId string) {
		log.Printf("relay revoke push: route=%s host=%s", routeId[:min(len(routeId), 8)], logutil.Value(hostId))
		reg.Revoke(routeId)
	})

	relayCtrl := &ipcControlAdapter{
		client:    ipcClient,
		issuerPub: issuerPub,
		store:     relayStore,
	}

	ing := ingress.New(cfg.ClientListen, cfg.AgentListen, tlsConfig, reg, relayCtrl, m, routeMaster, issuerPub, cfg.HeartbeatIntervalDur, cfg.BindTimeoutDur, cfg.AgentDeadAfterDur, cfg.MaxTotalStreams, cfg.MaxConns, cfg.BridgeMaxLifetimeDur, log.Default())
	if err := ing.Start(); err != nil {
		log.Fatalf("start ingress: %v", err)
	}
	log.Printf("relay listening client=%s agent=%s", cfg.ClientListen, cfg.AgentListen)

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	<-sigCh
	log.Println("shutting down relay...")
	close(revokePollStop)
	revokePollWG.Wait()
	_ = ing.Close()
	_ = ipcClient.Close()
}

func loadTLS(certFile, keyFile string) (*tls.Config, error) {
	cert, err := tls.LoadX509KeyPair(certFile, keyFile)
	if err != nil {
		return nil, err
	}
	return &tls.Config{
		Certificates: []tls.Certificate{cert},
		MinVersion:   tls.VersionTLS12,
	}, nil
}

func loadIssuerPub(path string) ([]byte, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(b) == ed25519.PublicKeySize {
		return append([]byte(nil), b...), nil
	}
	s := strings.TrimSpace(string(b))
	// Try base64
	for _, enc := range []*base64.Encoding{base64.RawURLEncoding, base64.RawStdEncoding, base64.StdEncoding, base64.URLEncoding} {
		if decoded, err := enc.DecodeString(s); err == nil && len(decoded) == 32 {
			return decoded, nil
		}
	}
	trimmed := []byte(s)
	if len(trimmed) == 32 {
		return trimmed, nil
	}
	return nil, fmt.Errorf("issuer public key must be 32 bytes, got %d", len(trimmed))
}

func isLoopbackListen(addr string) bool {
	host, _, err := net.SplitHostPort(addr)
	if err != nil {
		return false
	}
	if host == "localhost" {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ipcControlAdapter implements ingress.ControlAPI via IPC + local store poll
type ipcControlAdapter struct {
	client    *control.IPCClient
	issuerPub ed25519.PublicKey
	store     *store.Store
}

func (a *ipcControlAdapter) Enroll(req *ingress.EnrollProxyRequest) (*ingress.EnrollProxyResponse, error) {
	// Forward to control via IPC
	// Encode fields to base64 for IPC
	ipcReq := control.EnrollIPCRequest{
		InviteCode:    req.InviteCode,
		HostId:        req.HostId,
		HostPublicKey: base64.RawURLEncoding.EncodeToString(req.HostPublicKey),
		Ts:            req.Ts,
		Nonce:         base64.RawURLEncoding.EncodeToString(req.Nonce),
		Proof:         base64.RawURLEncoding.EncodeToString(req.Proof),
		Challenge:     base64.RawURLEncoding.EncodeToString(req.Challenge),
	}
	resp, err := a.client.Enroll(ipcReq)
	if err != nil {
		return nil, err
	}
	routeId, _ := base64.RawURLEncoding.DecodeString(resp.RouteId)
	routeSecret, _ := base64.RawURLEncoding.DecodeString(resp.RouteSecret)
	return &ingress.EnrollProxyResponse{
		RouteId:     routeId,
		RouteSecret: routeSecret,
		Capability:  resp.Capability,
		Generation:  resp.Generation,
	}, nil
}

func (a *ipcControlAdapter) LookupHostByRoute(routeId []byte) (string, uint64, []byte, int, bool, error) {
	if a.store == nil {
		// Store unavailable: refuse all lookups rather than trusting fallback data.
		return "", 0, nil, 0, false, errors.New("relay store not available")
	}
	h, err := a.store.GetHostByRoute(routeId)
	if err != nil {
		return "", 0, nil, 0, false, err
	}
	isRevoked := h.RevokedAt != nil
	return h.ID, uint64(h.Generation), h.HostPubKey, h.MaxStreams, isRevoked, nil
}

func (a *ipcControlAdapter) VerifyCapability(cap string) (*cryptoutil.CapabilityPayload, error) {
	return cryptoutil.VerifyCapability(a.issuerPub, cap)
}

func (a *ipcControlAdapter) Renew(req *ingress.RenewProxyRequest) (string, error) {
	return a.client.Renew(control.RenewIPCRequest{
		RouteId:       req.RouteId,
		HostId:        req.HostId,
		HostPublicKey: req.HostPubKey,
		Ts:            req.Ts,
		Nonce:         req.Nonce,
		Challenge:     req.Challenge,
		Proof:         req.Proof,
		OldCapability: req.OldCapability,
	})
}
