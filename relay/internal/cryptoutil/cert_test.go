package cryptoutil

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func testCertPEM(t *testing.T) (pemBytes, der []byte) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "dsh-links-relay"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err = x509.CreateCertificate(rand.Reader, tpl, tpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	pemBytes = pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	return pemBytes, der
}

func TestCertSHA256FingerprintHashesLeafDER(t *testing.T) {
	pemBytes, der := testCertPEM(t)
	got, err := CertSHA256Fingerprint(pemBytes)
	if err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(der)
	want := hex.EncodeToString(sum[:])
	if got != want {
		t.Fatalf("fingerprint=%s want %s", got, want)
	}
	if len(got) != 64 {
		t.Fatalf("len=%d, want 64", len(got))
	}
}

func TestCertSHA256FingerprintRejectsGarbage(t *testing.T) {
	if _, err := CertSHA256Fingerprint(nil); err == nil {
		t.Fatal("empty PEM accepted")
	}
	if _, err := CertSHA256Fingerprint([]byte("not a cert")); err == nil {
		t.Fatal("garbage PEM accepted")
	}
	if _, err := CertSHA256FingerprintFile(""); err == nil {
		t.Fatal("empty path accepted")
	}
}

func TestCertSHA256FingerprintFile(t *testing.T) {
	pemBytes, _ := testCertPEM(t)
	dir := t.TempDir()
	path := filepath.Join(dir, "relay.crt")
	if err := os.WriteFile(path, pemBytes, 0644); err != nil {
		t.Fatal(err)
	}
	fromFile, err := CertSHA256FingerprintFile(path)
	if err != nil {
		t.Fatal(err)
	}
	fromPEM, err := CertSHA256Fingerprint(pemBytes)
	if err != nil {
		t.Fatal(err)
	}
	if fromFile != fromPEM {
		t.Fatalf("file=%s pem=%s", fromFile, fromPEM)
	}
}

func TestPublicHostFromCertSkipsLoopback(t *testing.T) {
	pemBytes, _ := testCertPEM(t)
	if got := PublicHostFromCert(pemBytes); got != "" {
		t.Fatalf("unexpected host %q", got)
	}
}

func TestBuildEnrollURI(t *testing.T) {
	uri := BuildEnrollURI("relay.dshlinks.com", "8444", "invite-code", strings.Repeat("ab", 32), "")
	if !strings.HasPrefix(uri, "dsh-relay://relay.dshlinks.com/?") {
		t.Fatalf("uri=%s", uri)
	}
	if !strings.Contains(uri, "i=invite-code") || !strings.Contains(uri, "fp="+strings.Repeat("ab", 32)) {
		t.Fatalf("uri missing fields: %s", uri)
	}
	if strings.Contains(uri, "c=") {
		t.Fatalf("self-host enroll leaked control URL: %s", uri)
	}
	plain := BuildEnrollURI("relay.dshlinks.com", DefaultAgentPort, "invite-code", "", "")
	if strings.Contains(plain, "fp=") {
		t.Fatalf("public-CA enroll leaked fingerprint: %s", plain)
	}
	if BuildEnrollURI("", "8444", "invite", "", "") != "" {
		t.Fatal("empty host produced a URI")
	}
	hosted := BuildEnrollURI("relay.dshlinks.com", DefaultAgentPort, "invite-code", "", "https://control.example.com/panel")
	if !strings.Contains(hosted, "c=") || !strings.Contains(hosted, "control.example.com") {
		t.Fatalf("hosted enroll missing control URL: %s", hosted)
	}
	if strings.Contains(BuildEnrollURI("relay.dshlinks.com", DefaultAgentPort, "invite-code", "", "http://control.example.com"), "c=") {
		t.Fatal("http control URL must not be packed")
	}
	if strings.Contains(BuildEnrollURI("relay.dshlinks.com", DefaultAgentPort, "invite-code", "", "https://127.0.0.1:8080"), "c=") {
		t.Fatal("loopback control URL must not be packed")
	}
}
