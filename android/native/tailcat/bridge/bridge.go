// Package bridge is the tailcat client behind the app's JNI: one
// tailcat.Client per pair code, raw TCP streams to the host's listener
// as net.Conn, and handles that Kotlin names with an int.
//
// It holds no JNI (jni.go is the whole of that) and is plain Go, so a
// darwin build tests it without an Android device.
package bridge

import (
	"context"
	"errors"
	"fmt"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/tailscale/tailcat"
)

// Poll bounds one Read through JNI, wait included: 0 reads with no bound, as a Go stream does.
var Poll time.Duration

// Read and Write results that are not a count.
const (
	EOF       = -1
	Failed    = -2
	WriteFail = -1
)

// A pair code is "tc" and base64url; HostCodec in :core makes and checks
// the same. A scheme is accepted and dropped here, as the bridge's own
// callers may have one.
func bareCode(code string) string {
	s := strings.TrimSpace(code)
	if len(s) >= len("tailcat://") && strings.EqualFold(s[:len("tailcat://")], "tailcat://") {
		s = s[len("tailcat://"):]
	}
	return s
}

// A client per pair code. Streams counts the handles open through the
// client; the last to close closes it, so that its WireGuard state, its
// sockets and its DERP connection do not outlive the app's use of the
// host. Why not one client for every host: a tailcat client serves one
// server, and each keeps its own stack.
type entry struct {
	code    string
	tc      *tailcat.Client
	built   chan struct{} // closed when the relay lookup ends, done or failed
	err     error         // the lookup's failure
	streams int  // guarded by mu; dials count as one
	closed  bool // guarded by mu: the client is closed, and the entry gone
}

var (
	mu      sync.Mutex
	clients = map[string]*entry{} // by bare pair code
	handles = map[int]*conn{}     // by handle
	lastID  int
	lastErr string
)

// LastError is why the last call that returned a failure failed, or "".
func LastError() string {
	mu.Lock()
	defer mu.Unlock()
	return lastErr
}

// Connect dials port on the host with the pair code code, within
// timeout, and returns the handle of the stream or -1.
//
// The tailcat client is made at once, so that a second stream to the
// same host joins the first one's stack instead of making one. The dial
// blocks: a Kotlin caller runs it off its main thread.
func Connect(rawCode string, port int, timeout time.Duration) (int, error) {
	code := bareCode(rawCode)
	if code == "" {
		return -1, errors.New("tailcat: no pair code")
	}
	if port <= 0 || port > 65535 {
		return -1, fmt.Errorf("tailcat: %d is no port", port)
	}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	e := acquire(code)
	select {
	case <-e.built:
	case <-ctx.Done():
		release(e)
		err := fmt.Errorf("tailcat: finding the relay: %w", ctx.Err())
		NoteError(err)
		return -1, err
	}
	if e.err != nil {
		err := e.err
		NoteError(err)
		release(e)
		return -1, err
	}

	c, err := e.tc.DialTCPPort(ctx, uint16(port))
	if err != nil {
		release(e)
		NoteError(err)
		if errors.Is(err, context.DeadlineExceeded) {
			return -1, fmt.Errorf("tailcat: no answer from the host on port %d: it may not listen over tailcat, or its pair code may have changed", port)
		}
		return -1, fmt.Errorf("tailcat: %w", err)
	}
	h := &conn{Conn: c, e: e}
	mu.Lock()
	lastID++
	id := lastID
	handles[id] = h
	mu.Unlock()
	return id, nil
}

// acquire is the client of code, made on first use with its relay lookup
// under way, and one stream counted on it.
func acquire(code string) *entry {
	mu.Lock()
	defer mu.Unlock()
	e := clients[code]
	if e == nil {
		e = &entry{code: code, built: make(chan struct{})}
		tc := tailcat.NewClient(tailcat.Addr(code))
		e.tc = tc
		go func() {
			addr, err := tailcat.Addr(tc.Server).Resolve(context.Background())
			mu.Lock()
			if err == nil {
				tc.Server = addr
			}
			e.err = err
			mu.Unlock()
			close(e.built)
		}()
		clients[code] = e
	}
	e.streams++
	return e
}

// release gives back one of the client's streams, and closes the client
// under mu when it was the last: none is open, and no dial is underway.
// The lookup goroutine takes mu to write the relay it found, so the
// close does not race it; Client.Close is brief and does not call back
// here, so the mutex is held only across it.
func release(e *entry) {
	mu.Lock()
	defer mu.Unlock()
	e.streams--
	if e.streams > 0 || e.closed {
		return
	}
	e.closed = true
	delete(clients, e.code)
	e.tc.Close()
}

// NoteError records why a bridge call failed, for LastError.
func NoteError(err error) {
	mu.Lock()
	defer mu.Unlock()
	lastErr = ""
	if err != nil {
		lastErr = err.Error()
	}
}

// conn is one stream, and keeps its client open until it ends.
type conn struct {
	net.Conn
	e    *entry
	done atomic.Bool
}

func (c *conn) release() {
	if c.done.CompareAndSwap(false, true) {
		c.Conn.Close()
		release(c.e)
	}
}

func handle(id int) *conn {
	mu.Lock()
	defer mu.Unlock()
	return handles[id]
}

// Read reads a stream, and blocks until data or the stream's end, or
// until Poll, with no data to show for the wait: then it returns 0 and
// ErrPolled. A host that ends the stream first ends it here too, so that
// a Close from another thread, which ends a blocked Read, frees the
// client once.
//
// Why a bound at all: the JNI caller holds its array pinned for the
// length of the call, and while Go's runtime is inside a cgo call from
// one thread, JVM helpers on other threads that pin an array find the Go
// runtime's read lock and stop there. Bounding the call bounds that.
func Read(id int, b []byte) (int, error) {
	c := handle(id)
	if c == nil {
		return 0, errNoStream
	}
	if Poll <= 0 {
		n, err := c.Conn.Read(b)
		if err != nil {
			c.release()
		}
		return n, err
	}
	c.Conn.SetReadDeadline(time.Now().Add(Poll))
	n, err := c.Conn.Read(b)
	if n > 0 {
		return n, nil // The dead data, before any error that came with it.
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() && !errors.Is(err, context.DeadlineExceeded) {
		return 0, ErrPolled
	}
	if err != nil {
		c.release()
	}
	return 0, err
}

// ErrPolled is Read's end of a wait with nothing to show, and not the
// stream's end.
var ErrPolled = errors.New("tailcat: no data yet")

func Write(id int, b []byte) (int, error) {
	c := handle(id)
	if c == nil {
		return 0, errNoStream
	}
	return c.Conn.Write(b)
}

// Close ends a stream; the handle stays, so that a thread blocked in
// Read wakes, and Release forgets it.
func Close(id int) {
	if c := handle(id); c != nil {
		c.release()
	}
}

// Release ends a stream and forgets its handle.
func Release(id int) {
	c := handle(id)
	if c == nil {
		return
	}
	c.release()
	mu.Lock()
	delete(handles, id)
	mu.Unlock()
}

var errNoStream = errors.New("tailcat: no such stream")
