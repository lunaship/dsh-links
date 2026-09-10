package store

import (
	"database/sql"
	"encoding/base64"
	"errors"
	"regexp"
	"strings"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
)

const (
	RoleAdmin  = "admin"
	RoleTenant = "tenant"
)

var (
	ErrLoginTaken         = errors.New("login name taken")
	ErrInvalidLogin       = errors.New("invalid login name")
	ErrWeakPassword       = errors.New("password too short")
	ErrUserNotFound       = errors.New("user not found")
	ErrUserDisabled       = errors.New("user disabled")
	ErrPasswordMustChange = errors.New("password change required")
	ErrCurrentPassword    = errors.New("current password mismatch")
	ErrSamePassword       = errors.New("new password must differ")
	loginNamePattern      = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{2,31}$`)
)

type User struct {
	ID                 string
	LoginName          string
	DisplayName        string
	Role               string
	DisabledAt         *int64
	CreatedAt          int64
	PasswordHash       string
	PasswordMustChange bool
	LiveHosts          int
	UnusedInvites      int
}

func ValidLoginName(name string) bool {
	return loginNamePattern.MatchString(name)
}

func (s *Store) CreateTenant(loginName, displayName, password, reservedAdminUser string) (*User, error) {
	loginName = strings.TrimSpace(loginName)
	displayName = strings.TrimSpace(displayName)
	if !ValidLoginName(loginName) {
		return nil, ErrInvalidLogin
	}
	if strings.EqualFold(loginName, reservedAdminUser) {
		return nil, ErrInvalidLogin
	}
	if displayName == "" {
		displayName = loginName
	}
	if len(password) < MinPasswordLen {
		return nil, ErrWeakPassword
	}
	hash, err := HashPassword(password)
	if err != nil {
		return nil, err
	}
	idBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return nil, err
	}
	id := "user-" + base64.RawURLEncoding.EncodeToString(idBytes)
	now := nowSec()
	_, err = s.db.Exec(
		`INSERT INTO users(id, login_name, display_name, password_hash, password_must_change, role, created_at) VALUES(?,?,?,?,1,?,?)`,
		id, loginName, displayName, hash, RoleTenant, now,
	)
	if err != nil {
		if strings.Contains(strings.ToLower(err.Error()), "unique") {
			return nil, ErrLoginTaken
		}
		return nil, err
	}
	return &User{ID: id, LoginName: loginName, DisplayName: displayName, Role: RoleTenant, CreatedAt: now, PasswordMustChange: true}, nil
}

func (s *Store) GetUserByLogin(loginName string) (*User, error) {
	return s.scanUser(`SELECT id, COALESCE(login_name,''), display_name, COALESCE(password_hash,''), COALESCE(role,'admin'), disabled_at, created_at, COALESCE(password_must_change,0) FROM users WHERE login_name=?`, strings.TrimSpace(loginName))
}

func (s *Store) GetUser(id string) (*User, error) {
	return s.scanUser(`SELECT id, COALESCE(login_name,''), display_name, COALESCE(password_hash,''), COALESCE(role,'admin'), disabled_at, created_at, COALESCE(password_must_change,0) FROM users WHERE id=?`, id)
}

func (s *Store) scanUser(q string, arg any) (*User, error) {
	var u User
	var disabled sql.NullInt64
	var mustChange int64
	err := s.db.QueryRow(q, arg).Scan(&u.ID, &u.LoginName, &u.DisplayName, &u.PasswordHash, &u.Role, &disabled, &u.CreatedAt, &mustChange)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrUserNotFound
	}
	if err != nil {
		return nil, err
	}
	u.PasswordMustChange = mustChange != 0
	if disabled.Valid {
		v := disabled.Int64
		u.DisabledAt = &v
	}
	return &u, nil
}

func (s *Store) ListTenants() ([]User, error) {
	now := nowSec()
	rows, err := s.db.Query(`
SELECT id, COALESCE(login_name,''), display_name, COALESCE(role,'tenant'), disabled_at, created_at, COALESCE(password_must_change,0),
  (SELECT COUNT(*) FROM hosts WHERE user_id=users.id AND revoked_at IS NULL),
  (SELECT COUNT(*) FROM invites WHERE user_id=users.id AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at > ?)
FROM users WHERE role=? ORDER BY created_at DESC`, now, RoleTenant)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []User
	for rows.Next() {
		var u User
		var disabled sql.NullInt64
		var mustChange int64
		if err := rows.Scan(&u.ID, &u.LoginName, &u.DisplayName, &u.Role, &disabled, &u.CreatedAt, &mustChange, &u.LiveHosts, &u.UnusedInvites); err != nil {
			return nil, err
		}
		u.PasswordMustChange = mustChange != 0
		if disabled.Valid {
			v := disabled.Int64
			u.DisabledAt = &v
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

func (s *Store) DisableUser(id string) error {
	now := nowSec()
	res, err := s.db.Exec(`UPDATE users SET disabled_at=? WHERE id=? AND role=? AND disabled_at IS NULL`, now, id, RoleTenant)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrUserNotFound
	}
	return nil
}

func (s *Store) EnableUser(id string) error {
	u, err := s.GetUser(id)
	if err != nil {
		return err
	}
	if u.Role != RoleTenant {
		return ErrUserNotFound
	}
	if u.DisabledAt == nil {
		return nil
	}
	_, err = s.db.Exec(`UPDATE users SET disabled_at=NULL WHERE id=? AND role=?`, id, RoleTenant)
	return err
}

func (s *Store) AuthenticateTenant(loginName, password string) (*User, error) {
	u, err := s.GetUserByLogin(loginName)
	if err != nil {
		_ = VerifyPassword(password, dummyPasswordHash)
		return nil, ErrUserNotFound
	}
	if u.Role != RoleTenant || u.PasswordHash == "" {
		_ = VerifyPassword(password, dummyPasswordHash)
		return nil, ErrUserNotFound
	}
	if u.DisabledAt != nil {
		_ = VerifyPassword(password, u.PasswordHash)
		return nil, ErrUserDisabled
	}
	if !VerifyPassword(password, u.PasswordHash) {
		return nil, ErrUserNotFound
	}
	u.PasswordHash = ""
	return u, nil
}

func (s *Store) SetTenantPassword(id, password string) error {
	return s.setTenantPassword(id, password, true)
}

func (s *Store) setTenantPassword(id, password string, mustChange bool) error {
	if len(password) < MinPasswordLen {
		return ErrWeakPassword
	}
	u, err := s.GetUser(id)
	if err != nil {
		return err
	}
	if u.Role != RoleTenant {
		return ErrUserNotFound
	}
	hash, err := HashPassword(password)
	if err != nil {
		return err
	}
	flag := 0
	if mustChange {
		flag = 1
	}
	res, err := s.db.Exec(`UPDATE users SET password_hash=?, password_must_change=? WHERE id=? AND role=?`, hash, flag, id, RoleTenant)
	if err != nil {
		return err
	}
	n, _ := res.RowsAffected()
	if n == 0 {
		return ErrUserNotFound
	}
	return nil
}

func (s *Store) ChangeTenantPassword(id, current, next string) error {
	if current == next {
		return ErrSamePassword
	}
	u, err := s.GetUser(id)
	if err != nil {
		return err
	}
	if u.Role != RoleTenant || u.DisabledAt != nil || u.PasswordHash == "" {
		_ = VerifyPassword(current, dummyPasswordHash)
		return ErrUserNotFound
	}
	if !VerifyPassword(current, u.PasswordHash) {
		return ErrCurrentPassword
	}
	return s.setTenantPassword(id, next, false)
}

// dummyPasswordHash is used so a missing login still spends a PBKDF2 verify.
var dummyPasswordHash = mustHash("not-a-real-tenant-password")

func mustHash(pw string) string {
	h, err := HashPassword(pw)
	if err != nil {
		panic(err)
	}
	return h
}
