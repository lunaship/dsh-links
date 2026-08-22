package main

import "testing"

func TestIsLoopbackListen(t *testing.T) {
	tests := []struct {
		addr string
		want bool
	}{
		{"127.0.0.1:8080", true},
		{"[::1]:8080", true},
		{"localhost:8080", true},
		{"0.0.0.0:8080", false},
		{"127.evil.example:8080", false},
		{"example.com:8080", false},
		{"missing-port", false},
	}
	for _, tt := range tests {
		t.Run(tt.addr, func(t *testing.T) {
			if got := isLoopbackListen(tt.addr); got != tt.want {
				t.Fatalf("isLoopbackListen(%q)=%v want %v", tt.addr, got, tt.want)
			}
		})
	}
}
