package logutil

import (
	"strconv"
	"strings"
	"unicode/utf8"
)

const maxLogValueBytes = 256

// Value renders untrusted text as a single safe log field. It preserves
// printable UTF-8 while escaping control characters and bounding output size.
func Value(value string) string {
	if len(value) > maxLogValueBytes {
		value = value[:maxLogValueBytes]
	}
	var b strings.Builder
	for len(value) > 0 {
		r, size := utf8.DecodeRuneInString(value)
		if r == utf8.RuneError && size == 1 {
			b.WriteString(`\x`)
			b.WriteString(strings.ToUpper(strconv.FormatInt(int64(value[0]), 16)))
			value = value[1:]
			continue
		}
		value = value[size:]
		if r < 0x20 || r == 0x7f || (r >= 0x80 && !strconv.IsPrint(r)) {
			b.WriteString(strconv.QuoteRuneToASCII(r))
			continue
		}
		b.WriteRune(r)
	}
	return b.String()
}
