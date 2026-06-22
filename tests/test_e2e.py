"""end-to-end coverage of the three surfaces against a real device/emulator.

verifies the requirements: view (dump/find with on-device filtering and query),
interact (tap/swipe/global/set-text), the token gate, and parity across the
intents, http, and mcp surfaces.
"""

import json
import time

import adb
import pytest


# ---- http surface ----

def test_http_status(token):
    s, b = adb.http("POST", "/v1/status", token)
    assert s == 200 and b["ok"]
    assert b["data"]["service_enabled"] is True
    assert b["data"]["http"] is True
    assert b["data"]["bind"] == "127.0.0.1"  # loopback by default
    assert b["data"]["require_auth"] is True  # token required by default
    assert b["data"]["require_approval"] is False  # fine-grained off by default


def test_http_healthz_unauthenticated(token):
    s, b = adb.http("GET", "/healthz", "")  # no token
    assert s == 200 and b["ok"]


def test_bootstrap_serves_cli_and_skill(token):
    s, body = adb.http_text("/cli/mimic")  # unauthenticated
    assert s == 200 and body.startswith("#!") and "mimic --" in body
    s, body = adb.http_text("/SKILL.md")
    assert s == 200 and "mimic" in body.lower()


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


def test_http_screenshot_returns_png(token):
    s, ct, data = adb.http_raw("/v1/screenshot", token, {"format": "png", "scale": "0.5"})
    assert s == 200, ct
    assert ct.startswith("image/png")
    assert data[:8] == b"\x89PNG\r\n\x1a\n"  # png magic bytes
    assert len(data) > 1000


def _launchable_third_party():
    listed = adb.shell("pm", "list", "packages", "-3").split()
    for entry in listed:
        pkg = entry.replace("package:", "").strip()
        if not pkg:
            continue
        resolved = adb.shell(
            "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", pkg,
        )
        if "/" in resolved and "No activity" not in resolved:
            return pkg
    return None


def test_http_launch_third_party_app(token):
    # the visibility fix (<queries>) lets `launch` resolve non-system apps.
    pkg = _launchable_third_party()
    if not pkg:
        pytest.skip("no launchable third-party app installed")
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token, {"package": pkg})
    assert s == 200 and b["ok"] and b["data"]["launched"] is True, b
    time.sleep(1.5)
    assert pkg in adb.top_activity()
    adb.http("POST", "/v1/global", token, {"nav": "home"})


def test_http_launch_app(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token, {"package": "com.android.settings"})
    assert s == 200 and b["ok"] and b["data"]["launched"] is True
    time.sleep(1.5)
    top = adb.top_activity()
    adb.http("POST", "/v1/global", token, {"nav": "home"})  # cleanup
    assert "com.android.settings" in top


def test_http_packages_lists_launchable(token):
    s, b = adb.http("POST", "/v1/packages", token)
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and b["data"]
    assert {"package", "label", "component"} <= set(b["data"][0].keys())
    assert "com.android.settings" in [e["package"] for e in b["data"]]


def test_http_packages_query_filter(token):
    s, b = adb.http("POST", "/v1/packages", token, {"query": "settings"})
    assert s == 200 and b["ok"]
    assert all("settings" in (e["package"] + e["label"]).lower() for e in b["data"])


def test_http_packages_fuzzy(token):
    # a typo still finds the app via edit-distance matching.
    s, b = adb.http("POST", "/v1/packages", token, {"query": "settngs", "fuzzy": True})
    assert s == 200 and b["ok"]
    assert any("settings" in e["label"].lower() for e in b["data"]), b["data"]


def test_cli_packages(token):
    # exercises the real cli/mimic script (regression: it once sent query=PACKAGES).
    rc, out = adb.cli(["packages"], token)
    assert rc == 0, out
    data = json.loads(out)["data"]
    assert isinstance(data, list) and len(data) > 5
    rc, out = adb.cli(["packages", "settings"], token)
    data = json.loads(out)["data"]
    assert data and all("settings" in (e["package"] + e["label"]).lower() for e in data)
    rc, out = adb.cli(["packages", "settngs", "--fuzzy"], token)
    data = json.loads(out)["data"]
    assert any("settings" in e["label"].lower() for e in data), out


def test_set_text_into_focused_field(token):
    # set-text with no target should land in whatever field has input focus.
    adb.shell("am", "start", "-a", "android.intent.action.INSERT", "-t", "vnd.android.cursor.dir/contact")
    time.sleep(2.0)
    s, b = adb.http("POST", "/v1/find", token, {"query": "EditText", "by": "class"})
    fields = b.get("data") or []
    if not (s == 200 and b["ok"] and fields):
        pytest.skip("no editable field available to focus")
    cx, cy = fields[0]["center"]
    adb.http("POST", "/v1/click", token, {"x": cx, "y": cy})  # focus it
    time.sleep(0.5)
    s, b = adb.http("POST", "/v1/set_text", token, {"text": "mimicfocus"})  # no target
    assert s == 200 and b["ok"] and b["data"]["performed"] is True, b
    time.sleep(0.5)
    s, b = adb.http("POST", "/v1/find", token, {"query": "mimicfocus", "by": "text"})
    assert s == 200 and b["ok"] and len(b["data"]) >= 1, b
    adb.shell("input", "keyevent", "KEYCODE_BACK")
    adb.shell("input", "keyevent", "KEYCODE_BACK")


# ---- wait + launch --wait ----

def test_http_wait_found(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # our ui shows the bottom tabs
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/wait", token, {"query": "general", "by": "text", "timeout": 5}, timeout=10)
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and len(b["data"]) >= 1, b


def test_http_wait_timeout(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    t0 = time.monotonic()
    s, b = adb.http("POST", "/v1/wait", token, {"query": "zzznope999", "by": "text", "timeout": 2}, timeout=10)
    assert s == 200 and b["ok"] is False and "wait" in (b.get("error") or "").lower(), b
    assert time.monotonic() - t0 >= 1.5  # it actually polled for the timeout


def test_http_launch_wait_foreground(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token,
                    {"package": "com.android.settings", "wait": True, "timeout": 10}, timeout=15)
    assert s == 200 and b["ok"] and b["data"]["launched"] is True and b["data"]["foreground"] is True, b
    adb.http("POST", "/v1/global", token, {"nav": "home"})


def test_cli_wait(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    rc, out = adb.cli(["wait", "general", "--by", "text", "--timeout", "5"], token)
    assert rc == 0, out
    data = json.loads(out)["data"]
    assert isinstance(data, list) and len(data) >= 1


# ---- pairing and per-client tokens ----

def _wrong_code(code):
    return ("2" if code[0] != "2" else "1") + code[1:]


def test_pair_over_http_mints_token(token):
    # the fix: a client with no token pairs over http (proot/termux can't return
    # an `am` broadcast result). the minted token then authenticates.
    code = adb.start_pairing()
    s, b = adb.http_pair(code, "pairclient")
    assert s == 200 and b["ok"], b
    minted, cid = b["data"]["token"], b["data"]["id"]
    assert minted and cid
    s, b = adb.http("POST", "/v1/status", minted)
    assert s == 200 and b["ok"]


def test_pair_code_is_one_time(token):
    code = adb.start_pairing()
    s, _ = adb.http_pair(code)
    assert s == 200
    s, b = adb.http_pair(code)  # window closed on first redeem
    assert s == 401 and b["ok"] is False


def test_pair_rejects_bad_code(token):
    code = adb.start_pairing()
    s, b = adb.http_pair(_wrong_code(code))
    assert s == 401 and b["ok"] is False
    s, b = adb.http_pair(code)  # real code still valid after a bad attempt
    assert s == 200 and b["ok"]


def test_pair_appears_in_list_without_reopen(token):
    # pairing happens over http and never touches the ui; a foreground app must
    # still show the new client live, without an exit/reopen to force onResume.
    code = adb.start_pairing()  # leaves the app in the foreground
    s, b = adb.http_pair(code, "liveclient")
    assert s == 200, b
    cid = b["data"]["id"]
    time.sleep(0.8)  # let the token-store change post back to the ui thread
    assert adb.node_with(adb.ui(), f"revoke {cid}") is not None, \
        "paired client did not appear in the list without reopening the app"


def test_gui_revoke_invalidates_one_token(token):
    c1 = adb.start_pairing()
    s, b = adb.http_pair(c1, "clientone")
    assert s == 200, b
    t1, id1 = b["data"]["token"], b["data"]["id"]
    c2 = adb.start_pairing()
    s, b = adb.http_pair(c2, "clienttwo")
    assert s == 200, b
    t2 = b["data"]["token"]
    assert adb.http("POST", "/v1/status", t1)[0] == 200
    assert adb.http("POST", "/v1/status", t2)[0] == 200
    adb.revoke_in_ui(id1)
    assert adb.http("POST", "/v1/status", t1)[0] == 401  # revoked
    assert adb.http("POST", "/v1/status", t2)[0] == 200  # untouched


# ---- mcp surface ----

def test_mcp_initialize(token):
    s, b = adb.mcp(token, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}})
    assert "result" in b
    assert b["result"]["serverInfo"]["name"] == "mimic"


def test_mcp_tools_list(token):
    s, b = adb.mcp(token, "tools/list")
    names = [t["name"] for t in b["result"]["tools"]]
    assert {"mimic_dump", "mimic_find", "mimic_wait", "mimic_tap", "mimic_status",
            "mimic_screenshot", "mimic_packages"} <= set(names)


def test_mcp_screenshot_image_block(token):
    import base64
    time.sleep(1.2)  # screenshot is rate-limited to ~1/sec
    s, b = adb.mcp(token, "tools/call", {"name": "mimic_screenshot", "arguments": {"scale": "0.5"}})
    block = b["result"]["content"][0]
    assert block["type"] == "image" and block["mimeType"] == "image/png"
    assert base64.b64decode(block["data"])[:8] == b"\x89PNG\r\n\x1a\n"


def test_mcp_tools_call_status(token):
    s, b = adb.mcp(token, "tools/call", {"name": "mimic_status", "arguments": {}})
    assert b["result"]["isError"] is False
    assert "service_enabled" in b["result"]["content"][0]["text"]


def test_mcp_rejects_bad_token(token):
    s, b = adb.http("POST", "/mcp", "WRONGTOKEN", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert s == 401


def _mcp_call(token, name, args=None):
    s, b = adb.mcp(token, "tools/call", {"name": name, "arguments": args or {}})
    return b["result"]


def test_mcp_launch_success_reads_as_success(token):
    # the reported bug: a successful launch must not read as a failure. isError is
    # false and the text is a plain affirmative, not raw {"launched":true}.
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    r = _mcp_call(token, "mimic_launch", {"package": "com.android.settings"})
    assert r["isError"] is False, r
    text = r["content"][0]["text"].lower()
    assert "launch" in text and "no launch" not in text and "fail" not in text, text
    time.sleep(1.5)
    top = adb.top_activity()
    _mcp_call(token, "mimic_global", {"nav": "home"})
    assert "com.android.settings" in top


def test_mcp_launch_failure_is_error(token):
    r = _mcp_call(token, "mimic_launch", {"package": "com.example.nope"})
    assert r["isError"] is True
    assert "no launch intent" in r["content"][0]["text"]


def test_mcp_tap_and_global(token):
    r = _mcp_call(token, "mimic_tap", {"x": 10, "y": 10})
    assert r["isError"] is False and "perform" in r["content"][0]["text"].lower()
    r = _mcp_call(token, "mimic_global", {"nav": "home"})
    assert r["isError"] is False


def test_mcp_dump_and_find(token):
    r = _mcp_call(token, "mimic_dump", {"filter": "interactive", "format": "compact"})
    assert r["isError"] is False and isinstance(r["content"][0]["text"], str)
    r = _mcp_call(token, "mimic_find", {"query": "the", "by": "text"})
    assert r["isError"] is False


def test_mcp_packages(token):
    r = _mcp_call(token, "mimic_packages", {"query": "settings"})
    assert r["isError"] is False
    assert "com.android.settings" in r["content"][0]["text"]


def test_mcp_wait(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    r = _mcp_call(token, "mimic_wait", {"query": "general", "by": "text", "timeout": 5})
    assert r["isError"] is False and isinstance(r["content"][0]["text"], str)


def test_mcp_unknown_tool_is_error(token):
    r = _mcp_call(token, "mimic_nope", {})
    assert r["isError"] is True and "unknown tool" in r["content"][0]["text"]


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


# ---- authorization (per-token approval) -- runs last; restores approval off ----
# the suite stays non-interactive: tokens are set to allow-all so no on-device
# prompt appears. the interactive prompt (block/allow/deny/remember/grant-revoke)
# is verified by hand -- the service overlay is not visible to uiautomator.

def test_approval_allow_all_mode_bypasses(approval_on, token):
    # the session token is set to allow-all at setup, so even with approval on a
    # gated command runs without an on-device prompt.
    s, b = adb.http("POST", "/v1/packages", token, {})
    assert s == 200 and b["ok"] and isinstance(b["data"], list)


def test_auth_off_allows_no_token(token):
    # require-authentication off: a gated command runs without any token.
    adb.set_auth(False)
    try:
        s, b = adb.http("POST", "/v1/packages", "")  # no token
        assert s == 200 and b["ok"] and isinstance(b["data"], list), b
    finally:
        adb.set_auth(True)
    s, b = adb.http("POST", "/v1/packages", "")  # auth back on -> rejected
    assert s == 401
