package store

import (
	"crypto/rand"
	"database/sql"
	"encoding/base64"
	"errors"
	"time"
)

var ErrRenewalReplay = errors.New("renewal replay")

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

// PruneCredentials removes all but the newest keep credentials for a host,
// bounding storage when capabilities are renewed repeatedly.
func (s *Store) PruneCredentials(hostID string, keep int) error {
	if keep < 1 {
		keep = 1
	}
	_, err := s.db.Exec(
		`DELETE FROM credentials WHERE host_id=? AND id NOT IN (
			SELECT id FROM credentials WHERE host_id=? ORDER BY issued_at DESC, id DESC LIMIT ?
		)`, hostID, hostID, keep)
	return err
}

func (s *Store) IsRenewalReplay(replayDigest []byte, now int64) (bool, error) {
	var one int
	err := s.db.QueryRow(`SELECT 1 FROM renewal_replays WHERE digest=? AND expires_at>?`, replayDigest, now).Scan(&one)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return true, nil
}

// RecordRenewal atomically consumes a signed renewal transcript and records
// its new credential. Concurrent or later replay of the same transcript cannot
// create or return another capability.
func (s *Store) RecordRenewal(hostID string, replayDigest, capHash []byte, generation int64, issuedAt, expiresAt, replayExpiresAt int64, keep int) error {
	if keep < 1 {
		keep = 1
	}
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM renewal_replays WHERE expires_at <= ?`, time.Now().Unix()); err != nil {
		return err
	}
	result, err := tx.Exec(`INSERT OR IGNORE INTO renewal_replays(digest, host_id, expires_at) VALUES(?,?,?)`, replayDigest, hostID, replayExpiresAt)
	if err != nil {
		return err
	}
	inserted, err := result.RowsAffected()
	if err != nil {
		return err
	}
	if inserted != 1 {
		return ErrRenewalReplay
	}
	rb := make([]byte, 16)
	if _, err := rand.Read(rb); err != nil {
		return err
	}
	id := base64.RawURLEncoding.EncodeToString(rb)
	if _, err := tx.Exec(`INSERT INTO credentials(id, host_id, capability_hash, generation, issued_at, expires_at) VALUES(?,?,?,?,?,?)`,
		id, hostID, capHash, generation, issuedAt, expiresAt); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`DELETE FROM credentials WHERE host_id=? AND id NOT IN (
			SELECT id FROM credentials WHERE host_id=? ORDER BY issued_at DESC, id DESC LIMIT ?
		)`, hostID, hostID, keep); err != nil {
		return err
	}
	return tx.Commit()
}
