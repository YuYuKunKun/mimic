# roadmap

## milestone 1: minimal view + interact via intents (done; verified on device)

- [x] project planning docs (requirements, design, readme, contributing)
- [x] gradle + mise build scaffolding, makefile, .gitignore
- [x] accessibility service singleton and node-tree serialization
- [x] server-side tree filtering (interactive/text/visible/depth/package)
- [x] server-side query (by text/id/class/desc; exact/contains/regex)
- [x] output shaping (tree/flat/compact, field selection)
- [x] stateless interaction (tap/long-press/swipe/click/set-text/global nav)
- [x] command broadcast receiver with base64-json result data
- [x] token auth (salted hash, constant-time compare)
- [x] pairing handshake (6-digit expiring code -> secret token)
- [x] start/stop kill-switch by enabling/disabling the receiver component
- [x] launch-on-boot re-arm (boot receiver)
- [x] dark onboarding ui (service status, pairing, start/stop, boot toggle)
- [x] `mimic` posix shell cli
- [x] SKILL.md (raw intents + cli + context-reduction guidance)
- [x] python e2e tests over adb (`make test-e2e`)
- [x] debug apk builds clean with zero external dependencies

## milestone 2: surfaces + mcp (done; verified on device)

- [x] extract a transport-agnostic Commands core
- [x] localhost HostService: token-gated http server on 127.0.0.1
- [x] rest surface (`/v1/<cmd>`, json in/out)
- [x] in-app mcp server (`/mcp`, json-rpc tools over streamable http)
- [x] token stored app-private and revealable in the ui (was salted hash)
- [x] three independent surface toggles (intents/http/mcp) in the ui
- [x] cli auto-selects transport: http -> adb (incl. wireless) -> termux-am -> am
- [x] cli remote-shell quoting so spaced values survive the adb hop
- [x] docs updated (requirements, design, readme, skill)
- [x] whole app rebuilds clean with zero external dependencies
- [x] python e2e (`make test-e2e`) updated for the new ui + surfaces
- [x] app reconciles the host server with prefs on resume (fix found by e2e)
- [x] compact format sanitizes whitespace in labels (fix found by e2e)
- [x] http surface serves the cli + SKILL for bootstrap/update (`mimic update`)
- [x] activity launcher (LAUNCH) on all surfaces + cli `launch` + mcp mimic_launch

## milestone 3: screenshot, output, pairing controls (done; verified on device)

- [x] cli outputs raw json by default (no deps); `--pretty` opt-in needs jq and
      errors clearly when absent; SKILL speaks json, README documents `--pretty`
- [x] screenshot (SCREENSHOT) via takeScreenshot: http raw image, mcp image block,
      intents base64; cli `screenshot [PATH]` writes a /tmp file and prints it
- [x] "clear paired" in the ui forgets the token (rejects all clients)
- [x] on-device verification: 18/18 e2e pass on a real pixel 8 pro (all surfaces,
      bootstrap, launch, and png+jpeg screenshot)

## milestone 4: per-client tokens, one-time pairing, revocation (done; verified on device)

- [x] one-time pairing codes valid only during an explicit, time-boxed pairing
      window ("start pairing"); no window means every code is rejected
- [x] redeeming a code mints a fresh per-client token (id + label), returned over
      the active transport and stored permanently client-side
- [x] cli `pair` works over the active transport (http included), fixing the
      proot/termux case where `am` cannot return the broadcast result
- [x] legacy tokens for manual mcp/other config, minted from the gui (copy-paste)
- [x] revoke a specific client token, or all, from the gui (token management is
      gui-only: minting outside pairing and revocation need physical access)
- [x] gui lists active clients (label, id, kind) each with a revoke button
- [x] the clients list updates live when a client pairs over http (no reopen)
- [x] `mimic text` with no target types into the currently focused field
- [x] docs updated (requirements R2/R4, design, readme, skill)
- [x] python e2e: pair over http mints a working token, one-time code cannot be
      reused, gui revoke invalidates one token only

## milestone 5: bind interface, copy ux, package listing (done; verified on device)

- [x] configurable http/mcp bind interface (loopback, a lan address, or 0.0.0.0);
      the server rebinds live and falls back to loopback if an address is gone
- [x] ui copies the code/token on reveal and the address on demand; lists clients
      live; flags a non-loopback bind as network-exposed
- [x] `packages` lists launchable apps (package, label, component) over cli, mcp,
      intents -- permissionless via the existing manifest <queries>
- [x] mcp action results read as plain success/failure (isError + affirmative
      text), fixing a model misreading a successful launch as a failure
- [x] mcp e2e coverage (launch success/failure, tap/global, dump/find, packages,
      unknown tool) plus focused-field set-text and bind-default status
- [x] 33/33 e2e pass on a real pixel 8 pro

## backlog

- mcp sse / streaming responses and session ids (currently request/response only).
- optional file-based result delivery for very large intent trees.
- multi-window / window-list view beyond the active window.
- a package's non-launcher activities (only launcher entries are listed for now).
- gesture paths beyond straight-line swipe (multi-point, pinch).
