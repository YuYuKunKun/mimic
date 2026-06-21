# android a11y automation

an android accessibility service that exposes **view** (read the on-screen
accessibility tree) and **interact** (tap, swipe, click, type, navigate) to local
automation over three independent, token-gated surfaces:

- **intents** -- broadcast intents driven by `am` / `termux-am` / `adb`.
- **http** -- a rest api on `127.0.0.1` for `curl` and the `a11y` cli.
- **mcp** -- an in-app model context protocol server on the same localhost port,
  for agents (claude code/desktop and other mcp clients).

it deliberately does nothing else: no screenshots, no off-device network (the
server binds loopback only), no notification reading, no app launching.

## what it gives you

- read the active window's accessibility tree as json, with per-node bounds,
  tap coordinates, and supported actions.
- filter and query the tree **on the device** (interactive-only, text-only,
  by-text/id/class/desc, regex) so an agent sends and receives the minimum
  context.
- tap / long-press / swipe by coordinate; click or set-text on a node found by
  text or resource-id; back / home / recents / notifications.
- launch an app or activity by package, component, or action/uri.
- a `a11y` shell cli that works from termux/proot/host, and a `SKILL.md`.

## requirements

- android 8.0+ (api 26+).
- the android sdk to build (see below). `adb` to install and to run e2e tests.

## build

```
make            # assemble the debug apk
make install    # install onto a connected device/emulator (adb)
```

the build needs an android sdk. `mise` pins the toolchain; point the build at
your sdk with `local.properties` (`sdk.dir=/path/to/Android/sdk`) or the
`ANDROID_HOME` environment variable. see [CONTRIBUTING.md](CONTRIBUTING.md).

## set up on the device

the app opens to a small dark onboarding screen:

1. install the apk (`make install`).
2. open **a11y automation** and enable the accessibility service when it
   deep-links you to settings.
3. tap **show pairing code + token** to reveal the credentials.
4. turn on the surfaces you want (**intents**, **local http**, **mcp**).
   optionally enable **restart surfaces on boot**.

then connect a client:

- **cli (intents or http)**: in termux, either `a11y pair` (type the 6-digit
  code) or `a11y set-token <token>` (paste the revealed token). the cli
  auto-selects a working transport. don't have the cli yet? with the http surface
  on, the server hands it out (no token needed):
  `curl -s http://127.0.0.1:8473/cli/a11y -o a11y && chmod +x a11y` (and later
  `a11y update`).
- **mcp client**: point it at `http://127.0.0.1:8473/mcp` with header
  `x-a11y-token: <token>` (from a host, first `adb forward tcp:8473 tcp:8473`).

## use it

```
a11y status                       # service enabled? which surfaces on?
a11y dump --filter interactive    # actionable nodes only, as json
a11y find login --by text         # nodes whose text contains "login"
a11y tap 540 1200                 # tap a coordinate
a11y click --id com.app:id/submit # click a node by resource-id
a11y text "hello" --id com.app:id/search
a11y back                         # global navigation
a11y launch com.android.settings  # launch an app
```

see [SKILL.md](SKILL.md) for the full `a11y` cli reference and guidance for
agents on keeping context small; the rest/mcp/intent protocols behind it are in
[DESIGN.md](DESIGN.md).

## security

two independent gates protect the device: the accessibility service must be
enabled by you in settings, and every command on every surface must carry the
secret token. the token is stored in app-private storage (unreadable by other
apps without root); rotating it invalidates old clients.

the localhost socket is reachable by any local app, and the intents receiver is
exported -- the token is what makes unauthorized attempts fail. treat it as a
device secret, and turn off surfaces you are not using.

## license

see [LICENSE](LICENSE).
