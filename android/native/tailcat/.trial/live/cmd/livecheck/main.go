package main

import (
	"fmt"
	"os"
	"time"

	"github.com/smallnest/hylapocket/native/tailcat/bridge"
)

// The trial opens one stream through the bridge, as the app's socket does:
// its bytes are an HTTP request to the host's pocket service.
func main() {
	code := os.Getenv("CODE")
	if code == "" {
		fmt.Println("set CODE to a beacontower://bt1_ pair code")
		os.Exit(2)
	}
	buf := make([]byte, 4096)
	id, err := bridge.Connect(code, 7297, 20*time.Second)
	must(err)
	defer bridge.Release(id)
	_, err = bridge.Write(id, []byte("GET /pocket/api/hello HTTP/1.1\r\nHost: x\r\n\r\n"))
	must(err)
	time.Sleep(300 * time.Millisecond) // idle: no deadline is set
	n, err := bridge.Read(id, buf)
	fmt.Printf("first read: %d %v\n", n, err)
	got := append([]byte(nil), buf[:n]...)
	for err == nil {
		n, err = bridge.Read(id, buf)
		got = append(got, buf[:n]...)
	}
	fmt.Printf("read all: %q %v\n", got, err)
}

func must(err error) {
	if err != nil {
		fmt.Println("FAIL:", err)
		os.Exit(1)
	}
}
