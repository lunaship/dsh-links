package store

import (
	"encoding/base64"

	"github.com/lunaship/dsh-links/relay/internal/cryptoutil"
)

const (
	ControlEventInviteCreate    = "invite.create"
	ControlEventInviteRevoke    = "invite.revoke"
	ControlEventInviteDelete    = "invite.delete"
	ControlEventHostEnroll      = "host.enroll"
	ControlEventHostRevoke      = "host.revoke"
	ControlEventHostDelete      = "host.delete"
	ControlEventTenantCreate    = "tenant.create"
	ControlEventTenantDisable   = "tenant.disable"
	ControlEventTenantEnable    = "tenant.enable"
	ControlEventTenantPassword  = "tenant.password"
	ControlEventAccountPassword = "account.password"
	ControlEventAuthLogin       = "auth.login"
	ControlEventAuthLoginFail   = "auth.login.fail"

	controlEventLimit      = 100
	controlEventRetainSecs = 180 * 24 * 60 * 60
)

// ControlEvent is a Control-plane audit row. It never stores invite codes,
// passwords, route secrets, or session content. auth.login.fail is written
// only for known admin/tenant logins so guessing unknown names cannot
// flood the ledger.
type ControlEvent struct {
	ID            string
	At            int64
	ActorID       string
	ActorLogin    string
	Action        string
	TargetKind    string
	TargetID      string
	SubjectUserID string
	SubjectLogin  string
	Detail        string
}

func (s *Store) AppendControlEvent(ev ControlEvent) error {
	if ev.Action == "" || ev.TargetKind == "" {
		return nil
	}
	idBytes, err := cryptoutil.RandomBytes(16)
	if err != nil {
		return err
	}
	now := nowSec()
	_, _ = s.db.Exec(`DELETE FROM control_events WHERE at < ?`, now-controlEventRetainSecs)
	_, err = s.db.Exec(
		`INSERT INTO control_events(id, at, actor_id, actor_login, action, target_kind, target_id, subject_user_id, detail)
VALUES(?,?,?,?,?,?,?,?,?)`,
		"evt-"+base64.RawURLEncoding.EncodeToString(idBytes),
		now,
		ev.ActorID,
		ev.ActorLogin,
		ev.Action,
		ev.TargetKind,
		ev.TargetID,
		ev.SubjectUserID,
		ev.Detail,
	)
	return err
}

func (s *Store) ListControlEvents(userID string, all bool) ([]ControlEvent, error) {
	q := `SELECT e.id, e.at, e.actor_id, e.actor_login, e.action, e.target_kind, e.target_id, e.subject_user_id, e.detail,
 COALESCE(u.login_name,'')
FROM control_events e
LEFT JOIN users u ON u.id = e.subject_user_id `
	var args []any
	if !all {
		if userID == "" {
			return nil, nil
		}
		q += `WHERE e.subject_user_id=? OR e.actor_id=? `
		args = append(args, userID, userID)
	}
	q += `ORDER BY e.at DESC, e.id DESC LIMIT ?`
	args = append(args, controlEventLimit)
	rows, err := s.db.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ControlEvent
	for rows.Next() {
		var ev ControlEvent
		if err := rows.Scan(&ev.ID, &ev.At, &ev.ActorID, &ev.ActorLogin, &ev.Action, &ev.TargetKind, &ev.TargetID, &ev.SubjectUserID, &ev.Detail, &ev.SubjectLogin); err != nil {
			return nil, err
		}
		out = append(out, ev)
	}
	return out, rows.Err()
}
