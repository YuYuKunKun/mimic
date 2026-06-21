# design

## overview

a single android app, package `com.khimaros.mimic`. one accessibility core is
exposed over three independent **surfaces**, each separately toggleable:

- **MimicService** -- the `AccessibilityService` (singleton) holding the live
  framework connection. it implements view (read the node tree) and interact
  (dispatch gestures, perform node/global actions). all real work lives here.
- **Commands** -- a transport-agnostic core. each surface parses its own
  transport into a uniform `(name) -> String?` argument getter, calls
  `Commands.run`, and gets back a structured `Result`. this keeps the three
  surfaces thin and identical in behavior.
- **CommandReceiver** (intents surface) -- an exported `BroadcastReceiver`. it
  authenticates a broadcast, runs the command, and returns a base64-json
  envelope in the broadcast result data.
- **HostService** (http + mcp surfaces) -- a foreground service running a token-
  gated http server on `127.0.0.1`. `/v1/<cmd>` is the rest surface; `/mcp` is an
  in-app mcp (json-rpc) endpoint. both share one socket. it also serves a few
  unauthenticated routes a client needs before it has a token: the static `/`,
  `/cli/mimic`, `/SKILL.md` from bundled assets (bootstrap/update), and `POST
  /pair` (redeem a one-time code for a token). bundled assets are copied from the
  repo root at build time (gradle `syncBootstrap`) so they always match the source.
- **MainActivity** -- a dark onboarding ui: enable the service, start pairing or
  reveal a legacy token, revoke clients, and toggle the three surfaces plus
  launch-on-boot.
- **BootReceiver** -- restarts the host surfaces after a reboot when launch-on-
  boot is set; it performs no accessibility action, so it stays enabled.

everything runs in one process, so the surfaces call the `MimicService` singleton
directly with no ipc.

```
  clients                         app process (com.khimaros.mimic)
  -------                         ------------------------------------
  am/termux-am/adb broadcast --> CommandReceiver --\
  curl/agent  -> 127.0.0.1/v1 --> HostService(rest) --> Commands --> MimicService
  mcp client  -> 127.0.0.1/mcp --> HostService(mcp)  --/   (auth:        (framework
                                          \--- TokenStore ---/ TokenStore)   apis)
```

## surfaces

`AppState` holds an independent on/off pref per surface; a fresh install has all
three off.

- **intents**: toggling flips the `CommandReceiver` component
  enabled/disabled via `PackageManager`, so a stopped intents surface receives no
  broadcasts at all (not merely a runtime flag). started by `am`, `termux-am`, or
  `adb shell am broadcast`.
- **http** and **mcp**: toggling starts/stops the foreground `HostService`, which
  runs while either is on; each route also checks its own toggle, so the two can
  be enabled independently while sharing the socket.

why three: `am` from a non-shell uid (plain termux, or termux inside proot) sends
a broadcast but cannot wait for the result, so reads fail there. the localhost
http/mcp surfaces are reachable from proot, native termux, and the host (via
`adb forward`), and return data reliably. intents remain ideal when a shell-uid
`am` is available (adb, including wireless, or rooted `su`).

## command core and protocol

commands have surface-agnostic short names (`Cmd`): `DUMP`, `FIND`, `TAP`,
`LONG_PRESS`, `SWIPE`, `CLICK`, `SET_TEXT`, `GLOBAL`, `LAUNCH`, `SCREENSHOT`,
`STATUS` (plus intents-only `PAIR`). arguments are uniform string keys (`Extras`):
view -> `format` (tree|flat|compact), `filter` (interactive|text|visible|all),
`max_depth`, `package`, `fields`, and for query `by` (text|id|class|desc),
`query`, `match` (exact|contains|regex); interact -> `x`,`y`,`x2`,`y2`,`duration`,
`nav`,`text`; launch -> `package`,`component`,`action`,`uri`; screenshot ->
`format` (png|jpeg), `quality`, `scale`.

`LAUNCH` and `STATUS` do not need the accessibility service; the rest do.
launching uses the service (or app) context to `startActivity` and is subject to
android background-activity-launch rules. resolving another app by package needs
package visibility (android 11+): the manifest declares `<queries>` for launchable
apps and uri handlers, so `getLaunchIntentForPackage` can see third-party apps
without the broad `QUERY_ALL_PACKAGES` permission. `SCREENSHOT` uses the framework's
`takeScreenshot` (config `canTakeScreenshot`, api 30+, rate-limited ~1/sec) and
returns image bytes -- `Commands.Result.data` is a `ByteArray`. http sends it raw
(`image/png|jpeg`), mcp wraps it in an image content block, and json consumers
(intents, `toJson`) get base64.

each surface maps its transport onto this:

- intents: action = `com.khimaros.mimic.action.<CMD>`, arguments = `--es` extras.
  reply = `base64(json)` in the ordered-broadcast result data (no quotes/newlines,
  so a single regex extracts it from `am` output; needs no storage permission).
- rest: `POST /v1/<cmd>` with a json body of arguments (or query params), token in
  the `x-mimic-token` header (or `?token=`). reply = plain json.
- mcp: `tools/call` with `{name, arguments}`; the tool name maps to a command and
  the arguments object to the getter. reply = mcp tool content.

the json envelope is `{"ok": bool, "error": string?, "data": <payload>}`.

## view model

a node is serialized from `AccessibilityNodeInfo` into:

```
{ "class": "android.widget.Button", "text": "submit", "desc": "submit form",
  "id": "com.app:id/submit", "bounds": [l,t,r,b], "center": [cx,cy],
  "actions": ["click","focus"], "children": [ ... ] }   // children: tree only
```

filtering and query run **on the device**, before serialization, so the returned
payload is small -- the primary lever for keeping agent context small:

- `filter=interactive` keeps actionable nodes; `filter=text` keeps text-bearing
  nodes; `filter=visible` keeps user-visible nodes (tree mode keeps ancestors of
  matches for shape).
- `max_depth` caps depth; `package` restricts to one app.
- `FIND` returns a flat match list; `format=compact` emits one terse line per node
  (`cx,cy<tab>class<tab>label<tab>id`); `fields` drops unneeded attributes.

interaction is **stateless**: `CLICK by=id` re-resolves the node at call time, so a
tree read earlier cannot cause a click on a stale coordinate.

## mcp

`HostService` answers `/mcp` with a minimal mcp server (streamable http, json-rpc
2.0): `initialize`, `tools/list`, `tools/call`, `ping`; notifications get a 202.
each POST is handled statelessly and answered with `application/json`. the tools
(`mimic_dump`, `mimic_find`, `mimic_tap`, ...) wrap the same `Commands` core, with
json-schema input. auth is at the http layer (token header), so tool calls carry
no token themselves.

## authentication

two independent gates protect the device: the user must enable the accessibility
service in settings, and every command on every surface must carry the secret
token (the localhost socket is reachable by any local app, so the token, not the
loopback bind, is the real gate).

`TokenStore` keeps a set of per-client tokens in app-private shared preferences (a
json array, unreadable by other apps without root) and verifies a candidate in
constant time against every one. each record carries the secret, a short non-
secret `id`, a label, a kind, and a creation time.

a client gets a token two ways. **pairing**: the user taps "start pairing", which
opens a short in-memory window (`PAIRING_WINDOW_MS`) and shows a one-time,
attempt-limited 6-digit code; redeeming the code -- over any surface, including
the unauthenticated `POST /pair` so it works from proot/termux where `am` cannot
return a result -- mints a fresh token and closes the window. **legacy token**:
the ui mints a long-lived token to paste into clients that cannot pair (mcp
config). minting outside pairing and revocation are gui-only, so a remote client
can neither create extra tokens nor revoke peers. the ui lists active clients and
revokes one by `id` (others keep working) or all at once.

## clients

- `cli/mimic` -- a posix shell script. it auto-selects a transport: the localhost
  http surface if reachable (works in proot/termux/host), else `adb shell am` if a
  device is connected (shell uid, returns results, incl. wireless), else
  `termux-am`, else bare `am`. reads the token from
  `${MIMIC_HOME:-$HOME/.config/mimic}/token`.
- mcp clients (claude code/desktop, agents) point at `http://127.0.0.1:8473/mcp`
  with an `x-mimic-token` header (over `adb forward` from a host).
- `SKILL.md` -- documents the surfaces, the cli, and mcp config, and how to use
  `filter`/`FIND`/`format=compact` to keep context small.

## build

gradle (kotlin dsl) builds the apk with zero external dependencies (json via the
built-in `org.json`, http via `java.net`). `mise` pins the jdk and gradle; a
top-level `Makefile` wraps `build`/`install`/`test-e2e`/`precommit`. python e2e
tests drive a device over `adb` and skip when none is attached.
