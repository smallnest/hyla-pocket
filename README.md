# HylaPocket

HylaPocket is an Android client for Hyla. Rex has rexpocket; Hyla has this.

The phone shows the sessions of a Hyla host, opens their panes, and types in
them with the terminal's own key row. It reaches `hylad` over tailcat, the
relay hyla already uses, so the phone needs no account, no VPN, and no port
open on the host's network. The Hyla source serves it: `internal/pocket`, on
port `7297` of the tailcat listener.

## The host

HylaPocket does not use the Unix socket of a local `hylad`. It needs a
`hylad` with a tailcat listener, which serves HylaPocket on port `7297`
of that listener, to anyone who holds its pair code. Every other port of
the listener is Hyla's alone.

1. Install a Hyla that has the HylaPocket server: the changes are in the
   Hyla source tree, in `internal/pocket` and the daemon's tailcat
   listener.
2. Turn tailcat on, and give `hylad` a tailcat listener:

   ```
   hyla hosts pocket enable
   ```

   That is `hyla hosts tailcat enable`, with `tailcat = true` turned on.
   By hand, in `hyla config edit`:

   ```
   tailcat = true
   listen = tailcat://:7473
   ```
3. Start `hylad`, then show this machine's QR code:

   ```
   hyla hosts pocket qr
   ```

   The code is `hyla://pair?code=tc…&name=…`, which the phone scans. `hyla
   hosts pocket code` prints it as a line, to paste or to print.

The pair code is a password and an address both. Anyone with it reaches
every session of this machine; `hyla tailcat rotate` makes a new one, and
`hyla hosts pocket disable` stops HylaPocket. hylad refuses requests from
web pages, so a page you visit cannot take the sessions. The phone writes
one request per connection, as the wire in `docs/wire.md` says.

## The phone

1. Build and install the app:

   ```
   cd android
   ./gradlew :app:installDebug
   ```

   The build needs the Android SDK, the NDK, `go` for the tailcat
   transport (libtailcat.so), and `zig` for the terminal library
   (libghostty-vt).
2. Tap **Add host**, then the QR key in the address field, and point the
   camera at `hyla hosts pocket qr`. The code fills in the pair code and
   the name. Without a camera, **Choose a picture** reads a screenshot of
   the code; or paste the pair code into the address field.
   - A pair code is `tailcat://tc…`, or the `tc…` alone.
3. Tap the host to connect, then a session (or **New session**) to open it.

## What the app shows

- **Sessions**: the sessions of the host, with the panes of each.
- **Terminal**: the pane as it is on the host. The phone takes the pane
  while it shows it, so the pane has the phone's width and height. The key
  row has Esc, Tab, Ctrl, Alt and the arrows.
- **Splits**: the menu splits the shown pane to the right or below.
- **Status**: a dot shows what the program in the pane is doing.

The app keeps the last host, session and pane. When it starts again, it
returns there.

## The protocol

[`docs/wire.md`](docs/wire.md) is the protocol between the app and `hylad`:
the WebSockets, the methods, the events, and the terminal stream.

## Layout

```
android/core            The protocol client: no Android API, tested with JUnit.
android/app             The phone app: Compose, and the terminal on libghostty-vt.
android/native/ghostty  The JNI bridge to libghostty-vt.
android/tools           The build of libghostty-vt.
docs                    The protocol.
```
