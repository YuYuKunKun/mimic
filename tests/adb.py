"""shared adb + surface helpers for the e2e tests and verify_device.py.

drives the device over `adb`: install, enable accessibility, reveal the token and
toggle surfaces through the ui, then exercise the intents, http, and mcp surfaces.
"""

import json
import pathlib
import re
import shlex
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

PKG = "com.khimaros.mimic"
ACTIVITY = f"{PKG}/{PKG}.MainActivity"
SERVICE = f"{PKG}/{PKG}.MimicService"
RECEIVER = f"{PKG}/.CommandReceiver"
ACTION = PKG + ".action."
PORT = 8473
BASE = f"http://127.0.0.1:{PORT}"

APK = pathlib.Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk"

_DATA_RE = re.compile(r'data="([^"]*)"')
_CODE_RE = re.compile(r"(\d{6})")
_TOKEN_RE = re.compile(r"([A-Za-z0-9_-]{24,})")


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def shell(*parts):
    return adb("shell", " ".join(shlex.quote(p) for p in parts)).stdout


def device_available():
    try:
        out = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
    except FileNotFoundError:
        return False
    return out.returncode == 0 and out.stdout.strip() == "device"


# ---- setup ----

def install():
    adb("install", "-r", "-g", str(APK))


def enable_accessibility():
    current = shell("settings", "get", "secure", "enabled_accessibility_services").strip()
    if SERVICE not in current:
        merged = SERVICE if current in ("", "null") else f"{current}:{SERVICE}"
        shell("settings", "put", "secure", "enabled_accessibility_services", merged)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(1.5)


def launch():
    shell("am", "start", "-n", ACTIVITY)
    time.sleep(1.5)


def forward():
    adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")


# ---- ui automation ----

def ui():
    shell("uiautomator", "dump", "/sdcard/mimic_e2e.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/mimic_e2e.xml").stdout)


def node_with(root, needle):
    # case-insensitive: platform buttons render their text upper-cased.
    needle = needle.lower()
    for n in root.iter("node"):
        blob = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).lower()
        if needle in blob:
            return n
    return None


def _center(node):
    l, t, r, b = map(int, re.findall(r"\d+", node.get("bounds")))
    return (l + r) // 2, (t + b) // 2


def tap_node(node):
    x, y = _center(node)
    shell("input", "tap", str(x), str(y))
    time.sleep(0.6)


def ensure_switch(label, desired=True):
    node = node_with(ui(), label)
    assert node is not None, f"switch not found: {label}"
    if (node.get("checked") == "true") != desired:
        tap_node(node)


def reveal_token():
    tap_node(node_with(ui(), "show pairing code"))
    # the creds field uniquely contains "x-mimic-token"; the token is the only
    # long base64url run in it.
    creds = node_with(ui(), "x-mimic-token")
    text = creds.get("text") if creds is not None else ""
    m = _TOKEN_RE.search(text or "")
    assert m, f"token not found in ui: {text!r}"
    return m.group(1)


# ---- surfaces ----

def broadcast(action, **extras):
    """intents surface over `adb shell am` (shell uid returns results)."""
    parts = ["am", "broadcast", "-n", RECEIVER, "-a", ACTION + action]
    for k, v in extras.items():
        parts += ["--es", k, str(v)]
    out = shell(*parts)
    m = _DATA_RE.search(out)
    if not m:
        return None
    import base64
    return json.loads(base64.b64decode(m.group(1)).decode())


def http(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("x-mimic-token", token)
    if data:
        req.add_header("content-type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def mcp(token, method, params=None, rid=1):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    return http("POST", "/mcp", token, body)


def top_activity():
    out = adb("shell", "dumpsys activity activities").stdout
    m = re.search(r"topResumedActivity=\S+ u\d+ (\S+)", out)
    return m.group(1) if m else ""


def http_text(path):
    """GET a static (unauthenticated) route, returning (status, body text)."""
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def http_raw(path, token, body):
    """POST and return (status, content_type, raw bytes) -- for binary responses."""
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST")
    req.add_header("x-mimic-token", token)
    req.add_header("content-type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers.get("content-type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("content-type", ""), e.read()
