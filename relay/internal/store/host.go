package store

import (
	"database/sql"
	"encoding/base64"
	"time"
)

type Host struct {
	ID         string
	UserID     string
	LoginName  string
	RouteID    []byte // 16B
	HostName   string
	HostPubKey []byte // 32B
	Generation int64
	MaxStreams int
	Version    string
	LastSeenAt *int64
	RevokedAt  *int64
	CreatedAt  int64
	// DeviceID links anonymous self-service hosts to their device; empty =
	// invite enrolled (account-based) host, never budget-suspended.
	DeviceID string
	// SuspendedUntil holds a unix time while the host's daily budget is
	// exhausted; routes report as revoked until then.
	SuspendedUntil *int64
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
	l, err := s.GetHostByRouteWithDevice(routeId)
	if err != nil {
		return nil, err
	}
	return l.Host, nil
}

func (s *Store) GetHostByID(id string) (*Host, error) {
	var h Host
	var last, revoked, suspended sql.NullInt64
	var deviceID sql.NullString
	err := s.db.QueryRow(`SELECT id, user_id, route_id, host_name, host_pubkey, generation, max_streams, version, last_seen_at, revoked_at, created_at, device_id, suspended_until FROM hosts WHERE id=?`, id).
		Scan(&h.ID, &h.UserID, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt, &deviceID, &suspended)
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
	if deviceID.Valid {
		h.DeviceID = deviceID.String
	}
	if suspended.Valid {
		v := suspended.Int64
		h.SuspendedUntil = &v
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
	return s.listHostsQuery(`SELECT hosts.id, hosts.user_id, COALESCE(users.login_name,''), hosts.route_id, hosts.host_name, hosts.host_pubkey, hosts.generation, hosts.max_streams, hosts.version, hosts.last_seen_at, hosts.revoked_at, hosts.created_at FROM hosts LEFT JOIN users ON users.id = hosts.user_id ORDER BY hosts.created_at DESC`)
}

func (s *Store) ListHostsByUser(userID string) ([]Host, error) {
	return s.listHostsQuery(`SELECT hosts.id, hosts.user_id, COALESCE(users.login_name,''), hosts.route_id, hosts.host_name, hosts.host_pubkey, hosts.generation, hosts.max_streams, hosts.version, hosts.last_seen_at, hosts.revoked_at, hosts.created_at FROM hosts LEFT JOIN users ON users.id = hosts.user_id WHERE hosts.user_id=? ORDER BY hosts.created_at DESC`, userID)
}

func (s *Store) listHostsQuery(q string, args ...any) ([]Host, error) {
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Host
	for rows.Next() {
		var h Host
		var last sql.NullInt64
		var revoked sql.NullInt64
		if err := rows.Scan(&h.ID, &h.UserID, &h.LoginName, &h.RouteID, &h.HostName, &h.HostPubKey, &h.Generation, &h.MaxStreams, &h.Version, &last, &revoked, &h.CreatedAt); err != nil {
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

func (s *Store) DeleteHost(id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM credentials WHERE host_id=?`, id); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM renewal_replays WHERE host_id=?`, id); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM stats_daily WHERE host_id=?`, id); err != nil {
		return err
	}
	res, err := tx.Exec(`DELETE FROM hosts WHERE id=?`, id)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrHostNotFound
	}
	return tx.Commit()
}

func (s *Store) PurgeRevokedHosts() (int64, error) {
	return s.purgeRevokedHosts("")
}

// PurgeRevokedHostsByUser deletes one tenant's revoked Host rows and their
// credentials. Live Hosts are kept. Empty userID is a no-op.
func (s *Store) PurgeRevokedHostsByUser(userID string) (int64, error) {
	if userID == "" {
		return 0, nil
	}
	return s.purgeRevokedHosts(userID)
}

func (s *Store) purgeRevokedHosts(userID string) (int64, error) {
	filter := `revoked_at IS NOT NULL`
	var args []any
	if userID != "" {
		filter += ` AND user_id=?`
		args = append(args, userID)
	}
	tx, err := s.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	inHosts := `host_id IN (SELECT id FROM hosts WHERE ` + filter + `)`
	if _, err := tx.Exec(`DELETE FROM credentials WHERE `+inHosts, args...); err != nil {
		return 0, err
	}
	if _, err := tx.Exec(`DELETE FROM renewal_replays WHERE `+inHosts, args...); err != nil {
		return 0, err
	}
	if _, err := tx.Exec(`DELETE FROM stats_daily WHERE `+inHosts, args...); err != nil {
		return 0, err
	}
	res, err := tx.Exec(`DELETE FROM hosts WHERE `+filter, args...)
	if err != nil {
		return 0, err
	}
	n, _ := res.RowsAffected()
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}

func (s *Store) UpdateHostHeartbeat(id string) error {
	now := time.Now().Unix()
	_, err := s.db.Exec(`UPDATE hosts SET last_seen_at=? WHERE id=?`, now, id)
	return err
}

func (s *Store) UpdateHostHeartbeatByRoute(routeID []byte) error {
	if len(routeID) != 16 {
		return nil
	}
	now := time.Now().Unix()
	_, err := s.db.Exec(`UPDATE hosts SET last_seen_at=? WHERE route_id=? AND revoked_at IS NULL`, now, routeID)
	return err
}

// ClearHostHeartbeatByRoute drops last_seen_at so Control shows 离线
// immediately when this route's Agent disconnects. Revoked rows are left
// unchanged. Does not free a quota slot; that still requires RevokeHost.
func (s *Store) ClearHostHeartbeatByRoute(routeID []byte) error {
	if len(routeID) != 16 {
		return nil
	}
	_, err := s.db.Exec(`UPDATE hosts SET last_seen_at=NULL WHERE route_id=? AND revoked_at IS NULL`, routeID)
	return err
}

func (s *Store) GetHostRouteB64(host *Host) string {
	return base64.RawURLEncoding.EncodeToString(host.RouteID)
}
