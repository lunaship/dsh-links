package metrics

import (
	"sync"
	"sync/atomic"
	"time"
)

type Metrics struct {
	mu sync.RWMutex

	// Gauges
	OnlineHosts   int
	ActiveStreams int
	PendingBinds  int
	TotalConns    int64 // handled via atomic? but keep sync

	// Counters
	TotalConnects int64
	TotalBinds    int64
	TotalErrors   int64
	RxBytes       int64
	TxBytes       int64

	// Latency samples for p95 (simple ring)
	latencies []time.Duration
	latIdx    int
}

func New() *Metrics {
	return &Metrics{
		latencies: make([]time.Duration, 1024),
	}
}

func (m *Metrics) IncOnlineHosts(delta int) {
	m.mu.Lock()
	m.OnlineHosts += delta
	m.mu.Unlock()
}

func (m *Metrics) IncActiveStreams(delta int) {
	m.mu.Lock()
	m.ActiveStreams += delta
	m.mu.Unlock()
}

func (m *Metrics) IncPendingBinds(delta int) {
	m.mu.Lock()
	m.PendingBinds += delta
	m.mu.Unlock()
}

func (m *Metrics) RecordConnect() { atomic.AddInt64(&m.TotalConnects, 1) }
func (m *Metrics) RecordBind()    { atomic.AddInt64(&m.TotalBinds, 1) }
func (m *Metrics) RecordError()   { atomic.AddInt64(&m.TotalErrors, 1) }
func (m *Metrics) AddRx(n int64)  { atomic.AddInt64(&m.RxBytes, n) }
func (m *Metrics) AddTx(n int64)  { atomic.AddInt64(&m.TxBytes, n) }

func (m *Metrics) ObserveLatency(d time.Duration) {
	m.mu.Lock()
	m.latencies[m.latIdx%len(m.latencies)] = d
	m.latIdx++
	m.mu.Unlock()
}

type Snapshot struct {
	OnlineHosts   int   `json:"onlineHosts"`
	ActiveStreams int   `json:"activeStreams"`
	PendingBinds  int   `json:"pendingBinds"`
	TotalConnects int64 `json:"totalConnects"`
	TotalBinds    int64 `json:"totalBinds"`
	TotalErrors   int64 `json:"totalErrors"`
	RxBytes       int64 `json:"rxBytes"`
	TxBytes       int64 `json:"txBytes"`
	P95LatencyMs  int64 `json:"p95LatencyMs"`
}

func (m *Metrics) Snapshot() Snapshot {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s := Snapshot{
		OnlineHosts:   m.OnlineHosts,
		ActiveStreams: m.ActiveStreams,
		PendingBinds:  m.PendingBinds,
		TotalConnects: atomic.LoadInt64(&m.TotalConnects),
		TotalBinds:    atomic.LoadInt64(&m.TotalBinds),
		TotalErrors:   atomic.LoadInt64(&m.TotalErrors),
		RxBytes:       atomic.LoadInt64(&m.RxBytes),
		TxBytes:       atomic.LoadInt64(&m.TxBytes),
	}
	// compute p95 from stored latencies (non-zero)
	var vals []time.Duration
	for _, v := range m.latencies {
		if v != 0 {
			vals = append(vals, v)
		}
	}
	if len(vals) > 0 {
		// simple sort
		// insertion sort for small N
		for i := 1; i < len(vals); i++ {
			j := i
			for j > 0 && vals[j-1] > vals[j] {
				vals[j-1], vals[j] = vals[j], vals[j-1]
				j--
			}
		}
		idx := int(float64(len(vals)) * 0.95)
		if idx >= len(vals) {
			idx = len(vals) - 1
		}
		s.P95LatencyMs = vals[idx].Milliseconds()
	}
	return s
}
