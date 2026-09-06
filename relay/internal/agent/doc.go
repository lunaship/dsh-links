package agent

// Package agent is a placeholder for the formal Relay Agent implementation.
//
// The production Agent is not part of dsh-links-relay; it is built into
// the `dsh-links` plugin (src/relay/*) and always dials the Relay on
// 8444/tcp and bridges only to 127.0.0.1:18640.  This package is intentionally
// empty to enforce the repository boundary: dsh-links-relay must not copy
// business logic from dsh-links.  For Relay development and验收, the
// simulated agent is provided in `internal/testkit`.
