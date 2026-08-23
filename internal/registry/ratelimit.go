package registry

import (
	"container/list"
	"sync"
	"time"
)

// TokenBucket simple
type TokenBucket struct {
	mu           sync.Mutex
	tokens       float64
	capacity     float64
	refillPerSec float64
	last         time.Time
}

func NewTokenBucket(capacity int, refillPerMinute int) *TokenBucket {
	return &TokenBucket{
		tokens:       float64(capacity),
		capacity:     float64(capacity),
		refillPerSec: float64(refillPerMinute) / 60.0,
		last:         time.Now(),
	}
}

func (tb *TokenBucket) Allow() bool {
	tb.mu.Lock()
	defer tb.mu.Unlock()
	now := time.Now()
	elapsed := now.Sub(tb.last).Seconds()
	tb.tokens += elapsed * tb.refillPerSec
	if tb.tokens > tb.capacity {
		tb.tokens = tb.capacity
	}
	tb.last = now
	if tb.tokens >= 1 {
		tb.tokens--
		return true
	}
	return false
}

// RateLimiter holds per-key buckets
type RateLimiter struct {
	mu           sync.Mutex
	buckets      map[string]*rateLimitEntry
	lru          *list.List
	capacity     int
	refill       int
	maxKeys      int
	ttl          time.Duration
	cleanupEvery time.Duration
	lastCleanup  time.Time
}

type rateLimitEntry struct {
	bucket   *TokenBucket
	lastSeen time.Time
	element  *list.Element
}

func NewRateLimiter(capacity, refill int) *RateLimiter {
	return NewRateLimiterWithBounds(capacity, refill, 10000, 15*time.Minute)
}

// NewRateLimiterWithBounds constructs a bounded per-key limiter. Expired keys
// are removed and, at capacity, the least-recently-seen key is evicted so
// attacker-controlled keys cannot permanently poison the limiter.
func NewRateLimiterWithBounds(capacity, refill, maxKeys int, ttl time.Duration) *RateLimiter {
	if maxKeys <= 0 {
		maxKeys = 1
	}
	if ttl <= 0 {
		ttl = time.Minute
	}
	cleanupEvery := time.Minute
	if ttl < cleanupEvery {
		cleanupEvery = ttl
	}
	return &RateLimiter{
		buckets:      make(map[string]*rateLimitEntry),
		lru:          list.New(),
		capacity:     capacity,
		refill:       refill,
		maxKeys:      maxKeys,
		ttl:          ttl,
		cleanupEvery: cleanupEvery,
		lastCleanup:  time.Now(),
	}
}

func (rl *RateLimiter) Allow(key string) bool {
	rl.mu.Lock()
	now := time.Now()
	if now.Sub(rl.lastCleanup) >= rl.cleanupEvery {
		rl.cleanupLocked(now)
	}
	entry, ok := rl.buckets[key]
	if !ok {
		if len(rl.buckets) >= rl.maxKeys {
			rl.evictOldestLocked()
		}
		entry = &rateLimitEntry{bucket: NewTokenBucket(rl.capacity, rl.refill)}
		entry.element = rl.lru.PushBack(key)
		rl.buckets[key] = entry
	} else {
		rl.lru.MoveToBack(entry.element)
	}
	entry.lastSeen = now
	rl.mu.Unlock()
	return entry.bucket.Allow()
}

func (rl *RateLimiter) cleanupLocked(now time.Time) {
	for element := rl.lru.Front(); element != nil; {
		entry := rl.buckets[element.Value.(string)]
		if now.Sub(entry.lastSeen) < rl.ttl {
			break
		}
		next := element.Next()
		rl.removeElementLocked(element)
		element = next
	}
	rl.lastCleanup = now
}

func (rl *RateLimiter) evictOldestLocked() {
	if element := rl.lru.Front(); element != nil {
		rl.removeElementLocked(element)
	}
}

func (rl *RateLimiter) removeElementLocked(element *list.Element) {
	delete(rl.buckets, element.Value.(string))
	rl.lru.Remove(element)
}

func (rl *RateLimiter) Count() int {
	rl.mu.Lock()
	defer rl.mu.Unlock()
	return len(rl.buckets)
}
