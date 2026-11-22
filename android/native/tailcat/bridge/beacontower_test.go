package bridge

import (
	"bytes"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"strings"
	"testing"
	"time"
)

func testBeaconCode(t *testing.T, relay string) string {
	t.Helper()
	var pub, psk [32]byte
	if _, err := rand.Read(pub[:]); err != nil {
		t.Fatal(err)
	}
	if _, err := rand.Read(psk[:]); err != nil {
		t.Fatal(err)
	}
	raw := []byte{pairVersion}
	raw = append(raw, pub[:]...)
	raw = append(raw, psk[:]...)
	raw = binary.BigEndian.AppendUint16(raw, uint16(len(relay)))
	raw = append(raw, relay...)
	token := "tok"
	raw = binary.BigEndian.AppendUint16(raw, uint16(len(token)))
	raw = append(raw, token...)
	return "beacontower://" + pairCodePrefix + base64.RawURLEncoding.EncodeToString(raw)
}

func TestParseBeaconCode(t *testing.T) {
	code := testBeaconCode(t, "relay.example:7474")
	cases := map[string]string{
		"bare":          code,
		"core prefix":   strings.TrimPrefix(code, "beacontower://"),
		"host line":     "hyla://beacon?code=" + strings.TrimPrefix(code, "beacontower://"),
		"around spaces": "  " + code + "\n",
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
