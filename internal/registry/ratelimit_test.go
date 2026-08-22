package registry

import (
	"testing"
	"time"
)

func TestRateLimiterEvictsOldestKeyAtCapacity(t *testing.T) {
	rl := NewRateLimiterWithBounds(2, 1, 2, time.Hour)
	if !rl.Allow("a") || !rl.Allow("b") || !rl.Allow("c") {
		t.Fatal("a new key should replace the oldest key, not fail closed")
	}
	if got := rl.Count(); got != 2 {
		t.Fatalf("bucket count=%d, want 2", got)
	}
}

func TestRateLimiterExpiresInactiveKeys(t *testing.T) {
	rl := NewRateLimiterWithBounds(2, 1, 1, 10*time.Millisecond)
	if !rl.Allow("old") {
		t.Fatal("initial key rejected")
	}
	time.Sleep(20 * time.Millisecond)
	if !rl.Allow("new") {
		t.Fatal("new key rejected after old key expired")
	}
	if got := rl.Count(); got != 1 {
		t.Fatalf("bucket count=%d, want 1", got)
	}
}
