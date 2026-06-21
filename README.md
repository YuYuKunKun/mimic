# mimic

an android accessibility service that exposes **view** (read the on-screen
accessibility tree) and **interact** (tap, swipe, click, type, navigate) to local
automation over three independent, token-gated surfaces:

- **intents** -- broadcast intents driven by `am` / `termux-am` / `adb`.
- **http** -- a rest api on `127.0.0.1` for `curl` and the `mimic` cli.
- **mcp** -- an in-app model context protocol server on the same localhost port,
  for agents (claude code/desktop and other mcp clients).

## what it gives you

- read the active window's accessibility tree as json, with per-node bounds,
  tap coordinates, and supported actions.
- filter and query the tree **on the device** (interactive-only, text-only,
  by-text/id/class/desc, regex) so an agent sends and receives the minimum
  context.
- tap / long-press / swipe by coordinate; click or set-text on a node found by
  text or resource-id; back / home / recents / notifications.
- launch an app or activity by package, component, or action/uri.
- capture a screenshot (a last resort; the text tree is preferred and far cheaper).
- a `mimic` shell cli that works from termux/proot/host, and a `SKILL.md`.

## requirements

- android 8.0+ (api 26+).
- the android sdk to build (see below). `adb` to install and to run e2e tests.

## build

```
make            # assemble the debug apk
make install    # install onto a connected device/emulator (adb)
make release    # assemble the release apk (env-gated signing; see CONTRIBUTING)
```

the build needs an android sdk. `mise` pins the toolchain; point the build at
your sdk with `local.properties` (`sdk.dir=/path/to/Android/sdk`) or the
`ANDROID_HOME` environment variable. see [CONTRIBUTING.md](CONTRIBUTING.md).

## set up on the device

the app opens to a small dark onboarding screen:

1. install the apk (`make install`).
2. open **mimic** and enable the accessibility service when it
   deep-links you to settings.
3. tap **show pairing code + token** to reveal the credentials.
4. turn on the surfaces you want (**intents**, **local http**, **mcp**).
   optionally enable **restart surfaces on boot**.

then connect a client:

- **cli (intents or http)**: in termux, either `mimic pair` (type the 6-digit
  code) or `mimic set-token <token>` (paste the revealed token). the cli
  auto-selects a working transport. don't have the cli yet? with the http surface
  on, the server hands it out (no token needed):
  `curl -s http://127.0.0.1:8473/cli/mimic -o mimic && chmod +x mimic` (and later
  `mimic update`).
- **mcp client**: point it at `http://127.0.0.1:8473/mcp` with header
  `x-mimic-token: <token>` (from a host, first `adb forward tcp:8473 tcp:8473`).

## use it

```
mimic status                       # service enabled? which surfaces on?
mimic dump --filter interactive    # actionable nodes only, as json
mimic find login --by text         # nodes whose text contains "login"
mimic tap 540 1200                 # tap a coordinate
mimic click --id com.app:id/submit # click a node by resource-id
mimic text "hello" --id com.app:id/search
mimic back                         # global navigation
mimic launch com.android.settings  # launch an app
mimic screenshot                   # capture screen -> /tmp file (last resort)
```

every command prints a json envelope (`{"ok":...,"data":...}`) and exits nonzero
on error -- no tools required. for human-friendly output at a terminal, prefix
any command with `--pretty`, which unwraps and formats the payload (a compact
dump becomes tab-separated lines). `--pretty` requires `jq` and errors clearly if
it is missing:

```
mimic --pretty dump --filter interactive --format compact
```

see [SKILL.md](SKILL.md) for the full `mimic` cli reference and guidance for
agents on keeping context small; the rest/mcp/intent protocols behind it are in
[DESIGN.md](DESIGN.md).

## security

two independent gates protect the device: the accessibility service must be
enabled by you in settings, and every command on every surface must carry the
secret token. the token is stored in app-private storage (unreadable by other
apps without root). **clear paired** in the app forgets the token, rejecting every
client until you reveal a new one.

the localhost socket is reachable by any local app, and the intents receiver is
exported -- the token is what makes unauthorized attempts fail. treat it as a
device secret, and turn off surfaces you are not using.

## license

see [LICENSE](LICENSE).
