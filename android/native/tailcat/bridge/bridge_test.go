package bridge

import (
	"bufio"
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/tailscale/tailcat"
)

// startHost listens on port of a tailcat server, echo-prefixed, as a
// hylad host does, and answers with the server's pair code. The test
// needs the public DERP relays, as a device does.
func startHost(t *testing.T, port int) string {
	t.Helper()
	s := &tailcat.Server{}
	ln, err := s.Listen(context.Background(), "tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				line, err := bufio.NewReader(c).ReadString('\n')
				if err != nil {
					return
				}
				fmt.Fprintf(c, "echo %s", line)
			}()
		}
	}()
	return string(s.TailcatAddr())
}

func TestConnectEchoesOverTailcat(t *testing.T) {
	code := startHost(t, 18080)
	id, err := Connect("tailcat://"+code, 18080, 90*time.Second)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer Release(id)

	if _, err := Write(id, []byte("hello over tailcat\n")); err != nil {
		t.Fatalf("write: %v", err)
	}
	buf := make([]byte, 64)
	n, err := Read(id, buf)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if got, want := string(buf[:n]), "echo hello over tailcat\n"; got != want {
		t.Fatalf("read %q, want %q", got, want)
	}

	if _, err := Read(id, buf); err == nil {
		t.Fatal("read after the host's reply should end with EOF or a close")
	}
	mu.Lock()
	defer mu.Unlock()
	if len(clients) != 0 {
		t.Fatalf("the last stream's end should close its client, %d remain", len(clients))
	}
}
