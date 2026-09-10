package store

import (
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB

	tenantMaxLiveHosts     int
	tenantMaxUnusedInvites int

	// SQLite transactions used by enrollment start deferred. Concurrent
	// enrollments can all acquire a read lock and then deadlock while trying
	// to promote it to a write lock; SQLite reports that state as a
	// non-retryable "database is deadlocked" error. Enrollment is a rare
	// control-plane operation, so serialize that write transaction at the
	// store boundary while leaving ordinary reads and other operations
	// concurrent.
	enrollMu sync.Mutex
}

func newStore(db *sql.DB) *Store {
	return &Store{
		db:                     db,
		tenantMaxLiveHosts:     DefaultTenantMaxLiveHosts,
		tenantMaxUnusedInvites: DefaultTenantMaxUnusedInvites,
	}
}

func Open(path string) (*Store, error) {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0700); err != nil {
		return nil, err
	}
	dsn := fmt.Sprintf("file:%s?_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)", path)
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		return nil, err
	}
	s := newStore(db)
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func OpenMemory() (*Store, error) {
	db, err := sql.Open("sqlite", "file::memory:?cache=shared")
	if err != nil {
		return nil, err
	}
	if _, err := db.Exec(`PRAGMA foreign_keys=ON`); err != nil {
		return nil, err
	}
	s := newStore(db)
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

func (s *Store) DB() *sql.DB { return s.db }

func (s *Store) Close() error { return s.db.Close() }

func (s *Store) migrate() error {
	schema := `
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  disabled_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS invites (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  code_hash BLOB NOT NULL UNIQUE,
  expires_at INTEGER NOT NULL,
  consumed_at INTEGER,
  consumed_host_id TEXT,
  consumed_host_name TEXT,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS hosts (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id),
  route_id BLOB NOT NULL UNIQUE,
  host_name TEXT NOT NULL,
  host_pubkey BLOB NOT NULL UNIQUE,
  generation INTEGER NOT NULL,
  max_streams INTEGER NOT NULL,
  version TEXT NOT NULL,
  last_seen_at INTEGER,
  revoked_at INTEGER,
  created_at INTEGER NOT NULL,
  device_id TEXT,
  suspended_until INTEGER
);

CREATE TABLE IF NOT EXISTS credentials (
  id TEXT PRIMARY KEY,
  host_id TEXT NOT NULL REFERENCES hosts(id),
  capability_hash BLOB NOT NULL UNIQUE,
  generation INTEGER NOT NULL,
  issued_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS renewal_replays (
  digest BLOB PRIMARY KEY,
  host_id TEXT NOT NULL REFERENCES hosts(id),
  expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS stats_daily (
  host_id TEXT NOT NULL REFERENCES hosts(id),
  date TEXT NOT NULL,
  rx_bytes INTEGER NOT NULL,
  tx_bytes INTEGER NOT NULL,
  connect_count INTEGER NOT NULL,
  error_count INTEGER NOT NULL,
  PRIMARY KEY (host_id, date)
);
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  public_key BLOB NOT NULL UNIQUE,
  enabled INTEGER NOT NULL DEFAULT 1,
  max_hosts INTEGER NOT NULL DEFAULT 2,
  created_at INTEGER NOT NULL,
  disabled_at INTEGER
);
CREATE TABLE IF NOT EXISTS settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS control_events (
  id TEXT PRIMARY KEY,
  at INTEGER NOT NULL,
  actor_id TEXT NOT NULL DEFAULT '',
  actor_login TEXT NOT NULL DEFAULT '',
  action TEXT NOT NULL,
  target_kind TEXT NOT NULL,
  target_id TEXT NOT NULL,
  subject_user_id TEXT NOT NULL DEFAULT '',
  detail TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at);
CREATE INDEX IF NOT EXISTS idx_control_events_at ON control_events(at DESC);
CREATE INDEX IF NOT EXISTS idx_control_events_subject ON control_events(subject_user_id, at DESC);
CREATE INDEX IF NOT EXISTS idx_hosts_route ON hosts(route_id);
CREATE INDEX IF NOT EXISTS idx_hosts_pubkey ON hosts(host_pubkey);
CREATE INDEX IF NOT EXISTS idx_renewal_replays_expires ON renewal_replays(expires_at);
`
	if _, err := s.db.Exec(schema); err != nil {
		return err
	}
	// Phase 2: add device linkage + suspension columns to existing hosts.
	if err := s.ensureColumn("hosts", "device_id", "TEXT"); err != nil {
		return err
	}
	if err := s.ensureColumn("hosts", "suspended_until", "INTEGER"); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE INDEX IF NOT EXISTS idx_hosts_device ON hosts(device_id)`); err != nil {
		return err
	}
	if err := s.ensureColumn("invites", "consumed_host_id", "TEXT"); err != nil {
		return err
	}
	if err := s.ensureColumn("invites", "consumed_host_name", "TEXT"); err != nil {
		return err
	}
	if err := s.ensureColumn("users", "login_name", "TEXT"); err != nil {
		return err
	}
	if err := s.ensureColumn("users", "password_hash", "TEXT"); err != nil {
		return err
	}
	if err := s.ensureColumn("users", "role", "TEXT NOT NULL DEFAULT 'admin'"); err != nil {
		return err
	}
	if err := s.ensureColumn("users", "password_must_change", "INTEGER NOT NULL DEFAULT 0"); err != nil {
		return err
	}
	if _, err := s.db.Exec(`CREATE UNIQUE INDEX IF NOT EXISTS users_login_name ON users(login_name) WHERE login_name IS NOT NULL AND login_name != ''`); err != nil {
		return err
	}
	return nil
}

// ensureColumn adds a column to an existing table when it is not already
// present (SQLite lacks IF NOT EXISTS for ALTER TABLE ADD COLUMN).
func (s *Store) ensureColumn(table, column, decl string) error {
	rows, err := s.db.Query(`PRAGMA table_info(` + table + `)`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var cid int
		var name, typ string
		var notnull, pk int
		var dflt any
		if err := rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk); err != nil {
			return err
		}
		if name == column {
			return nil
		}
	}
	_, err = s.db.Exec(`ALTER TABLE ` + table + ` ADD COLUMN ` + column + ` ` + decl)
	return err
}

const defaultUserID = "user-default"

// EnsureDefaultUser returns the self-host / admin ledger row. It must not
// pick an arbitrary tenant: hosted Control can have tenant users before any
// admin invite is minted.
func (s *Store) EnsureDefaultUser() (string, error) {
	var id string
	err := s.db.QueryRow(`SELECT id FROM users WHERE id=?`, defaultUserID).Scan(&id)
	if err == nil {
		return id, nil
	}
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return "", err
	}
	_, err = s.db.Exec(`INSERT INTO users(id, display_name, role, created_at) VALUES(?,?,?,?)`, defaultUserID, "default", RoleAdmin, nowSec())
	if err != nil {
		return "", err
	}
	return defaultUserID, nil
}

func nowSec() int64 {
	return time.Now().Unix()
}

// SuspendHostUntil marks a host suspended until the given unix time. While
// suspended, CONNECT is RATE_LIMITED (retryable). Credentials stay valid.
func (s *Store) SuspendHostUntil(hostID string, until int64) error {
	_, err := s.db.Exec(`UPDATE hosts SET suspended_until = ? WHERE id = ?`, until, hostID)
	return err
}

// ClearSuspendedUntil removes a suspension (used after the window elapsed).
func (s *Store) ClearSuspendedUntil(hostID string) error {
	_, err := s.db.Exec(`UPDATE hosts SET suspended_until = NULL WHERE id = ?`, hostID)
	return err
}
