package store

import (
	"database/sql"
	"encoding/base64"
	"time"
)

type Host struct {
	ID         string
	UserID     string
	RouteID    []byte // 16B
	HostName   string
	HostPubKey []byte // 32B
	Generation int64
	MaxStreams int
	Version    string
	LastSeenAt *int64
	RevokedAt  *int64
	CreatedAt  int64
}

func (s *Store) CreateHost(userID, hostId string, routeId, hostPubKey []byte, hostName string, maxStreams int, version string, generation int64) (*Host, error) {
	now := time.Now().Unix()
	h := &Host{
		ID:         hostId,
		UserID:     userID,
		RouteID:    routeId,
		HostName:   hostName,
		HostPubKey: hostPubKey,
		Generation: generation,
		MaxStreams: maxStreams,
		Version:    version,
		CreatedAt:  now,
	}
	_, err := s.db.Exec(`INSERT INTO hosts(id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, created_at) VALUES(?,?,?,?,?,?,?,?,?)`,
		h.ID, h.UserID, h.RouteID, h.HostName, h.HostPubKey, h.Generation, h.MaxStreams, h.Version, h.CreatedAt)
	if err != nil {
		return nil, err
	}
	return h, nil
}

func (s *Store) GetHostByRoute(routeId []byte) (*Host, error) {
	var h Host
	var last sql.NullInt64
	var revoked sql.NullInt64
	err := s.db.QueryRow(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at FROM hosts WHERE route_id=?`, routeId).
		Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt)
	if err != nil {
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
	return &h, nil
}

func (s *Store) GetHostByID(id string) (*Host, error) {
	var h Host
	var last sql.NullInt64
	var revoked sql.NullInt64
	err := s.db.QueryRow(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at FROM hosts WHERE id=?`, id).
		Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt)
	if err != nil {
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
	return &h, nil
}

func (s *Store) GetHostByPubKey(pub []byte) (*Host, error) {
	var h Host
	var last sql.NullInt64
	var revoked sql.NullInt64
	err := s.db.QueryRow(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at FROM hosts WHERE host_pubkey=?`, pub).
		Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt)
	if err != nil {
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
	return &h, nil
}

func (s *Store) ListHosts() ([]Host, error) {
	rows, err := s.db.Query(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at FROM hosts ORDER BY created_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Host
	for rows.Next() {
		var h Host
		var last sql.NullInt64
		var revoked sql.NullInt64
		if err := rows.Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt); err != nil {
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
		out = append(out, h)
	}
	return out, rows.Err()
}

// ListRevokedHosts returns revoked hosts. Used by Control to push the current
// revocation set to a (re)connecting relay so the relay can reconcile its
// in-memory registry without a per-second full poll.
func (s *Store) ListRevokedHosts() ([]Host, error) {
	rows, err := s.db.Query(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at FROM hosts WHERE revoked_at IS NOT NULL ORDER BY revoked_at DESC`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Host
	for rows.Next() {
		var h Host
		var last sql.NullInt64
		var revoked sql.NullInt64
		if err := rows.Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt); err != nil {
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
		out = append(out, h)
	}
	return out, rows.Err()
}

func (s *Store) RevokeHost(id string) error {
	now := time.Now().Unix()
	// Increment generation and set revoked
	// Keep old route_id revoked; caller will create new route if needed
	res, err := s.db.Exec(`UPDATE hosts SET revoked_at=?, generation=generation+1 WHERE id=? AND revoked_at IS NULL`, now, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return errStr("host already revoked or not found")
	}
	return nil
}

func (s *Store) UpdateHostHeartbeat(id string) error {
	now := time.Now().Unix()
	_, err := s.db.Exec(`UPDATE hosts SET last_seen_at=? WHERE id=?`, now, id)
	return err
}

func (s *Store) GetHostRouteB64(host *Host) string {
	return base64.RawURLEncoding.EncodeToString(host.RouteID)
}
