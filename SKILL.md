---
name: mimic
description: Drive an android device from a shell with the `mimic` cli -- read the on-screen accessibility tree (filtered/queried on-device to stay small) and tap, swipe, click, type, and navigate. Use for ui automation on a connected/termux android device. Covers setup, every subcommand, and how to keep agent context small.
---

# mimic cli

`mimic` is a shell command that reads and acts on an android device's
accessibility tree: dump/query the ui, then tap, swipe, click, type, and
navigate. it talks to the **mimic** app over whatever transport is
available and auto-selects one, so you just run `mimic <command>`.

## setup (one time)

1. install and open the **mimic** app; enable its accessibility
   service when prompted.
2. tap **show pairing code + token**, then turn on at least one surface (the
   **local http** surface is the most portable).
3. give the cli the token, either way:
   ```
   mimic pair            # type the 6-digit code shown in the app
   mimic set-token       # or paste the token shown in the app
   ```
   the token is saved at `~/.config/mimic/token` and used automatically.

check it works:
```
mimic status            # -> {"ok":true,"data":{"service_enabled":true,...}}
```

## output (json)

every command prints a json envelope on stdout and exits nonzero on error:

```
{"ok": true, "data": <payload>}
{"ok": false, "error": "..."}
```

parse it as json -- that is the intended interface, and it needs no extra tools.
`data` is the payload: an object/array for tree/flat dumps and status, a string
for `--format compact` (newline-joined lines; json-escaped, so a parser restores
the tabs/newlines), and `{"performed":true}` for actions.

don't have the cli yet? with the **local http** surface on, the server serves it
(no token needed):
```
curl -s http://127.0.0.1:8473/cli/mimic -o mimic && chmod +x mimic
```
later, `mimic update` re-downloads the latest from the same server.

the server also serves this skill doc, so you can refresh it to match the
installed app version (also no token needed) -- write it over your local copy:
```
curl -s http://127.0.0.1:8473/SKILL.md -o SKILL.md
```

## keep context small (do this for agent use)

the full tree is large. narrow it **on the device** instead of dumping
everything and filtering locally:

- **reach for `find` first.** you usually know what you want:
  ```
  mimic find "sign in"
  ```
- **filter when you must dump:** `--filter interactive` keeps actionable nodes;
  `--filter text` keeps text-bearing nodes.
- **use the compact format:** `data` becomes a single newline-joined string of
  `cx,cy<TAB>class<TAB>label<TAB>id` lines -- the cheapest representation, and the
  leading `cx,cy` is exactly where to tap:
  ```
  mimic dump --filter interactive --format compact
  ```
- **trim further:** `--fields center,text,id`, `--max-depth N`, `--package PKG`.

## commands

```
mimic status                          service enabled? which surfaces are on?

view (filtering/query run on-device):
  mimic dump [--filter interactive|text|visible|all]
            [--format tree|flat|compact]
            [--max-depth N] [--package PKG]
            [--fields class,text,desc,id,bounds,center,actions]
  mimic find QUERY [--by text|id|class|desc]
                  [--match exact|contains|regex]
                  [+ any dump option]

interact (stateless -- targets re-resolve every call):
  mimic tap        X Y [--duration MS]
  mimic longpress  X Y [--duration MS]
  mimic swipe      X1 Y1 X2 Y2 [--duration MS]
  mimic click      X Y | --id ID | --text T | --class C | --desc D [--match M]
  mimic text       VALUE --id ID | --text T [--match M]
  mimic back | home | recents | notifications

launch an app or activity:
  mimic launch PACKAGE                  launch an app by package
  mimic launch --component PKG/.ACT     launch an explicit activity
  mimic launch --action ACTION [--uri URI] [--package PKG]
  mimic launch --uri URI                open a uri (ACTION_VIEW)

screenshot (LAST RESORT -- see below):
  mimic screenshot [PATH] [--format png|jpeg] [--quality 1-100] [--scale 0-1]

setup:
  mimic pair [CODE]                     exchange the app's 6-digit code for a token
  mimic set-token [TOKEN]               save a token copied from the app
  mimic update [DEST]                   re-download this script from the server
```

## screenshot is a last resort

prefer the text tree (`dump`/`find`) for almost everything -- it is far cheaper
in tokens, gives you exact tap coordinates and ids, and is reliable. only reach
for `screenshot` when the tree is genuinely insufficient: canvas/`SurfaceView`
content, webview/game pixels, images, or visual state the accessibility tree does
not expose. `mimic screenshot` writes a png to a unique `/tmp` file (or `PATH`) and
prints the path; pass `--scale 0.5` / `--format jpeg` to shrink it. it is
rate-limited by android to about one per second.

note: `launch` is subject to android background-activity-launch rules -- it is
reliable when the device is unlocked and the app was recently foreground; a
purely background launch may be blocked by the system.

output is the json envelope on stdout (see "output (json)" above); a failed
command prints its error to stderr and exits nonzero.

## recipes

```
# what can i interact with on this screen?
mimic dump --filter interactive --format compact

# log in
mimic click --id com.example.app:id/username
mimic text "alice" --id com.example.app:id/username
mimic text "secret" --id com.example.app:id/password
mimic click --text "log in" --match contains

# scroll and re-survey
mimic swipe 540 1600 540 600
mimic find EditText --by class            # the editable fields now on screen

# find by regex, then tap its coordinates from the compact line
mimic find "^\\$[0-9]+" --match regex --format compact
mimic tap 712 980

# open an app, then a url
mimic launch com.android.settings
mimic launch --uri https://example.com
```

## transports (usually automatic)

the cli auto-selects, best first: the localhost **http** surface if it answers,
else **adb** if a device is connected (works over usb or wireless debugging),
else **termux-am**, else bare **am**. override if needed:

- `MIMIC_TRANSPORT=http|intents`  force a transport.
- `MIMIC_HOST=host:port`          http endpoint (default `127.0.0.1:8473`).
- `MIMIC_AM="adb shell am"`       a specific `am` for the intents transport.
- `MIMIC_HOME=/path`              token directory (default `~/.config/mimic`).

note: plain `am` run as a normal app (termux, or termux inside proot) can send a
command but cannot return its result -- if reads come back empty, turn on the
app's **http** surface and the cli will use it.

## troubleshooting

- `no token`: run `mimic pair` or `mimic set-token`.
- `unauthorized`: the token is stale; re-reveal it in the app and re-pair.
- `no response`: the selected surface is off, or on-device `am` cannot return a
  result -- enable the http surface in the app.
- `accessibility service not enabled`: enable it in accessibility settings.
- `no node matched query`: widen the query or `--match contains`; confirm with
  `mimic find`.

## mcp client config

the app hosts an mcp server, so an agent can use the same view/interact commands
as mcp tools without the cli. point any mcp client at the **streamable http**
endpoint and send the auth header:

- transport / type: `http` (streamable http; some clients call it `streamable-http`)
- url: `http://127.0.0.1:8473/mcp`
- headers: `x-mimic-token: <token>`

most clients take a json entry; the exact keys vary by client, but the shape is:

```json
{
  "mcpServers": {
    "mimic": {
      "type": "http",
      "url": "http://127.0.0.1:8473/mcp",
      "headers": { "x-mimic-token": "PASTE_TOKEN" }
    }
  }
}
```

if the client runs on a different host than the phone, forward the port first:
`adb forward tcp:8473 tcp:8473`. the tools are `mimic_dump`, `mimic_find`,
`mimic_tap`, `mimic_long_press`, `mimic_swipe`, `mimic_click`, `mimic_set_text`,
`mimic_global`, `mimic_launch`, `mimic_status`; their arguments mirror the cli flags.

the rest api and raw intent protocol are documented in [DESIGN.md](DESIGN.md).
