# 0002 — Shell design: One Dark, boxy, four sections, no ad surfaces

> **Superseded in part by [0007](0007-user-themes.md):** the shipped default
> theme is now IBM Carbon iced with a One Dark cast, and users can author their
> own themes on the device. One Dark remains a preset. Everything else here —
> boxy corners, the four bottom sections, no ad surfaces — still stands.


**Status:** accepted
**Date:** 2026-09-07

## Context

0.1.0 needed a chrome to put the address bar and content area in. The direction
was set from a mobile browser concept mockup: a dark interface with a bottom
navigation bar, a tab switcher, and a dedicated incognito surface. The mockup was
a reference for layout and mood only — no assets, code, or branding from it are
used, and the decisions below are Cobalt's own.

The shell is not throwaway code. Stage 6 replaces what draws *inside* the content
area (the Compose renderer, when Blink lands); the chrome around it is the part
of Cobalt we own outright and keep. So it was worth designing now rather than
stubbing.

## Decisions

**One Dark, blue accent.** Atom's palette, unchanged, with `#61AFEF` as the
single accent. Using an established palette means a rendered code block agrees
with the interface around it. More themes come later; the palette is expressed as
named entries so a second theme is a matter of supplying another set.

**Boxy corners.** Material 3's shape scale runs 4dp–28dp, which reads as
consumer-soft. Cobalt's runs 0dp–3dp. The selected-item pill in the navigation
bar is removed for the same reason; the accent colour carries selection instead.

**Fonts: Open Sans, EB Garamond, JetBrains Mono.** Sans for the interface, serif
for display text and document headings, mono for code. All three are variable
fonts with the weight axis set explicitly, because synthetic bolding of a
variable face smears the strokes.

**Bottom bar: Home, Extensions, Tabs, Downloads** *(0.1.0; superseded by the
one-toolbar amendment below, which moved all four into the toolbar and its
sheets)*. Bookmarks were on the bar initially and were moved into the overflow
menu. A bookmark is a
property of the page you are looking at, so the control belongs next to the
address that identifies it, and saving one is a per-page action rather than a
place you navigate to. That frees the slot for extensions — the feature Cobalt
exists to bring back.

**One toolbar, at the bottom; everything else in sheets** *(amended
2026-09-11)*. 0.1.0 had the address bar at the top and a four-section bar at
the bottom. Now there is one row under the page: the address (reload or stop
inside it), the tab count, and ⋮. The tab switcher and the options menu are
bottom sheets that rise from that row: page actions as tiles (new tab, forward,
reload, screenshot), places as a list (home, extensions, downloads, bookmarks,
settings). Nothing is drawn above the page and nothing floats over it. The
reasons are less on screen competing with the page, and every control within a
thumb's reach. While the keyboard is up, the toolbar rides above it.

A floating action button was considered for home, tabs and reload, and
rejected. It covers whatever part of the page is under it (consent banners and
sites' own bottom navigation, in practice), and it would make the tab switcher,
the most used control after the address, cost two taps instead of one.

**The tab switcher shows page previews.** Two columns of cards, each with a
picture of the page as it last looked and its title. 0.1.0 chose a list because
titles do not survive cropping; with a real renderer, the page's look is how
people find a tab, and the title stays on the card.

**No shortcut grid, no trending list.** Concept designs for this screen usually
carry both. Both are advertising surfaces: shortcut tiles are sold placements, and a trending feed
has an incentive to be sticky. Cobalt has neither and will not grow either. When
Stage 7 gives it browsing history, the new-tab page can show the user's own
most-visited sites — earned by their behaviour, not by a payment.

**Incognito is a full recolour, not a badge.** Cyan accent on a darker ground,
applied to the whole shell. Being wrong about which mode you are in is an
asymmetric mistake, so the signal has to be visible from across a room. The
incognito entry sits beside "New tab" as an equally sized button rather than
inside a menu, because burying the private option is itself a nudge.

**Tabs exist now, in memory.** A list of documents and nothing more: no process
per tab, no persistence, no eviction. Building the shell around a single page and
then rebuilding it in Stage 7 would cost more than carrying a tab list from the
start. The real model arrives with Chromium's.

## Consequences

- Stage 1 grew beyond its original scope, which listed tabs as a non-goal. That
  was the right call for the shell and the wrong call for anything below it: the
  renderer stayed as thin as planned.
- Sections that do not work yet — extensions, bookmarks, downloads, settings —
  say what they are waiting for and which stage delivers it, rather than showing
  an empty list that implies a feature exists.
- The incognito screen states plainly that 0.1.0 stores nothing in *any* tab, so
  the separation is not yet enforced. A vague privacy promise is worse than none.
