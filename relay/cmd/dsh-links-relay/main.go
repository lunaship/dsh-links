package main

import (
	"context"
	"crypto/ed25519"
	"crypto/tls"
	"encoding/base64"
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

	"github.com/lunaship/dsh-links/relay/internal/config"
	"github.com/lunaship/dsh-links/relay/internal/control"
	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
	"github.com/lunaship/dsh-links/relay/internal/ingress"
	"github.com/lunaship/dsh-links/relay/internal/logutil"
	"github.com/lunaship/dsh-links/relay/internal/metrics"
	"github.com/lunaship/dsh-links/relay/internal/registry"
	"github.com/lunaship/dsh-links/relay/internal/store"
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
	case "tenant":
		runTenant(configPath, os.Args[2:])
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
	dsh-links-relay tenant  --config .local/config.toml <list|hosts|create|disable|enable|password>
  dsh-links-relay help

Commands:
  init     create keys, a localhost TLS certificate, and config.toml
  control  run control plane (SQLite, invites, admin API, Unix socket)
  relay    run data plane (8443/8444 TLS, registry, bridge)
  tenant   maintainer provisioning for Control tenants (loopback admin API)
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
	adminPassword, err := cfg.LoadAdminPassword()
	if err != nil {
		log.Fatalf("load admin password: %v", err)
	}
	if err := requireAdminPassword(adminPassword); err != nil {
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

	st.SetTenantLimits(cfg.TenantMaxLiveHosts, cfg.TenantMaxUnusedInvites)

	ctrl, err := control.New(st, issuerPrivBytes, routeMaster, cfg.DefaultMaxStreamsPerRoute)
	if err != nil {
		log.Fatalf("new control: %v", err)
	}
	// Phase 2 anonymous policy: config is the initial value; the runtime kill
	// switch persisted in settings overrides it after the first admin toggle.
	ctrl.ApplyAnonymousPolicy(control.AnonymousPolicy{
		Enabled:    cfg.AnonymousEnroll,
		MaxHosts:   cfg.AnonymousMaxHostsPerDevice,
		MaxStreams: cfg.AnonymousMaxStreamsPerRoute,
		DailyBytes: cfg.AnonymousDailyBytes,
	})
	ctrl.SetCapabilityTTL(cfg.CapabilityTTLDur)
	if v, _ := st.GetSetting(control.SettingAnonymousEnroll); v != "" {
		_ = ctrl.SetAnonymousEnabled(v == "1")
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

	// Inject revoke push callback so RevokeHost immediately notifies relays,
	// and waits up to 1s for each relay to confirm the route is actually
	// closed locally (delivered/acked reach the admin API response).
	ctrl.SetRevokeFn(func(routeId, hostId string) (int, int) {
		delivered, acked := ipcSrv.BroadcastRevokeAndWait(routeId, hostId, time.Second)
		log.Printf("host revoked %s host=%s delivered=%d acked=%d", routeId[:min(len(routeId), 8)], logutil.Value(hostId), delivered, acked)
		return delivered, acked
	})
	ctrl.SetSuspendFn(func(routeId, hostId string) {
		ipcSrv.BroadcastSuspend(routeId, hostId)
	})

	// Start HTTP admin
	adminTLS := strings.TrimSpace(cfg.AdminTLSCert) != ""
	adminSrv := control.NewServerWithSecureCookies(ctrl, adminToken, cfg.AdminUser, adminPassword, controlSecureCookies(adminTLS, cfg.AdminSecureCookies))
	applyEnrollMeta(adminSrv, cfg)
	adminSrv.SetPublicControlURL(cfg.PublicControlURL)
	httpSrv := &http.Server{
		Addr:              cfg.AdminListen,
		Handler:           adminSrv.Handler(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
		MaxHeaderBytes:    16 << 10,
	}
	if err := validateAdminTransport(cfg.AdminListen, cfg.AdminAllowNonLoopback, cfg.AdminTLSCert, cfg.AdminTLSKey); err != nil {
		log.Fatal(err)
	}
	go func() {
		scheme := "http"
		if adminTLS {
			scheme = "https"
		}
		log.Printf("admin API listening on %s://%s", scheme, cfg.AdminListen)
		var err error
		if adminTLS {
			err = httpSrv.ListenAndServeTLS(cfg.AdminTLSCert, cfg.AdminTLSKey)
		} else {
			err = httpSrv.ListenAndServe()
		}
		if err != nil && err != http.ErrServerClosed {
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
	if fp, err := cryptoutil.CertSHA256FingerprintFile(cfg.TLSCert); err == nil {
		log.Printf("relay TLS SHA-256 fingerprint %s", fp)
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

	reg := registry.New(cfg.MaxTotalStreams)
	m := metrics.New()
	revokePollStop := make(chan struct{})
	var revokePollWG sync.WaitGroup
	revokePollWG.Add(1)
	go func() {
		defer revokePollWG.Done()
		// Revocation is primarily event-driven: Control broadcasts a
		// revoke_notify immediately on RevokeHost, and re-pushes the current
		// revoked set to this relay on every (re)connect, so the 1s invariant
		// holds without a per-second sweep. This poll is only a belt-and-
		// suspenders fallback for a dropped notification, so it runs at 3s
		// instead of 1s to avoid O(online-sessions) IPC chatter per second.
		ticker := time.NewTicker(3 * time.Second)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				for _, sess := range reg.List() {
					host, err := ipcClient.LookupHostByRoute(sess.RouteIdRaw)
					if err != nil || host.Revoked || host.Generation != sess.Generation {
						reg.Revoke(sess.RouteIdStr)
					} else if host.Suspended {
						reg.EvictStreams(sess.RouteIdStr)
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
	ipcClient.SetSuspendFn(func(routeId, hostId string) {
		log.Printf("relay suspend push: route=%s host=%s", routeId[:min(len(routeId), 8)], logutil.Value(hostId))
		reg.EvictStreams(routeId)
	})

	relayCtrl := &ipcControlAdapter{
		client:    ipcClient,
		issuerPub: issuerPub,
	}

	ing := ingress.New(cfg.ClientListen, cfg.AgentListen, tlsConfig, reg, relayCtrl, m, issuerPub, cfg.HeartbeatIntervalDur, cfg.BindTimeoutDur, cfg.AgentDeadAfterDur, cfg.MaxTotalStreams, cfg.MaxConns, cfg.IPv6PrefixLen, cfg.BridgeMaxLifetimeDur, log.Default())
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

func validateAdminTransport(addr string, allowNonLoopback bool, tlsCert, tlsKey string) error {
	if isLoopbackListen(addr) {
		return nil
	}
	if !allowNonLoopback {
		return fmt.Errorf("admin_listen must be loopback unless admin_allow_non_loopback is explicitly enabled, got %s", addr)
	}
	if strings.TrimSpace(tlsCert) == "" || strings.TrimSpace(tlsKey) == "" {
		return fmt.Errorf("non-loopback admin_listen requires admin_tls_cert and admin_tls_key")
	}
	return nil
}

func controlSecureCookies(adminTLS, adminSecureCookies bool) bool {
	return adminTLS || adminSecureCookies
}

func applyEnrollMeta(server *control.Server, cfg *config.Config) {
	if fp, err := cryptoutil.CertSHA256FingerprintFile(cfg.TLSCert); err == nil {
		server.SetTLSFingerprint(fp)
	}
	host := strings.TrimSpace(cfg.PublicHost)
	pin := false
	if pem, err := os.ReadFile(cfg.TLSCert); err == nil {
		if cert, err := cryptoutil.ParseLeafCert(pem); err == nil {
			pin = cryptoutil.CertIsSelfSigned(cert)
		}
		if host == "" {
			host = cryptoutil.PublicHostFromCert(pem)
		}
	}
	if host == "" {
		host = strings.TrimSpace(os.Getenv("RELAY_HOST"))
	}
	_, agentPort, err := net.SplitHostPort(cfg.AgentListen)
	if err != nil || strings.TrimSpace(agentPort) == "" {
		agentPort = cryptoutil.DefaultAgentPort
	}
	server.SetEnrollMeta(host, agentPort, pin)
}

// ipcControlAdapter implements ingress.ControlAPI exclusively via IPC. Relay
// deliberately has no filesystem path to Control's SQLite database.
type ipcControlAdapter struct {
	client    *control.IPCClient
	issuerPub ed25519.PublicKey
}

var _ ingress.ControlAPI = (*ipcControlAdapter)(nil)

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
		HostName:      req.HostName,
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

func (a *ipcControlAdapter) LookupHostByRoute(routeId []byte) (string, uint64, []byte, int, bool, bool, error) {
	h, err := a.client.LookupHostByRoute(routeId)
	if err != nil {
		return "", 0, nil, 0, false, false, err
	}
	pubKey, err := base64.RawURLEncoding.DecodeString(h.HostPublicKey)
	if err != nil || len(pubKey) != ed25519.PublicKeySize {
		return "", 0, nil, 0, false, false, fmt.Errorf("invalid host public key from control")
	}
	return h.HostID, h.Generation, pubKey, h.MaxStreams, h.Revoked, h.Suspended, nil
}

func (a *ipcControlAdapter) VerifyRouteMAC(req *ingress.RouteMACProxyRequest) error {
	ipcReq := control.VerifyRouteMACIPCRequest{
		Operation:  req.Operation,
		RouteID:    base64.RawURLEncoding.EncodeToString(req.RouteID),
		Generation: req.Generation,
		Ts:         req.Ts,
		Nonce:      base64.RawURLEncoding.EncodeToString(req.Nonce),
		Challenge:  base64.RawURLEncoding.EncodeToString(req.Challenge),
		MAC:        base64.RawURLEncoding.EncodeToString(req.MAC),
	}
	if len(req.StreamID) > 0 {
		ipcReq.StreamID = base64.RawURLEncoding.EncodeToString(req.StreamID)
	}
	return a.client.VerifyRouteMAC(ipcReq)
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

func (a *ipcControlAdapter) ReportUsage(routeID []byte, rx, tx int64, connects int) error {
	return a.client.ReportUsage(routeID, rx, tx, connects)
}

func (a *ipcControlAdapter) TouchHost(routeID []byte) error {
	return a.client.TouchHost(routeID)
}

func (a *ipcControlAdapter) ClearHostHeartbeat(routeID []byte) error {
	return a.client.ClearHostHeartbeat(routeID)
}

func (a *ipcControlAdapter) Bootstrap(req *ingress.BootstrapProxyRequest) (string, error) {
	challenge := ""
	if len(req.Challenge) == 32 {
		challenge = base64.RawURLEncoding.EncodeToString(req.Challenge)
	}
	return a.client.Bootstrap(control.BootstrapIPCRequest{
		PubKey:    base64.RawURLEncoding.EncodeToString(req.PubKey),
		Ts:        req.Ts,
		Nonce:     base64.RawURLEncoding.EncodeToString(req.Nonce),
		Proof:     base64.RawURLEncoding.EncodeToString(req.Proof),
		Challenge: challenge,
	})
}

func (a *ipcControlAdapter) RevokeSelf(req *ingress.RevokeSelfProxyRequest) (string, error) {
	challenge := ""
	if len(req.Challenge) == 32 {
		challenge = base64.RawURLEncoding.EncodeToString(req.Challenge)
	}
	return a.client.RevokeSelf(control.RevokeSelfIPCRequest{
		RouteId:   base64.RawURLEncoding.EncodeToString(req.RouteId),
		Ts:        req.Ts,
		Nonce:     base64.RawURLEncoding.EncodeToString(req.Nonce),
		Proof:     base64.RawURLEncoding.EncodeToString(req.Proof),
		Challenge: challenge,
	})
}
