package agent

// Package agent is a placeholder for the formal Relay Agent implementation.
//
// The production Agent lives in this same repository's plugin (`src/relay/*`):
// it dials the Relay on 8444/tcp and bridges only to 127.0.0.1:18640. This
// Go package is intentionally empty so the Relay process does not copy plugin
// business logic. For Relay development and acceptance tests, the simulated
// agent is provided in `internal/testkit`.
