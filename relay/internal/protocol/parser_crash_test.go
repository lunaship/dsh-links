package protocol

import (
	"bytes"
	"strings"
	"testing"
)

// Regression battery for the claimed "[]\n crashes checkDuplicateKeys with an
// out-of-bounds panic" finding. Every input must return an error or parse
// cleanly — never panic.
func TestMalformedFramesDoNotPanic(t *testing.T) {
	inputs := []string{
		"[]\n",
		"[]",
		"]\n",
		"[\n",
		"[[]]\n",
		"[{}]\n",
		"[{},{}]\n",
		"{}\n",
		"{\"a\":[]}\n",
		"{\"a\":[[]]}\n",
		"[1,2,3]\n",
		"[null,true,false]\n",
		"[\"s\"]\n",
		"\"bare\"\n",
		"1\n",
		"null\n",
		"true\n",
		"[}]\n",
		"{]\n",
		"[,]\n",
		"{\"a\":1}{\"a\":1}\n",
		"[[[[[[[[[[[]]]]]]]]]]]\n",
		"{\"a\":{\"b\":{\"c\":[]}}}\n",
	}
	for _, in := range inputs {
		t.Run(strings.TrimSpace(in), func(t *testing.T) {
			fr := NewFrameReader(bytes.NewReader([]byte(in)))
			for i := 0; i < 3; i++ {
				if _, err := fr.ReadFrame(2048); err != nil {
					break
				}
			}
			// Also exercise the duplicate-key checker directly.
			_ = checkDuplicateKeys([]byte(strings.TrimSuffix(in, "\n")))
		})
	}
}
