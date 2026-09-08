# 0009 — Capture page diagnostics, without shipping a console

**Status:** accepted
**Stage:** 7 (browser features)

## Decision

Cobalt captures console output and page errors into a bounded in-memory buffer,
and offers **"Copy page diagnostics"** in the overflow menu so a user can send a
broken site's logs to whoever built it.

There is **no console UI**. No REPL, no evaluation, no DevTools panel. The only
exposed action is copying what was captured.

## Capture from the browser, not from injected JavaScript

The obvious implementation is to override `console.log` and friends in page
script. **Do not.** Chromium already exposes this at the browser layer:

```cpp
// content/public/browser/web_contents_observer.h
virtual void OnDidAddMessageToConsole(
    RenderFrameHost* source_frame,
    blink::mojom::ConsoleMessageLevel log_level,   // kVerbose|kInfo|kWarning|kError
    const std::u16string& message,
    int32_t line_no,
    const std::u16string& source_id,
    const std::optional<std::u16string>& untrusted_stack_trace) {}
```

`components/embedder_support/android/delegate/web_contents_delegate_android.cc`
already routes this to Java, so the wiring largely exists.

Four reasons the browser-side hook wins:

1. **The page cannot see it.** Overriding `console` is trivially detectable, and
   a site that checks will behave differently — the exact opposite of what a
   diagnostic feature needs.
2. **No injection race.** Script injected at document start still misses
   messages from earlier in the load; the observer gets everything.
3. **CSP cannot block it.** An injected script can be refused by the page's own
   Content-Security-Policy — most likely on precisely the strict, complex sites
   most worth diagnosing.
4. **It catches what `console.log` never sees.** CSP violations, mixed-content
   blocks, failed subresource loads and uncaught exceptions are reported through
   this path too, and they explain far more breakage than anything the site
   chose to log deliberately.

Note Chromium's own naming: `untrusted_stack_trace`. Everything here comes from
the renderer, which is the part of the browser most likely to be compromised.
Treat every captured string as **untrusted data** — never render it as HTML,
never let it reach a code path that interprets it.

## The privacy problem, which shapes the whole feature

Console output routinely contains bearer tokens, session identifiers, full URLs
with secrets in the query string, personal data from application state, and —
on badly built sites — credentials logged outright.

A one-tap "copy logs" that a user pastes into a public bug tracker can therefore
leak their own session. That is a real harm, caused by a feature meant to help.

So:

- **Show before copying.** The user sees exactly what will be copied, scrollable,
  and copies from that view. Never a blind copy.
- **Redact the obvious.** JWT-shaped strings, long hex and base64 runs, and query
  parameters named like `token`, `key`, `session`, `auth`, `password`, `secret`
  are masked before display, with a note saying redaction happened. Imperfect by
  nature — which is exactly why the preview is not optional.
- **Current page only.** The buffer is per-tab and clears on navigation. No
  history, no cross-origin mixing.
- **Never leaves the device by itself.** No upload, no telemetry, no "send to
  Cobalt" endpoint. The clipboard is the only exit, and the user drives it.
- **Nothing persisted.** Memory only; gone when the tab closes.

## Bounded, because the target is a 4 GB phone

A ring buffer capped at both entry count and total bytes — 500 entries or 256 KB,
whichever is reached first, oldest evicted. A page in a `console.log` loop must
not be able to grow browser memory without limit. Individual messages are
truncated with a marker rather than dropped, since a truncated error still names
the failure.

Capture is off the hot path: appending to a ring buffer per message is cheap, but
it happens for every message on every page, so it stays allocation-light.

## What the copied report contains

The URL, timestamp, Cobalt and Chromium versions, the device's Android version,
and the captured entries with level and source line. Enough for a developer to
act on, and no more.

## Consequences

- Menu item lives in the top-right overflow, alongside settings and bookmarks.
- Wording avoids "console" — it is "page diagnostics". The audience is a user
  reporting a broken site, not a developer.
- A settings toggle turns capture off entirely for anyone who does not want it
  running at all.
