package logutil

import (
	"strings"
	"testing"
)

func TestValueEscapesControlsAndBoundsOutput(t *testing.T) {
	got := Value("host\r\n\x1b[31m")
	if strings.ContainsAny(got, "\r\n\x1b") {
		t.Fatalf("control character reached log output: %q", got)
	}
	if !strings.Contains(got, `\r`) || !strings.Contains(got, `\n`) || !strings.Contains(got, `\x1b`) {
		t.Fatalf("controls were not visibly escaped: %q", got)
	}
	if got := Value(strings.Repeat("a", 1024)); len(got) > maxLogValueBytes {
		t.Fatalf("log output was not bounded: %d", len(got))
	}
}

func TestRedactDiagnosticTextRemovesSecretsAndContent(t *testing.T) {
	raw := `level=error routeSecret=route-value Authorization="Bearer access-token" inviteCode=invite-value message="private message" {"token":"json-token"}`
	got := RedactDiagnosticText(raw)
	for _, forbidden := range []string{"route-value", "access-token", "invite-value", "private message", "json-token"} {
		if strings.Contains(got, forbidden) {
			t.Fatalf("diagnostic leaked %q: %s", forbidden, got)
		}
	}
	if strings.Count(got, "<redacted>") != 5 {
		t.Fatalf("expected five redactions, got %q", got)
	}
}

func TestRedactDiagnosticTextRemovesUnquotedAuthorizationAndBearerValues(t *testing.T) {
	for _, raw := range []string{
		`Authorization: Bearer header-access-token`,
		`authorization=plain-access-token`,
		`request failed: bearer standalone-access-token`,
	} {
		got := RedactDiagnosticText(raw)
		if strings.Contains(got, "access-token") {
			t.Fatalf("diagnostic leaked authorization value: %q", got)
		}
		if !strings.Contains(got, "<redacted>") {
			t.Fatalf("diagnostic did not mark redaction: %q", got)
		}
	}
}
