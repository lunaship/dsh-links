package bridge

import (
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"
)

const (
	bufSize      = 32 * 1024
	idleTimeout  = 5 * time.Minute
	writeTimeout = 30 * time.Second
)

// Bridge copies bidirectional between a and b. Idle is measured on the whole
// connection: a successful read or write on either direction refreshes activity.
// Max lifetime is still enforced independently of idle.
func Bridge(a, b net.Conn, onRx func(int64), onTx func(int64), maxLifetime time.Duration) error {
	return BridgeWithTimeouts(a, b, onRx, onTx, maxLifetime, idleTimeout, writeTimeout)
}

func BridgeWithTimeouts(a, b net.Conn, onRx func(int64), onTx func(int64), maxLifetime, idle, writeWait time.Duration) error {
	if idle <= 0 {
		idle = idleTimeout
	}
	if writeWait <= 0 {
		writeWait = writeTimeout
	}

	var lifetimeTimer *time.Timer
	if maxLifetime > 0 {
		lifetimeTimer = time.AfterFunc(maxLifetime, func() {
			_ = a.Close()
			_ = b.Close()
		})
		defer lifetimeTimer.Stop()
	}

	var lastUnix atomic.Int64
	lastUnix.Store(time.Now().UnixNano())
	bump := func() { lastUnix.Store(time.Now().UnixNano()) }

	stopWatch := make(chan struct{})
	var watchOnce sync.Once
	stopWatcher := func() { watchOnce.Do(func() { close(stopWatch) }) }
	defer stopWatcher()

	go func() {
		ticker := time.NewTicker(idle / 10)
		if idle/10 < 10*time.Millisecond {
			ticker.Reset(10 * time.Millisecond)
		}
		defer ticker.Stop()
		for {
			select {
			case <-stopWatch:
				return
			case <-ticker.C:
				last := time.Unix(0, lastUnix.Load())
				if time.Since(last) > idle {
					_ = a.Close()
					_ = b.Close()
					return
				}
			}
		}
	}()

	errCh := make(chan error, 2)
	var wg sync.WaitGroup
	wg.Add(2)

	copyFn := func(dst, src net.Conn, cb func(int64)) {
		defer wg.Done()
		buf := make([]byte, bufSize)
		for {
			n, err := src.Read(buf)
			if n > 0 {
				bump()
				_ = dst.SetWriteDeadline(time.Now().Add(writeWait))
				if werr := writeAll(dst, buf[:n]); werr != nil {
					errCh <- werr
					return
				}
				bump()
				if cb != nil {
					cb(int64(n))
				}
			}
			if err != nil {
				if err != io.EOF {
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

	err := <-errCh
	_ = a.Close()
	_ = b.Close()
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
