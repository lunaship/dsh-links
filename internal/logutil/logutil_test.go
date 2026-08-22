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
