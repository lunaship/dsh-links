package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"time"
)

type Credential struct {
	ID             string
	HostID         string
	CapabilityHash []byte
	Generation     int64
	IssuedAt       int64
	ExpiresAt      int64
	RevokedAt      *int64
}

func (s *Store) CreateCredential(hostID string, capHash []byte, generation int64, issuedAt, expiresAt int64) (*Credential, error) {
	// Generate random ID 16 bytes base64url
	rb := make([]byte, 16)
	if _, err := rand.Read(rb); err != nil {
		return nil, err
	}
	id := base64.RawURLEncoding.EncodeToString(rb)
	if issuedAt == 0 {
		issuedAt = time.Now().Unix()
	}
	c := &Credential{
		ID:             id,
		HostID:         hostID,
		CapabilityHash: capHash,
		Generation:     generation,
		IssuedAt:       issuedAt,
		ExpiresAt:      expiresAt,
	}
	_, err := s.db.Exec(`INSERT INTO credentials(id, host_id, capability_hash, generation, issued_at, expires_at) VALUES(?,?,?,?,?,?)`,
		c.ID, c.HostID, c.CapabilityHash, c.Generation, c.IssuedAt, c.ExpiresAt)
	if err != nil {
		return nil, err
	}
	return c, nil
}

func (s *Store) ListCredentials(hostID string) ([]Credential, error) {
	rows, err := s.db.Query(`SELECT id, host_id, capability_hash, generation, issued_at, expires_at, revoked_at FROM credentials WHERE host_id=?`, hostID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Credential
	for rows.Next() {
		var c Credential
		var revoked sql.NullInt64
		if err := rows.Scan(&c.ID, &c.HostID, &c.CapabilityHash, &c.Generation, &c.IssuedAt, &c.ExpiresAt, &revoked); err != nil {
			return nil, err
		}
		if revoked.Valid {
			v := revoked.Int64
			c.RevokedAt = &v
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
