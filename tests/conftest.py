"""fixtures: require a device, install the apk, enable the service, then reveal a
legacy token and turn on all three surfaces through the ui."""

import adb
import pytest


@pytest.fixture(scope="session", autouse=True)
def require_device():
    if not adb.device_available():
        pytest.skip("no adb device attached; skipping e2e", allow_module_level=False)


@pytest.fixture(scope="session")
def installed(require_device):
    if not adb.APK.exists():
        pytest.skip(f"debug apk not built ({adb.APK}); run `make` first")
    adb.install()
    adb.enable_accessibility()
    adb.launch()
    return True


@pytest.fixture(scope="session")
def token(installed):
    """reveal a legacy token and enable intents + http + mcp, then forward the port."""
    tok = adb.reveal_token()
    for label in ("intents", "local http", "mcp server"):
        adb.ensure_switch(label, True)
    adb.forward()
    return tok
