"""end-to-end coverage of the three surfaces against a real device/emulator.

verifies the requirements: view (dump/find with on-device filtering and query),
interact (tap/swipe/global/set-text), the token gate, and parity across the
intents, http, and mcp surfaces.
"""

import time

import adb


# ---- http surface ----

def test_http_status(token):
    s, b = adb.http("POST", "/v1/status", token)
    assert s == 200 and b["ok"]
    assert b["data"]["service_enabled"] is True
    assert b["data"]["http"] is True


def test_http_healthz_unauthenticated(token):
    s, b = adb.http("GET", "/healthz", "")  # no token
    assert s == 200 and b["ok"]


def test_bootstrap_serves_cli_and_skill(token):
    s, body = adb.http_text("/cli/a11y")  # unauthenticated
    assert s == 200 and body.startswith("#!") and "a11y --" in body
    s, body = adb.http_text("/SKILL.md")
    assert s == 200 and "android a11y" in body.lower()


def test_http_rejects_bad_token(token):
    s, b = adb.http("POST", "/v1/status", "WRONGTOKEN")
    assert s == 401 and b["ok"] is False


def test_http_dump_compact_is_terse(token):
    s, b = adb.http("POST", "/v1/dump", token, {"filter": "interactive", "format": "compact"})
    assert s == 200 and b["ok"]
    assert isinstance(b["data"], str)
    for line in b["data"].splitlines():
        assert "\t" in line and "," in line.split("\t", 1)[0]


def test_http_find_flat(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # our own ui is in front
    s, b = adb.http("POST", "/v1/find", token, {"query": "surface", "by": "text"})
    assert s == 200 and b["ok"] and isinstance(b["data"], list)


def test_http_fields_selection(token):
    s, b = adb.http("POST", "/v1/dump", token, {"format": "flat", "fields": "center"})
    assert s == 200 and b["ok"]
    for node in b["data"]:
        assert set(node.keys()) <= {"center"}


def test_http_tap_and_global(token):
    s, b = adb.http("POST", "/v1/tap", token, {"x": 10, "y": 10})
    assert s == 200 and b["ok"] and b["data"]["performed"] is True
    s, b = adb.http("POST", "/v1/global", token, {"nav": "home"})
    assert s == 200 and b["ok"] and b["data"]["performed"] is True


def test_http_launch_app(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token, {"package": "com.android.settings"})
    assert s == 200 and b["ok"] and b["data"]["launched"] is True
    time.sleep(1.5)
    top = adb.top_activity()
    adb.http("POST", "/v1/global", token, {"nav": "home"})  # cleanup
    assert "com.android.settings" in top


# ---- mcp surface ----

def test_mcp_initialize(token):
    s, b = adb.mcp(token, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}})
    assert "result" in b
    assert b["result"]["serverInfo"]["name"] == "android-a11y"


def test_mcp_tools_list(token):
    s, b = adb.mcp(token, "tools/list")
    names = [t["name"] for t in b["result"]["tools"]]
    assert {"a11y_dump", "a11y_find", "a11y_tap", "a11y_status"} <= set(names)


def test_mcp_tools_call_status(token):
    s, b = adb.mcp(token, "tools/call", {"name": "a11y_status", "arguments": {}})
    assert b["result"]["isError"] is False
    assert "service_enabled" in b["result"]["content"][0]["text"]


def test_mcp_rejects_bad_token(token):
    s, b = adb.http("POST", "/mcp", "WRONGTOKEN", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert s == 401


# ---- intents surface (over adb shell am, shell uid returns results) ----

def test_intents_status(token):
    resp = adb.broadcast("STATUS")
    assert resp and resp["ok"] and resp["data"]["service_enabled"] is True


def test_intents_unauthorized(token):
    resp = adb.broadcast("DUMP")  # no token extra
    assert resp is not None and resp["ok"] is False
    assert "unauthorized" in resp["error"]


def test_intents_dump(token):
    resp = adb.broadcast("DUMP", token=token, format="tree")
    assert resp["ok"] and isinstance(resp["data"], dict)
