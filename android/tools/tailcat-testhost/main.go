// Command tailcat-testhost is a tailcat host for the app's JVM tests: what hylad is to the app,
// as little of it as a test needs. It picks a free port, prints "pair=<pair code> port=<port>"
// and listens there over tailcat, on the public relays.
//
// A connection reads lines and gets each back as "echo " and the line. A GET is a WebSocket
// opening handshake, answered with 101, and then the frames after it: each text or binary frame
// comes back as a text frame of the same payload, and a close frame closes the connection.
//
// It runs under `go run` from tools/tailcat-testhost/go.mod, which the tests' gradle build makes
// up to date first: see app/build.gradle.kts.
package main

import (
	"bufio"
	"context"
	"crypto/sha1"
	"encoding/base64"
	"encoding/binary"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"strings"

	"github.com/tailscale/tailcat"
)

func main() {
	log.SetOutput(io.Discard) // tailcat's log lines would eat the test's.
	port := 0
	if len(os.Args) > 1 {
		fmt.Sscan(os.Args[1], &port)
	}
	s := &tailcat.Server{}
	ln, err := s.Listen(context.Background(), "tcp", fmt.Sprintf(":%d", port))
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
	defer s.Close()
	got := ln.Addr().(*net.TCPAddr).Port
	fmt.Printf("pair=%s port=%d\n", s.TailcatAddr(), got)
	os.Stdout.Sync()
	for {
		c, err := ln.Accept()
		if err != nil {
			return
		}
		go serve(c)
	}
}

func serve(c net.Conn) {
	defer c.Close()
	r := bufio.NewReader(c)
	line, err := r.ReadString('\n')
	if err != nil {
		return
	}
	if !strings.HasPrefix(line, "GET ") {
		for {
			fmt.Fprintf(c, "echo %s", line)
			if line, err = r.ReadString('\n'); err != nil {
				return
			}
		}
	}
	key := ""
	for {
		h, err := r.ReadString('\n')
		if err != nil {
			return
		}
		if k, v, ok := strings.Cut(h, ":"); ok && strings.EqualFold(strings.TrimSpace(k), "Sec-WebSocket-Key") {
			key = strings.TrimSpace(v)
		}
		if h == "\r\n" {
			break
		}
	}
	sum := sha1.Sum([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
	fmt.Fprintf(c, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"+
		"Sec-WebSocket-Accept: %s\r\n\r\n", base64.StdEncoding.EncodeToString(sum[:]))
	for {
		op, payload, err := readFrame(r)
		if err != nil || op == 0x8 {
			return
		}
		writeFrame(c, 0x1, payload)
	}
}

func readFrame(r *bufio.Reader) (op byte, payload []byte, err error) {
	h := make([]byte, 2)
	if _, err = io.ReadFull(r, h); err != nil {
		return
	}
	op, n := h[0]&0x0f, uint64(h[1]&0x7f)
	switch n {
	case 126:
		var ext [2]byte
		if _, err = io.ReadFull(r, ext[:]); err != nil {
			return
		}
		n = uint64(binary.BigEndian.Uint16(ext[:]))
	case 127:
		var ext [8]byte
		if _, err = io.ReadFull(r, ext[:]); err != nil {
			return
		}
		n = binary.BigEndian.Uint64(ext[:])
	}
	var mask [4]byte
	if h[1]&0x80 != 0 {
		if _, err = io.ReadFull(r, mask[:]); err != nil {
			return
		}
	}
	payload = make([]byte, n)
	if _, err = io.ReadFull(r, payload); err != nil {
		return
	}
	for i := range payload {
		payload[i] ^= mask[i%4]
	}
	return
}

func writeFrame(c net.Conn, op byte, payload []byte) {
	head := []byte{0x80 | op}
	switch n := len(payload); {
	case n < 126:
		head = append(head, byte(n))
	case n < 1<<16:
		head = append(head, 126, byte(n>>8), byte(n))
	default:
		head = append(head, 127)
		head = binary.BigEndian.AppendUint64(head, uint64(n))
	}
	c.Write(append(head, payload...))
}
