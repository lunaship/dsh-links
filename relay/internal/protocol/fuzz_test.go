package protocol

import (
	"bytes"
	"testing"
)

func FuzzFrameReader(f *testing.F) {
	// Seed with valid frames
	f.Add([]byte(`{"type":"HELLO","v":1,"challenge":"AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"}` + "\n"))
	f.Add([]byte(`{"type":"PING"}` + "\n"))
	f.Add([]byte(`{"type":"CONNECT","route":"AQIDBAUGBwgJCgsMDQ4PEA","ts":1787300000,"nonce":"qrvM3e7_ABEiM0RVZneImQ","mac":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}` + "\n"))
	f.Add([]byte("not json\n"))
	f.Add([]byte("\x00\x01\x02\n"))

	f.Fuzz(func(t *testing.T, data []byte) {
		// Ensure we don't panic
		fr := NewFrameReader(bytes.NewReader(data))
		for i := 0; i < 5; i++ {
			_, err := fr.ReadFrame(2048)
			if err != nil {
				break
			}
		}
	})
}

func FuzzDuplicateKeys(f *testing.F) {
	f.Add([]byte(`{"a":1,"a":2}`))
	f.Add([]byte(`{"type":"HELLO","v":1,"v":2}`))
	f.Fuzz(func(t *testing.T, data []byte) {
		_ = checkDuplicateKeys(data)
	})
}
