---
name: android-a11y
description: Drive an android device from a shell with the `a11y` cli -- read the on-screen accessibility tree (filtered/queried on-device to stay small) and tap, swipe, click, type, and navigate. Use for ui automation on a connected/termux android device. Covers setup, every subcommand, and how to keep agent context small.
---

# android a11y automation cli

`a11y` is a shell command that reads and acts on an android device's
accessibility tree: dump/query the ui, then tap, swipe, click, type, and
navigate. it talks to the **a11y automation** app over whatever transport is
available and auto-selects one, so you just run `a11y <command>`.

## setup (one time)

1. install and open the **a11y automation** app; enable its accessibility
   service when prompted.
2. tap **show pairing code + token**, then turn on at least one surface (the
   **local http** surface is the most portable).
3. give the cli the token, either way:
   ```
   a11y pair            # type the 6-digit code shown in the app
   a11y set-token       # or paste the token shown in the app
   ```
   the token is saved at `~/.config/a11y/token` and used automatically.

check it works:
```
a11y status            # -> service enabled? which surfaces on?
```

don't have the cli yet? with the **local http** surface on, the server serves it
(no token needed):
```
curl -s http://127.0.0.1:8473/cli/a11y -o a11y && chmod +x a11y
```
later, `a11y update` re-downloads the latest from the same server.

## keep context small (do this for agent use)

the full tree is large. narrow it **on the device** instead of dumping
everything and filtering locally:

- **reach for `find` first.** you usually know what you want:
  ```
  a11y find "sign in"
  ```
- **filter when you must dump:** `--filter interactive` keeps actionable nodes;
  `--filter text` keeps text-bearing nodes.
- **use the compact format:** one terse line per node,
  `cx,cy<TAB>class<TAB>label<TAB>id` -- cheapest, and the leading `cx,cy` is
  exactly where to tap:
  ```
  a11y dump --filter interactive --format compact
  ```
- **trim further:** `--fields center,text,id`, `--max-depth N`, `--package PKG`.

## commands

```
a11y status                          service enabled? which surfaces are on?

view (filtering/query run on-device):
  a11y dump [--filter interactive|text|visible|all]
            [--format tree|flat|compact]
            [--max-depth N] [--package PKG]
            [--fields class,text,desc,id,bounds,center,actions]
  a11y find QUERY [--by text|id|class|desc]
                  [--match exact|contains|regex]
                  [+ any dump option]

interact (stateless -- targets re-resolve every call):
  a11y tap        X Y [--duration MS]
  a11y longpress  X Y [--duration MS]
  a11y swipe      X1 Y1 X2 Y2 [--duration MS]
  a11y click      X Y | --id ID | --text T | --class C | --desc D [--match M]
  a11y text       VALUE --id ID | --text T [--match M]
  a11y back | home | recents | notifications

launch an app or activity:
  a11y launch PACKAGE                  launch an app by package
  a11y launch --component PKG/.ACT     launch an explicit activity
  a11y launch --action ACTION [--uri URI] [--package PKG]
  a11y launch --uri URI                open a uri (ACTION_VIEW)

setup:
  a11y pair [CODE]                     exchange the app's 6-digit code for a token
  a11y set-token [TOKEN]               save a token copied from the app
  a11y update [DEST]                   re-download this script from the server
```

note: `launch` is subject to android background-activity-launch rules -- it is
reliable when the device is unlocked and the app was recently foreground; a
purely background launch may be blocked by the system.

output is json on stdout; install `jq` for pretty output. a failed command
prints its error to stderr and exits nonzero.

## recipes

```
# what can i interact with on this screen?
a11y dump --filter interactive --format compact

# log in
a11y click --id com.example.app:id/username
a11y text "alice" --id com.example.app:id/username
a11y text "secret" --id com.example.app:id/password
a11y click --text "log in" --match contains

# scroll and re-survey
a11y swipe 540 1600 540 600
a11y find EditText --by class            # the editable fields now on screen

# find by regex, then tap its coordinates from the compact line
a11y find "^\\$[0-9]+" --match regex --format compact
a11y tap 712 980

# open an app, then a url
a11y launch com.android.settings
a11y launch --uri https://example.com
```

## transports (usually automatic)

the cli auto-selects, best first: the localhost **http** surface if it answers,
else **adb** if a device is connected (works over usb or wireless debugging),
else **termux-am**, else bare **am**. override if needed:

- `A11Y_TRANSPORT=http|intents`  force a transport.
- `A11Y_HOST=host:port`          http endpoint (default `127.0.0.1:8473`).
- `A11Y_AM="adb shell am"`       a specific `am` for the intents transport.
- `A11Y_HOME=/path`              token directory (default `~/.config/a11y`).

note: plain `am` run as a normal app (termux, or termux inside proot) can send a
command but cannot return its result -- if reads come back empty, turn on the
app's **http** surface and the cli will use it.

## troubleshooting

- `no token`: run `a11y pair` or `a11y set-token`.
- `unauthorized`: the token is stale; re-reveal it in the app and re-pair.
- `no response`: the selected surface is off, or on-device `am` cannot return a
  result -- enable the http surface in the app.
- `accessibility service not enabled`: enable it in accessibility settings.
- `no node matched query`: widen the query or `--match contains`; confirm with
  `a11y find`.

## mcp client config

the app hosts an mcp server, so an agent can use the same view/interact commands
as mcp tools without the cli. point any mcp client at the **streamable http**
endpoint and send the auth header:

- transport / type: `http` (streamable http; some clients call it `streamable-http`)
- url: `http://127.0.0.1:8473/mcp`
- headers: `x-a11y-token: <token>`

most clients take a json entry; the exact keys vary by client, but the shape is:

```json
{
  "mcpServers": {
    "android-a11y": {
      "type": "http",
      "url": "http://127.0.0.1:8473/mcp",
      "headers": { "x-a11y-token": "PASTE_TOKEN" }
    }
  }
}
```

if the client runs on a different host than the phone, forward the port first:
`adb forward tcp:8473 tcp:8473`. the tools are `a11y_dump`, `a11y_find`,
`a11y_tap`, `a11y_long_press`, `a11y_swipe`, `a11y_click`, `a11y_set_text`,
`a11y_global`, `a11y_launch`, `a11y_status`; their arguments mirror the cli flags.

the rest api and raw intent protocol are documented in [DESIGN.md](DESIGN.md).
