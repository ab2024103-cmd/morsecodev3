# §21.2 — the mandatory real-device matrix

**Status: NOT EXECUTED. No physical device and no emulator exists in this
environment** (no JDK/Android SDK locally; CI is a headless Linux runner). §21.2
is explicit that "no change to the engine, control channel or session lifecycle
may be called 'working' on the strength of code review", so every case below is
**BLOCKED**, not passed.

This file is the run sheet. Each case names what to do, what to watch, and the
criterion it settles, so the person with two phones can work through it without
re-reading the specification.

## Setup

- Device A: the reference **Huawei MYA-L10** (Android 6, API 23) — A10's device.
- Device B: any current Android phone.
- Both on one Wi-Fi network for the LAN cases; Wi-Fi off on both for the Nearby
  cases; a laptop on the same network for WebShare.
- Install `morsecode-1.0.0-debug.apk` from the CI release on both.

## The sixteen cases

| # | Case | What must happen | Settles |
| --- | --- | --- | --- |
| 1 | Single small file (under one chunk), A → B | Arrives byte-identical; one summary; history row on both sides | A8 |
| 2 | Single large file (many chunks), A → B | Steady progress, correct speed, CRC verified | A1, A6 |
| 3 | Multi-file batch, A → B | Rows independent; one coalesced summary | A8 |
| 4 | Full turnaround: A→B completes, then B→A on the SAME session | Both directions in one session; §6.6's two-line summary | A8 |
| 5 | Receiver idle > 30 s mid-session | Session survives; no "connection closed" | INV-1, §11.2 |
| 6 | Cancel mid-transfer from the screen AND from the notification | Both cancel the same item; others continue | A2, §6.18 |
| 7 | Minimise during a send, navigate away, return | Uninterrupted; notification shows progress | §3.7, §14.5 |
| 8 | One deliberately unreadable file in a batch | That item FAILED, the rest complete | A2 |
| 9 | Pause mid-file, wait, resume — on LAN and on Nearby | LAN resumes at the offset; Nearby says "Pauses after the current file" | A2, §11.3 |
| 10 | Deliberate filename conflict — Overwrite / Skip / Keep both / Apply-to-all | Each behaves and produces byte-correct files | §9.5 |
| 11 | Folder send, as ZIP and as Files | Structure reconstructed exactly | A25 |
| 12 | Force-kill mid-batch, relaunch | "Resume interrupted transfer?" appears; Resume continues from the persisted offsets | §9.7 |
| 13 | BROADCAST to 3 phones: one rejects, one drops mid-file and reconnects, one completes | Batch finishes; the reconnecting peer resumes from its own offset; counts exact | A9 |
| 14 | Theme switch (light/dark/system) and accent change mid-transfer | No state loss, no flicker of the wrong colours | A11 |
| 15 | WebShare with a laptop AND a second phone browser, dark and light | Consent, browsing, download, zip, upload, lightbox, players | A4, A5, A12, A13, A14, A20, A21, A22, A27 |
| 16 | §4.14 sweep: 320 / 360 / 412 / 480 / 600 / 840 dp, both orientations, largest font scale, split-screen | No horizontal scrollbar, nothing clipped, five tabs span the width, grids re-span | A35 |

## Accessibility pass (A17)

Run case 1 end to end with TalkBack on: every control announced meaningfully,
nothing under 48 dp, progress announced at intervals rather than per tick, and
an explicit "Transfer complete".

## Network-isolation pass (A16, third clause)

With a proxy (or in airplane mode + hotspot), confirm no request leaves the
local subnet: no analytics, no update ping, no crash upload.

## Reporting

Record each case as PASS / FAIL with the device, the build and what was seen —
and per §22.1, anything shipped but unobserved stays BLOCKED.
