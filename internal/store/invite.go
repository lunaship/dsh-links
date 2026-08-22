package store

import (
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

type Invite struct {
	ID         string
	UserID     string
	CodeHash   []byte
	ExpiresAt  int64
	ConsumedAt *int64
	RevokedAt  *int64
	CreatedAt  int64
}

// ValidateInvite checks that an invite currently exists and can be consumed
// without mutating it. EnrollHost performs the same checks again inside its
// transaction, so this early gate is safe against races and avoids expensive
// cryptographic and allocation work for invalid codes.
func (s *Store) ValidateInvite(code string) error {
	h := sha256.Sum256([]byte(code))
	var expiresAt int64
	var consumed, revoked sql.NullInt64
	err := s.db.QueryRow(`SELECT expires_at, consumed_at, revoked_at FROM invites WHERE code_hash=?`, h[:]).
		Scan(&expiresAt, &consumed, &revoked)
	if err != nil {
		return err
	}
	if consumed.Valid {
		return ErrInviteConsumed
	}
	if revoked.Valid {
		return ErrInviteRevoked
	}
	if expiresAt <= time.Now().Unix() {
		return ErrInviteExpired
	}
	return nil
}

// EnrollHost atomically consumes an invite and creates the host and its first
// credential. Any validation or database failure leaves the invite reusable.
func (s *Store) EnrollHost(code string, host *Host, capabilityHash []byte, issuedAt, expiresAt int64) error {
	h := sha256.Sum256([]byte(code))
	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var userID string
	var inviteID string
	var inviteExpires int64
	var consumed, revoked sql.NullInt64
	err = tx.QueryRow(`SELECT id, user_id, expires_at, consumed_at, revoked_at FROM invites WHERE code_hash=?`, h[:]).
		Scan(&inviteID, &userID, &inviteExpires, &consumed, &revoked)
	if err != nil {
		return err
	}
	if consumed.Valid {
		return ErrInviteConsumed
	}
	if revoked.Valid {
		return ErrInviteRevoked
	}
	if inviteExpires <= now {
		return ErrInviteExpired
	}

	host.UserID = userID
	host.CreatedAt = now
	if _, err := tx.Exec(`INSERT INTO hosts(id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, created_at) VALUES(?,?,?,?,?,?,?,?,?)`,
		host.ID, host.UserID, host.RouteID, host.HostName, host.HostPubKey, host.Generation, host.MaxStreams, host.Version, host.CreatedAt); err != nil {
		return fmt.Errorf("create host: %w", err)
	}
	credentialIDBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return err
	}
	credentialID := base64.RawURLEncoding.EncodeToString(credentialIDBytes)
	if _, err := tx.Exec(`INSERT INTO credentials(id, host_id, capability_hash, generation, issued_at, expires_at) VALUES(?,?,?,?,?,?)`,
		credentialID, host.ID, capabilityHash, host.Generation, issuedAt, expiresAt); err != nil {
		return fmt.Errorf("create credential: %w", err)
	}
	res, err := tx.Exec(`UPDATE invites SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL`, now, inviteID)
	if err != nil {
		return err
	}
	if n, err := res.RowsAffected(); err != nil || n != 1 {
		if err != nil {
			return err
		}
		return ErrInviteConsumed
	}
	return tx.Commit()
}

// CreateInvite creates a one-time invite, returns code and record.
func (s *Store) CreateInvite(userID string, ttl time.Duration) (code string, rec *Invite, err error) {
	code, err = cryptoutil.GenerateInviteCode()
	if err != nil {
		return "", nil, err
	}
	h := sha256.Sum256([]byte(code))
	codeHash := h[:]
	idBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return "", nil, err
	}
	id := base64.RawURLEncoding.EncodeToString(idBytes)
	now := time.Now().Unix()
	exp := now + int64(ttl.Seconds())
	_, err = s.db.Exec(`INSERT INTO invites(id, user_id, code_hash, expires_at, created_at) VALUES(?,?,?,?,?)`,
		id, userID, codeHash, exp, now)
	if err != nil {
		return "", nil, err
	}
	return code, &Invite{ID: id, UserID: userID, CodeHash: codeHash, ExpiresAt: exp, CreatedAt: now}, nil
}

// ConsumeInvite verifies and consumes invite atomically.
// Returns invite ID if success.
func (s *Store) ConsumeInvite(code string) (*Invite, error) {
	h := sha256.Sum256([]byte(code))
	hash := h[:]
	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var inv Invite
	var consumed sql.NullInt64
	var revoked sql.NullInt64
	err = tx.QueryRow(`SELECT id, user_id, code_hash, expires_at, consumed_at, revoked_at, created_at FROM invites WHERE code_hash=?`, hash).
		Scan(&inv.ID, &inv.UserID, &inv.CodeHash, &inv.ExpiresAt, &consumed, &revoked, &inv.CreatedAt)
	if err != nil {
		return nil, err
	}
	if consumed.Valid {
		return nil, ErrInviteConsumed
	}
	if revoked.Valid {
		return nil, ErrInviteRevoked
	}
	if inv.ExpiresAt < now {
		return nil, ErrInviteExpired
	}
	_, err = tx.Exec(`UPDATE invites SET consumed_at=? WHERE id=?`, now, inv.ID)
	if err != nil {
		return nil, err
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	v := now
	inv.ConsumedAt = &v
	return &inv, nil
}

func (s *Store) ListInvites() ([]Invite, error) {
	rows, err := s.db.Query(`SELECT id, user_id, code_hash, expires_at, consumed_at, revoked_at, created_at FROM invites ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Invite
	for rows.Next() {
		var inv Invite
		var consumed sql.NullInt64
		var revoked sql.NullInt64
		if err := rows.Scan(&inv.ID, &inv.UserID, &inv.CodeHash, &inv.ExpiresAt, &consumed, &revoked, &inv.CreatedAt); err != nil {
			return nil, err
		}
		if consumed.Valid {
			v := consumed.Int64
			inv.ConsumedAt = &v
		}
		if revoked.Valid {
			v := revoked.Int64
			inv.RevokedAt = &v
		}
		out = append(out, inv)
	}
	return out, rows.Err()
}

func (s *Store) RevokeInvite(id string) error {
	now := time.Now().Unix()
	res, err := s.db.Exec(`UPDATE invites SET revoked_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL`, now, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrInviteNotRevocable
	}
	return nil
}

var (
	ErrInviteConsumed     = errStr("invite already consumed")
	ErrInviteRevoked      = errStr("invite revoked")
	ErrInviteExpired      = errStr("invite expired")
	ErrInviteNotRevocable = errStr("invite not revocable")
)

type errStr string

func (e errStr) Error() string { return string(e) }
