package cryptoutil

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/binary"
)

// BuildMAC transcript for CONNECT/BIND.
// Layout:
// ASCII("DLR/1") 0x00 ASCII(op) 0x00 routeId(16) streamId_or_empty(16) generation_or_empty(8) BE64(ts) nonce(16) challenge(32)

func BuildMACTranscript(op string, routeId, streamId []byte, generation *uint64, ts int64, nonce, challenge []byte) []byte {
	// op is "CONNECT" or "BIND" or "REGISTER"? For HMAC only CONNECT/BIND.
	buf := make([]byte, 0, 128)
	buf = append(buf, []byte("DLR/1")...)
	buf = append(buf, 0x00)
	buf = append(buf, []byte(op)...)
	buf = append(buf, 0x00)
	buf = append(buf, routeId...)
	if streamId != nil {
		buf = append(buf, streamId...)
	}
	if generation != nil {
		genBytes := make([]byte, 8)
		binary.BigEndian.PutUint64(genBytes, *generation)
		buf = append(buf, genBytes...)
	}
	tsBytes := make([]byte, 8)
	binary.BigEndian.PutUint64(tsBytes, uint64(ts))
	buf = append(buf, tsBytes...)
	buf = append(buf, nonce...)
	buf = append(buf, challenge...)
	return buf
}

// ComputeMAC computes HMAC-SHA256(routeSecret, transcript)
func ComputeMAC(routeSecret []byte, transcript []byte) []byte {
	m := hmac.New(sha256.New, routeSecret)
	_, _ = m.Write(transcript)
	return m.Sum(nil)
}

// VerifyMAC constant time.
func VerifyMAC(routeSecret, transcript, mac []byte) bool {
	expected := ComputeMAC(routeSecret, transcript)
	return hmac.Equal(expected, mac)
}

// ENROLL transcript:
// ASCII("DLR/1\x00ENROLL\x00") || SHA256(inviteCode) || U16BE(len hostId) || hostId || hostPubKey(32) || BE64(ts) || nonce(16) || challenge(32)

func BuildEnrollTranscript(inviteCode string, hostId string, hostPubKey []byte, ts int64, nonce, challenge []byte) []byte {
	buf := make([]byte, 0, 256)
	buf = append(buf, []byte("DLR/1")...)
	buf = append(buf, 0x00)
	buf = append(buf, []byte("ENROLL")...)
	buf = append(buf, 0x00)
	h := sha256.Sum256([]byte(inviteCode))
	buf = append(buf, h[:]...)
	// U16BE hostId length
	hid := []byte(hostId)
	ln := make([]byte, 2)
	binary.BigEndian.PutUint16(ln, uint16(len(hid)))
	buf = append(buf, ln...)
	buf = append(buf, hid...)
	buf = append(buf, hostPubKey...)
	tsB := make([]byte, 8)
	binary.BigEndian.PutUint64(tsB, uint64(ts))
	buf = append(buf, tsB...)
	buf = append(buf, nonce...)
	buf = append(buf, challenge...)
	return buf
}

// REGISTER transcript:
// ASCII("DLR/1\x00REGISTER\x00") || SHA256(capability_compact) || BE64(ts) || nonce(16) || challenge(32)

func BuildRegisterTranscript(capabilityCompact string, ts int64, nonce, challenge []byte) []byte {
	buf := make([]byte, 0, 256)
	buf = append(buf, []byte("DLR/1")...)
	buf = append(buf, 0x00)
	buf = append(buf, []byte("REGISTER")...)
	buf = append(buf, 0x00)
	h := sha256.Sum256([]byte(capabilityCompact))
	buf = append(buf, h[:]...)
	tsB := make([]byte, 8)
	binary.BigEndian.PutUint64(tsB, uint64(ts))
	buf = append(buf, tsB...)
	buf = append(buf, nonce...)
	buf = append(buf, challenge...)
	return buf
}

// RENEW transcript:
// ASCII("DLR/1\x00RENEW\x00") || SHA256(old_capability_compact) || BE64(ts) || nonce(16) || challenge(32)

func BuildRenewTranscript(oldCapabilityCompact string, ts int64, nonce, challenge []byte) []byte {
	buf := make([]byte, 0, 256)
	buf = append(buf, []byte("DLR/1")...)
	buf = append(buf, 0x00)
	buf = append(buf, []byte("RENEW")...)
	buf = append(buf, 0x00)
	h := sha256.Sum256([]byte(oldCapabilityCompact))
	buf = append(buf, h[:]...)
	tsB := make([]byte, 8)
	binary.BigEndian.PutUint64(tsB, uint64(ts))
	buf = append(buf, tsB...)
	buf = append(buf, nonce...)
	buf = append(buf, challenge...)
	return buf
}
