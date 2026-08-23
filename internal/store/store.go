package store

import (
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"time"

	_ "modernc.org/sqlite"
)

type Store struct {
	db *sql.DB
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
  created_at INTEGER NOT NULL
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

CREATE TABLE IF NOT EXISTS stats_daily (
  host_id TEXT NOT NULL REFERENCES hosts(id),
  date TEXT NOT NULL,
  rx_bytes INTEGER NOT NULL,
  tx_bytes INTEGER NOT NULL,
  connect_count INTEGER NOT NULL,
  error_count INTEGER NOT NULL,
  PRIMARY KEY (host_id, date)
);
CREATE INDEX IF NOT EXISTS idx_invites_expires ON invites(expires_at);
CREATE INDEX IF NOT EXISTS idx_hosts_route ON hosts(route_id);
CREATE INDEX IF NOT EXISTS idx_hosts_pubkey ON hosts(host_pubkey);
`
	_, err := s.db.Exec(schema)
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
