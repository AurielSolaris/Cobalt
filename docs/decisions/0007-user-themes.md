# 0007 — User-authored themes, edited on the device

**Status:** accepted, deferred
**Stage:** 7 (browser features) — after the patches land, not before
**Supersedes:** the default-theme choice in [0002](0002-shell-design.md)

## Decision

Cobalt ships preset themes **and** lets the user author their own **from the
app's own settings**, on the phone, with no desktop involved.

## Why the "on the device" part is the decision

A theme system driven by hand-written CSS files means: wait until you are at a
computer, write the file, connect a cable, copy it across, import it. That is not
a theme system on a phone — it is a theme system that happens to be reachable
from a phone. The customisation exists but almost nobody will use it.

So the in-app editor is the primary path and file import is the secondary one.
Import/export still earns its place — it is how themes get **shared** — but it is
not how they get *made*.

## The token vocabulary

Requested: primary bg, secondary bg, foreground, text-color, font-mono,
font-sans, accent, accent-gradient.

**`foreground` and `text-color` are the same thing** in every design-token system
worth copying, and having both invites them to drift apart. Resolved as:

| Token | Role |
|---|---|
| `bg.primary` | The page and chrome behind everything |
| `bg.secondary` | Cards, sheets, the address bar, raised surfaces |
| `fg` | Primary text and icons |
| `fg.muted` | Secondary text, placeholders, disabled labels |
| `accent` | Everything interactive |
| `accent.gradient` | Two or more stops plus an angle, for emphasis surfaces |
| `font.sans` | Interface text |
| `font.mono` | Code, URLs, anything that must align |

Four more are needed for the interface to actually work, and are derived with an
override available rather than demanded of the user up front:

`border` (dividers and outlines), and `success` / `warning` / `danger`. Security
state in the address bar cannot be left to a user palette — a theme must not be
able to make "insecure" look reassuring. Those three are **overridable but never
unset**, and the address-bar security indicator does not use them alone.

## Contrast: warn, never block

A user can pick black text on a black background. The editor computes contrast
(WCAG AA, 4.5:1 for body text) and **warns clearly** when a pairing fails.

It does not refuse. It is their browser, and a theme that is deliberately
low-contrast is a legitimate thing to want. Refusing to render it would be
paternalism; failing to mention it would be negligence. A one-tap "fix contrast"
that nudges the offending token to the nearest passing value covers the common
case, which is an accident rather than an intention.

## Fonts: bundled choices first

`font.sans` and `font.mono` pick from **bundled** fonts initially — EB Garamond,
Open Sans, and the mono face, plus the system stack.

Loading arbitrary user-supplied font files is deferred, and not for effort
reasons: font parsing is a well-trodden attack surface, and a browser accepting
untrusted font binaries from the filesystem deserves more thought than a theme
picker should carry. Revisit as its own decision.

## Incognito is derived, not authored

Incognito must stay visually distinct in every theme, including user-made ones,
because that distinction is a **safety signal** — the user has to be able to tell
at a glance which mode they are in.

So incognito is **derived** from the active theme by a fixed transform rather
than being a separate palette the user edits. A user cannot accidentally make
incognito look identical to normal browsing.

## The default theme

**IBM Carbon, iced, with a One Dark cast.** Carbon supplies the neutral greys
and the blue ramp; One Dark supplies the slight blue-grey warmth that keeps it
from feeling clinical.

Proposed starting values — **subject to review on a real screen**, since palettes
lie in hex and tell the truth on a phone at night:

| Token | Value | Source |
|---|---|---|
| `bg.primary` | `#16181C` | Carbon Gray 100, nudged blue |
| `bg.secondary` | `#21242B` | between Carbon Gray 90 and One Dark's deep bg |
| `surface` | `#2A2E36` | |
| `border` | `#3A3F4B` | |
| `fg` | `#F4F4F4` | Carbon Gray 10 — brighter than One Dark's `#ABB2BF` |
| `fg.muted` | `#8D949E` | |
| `accent` | `#78A9FF` | Carbon Blue 40 — the ice blue |
| `accent.gradient` | `#78A9FF` → `#82CFFF` | Carbon Blue 40 → Cyan 30 |

This replaces plain One Dark as the shipped default. One Dark stays as a preset —
it is a good theme and the work is already done.

## The icon

Colour palette with a brush. Traditional, instantly legible, and there is no
reason to be clever about it — the same reasoning that put a puzzle piece on
extensions.

## Consequences

- `Theme.kt` already defines named palette entries rather than scattered
  literals, so the token set is an extension of that structure, not a rewrite.
- Themes become **persisted user data** — they must survive reinstall, which
  means backup and export.
- Every new UI surface must consume tokens. A hardcoded colour anywhere is a bug
  once user themes exist, because it will not follow the theme.
- The theme editor is a settings section reachable from the top-right overflow,
  alongside the other settings moved there.
