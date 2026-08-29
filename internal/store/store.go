package store

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB

	// SQLite transactions used by enrollment start deferred. Concurrent
	// enrollments can all acquire a read lock and then deadlock while trying
	// to promote it to a write lock; SQLite reports that state as a
	// non-retryable "database is deadlocked" error. Enrollment is a rare
	// control-plane operation, so serialize that write transaction at the
	// store boundary while leaving ordinary reads and other operations
	// concurrent.
	enrollMu sync.Mutex
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
	s := &Store{db: db}
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
	s := &Store{db: db}
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
CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at);
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

// EnsureDefaultUser creates a default user if not exists
func (s *Store) EnsureDefaultUser() (string, error) {
	var id string
	err := s.db.QueryRow(`SELECT id FROM users LIMIT 1`).Scan(&id)
	if err == nil {
		return id, nil
	}
	// create default
	id = "user-default"
	_, err = s.db.Exec(`INSERT INTO users(id, display_name, created_at) VALUES(?,?,?)`, id, "default", nowSec())
	if err != nil {
		return "", err
	}
	return id, nil
}

func nowSec() int64 {
	return time.Now().Unix()
}

// SuspendHostUntil marks a host suspended until the given unix time. While
// suspended the route lookup reports it as revoked and the operator's push
// (via revokeFn) evicts active streams.
func (s *Store) SuspendHostUntil(hostID string, until int64) error {
	_, err := s.db.Exec(`UPDATE hosts SET suspended_until = ? WHERE id = ?`, until, hostID)
	return err
}

// ClearSuspendedUntil removes a suspension (used after the window elapsed).
func (s *Store) ClearSuspendedUntil(hostID string) error {
	_, err := s.db.Exec(`UPDATE hosts SET suspended_until = NULL WHERE id = ?`, hostID)
	return err
}
