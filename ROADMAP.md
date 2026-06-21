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
- [x] `a11y` posix shell cli
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
- [x] http surface serves the cli + SKILL for bootstrap/update (`a11y update`)
- [x] activity launcher (LAUNCH) on all surfaces + cli `launch` + mcp a11y_launch

## milestone 3: screenshot, output, pairing controls (done; verified on device)

- [x] cli outputs raw json by default (no deps); `--pretty` opt-in needs jq and
      errors clearly when absent; SKILL speaks json, README documents `--pretty`
- [x] screenshot (SCREENSHOT) via takeScreenshot: http raw image, mcp image block,
      intents base64; cli `screenshot [PATH]` writes a /tmp file and prints it
- [x] "clear paired" in the ui forgets the token (rejects all clients)
- [x] on-device verification: 18/18 e2e pass on a real pixel 8 pro (all surfaces,
      bootstrap, launch, and png+jpeg screenshot)

## backlog

- mcp sse / streaming responses and session ids (currently request/response only).
- optional file-based result delivery for very large intent trees.
- multi-window / window-list view beyond the active window.
- per-client tokens; unpair a single client.
- listing installed packages / a package's launchable activities (dropped for now).
- gesture paths beyond straight-line swipe (multi-point, pinch).
