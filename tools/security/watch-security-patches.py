#!/usr/bin/env python3
"""Watch Chromium forks and upstream for security fixes, and file triage issues.

The machinery behind [decision 0017](docs/decisions/0017-corroborate-security-fixes-across-forks.md).
Cobalt is pinned to Chromium 140 and backports security fixes rather than
rebasing ([0016](docs/decisions/0016-pin-chromium-140-and-backport.md)), and
0016 named the load-bearing risk itself:

    The hard part is not applying backports. It is knowing what to backport.

0017's answer is corroboration: every fork carrying an out-of-tree patch set
faces the same problem, and a fork shipping an out-of-band release is a public,
timestamped statement that a diff was security-critical. This turns that from an
intention into a cron job.

## What it does

1. Reads the Chrome Releases feed for CVE lists -- the authoritative upstream
   record, with severity, component and bug id.
2. Reads recent commits from each tracked fork and scans messages for CVE ids.
3. Joins them **on the CVE id**, which is the only identifier all four sources
   share, and files one issue per CVE with the corroboration matrix filled in.

## What it does not do

**It does not decide anything, and it never touches code.** 0017 is explicit
that forks are a triage signal and upstream is the source of code, and that a
fork's patch is not automatically correct for Cobalt -- 0011 exists because a
mechanically applied patch disabled a security check for two builds. Every issue
this opens is labelled `needs-triage` and says so.

It will also miss things. A fork that fixes a bug without naming the CVE is
invisible to a CVE-keyed join, and Chromium restricts bug visibility for months.
This is a floor on what gets noticed, not a ceiling.

Usage:
    watch-security-patches.py --dry-run          print what would be filed
    watch-security-patches.py --file             open/update issues via gh
    watch-security-patches.py --days 14          how far back to read forks
"""

import argparse
import html
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from xml.etree import ElementTree

# Forks tracked for corroboration. Each carries a large out-of-tree patch set
# against Chromium and ships its own releases, which is what makes its timing
# informative. None of them will hand Cobalt a patch that applies to M140 --
# they all track forward -- so these are read as evidence, never as code (0017).
FORKS = [
    ("Vanadium", "GrapheneOS/Vanadium"),
    ("Cromite", "uazo/cromite"),
    ("Brave", "brave/brave-core"),
]

CHROME_RELEASES = "https://chromereleases.googleblog.com/feeds/posts/default"

CVE_RE = re.compile(r"CVE-\d{4}-\d{4,7}")

# Chrome Releases lists fixes as, e.g.:
#   [$5000][40012345] High CVE-2025-1234: Use after free in Media.
ADVISORY_RE = re.compile(
    r"\[(?:[^\]]*)\]\[(?P<bug>\d+)\]\s*"
    r"(?P<severity>Critical|High|Medium|Low)\s+"
    r"(?P<cve>CVE-\d{4}-\d{4,7}):\s*(?P<title>[^.<]+)")

# Subsystems Cobalt does not compile, so a CVE confined to one does not apply.
# Kept in sync by hand with tools/build/build-chromium.sh's args.gn block --
# this script runs on a GitHub runner with no Chromium checkout, so it cannot
# read the real args.gn. If that block changes, change this.
NOT_COMPILED = {
    "vr": "enable_vr = false",
    "webxr": "enable_vr = false, and WebXR's API surface is removed too",
    "openxr": "enable_openxr = false",
    "arcore": "enable_arcore = false",
    "cardboard": "enable_cardboard = false",
    "nacl": "removed from Chromium entirely before M140",
}

# Areas where a fix is almost certainly relevant to Cobalt and should be looked
# at first. Substring match against the advisory title and component.
HIGH_RELEVANCE = {
    "net": "touches the network stack",
    "v8": "touches the JavaScript engine",
    "blink": "touches the rendering engine",
    "webrtc": "touches WebRTC",
    "media": "touches media decoding, a common drive-by path",
    "extensions": "touches extensions, which Cobalt enables far more of than upstream Android",
    "storage": "touches storage",
    "skia": "touches Skia",
    "angle": "touches ANGLE",
    "pdf": "touches the PDF reader",
    "downloads": "touches downloads",
    "navigation": "touches navigation",
    "loader": "touches resource loading",
    "webgl": "touches WebGL, which is reachable from any page",
    "webgpu": "touches WebGPU",
    "dawn": "touches Dawn, the WebGPU backend",
    "gpu": "touches the GPU process",
    "sandbox": "touches the sandbox",
    "mojo": "touches IPC",
    "sqlite": "touches SQLite",
    "freetype": "touches FreeType",
    "libwebp": "touches libwebp, shared with other browsers - cross-check Mozilla advisories (0017)",
    "fonts": "touches font handling",
    "autofill": "touches autofill",
    "permissions": "touches the permission system",
    "site isolation": "touches site isolation",
}


def slug(name):
    """A label-safe form of a source name: 'Chromium upstream' -> 'upstream'."""
    return name.lower().replace("chromium ", "").replace(" ", "-")


def strip_html(text):
    """Chrome Releases posts are styled HTML, not text.

    The bug id and the severity sit either side of several <span> tags, so
    ADVISORY_RE matches nothing at all against the raw content -- it finds the
    CVE ids and silently parses zero advisories, which looks exactly like a
    quiet week. Strip first.
    """
    text = re.sub(r"<br\s*/?>", "\n", text)
    text = re.sub(r"</(p|div|li)>", "\n", text)
    text = re.sub(r"<[^>]+>", "", text)
    return html.unescape(text)


# GitHub's API documents a descriptive User-Agent as a requirement and rejects
# requests without one; Chrome Releases serves the feed to anything. Both were
# checked with a descriptive UA and with a Chrome-like one, and both returned
# 200 either way -- the 403s seen while developing this were
# `x-ratelimit-remaining: 0`, i.e. unauthenticated quota exhaustion, not
# user-agent filtering. So the honest UA stays. It is overridable for the day a
# source does start discriminating, because that is a configuration change and
# should not need a code change.
USER_AGENT = os.environ.get(
    "COBALT_WATCH_UA",
    "cobalt-security-watch (+https://github.com/AurielSolaris/Cobalt)")


class Unreachable(Exception):
    """A source could not be read. Never confuse this with 'nothing found'."""


def get(url, token=None, accept="application/vnd.github+json", attempts=3):
    last = None
    for attempt in range(attempts):
        req = urllib.request.Request(url, headers={
            "User-Agent": USER_AGENT,
            "Accept": accept,
        })
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return r.read()
        except urllib.error.HTTPError as exc:
            remaining = exc.headers.get("x-ratelimit-remaining")
            if exc.code in (403, 429) and remaining == "0":
                last = (f"rate limited (x-ratelimit-remaining=0); "
                        f"{'token was sent' if token else 'no token was sent'}")
            else:
                last = f"HTTP {exc.code} {exc.reason}"
            # 5xx and 429 are worth another try; a hard 403 is not.
            if exc.code < 500 and exc.code != 429:
                break
        except urllib.error.URLError as exc:
            last = str(exc.reason)
        if attempt < attempts - 1:
            time.sleep(2 ** attempt)
    raise Unreachable(last or "unknown error")


# --------------------------------------------------------------- upstream


def upstream_advisories(days):
    """CVEs from the Chrome Releases feed, with severity and component."""
    raw = get(CHROME_RELEASES, accept="application/atom+xml")

    root = ElementTree.fromstring(raw)
    ns = {"a": "http://www.w3.org/2005/Atom"}
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    found = {}

    for entry in root.findall("a:entry", ns):
        published = entry.findtext("a:published", default="", namespaces=ns)
        try:
            when = datetime.fromisoformat(published.replace("Z", "+00:00"))
        except ValueError:
            continue
        if when < cutoff:
            continue
        content = strip_html(
            entry.findtext("a:content", default="", namespaces=ns) or "")
        link = ""
        for l in entry.findall("a:link", ns):
            if l.get("rel") == "alternate":
                link = l.get("href", "")
        for m in ADVISORY_RE.finditer(content):
            found[m.group("cve")] = {
                "severity": m.group("severity"),
                "title": m.group("title").strip(),
                "bug": m.group("bug"),
                "release": link,
                "when": when,
            }
    return found


# ------------------------------------------------------------------ forks


def fork_commits(repo, days, token):
    """Recent commits from one fork, as {cve: [(sha, subject)]}.

    One paged listing per repo rather than a commit search per CVE: search is
    rate-limited hard enough that a per-CVE query would exhaust the budget
    within a single run.
    """
    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()
    hits = {}
    url = f"https://api.github.com/repos/{repo}/commits?since={since}&per_page=100"
    try:
        commits = json.loads(get(url, token))
    except json.JSONDecodeError as exc:
        raise Unreachable(f"unparseable response: {exc}")

    for c in commits:
        message = (c.get("commit", {}).get("message") or "")
        subject = message.splitlines()[0] if message else ""
        for cve in set(CVE_RE.findall(message)):
            hits.setdefault(cve, []).append((c["sha"][:7], subject, c["html_url"]))
    return hits


# ------------------------------------------------------------- relevance


def relevance(advisory):
    """One line on whether this plausibly matters to Cobalt.

    Deliberately conservative: it flags for attention rather than filtering, and
    only claims 'does not apply' for subsystems that are provably not compiled.
    Getting this wrong in the permissive direction wastes a triage; getting it
    wrong in the other direction ships a hole.
    """
    text = (advisory.get("title", "") + " " + advisory.get("component", "")).lower()

    for key, why in NOT_COMPILED.items():
        if re.search(rf"\b{re.escape(key)}\b", text):
            return f"probably not applicable - {why}. Confirm against args.gn."

    for key, why in HIGH_RELEVANCE.items():
        if re.search(rf"\b{re.escape(key)}\b", text):
            return f"warning - {why}"

    return "unclassified - needs a human to place the component"


# ------------------------------------------------------------------ issues


def gh(args, check=True):
    return subprocess.run(["gh"] + args, capture_output=True, text=True,
                          check=check)


def existing_issue(cve, repo):
    """The issue already tracking this CVE, if any. Keeps runs idempotent."""
    r = gh(["issue", "list", "--repo", repo, "--state", "all",
            "--search", cve, "--json", "number,title,body", "--limit", "10"],
           check=False)
    if r.returncode != 0:
        return None
    try:
        for issue in json.loads(r.stdout or "[]"):
            if cve in issue.get("title", ""):
                return issue
    except json.JSONDecodeError:
        pass
    return None


def build_issue(cve, advisory, fork_hits):
    """Title, body and labels for one CVE."""
    # Source is whichever fork saw it, because a fork seeing it before Chrome
    # Releases does is the entire point of watching them. Upstream only becomes
    # the source when no fork has mentioned it.
    source_name, source_ref, source_link = "Chromium upstream", "", ""
    for name, repo in FORKS:
        if cve in fork_hits.get(name, {}):
            sha, _subject, link = fork_hits[name][cve][0]
            source_name = name
            source_ref = f"{repo}@{sha}"
            source_link = link
            break

    severity = advisory.get("severity", "unknown")
    component = advisory.get("title") or "unknown"
    bug = advisory.get("bug")

    patched_by = []
    for name, _repo in FORKS:
        mark = "yes" if cve in fork_hits.get(name, {}) else "not seen"
        patched_by.append(f"{name} {mark}")
    upstream_mark = "yes" if advisory.get("severity") else "not yet"
    patched_by.append(f"Chromium upstream {upstream_mark}")

    title = f"[{cve}] Patched in {source_name} - needs Cobalt triage"

    links = []
    if source_link:
        links.append(f"[the {source_name} commit]({source_link})")
    links.append(f"[{cve} on NVD](https://nvd.nist.gov/vuln/detail/{cve})")
    if bug:
        links.append(f"[Chromium bug {bug}](https://issues.chromium.org/issues/{bug})")
    if advisory.get("release"):
        links.append(f"[Chrome Releases post]({advisory['release']})")

    body = f"""- **Source:** {source_name}{f" ({source_ref})" if source_ref else ""}
- **CVE:** {cve}
- **Severity:** {severity}
- **Affected component:** {component}
- **Cobalt relevance:** {relevance({**advisory, "component": component})}
- **Also patched by:** {" | ".join(patched_by)}

{" - ".join(links)}

---

Filed automatically by `tools/security/watch-security-patches.py`.

**Nothing has been decided and no code has been touched.** Per
[0017](docs/decisions/0017-corroborate-security-fixes-across-forks.md), forks
are a triage signal and upstream is the source of code: take the fix from
Chromium's own milestone branch and adapt it to M140 rather than lifting a
fork's version, which was written against a different base and a different patch
set. Per [0011](docs/decisions/0011-refuse-mechanical-disablers.md), read it
before applying it.

Triage checklist:

- [ ] Does the affected code exist in M140, and in a form Cobalt compiles?
- [ ] Find the upstream fix and the milestone branch carrying it
- [ ] Adapt to M140, or record why it cannot be backported (a rebase trigger --
      see [0016](docs/decisions/0016-pin-chromium-140-and-backport.md))
- [ ] Land it through `tools/patches/series.txt`, never by hand
- [ ] Add it to the published backport record, citing *why* it was identified
"""

    labels = ["security", "needs-triage", f"source:{slug(source_name)}"]
    return title, body, labels


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--days", type=int, default=14,
                    help="how far back to read forks and releases")
    ap.add_argument("--file", action="store_true",
                    help="open or update issues (default is to print)")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY",
                                                     "AurielSolaris/Cobalt"))
    ap.add_argument("--max-issues", type=int, default=25,
                    help="cap issues filed per run; the rest wait for the next "
                         "run rather than being dropped")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")

    # A source that could not be read is not a source that found nothing. An
    # earlier version of this printed "nothing to triage" while all three forks
    # were returning 403, which is the single worst thing a security tool can
    # do, so unreachability is tracked and reported separately throughout.
    unreachable = []

    try:
        advisories = upstream_advisories(args.days)
    except Unreachable as exc:
        unreachable.append(("Chrome Releases", str(exc)))
        advisories = {}

    fork_hits = {}
    for name, repo in FORKS:
        try:
            fork_hits[name] = fork_commits(repo, args.days, token)
        except Unreachable as exc:
            unreachable.append((repo, str(exc)))
            fork_hits[name] = {}

    cves = set(advisories)
    for hits in fork_hits.values():
        cves |= set(hits)

    print(f"window: {args.days} days")
    print(f"upstream advisories: {len(advisories)}")
    for name, _ in FORKS:
        print(f"{name} commits naming a CVE: {len(fork_hits[name])}")
    print(f"distinct CVEs: {len(cves)}")

    if unreachable:
        print()
        print(f"UNREACHABLE ({len(unreachable)}):", file=sys.stderr)
        for who, why in unreachable:
            print(f"  {who}: {why}", file=sys.stderr)
        if not token:
            print("  (no GITHUB_TOKEN/GH_TOKEN in the environment; the GitHub "
                  "API allows very few unauthenticated requests per hour)",
                  file=sys.stderr)

    if len(unreachable) == 1 + len(FORKS):
        print("read nothing from any source -- that is a failure, "
              "not an all-clear", file=sys.stderr)
        return 2

    print()

    if not cves:
        if unreachable:
            print(f"no CVEs from the sources that answered; "
                  f"{len(unreachable)} did not answer")
            return 1
        print("nothing to triage")
        return 0

    # Severity first, so a run that hits the cap files the ones that matter
    # most. Nothing is discarded: what does not fit is filed by the next run.
    # This is pacing, not the "Critical and High only" policy that decision 0016
    # explicitly rejects -- Medium bugs chain, and they still get an issue.
    rank = {"Critical": 0, "High": 1, "Medium": 2, "Low": 3}
    order = sorted(cves, key=lambda c: (rank.get(advisories.get(c, {})
                                                 .get("severity"), 4), c))

    opened = skipped = 0
    for cve in order:
        title, body, labels = build_issue(cve, advisories.get(cve, {}), fork_hits)

        if not args.file:
            print("=" * 72)
            print(title)
            print("labels:", ", ".join(labels))
            print()
            print(body)
            continue

        if existing_issue(cve, args.repo):
            print(f"  {cve:<20} already tracked")
            skipped += 1
            continue

        if opened >= args.max_issues:
            print(f"  {cve:<20} deferred to the next run (cap {args.max_issues})")
            continue

        cmd = ["issue", "create", "--repo", args.repo,
               "--title", title, "--body", body]
        for l in labels:
            cmd += ["--label", l]
        r = gh(cmd, check=False)
        if r.returncode != 0:
            print(f"  {cve:<20} FAILED  {r.stderr.strip()}", file=sys.stderr)
        else:
            print(f"  {cve:<20} filed  {r.stdout.strip()}")
            opened += 1

    if args.file:
        print(f"\n{opened} filed, {skipped} already tracked")
    # A partial read still files what it found, but it must not exit clean:
    # green means every source was read.
    return 1 if unreachable else 0


if __name__ == "__main__":
    sys.exit(main())
