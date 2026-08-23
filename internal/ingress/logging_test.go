package ingress

import (
	"bytes"
	"log"
	"strings"
	"testing"
)

func TestCapabilityVerificationFailureDoesNotLogCompactJWS(t *testing.T) {
	var output bytes.Buffer
	logger := log.New(&output, "", 0)
	capability := "header.tenant-metadata-and-route.signature"
	logCapabilityVerifyFailure(logger, "127.0.0.1", capability)

	got := output.String()
	if strings.Contains(got, capability) || strings.Contains(got, "tenant-metadata") || strings.Contains(got, "header.") {
		t.Fatalf("capability material leaked to log: %q", got)
	}
	if !strings.Contains(got, "len=42") {
		t.Fatalf("sanitized diagnostic missing length: %q", got)
	}
}
