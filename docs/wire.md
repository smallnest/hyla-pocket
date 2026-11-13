# The wire between HylaPocket and hylad

HylaPocket talks to hylad over tailcat, as Hyla reaches a tailcat host: the
phone opens a raw connection to port `7297` of the host's tailcat listener,
and the bytes over it are an HTTP/1.1 WebSocket, the same wire rexpocket
uses with Rex. The reference implementation is `internal/pocket` in the
Hyla source.

## Where it connects

- `tailcat = true` turns tailcat on, and `listen = tailcat://:7473` gives
  hylad a tailcat listener. `hyla hosts pocket enable` does both.
- The phone knows the host by its pair code alone. It opens tailcat
  streams to port `7297` of the host's listener, as Hyla's own client
  does (`remote.OpenTailcat`, `DialTCPPort`), and writes one HTTP/1.1
  WebSocket request on each.
- Port `7297` of the listener is HylaPocket's (`remote.ServePocket`,
  the listen's PocketPort), and serves the paths under `/pocket/`, without
  a token: the pair code admitted the connection, and there is nothing
  else to check. The app asks `/pocket/api/control` and
  `/pocket/api/data`. Every other port answers nothing.
- `hyla hosts pocket qr` prints `hyla://pair?code=tc…&name=…`.
- A `tailnet-serve` listener of the settings serves the same prefix on its
  socket, so `curl` on the host reaches HylaPocket there.
- The app refuses requests from web pages: a page cannot reach the
  sessions.
- It writes the request once, and takes one response: a connection of a
  tailcat port is one HTTP connection.

## HTTP

| Path | Method | Answer |
|---|---|---|
| `/api/control` | WebSocket, subprotocol `rex.control.v1` | The control connection. |
| `/api/data` | WebSocket, subprotocol `rex.data.terminal.v1` | One pane's terminal. |
| `/api/hello` | GET | `{"app":"HylaPocket","hostname":"…","version":"3.6.5","protocol":1}` |

A request for a path other than these gets `404`. The paths are under
`/pocket/`, as hylad serves them, and this file writes what follows them.
A connection other than a WebSocket to the two paths gets `400`.

After the upgrade the control connection speaks hello, then requests, as in
"Text messages". hylad accepts the hello for compatibility and does not
need it; a request may come at once.

## Text messages

All messages are JSON.

### Hello, client → server

```json
{"type":"hello","api_revision":1,"client":{"kind":"app","platform":"android","name":"HylaPocket","version":"0.1.0"}}
```

There is no welcome: the control connection is ready when it opens, and the
first request may follow the hello immediately. The first answer to a
request is its response.

### Request, client → server

```json
{"type":"request","id":"7","method":"session.list","payload":{}}
```

### Response, server → client

```json
{"type":"response","id":"7","payload":{}}
{"type":"response","id":"7","error":{"code":"not_found","status":404,"message":"no session \"x\""}}
```

### Event, server → client

```json
{"type":"event","payload":{"type":"block_status","session_id":"s1","block_id":"b2","state":"working","message":"Running tests"}}
```

hylad sends the events that rexpocket's `PocketSession.onEvent` knows, and
the app ignores the ones it does not know.

## Methods

`session_id` is a Hyla session id (`proto.Session.ID`). `block_id` is a pane
id (`proto.Pane.ID`): a "block" and a "pane" are the same thing.

| Method | Payload | Payload of the answer |
|---|---|---|
| `session.list` | none | `{"sessions":[session]}` |
| `session.view` | `{"session_id":id}` | A view |
| `session.attach` | `{"session_id":id}` | `{"view":view}` |
| `session.bootstrap` | `{"preferred_session_id":id?}` | `{"attach_all":{"sessions":[session]},"selected_session":{"session":session,"view":view}?}` |
| `session.create` | `{"label":name?}` | `{"session_id":id}` |
| `session.destroy` | `{"session_id":id}` | none |
| `session.set_label` | `{"session_id":id,"label":text}` | none |
| `session.new_split` | `{"session_id":id,"anchor_id":pane?,"direction":"vertical" or "horizontal"}` | `{"block_ids":[id]}` |
| `session.new_window` | `{"session_id":id}` | `{"block_ids":[id]}` |
| `session.close_window` | `{"session_id":id}` | none: it closes the session's other panes |
| `block.close` | `{"session_id":id,"block_id":pane}` | none |
| `session.set_block_label` | `{"session_id":id,"block_id":pane,"label":text}` | none |
| `com.superlogical.terminal.write` | `{"session_id":id,"block_id":pane,"args":{"data":text}}` | none |
| `com.superlogical.terminal.resize` | `{"session_id":id,"block_id":pane,"args":{"columns":n,"rows":n,"cell_width_px":n,"cell_height_px":n}}` | none |
| `session.own_block` | `{"session_id":id,"block_ids":[pane],"mode":"take"}` | none |
| `session.disown_block` | `{"session_id":id,"block_ids":[pane]}` | none |
| `data.connect` | `{"session_id":id,"block_id":pane}` | `{"ticket":"<32 hex>"}` |

`session.attach`, `session.view` and `session.bootstrap` do not move the
desktop's focus. `session.attach` tells hylad that this connection looks at
the session, which clears its unread mark, as `hyla focus` does.

`com.superlogical.terminal.resize` is the size the phone's screen fits. hylad
keeps it for the pane, and the app's size wins while the app owns the pane
(see below), as a terminal client's size does.

A write, a resize and a key have the panes' order: hylad applies them in the
order the requests arrive on one control connection.

### Errors

| Code | Status | When |
|---|---|---|
| `invalid_request` | 400 | The message is not a request, or its payload is wrong. |
| `not_found` | 404 | No such session or pane. |
| `unauthorized` | 401 | A data stream without a valid ticket. |
| `internal` | 500 | hylad failed: the request is done or not, and the answer does not say. |

## Session

```json
{"session_id":"8urzpnm","label":"api"}
```

`label` is the session's name, else the title of its lead pane.

## View

```json
{
  "session_id":"8urzpnm",
  "label":"api",
  "revision":1794495440666,
  "active_window_id":"w1",
  "windows":[
    {"window_id":"w1","label":"api","focused_block_id":"ra67m9s","layers":[{"blocks":[
      {"block_id":"ra67m9s","label":"Claude Code"}
    ]}]}
  ]
}
```

A Hyla session is a Rex session. The Hyla panes of one row are a Rex window.
Each Hyla pane is a Rex block. `revision` is when the view last changed (unix
ms); the app keeps the newest view it has seen.

A session whose panes are in more than one row has more windows: one for each
row, with `layers` as its columns.

## Events

| Event | Payload | Made when |
|---|---|---|
| `block_status` | `session_id`, `block_id`, `state`, `message`, `kind`, `progress` | A pane's status changes. |
| `pane_closed` | `session_id`, `block_id` | A pane closes. |
| `session_renamed` | `session_id`, `label` | A session's name changes. |
| `session_closed` | `session_id` | A session closes. |
| `view_changed` | `session_id` | The session's panes changed: a split, a close, a focus. |

## The data stream

The client asks `data.connect` for a ticket, then opens
`/api/data?ticket=<ticket>&session=<session_id>&block=<pane>` as a WebSocket
with subprotocol `rex.data.terminal.v1`.

- The ticket can be used once, and for 30 seconds.
- Only the owning client may take a pane's stream. hylad answers `403` when
  another client owns it.
- After the upgrade hylad sends binary messages: hylad's VT snapshot of the
  pane first (`Terminal.Snapshot`), then each frame of the pane's output
  (`Mirror.Frame`) as it arrives. rexpocket's decoder finds no `GHOSTSNP`
  magic, and then shows every byte as output: the snapshot and the frames
  are ordinary VT.
- When the pane resizes, the stream goes on with frames for the new size:
  the mirror's first frame after a resize starts the screen over.
- When the pane closes or hylad stops owning it, hylad closes the WebSocket
  with code `4000`.

Typing never goes over this stream: it goes over the control connection,
as in rexpocket.

The ticket is the handshake: after the upgrade hylad sends the pane's VT at
once, with no welcome. The stream cannot be resumed; a client that drops
connects again with a new ticket, and the stream starts from a snapshot
again, which replaces the screen. A request carries no token: the pair code
admitted the connection.

## Ownership

This version does not delay the desktop: it only sizes the stream. The pane
keeps the size the desktop gives it, and `terminal.resize` gives hylad the
phone's size for the pane. A later version follows the pane's owner, so the
desktop and the phone can show one pane at different sizes.
