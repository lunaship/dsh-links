package store

import (
	"database/sql"
	"time"
)

// DateLayout is the stats_daily partition key format (host-local UTC date).
const DateLayout = "2006-01-02"

// AddRouteUsage increments the daily usage counters for a host. The relay
// pushes routed byte/connect counters to control every few seconds; control
// resolves the route to its host and accumulates here. The row doubles as the
// billing/quota basis for phase-2 daily budgets.
func (s *Store) AddRouteUsage(hostID, date string, rx, tx int64, connects int) error {
	if hostID == "" {
		return nil
	}
	_, err := s.db.Exec(
		`INSERT INTO stats_daily (host_id, date, rx_bytes, tx_bytes, connect_count, error_count)
		 VALUES (?, ?, ?, ?, ?, 0)
		 ON CONFLICT(host_id, date) DO UPDATE SET
		   rx_bytes = rx_bytes + excluded.rx_bytes,
		   tx_bytes = tx_bytes + excluded.tx_bytes,
		   connect_count = connect_count + excluded.connect_count`,
		hostID, date, rx, tx, connects,
	)
	return err
}

// DailyUsage is one host-day aggregate row.
type DailyUsage struct {
	HostID        string
	Date          string
	RXBytes       int64
	TXBytes       int64
	ConnectCount  int64
	ErrorCount    int64
}

// GetDailyUsage returns the usage row for a host on a date (zero values when
// no traffic was recorded).
func (s *Store) GetDailyUsage(hostID, date string) (*DailyUsage, error) {
	row := s.db.QueryRow(
		`SELECT host_id, date, rx_bytes, tx_bytes, connect_count, error_count
		 FROM stats_daily WHERE host_id = ? AND date = ?`, hostID, date)
	var u DailyUsage
	err := row.Scan(&u.HostID, &u.Date, &u.RXBytes, &u.TXBytes, &u.ConnectCount, &u.ErrorCount)
	if err == sql.ErrNoRows {
		return &DailyUsage{HostID: hostID, Date: date}, nil
	}
	if err != nil {
		return nil, err
	}
	return &u, nil
}

// Today returns the host-local UTC date string for stats partitioning.
func Today() string {
	return time.Now().UTC().Format(DateLayout)
}