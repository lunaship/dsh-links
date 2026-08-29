package store

import (
	"crypto/ed25519"
	"database/sql"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/dsh-links/dsh-links-relay/internal/cryptoutil"
)

// ErrDeviceDisabled is returned when an enrolled device has been disabled by
// the operator.
var ErrDeviceDisabled = errors.New("device disabled")

// ErrDeviceNotAllowed is returned when anonymous enrollment is switched off.
var ErrDeviceNotAllowed = errors.New("anonymous enrollment disabled")

// ErrDeviceHostLimit is returned when the device already reached its host
// quota.
var ErrDeviceHostLimit = errors.New("device host limit reached")

// Device is an anonymous identity: the fingerprint of an Ed25519 public key
// presented at bootstrap time. Devices are the rate-limit, quota and
// revocation subjects of phase-2 self-service enrollment.
type Device struct {
	ID         string
	PublicKey  []byte
	Enabled    bool
	MaxHosts   int
	CreatedAt  time.Time
	DisabledAt *time.Time
}

// DeviceFingerprint returns the hex fingerprint used as the device ID.
func DeviceFingerprint(pub ed25519.PublicKey) string {
	return hex.EncodeToString(pub)
}

// GetDevice returns the device record by fingerprint ID.
func (s *Store) GetDevice(id string) (*Device, error) {
	row := s.db.QueryRow(
		`SELECT id, public_key, enabled, max_hosts, created_at, disabled_at
		 FROM devices WHERE id = ?`, id)
	var d Device
	var enabled int
	var created int64
	var disabledAt sql.NullInt64
	err := row.Scan(&d.ID, &d.PublicKey, &enabled, &d.MaxHosts, &created, &disabledAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, sql.ErrNoRows
		}
		return nil, err
	}
	d.Enabled = enabled != 0
	d.CreatedAt = time.Unix(created, 0)
	if disabledAt.Valid {
		t := time.Unix(disabledAt.Int64, 0)
		d.DisabledAt = &t
	}
	return &d, nil
}

// GetDeviceByPubKey returns the device record by its public key.
func (s *Store) GetDeviceByPubKey(pub []byte) (*Device, error) {
	row := s.db.QueryRow(
		`SELECT id, public_key, enabled, max_hosts, created_at, disabled_at
		 FROM devices WHERE public_key = ?`, pub)
	var d Device
	var enabled int
	var created int64
	var disabledAt sql.NullInt64
	err := row.Scan(&d.ID, &d.PublicKey, &enabled, &d.MaxHosts, &created, &disabledAt)
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, sql.ErrNoRows
		}
		return nil, err
	}
	d.Enabled = enabled != 0
	d.CreatedAt = time.Unix(created, 0)
	if disabledAt.Valid {
		t := time.Unix(disabledAt.Int64, 0)
		d.DisabledAt = &t
	}
	return &d, nil
}

// EnsureDevice registers an anonymous device on first sight (bootstrap or
// enrollment). If the device already exists its record is returned unchanged.
func (s *Store) EnsureDevice(pub ed25519.PublicKey, maxHosts int) (*Device, error) {
	id := DeviceFingerprint(pub)
	if d, err := s.GetDevice(id); err == nil {
		return d, nil
	}
	now := time.Now().Unix()
	if _, err := s.db.Exec(
		`INSERT OR IGNORE INTO devices (id, public_key, enabled, max_hosts, created_at)
		 VALUES (?, ?, 1, ?, ?)`, id, []byte(pub), maxHosts, now); err != nil {
		return nil, err
	}
	return s.GetDevice(id)
}

// SetDeviceEnabled flips the device's enabled bit. Disabling cascades to all
// of the device's hosts at the control layer (revoke each host).
func (s *Store) SetDeviceEnabled(id string, enabled bool) error {
	if enabled {
		_, err := s.db.Exec(
			`UPDATE devices SET enabled = 1, disabled_at = NULL WHERE id = ?`, id)
		return err
	}
	_, err := s.db.Exec(
		`UPDATE devices SET enabled = 0, disabled_at = ? WHERE id = ?`, time.Now().Unix(), id)
	return err
}

// DeleteDevice removes the device row. Callers must revoke/delete its hosts
// first (or accept that orphaned hosts remain until revoked).
func (s *Store) DeleteDevice(id string) error {
	_, err := s.db.Exec(`DELETE FROM devices WHERE id = ?`, id)
	return err
}

// CountHostsForDevice returns the number of hosts linked to a device.
func (s *Store) CountHostsForDevice(id string) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM hosts WHERE device_id = ?`, id).Scan(&n)
	return n, err
}

// ListDevices returns all devices ordered by creation time (newest first).
func (s *Store) ListDevices() ([]*Device, error) {
	rows, err := s.db.Query(
		`SELECT id, public_key, enabled, max_hosts, created_at, disabled_at
		 FROM devices ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Device
	for rows.Next() {
		var d Device
		var enabled int
		var created int64
		var disabledAt sql.NullInt64
		if err := rows.Scan(&d.ID, &d.PublicKey, &enabled, &d.MaxHosts, &created, &disabledAt); err != nil {
			return nil, err
		}
		d.Enabled = enabled != 0
		d.CreatedAt = time.Unix(created, 0)
		if disabledAt.Valid {
			t := time.Unix(disabledAt.Int64, 0)
			d.DisabledAt = &t
		}
		out = append(out, &d)
	}
	return out, rows.Err()
}

// LinkHostToDevice attaches an existing host record to a device.
func (s *Store) LinkHostToDevice(hostID, deviceID string) error {
	_, err := s.db.Exec(`UPDATE hosts SET device_id = ? WHERE id = ?`, deviceID, hostID)
	return err
}

// ---- settings (operator runtime switches, e.g. the anonymous kill switch) ----

// GetSetting returns the stored value for key or "" when absent.
func (s *Store) GetSetting(key string) (string, error) {
	var v string
	err := s.db.QueryRow(`SELECT value FROM settings WHERE key = ?`, key).Scan(&v)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return v, err
}

// SetSetting upserts a runtime setting.
func (s *Store) SetSetting(key, value string) error {
	_, err := s.db.Exec(
		`INSERT INTO settings (key, value) VALUES (?, ?)
		 ON CONFLICT(key) DO UPDATE SET value = excluded.value`, key, value)
	return err
}
// EnrollAnonymousHost atomically enrolls a host for an anonymous device.
// The device quota is enforced inside the same serialized transaction used by
// invite enrollment, so concurrent self-service enrollments cannot overshoot
// maxHosts. The host is linked to the built-in user (anonymous enrollment is
// subject-based, not account-based) and to the device row via device_id.
func (s *Store) EnrollAnonymousHost(deviceID string, host *Host, maxHosts int, materialize func(generation int64) (*EnrollMaterial, error)) error {
	s.enrollMu.Lock()
	defer s.enrollMu.Unlock()

	now := time.Now().Unix()
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	var enabled int
	if err := tx.QueryRow(`SELECT enabled FROM devices WHERE id = ?`, deviceID).Scan(&enabled); err != nil {
		return err
	}
	if enabled != 1 {
		return ErrDeviceDisabled
	}
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM hosts WHERE device_id = ? AND revoked_at IS NULL`, deviceID).Scan(&n); err != nil {
		return err
	}
	if n >= maxHosts {
		return ErrDeviceHostLimit
	}
	// host ID / key / route must be unique in this transaction.
	var exists string
	err = tx.QueryRow(`SELECT id FROM hosts WHERE id = ? OR host_pubkey = ? OR route_id = ?`,
		host.ID, host.HostPubKey, host.RouteID).Scan(&exists)
	if err == nil {
		return errors.New("host id, pubkey or route already registered")
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return err
	}
	userID, err := s.EnsureDefaultUser()
	if err != nil {
		return err
	}
	host.UserID = userID
	host.Generation = 1
	host.CreatedAt = now
	if _, err := tx.Exec(
		`INSERT INTO hosts(id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, created_at, device_id)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		host.ID, userID, host.RouteID, host.HostName, host.HostPubKey, host.Generation,
		host.MaxStreams, host.Version, host.CreatedAt, deviceID); err != nil {
		return fmt.Errorf("create anonymous host: %w", err)
	}
	material, err := materialize(host.Generation)
	if err != nil {
		return err
	}
	if material == nil || len(material.CapabilityHash) == 0 {
		return errors.New("missing enroll material")
	}
	credentialIDBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return err
	}
	credentialID := base64.RawURLEncoding.EncodeToString(credentialIDBytes)
	if _, err := tx.Exec(`INSERT INTO credentials(id, host_id, capability_hash, generation, issued_at, expires_at) VALUES(?,?,?,?,?,?)`,
		credentialID, host.ID, material.CapabilityHash, host.Generation, material.IssuedAt, material.ExpiresAt); err != nil {
		return fmt.Errorf("create credential: %w", err)
	}
	return tx.Commit()
}

// RouteLookup carries a host plus the enabled state of its owning device.
// Anonymous hosts become unreachable the moment their device is disabled,
// without touching the host row itself.
type RouteLookup struct {
	Host          *Host
	DeviceEnabled bool
}

// GetHostByRouteWithDevice returns the host for a route and whether its
// device is enabled (hosts without a device link count as enabled).
func (s *Store) GetHostByRouteWithDevice(routeID []byte) (*RouteLookup, error) {
	row := s.db.QueryRow(
		`SELECT h.id, h.user_id, h.route_id, h.host_name, h.host_pubkey,
		        h.generation, h.max_streams, h.version, h.last_seen_at,
		        h.revoked_at, h.created_at, h.device_id, h.suspended_until, d.enabled
		 FROM hosts h LEFT JOIN devices d ON d.id = h.device_id
		 WHERE h.route_id = ?`, routeID)
	var h Host
	var deviceID sql.NullString
	var suspended sql.NullInt64
	var deviceEnabled sql.NullInt64
	err := row.Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey,
		&h.Generation, &h.MaxStreams, &h.Version, &h.LastSeenAt, &h.RevokedAt,
		&h.CreatedAt, &deviceID, &suspended, &deviceEnabled)
	if suspended.Valid {
		v := suspended.Int64
		h.SuspendedUntil = &v
	}
	if deviceID.Valid {
		h.DeviceID = deviceID.String
	}
	if err != nil {
		return nil, err
	}
	enabled := true
	if deviceID.Valid && deviceEnabled.Valid {
		enabled = deviceEnabled.Int64 != 0
	}
	return &RouteLookup{Host: &h, DeviceEnabled: enabled}, nil
}

// ListHostsByDevice returns every host linked to a device (revoked or not).
func (s *Store) ListHostsByDevice(deviceID string) ([]*Host, error) {
	rows, err := s.db.Query(
		`SELECT id, user_id, route_id, host_name, host_pubkey, generation,
		        max_streams, version, last_seen_at, revoked_at, created_at
		 FROM hosts WHERE device_id = ? ORDER BY created_at DESC`, deviceID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Host
	for rows.Next() {
		var h Host
		var last, revoked sql.NullInt64
		if err := rows.Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey,
			&h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt); err != nil {
			return nil, err
		}
		if last.Valid {
			v := last.Int64
			h.LastSeenAt = &v
		}
		if revoked.Valid {
			v := revoked.Int64
			h.RevokedAt = &v
		}
		out = append(out, &h)
	}
	return out, rows.Err()
}
