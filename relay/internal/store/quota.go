package store

import (
	"database/sql"
	"errors"
	"strings"
)

const (
	DefaultTenantMaxLiveHosts     = 8
	DefaultTenantMaxUnusedInvites = 4
)

var (
	ErrTenantHostLimit   = errors.New("tenant host limit reached")
	ErrTenantInviteLimit = errors.New("tenant invite limit reached")
)

// SetTenantLimits caps how many live hosts and unused invites a Control
// tenant may hold. Self-host admin ledger (role=admin) is never capped.
// Zero or negative values keep the current setting.
func (s *Store) SetTenantLimits(liveHosts, unusedInvites int) {
	if liveHosts > 0 {
		s.tenantMaxLiveHosts = liveHosts
	}
	if unusedInvites > 0 {
		s.tenantMaxUnusedInvites = unusedInvites
	}
}

func IsHostQuotaError(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, ErrTenantHostLimit) || errors.Is(err, ErrDeviceHostLimit) {
		return true
	}
	msg := err.Error()
	return strings.Contains(msg, ErrTenantHostLimit.Error()) || strings.Contains(msg, ErrDeviceHostLimit.Error())
}

type TenantQuota struct {
	LiveHosts        int  `json:"liveHosts"`
	MaxLiveHosts     int  `json:"maxLiveHosts"`
	UnusedInvites    int  `json:"unusedInvites"`
	MaxUnusedInvites int  `json:"maxUnusedInvites"`
	HostFull         bool `json:"hostFull"`
	InviteFull       bool `json:"inviteFull"`
}

func (s *Store) TenantLimits() (liveHosts, unusedInvites int) {
	return s.tenantMaxLiveHosts, s.tenantMaxUnusedInvites
}

func (s *Store) TenantQuota(userID string) (*TenantQuota, error) {
	u, err := s.GetUser(userID)
	if err != nil {
		return nil, err
	}
	if u.Role != RoleTenant {
		return nil, nil
	}
	live, err := s.countLiveHosts(userID)
	if err != nil {
		return nil, err
	}
	unused, err := s.countUnusedInvites(userID)
	if err != nil {
		return nil, err
	}
	return &TenantQuota{
		LiveHosts:        live,
		MaxLiveHosts:     s.tenantMaxLiveHosts,
		UnusedInvites:    unused,
		MaxUnusedInvites: s.tenantMaxUnusedInvites,
		HostFull:         s.tenantMaxLiveHosts > 0 && live >= s.tenantMaxLiveHosts,
		InviteFull:       s.tenantMaxUnusedInvites > 0 && unused >= s.tenantMaxUnusedInvites,
	}, nil
}

func (s *Store) countUnusedInvites(userID string) (int, error) {
	var n int
	err := s.db.QueryRow(
		`SELECT COUNT(*) FROM invites WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?`,
		userID, nowSec(),
	).Scan(&n)
	return n, err
}

// countLiveHosts is unrevoked enrolled Hosts, not heartbeat 在线.
func (s *Store) countLiveHosts(userID string) (int, error) {
	var n int
	err := s.db.QueryRow(`SELECT COUNT(*) FROM hosts WHERE user_id=? AND revoked_at IS NULL`, userID).Scan(&n)
	return n, err
}

func (s *Store) enforceTenantHostLimitTx(tx *sql.Tx, userID string) error {
	var role string
	err := tx.QueryRow(`SELECT COALESCE(role,'admin') FROM users WHERE id=?`, userID).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrUserNotFound
	}
	if err != nil {
		return err
	}
	if role != RoleTenant {
		return nil
	}
	var n int
	if err := tx.QueryRow(`SELECT COUNT(*) FROM hosts WHERE user_id=? AND revoked_at IS NULL`, userID).Scan(&n); err != nil {
		return err
	}
	if n >= s.tenantMaxLiveHosts {
		return ErrTenantHostLimit
	}
	return nil
}
