package bridge

import (
	"io"
	"net"
	"sync"
	"time"
)

// Bridge copies bidirectional between a and b with idle timeout and buffered limits.
// 32 KiB buffer, 5 minute idle, max lifetime enforced via done channel.
const (
	bufSize     = 32 * 1024
	idleTimeout = 5 * time.Minute
)

// Bridge runs until one side closes or idle/max-lifetime timeout.
// It counts bytes via callbacks.
func Bridge(a, b net.Conn, onRx func(int64), onTx func(int64), maxLifetime time.Duration) error {
	var lifetimeTimer *time.Timer
	if maxLifetime > 0 {
		lifetimeTimer = time.AfterFunc(maxLifetime, func() {
			_ = a.Close()
			_ = b.Close()
		})
		defer lifetimeTimer.Stop()
	}
	// Set initial deadline for idle? We'll use per-copy timeout handling.
	// Use goroutines with copy.
	errCh := make(chan error, 2)
	var wg sync.WaitGroup
	wg.Add(2)

	copyFn := func(dst, src net.Conn, cb func(int64)) {
		defer wg.Done()
		buf := make([]byte, bufSize)
		for {
			// Set read deadline for idle
			_ = src.SetReadDeadline(time.Now().Add(idleTimeout))
			n, err := src.Read(buf)
			if n > 0 {
				// clear deadline for write? set write deadline
				_ = dst.SetWriteDeadline(time.Now().Add(30 * time.Second))
				if werr := writeAll(dst, buf[:n]); werr != nil {
					errCh <- werr
					return
				}
				if cb != nil {
					cb(int64(n))
				}
				// reset deadlines
				_ = src.SetReadDeadline(time.Now().Add(idleTimeout))
			}
			if err != nil {
				if err != io.EOF {
					// Check if timeout due to idle vs closed
					// Propagate
					errCh <- err
				} else {
					errCh <- nil
				}
				return
			}
		}
	}

	go copyFn(b, a, onRx)
	go copyFn(a, b, onTx)

	// Wait for one side to finish, then close both
	// First error
	err := <-errCh
	// Close both to unblock other
	_ = a.Close()
	_ = b.Close()
	// Drain the second copier before returning so no goroutine or send remains.
	<-errCh
	wg.Wait()
	return err
}

func writeAll(dst io.Writer, data []byte) error {
	for len(data) > 0 {
		n, err := dst.Write(data)
		if err != nil {
			return err
		}
		if n == 0 {
			return io.ErrShortWrite
		}
		data = data[n:]
	}
	return nil
}
