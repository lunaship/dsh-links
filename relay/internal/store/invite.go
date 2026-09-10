package store

import (
	"bytes"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
)

const (
	DefaultInviteTTL = 8 * time.Hour
	MinInviteTTL     = time.Minute
	MaxInviteTTL     = 24 * time.Hour
)

type Invite struct {
	ID               string
	UserID           string
	LoginName        string
	CodeHash         []byte
	ExpiresAt        int64
	ConsumedAt       *int64
	ConsumedHostID   string
	ConsumedHostName string
	ConsumedHostLive bool
	RevokedAt        *int64
	CreatedAt        int64
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

// EnrollMaterial is produced after generation is assigned inside EnrollHost.
type EnrollMaterial struct {
	CapabilityHash []byte
	IssuedAt       int64
	ExpiresAt      int64
}

const enrollCASAttempts = 8

var errGenerationConflict = errStr("enroll generation conflict")

func isRetryableEnroll(err error) bool {
	if errors.Is(err, errGenerationConflict) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "database is locked") || strings.Contains(msg, "sqlite_busy")
}

// EnrollHost atomically consumes an invite, assigns the next generation inside
// the transaction, and creates the host credential. replacedRouteID is the
// prior route for a rebind (nil on first enroll). Any validation or database
// failure leaves the invite reusable.
func (s *Store) EnrollHost(code string, host *Host, materialize func(generation int64) (*EnrollMaterial, error)) (replacedRouteID []byte, err error) {
	// A deferred SQLite transaction reads the invite/host rows before it
	// writes them. With multiple database/sql connections, concurrent
	// enrollments can therefore hold shared locks and deadlock when they all
	// attempt the read-to-write promotion. Enrollment is infrequent and must
	// assign a unique generation, so serialize this transaction in-process.
	s.enrollMu.Lock()
	defer s.enrollMu.Unlock()

	var last error
	for attempt := 0; attempt < enrollCASAttempts; attempt++ {
		replacedRouteID, last = s.enrollHostOnce(code, host, materialize)
		if last == nil {
			return replacedRouteID, nil
		}
		if !isRetryableEnroll(last) {
			return nil, last
		}
	}
	return nil, last
}

func (s *Store) enrollHostOnce(code string, host *Host, materialize func(generation int64) (*EnrollMaterial, error)) ([]byte, error) {
	h := sha256.Sum256([]byte(code))
	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	var userID string
	var inviteID string
	var inviteExpires, inviteCreated int64
	var consumed, revoked sql.NullInt64
	err = tx.QueryRow(`SELECT id, user_id, expires_at, created_at, consumed_at, revoked_at FROM invites WHERE code_hash=?`, h[:]).
		Scan(&inviteID, &userID, &inviteExpires, &inviteCreated, &consumed, &revoked)
	if err != nil {
		return nil, err
	}
	if consumed.Valid {
		return nil, ErrInviteConsumed
	}
	if revoked.Valid {
		return nil, ErrInviteRevoked
	}
	if inviteExpires <= now {
		return nil, ErrInviteExpired
	}

	host.UserID = userID
	host.CreatedAt = now
	var replacedRouteID []byte
	var existingPub, existingRoute []byte
	var existingGen int64
	var existingRevoked sql.NullInt64
	err = tx.QueryRow(`SELECT host_pubkey, route_id, generation, revoked_at FROM hosts WHERE id=?`, host.ID).
		Scan(&existingPub, &existingRoute, &existingGen, &existingRevoked)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		var otherID string
		err = tx.QueryRow(`SELECT id FROM hosts WHERE host_pubkey=?`, host.HostPubKey).Scan(&otherID)
		if err == nil {
			return nil, errStr("host key already registered")
		}
		if err != nil && !errors.Is(err, sql.ErrNoRows) {
			return nil, err
		}
		if err := s.enforceTenantHostLimitTx(tx, userID); err != nil {
			return nil, s.finishHostQuota(tx, inviteID, inviteCreated, inviteExpires, now, err)
		}
		host.Generation = 1
		if _, err := tx.Exec(`INSERT INTO hosts(id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, created_at) VALUES(?,?,?,?,?,?,?,?,?)`,
			host.ID, host.UserID, host.RouteID, host.HostName, host.HostPubKey, host.Generation, host.MaxStreams, host.Version, host.CreatedAt); err != nil {
			return nil, fmt.Errorf("create host: %w", err)
		}
	case err != nil:
		return nil, err
	default:
		if !bytes.Equal(existingPub, host.HostPubKey) {
			return nil, errStr("host id already registered")
		}
		if existingRevoked.Valid {
			if err := s.enforceTenantHostLimitTx(tx, userID); err != nil {
				return nil, s.finishHostQuota(tx, inviteID, inviteCreated, inviteExpires, now, err)
			}
		}
		host.Generation = existingGen + 1
		res, err := tx.Exec(`UPDATE hosts SET user_id=?, route_id=?, host_name=?, generation=?, max_streams=?, version=?, revoked_at=NULL WHERE id=? AND generation=?`,
			host.UserID, host.RouteID, host.HostName, host.Generation, host.MaxStreams, host.Version, host.ID, existingGen)
		if err != nil {
			return nil, fmt.Errorf("rebind host: %w", err)
		}
		n, err := res.RowsAffected()
		if err != nil {
			return nil, err
		}
		if n != 1 {
			return nil, errGenerationConflict
		}
		replacedRouteID = append([]byte(nil), existingRoute...)
	}
	material, err := materialize(host.Generation)
	if err != nil {
		return nil, err
	}
	if material == nil || len(material.CapabilityHash) == 0 {
		return nil, errStr("missing enroll material")
	}
	credentialIDBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return nil, err
	}
	credentialID := base64.RawURLEncoding.EncodeToString(credentialIDBytes)
	if _, err := tx.Exec(`INSERT INTO credentials(id, host_id, capability_hash, generation, issued_at, expires_at) VALUES(?,?,?,?,?,?)`,
		credentialID, host.ID, material.CapabilityHash, host.Generation, material.IssuedAt, material.ExpiresAt); err != nil {
		return nil, fmt.Errorf("create credential: %w", err)
	}
	consumedName := strings.TrimSpace(host.HostName)
	if consumedName == "" {
		consumedName = host.ID
	}
	res, err := tx.Exec(`UPDATE invites SET consumed_at=?, consumed_host_id=?, consumed_host_name=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL`, now, host.ID, consumedName, inviteID)
	if err != nil {
		return nil, err
	}
	if n, err := res.RowsAffected(); err != nil || n != 1 {
		if err != nil {
			return nil, err
		}
		return nil, ErrInviteConsumed
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return replacedRouteID, nil
}

// inviteQuotaHoldUntil extends a short unused invite so a quota-blocked paste
// survives the revoke-and-retry wait. Never past created_at + MaxInviteTTL.
func inviteQuotaHoldUntil(createdAt, expiresAt, now int64) int64 {
	if createdAt <= 0 || now <= 0 {
		return 0
	}
	hold := now + int64(DefaultInviteTTL/time.Second)
	maxUntil := createdAt + int64(MaxInviteTTL/time.Second)
	if hold > maxUntil {
		hold = maxUntil
	}
	if hold <= now || hold <= expiresAt {
		return 0
	}
	return hold
}

func (s *Store) finishHostQuota(tx *sql.Tx, inviteID string, createdAt, expiresAt, now int64, quotaErr error) error {
	if !errors.Is(quotaErr, ErrTenantHostLimit) {
		return quotaErr
	}
	until := inviteQuotaHoldUntil(createdAt, expiresAt, now)
	if until == 0 {
		return quotaErr
	}
	if _, err := tx.Exec(`UPDATE invites SET expires_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL`, until, inviteID); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	return quotaErr
}

// CreateInvite creates a one-time invite, returns code and record.
func (s *Store) CreateInvite(userID string, ttl time.Duration) (code string, rec *Invite, err error) {
	code, rec, _, err = s.createInvite(userID, ttl, false)
	return code, rec, err
}

// CreateInviteReplacingOldestUnused mints a new unused code for a tenant who
// is already at the unused-invite cap by voiding the oldest live unused
// invite first. Self-host admin is uncapped and ignores the replace flag.
func (s *Store) CreateInviteReplacingOldestUnused(userID string, ttl time.Duration) (code string, rec *Invite, replaced *Invite, err error) {
	return s.createInvite(userID, ttl, true)
}

func (s *Store) createInvite(userID string, ttl time.Duration, replaceOldestUnused bool) (code string, rec *Invite, replaced *Invite, err error) {
	code, err = cryptoutil.GenerateInviteCode()
	if err != nil {
		return "", nil, nil, err
	}
	h := sha256.Sum256([]byte(code))
	codeHash := h[:]
	idBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return "", nil, nil, err
	}
	id := base64.RawURLEncoding.EncodeToString(idBytes)
	now := time.Now().Unix()
	exp := now + int64(ttl.Seconds())

	tx, err := s.db.Begin()
	if err != nil {
		return "", nil, nil, err
	}
	defer tx.Rollback()

	var role string
	err = tx.QueryRow(`SELECT COALESCE(role,'admin') FROM users WHERE id=?`, userID).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil, nil, ErrUserNotFound
	}
	if err != nil {
		return "", nil, nil, err
	}
	if role == RoleTenant {
		var n int
		if err := tx.QueryRow(
			`SELECT COUNT(*) FROM invites WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?`,
			userID, now,
		).Scan(&n); err != nil {
			return "", nil, nil, err
		}
		if n >= s.tenantMaxUnusedInvites {
			if !replaceOldestUnused {
				return "", nil, nil, ErrTenantInviteLimit
			}
			var oldID string
			var oldCreated int64
			err := tx.QueryRow(
				`SELECT id, created_at FROM invites WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ? ORDER BY created_at ASC, rowid ASC LIMIT 1`,
				userID, now,
			).Scan(&oldID, &oldCreated)
			if errors.Is(err, sql.ErrNoRows) {
				return "", nil, nil, ErrTenantInviteLimit
			}
			if err != nil {
				return "", nil, nil, err
			}
			res, err := tx.Exec(`UPDATE invites SET revoked_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL`, now, oldID)
			if err != nil {
				return "", nil, nil, err
			}
			affected, _ := res.RowsAffected()
			if affected == 0 {
				return "", nil, nil, ErrTenantInviteLimit
			}
			replaced = &Invite{ID: oldID, UserID: userID, CreatedAt: oldCreated, RevokedAt: &now}
		}
	}

	if _, err := tx.Exec(`INSERT INTO invites(id, user_id, code_hash, expires_at, created_at) VALUES(?,?,?,?,?)`,
		id, userID, codeHash, exp, now); err != nil {
		return "", nil, nil, err
	}
	if err := tx.Commit(); err != nil {
		return "", nil, nil, err
	}
	return code, &Invite{ID: id, UserID: userID, CodeHash: codeHash, ExpiresAt: exp, CreatedAt: now}, replaced, nil
}

func (s *Store) GetInvite(id string) (*Invite, error) {
	var inv Invite
	var consumed, revoked sql.NullInt64
	var live int64
	err := s.db.QueryRow(`SELECT invites.id, invites.user_id, invites.code_hash, invites.expires_at, invites.consumed_at, invites.revoked_at, invites.created_at, COALESCE(invites.consumed_host_id,''), COALESCE(NULLIF(TRIM(hosts.host_name), ''), NULLIF(TRIM(invites.consumed_host_name), ''), ''), CASE WHEN hosts.id IS NOT NULL AND hosts.revoked_at IS NULL THEN 1 ELSE 0 END FROM invites LEFT JOIN hosts ON hosts.id = invites.consumed_host_id WHERE invites.id=?`, id).
		Scan(&inv.ID, &inv.UserID, &inv.CodeHash, &inv.ExpiresAt, &consumed, &revoked, &inv.CreatedAt, &inv.ConsumedHostID, &inv.ConsumedHostName, &live)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrInviteNotFound
	}
	if err != nil {
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
	inv.ConsumedHostLive = live != 0
	return &inv, nil
}

const inviteListQuery = `SELECT invites.id, invites.user_id, COALESCE(users.login_name,''), invites.code_hash, invites.expires_at, invites.consumed_at, invites.revoked_at, invites.created_at, COALESCE(invites.consumed_host_id,''), COALESCE(NULLIF(TRIM(hosts.host_name), ''), NULLIF(TRIM(invites.consumed_host_name), ''), ''), CASE WHEN hosts.id IS NOT NULL AND hosts.revoked_at IS NULL THEN 1 ELSE 0 END FROM invites LEFT JOIN users ON users.id = invites.user_id LEFT JOIN hosts ON hosts.id = invites.consumed_host_id`

func (s *Store) ListInvites() ([]Invite, error) {
	return s.listInvitesQuery(inviteListQuery + ` ORDER BY invites.created_at DESC`)
}

func (s *Store) ListInvitesByUser(userID string) ([]Invite, error) {
	return s.listInvitesQuery(inviteListQuery+` WHERE invites.user_id=? ORDER BY invites.created_at DESC`, userID)
}

func (s *Store) listInvitesQuery(q string, args ...any) ([]Invite, error) {
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Invite
	for rows.Next() {
		var inv Invite
		var consumed sql.NullInt64
		var revoked sql.NullInt64
		var live int64
		if err := rows.Scan(&inv.ID, &inv.UserID, &inv.LoginName, &inv.CodeHash, &inv.ExpiresAt, &consumed, &revoked, &inv.CreatedAt, &inv.ConsumedHostID, &inv.ConsumedHostName, &live); err != nil {
			return nil, err
		}
		inv.ConsumedHostLive = live != 0
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

// RevokeUnusedInvitesByUser voids unused invites for a tenant kill switch.
func (s *Store) RevokeUnusedInvitesByUser(userID string) (int64, error) {
	res, err := s.db.Exec(
		`UPDATE invites SET revoked_at=? WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL`,
		nowSec(), userID,
	)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
}

func (s *Store) DeleteInvite(id string) error {
	res, err := s.db.Exec(`DELETE FROM invites WHERE id=?`, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrInviteNotFound
	}
	return nil
}

func (s *Store) PurgeStaleInvites(now int64) (int64, error) {
	return s.purgeStaleInvites("", now)
}

// PurgeStaleInvitesByUser removes one tenant's revoked, expired-unused, or
// consumed invites whose computer no longer occupies a slot. Live unused
// codes and consumed occupying mappings are kept. Empty userID is a no-op.
func (s *Store) PurgeStaleInvitesByUser(userID string, now int64) (int64, error) {
	if userID == "" {
		return 0, nil
	}
	return s.purgeStaleInvites(userID, now)
}

func (s *Store) purgeStaleInvites(userID string, now int64) (int64, error) {
	if now <= 0 {
		now = time.Now().Unix()
	}
	// Keep unused live codes, and consumed codes whose computer still
	// occupies a slot (Control invite-row revoke uses that mapping).
	q := `DELETE FROM invites WHERE (
  revoked_at IS NOT NULL
  OR (consumed_at IS NULL AND expires_at < ?)
  OR (consumed_at IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM hosts h WHERE h.id = invites.consumed_host_id AND h.revoked_at IS NULL
  ))
)`
	args := []any{now}
	if userID != "" {
		q += ` AND user_id=?`
		args = append(args, userID)
	}
	res, err := s.db.Exec(q, args...)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	return n, nil
}

var (
	ErrInviteConsumed     = errStr("invite already consumed")
	ErrInviteRevoked      = errStr("invite revoked")
	ErrInviteExpired      = errStr("invite expired")
	ErrInviteNotRevocable = errStr("invite not revocable")
	ErrInviteNotFound     = errStr("invite not found")
	ErrHostNotFound       = errStr("host not found")
)

type errStr string

func (e errStr) Error() string { return string(e) }
