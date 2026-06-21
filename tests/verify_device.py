#!/usr/bin/env python3
"""one-shot on-device verification of the http + mcp + intents surfaces.

drives the new ui (reveal token, toggle surfaces), forwards the port, then
exercises every surface and prints a pass/fail report. requires a connected
device and the debug apk built. shares helpers with the pytest e2e (adb.py).
"""

import json
import sys
import time

import adb


def main():
    if not adb.APK.exists():
        sys.exit(f"build first: {adb.APK} missing")
    if not adb.device_available():
        sys.exit("no device attached")

    adb.install()
    adb.enable_accessibility()
    adb.launch()
    token = adb.reveal_token()
    print("token:", token[:8] + "...")
    for label in ("intents", "local http", "mcp server"):
        adb.ensure_switch(label, True)
    adb.forward()

    # reinstalling strips the accessibility grant; the service rebinds a few
    # seconds after re-enabling. wait for it to connect before the view tests.
    for _ in range(15):
        s, b = adb.http("POST", "/v1/status", token)
        if s == 200 and b.get("ok") and b["data"].get("service_enabled"):
            break
        time.sleep(1)
    print("service_enabled:", b.get("data", {}).get("service_enabled"))

    results = []

    def check(name, ok, detail=""):
        results.append(ok)
        print(f"  [{'PASS' if ok else 'FAIL'}] {name}  {detail}")

    print("\n== http ==")
    s, b = adb.http("GET", "/healthz", "")
    check("healthz", s == 200 and b.get("ok"), str(b))
    s, b = adb.http("POST", "/v1/status", token)
    check("status", s == 200 and b.get("ok") and b["data"]["service_enabled"], json.dumps(b.get("data", {})))
    s, b = adb.http("POST", "/v1/dump", token, {"filter": "interactive", "format": "compact"})
    check("dump compact", s == 200 and b.get("ok"), repr((b.get("data") or "")[:80]))
    s, b = adb.http("POST", "/v1/status", "WRONG")
    check("auth rejects bad token", s == 401, str(s))

    print("\n== mcp ==")
    s, b = adb.mcp(token, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}})
    check("initialize", b.get("result", {}).get("serverInfo", {}).get("name") == "mimic", "")
    s, b = adb.mcp(token, "tools/list")
    tools = [t["name"] for t in b.get("result", {}).get("tools", [])]
    check("tools/list", "mimic_dump" in tools, f"{len(tools)} tools")
    s, b = adb.mcp(token, "tools/call", {"name": "mimic_status", "arguments": {}})
    check("tools/call mimic_status", b.get("result", {}).get("isError") is False, "")

    print("\n== intents ==")
    r = adb.broadcast("STATUS")
    check("broadcast status", bool(r and r.get("ok")), "")

    print(f"\n{sum(results)}/{len(results)} checks passed")
    sys.exit(0 if all(results) else 1)


if __name__ == "__main__":
    main()
