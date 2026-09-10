#!/usr/bin/env python3
"""Prove, on a real device, that the bundled extension has unlimitedStorage.

Two independent mechanisms honour that permission and they share nothing but
the permission itself, so both are checked:

  * the quota system   ExtensionSpecialStoragePolicy -> QuotaManagerImpl,
                       covering IndexedDB, Cache API and the rest.
  * the settings store LocalValueStoreCache -> WeakUnlimitedSettingsStorage,
                       covering chrome.storage.local, which has its own
                       QUOTA_BYTES enforcer and knows nothing about the above.

Why this exists as a script rather than a paragraph in a document: the first
answer recorded for unlimitedStorage was reasoned from GN files and was wrong
in both directions -- first "it is gated off" (it is not), then a measurement
that said "not in effect" (the extension had been terminated). Reading build
files predicts what is compiled, not what happens. Run this instead.

THE TRAP THIS SCRIPT EXISTS TO CATCH: a terminated extension reports exactly
the same numbers as one that never had the permission, because termination
revokes the grant. That produced a convincing false negative once already. The
extension's state is therefore checked first, and a run against anything but
ENABLED is refused rather than reported.

Usage:  tools/device/check-unlimited-storage.py [--serial SERIAL] [--id EXT_ID]

Needs:  adb on PATH, a device with Cobalt installed and running, and
        python3 -m pip install websocket-client
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.request

UBLOCK_ID = "fimbmjialkbnbbedhcpdodbhicmjfgli"
PORT = 9222
CONTROL_ORIGIN = "https://example.com/"

# chrome.storage.local's documented ceiling. Writing past it must succeed.
QUOTA_BYTES = 10 * 1024 * 1024
PROBE_KEY = "__cobalt_unlimited_storage_probe"

try:
    import websocket  # websocket-client
except ImportError:
    sys.exit("need websocket-client:  python3 -m pip install websocket-client")


def adb(args, serial=None):
    cmd = ["adb"] + (["-s", serial] if serial else []) + args
    return subprocess.run(cmd, capture_output=True, text=True)


class Devtools:
    """Minimal CDP client, driven entirely through the browser endpoint.

    Per-page WebSocket endpoints do not answer on Android -- they accept the
    connection and then never reply -- so every session here is opened with
    Target.attachToTarget from /devtools/browser instead.
    """

    def __init__(self, port):
        url = "http://127.0.0.1:%d/json/version" % port
        with urllib.request.urlopen(url, timeout=10) as r:
            ws_url = json.load(r)["webSocketDebuggerUrl"]
        # suppress_origin: DevTools rejects a handshake carrying an Origin
        # header it was not told to allow, with a 403 and no retry.
        self.ws = websocket.create_connection(
            ws_url, timeout=30, suppress_origin=True)
        self.n = 0

    def call(self, method, session=None, **params):
        self.n += 1
        msg = {"id": self.n, "method": method, "params": params}
        if session:
            msg["sessionId"] = session
        self.ws.send(json.dumps(msg))
        while True:
            m = json.loads(self.ws.recv())
            if m.get("id") == self.n:
                if "error" in m:
                    raise RuntimeError("%s: %s" % (method, m["error"]))
                return m["result"]

    def a_page(self):
        for t in self.call("Target.getTargets", filter=[{}])["targetInfos"]:
            if t["type"] == "page":
                return t["targetId"]
        raise RuntimeError("no page target; is the browser showing a tab?")

    def eval_at(self, url, expression, settle=6):
        target = self.a_page()
        session = self.call(
            "Target.attachToTarget", targetId=target, flatten=True)["sessionId"]
        self.call("Page.navigate", session=session, url=url)
        time.sleep(settle)
        r = self.call("Runtime.evaluate", session=session,
                      expression=expression, awaitPromise=True,
                      returnByValue=True)
        if "exceptionDetails" in r:
            raise RuntimeError("%s: %s"
                               % (url, r["exceptionDetails"].get("text")))
        return r["result"].get("value")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial")
    ap.add_argument("--id", default=UBLOCK_ID)
    args = ap.parse_args()

    ext = args.id
    origin = "chrome-extension://%s" % ext

    r = adb(["forward", "tcp:%d" % PORT, "localabstract:chrome_devtools_remote"],
            args.serial)
    if r.returncode != 0:
        sys.exit("adb forward failed: %s" % r.stderr.strip())

    dt = Devtools(PORT)
    failures = []

    # --- 0. The extension must be alive, or nothing below means anything. ---
    print("=== extension state")
    info = dt.eval_at("chrome://extensions/", """
        new Promise(r => setTimeout(() => chrome.developerPrivate.getExtensionsInfo(
            {includeDisabled: true},
            i => r(JSON.stringify(i.map(e => ({id: e.id, state: e.state}))))), 4000))
    """, settle=8)
    states = {e["id"]: e["state"] for e in json.loads(info)}
    state = states.get(ext)
    print("  %s  %s" % (ext, state))
    if state != "ENABLED":
        sys.exit(
            "  REFUSED  extension is %s, not ENABLED.\n"
            "           A terminated extension has its storage rights revoked,\n"
            "           so it measures identically to one that never had the\n"
            "           permission. Restart the browser and re-run."
            % (state or "not installed"))

    # --- 1. Quota system: the extension origin against a plain web origin. ---
    print()
    print("=== quota system (ExtensionSpecialStoragePolicy -> QuotaManagerImpl)")
    estimate = ("(async () => JSON.stringify(await navigator.storage.estimate()))()")
    control = json.loads(dt.eval_at(CONTROL_ORIGIN, estimate))
    subject = json.loads(dt.eval_at(origin + "/manifest.json", estimate))
    print("  control  %-24s quota %d" % (CONTROL_ORIGIN, control["quota"]))
    print("  subject  %-24s quota %d" % (origin[:24], subject["quota"]))
    if subject["quota"] > control["quota"]:
        print("  PASS     subject is on the unlimited path (free space + usage),")
        print("           control is on the shared pool (a fraction of the disk)")
    else:
        failures.append("quota: subject %d is not above control %d"
                        % (subject["quota"], control["quota"]))
        print("  FAIL     subject is not above the shared pool")

    # chrome://quota-internals states the same thing more directly: it splits
    # total usage into unlimited and non-unlimited buckets.
    print()
    print("=== quota-internals bucket split")
    text = dt.eval_at("chrome://quota-internals/",
                      "new Promise(r => setTimeout("
                      "() => r(document.body.innerText), 9000))", settle=4)
    line = [l for l in text.splitlines() if "for unlimited origins" in l]
    if line:
        print("  %s" % line[0].strip())
    else:
        for l in text.splitlines():
            if "B (" in l:
                print("  %s" % l.strip())

    # --- 2. Settings store: write past chrome.storage.local's own quota. ---
    print()
    print("=== settings store (LocalValueStoreCache -> WeakUnlimitedSettingsStorage)")
    probe = """
      (async () => {
        const q = chrome.storage.local.QUOTA_BYTES;
        const before = await new Promise(r =>
            chrome.storage.local.getBytesInUse(null, r));
        const big = "x".repeat(q + 1048576);
        let err = null;
        await new Promise(r => chrome.storage.local.set({"%s": big}, () => {
            err = chrome.runtime.lastError ? chrome.runtime.lastError.message : null;
            r();
        }));
        const after = await new Promise(r =>
            chrome.storage.local.getBytesInUse(null, r));
        await new Promise(r => chrome.storage.local.remove("%s", r));
        const cleaned = await new Promise(r =>
            chrome.storage.local.getBytesInUse(null, r));
        return JSON.stringify({q, before, wrote: big.length, err, after, cleaned});
      })()
    """ % (PROBE_KEY, PROBE_KEY)
    s = json.loads(dt.eval_at(origin + "/dashboard.html", probe, settle=8))
    print("  QUOTA_BYTES  %d" % s["q"])
    print("  in use       %d" % s["before"])
    print("  wrote        %d   (%d past the limit on its own)"
          % (s["wrote"], s["wrote"] - s["q"]))
    print("  lastError    %s" % (s["err"] or "null"))
    print("  after        %d" % s["after"])
    print("  cleaned to   %d" % s["cleaned"])
    if s["err"] is None and s["after"] > s["q"]:
        print("  PASS     the quota enforcer is bypassed")
    else:
        failures.append("storage.local: lastError=%s after=%d"
                        % (s["err"], s["after"]))
        print("  FAIL     the write was rejected or truncated")
    if s["cleaned"] != s["before"]:
        failures.append("storage.local: probe key not cleaned up (%d != %d)"
                        % (s["cleaned"], s["before"]))

    print()
    if failures:
        print("FAILED:")
        for f in failures:
            print("  %s" % f)
        return 1
    print("OK  unlimitedStorage is in effect on both mechanisms.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
