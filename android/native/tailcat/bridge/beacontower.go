package bridge

// BeaconTower behind the app's JNI: one end-to-end encrypted tunnel per pair
// code, multiplexed into streams by target, as hyla speaks it
// (hyla/internal/beacontower). The phone reaches a host's HylaPocket server
// as the stream target "pocket"; the relay only carries the tunnel, and the
// host is the only peer that can read through it.
//
// The wire is hyla's, copied here because the app's module does not import
// hyla: its relay control frames, its 1-RTT handshake (X25519 + PSK + AEAD
// records) and its credit-based mux. hyla's test suite and the live test
// behind it are what keep the two in step.

import (
	"bytes"
	"context"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/coder/websocket"
	"golang.org/x/crypto/chacha20poly1305"
)

const (
	pairCodePrefix  = "bt1_"
	pairVersion     = 1
	maxPairFieldLen = 512
	relayMagic      = "BT01"
	pocketTarget    = "pocket"
	// beaconDialWait bounds a tunnel's dial when the caller's timeout is shorter than one
	// handshake: the tunnel outlives the stream that asked for it.
	beaconDialWait = 20 * time.Second
)

// BeaconKey is everything a pairing code carries: where the relay is, the
// host's identity key and pre-shared key, and the relay's optional token.
type BeaconKey struct {
	Relay      string // normalized, as hyla's ParseRelayAddr writes it
	HostPub    [32]byte
	PSK        [32]byte
	RelayToken string
}

func stripBeaconScheme(raw string) string {
	s := strings.TrimSpace(raw)
	if len(s) >= len("beacontower://") && strings.EqualFold(s[:len("beacontower://")], "beacontower://") {
		s = strings.TrimSpace(s[len("beacontower://"):])
	}
	for _, prefix := range []string{"hyla://pair?", "hyla://beacon?"} {
		if len(s) >= len(prefix) && strings.EqualFold(s[:len(prefix)], prefix) {
			values, err := url.ParseQuery(s[len(prefix):])
			if err != nil {
				return ""
			}
			code := strings.TrimSpace(values.Get("code"))
			if len(code) >= len("beacontower://") && strings.EqualFold(code[:len("beacontower://")], "beacontower://") {
				code = strings.TrimSpace(code[len("beacontower://"):])
			}
			return code
		}
	}
	return s
}

func canonicalBeaconCode(raw string) string {
	return stripBeaconScheme(raw)
}

// isBeaconCode tells a BeaconTower code, "bt1_…", from a tailcat one, "tc…":
// both are base64url, but the prefix is the code's version and kind.
func isBeaconCode(s string) bool {
	return strings.HasPrefix(stripBeaconScheme(s), pairCodePrefix)
}

// ParseBeaconCode reads "bt1_…", "beacontower://bt1_…", or a "hyla://pair?code=…" /
// "hyla://beacon?code=…" URI, as hyla's ParsePairCode does.
func ParseBeaconCode(code string) (BeaconKey, error) {
	var k BeaconKey
	s := stripBeaconScheme(code)
	if !strings.HasPrefix(s, pairCodePrefix) {
		return k, errors.New("beacontower: not a BeaconTower code")
	}
	raw, err := base64.RawURLEncoding.DecodeString(strings.TrimPrefix(s, pairCodePrefix))
	if err != nil {
		return k, errors.New("beacontower: the code is not base64url")
	}
	if len(raw) < 70 {
		return k, errors.New("beacontower: the code is cut short")
	}
	if raw[0] != pairVersion {
		return k, fmt.Errorf("beacontower: code version %d is not known", raw[0])
	}
	copy(k.HostPub[:], raw[1:33])
	copy(k.PSK[:], raw[33:65])
	pos := 65
	relayLen := int(binary.BigEndian.Uint16(raw[pos : pos+2]))
	pos += 2
	if relayLen == 0 || relayLen > maxPairFieldLen || pos+relayLen+2 > len(raw) {
		return k, errors.New("beacontower: the code has no relay")
	}
	relay := string(raw[pos : pos+relayLen])
	pos += relayLen
	tokenLen := int(binary.BigEndian.Uint16(raw[pos : pos+2]))
	pos += 2
	if tokenLen > maxPairFieldLen || pos+tokenLen != len(raw) {
		return k, errors.New("beacontower: the code's token is broken")
	}
	k.RelayToken = string(raw[pos:])
	if k.Relay, err = normalizeRelay(relay); err != nil {
		return k, err
	}
	if _, err := ecdh.X25519().NewPublicKey(k.HostPub[:]); err != nil {
		return k, errors.New("beacontower: the code's host key is broken")
	}
	if k.PSK == ([32]byte{}) {
		return k, errors.New("beacontower: the code has no key")
	}
	return k, nil
}

// normalizeRelay reads host:port, tcp://, tls://, ws:// or wss:// as hyla's
// ParseRelayAddr does, and returns it in the form a host's code carries.
func normalizeRelay(raw string) (string, error) {
	s := strings.TrimSpace(raw)
	if s == "" {
		return "", errors.New("beacontower: the relay address is empty")
	}
	for _, r := range s {
		if r <= ' ' || r == 0x7f {
			return "", errors.New("beacontower: the relay address has a space")
		}
	}
	explicit := strings.Contains(s, "://")
	input := s
	if !explicit {
		input = "tcp://" + s
	}
	u, err := url.Parse(input)
	if err != nil {
		return "", fmt.Errorf("beacontower: relay address: %v", err)
	}
	if u.User != nil {
		return "", errors.New("beacontower: the relay address has credentials")
	}
	if u.RawQuery != "" || u.Fragment != "" {
		return "", errors.New("beacontower: the relay address has a query")
	}
	scheme := strings.ToLower(u.Scheme)
	host := strings.TrimSpace(u.Hostname())
	if host == "" || strings.HasPrefix(host, "-") {
		return "", errors.New("beacontower: the relay address has no host")
	}
	port := u.Port()
	if port != "" {
		if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
			return "", fmt.Errorf("beacontower: the relay port %q is not one", port)
		}
	}
	switch scheme {
	case "tcp", "tls":
		if u.Path != "" && u.Path != "/" {
			return "", fmt.Errorf("beacontower: a %s relay address takes no path", scheme)
		}
		if port == "" {
			port = "7474"
		}
		hp := net.JoinHostPort(host, port)
		if explicit {
			return scheme + "://" + hp, nil
		}
		return hp, nil
	case "ws", "wss":
		p := u.EscapedPath()
		if p == "" || p == "/" {
			p = "/ws"
		}
		if port == "" {
			if scheme == "wss" {
				return "wss://" + formatHost(host) + p, nil
			}
			return "ws://" + formatHost(host) + p, nil
		}
		if (scheme == "wss" && port == "443") || (scheme == "ws" && port == "80") {
			return scheme + "://" + formatHost(host) + p, nil
		}
		return scheme + "://" + net.JoinHostPort(host, port) + p, nil
	}
	return "", fmt.Errorf("beacontower: the relay scheme %q is no tcp, tls, ws or wss", scheme)
}

func formatHost(host string) string {
	if strings.Contains(host, ":") {
		return "[" + host + "]"
	}
	return host
}

// ---- the relay's control frames, as hyla's protocol.go writes them ----

const (
	msgServerHello byte = 0x01
	msgConnect     byte = 0x03
	msgOK          byte = 0x06
	msgErr         byte = 0x07
	msgPing        byte = 0x08
	msgPong        byte = 0x09

	flagTokenRequired byte = 0x01
	ctrlMaxPayload         = 4096
)

func hostIDFromPub(pub [32]byte) [16]byte {
	sum := sha256.Sum256(append([]byte("bt-host-id-v1"), pub[:]...))
	var id [16]byte
	copy(id[:], sum[:16])
	return id
}

func tokenDigest(token string) [32]byte {
	if token == "" {
		return [32]byte{}
	}
	return sha256.Sum256(append([]byte("bt-tok-v1"), token...))
}

// clientTokenProof answers the relay's challenge for the host the client
// asks for, with the token's digest: the token itself never goes out.
func clientTokenProof(digest, nonce [32]byte, hostID [16]byte) [32]byte {
	mac := hmac.New(sha256.New, digest[:])
	mac.Write([]byte("bt-cli-v1"))
	mac.Write(nonce[:])
	mac.Write(hostID[:])
	var out [32]byte
	copy(out[:], mac.Sum(nil))
	return out
}

func writeCtrl(w io.Writer, typ byte, payload []byte) error {
	buf := make([]byte, 3+len(payload))
	buf[0] = typ
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	_, err := w.Write(buf)
	return err
}

func readCtrl(r io.Reader) (byte, []byte, error) {
	var hdr [3]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, err
	}
	n := int(binary.BigEndian.Uint16(hdr[1:3]))
	if n > ctrlMaxPayload {
		return 0, nil, errors.New("beacontower: control frame too large")
	}
	payload := make([]byte, n)
	if _, err := io.ReadFull(r, payload); err != nil {
		return 0, nil, err
	}
	return hdr[0], payload, nil
}

// readServerHello is the relay's hello: the key its challenge is signed
// under, the challenge CONNECT answers, and what it asks of a client.
func readServerHello(r io.Reader) (nonce [32]byte, flags byte, err error) {
	typ, payload, err := readCtrl(r)
	if err != nil {
		return nonce, 0, err
	}
	if typ != msgServerHello || len(payload) != 65 {
		return nonce, 0, errors.New("beacontower: the relay's hello is broken")
	}
	copy(nonce[:], payload[32:64])
	return nonce, payload[64], nil
}

func readOK(r io.Reader) error {
	typ, payload, err := readCtrl(r)
	if err != nil {
		return err
	}
	switch typ {
	case msgOK:
		return nil
	case msgErr:
		msg := strings.TrimSpace(string(payload))
		if msg == "" {
			msg = "the relay refused the connection"
		}
		return fmt.Errorf("beacontower: relay: %s", msg)
	}
	return fmt.Errorf("beacontower: the relay answered %d", typ)
}

// dialRelay opens a connection to the relay in the transport its address
// names, as hyla's DialRelayWithTLS does: "BT01" on tcp and tls, WebSocket
// on ws and wss.
func dialRelay(ctx context.Context, relay string) (net.Conn, error) {
	// A relay's scheme is one of tcp, tls, ws and wss, which url.Parse
	// splits off; a bare host:port comes here as a tcp one.
	scheme, rest := "tcp", relay
	if i := strings.Index(relay, "://"); i >= 0 {
		scheme, rest = relay[:i], relay[i+3:]
	}
	u, err := url.Parse(scheme + "://" + rest)
	if err != nil {
		return nil, err
	}
	d := net.Dialer{Timeout: 10 * time.Second, KeepAlive: 30 * time.Second}
	switch scheme {
	case "tcp", "tls":
		raw, err := d.DialContext(ctx, "tcp", u.Host)
		if err != nil {
			return nil, err
		}
		var c net.Conn = raw
		if u.Scheme == "tls" {
			tc := tls.Client(raw, &tls.Config{ServerName: u.Hostname(), MinVersion: tls.VersionTLS13})
			if err := tc.HandshakeContext(ctx); err != nil {
				_ = raw.Close()
				return nil, err
			}
			c = tc
		}
		if _, err := c.Write([]byte(relayMagic)); err != nil {
			_ = c.Close()
			return nil, err
		}
		return c, nil
	case "ws", "wss":
		ws, _, err := websocket.Dial(ctx, relay, &websocket.DialOptions{CompressionMode: websocket.CompressionDisabled})
		if err != nil {
			return nil, err
		}
		ws.SetReadLimit(128 << 10)
		// Not ctx: the conn lives as long as the tunnel, not the dial.
		return websocket.NetConn(context.Background(), ws, websocket.MessageBinary), nil
	}
	return nil, fmt.Errorf("beacontower: no way to reach a %q relay", u.Scheme)
}

// ---- tunnels: hyla's ClientPool, cut to the one target the app uses ----

// beaconTunnel is the mux of one pair code. Why one per code, shared: a host's
// relay pairs each tunnel, and its E2EE handshake is not repeated for the
// control connection's stream and every pane's. The app's streams are odd,
// as hyla's client's are.
type beaconTunnel struct {
	dialed chan struct{} // closed when the dial ended, done or failed
	mu     sync.Mutex
	mux    *beaconMux
	err    error
	next   uint32 // the last stream ID given out
}

var (
	beaconMu      sync.Mutex
	beaconTunnels = map[string]*beaconTunnel{}
)

func dropTunnel(code string, t *beaconTunnel) {
	beaconMu.Lock()
	if beaconTunnels[code] == t {
		delete(beaconTunnels, code)
	}
	beaconMu.Unlock()
}

// acquireTunnel is the tunnel of code, dialed on first use.
func acquireTunnel(code string, key BeaconKey) (*beaconTunnel, bool) {
	beaconMu.Lock()
	if t := beaconTunnels[code]; t != nil {
		select {
		case <-t.dialed:
			t.mu.Lock()
			dead := t.err != nil || t.mux == nil || t.mux.isClosed()
			t.mu.Unlock()
			if !dead {
				beaconMu.Unlock()
				return t, true
			}
			delete(beaconTunnels, code)
		default:
			beaconMu.Unlock()
			return t, true
		}
	}
	t := &beaconTunnel{dialed: make(chan struct{})}
	beaconTunnels[code] = t
	beaconMu.Unlock()

	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), beaconDialWait)
		defer cancel()
		raw, err := dialRelay(ctx, key.Relay)
		var mux *beaconMux
		if err == nil {
			mux, err = pairTunnel(ctx, raw, key)
		}
		t.mu.Lock()
		t.mux, t.err = mux, err
		t.mu.Unlock()
		close(t.dialed)
		if mux == nil {
			dropTunnel(code, t)
			return
		}
		go func() {
			<-mux.done
			dropTunnel(code, t)
		}()
	}()
	return t, false
}

// pairTunnel asks the relay for the host's tunnel, then speaks E2EE inside
// it: hyla's ClientPool.dialTunnel.
func pairTunnel(ctx context.Context, raw net.Conn, key BeaconKey) (*beaconMux, error) {
	stop := context.AfterFunc(ctx, func() { _ = raw.Close() })
	defer stop()

	nonce, flags, err := readServerHello(raw)
	if err != nil {
		_ = raw.Close()
		return nil, err
	}
	var digest [32]byte
	if flags&flagTokenRequired != 0 {
		if strings.TrimSpace(key.RelayToken) == "" {
			_ = raw.Close()
			return nil, errors.New("beacontower: the relay wants a token this code does not carry")
		}
		digest = tokenDigest(key.RelayToken)
	}
	hostID := hostIDFromPub(key.HostPub)
	proof := clientTokenProof(digest, nonce, hostID)
	req := make([]byte, 48)
	copy(req[:16], hostID[:])
	copy(req[16:], proof[:])
	if err := writeCtrl(raw, msgConnect, req); err != nil {
		_ = raw.Close()
		return nil, err
	}
	if err := readOK(raw); err != nil {
		_ = raw.Close()
		return nil, err
	}
	if !stop() && ctx.Err() != nil {
		_ = raw.Close()
		return nil, ctx.Err()
	}
	enc, err := clientHandshake(raw, key.HostPub, key.PSK)
	if err != nil {
		_ = raw.Close()
		return nil, err
	}
	return newBeaconMux(enc), nil
}

func shouldEvictTunnel(mux *beaconMux, openErr, ctxErr error) bool {
	if mux == nil || mux.isClosed() {
		return true
	}
	if errors.Is(openErr, net.ErrClosed) || errors.Is(openErr, io.EOF) {
		return true
	}
	if errors.Is(ctxErr, context.DeadlineExceeded) && time.Since(mux.lastRecvTime()) >= 5*time.Second {
		return true
	}
	return false
}

// BeaconConnect opens a stream of target on the host of a BeaconTower pair
// code, within timeout, and returns the handle of the stream or -1.
//
// Why target: hyla's host serves its own protocol as "hyla" and HylaPocket's
// as "pocket", and a stream is one HTTP request of the phone's. The call
// blocks: a Kotlin caller runs it off its main thread.
func BeaconConnect(rawCode, target string, timeout time.Duration) (int, error) {
	key, err := ParseBeaconCode(rawCode)
	if err != nil {
		NoteError(err)
		return -1, err
	}
	wait := timeout
	if wait <= 0 {
		wait = beaconDialWait
	}
	canon := canonicalBeaconCode(rawCode)
	for attempt := 0; attempt < 2; attempt++ {
		t, reused := acquireTunnel(canon, key)
		select {
		case <-t.dialed:
		case <-time.After(wait):
			err := errors.New("beacontower: the relay did not pair the host in time")
			NoteError(err)
			return -1, err
		}
		t.mu.Lock()
		mux, dialErr := t.mux, t.err
		t.mu.Unlock()
		if dialErr != nil {
			dropTunnel(canon, t)
			if reused && attempt == 0 {
				continue
			}
			NoteError(dialErr)
			return -1, dialErr
		}
		if mux == nil || mux.isClosed() {
			dropTunnel(canon, t)
			if reused && attempt == 0 {
				continue
			}
			err := net.ErrClosed
			NoteError(err)
			return -1, err
		}

		// Not the stream's context: a stream outlives the wait for its stream to open.
		ctx, cancel := context.WithTimeout(context.Background(), wait)
		t.mu.Lock()
		t.next++
		id := 2*t.next - 1
		t.mu.Unlock()
		conn := newBeaconConn(mux, id)
		openErr := mux.open(ctx, conn, target)
		ctxErr := ctx.Err()
		cancel()
		if openErr != nil {
			if shouldEvictTunnel(mux, openErr, ctxErr) {
				dropTunnel(canon, t)
				mux.close()
				if reused && attempt == 0 {
					continue
				}
			}
			NoteError(openErr)
			return -1, openErr
		}
		return newHandle(streamConn{conn})
	}
	err = net.ErrClosed
	NoteError(err)
	return -1, err
}

// newHandle makes a handle of a BeaconTower stream, in the same table as
// tailcat's: Read and Write reach it as they reach those.
func newHandle(c net.Conn) (int, error) {
	mu.Lock()
	defer mu.Unlock()
	lastID++
	handles[lastID] = &conn{Conn: c}
	return lastID, nil
}

// streamConn makes hyla's MuxStream a net.Conn of the handle: its Close
// ends the stream with a FIN or a reset, where net.Conn's signature needs.
type streamConn struct {
	net.Conn
}

func (c streamConn) Close() error {
	c.Conn.Close()
	return nil
}

// ---- E2EE: hyla's ClientE2EHandshake and aeadConn ----

const (
	dirClientToHost byte = 1
	dirHostToClient byte = 2

	recordPlainLimit = 32 << 10
	aeadOverhead     = chacha20poly1305.Overhead
	aeadKeySize      = chacha20poly1305.KeySize
)

func x25519(priv, pub [32]byte) ([]byte, error) {
	k, err := ecdh.X25519().NewPrivateKey(priv[:])
	if err != nil {
		return nil, err
	}
	p, err := ecdh.X25519().NewPublicKey(pub[:])
	if err != nil {
		return nil, err
	}
	return k.ECDH(p)
}

func deriveHandshakeKey(psk [32]byte, dhES []byte, hostPub, cePub [32]byte) ([]byte, error) {
	info := append(append([]byte("bt-e2e-hs-v1"), hostPub[:]...), cePub[:]...)
	return hkdf.Key(sha256.New, dhES, psk[:], string(info), aeadKeySize)
}

func sessionKeys(psk [32]byte, dhES, dhEE []byte, hostPub, cePub, hePub [32]byte) (c2h, h2c []byte, tr [32]byte, err error) {
	prk, err := hkdf.Extract(sha256.New, append(append([]byte{}, dhES...), dhEE...), psk[:])
	if err != nil {
		return nil, nil, tr, err
	}
	trIn := append(append(append([]byte("bt-e2e-tr-v1"), hostPub[:]...), cePub[:]...), hePub[:]...)
	tr = sha256.Sum256(trIn)
	if c2h, err = hkdf.Expand(sha256.New, prk, "bt-c2h"+string(tr[:]), aeadKeySize); err != nil {
		return nil, nil, tr, err
	}
	if h2c, err = hkdf.Expand(sha256.New, prk, "bt-h2c"+string(tr[:]), aeadKeySize); err != nil {
		return nil, nil, tr, err
	}
	return c2h, h2c, tr, nil
}

// clientHandshake is the 1-RTT handshake: the host proves its key and its
// PSK by answering the client's nonce, and both ephemeral keys make keys of
// this session's own, so a later leak of the host's key opens no past one.
func clientHandshake(raw net.Conn, hostPub, psk [32]byte) (net.Conn, error) {
	key, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	var cePriv, cePub [32]byte
	copy(cePriv[:], key.Bytes())
	copy(cePub[:], key.PublicKey().Bytes())
	dhES, err := x25519(cePriv, hostPub)
	if err != nil {
		return nil, err
	}
	hsKey, err := deriveHandshakeKey(psk, dhES, hostPub, cePub)
	if err != nil {
		return nil, err
	}
	hs, err := chacha20poly1305.New(hsKey)
	if err != nil {
		return nil, err
	}
	// The host refuses a clock more than five minutes off its own.
	initPlain := make([]byte, 24)
	binary.BigEndian.PutUint64(initPlain[:8], uint64(time.Now().Unix()))
	if _, err := rand.Read(initPlain[8:]); err != nil {
		return nil, err
	}
	initAAD := append(append([]byte("bt-e2e-init-v1"), hostPub[:]...), cePub[:]...)
	pkt := make([]byte, 35, 35+24+aeadOverhead)
	pkt[0] = 1
	copy(pkt[1:33], cePub[:])
	ct := hs.Seal(pkt[35:35], make([]byte, chacha20poly1305.NonceSize), initPlain, initAAD)
	binary.BigEndian.PutUint16(pkt[33:35], uint16(len(ct)))
	if _, err := raw.Write(append(pkt, ct...)); err != nil {
		return nil, fmt.Errorf("beacontower: handshake: %w", err)
	}

	var hdr [35]byte
	if _, err := io.ReadFull(raw, hdr[:]); err != nil {
		return nil, fmt.Errorf("beacontower: handshake: %w", err)
	}
	if hdr[0] != 1 {
		return nil, fmt.Errorf("beacontower: handshake version %d is not known", hdr[0])
	}
	var hePub [32]byte
	copy(hePub[:], hdr[1:33])
	n := int(binary.BigEndian.Uint16(hdr[33:35]))
	if n < aeadOverhead || n > 256 {
		return nil, errors.New("beacontower: the host's answer is broken")
	}
	respCT := make([]byte, n)
	if _, err := io.ReadFull(raw, respCT); err != nil {
		return nil, fmt.Errorf("beacontower: handshake: %w", err)
	}
	dhEE, err := x25519(cePriv, hePub)
	if err != nil {
		return nil, err
	}
	c2h, h2c, tr, err := sessionKeys(psk, dhES, dhEE, hostPub, cePub, hePub)
	if err != nil {
		return nil, err
	}
	respAEAD, err := chacha20poly1305.New(h2c)
	if err != nil {
		return nil, err
	}
	respAAD := append([]byte("bt-e2e-resp-v1"), tr[:]...)
	plain, err := respAEAD.Open(nil, make([]byte, chacha20poly1305.NonceSize), respCT, respAAD)
	if err != nil {
		return nil, errors.New("beacontower: the host did not prove itself: its code may have changed")
	}
	if !bytes.Equal(plain, initPlain[8:]) {
		return nil, errors.New("beacontower: the host answered another challenge")
	}
	return newRecordConn(raw, c2h, h2c)
}

// recordConn is hyla's aeadConn: each Read and Write chunk is one record,
// with its sequence number and direction in the nonce and the AAD, so a
// dropped, reordered or replayed record is not a record at all.
type recordConn struct {
	net.Conn

	rmu  sync.Mutex
	r    cipher.AEAD
	rseq uint64
	rbuf []byte
	rerr error

	wmu  sync.Mutex
	w    cipher.AEAD
	wseq uint64
	werr error
}

func newRecordConn(raw net.Conn, writeKey, readKey []byte) (net.Conn, error) {
	waead, err := chacha20poly1305.New(writeKey)
	if err != nil {
		return nil, err
	}
	raead, err := chacha20poly1305.New(readKey)
	if err != nil {
		return nil, err
	}
	return &recordConn{Conn: raw, r: raead, rseq: 1, w: waead, wseq: 1}, nil
}

func recordNonceAAD(dir byte, seq uint64) (nonce [12]byte, aad [9]byte) {
	binary.BigEndian.PutUint64(nonce[4:], seq)
	aad[0] = dir
	binary.BigEndian.PutUint64(aad[1:], seq)
	return nonce, aad
}

func (c *recordConn) Read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	c.rmu.Lock()
	defer c.rmu.Unlock()
	for len(c.rbuf) == 0 {
		if c.rerr != nil {
			return 0, c.rerr
		}
		var lenBuf [4]byte
		if _, err := io.ReadFull(c.Conn, lenBuf[:]); err != nil {
			c.rerr = err
			return 0, err
		}
		n := int(binary.BigEndian.Uint32(lenBuf[:]))
		if n < aeadOverhead || n > recordPlainLimit+aeadOverhead {
			c.rerr = errors.New("beacontower: a record is the wrong size")
			return 0, c.rerr
		}
		buf := make([]byte, n)
		if _, err := io.ReadFull(c.Conn, buf); err != nil {
			c.rerr = err
			return 0, err
		}
		nonce, aad := recordNonceAAD(dirHostToClient, c.rseq)
		plain, err := c.r.Open(buf[:0], nonce[:], buf, aad[:])
		if err != nil {
			c.rerr = errors.New("beacontower: a record failed its check")
			return 0, c.rerr
		}
		c.rseq++
		c.rbuf = plain
	}
	n := copy(p, c.rbuf)
	c.rbuf = c.rbuf[n:]
	return n, nil
}

func (c *recordConn) Write(p []byte) (int, error) {
	c.wmu.Lock()
	defer c.wmu.Unlock()
	if c.werr != nil {
		return 0, c.werr
	}
	written := 0
	for len(p) > 0 {
		chunk := p
		if len(chunk) > recordPlainLimit {
			chunk = chunk[:recordPlainLimit]
		}
		nonce, aad := recordNonceAAD(dirClientToHost, c.wseq)
		c.wseq++
		buf := make([]byte, 4, 4+len(chunk)+aeadOverhead)
		ct := c.w.Seal(buf[4:4], nonce[:], chunk, aad[:])
		binary.BigEndian.PutUint32(buf[:4], uint32(len(ct)))
		if _, err := c.Conn.Write(append(buf, ct...)); err != nil {
			c.werr = err
			return written, err
		}
		written += len(chunk)
		p = p[len(chunk):]
	}
	return written, nil
}

// ---- mux: hyla's Mux, for the streams the app opens ----

const (
	muxOpen byte = 1 + iota
	muxOpenOK
	muxOpenErr
	muxData
	muxWindow
	muxFin
	muxRst
	muxPing
	muxPong

	muxHeaderLen             = 9
	muxInitWindow            = 256 << 10
	muxWindowUpdateThreshold = 64 << 10
	muxMaxDataFrame          = 16 << 10
	muxMaxOpenPayload        = 1024
	muxPingInterval          = 20 * time.Second
	muxIdleTimeout           = 60 * time.Second
)

type muxCtrlFrame struct {
	typ     byte
	id      uint32
	val     uint32
	payload []byte
}

// beaconMux carries the app's streams over one tunnel. Why a mux, not a
// connection per stream: a pane's stream stays open while it idles, and the
// relay pairs a tunnel with a handshake the app should pay once.
type beaconMux struct {
	conn net.Conn

	wmu  sync.Mutex
	wbuf []byte

	mu      sync.Mutex
	streams map[uint32]*beaconConn
	closed  bool

	done     chan struct{}
	ctrlCh   chan muxCtrlFrame
	lastRecv atomic.Int64 // unix nanoseconds
	pingSeq  atomic.Uint32
}

func newBeaconMux(conn net.Conn) *beaconMux {
	m := &beaconMux{
		conn:    conn,
		wbuf:    make([]byte, muxHeaderLen+muxMaxDataFrame),
		streams: map[uint32]*beaconConn{},
		done:    make(chan struct{}),
		ctrlCh:  make(chan muxCtrlFrame, 64),
	}
	m.lastRecv.Store(time.Now().UnixNano())
	go m.readLoop()
	go m.ctrlWriteLoop()
	go m.keepalive()
	return m
}

func (m *beaconMux) lastRecvTime() time.Time {
	return time.Unix(0, m.lastRecv.Load())
}

func (m *beaconMux) enqueueCtrl(typ byte, id, val uint32, payload []byte) bool {
	frame := muxCtrlFrame{typ: typ, id: id, val: val}
	if len(payload) > 0 {
		frame.payload = append([]byte(nil), payload...)
	}
	select {
	case <-m.done:
		return false
	case m.ctrlCh <- frame:
		return true
	default:
		return false
	}
}

func (m *beaconMux) ctrlWriteLoop() {
	for {
		select {
		case <-m.done:
			return
		case frame := <-m.ctrlCh:
			if err := m.writeFrame(frame.typ, frame.id, frame.val, frame.payload); err != nil {
				m.close()
				return
			}
		}
	}
}

func (m *beaconMux) isClosed() bool {
	select {
	case <-m.done:
		return true
	default:
		return false
	}
}

func (m *beaconMux) close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	conns := make([]*beaconConn, 0, len(m.streams))
	for _, c := range m.streams {
		conns = append(conns, c)
	}
	clear(m.streams)
	m.mu.Unlock()
	close(m.done)
	for _, c := range conns {
		c.reset(net.ErrClosed)
	}
	_ = m.conn.Close()
}

func (m *beaconMux) writeFrame(typ byte, id, val uint32, payload []byte) error {
	m.wmu.Lock()
	defer m.wmu.Unlock()
	if m.isClosed() || m.conn == nil {
		return net.ErrClosed
	}
	total := muxHeaderLen + len(payload)
	buf := m.wbuf
	if total > len(buf) {
		buf = make([]byte, total)
	} else {
		buf = buf[:total]
	}
	buf[0] = typ
	binary.BigEndian.PutUint32(buf[1:5], id)
	binary.BigEndian.PutUint32(buf[5:9], val)
	copy(buf[9:], payload)
	_, err := m.conn.Write(buf)
	return err
}

// open starts conn as a stream of target, and waits for the host to take
// it: hyla's Mux.OpenStream.
func (m *beaconMux) open(ctx context.Context, conn *beaconConn, target string) error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return net.ErrClosed
	}
	conn.mu.Lock()
	m.streams[conn.id] = conn
	conn.mu.Unlock()
	m.mu.Unlock()

	if err := m.writeFrame(muxOpen, conn.id, uint32(len(target)), []byte(target)); err != nil {
		m.removeStream(conn.id)
		conn.reset(err)
		return err
	}
	select {
	case err := <-conn.openCh:
		if err != nil {
			m.removeStream(conn.id)
			conn.reset(err)
		}
		return err
	case <-m.done:
		m.removeStream(conn.id)
		conn.reset(net.ErrClosed)
		return net.ErrClosed
	case <-ctx.Done():
		m.removeStream(conn.id)
		conn.reset(ctx.Err())
		m.enqueueCtrl(muxRst, conn.id, 0, nil)
		return ctx.Err()
	}
}

// removeStream forgets a stream: one the host ended, or one its caller
// closed. Under the stream's lock, a Read that already found it sends its
// window update first, so the host never gets credit for a stream it ended.
func (m *beaconMux) removeStream(id uint32) {
	m.mu.Lock()
	delete(m.streams, id)
	m.mu.Unlock()
}

func (m *beaconMux) stream(id uint32) *beaconConn {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.streams[id]
}

func (m *beaconMux) readLoop() {
	// The mux ends when its conn does, whichever way the loop leaves.
	defer m.close()
	var hdr [muxHeaderLen]byte
	for {
		if _, err := io.ReadFull(m.conn, hdr[:]); err != nil {
			return
		}
		m.lastRecv.Store(time.Now().UnixNano())
		typ := hdr[0]
		id := binary.BigEndian.Uint32(hdr[1:5])
		val := binary.BigEndian.Uint32(hdr[5:9])
		var payload []byte
		switch typ {
		case muxOpen, muxOpenErr:
			if val > muxMaxOpenPayload {
				return
			}
			payload = make([]byte, val)
			if _, err := io.ReadFull(m.conn, payload); err != nil {
				return
			}
		case muxData:
			if val > muxMaxDataFrame {
				return
			}
			payload = make([]byte, val)
			if _, err := io.ReadFull(m.conn, payload); err != nil {
				return
			}
		}
		switch typ {
		case muxPing:
			m.enqueueCtrl(muxPong, 0, val, nil)
		case muxPong:
		case muxOpenOK:
			if c := m.stream(id); c != nil {
				c.deliverOpen(nil)
			}
		case muxOpenErr:
			msg := string(payload)
			if msg == "" {
				msg = "the host refused the stream"
			}
			if c := m.stream(id); c != nil {
				c.deliverOpen(errors.New("beacontower: " + msg))
			}
		case muxData:
			c := m.stream(id)
			if c == nil {
				continue
			}
			if !c.push(payload) {
				m.removeStream(id)
				m.enqueueCtrl(muxRst, id, 0, nil)
				c.reset(net.ErrClosed)
			}
		case muxWindow:
			if val > 0 {
				if c := m.stream(id); c != nil {
					c.addWindow(val)
				}
			}
		case muxFin:
			if c := m.stream(id); c != nil {
				c.finish()
				if c.isClosed() {
					m.removeStream(id)
				}
			}
		case muxRst:
			// The host ended the stream with data unread: Reads report
			// net.ErrClosed.
			if c := m.stream(id); c != nil {
				c.reset(net.ErrClosed)
				m.removeStream(id)
			}
		case muxOpen:
			// The host opens no stream to the app.
			msg := []byte("no such service")
			m.enqueueCtrl(muxOpenErr, id, uint32(len(msg)), msg)
		default:
			return
		}
	}
}

func (m *beaconMux) keepalive() {
	t := time.NewTicker(muxPingInterval)
	defer t.Stop()
	for {
		select {
		case <-m.done:
			return
		case <-t.C:
			// Without the host's traffic, the tunnel is gone however open
			// its socket looks: a relay or a network that vanished.
			if time.Since(m.lastRecvTime()) > muxIdleTimeout {
				m.close()
				return
			}
			m.enqueueCtrl(muxPing, 0, m.pingSeq.Add(1), nil)
		}
	}
}

// beaconConn is one stream: what hyla's MuxStream reads and writes, under
// the deadline a socket's reads and writes keep.
type beaconConn struct {
	mux *beaconMux
	id  uint32

	openOnce  sync.Once
	openCh    chan error
	wake      chan struct{} // data arrived, credit came, or the stream ended
	writeWake chan struct{}

	mu       sync.Mutex
	buf      []byte
	consumed uint32
	fin      bool
	localFin bool
	closed   bool
	err      error
	sendWin  int64
	readDL   time.Time
	writeDL  time.Time
}

func newBeaconConn(m *beaconMux, id uint32) *beaconConn {
	return &beaconConn{
		mux:       m,
		id:        id,
		openCh:    make(chan error, 1),
		wake:      make(chan struct{}, 1),
		writeWake: make(chan struct{}, 1),
		sendWin:   muxInitWindow,
	}
}

func signal(ch chan struct{}) {
	select {
	case ch <- struct{}{}:
	default:
	}
}

func (c *beaconConn) deliverOpen(err error) { c.openOnce.Do(func() { c.openCh <- err }) }

// push takes the host's data, or refuses it when the window is full: the
// host then waits for the window the reads below give back.
func (c *beaconConn) push(data []byte) bool {
	if len(data) == 0 {
		return true
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed || c.err != nil || c.fin {
		return true
	}
	if len(c.buf)+len(data) > muxInitWindow {
		return false
	}
	c.buf = append(c.buf, data...)
	signal(c.wake)
	return true
}

func (c *beaconConn) addWindow(delta uint32) {
	c.mu.Lock()
	c.sendWin += int64(delta)
	c.mu.Unlock()
	signal(c.writeWake)
}

func (c *beaconConn) finish() {
	c.mu.Lock()
	c.fin = true
	c.mu.Unlock()
	signal(c.wake)
	signal(c.writeWake)
}

// reset ends the stream the host cut: what the host sent before is still
// the app's to read, and the error follows it.
func (c *beaconConn) reset(err error) {
	c.mu.Lock()
	if c.closed && c.err != nil {
		c.mu.Unlock()
		return
	}
	c.closed = true
	if c.err == nil {
		c.err = err
	}
	c.mu.Unlock()
	c.deliverOpen(err)
	signal(c.wake)
	signal(c.writeWake)
}

// read is the stream's Read, waiting for data until its read deadline, as a
// socket's does.
//
// Data already here comes before the stream's end, and a reset is a cut: the
// host sends no more, and what it sent before is still the app's to read.
func (c *beaconConn) read(p []byte) (int, error) {
	if len(p) == 0 {
		return 0, nil
	}
	for {
		c.mu.Lock()
		if len(c.buf) > 0 {
			n := copy(p, c.buf)
			c.buf = c.buf[n:]
			c.consumed += uint32(n)
			var credit uint32
			if c.consumed >= muxWindowUpdateThreshold && !c.fin && c.err == nil {
				credit, c.consumed = c.consumed, 0
			}
			c.mu.Unlock()
			if credit > 0 {
				_ = c.mux.writeFrame(muxWindow, c.id, credit, nil)
			}
			return n, nil
		}
		if c.closed || c.fin || c.err != nil {
			err := c.err
			if err == nil {
				err = io.EOF
			}
			c.mu.Unlock()
			return 0, err
		}
		dl := c.readDL
		c.mu.Unlock()
		if err := waitFor(c.wake, dl, c.mux.done); err != nil {
			return 0, err
		}
	}
}

func (c *beaconConn) isClosed() bool {
	c.mu.Lock()
	closed := c.closed
	c.mu.Unlock()
	return closed
}

// write is the stream's Write, stalled while the host's window is full and
// not past the write deadline.
func (c *beaconConn) write(p []byte) (int, error) {
	written := 0
	for len(p) > 0 {
		c.mu.Lock()
		if c.closed || c.err != nil {
			err := c.err
			if err == nil {
				err = net.ErrClosed
			}
			c.mu.Unlock()
			return written, err
		}
		if c.fin {
			c.mu.Unlock()
			return written, syscall.EPIPE
		}
		dl := c.writeDL
		if !dl.IsZero() && !time.Now().Before(dl) {
			c.mu.Unlock()
			return written, os.ErrDeadlineExceeded
		}
		avail := c.sendWin
		if avail <= 0 {
			c.mu.Unlock()
			if err := waitFor(c.writeWake, dl, c.mux.done); err != nil {
				return written, err
			}
			continue
		}
		n := len(p)
		if int64(n) > avail {
			n = int(avail)
		}
		if n > muxMaxDataFrame {
			n = muxMaxDataFrame
		}
		c.sendWin -= int64(n)
		c.mu.Unlock()
		if err := c.mux.writeFrame(muxData, c.id, uint32(n), p[:n]); err != nil {
			return written, err
		}
		written += n
		p = p[n:]
	}
	return written, nil
}

// close ends the stream cleanly with a FIN (when not already sent).
func (c *beaconConn) close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	sendFin := !c.localFin
	c.localFin = true
	c.mu.Unlock()
	signal(c.wake)
	signal(c.writeWake)
	if sendFin {
		if !c.mux.enqueueCtrl(muxFin, c.id, 0, nil) {
			_ = c.mux.writeFrame(muxFin, c.id, 0, nil)
		}
	}
	c.mux.removeStream(c.id)
}

func (c *beaconConn) setReadDeadline(t time.Time) {
	c.mu.Lock()
	c.readDL = t
	c.mu.Unlock()
	signal(c.wake)
}

// Close ends the stream for a net.Conn's caller, and Read, Write and the
// deadlines are a socket's.
func (c *beaconConn) Close() error {
	c.close()
	return nil
}

func (c *beaconConn) Read(b []byte) (int, error) { return c.read(b) }

func (c *beaconConn) Write(b []byte) (int, error) { return c.write(b) }

func (c *beaconConn) SetDeadline(t time.Time) error {
	c.setReadDeadline(t)
	c.setWriteDeadline(t)
	return nil
}

func (c *beaconConn) SetReadDeadline(t time.Time) error {
	c.setReadDeadline(t)
	return nil
}

func (c *beaconConn) SetWriteDeadline(t time.Time) error {
	c.setWriteDeadline(t)
	return nil
}

func (c *beaconConn) LocalAddr() net.Addr  { return c.mux.conn.LocalAddr() }
func (c *beaconConn) RemoteAddr() net.Addr { return c.mux.conn.RemoteAddr() }

func (c *beaconConn) setWriteDeadline(t time.Time) {
	c.mu.Lock()
	c.writeDL = t
	c.mu.Unlock()
	signal(c.writeWake)
}

// waitFor waits for wake, or until deadline, or until the tunnel ends.
//
// A deadline that passed is os.ErrDeadlineExceeded, as a stream of hyla's
// returns, and a stream of tailcat's returns a timeout its reader reads as
// a timed-out read: on the phone and in OkHttp, both end the socket.
func waitFor(wake <-chan struct{}, deadline time.Time, done <-chan struct{}) error {
	var timeout <-chan time.Time
	if !deadline.IsZero() {
		d := time.Until(deadline)
		if d <= 0 {
			return os.ErrDeadlineExceeded
		}
		t := time.NewTimer(d)
		defer t.Stop()
		timeout = t.C
	}
	select {
	case <-wake:
		return nil
	case <-done:
		return net.ErrClosed
	case <-timeout:
		return os.ErrDeadlineExceeded
	}
}
