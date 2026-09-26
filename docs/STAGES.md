# Morsecode — 17-stage build plan

Derived from `prompmaster.txt` §0.6 (build order), §6/§7 (screens), §22 (acceptance).
One stage per agent session. Paste the block verbatim. Never start a stage before the
previous one's §22 criteria pass.

Heavy stages: **5 (engine), 10 (Files), 14 (WebShare)** — budget 2–3 sessions each.
Light stages: **1, 3, 7, 12, 16**.

---

### Stage 1 — Project skeleton and identity

```text
Stage 1 of the plan: create the project skeleton and lock identity — §1 (product
identity, §1.1.1 verbatim: app.morsecode.android, MorsecodeApp.kt, 1.0.0 (1),
magic "MRSC", ports 33455/33456/33457), §3 (hard platform rules), §18 (Android
version matrix), §19 (build, versioning, release, signing), §20.8 (lint gate),
§20.9 (Kotlin constraints), Appendix B as the colour resources.
No feature code, no screens. Obey the §0.5 tags: [KEPT] rules are
release-blocking, [CHANGED] rules must not be "restored".
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A19 must already pass on an empty repo.
```

### Stage 2 — Design system ("Sunflower")

```text
Stage 2 of the plan: implement the design system — §4 in full: tokens §4.1–4.9,
launcher icon §4.10, motion §4.11, core components §4.12 (build once, reuse
everywhere), hard UI rules §4.13, responsive rules §4.14. Wire both themes and
all five accents so a single switch re-skins everything. Use Appendix A for
every string and Appendix B for every colour; no hex may appear in a layout.
Ship a component-gallery debug screen rendering every component in dark and
light at 320 dp, 360 dp and 600 dp.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each.
```

### Stage 3 — Navigation shell

```text
Stage 3 of the plan: build the navigation shell — §5 (information architecture,
the four-tab bottom nav and every route), §20.4 (screens receive their purpose
as an explicit intent, never an inferred mode), §6.19 (empty and error states
for every list), §6.18 (notification channels morsecode.transfer and
morsecode.webshare, created but not yet used).
Every destination is a stub that renders its real chrome with an empty state.
Obey §4 tokens and §4.14 responsive rules.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each.
```

### Stage 4 — Storage, permissions and the media library

```text
Stage 4 of the plan: implement storage and the media library — §12 in full
(12.1 access method by content type, 12.2 graceful denial, 12.3 all-files
access, 12.4 destinations incl. Download/Morsecode, 12.5 MediaLibrary with the
MediaStore date and pagination rules), §3.5 permissions exactly as listed, §13
device performance tiering, §20.10 (a count that takes minutes is a defect).
Expose the categories PHOTOS / VIDEOS / MUSIC / DOCUMENTS / APPS / FILES behind
one repository; no screen may query MediaStore directly.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A7 and A16 belong to this stage.
```

### Stage 5 — Transfer engine (1:1)

```text
Stage 5 of the plan: implement the transfer engine — §9 exact semantics, every
invariant INV-1..INV-8 (never hang, pause/cancel never blocks others, resume
from .part, no idle shutdown, one summary per batch, clickable breadcrumbs,
per-item selection), §20.1 single source of truth, §20.2 async into recycled
views, §20.3 events vs state, plus the unit tests named in §21.1 in the same
change as the logic.
Headless: no UI in this stage. Drive it from tests and a debug harness.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A2, A3, A6 and A8 belong to this stage.
```

### Stage 6 — LAN transport and discovery

```text
Stage 6 of the plan: implement LAN — §11.1 unified discovery (UDP :33457, 1200
ms beacon, one peer list with transport badges), §11.2 LAN transport (control
:33456, the exact frame layout and "MRSC" magic, byte-by-byte header parity),
§11.6 protocol versioning, §17.3–17.4 session tokens and recent devices.
The LAN framing rule in §11.2 is [KEPT] — reproduce it exactly, do not
restructure the header.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A1 and A6 belong to this stage.
```

### Stage 7 — Nearby Connections transport

```text
Stage 7 of the plan: implement the Nearby transport — §11.3 in full, behind the
same Transport interface as §11.2 so the engine cannot tell them apart, plus
the low-tier limits in §13. Nearby peers appear in the SAME discovery list with
a NEARBY badge (§11.1); a Play-Services-less device degrades to LAN only, with
the Doctor warning from §6.15, never a crash.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A1 belongs to this stage.
```

### Stage 8 — Onboarding, Connect, Discovery, Consent

```text
Stage 8 of the plan: implement the entry screens — §6.1 onboarding (four cards,
replayable), §6.2 Connect dashboard (radar, Send/Receive, broadcast entry,
WebShare card, recent devices), §6.3 Send · Discovery including multi-select,
§6.16 consent dialogs, §11.5 manual pairing — NO QR anywhere in code,
resources, routes or permissions — and §17.2 consent gates.
The broadcast button routes to discovery in multi-select (§6.8.1), never
straight to the sender.
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A5, A16 and A28 belong here.
```

### Stage 9 — Transfer screens, queue sheet, notifications

```text
Stage 9 of the plan: implement the transfer UI — §6.4 sending, §6.5 receive ·
listening, §6.6 receiving + sending back, §6.7 the queue sheet with per-item
pause/retry/cancel/send-now/remove, §6.18 notifications, §16.4 (log every
handshake step).
Bind them to the Stage 5 engine only through its state stream; no screen may
compute progress itself (§16.5).
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A2, A3, A8 and A24 belong here.
```

### Stage 10 — Files tab and photo viewer

```text
Stage 10 of the plan: implement §6.9 and §6.10 (Files tab + viewer).
§6.9: five tabs, day-grouped grids, per-item selection on every tab with group
select-all, the working sort sheet (Date/Name/Size/Type + direction), the
address bar, categories and folders that open real listings, selectable
folders sent as <folder>.zip, and a [Send] that enqueues, clears the selection
and navigates to the transfer screen.
§6.10: the image fills the viewer, navigation is a swipe pager with page dots
(no arrow buttons), and the Send FAB really sends.
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A7, A23, A24, A25, A29, A30,
A31, A33 and A34 belong here.
```

### Stage 11 — Media players

```text
Stage 11 of the plan: implement §6.11 (music + video players).
Both get the full scrubbing contract: tap-to-seek, a draggable knob, a 24 dp
hit area, labels that follow the thumb, the clock yielding during a drag,
seekTo() on release, working ±10 s that clamp, and keyboard/TalkBack stepping.
Volume is a continuous slider with mute-and-remember, never a bare toggle.
Music runs in a MediaSession foreground service and survives tab changes.
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A23 and A32 belong here.
```

### Stage 12 — History, Settings, Logs, Doctor, Help

```text
Stage 12 of the plan: implement §6.12 history, §6.13 settings (every switch
wired to real behaviour — a stored-but-unread preference is a defect), §6.14
the log viewer whose [Export .txt] raises the system share sheet, §6.15 the
Connection Doctor's six checks, §6.17 Help & FAQ, plus §16 diagnostics and
logging and §17 security and privacy.
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A15 and A26 belong here.
```

### Stage 13 — Broadcast (1 → N)

```text
Stage 13 of the plan: implement broadcast — §10 engine (10.1 model, 10.2
fan-out with one independent session per peer, 10.3 invariants, 10.4 the stat
formulas exactly, 10.5 receiver side, 10.6 honesty) and §6.8 screens (sender
with per-peer sub-rows, receivers, completion).
Entry is via multi-select discovery (§6.8.1). One rejecting or slow peer must
never stall the others (INV-B1).
Obey §4 tokens, §4.13 hard UI rules, §4.14 responsive rules, §15 accessibility.
Use Appendix A for every string. When you're done, list which of §22's criteria
this stage satisfies and how you verified each — A9 and A28 belong here.
```

### Stage 14 — WebShare server and site

```text
Stage 14 of the plan: implement WebShare — §7.1 server (:33455, never idles
out), §7.2 endpoints, §7.3 browser shell in dark AND light, §7.4 home, §7.5
category pages (folder panels, universal per-item selection with select-all,
plain Download beside streamed .zip, selectable folders, the photo lightbox,
the closable audio player), §7.10 media JSON, §17.3 per-session tokens.
Serve the assets from the APK; no CDN, no network dependency.
Obey §4 tokens (the site uses the same accent), §4.13 hard UI rules and §7.9
responsive rules. Use Appendix A for every string. When you're done, list which
of §22's criteria this stage satisfies and how you verified each — A4, A5, A12,
A20, A21, A22 and A27 belong here.
```

### Stage 15 — WebShare player, uploads, push, mobile browser

```text
Stage 15 of the plan: finish WebShare — §7.6 the in-browser video player
(Range/206 seeking, the full scrubbing contract, element removed on navigate),
§7.7 chunked uploads that pause and resume from the last received chunk, §7.8
push (phone → browser) over SSE plus ranged download, §7.9 responsive and
accessible down to a phone browser, and §11.4 WebPeerTransport so a browser
session appears as a peer.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A13, A14, A20, A31 and A32 belong here.
```

### Stage 16 — Battery, OEM, accessibility, performance

```text
Stage 16 of the plan: the reliability pass — §14 battery, power and OEM
(exemption request with explanation, autostart guidance, wake/wifi locks),
§15 accessibility in full (TalkBack, 48 dp targets, sp text at the largest font
scale, never colour alone), §13 device tiering on the reference MYA-L10, §20.5
reachability (no dead helpers), §20.6 honest capability, §20.10 performance as
correctness.
Re-run the §4.14 matrix: 320 / 360 / 412 / 480 / 600 / 840 dp, portrait and
landscape, largest font scale, split-screen.
When you're done, list which of §22's criteria this stage satisfies and how you
verified each — A10, A11, A17 and A35 belong here.
```

### Stage 17 — Test matrix, CI and release

```text
Stage 17 of the plan: ship it — §21.1 automated unit tests complete, §21.2 the
mandatory real-device matrix executed and reported, §19.3 CI assembling signed
release and debug APKs on push, §19.4 the size budget, §20.8 lint gate green.
Then run §22 in order, A1 through A35, and publish a table: criterion → pass /
fail → evidence. Finish with §23's definition of done.
Nothing ships while any criterion fails. If one cannot be tested in this
environment, say so explicitly rather than marking it pass.
```

---

## Between stages

```text
Self-review before I look at it:
1. Every requirement in the sections you just implemented that is NOT in the
   code yet, with section numbers.
2. Anything you implemented that the spec does not ask for.
3. The §22 criteria you claimed, re-run, with output.
Report deviations — do not silently amend the spec.
```
