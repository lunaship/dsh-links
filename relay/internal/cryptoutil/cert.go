package cryptoutil

import (
	"bytes"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"fmt"
	"net"
	"net/url"
	"os"
	"strings"
)

const DefaultAgentPort = "8444"

// CertSHA256Fingerprint returns the lowercase hex SHA-256 of the leaf
// certificate DER. This matches Node's X509Certificate.fingerprint256
// without colons, which the plugin pins for self-signed Relay TLS.
func CertSHA256Fingerprint(pemBytes []byte) (string, error) {
	cert, err := ParseLeafCert(pemBytes)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(cert.Raw)
	return hex.EncodeToString(sum[:]), nil
}

// CertSHA256FingerprintFile reads a PEM certificate from path and returns
// its SHA-256 fingerprint. Missing or unreadable files return an error;
// callers that treat the fingerprint as optional should ignore that error.
func CertSHA256FingerprintFile(path string) (string, error) {
	path = strings.TrimSpace(path)
	if path == "" {
		return "", fmt.Errorf("certificate path is empty")
	}
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return CertSHA256Fingerprint(b)
}

func ParseLeafCert(pemBytes []byte) (*x509.Certificate, error) {
	block, _ := pem.Decode(pemBytes)
	if block == nil || block.Type != "CERTIFICATE" || len(block.Bytes) == 0 {
		return nil, fmt.Errorf("no certificate PEM")
	}
	cert, err := x509.ParseCertificate(block.Bytes)
	if err != nil {
		return nil, fmt.Errorf("parse certificate: %w", err)
	}
	return cert, nil
}

func CertIsSelfSigned(cert *x509.Certificate) bool {
	if cert == nil {
		return false
	}
	return bytes.Equal(cert.RawIssuer, cert.RawSubject)
}

// PublicHostFromCert returns the first non-loopback DNS SAN, else the first
// non-loopback IP. Localhost names are skipped so enroll URIs point at a
// host the plugin can actually reach.
func PublicHostFromCert(pemBytes []byte) string {
	cert, err := ParseLeafCert(pemBytes)
	if err != nil {
		return ""
	}
	for _, name := range cert.DNSNames {
		name = strings.TrimSpace(name)
		if name == "" || strings.EqualFold(name, "localhost") {
			continue
		}
		return name
	}
	for _, ip := range cert.IPAddresses {
		if ip == nil || ip.IsLoopback() {
			continue
		}
		return ip.String()
	}
	return ""
}

// BuildEnrollURI packs host, one-time invite, and optional SHA-256 pin into
// a single paste token for the plugin. Fingerprint is omitted for public-CA
// certificates so the plugin uses system trust.
func BuildEnrollURI(host, agentPort, invite, fingerprint string) string {
	host = strings.TrimSpace(host)
	invite = strings.TrimSpace(invite)
	if host == "" || invite == "" {
		return ""
	}
	port := strings.TrimSpace(agentPort)
	if port == "" || port == DefaultAgentPort {
		port = ""
	}
	u := url.URL{Scheme: "dsh-relay", Path: "/"}
	if port != "" {
		u.Host = net.JoinHostPort(host, port)
	} else if ip := net.ParseIP(host); ip != nil && ip.To4() == nil {
		u.Host = "[" + host + "]"
	} else {
		u.Host = host
	}
	query := url.Values{}
	query.Set("i", invite)
	fp := strings.ToLower(strings.TrimSpace(fingerprint))
	if len(fp) == 64 {
		query.Set("fp", fp)
	}
	u.RawQuery = query.Encode()
	return u.String()
}
