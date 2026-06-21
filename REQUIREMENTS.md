# requirements

product requirements for the mimic service. it is never
okay to regress on these in a release.

## scope

an android app that registers as an accessibility service and exposes a small set
of capabilities to external automation, over one or more local surfaces:

- **view**: read the accessibility node tree of the active window(s).
- **interact**: drive the ui (tap, long press, swipe, click, set text, and the
  global navigation actions back/home/recents/notifications).
- **launch**: start an app or activity (by package, component, or action/uri).
- **capture**: take a screenshot (a last resort; the text tree is preferred).

nothing else. no off-device network, no notification reading, no contact/phone/
storage access. the one networking facility is a loopback-only (127.0.0.1) server
for on-device clients; data never leaves the device. the permission surface stays
as small as the accessibility framework and that local server require.

## R1 view

- R1.1 return the node tree of the active window as machine-readable json.
- R1.2 each node carries at least: a role/class, text, content-description,
  resource-id, screen bounds, center coordinates, and which actions it supports
  (clickable, long-clickable, editable, scrollable, focusable, checkable).
- R1.3 support server-side filtering to reduce returned size: interactive-only,
  text-bearing-only, visible-only, depth limit, and package scope. filtering
  happens on the device so the wire payload stays small.
- R1.4 support server-side query: find nodes by text, resource-id, class, or
  content-description, with exact, contains, or regex matching.
- R1.5 support output shaping: tree vs flat vs compact form, and selection of
  which fields are included.

## R2 interact

- R2.1 tap, long press, and swipe by absolute screen coordinates.
- R2.2 click a node located by text, resource-id, or coordinates.
- R2.3 set the text of an editable node located by text or resource-id.
- R2.4 perform global navigation: back, home, recents, notifications.
- R2.5 interaction is stateless: a target is re-resolved on every call from
  coordinates, text, or resource-id. no reliance on ephemeral node ids that go
  stale when the screen changes.
- R2.6 launch an app or activity by package, explicit component, or action/uri.
  this is subject to android background-activity-launch rules.
- R2.7 capture a screenshot (png/jpeg, optional downscale) via the accessibility
  framework. http returns raw image bytes, mcp an image content block, intents
  base64; the cli writes it to a file. it is a last resort relative to the tree,
  and is rate-limited by android to about one per second.

## R3 surfaces and protocol

- R3.1 the same view/interact command core is reachable over three independently
  toggleable surfaces:
  - **intents**: ordered broadcast intents invoked with `am`/`termux-am`/adb
    `broadcast`; results returned in the broadcast result data as base64-json.
  - **http**: a token-gated rest api on `127.0.0.1` (`/v1/<cmd>`), json in/out.
  - **mcp**: an in-app model context protocol server (streamable http, json-rpc)
    on the same localhost port (`/mcp`), exposing the commands as mcp tools.
- R3.2 the intents result must survive `am`/`termux-am` output parsing and need
  no storage permission; the localhost surfaces return plain json.
- R3.3 every command reports a clear status and error message on failure
  (service not enabled, bad token, target not found, bad arguments).
- R3.4 because `am` from a non-shell uid cannot return a result, the cli must
  also work over the localhost http surface (reachable from proot, native
  termux, or the host via `adb forward`) and over adb when connected.

## R4 authentication and pairing

- R4.1 the accessibility service must be enabled by the user in system settings.
  this is the first, non-bypassable gate.
- R4.2 every command on every surface must carry a secret token. requests without
  a valid token are rejected, so other apps on the device cannot read the screen
  or inject input (the localhost socket is reachable by any local app).
- R4.3 the token can be obtained two ways: copied from the app ui (for the http
  and mcp surfaces), or via a pairing handshake for the intents cli -- the app
  shows a short, time-limited, attempt-limited 6-digit code that the client
  exchanges for the token.
- R4.4 the token is stored only in app-private storage (unreadable by other apps
  without root) and compared in constant time. the ui can reveal it for client
  config.
- R4.5 the user can clear pairing from the app (forgetting the token), which
  rejects every paired client until a new token is shown. revealing the
  code/token again only mints a token when none exists, so it does not silently
  invalidate working clients.

## R5 clients

- R5.1 a self-contained posix shell cli (`mimic`) usable from termux with no extra
  runtime. it auto-selects a working transport (localhost http, else adb, else
  termux-am/am) and stores the token mode 600.
- R5.2 a `SKILL.md` documenting the surfaces, the cli, and mcp client config, with
  explicit guidance on using filtering/query to minimize context for agents.
- R5.3 the http surface serves the cli and `SKILL.md` (unauthenticated) so a
  client can bootstrap (`curl .../cli/mimic`) or self-update (`mimic update`); the
  served copies are bundled from the repo at build time so they match the source.

## R6 build and test

- R6.1 the project builds with `make` (gradle under the hood).
- R6.2 end-to-end tests in python, run with `make test-e2e`, drive a real device
  or emulator over adb and skip gracefully when none is attached.
- R6.3 `make precommit` runs the checks expected before a commit.

## R7 app lifecycle and ui

- R7.1 a simple, dark onboarding screen walks the user through the steps that
  only a human can do: enable the accessibility service, reveal the token, and
  turn on the surfaces.
- R7.2 each surface (intents, http, mcp) has its own independent on/off toggle
  and acts as a local kill-switch. stopping the intents surface disables the
  receiver component outright; stopping http/mcp stops the localhost server. a
  fresh install has every surface off.
- R7.3 an opt-in "start on boot" restores the surfaces after a reboot.
