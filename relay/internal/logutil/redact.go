package logutil

import "regexp"

// DiagnosticText removes values that must never leave a tester's machine.
// Diagnostic exports should use this helper on each line and must otherwise
// be built from the explicit allowlist in deploy/collect-diagnostics.sh.
var (
	authorizationSecret = regexp.MustCompile(`(?i)("?authorization"?\s*[=:]\s*)("[^"]*"|'[^']*'|bearer\s+[^\s,}]+|[^\s,}]+)`)
	bearerSecret        = regexp.MustCompile(`(?i)(\bbearer\s+)[A-Za-z0-9._~+/=-]+`)
	diagnosticSecret    = regexp.MustCompile(`(?i)("?(token|route[_-]?secret|invite(code)?|private[_-]?key|password|cookie|message|prompt|body)"?)(\s*[=:]\s*)("[^"]*"|'[^']*'|[^\s,}]+)`)
)

func RedactDiagnosticText(line string) string {
	line = authorizationSecret.ReplaceAllString(line, `$1<redacted>`)
	line = bearerSecret.ReplaceAllString(line, `$1<redacted>`)
	return diagnosticSecret.ReplaceAllString(line, `$1$4<redacted>`)
}
