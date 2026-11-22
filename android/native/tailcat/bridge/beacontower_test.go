package bridge

import (
	"bytes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/crypto/chacha20poly1305"
)

func testBeaconCodeWithKey(t *testing.T, relay, token string) (string, [32]byte, [32]byte, [32]byte) {
	t.Helper()
	privKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	var priv, pub, psk [32]byte
	copy(priv[:], privKey.Bytes())
	copy(pub[:], privKey.PublicKey().Bytes())
	if _, err := rand.Read(psk[:]); err != nil {
		t.Fatal(err)
	}
	raw := []byte{pairVersion}
	raw = append(raw, pub[:]...)
	raw = append(raw, psk[:]...)
	raw = binary.BigEndian.AppendUint16(raw, uint16(len(relay)))
	raw = append(raw, relay...)
	raw = binary.BigEndian.AppendUint16(raw, uint16(len(token)))
	raw = append(raw, token...)
	return "beacontower://" + pairCodePrefix + base64.RawURLEncoding.EncodeToString(raw), priv, pub, psk
}

func testBeaconCode(t *testing.T, relay string) string {
	t.Helper()
	code, _, _, _ := testBeaconCodeWithKey(t, relay, "tok")
	return code
}

func TestParseBeaconCode(t *testing.T) {
	code := testBeaconCode(t, "relay.example:7474")
	cases := map[string]string{
		"bare":           code,
		"upper scheme":   "BEACONTOWER://" + strings.TrimPrefix(code, "beacontower://"),
		"core prefix":    strings.TrimPrefix(code, "beacontower://"),
		"host line":      "hyla://beacon?code=" + strings.TrimPrefix(code, "beacontower://"),
		"pair link":      "hyla://pair?code=" + strings.TrimPrefix(code, "beacontower://") + "&name=studio",
		"upper pair link": "HYLA://PAIR?code=" + code + "&name=studio",
		"around spaces":  "  " + code + "\n",
	}
	for name, in := range cases {
		t.Run(name, func(t *testing.T) {
			if !isBeaconCode(in) {
				t.Fatal("a BeaconTower code is not seen as one")
			}
			key, err := ParseBeaconCode(in)
			if err != nil {
				t.Fatal(err)
			}
			if key.Relay != "relay.example:7474" || key.RelayToken != "tok" {
				t.Fatalf("relay %q, token %q", key.Relay, key.RelayToken)
			}
			if key.HostPub == [32]byte{} || key.PSK == [32]byte{} {
				t.Fatal("a key is empty")
			}
		})
	}
	if isBeaconCode("tc_abc") {
		t.Fatal("a tailcat code is seen as a BeaconTower one")
	}
}

func TestParseBeaconCodeRejects(t *testing.T) {
	code := testBeaconCode(t, "relay.example")
	cut := strings.TrimPrefix(code, "beacontower://")
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(cut, pairCodePrefix))
	if err != nil {
		t.Fatal(err)
	}
	wrongVersion := append([]byte{pairVersion + 1}, raw[1:]...)
	bare := func(b []byte) string {
		return "beacontower://" + pairCodePrefix + base64.RawURLEncoding.EncodeToString(b)
	}
	for name, in := range map[string]string{
		"not base64":   "beacontower://" + pairCodePrefix + "!!!",
		"cut short":    bare(raw[:60]),
		"later":        bare(wrongVersion),
		"bad relay":    testBeaconCode(t, "relay with space"),
		"no relay":     bare(raw[:67]),
		"tailcat code": "tc123",
	} {
		if _, err := ParseBeaconCode(in); err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}

func TestNormalizeRelay(t *testing.T) {
	cases := map[string]string{
		"relay.example:7474":         "relay.example:7474",
		"relay.example":              "relay.example:7474",
		"tcp://relay.example":        "tcp://relay.example:7474",
		"tls://relay.example:9000":   "tls://relay.example:9000",
		"ws://relay.example":         "ws://relay.example/ws",
		"ws://relay.example:80":      "ws://relay.example/ws",
		"wss://relay.example":        "wss://relay.example/ws",
		"wss://relay.example:443/bt": "wss://relay.example/bt",
		"ws://relay.example:8080/bt": "ws://relay.example:8080/bt",
		"TLS://relay.example:9000":   "tls://relay.example:9000",
		"[::1]:7474":                 "[::1]:7474",
	}
	for in, want := range cases {
		got, err := normalizeRelay(in)
		if err != nil {
			t.Errorf("%q: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("%q: %q, want %q", in, got, want)
		}
	}
	for _, in := range []string{"", "relay example", "ftp://relay.example", "tcp://relay.example/path", "ws://user:pw@relay.example", "tcp://relay.example:0"} {
		if _, err := normalizeRelay(in); err == nil {
			t.Errorf("%q: accepted", in)
		}
	}
}

// TestBeaconStreamEndsTruthfully checks the stream's reads against a host
// that ends it: the data comes first, then the end, never a read that
// reports the end while data waits.
func TestBeaconStreamEndsTruthfully(t *testing.T) {
	c := newBeaconConn(&beaconMux{done: make(chan struct{})}, 1)
	c.push([]byte("HTTP/1.1 200 OK\r\n\r\nhi"))
	c.finish()
	buf := make([]byte, 64)
	var got bytes.Buffer
	for {
		n, err := c.read(buf)
		got.Write(buf[:n])
		if err != nil {
			if err.Error() != "EOF" {
				t.Fatalf("read: %v", err)
			}
			break
		}
		if n == 0 {
			t.Fatal("a read with no data and no error")
		}
	}
	if got.String() != "HTTP/1.1 200 OK\r\n\r\nhi" {
		t.Fatalf("read %q", got.String())
	}
}

func TestBeaconStreamReadDeadline(t *testing.T) {
	c := newBeaconConn(&beaconMux{done: make(chan struct{})}, 1)
	c.readDL = time.Now().Add(20 * time.Millisecond)
	if _, err := c.read(make([]byte, 8)); err == nil {
		t.Fatal("an idle read past its deadline returned no error")
	}
	// The stream survives the deadline: data after it still reads.
	c.push([]byte("ok"))
	buf := make([]byte, 8)
	n, err := c.read(buf)
	if err != nil || string(buf[:n]) != "ok" {
		t.Fatalf("read after deadline: %q %v", buf[:n], err)
	}
}

type failingWriteConn struct {
	net.Conn
	writes int
	err    error
}

func (f *failingWriteConn) Write(p []byte) (int, error) {
	f.writes++
	return 0, f.err
}

func TestBeaconRecordConnLatchesWriteError(t *testing.T) {
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()

	key := bytes.Repeat([]byte{0x42}, aeadKeySize)
	fc := &failingWriteConn{Conn: c1, err: errors.New("boom")}
	rc, err := newRecordConn(fc, key, key)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := rc.Write([]byte("first")); err == nil {
		t.Fatal("expected first Write to fail")
	}
	fc.err = nil
	if _, err := rc.Write([]byte("second")); err == nil {
		t.Fatal("expected latched write error on second Write")
	}
	if fc.writes != 1 {
		t.Fatalf("underlying Conn.Write called %d times after error; want 1", fc.writes)
	}
}

func TestBeaconBridgeReadPollReturnsErrPolled(t *testing.T) {
	prev := Poll
	Poll = 15 * time.Millisecond
	defer func() { Poll = prev }()

	c1, c2 := net.Pipe()
	go func() { _, _ = io.Copy(io.Discard, c2) }()
	defer c1.Close()
	defer c2.Close()
	m := newBeaconMux(c1)
	defer m.close()

	bc := newBeaconConn(m, 1)
	id, err := newHandle(streamConn{bc})
	if err != nil {
		t.Fatal(err)
	}
	defer Release(id)

	n, err := Read(id, make([]byte, 16))
	if n != 0 || !errors.Is(err, ErrPolled) {
		t.Fatalf("Read with Poll returned (%d, %v); want (0, ErrPolled)", n, err)
	}
}

type testBeaconServer struct {
	hostPriv, hostPub, psk [32]byte
	relayToken             string
	requireToken           bool

	mu      sync.Mutex
	tunnels []net.Conn
}

func (s *testBeaconServer) serveConn(raw net.Conn, checkMagic bool) {
	defer raw.Close()
	if checkMagic {
		var magic [4]byte
		if _, err := io.ReadFull(raw, magic[:]); err != nil || string(magic[:]) != relayMagic {
			return
		}
	}
	var nonce [32]byte
	_, _ = rand.Read(nonce[:])
	var hello [65]byte
	hello[0] = 1
	copy(hello[32:64], nonce[:])
	if s.requireToken {
		hello[64] = flagTokenRequired
	}
	if err := writeCtrl(raw, msgServerHello, hello[:]); err != nil {
		return
	}
	typ, payload, err := readCtrl(raw)
	if err != nil || typ != msgConnect || len(payload) != 48 {
		return
	}
	wantHostID := hostIDFromPub(s.hostPub)
	if !bytes.Equal(payload[:16], wantHostID[:]) {
		_ = writeCtrl(raw, msgErr, []byte("unknown host"))
		return
	}
	var digest [32]byte
	if s.requireToken {
		digest = tokenDigest(s.relayToken)
	}
	wantProof := clientTokenProof(digest, nonce, wantHostID)
	if !hmac.Equal(payload[16:], wantProof[:]) {
		_ = writeCtrl(raw, msgErr, []byte("bad token"))
		return
	}
	if err := writeCtrl(raw, msgOK, nil); err != nil {
		return
	}

	// Host-side 1-RTT X25519 + PSK handshake.
	var hdr [35]byte
	if _, err := io.ReadFull(raw, hdr[:]); err != nil || hdr[0] != 1 {
		return
	}
	var cePub [32]byte
	copy(cePub[:], hdr[1:33])
	ctLen := int(binary.BigEndian.Uint16(hdr[33:35]))
	initCT := make([]byte, ctLen)
	if _, err := io.ReadFull(raw, initCT); err != nil {
		return
	}
	dhES, err := x25519(s.hostPriv, cePub)
	if err != nil {
		return
	}
	hsKey, err := deriveHandshakeKey(s.psk, dhES, s.hostPub, cePub)
	if err != nil {
		return
	}
	hsAEAD, err := chacha20poly1305.New(hsKey)
	if err != nil {
		return
	}
	initAAD := append(append([]byte("bt-e2e-init-v1"), s.hostPub[:]...), cePub[:]...)
	initPlain, err := hsAEAD.Open(nil, make([]byte, chacha20poly1305.NonceSize), initCT, initAAD)
	if err != nil || len(initPlain) != 24 {
		return
	}
	heKey, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return
	}
	var hePriv, hePub [32]byte
	copy(hePriv[:], heKey.Bytes())
	copy(hePub[:], heKey.PublicKey().Bytes())
	dhEE, err := x25519(hePriv, cePub)
	if err != nil {
		return
	}
	c2h, h2c, tr, err := sessionKeys(s.psk, dhES, dhEE, s.hostPub, cePub, hePub)
	if err != nil {
		return
	}
	respAEAD, err := chacha20poly1305.New(h2c)
	if err != nil {
		return
	}
	respAAD := append([]byte("bt-e2e-resp-v1"), tr[:]...)
	respCT := respAEAD.Seal(nil, make([]byte, chacha20poly1305.NonceSize), initPlain[8:], respAAD)
	resp := make([]byte, 35+len(respCT))
	resp[0] = 1
	copy(resp[1:33], hePub[:])
	binary.BigEndian.PutUint16(resp[33:35], uint16(len(respCT)))
	copy(resp[35:], respCT)
	if _, err := raw.Write(resp); err != nil {
		return
	}

	s.mu.Lock()
	s.tunnels = append(s.tunnels, raw)
	s.mu.Unlock()

	srvConn := newTestHostRecordConn(raw, h2c, c2h)
	s.serveMux(srvConn)
}

type testHostRecordConn struct {
	net.Conn
	r    cipher.AEAD
	rseq uint64
	rbuf []byte
	wmu  sync.Mutex
	w    cipher.AEAD
	wseq uint64
}

func newTestHostRecordConn(raw net.Conn, writeKey, readKey []byte) *testHostRecordConn {
	w, _ := chacha20poly1305.New(writeKey)
	r, _ := chacha20poly1305.New(readKey)
	return &testHostRecordConn{Conn: raw, w: w, wseq: 1, r: r, rseq: 1}
}

func (c *testHostRecordConn) Read(p []byte) (int, error) {
	for len(c.rbuf) == 0 {
		var lb [4]byte
		if _, err := io.ReadFull(c.Conn, lb[:]); err != nil {
			return 0, err
		}
		n := int(binary.BigEndian.Uint32(lb[:]))
		buf := make([]byte, n)
		if _, err := io.ReadFull(c.Conn, buf); err != nil {
			return 0, err
		}
		nonce, aad := recordNonceAAD(dirClientToHost, c.rseq)
		plain, err := c.r.Open(buf[:0], nonce[:], buf, aad[:])
		if err != nil {
			return 0, err
		}
		c.rseq++
		c.rbuf = plain
	}
	n := copy(p, c.rbuf)
	c.rbuf = c.rbuf[n:]
	return n, nil
}

func (c *testHostRecordConn) Write(p []byte) (int, error) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	written := 0
	for len(p) > 0 {
		chunk := p
		if len(chunk) > recordPlainLimit {
			chunk = chunk[:recordPlainLimit]
		}
		nonce, aad := recordNonceAAD(dirHostToClient, c.wseq)
		c.wseq++
		buf := make([]byte, 4, 4+len(chunk)+aeadOverhead)
		ct := c.w.Seal(buf[4:4], nonce[:], chunk, aad[:])
		binary.BigEndian.PutUint32(buf[:4], uint32(len(ct)))
		if _, err := c.Conn.Write(append(buf, ct...)); err != nil {
			return written, err
		}
		written += len(chunk)
		p = p[len(chunk):]
	}
	return written, nil
}

func writeTestMuxFrame(c *testHostRecordConn, typ byte, id, val uint32, payload []byte) error {
	buf := make([]byte, muxHeaderLen+len(payload))
	buf[0] = typ
	binary.BigEndian.PutUint32(buf[1:5], id)
	binary.BigEndian.PutUint32(buf[5:9], val)
	copy(buf[9:], payload)
	_, err := c.Write(buf)
	return err
}

func (s *testBeaconServer) serveMux(c *testHostRecordConn) {
	var hdr [muxHeaderLen]byte
	for {
		if _, err := io.ReadFull(c, hdr[:]); err != nil {
			return
		}
		typ := hdr[0]
		id := binary.BigEndian.Uint32(hdr[1:5])
		val := binary.BigEndian.Uint32(hdr[5:9])
		var payload []byte
		switch typ {
		case muxOpen, muxOpenErr, muxData:
			payload = make([]byte, val)
			if _, err := io.ReadFull(c, payload); err != nil {
				return
			}
		}
		switch typ {
		case muxOpen:
			_ = writeTestMuxFrame(c, muxOpenOK, id, 0, nil)
		case muxData:
			// Grant window back immediately and echo the chunk.
			_ = writeTestMuxFrame(c, muxWindow, id, uint32(len(payload)), nil)
			for off := 0; off < len(payload); off += muxMaxDataFrame {
				end := off + muxMaxDataFrame
				if end > len(payload) {
					end = len(payload)
				}
				chunk := payload[off:end]
				if err := writeTestMuxFrame(c, muxData, id, uint32(len(chunk)), chunk); err != nil {
					return
				}
			}
		case muxFin:
			_ = writeTestMuxFrame(c, muxFin, id, 0, nil)
		case muxPing:
			_ = writeTestMuxFrame(c, muxPong, 0, val, nil)
		}
	}
}

func (s *testBeaconServer) closeTunnels() {
	s.mu.Lock()
	ts := append([]net.Conn(nil), s.tunnels...)
	s.tunnels = nil
	s.mu.Unlock()
	for _, c := range ts {
		_ = c.Close()
	}
}

func TestBeaconConnectFailureNotCachedAndRecovers(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()

	code, priv, pub, psk := testBeaconCodeWithKey(t, addr, "tok")
	t.Cleanup(func() { dropTunnel(canonicalBeaconCode(code), nil) })

	if _, err := Connect(code, 7297, 2*time.Second); err == nil {
		t.Fatal("expected initial Connect to closed relay to fail")
	}

	beaconMu.Lock()
	_, cached := beaconTunnels[canonicalBeaconCode(code)]
	beaconMu.Unlock()
	if cached {
		t.Fatal("failed BeaconTower tunnel remained cached in beaconTunnels")
	}

	// Bring up the relay+host on the exact same address; retry must now succeed.
	ln2, err := net.Listen("tcp", addr)
	if err != nil {
		t.Skipf("could not rebind %s: %v", addr, err)
	}
	defer ln2.Close()

	srv := &testBeaconServer{hostPriv: priv, hostPub: pub, psk: psk, relayToken: "tok", requireToken: true}
	go func() {
		for {
			c, err := ln2.Accept()
			if err != nil {
				return
			}
			go srv.serveConn(c, true)
		}
	}()

	id, err := Connect(code, 7297, 3*time.Second)
	if err != nil {
		t.Fatalf("recovery Connect failed: %v", err)
	}
	defer func() {
		Release(id)
		srv.closeTunnels()
	}()

	if _, err := Write(id, []byte("ping")); err != nil {
		t.Fatalf("Write: %v", err)
	}
	buf := make([]byte, 16)
	n, err := Read(id, buf)
	if err != nil || string(buf[:n]) != "ping" {
		t.Fatalf("Read = (%q, %v); want (\"ping\", nil)", buf[:n], err)
	}
}

func TestBeaconConnectEndToEndTCPAndWS(t *testing.T) {
	runCase := func(t *testing.T, mode string, requireToken bool) {
		var (
			relay string
			priv  [32]byte
			pub   [32]byte
			psk   [32]byte
			code  string
			srv   *testBeaconServer
		)
		srv = &testBeaconServer{relayToken: "secret", requireToken: requireToken}

		if mode == "tcp" {
			ln, err := net.Listen("tcp", "127.0.0.1:0")
			if err != nil {
				t.Fatal(err)
			}
			defer ln.Close()
			relay = ln.Addr().String()
			go func() {
				for {
					c, err := ln.Accept()
					if err != nil {
						return
					}
					go srv.serveConn(c, true)
				}
			}()
		} else {
			ts := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{CompressionMode: websocket.CompressionDisabled})
				if err != nil {
					return
				}
				ws.SetReadLimit(128 << 10)
				srv.serveConn(websocket.NetConn(r.Context(), ws, websocket.MessageBinary), false)
			}))
			defer ts.Close()
			relay = "ws://" + strings.TrimPrefix(ts.URL, "http://") + "/ws"
		}

		code, priv, pub, psk = testBeaconCodeWithKey(t, relay, "secret")
		srv.hostPriv, srv.hostPub, srv.psk = priv, pub, psk

		// Test hyla://pair?... scanning input form end-to-end as well.
		pairURI := "hyla://pair?code=" + strings.TrimPrefix(code, "beacontower://") + "&name=desktop"
		id, err := Connect(pairURI, 7297, 5*time.Second)
		if err != nil {
			t.Fatalf("%s Connect failed: %v", mode, err)
		}

		// >32 KiB payload verifies AEAD multi-record write and WS SetReadLimit(128<<10).
		payload := bytes.Repeat([]byte("0123456789abcdef"), 2560) // 40 KiB
		if _, err := Write(id, payload); err != nil {
			t.Fatalf("%s Write: %v", mode, err)
		}
		var got bytes.Buffer
		buf := make([]byte, 8192)
		for got.Len() < len(payload) {
			n, err := Read(id, buf)
			if err != nil {
				t.Fatalf("%s Read after %d bytes: %v", mode, got.Len(), err)
			}
			got.Write(buf[:n])
		}
		if !bytes.Equal(got.Bytes(), payload) {
			t.Fatalf("%s echo mismatch (%d vs %d bytes)", mode, got.Len(), len(payload))
		}
		Release(id)

		// Closing server tunnel must evict the cached mux so a new Connect recovers cleanly.
		srv.closeTunnels()
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			beaconMu.Lock()
			_, exists := beaconTunnels[canonicalBeaconCode(pairURI)]
			beaconMu.Unlock()
			if !exists {
				break
			}
			time.Sleep(10 * time.Millisecond)
		}
		id2, err := Connect(code, 7297, 5*time.Second)
		if err != nil {
			t.Fatalf("%s reconnect after server tunnel drop failed: %v", mode, err)
		}
		defer Release(id2)
		if _, err := Write(id2, []byte("again")); err != nil {
			t.Fatalf("%s Write2: %v", mode, err)
		}
		n, err := Read(id2, buf[:16])
		if err != nil || string(buf[:n]) != "again" {
			t.Fatalf("%s Read2 = (%q, %v); want \"again\"", mode, buf[:n], err)
		}
		srv.closeTunnels()
	}

	t.Run("tcp-no-token", func(t *testing.T) { runCase(t, "tcp", false) })
	t.Run("ws-large-payload-with-token", func(t *testing.T) { runCase(t, "ws", true) })
}
