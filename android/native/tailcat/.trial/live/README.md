# Live trial

Checks `BeaconConnect` through the bridge against a real hylad that is
registered on a BeaconTower relay.

1. On the host, run `hyla pocket beacon` and copy the `hyla://pair?code=bt1_...`
   line. The host must listen over BeaconTower.
2. In this directory, run the trial with the code:

   ```
   CODE='hyla://pair?code=bt1_...' go run ./cmd/livecheck
   ```

   The program is its own package because `go run .` would also compile the
   test files of the bridge, and `go test ./...` here would run them without
   the cgo flags they need.

The trial opens a stream through `bridge.Connect`, writes an HTTP request to
`pocket`, and reads until the host ends the stream. A successful run prints the
response and `read all: "HTTP/1.1 200 ..." EOF`.
