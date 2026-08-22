package protocol

// Error codes
const (
	ErrBadRequest     = "BAD_REQUEST"
	ErrAuthFailed     = "AUTH_FAILED"
	ErrReplayRejected = "REPLAY_REJECTED"
	ErrAgentOffline   = "AGENT_OFFLINE"
	ErrRouteBusy      = "ROUTE_BUSY"
	ErrBindTimeout    = "BIND_TIMEOUT"
	ErrRateLimited    = "RATE_LIMITED"
	ErrServerBusy     = "SERVER_BUSY"
	ErrRevoked        = "REVOKED"
)

var retryable = map[string]bool{
	ErrAgentOffline: true,
	ErrRouteBusy:    true,
	ErrBindTimeout:  true,
	ErrRateLimited:  true,
	ErrServerBusy:   true,
}

func IsRetryable(code string) bool {
	return retryable[code]
}
