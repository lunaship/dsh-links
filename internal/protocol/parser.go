package protocol

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"time"
)

// Strict JSON without duplicate keys, without BOM/NUL, LF terminated, max size.

// Limits
const maxLineLength = 4096 // internal buf, actual per-type limits enforced after read

// ErrFrameTooLarge
var (
	ErrTooLarge     = errors.New("frame too large")
	ErrBOM          = errors.New("BOM not allowed")
	ErrNUL          = errors.New("NUL byte not allowed")
	ErrDuplicateKey = errors.New("duplicate JSON key")
)

// FrameReader reads LF-terminated JSON frames.
type FrameReader struct {
	r *bufio.Reader
	// carry holds leftover bytes after READY (for bridge handover)
	carry []byte
}

func NewFrameReader(r io.Reader) *FrameReader {
	return &FrameReader{r: bufio.NewReaderSize(r, 8192)}
}

// Carry returns buffered bytes not yet consumed, for handover to bridge.
func (fr *FrameReader) Carry() []byte {
	return fr.carry
}

// Buffered returns remaining buffered bytes in underlying bufio.
func (fr *FrameReader) Buffered() int {
	return fr.r.Buffered()
}

// ReadFrame reads one line, validates, returns raw JSON bytes (without LF).
// timeout not enforced here; caller sets ReadDeadline on conn.
func (fr *FrameReader) ReadFrame(maxBytes int) ([]byte, error) {
	line, err := fr.r.ReadSlice('\n')
	if errors.Is(err, bufio.ErrBufferFull) {
		return nil, ErrTooLarge
	}
	if err != nil {
		return nil, err
	}
	if len(line) == 0 {
		return nil, errors.New("empty frame")
	}
	// line includes LF
	if line[len(line)-1] != '\n' {
		return nil, errors.New("missing LF terminator")
	}
	raw := line[:len(line)-1] // without LF
	if len(raw) > 0 && raw[len(raw)-1] == '\r' {
		return nil, errors.New("CRLF not allowed")
	}
	// Max per-type
	if maxBytes > 0 && len(raw) > maxBytes {
		return nil, fmt.Errorf("%w: %d > %d", ErrTooLarge, len(raw), maxBytes)
	}
	// BOM
	if len(raw) >= 3 && raw[0] == 0xEF && raw[1] == 0xBB && raw[2] == 0xBF {
		return nil, ErrBOM
	}
	// NUL
	if bytes.Contains(raw, []byte{0x00}) {
		return nil, ErrNUL
	}
	// UTF-8 already implied; json will check
	// Duplicate keys
	if err := checkDuplicateKeys(raw); err != nil {
		return nil, err
	}
	// Save any buffered bytes after this line for later? bufio already holds them.
	// But we need to know if there are extra bytes after LF already buffered.
	// They'll be available via Buffered().
	return bytes.Clone(raw), nil
}

// AfterReady, caller should get remaining buffered data:
// Example: client sent frame + immediately started bridge bytes in same packet, they may be in buffer.
// We expose method to drain buffered bytes.

func (fr *FrameReader) DrainedBuffered() []byte {
	n := fr.r.Buffered()
	if n == 0 {
		return nil
	}
	b := make([]byte, n)
	_, _ = io.ReadFull(fr.r, b)
	return b
}

// checkDuplicateKeys detects duplicate JSON object keys using a two-pass
// state-machine approach. It tracks whether each string token is a key or a
// value by monitoring delimiter context (object vs array) and position
// (after '{' or ',' inside an object = key; otherwise = value).
func checkDuplicateKeys(data []byte) error {
	dec := json.NewDecoder(bytes.NewReader(data))

	type state int
	const (
		objExpectKey   state = iota // next string is a key
		objExpectValue              // next string is a value (after a key)
		arrExpectValue              // inside array
	)

	var stk []state
	var keySets []map[string]struct{}

	for {
		tok, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}

		switch v := tok.(type) {
		case json.Delim:
			switch v {
			case '{':
				stk = append(stk, objExpectKey)
				keySets = append(keySets, make(map[string]struct{}))
			case '}':
				if len(stk) == 0 {
					return errors.New("unexpected }")
				}
				stk = stk[:len(stk)-1]
				keySets = keySets[:len(keySets)-1]
				// After closing an object, parent (if object) now expects a key.
				if len(stk) > 0 && stk[len(stk)-1] == objExpectValue {
					stk[len(stk)-1] = objExpectKey
				}
			case '[':
				stk = append(stk, arrExpectValue)
			case ']':
				if len(stk) == 0 {
					return errors.New("unexpected ]")
				}
				stk = stk[:len(stk)-1]
				if len(stk) > 0 && stk[len(stk)-1] == objExpectValue {
					stk[len(stk)-1] = objExpectKey
				}
			}
		case string:
			if len(stk) == 0 {
				// Bare string at root — not a key, ignore.
				continue
			}
			if stk[len(stk)-1] == objExpectKey {
				// This string is an object key.
				ks := keySets[len(keySets)-1]
				if _, exists := ks[v]; exists {
					return fmt.Errorf("%w: %q", ErrDuplicateKey, v)
				}
				ks[v] = struct{}{}
				stk[len(stk)-1] = objExpectValue
			} else {
				// Value string.
				if len(stk) > 0 && stk[len(stk)-1] == objExpectValue {
					stk[len(stk)-1] = objExpectKey
				}
			}
		case float64, bool, nil:
			// Value token — if we were in an object expecting a value,
			// the next string (if any) will be a key.
			if len(stk) > 0 && stk[len(stk)-1] == objExpectValue {
				stk[len(stk)-1] = objExpectKey
			}
		}
	}
	return nil
}

// Validate helper for ts skew
func ValidateTs(ts int64, now time.Time) error {
	nowSec := now.Unix()
	diff := ts - nowSec
	if diff < -60 || diff > 60 {
		return fmt.Errorf("ts skew too large: ts=%d now=%d diff=%d", ts, nowSec, diff)
	}
	return nil
}

// EnforceMaxSize picks limit per type
func MaxForType(t string) int {
	switch t {
	case TypeHello:
		return MaxHello
	case TypeConnect:
		return MaxConnect
	case TypeBind:
		return MaxBind
	case TypeReady:
		return MaxReady
	case TypeError:
		return MaxError
	case TypeRegister:
		return MaxRegister
	case TypeEnroll:
		return MaxEnroll
	case TypeRenew:
		return MaxRenew
	case TypeOpen:
		return MaxOpen
	case TypePing, TypePong:
		return MaxPingPong
	case TypeEnrolled:
		return MaxEnrolled
	case TypeRegistered:
		return MaxRegistered
	case TypeRenewed:
		return MaxRenewed
	default:
		return 2048
	}
}
