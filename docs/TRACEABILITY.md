# Morsecode — traceability log

One block per stage: file → sections implemented → criteria claimed →
PASS / BLOCKED / FAIL with evidence. Evidence classes follow §22.1: PASS means
executed with its command or output, BLOCKED means it cannot be executed in
this environment and says why, FAIL means it failed. "Shipped, not confirmed"
is BLOCKED, never PASS.

**Environment note (applies to every stage).** The agent sandbox has no JDK, no
Android SDK and no egress to `dl.google.com` or Maven Central; only
`github.com` is reachable. Per §19.5 local tooling is therefore a linter, not a
compiler: compilation, unit tests and APK assembly are executed by GitHub
Actions and read from that run's output. No device — physical or emulated — is
available here, so every criterion that requires running the app on hardware is
BLOCKED by definition (§22.1 anticipates exactly this).

---

## Stage 1 — Project skeleton and identity

Sections: §1 (identity, §1.1.1 verbatim), §3 (hard platform rules), §18
(version matrix, as constraints on the skeleton), §19 (build, versioning,
release, signing), §20.8 (lint gate), §20.9 (Kotlin constraints), Appendix B
(colour resources).

### Files

| File | Sections implemented |
|---|---|
| `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` | §3.1 single `:app` module, no DI framework, no annotation processors |
| `gradle/libs.versions.toml` | §3.3 third-party budget — AndroidX + Nearby Connections + NanoHTTPD (+ Kotlin/coroutines) and nothing else; no Material Components (§1.5, §4.13) |
| `gradle/wrapper/*`, `gradlew`, `gradlew.bat` | §19.3 reproducible CI build (Gradle 8.7) |
| `app/build.gradle.kts` | §1.1.1 applicationId `app.morsecode.android`; §19.1 1.0.0 (1); §3.2 minSdk 21 / targetSdk 35 / compileSdk 35, JDK 17, no core-library desugaring so §3.2's manual SDK guards stay mandatory; §19.3 signing-key rule; §19.4 R8 + resource shrinking for the size budget |
| `app/proguard-rules.pro` | §7.1 NanoHTTPD, §11.3 Nearby keep rules; §16.6 readable crash line numbers |
| `app/src/main/AndroidManifest.xml` | §3.5 permission set **exactly**, no additions, no CAMERA (§11.5/D13), no CHANGE_NETWORK_STATE; §3.6 ACTION_SEND / ACTION_SEND_MULTIPLE; §3.9 network security config; §12.5/§18 `<queries>`; §1.1.1 `morsecode://` scheme; §18 FileProvider instead of `file://` |
| `core/util/Ids.kt` | §1.1.1 every prescribed identifier, once: package, name, 1.0.0 (1), `MRSC`, `app.morsecode.android.nearby`, `morsecode`, `.morsecode.part`, `Download/Morsecode`, `morsecode.transfer` / `morsecode.webshare`, ports 33455/33456/33457, protocolVersion 1, log header |
| `core/logging/LogStore.kt` | §16.1–16.3, §16.6 ring buffer, persistent file, crash capture, `Morsecode <version> (<versionCode>)` export header; §3.2-safe (no NIO, no Java-8 collection defaults) |
| `di/AppServices.kt` | §3.1 manual service locator; §8.2 process-scoped singletons |
| `MorsecodeApp.kt` | §8.1 process init, uncaught-exception handler chained to the platform's, theme bootstrap; §3.4 no egress of any kind |
| `MainActivity.kt`, `layout/activity_main.xml` | §8.1 single activity; the shell itself is Stage 3 |
| `res/values/colors.xml`, `res/values-night/colors.xml` | §4.2, §4.3, §4.4, §4.5, §4.6, §4.9, §4.10 and Appendix B — every token, exact hex, and the only file allowed to contain hex |
| `res/values/attrs.xml`, `themes.xml`, `values-night/themes.xml` | §4.13 no screen hard-codes a colour; AppCompat theme ancestry (§4.13 widget-ancestry rule) |
| `res/values/dimens.xml` | §4.8 spacing/shape/elevation; §15.2 48 dp minimum; §4.14 adaptive cell width and max content width |
| `res/xml/network_security_config.xml` | §3.9 cleartext for local destinations only, with the honest note (§20.6) that the format cannot express a CIDR range and that raw sockets and the inbound WebShare server are not governed by it |
| `res/xml/file_paths.xml`, `backup_rules.xml`, `data_extraction_rules.xml` | §18 content:// only; §16.6/§17.1 logs and crash reports never leave the device automatically |
| `drawable/ic_launcher_foreground.xml`, `mipmap-anydpi-v26/*`, `mipmap-{m,h,xh,xxh,xxxh}dpi/*`, `tools/genicons.sh` | §4.10 the Morse glyph on the warm orange gradient, adaptive + legacy + round at every density; §19.2 no template icon in any build |
| `tools/lintgate.py` | §20.8 all seven rule classes + the A19 identity grep |
| `.github/workflows/build.yml` | §19.3 CI: gate, unit tests, debug + release APKs published as release assets; §19.3 signing-key rule enforced and reported |
| `app/src/test/java/.../IdsTest.kt` | §21.1 (identity half); asserts every §1.1.1 value literally |

### Criteria claimed

| Criterion | Result | Evidence |
|---|---|---|
| **A19** identity | **PASS** | `python3 tools/lintgate.py` → `PASS — no findings`; its check 8 greps the whole repo except `docs/` (per the amended A19) for the legacy name, and the needle is assembled at runtime so the gate is not itself a hit. `IdsTest` asserts `app.morsecode.android`, `Morsecode`, `1.0.0` / `1`, `MRSC`, `app.morsecode.android.nearby`, `morsecode`, `.morsecode.part`, `Download/Morsecode`, `morsecode.transfer`, `morsecode.webshare`, 33455/33456/33457 and the log header literally. The launcher label is `@string/app_name` = `Morsecode`. APK-strings verification is deferred to Stage 17 when a release APK exists. |
| **A16** permission list matches §3.5 exactly; no stranger-link feature | **PASS (static half)** | Manifest diffed line by line against §3.5: 24 `uses-permission` entries, the three `maxSdkVersion` caps, `neverForLocation` on BLUETOOTH_SCAN and NEARBY_WIFI_DEVICES, no CAMERA, no CHANGE_NETWORK_STATE, Wi-Fi/Bluetooth features `required="false"`. No relay, stranger-link or temporary-link code exists. |
| **A16** no network call leaves the local subnet | **BLOCKED** | Needs a proxy / airplane-mode test on a device. No device in this environment (§22.1). Statically: no HTTP client, no analytics SDK and no egress code exists in the tree. |
| **A15** launcher icon is the Morsecode logo | **PASS (icon half)** | `bash tools/genicons.sh` regenerates `mipmap-{mdpi…xxxhdpi}/ic_launcher{,_round}.png` (48/72/96/144/192 px) from the §4.10 geometry; the adaptive pair is `mipmap-anydpi-v26/*` over `@color/logo_gradient_mid` (#F5A712). Rendered output inspected visually: squircle, 135° #EF7D0F→#F5A712→#FBBF24 gradient, black glyph of three bars, six dots (2×3) and one long bar. Crash capture and `.txt` export exist (`LogStore`) but their UI is Stage 12, so the rest of A15 is not claimed yet. |
| **A18** lint gate green | **PASS (gate half)** | `python3 tools/lintgate.py` → `PASS — no findings` (36 files). Gate proven non-vacuous: a deliberate probe layout + Kotlin file produced 8 findings across all seven §20.8 rule classes (unresolved `@string`, unguarded `putIfAbsent`, hex in XML, hex in Kotlin, missing contentDescription, two sub-48 dp targets, literal `android:text`), exit code 1; probes removed and the gate returned to PASS. |
| **A18** unit tests green; signed release + debug APKs published | See CI section below | |
| **A11** no screen contains a hardcoded hex value | **PASS (so far)** | Same gate, rule 5: the only files permitted to hold hex are `values/colors.xml` and `values-night/colors.xml`. The full A11 claim (both themes render every screen, accent retints instantly) belongs to Stage 16. |

### CI evidence

Run [`36276610006`](https://github.com/ab2024103-cmd/morsecodev3/actions/runs/36276610006)
on commit `46f6df8`, branch `arena/01a0dfbc-morsecodev3`, job `108500468894`,
conclusion **success** in 2m51s. Every step succeeded, read back from that
specific run rather than inferred (§19.5):

```
success  Lint gate (§20.8)
success  Set up JDK 17
success  Install the upload key, when the secrets exist (§19.3)
success  Unit tests (§21.1)
success  Assemble debug and release (§19.3)
success  Upload build artifacts
success  Publish both APKs as release assets (§19.3)
```

Release assets, both downloadable from
`https://github.com/ab2024103-cmd/morsecodev3/releases/tag/ci-arena-01a0dfbc-morsecodev3`:

| Asset | Size |
|---|---|
| `morsecode-1.0.0-debug.apk` | 5 587 476 B (5.33 MiB) |
| `morsecode-1.0.0-preview-unsigned.apk` | 1 196 582 B (1.14 MiB) |

So: the project compiles on a real toolchain, `IdsTest` (7 tests) passes, and
both APKs exist as release assets. **A18 is still only partly satisfied**: the
release APK carries the DEBUG key because no `KEYSTORE_B64` / `KEY_ALIAS` /
`KEY_PASSWORD` / `STORE_PASSWORD` secrets exist on this repository, so it is
published as `-preview-unsigned` exactly as §19.3's amended signing-key rule
requires. Generating a throwaway key per build is forbidden, so this stays
**BLOCKED** until the secrets are added — one action is needed from the repo
owner, not from the build.

Sizes are well inside §19.4 (release ≈ 6.5 MB, debug ≈ 8 MB) at skeleton stage;
they are re-judged in Stage 17 with the whole product present.

### Not implemented yet, deliberately (and where it lands)

- Theme bootstrap currently sets `MODE_NIGHT_FOLLOW_SYSTEM`. §4.1's full rule
  (stored Light/Dark/Follow-system choice; Dark when the system has no
  preference) needs `Prefs`, which is Stage 2.
- Only `app_name` exists in `strings.xml`; Appendix A is added screen by screen
  from Stage 2 on (§3.8).
- Accent theme overlays for the other four §4.4 swatches: Stage 2. The five
  swatch colours themselves are already in the token file.
- §6.18 notification channels are named in `Ids` but not created: Stage 3.
- `MainActivity` does not yet consume ACTION_SEND / ACTION_SEND_MULTIPLE; the
  intent filters are declared, the intake is Stage 3 when a send flow exists.
- §19.4 size budget is unmeasurable until the first release APK exists;
  verified in CI from Stage 1 onward and judged in Stage 17.

### Deviations from the specification

1. **Two extra notification channel ids.** §6.18 names four channels; §1.1.1
   prescribes ids for two. `Ids.CHANNEL_REQUESTS` / `Ids.CHANNEL_PLAYBACK`
   follow the same prescribed shape. Nothing prescribed was changed.
2. **`network_security_config.xml` cannot express "private ranges".** The
   platform format matches hosts and literal IPs, not CIDR. Stated in the file
   and above rather than papered over (§20.6).
3. **No Material Components dependency.** §4.13 discusses MDC-2 vs M3 theme
   ancestry, but §3.3's allowed set does not include Material and §1.5 asks for
   custom lightweight widgets. AppCompat ancestry is used, which satisfies
   §4.13's actual requirement (know what you inherit from) with no third-party
   growth.

---

## Stage 2 — the design system (§4)

Scope built: §4.1–4.9 tokens, §4.11 motion, §4.12 shared components, §4.13 hard
rules, §4.14 responsive rules, and the Appendix A strings those components need.

### Files

| File | Sections implemented | Notes |
| --- | --- | --- |
| `res/values/type.xml` | §4.7 | Eleven text appearances (screen title, section header, item title, item meta, body, button, state chip, stat value, stat label, tab label, mono) plus `Widget.Morsecode.FileName` for the one-line middle-ellipsised name. No text style is declared anywhere else. |
| `res/values/dimens.xml`, `values-sw480dp/`, `values-sw600dp/`, `values-sw840dp/` | §4.8, §4.14 | 8 dp grid, radii (card 16, sheet/dialog 20, chip 8, thumb 10, button 14), 1 dp hairline, 48 dp touch target, and the grid-column/gutter overrides per width bucket. |
| `res/values/accents.xml` | §4.4, §4.5 | The five accents as five complete themes (`Theme.Morsecode.{Sunflower,Leaf,Ember,Violet,Sky}`). One `setTheme` before `super.onCreate` retints everything because every component resolves `?attr/accent*`. |
| `res/values/attrs.xml`, `values/themes.xml`, `values-night/themes.xml` | §4.1, §4.2, §4.3, §4.13 | Semantic attributes only; `isLightSurface` lets a view pick the light or dark rule (accent wash 8 %/35 % dark, 14 %/45 % light) without reading a colour. Adds `Theme.Morsecode.Dialog` (floating, dim 0.62) and `Animation.Morsecode.BottomSheet`. |
| `res/anim/sheet_enter.xml`, `sheet_exit.xml` | §4.11 | 220 ms in, 180 ms out, decelerate/accelerate. |
| `res/drawable/` (27 icons) | §4.9 | One outlined 24 dp family at 2 dp stroke, tinted by attribute; the six filled type tiles are drawn by `TypeTileView` from the §4.3 type colours. |
| `core/util/Accent.kt` | §4.4 | The swatch list, its order, the default and the key↔theme mapping — one place. |
| `core/util/ThemeColors.kt` | §4.13 | Every runtime colour read goes through `?attr` resolution here; no view hardcodes a hex. |
| `core/util/DeviceTier.kt` | §4.11, §13.1 | Tier from RAM/cores; `scaled()` folds in `ANIMATOR_DURATION_SCALE`, so the radar degrades to a static ring and every duration collapses to 0 when animations are off. |
| `core/util/Fmt.kt` | §4.10, §6.4, §6.9 | Sizes, the shared-unit progress pair, speeds, clock durations, spoken durations, clamped percents. |
| `core/util/PeerPalette.kt` | §4.6 | `abs(deviceId.hashCode()) % 6` over the prescribed six colours; the tension with the deck's avatars is recorded below. |
| `core/data/Prefs.kt` | §4.1, §8.2 | Theme mode and accent as flows over `SharedPreferences`; the single switch both themes and all five accents hang from. |
| `core/ui/Themes.kt` | §4.1, §4.4 | `applyNightMode` at process start, `applyAccent` before `super.onCreate`. |
| `core/ui/Shapes.kt` | §4.8, §4.12 | Every background in the product is built here (card, raised, chip, pill, circle, accent wash, accent button, outlined button, pressable ripple). |
| `core/ui/PeerCardView.kt` | §4.12a, §6.2 | Avatar, name, meta, LAN/NEARBY/WEB/FROM transport badge, trailing state chip. Built once; the dashboard, broadcast and transfer screens reuse it. |
| `core/ui/TransferRowView.kt` | §4.12b | One row, three variants (sender, receiver, per-peer sub-row) so a queue item looks the same wherever it appears. |
| `core/ui/SummaryCardView.kt` | §4.12c, §6.6 | Title plus N detail lines and up to three stat tiles — the In:/Out: gap card and the broadcast totals use the same view. |
| `core/ui/StatTileView.kt`, `SectionHeaderView.kt`, `StateChipView.kt`, `TypeTileView.kt`, `AvatarView.kt`, `ThinProgressBar.kt`, `RadarView.kt` | §4.12d–g, §4.11 | The remaining shared pieces. Progress lerps over 150 ms, chips crossfade over 120 ms, the radar sweeps in 2.4 s with 1.6 s blips. |
| `core/ui/ActionBarView.kt` | §4.12, §5.3 | Choose / Queue / Pause / Minimize / End with labels always visible (§4.13: no icon-only actions). |
| `core/ui/BottomNavView.kt` | §5.1, §5.2 | The four tabs; the screens that keep it are wired in Stage 3. |
| `core/ui/EmptyStateView.kt`, `TipView.kt` | §4.12, §6.x | Empty states carry an action; tips are dismissible and remember it in `Prefs`. |
| `core/ui/ConsentDialog.kt` | §4.12, §11.4, §15.2 | Peer and browser variants, not cancellable — consent is a decision, not a dismissal. |
| `core/ui/BottomSheet.kt`, `Ui.kt` | §4.12, §4.13 | Hand-built sheet (drag handle, 20 dp top corners, 220 ms spring) and snackbar, because there is no Material dependency. |
| `app/src/debug/.../ComponentGalleryActivity.kt` (+ debug manifest and strings) | build-plan Stage 2 | Debug-only launcher activity rendering every component, both consent dialogs, in dark and light, across all five accents, at 320 / 360 / 600 dp. Not present in release. |
| `app/src/test/.../FmtTest.kt`, `PeerPaletteTest.kt`, `AccentTest.kt` | §21.1 | Lock the meta-line copy, the avatar palette and the swatch list. |
| `tools/testtally.py`, `.github/workflows/build.yml` | §22.1 | CI now carries the unit-test tally into the run summary and the release notes, because artifact storage is not reachable from the development sandbox. |

### Criteria

| Criterion | Verdict | Evidence |
| --- | --- | --- |
| A7 (one accent switch retints the product; both themes complete) | **PASS, static** | Five accent themes exist, every component resolves `?attr/accent*` via `ThemeColors`, and `Themes.applyAccent` is applied before `super.onCreate`. The gate proves no view holds a literal colour. Visual confirmation on a device is Stage 17. |
| A16 (no text style, colour or dimension declared outside the token files) | **PASS** | `python3 tools/lintgate.py` → `PASS — no findings`, 129 files scanned, in the sandbox and as the first CI step. |
| A11 (no hardcoded hex outside the token files) | **PASS** | Same gate run; the hex rule is one of its eight families and was proved non-vacuous in Stage 1. |
| §4.13 no icon-only primary actions | **PASS** | `ActionBarView` renders a label for every action; there is no label-less path through it. |
| §4.14 responsive at 320 / 360 / 600 dp | **BLOCKED** | The width buckets and the gallery exist, but nothing has been rendered on a screen. Confirmed in Stage 17 against §21.2. |
| §4.11 motion respects reduced animation and the low tier | **PASS, static** | `DeviceTier.scaled()` reads `ANIMATOR_DURATION_SCALE` and returns 0, and `RadarView` falls back to a static ring with "Searching…". Measured behaviour is Stage 17. |

### CI evidence

Run **36295738614** (job 108554117962, 3 m 54 s, 16/16 steps green) built the
stage; run **36296022749** re-ran it with the tally step and is the one to read.
Its release notes on `ci-arena-01a0dfbc-morsecodev3` record:

```
- unit tests: 18 tests, 0 failed, 0 skipped - AccentTest (2), FmtTest (6), IdsTest (7), PeerPaletteTest (3)
- lint gate (§20.8): passed
- signed with the stable upload key: false
morsecode-1.0.0-debug.apk             5 692 075 B
morsecode-1.0.0-preview-unsigned.apk  1 214 355 B
```

The release variant is still built with the debug key, so §19.3 and A18 remain
NOT satisfied until the four repo secrets exist. The 1.2 MB release APK is well
inside the §19.4 budget, with no screens in it yet. That the whole design system compiles against
the real SDK is the strongest verification available here; the sandbox has no
JDK or Android SDK and cannot reach any artifact host.

### Not yet done in this stage

- No screen consumes these components yet — that is Stage 3's navigation shell.
- Appendix A is present only for the strings these components need; the rest
  arrives with the screens that speak them.
- The lint gate scans `app/src/main/res` only. The debug gallery's resources are
  deliberately outside its scope: they are scaffolding, not product surface.
- Robolectric is a declared dependency with no tests yet; the first ones arrive
  with the transfer engine.

### Deviations and resolved tensions

4. **§4.6 avatar colours.** The prescribed formula `abs(hashCode()) % 6` applied
   to the four mock display names yields green, pink, pink, sky — not the amber,
   violet, sky, green the deck shows. The formula is keyed on the *device id*,
   so it is reproduced verbatim and the fixture ids are calibrated
   (`MYA-L10:1`, `Ravi's Redmi:2`, `Pixel 7X:3`, `Samsung A14:3`) to land on the
   prescribed colours. Both halves are asserted in `PeerPaletteTest`, and the
   reasoning is in the header of `PeerPalette.kt`.
5. **"Deep" accent shades are the pressed state.** §4.4 gives Sunflower a deep
   shade explicitly and the other four by implication; they are used as the
   pressed colour in `Shapes.pressable`, which is the only role the deck shows
   them in.
6. **Bottom sheet and snackbar are hand-built.** Consequence of deviation 3 (no
   Material dependency): §4.12's sheet behaviour is implemented on
   `AppCompatDialogFragment` with the prescribed 20 dp top corners and 220 ms
   spring rather than inherited.

---

## Stage 3 — the navigation shell (§5)

Scope built: §5.1 the four tabs, §5.2 every route on the navigation map,
§5.3 Back / Minimize / End, §5.4 and §20.4 explicit purpose, §6.18 the four
notification channels (created, not yet used), §6.19 an empty state on every
list. Every destination renders its real chrome; none invents content.

### Files

| File | Sections implemented | Notes |
| --- | --- | --- |
| `MainActivity.kt` | §5.1, §5.2, §5.3, §8.1 | One activity, one back stack, `Navigator` implementation. Pushing records the launching tab so it stays lit; switching tab clears the pushed stack; back pops, then falls back to Connect, then finishes — which is what covers the post-crash relaunch state (§5.3). |
| `res/layout/activity_main.xml` | §5.1 | Fragment host above a hairline above the four-tab nav. |
| `core/ui/Nav.kt` | §5.4, §20.4 | `Purpose` (SENDER / RECEIVER / BROADCAST / RESUME), the argument keys, and `purposeOf` which **throws** rather than defaulting. A default would be an inference from incidental state. |
| `core/ui/Screen.kt` | §5.2, §4.14, §6.19 | Base destination: toolbar, screen padding, the 640 dp content cap, the two shell facts (`navTab`, `hidesBottomNav`), and the shared `emptyState` helper so no list can ship blank. |
| `core/ui/ScreenToolbar.kt` | §6.2, §6.9, §6.12, §15.1 | The one toolbar: optional back circle, title, trailing icon actions with descriptions and 48 dp targets. |
| `core/ui/TabStripView.kt` | §6.9 | Five equal flex cells filling the width, 2 dp accent underline spanning its own cell, labels ellipsise before the strip does. Owns the tab index — there is deliberately no second copy. |
| `core/ui/SegmentedControl.kt` | §6.12, §4.8 | The full-width [Received \| Sent] pill; the only place the full pill radius is used. |
| `core/ui/SettingRowView.kt` | §6.13 | Label + subtitle + chevron **or** switch. No display-only mode: a switch cannot be constructed without a listener, because §6.13 calls a stored-but-unread preference a defect. |
| `core/ui/Buttons.kt` | §4.4, §4.8, §6.2 | Accent, outlined and accent-wash buttons; all take text, so §4.13's no-icon-only rule holds by construction. |
| `core/util/Notifications.kt` | §6.18 | The four channels — Transfers (low), Requests (high), WebShare (low), Playback (low) — created at process start from the `Ids` constants. Nothing posts to them yet. |
| `feature/dashboard/DashboardFragment.kt` | §6.2 | "Connect" + ?, radar, [↑ Send] · [↓ Receive], the full-width washed "⇶ Broadcast to several phones" PRIMARY entry, the WebShare card with its OFF pill, "RECENT DEVICES" + Clear. Send, the Send long-press and Broadcast all land on Discovery (A28's route). |
| `feature/dashboard/DiscoveryFragment.kt` | §6.3, §11.5 | Radar, mono TRANSPORT / QUEUE rows, "DISCOVERED" + Refresh, the 15-second empty state with [Open Connection Doctor] and [Try Nearby instead], and exactly one bottom control — [Manual IP], or [Broadcast to (n)] in multi-select. No QR anywhere (G12). |
| `feature/transfer/TransferFragment.kt` | §6.4, §6.5, §5.2, §5.3 | Purpose-driven chrome, keeps the nav, Back = Minimize, End = confirm dialog with §5.3's exact copy. |
| `feature/transfer/BroadcastFragment.kt` | §6.8.2, §5.2, §5.3 | Hub chrome with the totals card in place from the start and the same Minimize/keep-nav rules. |
| `feature/filemanager/FilesFragment.kt` | §6.9, §6.19 | Toolbar + search · SORT · view toggle, exactly five tabs, the one-line tap hint, and a per-tab empty state rendered from the strip's index. |
| `feature/history/HistoryFragment.kt` | §6.12 | Title + search · filter · clear-all, the direction pill, one render path for both directions. |
| `feature/settings/SettingsFragment.kt` | §6.13, §4.4 | Profile card, the five-circle accent picker (live), Dark mode and Follow system (live, and Follow system disables/mirrors Dark mode per G3), then the remaining rows. |
| `feature/settings/LogViewerFragment.kt`, `ConnectionDoctorFragment.kt` | §6.14, §6.15 | Both diagnostics routes with their chip row and footer buttons. |
| `feature/help/HelpFragment.kt` | §6.17 | Help route; its "→ Connection Doctor" action is already real. |
| `feature/webshare/WebShareFragment.kt` | §6.16b, §7.1 | Address, Start/Stop, SESSIONS with an empty state. No QR block. |
| `feature/onboarding/OnboardingFragment.kt` | §6.1 | Route plus the replay entry point and the "seen" flag. |
| `feature/viewer/MusicPlayerFragment.kt` | §5.2, §6.11 | Now-Playing **keeps** the nav — the documented non-exception. |
| `feature/viewer/ImmersiveActivity.kt`, `ViewerActivity.kt`, `VideoPlayerActivity.kt` | §5.2 [CHANGED], §6.10, §8.1 | The two immersive surfaces as separate activities, so the absent bottom nav is structural rather than a flag a screen could forget. Backdrop and chrome are new theme attributes, so §4.13 still holds inside the viewer. |
| `res/drawable/` (16 new icons) | §4.9 | back, close, search, sort, view-grid, overflow, trash, filter, refresh, folder, plus, logs, doctor, copy, wifi, arrow-up — same 24 dp / 2 dp outlined family. |
| `res/values/strings.xml` | §3.8, Appendix A | +118 strings for these screens, all escaped and unique. |
| `app/src/test/.../ShellTest.kt` | §21.1, §5.1–5.4 | Seven Robolectric tests over the real activity. |

### Criteria

| Criterion | Verdict | Evidence |
| --- | --- | --- |
| §5.1 every tab opens without crashing (release blocker) | **PASS** | `ShellTest.everyTabOpensWithoutCrashing` drives the real `MainActivity` through all four tabs and asserts the expected fragment each time. Run 36296975670: 25 tests, 0 failed. |
| §5.2 Transfer keeps the nav, launching tab stays lit | **PASS** | `ShellTest.transferKeepsTheBottomNavAndTheLaunchingTabStaysLit` pushes Transfer from the Files tab and asserts the nav is `VISIBLE` and Files is still the selected tab. |
| §5.2 viewer and player hide the nav | **PASS, structural** | They are separate activities with no nav in their view tree; there is no code path that could show one. Visual confirmation is Stage 10/11. |
| §5.3 Back falls back to popping the stack | **PASS** | `ShellTest.backPopsThePushedDestination` and `switchingTabsClearsThePushedStack`. |
| §5.3 Back on a transfer = Minimize, End confirms | **PASS, static** | `TransferFragment` registers an `OnBackPressedCallback` that minimizes, and End raises the §5.3 dialog with its prescribed copy. "Session survives in the service" is unverifiable until the service exists (Stage 5). |
| §5.4 / §20.4 explicit purpose | **PASS** | `purposeIsExplicitAndBroadcastDiscoveryIsMultiSelect` and `aDestinationWithoutAPurposeFailsLoudly` (expects `IllegalStateException`). |
| §6.18 four channels created | **PASS, static** | `Notifications.createChannels` runs in `MorsecodeApp.onCreate` with the four `Ids` constants. Observing them in system settings needs a device (Stage 17). |
| §6.19 every list has an empty state | **PASS** | Every list surface in this stage routes through `Screen.emptyState`, which requires an icon and a line; the dashboard, discovery, all five Files tabs, both History directions, WebShare sessions, logs and help each have one. |
| A16 (no text style, colour or dimension outside the token files) | **PASS** | `python3 tools/lintgate.py` → `PASS — no findings`, 185 files, locally and as the first CI step. |
| A11 (no hardcoded hex) | **PASS** | Same gate. The viewer's backdrop and chrome were added as theme attributes rather than literals for exactly this reason. |
| A28 (Broadcast lands on Discovery in multi-select) | **PASS, route half** | Dashboard's Broadcast button and the Send long-press both open `DiscoveryFragment.newInstance(multiSelect = true)`, whose purpose is BROADCAST — asserted in `ShellTest`. The "picking two opens the sender at 0 %, picking one is refused" half is Stage 13. |
| A35 (responsive 320–840 dp) | **BLOCKED** | The width buckets, the 640 dp content cap and the equal-flex tab strip exist, but nothing has been rendered or resized on a screen. Stage 17. |
| A17 (accessibility) | **BLOCKED** | Every control added here carries a description and a 48 dp target, but TalkBack cannot be exercised in CI. Stage 16/17. |

### CI evidence

Run **36296975670**, all 17 steps green. Release notes on
`ci-arena-01a0dfbc-morsecodev3`:

```
- unit tests: 25 tests, 0 failed, 0 skipped - AccentTest (2), FmtTest (6), IdsTest (7), PeerPaletteTest (3), ShellTest (7)
- lint gate (§20.8): passed
- signed with the stable upload key: false
morsecode-1.0.0-debug.apk             5.6M
morsecode-1.0.0-preview-unsigned.apk  1.3M
```

Robolectric is now in use: `ShellTest` runs the real `MainActivity`, so "the
tabs open" is an executed result, not an assertion about source code.

### Not yet done in this stage

- `ACTION_SEND` / `ACTION_SEND_MULTIPLE` intake. The filters are declared and
  Stage 1's note said Stage 3; it moves to **Stage 5**, because handing a
  shared batch to a queue that does not exist would be theatre. Recorded here
  rather than quietly dropped.
- Per-tab back stacks. Switching tab clears the pushed stack — the simplest
  behaviour that satisfies §5.1 and §5.2; nothing in the specification asks
  for four parallel histories.
- The Settings switches whose behaviour lands later (Sounds, Notifications,
  Crash reports, Conflict policy, Broadcast peers, Storage access, Battery)
  are rows, not toggles. §6.13 calls a stored-but-unread preference a defect,
  so they become switches in the stage that reads them.
- Onboarding does not yet gate first launch; the flag exists, the four slides
  are Stage 8.

### Deviations and resolved tensions

7. **No `TextAppearance.Morsecode.TabLabel`.** §4.7 lists exactly ten text
   appearances and a tab label is not among them, so the tab strip uses the
   prescribed button label (14 sp medium, sentence case) rather than an
   eleventh invented style.
8. **Three new theme attributes for the immersive surfaces**
   (`colorViewerBackdrop`, `colorViewerChrome`, `colorViewerChromeScrim`).
   §6.10 prescribes black in dark and `#141310` in light; §4.13 forbids a
   screen holding a hex. Attributes are the only way to satisfy both, and the
   values are the prescribed ones.

---

## Stage 4 — storage, permissions and the media library (§12, §3.5, §13)

Scope built: §12.1 access method by content type, §12.2 graceful denial,
§12.3 all-files access, §12.4 destinations, §12.5 MediaLibrary with the
MediaStore date and pagination rules, §3.5 permission grouping, §13 tiering
applied to page size / decode size / cache budget, §20.10 counts that return
in milliseconds rather than minutes.

Every category — PHOTOS · VIDEOS · MUSIC · DOCUMENTS · APPS · FILES (+ Ebooks,
Archives, APKs, Large files) — is exposed behind one repository. No screen
queries MediaStore.

### Files

| File | Sections implemented | Notes |
| --- | --- | --- |
| `core/model/Models.kt` | §8.1, §12.2, §12.5 | The shared vocabulary: `MediaItem` (whose `dateMillis` is *always* milliseconds), `FolderInfo`, `CategoryCount` with an explicit PENDING so §6.9's shimmer never shows a hard "0", `MediaPage`, `DirectoryEntry`, and `DirectoryListing` as a sealed type. |
| `core/media/MediaLibrary.kt` | §12.5, §12.2, §20.10 | Counts, paging, folder panels, installed apps, directory listings. Every query goes through one `query()` that **asserts** INV-10 before touching the resolver, and `SecurityException` is a condition, not a crash. |
| `core/media/MediaQueries.kt` | §12.5 INV-9, INV-10 | The ordering expression verbatim from the spec, the LIMIT/OFFSET detector, and the page-bounds maths. Isolated precisely so both invariants are testable without a device. |
| `core/media/MediaDates.kt` | §12.5 DATE UNITS | `secondsToMillis` / `millisToSeconds` / `effectiveMillis` / day-start keys. Conversion happens once, at the cursor. |
| `core/media/FileTypes.kt` | §4.9, §6.9 | MIME first, extension second; one classifier drives both the tile colour and the CATEGORIES row, so they cannot disagree. Large files is a size rule checked first. |
| `core/media/ThumbnailCache.kt` | §6.9.2, §13 | Tier-scaled LRU keyed on `id:mtime:size`, `loadThumbnail` on 29+ and a measured `inSampleSize` decode below it, album art via `MediaMetadataRetriever`. A failed decode is logged, because a list of blanks means the loader is unwired. |
| `core/storage/SafStore.kt` | §12.1 | `ACTION_OPEN_DOCUMENT_TREE`, `takePersistableUriPermission` at the moment of grant, and the grant list read back from `persistedUriPermissions` — a private copy would drift the moment the user revoked one in system settings. |
| `core/storage/Destinations.kt` | §12.4, §12.5 | The single write target. Default `Download/Morsecode`, created on first receive; one accessor for the label, the "Open folder" intent, the MediaStore collection, the relative path and the `.morsecode.part` name. |
| `core/util/Permissions.kt` | §3.5, §12.3, §14.1 | Request groups by task and API level. MANAGE_EXTERNAL_STORAGE is structurally absent from every group, battery exemption is not here at all, and CAMERA appears nowhere. |
| `di/AppServices.kt` | §3.1, §8.2 | `mediaLibrary`, `thumbnails`, `safStore`, `destinations` — process-scoped, never screen-scoped. |
| `app/src/test/.../{MediaDatesTest,MediaQueriesTest,FileTypesTest,ManifestPermissionsTest,StorageTest}.kt` | §21.1 | 26 new tests. |

### Criteria

| Criterion | Verdict | Evidence |
| --- | --- | --- |
| **A7** (day groups once and in order; Android 14 paginates with no "Invalid token LIMIT"; received files carry correct DATE_TAKEN units) | **BLOCKED** | The criterion is stated as an observation on a device and there is none here, so it cannot be PASS. All three of its mechanisms are executed, though: `MediaQueriesTest` asserts the INV-9 expression with its `_ID DESC` tiebreak and that **no** sort string for any key or direction contains LIMIT/OFFSET (plus a non-vacuity check that the detector really fires); `walkingEveryPageVisitsEveryRowExactlyOnce` proves pages tile the cursor with no gap or overlap, which is the property day headers depend on; `StorageTest.insertValuesCarryDateTakenInMillisecondsAndDateModifiedInSeconds` pins the units on the write side. Run 36298129160. |
| **A16** (permission list matches §3.5 exactly; no stranger-link feature; no call leaves the local subnet) | **BLOCKED** | Clause 1 is **PASS, executed**: `ManifestPermissionsTest` asserts set equality in both directions against the §3.5 list, plus the absence of CAMERA and CHANGE_NETWORK_STATE, the three `maxSdkVersion` ceilings, `neverForLocation`, and that no hardware feature is required. Clause 2 is PASS via A19's gate. Clause 3 needs the proxy / airplane-mode test and is the reason the criterion as a whole is BLOCKED (§21.2, Stage 17). |
| §12.1 access method by content type | **PASS, static** | Media goes through MediaStore collections (`Destinations.collectionFor`); general files and trees go through SAF + DocumentFile; nothing assumes a raw path is writable on 29+. Other apps' `/Android/data` is never claimed. |
| §12.2 graceful denial | **PASS** | `DirectoryListing.AccessDenied` is a distinct type, returned both when `canRead()` is false and when `listFiles()` returns null — the exact case that used to render as an empty folder. `StorageTest.anUnreadableFolderIsNotAnEmptyFolder` asserts Ok / Missing / Ok-but-empty are three different answers. |
| §12.3 all-files access never at first run | **PASS, structural** | MANAGE_EXTERNAL_STORAGE is in no request group; the only entry point is `allFilesAccessIntent`, documented as Settings-only and behind a rationale sheet. No onboarding code can reach it. |
| §12.4 one destination, never two | **PASS** | `StorageTest.choosingATreeMovesEverythingToIt`: the label and the "Open folder" target both follow the single stored value. |
| §12.5 one repository | **PASS** | `grep -rn "MediaStore\|contentResolver" app/src/main/java/app/morsecode/android/feature --include=*.kt` → **0 matches**. Outside `core/media/` and `core/storage/` the only occurrences of the word anywhere are five comments (DeviceTier, Permissions, Models, AppServices). |
| §13 tiering scales work, never capability | **PASS** | `StorageTest.tieringScalesWorkAndNeverCapability`; every tier value is a size or a count, and none is a feature flag. |
| §20.10 counts are indexed | **PASS, static** | Counts are `_ID` projections over indexed MediaStore queries; the Files-tab categories stop one row past the page rather than walking the volume. Measured timings need a device (Stage 17). |
| A18 (lint gate green, unit tests green) | **PASS** | Gate `PASS — no findings` over 194 files; 51 tests, 0 failed. |

### CI evidence

Run **36298129160**, all 17 steps green.

```
- unit tests: 51 tests, 0 failed, 0 skipped - AccentTest (2), FileTypesTest (5), FmtTest (6),
  IdsTest (7), ManifestPermissionsTest (4), MediaDatesTest (5), MediaQueriesTest (5),
  PeerPaletteTest (3), ShellTest (7), StorageTest (7)
- lint gate (§20.8): passed
morsecode-1.0.0-debug.apk             5.6M
morsecode-1.0.0-preview-unsigned.apk  1.3M
```

### Not yet done in this stage

- No screen consumes the library yet. The Files tab still shows its Stage 3
  empty states; wiring the grids, the selection basket, the address bar and
  the sort sheet is Stage 10, and the permission prompts are requested
  contextually there and in Stage 8.
- Received-file insertion is prepared (`mediaValues`, `collectionFor`,
  `relativePathFor`, `partNameFor`) but nothing writes yet — there is no
  receive path until Stage 5.
- `folders()` reads every row of a media table to group buckets. That is
  acceptable for the panel counts §7.5 needs and is bounded by the media
  tables, not the filesystem, but if it ever shows on the reference phone it
  becomes a `GROUP BY` query. Noted rather than assumed away.
- Trash, rename, move, copy, compress and properties (§6.9 long-press
  toolbar) belong to the Files tab stage, not to the repository.

### Deviations and resolved tensions

9. **`DirectoryListing` is a sealed type rather than a nullable list.** §12.2
   only requires the two conditions to be distinguishable; making it a type
   means a screen *cannot* render a denied folder as empty even by accident,
   which is what the rule is actually protecting.
10. **The SAF hint for a denied path is the primary-storage tree root.** §12.2
    asks for a button "scoped to that location", but the platform accepts only
    a document tree URI and offers no documented path→tree mapping. The
    closest legitimate starting point is used and the limitation is stated
    here rather than papered over (§20.6).

---

## Stage 5 — the 1:1 transfer engine (§9)

Headless, as the stage requires: no screen consumes any of this yet. It is
driven by `TransferEngineTest` through a fake transport and, on a device, by a
debug-only harness activity.

### Files

| File | Sections implemented | Notes |
| --- | --- | --- |
| `core/model/Models.kt` (extended) | §9.1, §9.3, §9.6, §20.3 | `TransferItem` field for field from §9.1, the seven states with `isTerminal`, `SendResult`, `BatchSummary`, `EngineEvent`, and `SessionState` as three cases so "never connected" is distinguishable from "was connected, now closed". |
| `core/transfer/TransferQueue.kt` | §20.1, §9.2, §9.6 | The one source for the queue view, the notification and the batch summary. Owns `nextQueued`, the INV-3 pause/re-queue pair, `retryFailed`, and `summaryOf` — which reads the same items the rows rendered rather than recomputing. |
| `core/transfer/TransferEngine.kt` | §9.2–9.7, §8.2, §3.6 | One worker per session; the bounded await; §9.3's retry table; §9.6's coalesced summary; §9.7's report/restore/discard triple; the held ACTION_SEND batch. Timings are injected, so the tests drive the shipping code paths rather than a test-only branch. |
| `core/transfer/TransferService.kt` | §3.7, §6.18 | The `dataSync` foreground service that owns sessions so minimising or rotating never interrupts a transfer, with §6.18's plain-words explanation as its text. |
| `core/network/Transport.kt` | §8.2, §11.4 | `TransportSession` (one connected peer) and `Transport`. INV-1(a) is written into the contract: `close` must complete every pending waiter. |
| `core/network/Framing.kt` | §11.2, §9.4 | The MRSC codec and `readHeaderLine` — the byte-by-byte header read A6 names. A CRC mismatch throws rather than being "repaired". |
| `core/util/Integrity.kt` | §9.4 | Pre-hash limit, SHA-256, the size/hash verification rule, and the resume-offset and resume-sequence maths. |
| `core/storage/Conflicts.kt` | §9.5 | All four policies, "already present", "keep both" naming, and a per-batch "apply to all" that cannot leak into the next batch. |
| `core/storage/Destinations.kt` (extended) | §9.4 | `purgeOrphanParts`: orphan `.morsecode.part` files older than 24 h with no journal entry go; a journaled part is resumable work and stays. |
| `core/data/JournalStore.kt` | §8.2, §9.7 | Written on every transition, replaced atomically, and restores IN_PROGRESS/QUEUED as PAUSED so nothing can come back mid-flight. |
| `core/data/HistoryStore.kt` | §6.12, §9.6 | One `record()`, called by the engine's single completion path for both directions. |
| `MainActivity.kt` | §3.6 | ACTION_SEND / ACTION_SEND_MULTIPLE describe their URIs through the media library, hold the batch on the engine and open Discovery. |
| `app/src/debug/.../TransferHarnessActivity.kt` | stage requirement | Loopback transport at 2 MB/s in 256 KB steps that deliberately does **not** acknowledge pause or cancel, so the 3-second fallback is what a human exercises. Debug variant only. |
| `app/src/test/.../{FakeSession,TransferEngineTest,TransferQueueTest,FramingTest,ConflictsTest,IntegrityTest}.kt` | §21.1 | 43 new tests. |
| `.github/workflows/build.yml`, `tools/failreport.py` | §22.1 | On failure CI now writes the Kotlin errors and failing test cases into the release notes. |

### Criteria

| Criterion | Verdict | Evidence |
| --- | --- | --- |
| **A6** (never fails with "chunk magic mismatch" — byte-by-byte header read present and covered by a unit test) | **PASS for the check the criterion names** | `FramingTest.theHeaderIsReadByteByByteAndLeavesTheFirstFrameIntact` reads a JSON header then the first MRSC frame off the same stream, and `aBufferedReaderWouldHaveSwallowedTheFirstFrame` *reproduces the production defect* — after a `BufferedReader.readLine()` the stream is empty and the frame is gone. Also covered: round-trip of 0/1/256 KB/odd-sized frames, CRC detection of a single flipped byte, magic mismatch on a one-byte slip, and the oversize-length guard. **No LAN bytes have moved yet** — the socket path is Stage 6 and the two-phone observation is Stage 17. |
| **A2** (pause or cancel one file mid-transfer → the others complete; nothing stuck "Queued"; no restart) | **BLOCKED** | It is a two-phone observation and there is no transport yet. The engine half is executed: `cancellingOneInFlightFileLetsTheRestComplete` cancels a file whose transport never answers and asserts the other two COMPLETED and that **no item is left QUEUED**; `pausingOneFileLeavesItResumableAndTheOthersUntouched` asserts PAUSED with `resumeOffset` = bytes moved while the next file completes. Run 36299939101. |
| **A3** (end a session mid-transfer, start a new one → the interrupted file resumes automatically; new sends start at once) | **BLOCKED** | Same reason. Engine half executed: `losingASessionPausesEverythingAndFailsNothing` (all PAUSED, none FAILED, offset kept, state becomes `Closed`) and `aNewSessionRequeuesAndFinishesTheInterruptedBatch` (a fresh session finishes the batch with no user action). `aDeliberatePauseSurvivesAReconnect` proves INV-3 re-queues what the *connection* paused, not what the *user* did. |
| **A8** (ONE summary per batch, never one per file; SKIPPED separate from FAILED on both sides) | **BLOCKED** | The receiving side does not exist until Stage 6/9. Sender half executed: `aBatchCompletesAndProducesExactlyOneSummary` asserts exactly one `BatchCompleted` for three files, and `skippedIsReportedSeparatelyFromFailed` asserts `sent=1, skipped=1, failed=1` with three distinct item states. |
| INV-1 never hang | **PASS** | All three guards are executed against a transport that never returns: `aTransportThatNeverAnswersIsBoundedByTheIdleWatchdog` (idle bound, then §9.3's retries, then FAILED, and the next file still completes), the cancel and pause tests (3-second terminal fallback), and the session-loss test (INV-1a). |
| INV-2 pause/cancel never blocks others | **PASS** | The two tests above; the worker picks the next file on its next turn. |
| INV-3 session restart unsticks everything | **PASS** | `losingASessionPausesEverythingAndFailsNothing` + `aNewSessionRequeuesAndFinishesTheInterruptedBatch` + `TransferQueueTest.requeueSkipsWhatTheUserPausedOnPurpose`. |
| §9.3 retry policy | **PASS** | `aFlakyFileIsRetriedUpToThreeTimesAndThenSucceeds` (3 attempts, `retryCount` 2) and `aPermanentFailureStopsAfterExactlyThreeRetries` (4 attempts = first + 3 retries, one `ItemFailed`). A dead session pauses instead of failing. |
| §9.5 conflict policy | **PASS** | `ConflictsTest`: all four policies, "already present" beating any policy, same-size-without-hashes being a conflict rather than a duplicate, `photo (1).jpg` → `photo (2).jpg`, extension preserved through `archive.tar.gz`, and "apply to all" scoped to one batch. |
| §9.6 history through one path | **PASS** | `everyTerminalItemGoesThroughTheOneCompletePath`: completed, skipped and failed items all produce history rows with the right state and peer. |
| §9.7 restart recovery | **PASS** | `anInterruptedBatchIsJournaledAndOfferedBackAsPaused`: the journal exists mid-transfer, a fresh engine reports the peer and the remaining count, **nothing is in the queue until the user answers**, restore brings items back PAUSED, discard clears. |
| §9.4 orphan parts | **PASS** | `StorageTest.orphanPartFilesGoButResumableOnesStay`: >24 h orphan deleted, journaled part kept, fresh part kept, ordinary file untouched. |
| §20.3 events vs state | **PASS** | `afreshEngineNeverAnnouncesAClosedConnection`: a loss with no prior session does not fabricate a `Closed` state. |
| §3.6 shared items | **PASS** | `sharedItemsAreHeldUntilAPeerExists`; `MainActivity.handleShare` holds the batch and opens Discovery. |
| A18 (gate green, tests green) | **PASS** | Gate `PASS — no findings`, 203 files; **95 tests, 0 failed** in run 36299939101. |

### CI evidence

Run **36299939101**, all steps green:

```
- unit tests: 95 tests, 0 failed, 0 skipped - AccentTest (2), ConflictsTest (8), FileTypesTest (5),
  FmtTest (6), FramingTest (8), IdsTest (7), IntegrityTest (6), ManifestPermissionsTest (4),
  MediaDatesTest (5), MediaQueriesTest (5), PeerPaletteTest (3), ShellTest (7), StorageTest (8),
  TransferEngineTest (14), TransferQueueTest (7)
```

Two earlier runs in this stage failed and are part of the record: **36299667904**
(12 failing tests) and **36299572155** (two Kotlin errors). Both were diagnosed
from the new failure report, not from guesswork.

### Not yet done in this stage

- **The receiving side.** `TransportSession` has no incoming callbacks yet; §9.4's
  `.part` write/verify/rename, §9.5's dialog and the receiver's "already
  present" reply arrive with the LAN transport in Stage 6, where there is a
  real byte stream to attach them to. `Conflicts` and `Integrity` are the
  decision halves and are complete and tested.
- **No UI.** The queue sheet, the transfer rows and the §9.6 summary card are
  Stage 9; the §9.7 "Resume interrupted transfer?" dialog is Stage 9 too — the
  engine only reports that there is something to ask about.
- `SoundFx` (§8.1) is not written: §6.13's Sounds switch is the thing that
  reads it, and a preference nobody reads is a defect.
- The notification shows no peer, file or progress and has no actions; Stage 9
  owns that, together with cancelling from the notification (§21.2 case 6).

### Deviations and resolved tensions

11. **A cancelled item is not written to history.** §9.6 requires both
    directions through one `complete()` path, which is implemented; cancelling
    is the user's own action and §6.12's history is a record of transfers, not
    of abandoned ones. Completed, skipped and failed items are all recorded.
12. **The engine's time constants are constructor parameters.** The defaults
    are the specified values (3 retries, 800 ms, 3 s fallback, 2.5 s batch
    window). Injecting them is what lets the tests exercise the shipping loop
    instead of a test-only fast path.
13. **Tests drive the engine with virtual time rather than `advanceUntilIdle()`.**
    The first CI run of this suite showed `advanceUntilIdle()` leaving the
    engine's background work unrun, so items sat QUEUED. Recorded because it
    is a trap any later stage's tests could fall into.

---

## Stage 6 — LAN transport and unified discovery (§11.1, §11.2, §11.5, §11.6, §17.4)

### Files

| File | Sections implemented | Notes |
| --- | --- | --- |
| `core/network/Discovery.kt` | §11.1, §11.2 | UDP :33457, one announce every 1200 ms, MulticastLock held while discovering, peers expiring after four missed beacons. ONE deduplicated list keyed on device id, which Nearby and WebShare publish into through `publish()`. `preference` (Auto / Wi-Fi LAN / Nearby) only decides which peer Auto dials first — it never hides a peer, because §11.1 forbids a switch that hides half the network. |
| `core/network/Protocol.kt` | §11.2, §11.6 | Every control message in one place: HELLO, ACCEPT/REJECT, META, ACK, PAUSE_REQ, RESUME_REQ, CANCEL, BYE, PING/PONG, plus the DATA header and status lines. `versionProblem` returns the readable sentence, and the mirror-image sentence when the *peer* is newer. |
| `core/network/ControlChannel.kt` | §11.2 | One mutex guards every read and every write, and `request()` holds it across write-then-read-reply as one atomic unit. A read timeout returns null — idle is not closed. A `BufferedReader` is used here only because this channel is pure text. |
| `core/network/LanTransport.kt` | §11.2, §9.8, §11.6 | Serves control and data on the same port and dispatches on the first line, which is always read **byte by byte**. Consent is asked before anything else happens; reject is a clean REJECT with nothing written. The message pump answers META, PAUSE_REQ, CANCEL, PING and BYE. |
| `core/network/LanSession.kt` | §11.2, §9.3, §9.4 | One data connection **per file**, never reused. Seeks to the receiver's `.part` length and continues the seq numbering; `onProgress` fires only after `writeFrame`'s flush; no socket is ever force-cast to a channel; pause/cancel are checked per chunk and return `Paused`/`Cancelled` without touching other files. |
| `core/transfer/IncomingFiles.kt` | §9.4, §9.5, §8.2 | Process-scoped receive side. Answers META with §9.5's decision (detection order name → size → sha256, hash computed only when needed) and the resume offset, then appends CRC-checked frames and publishes on verify. A CRC failure discards the partial rather than leaving suspect bytes. |
| `core/storage/ReceiveSink.kt` | §9.4, §12.1 | `<name>.morsecode.part` → verify → publish, on both storage paths: a real part file on ≤28, and an `IS_PENDING` row whose display name carries the same suffix on 29+, published by one update that renames and clears the flag. |
| `core/network/SessionRegistry.kt` | §8.1, §6.16 | One live session per peer. Being in here IS the "already trusted in this session" state §6.16 refers to, and it dies with the session. |
| `core/network/ManualAddress.kt` | §11.5 | `host`, `host:port`, `morsecode://host:port`, IPv6 in brackets; defaults to 33456, validates before dialling, and owns the "Can't reach …" sentence. No QR anywhere. |
| `core/data/RecentDevices.kt` | §17.4 | A transport id, a name, a transport, a timestamp and a summary — and deliberately nothing that could shorten consent. |
| `core/transfer/TransferEngine.kt` (extended) | §8.2, §9.6, §20.1 | `registerIncoming` / `incomingProgress` / `incomingResult`: receives land in the SAME queue as sends and finish through the SAME history path. |
| `di/AppServices.kt` | §3.1 | `discovery`, `lanTransport`, `incomingFiles`, `receiveSinks`, `sessionRegistry`, `recentDevices`, `conflictPolicy`, and a stable per-install `deviceId`. |
| `app/src/test/.../{LanLoopbackTest,ProtocolTest,RecentDevicesTest}.kt` | §21.1 | 18 new tests, 5 of them over real sockets. |

### Criteria

| Criterion | Verdict | Evidence |
| --- | --- | --- |
| **A6** (LAN transfer never fails with "chunk magic mismatch"; byte-by-byte header read present and covered by a unit test) | **PASS** | Stage 5 proved the codec and the header read in isolation; this stage moves real bytes with them. `LanLoopbackTest.aFileCrossesTheWireByteIdentical` sends 600 KB — two full 256 KB frames and a remainder — over loopback TCP through `LanTransport` → `ControlChannel` → `LanSession` → `IncomingFiles`, and asserts the received file's **SHA-256 equals the source's**. Framing never slipped because the accept loop's only reader for a fresh socket is the byte-wise one. Two physical phones remain Stage 17. |
| **A1** (two phones on the same Wi-Fi: discovery defaults to LAN, a 144 MB video moves at Wi-Fi speed; Nearby peers appear in the same list) | **BLOCKED** | It is a two-phone, real-Wi-Fi measurement. What is executed: the loopback transfer above; `Discovery.preferred` returning the LAN peer under Auto; `ProtocolTest.aPeerIsOneRowWhateverFoundIt` and `Discovery.merge` keeping one row per device id with LAN winning. Throughput on real hardware is Stage 17; Nearby itself is Stage 7. |
| §11.2 resume from the receiver's `.part` | **PASS** | `aResumedSendContinuesFromThePartFile`: 150 KB pre-written, the ACK reports that offset, the sender seeks, and the finished file is **byte-identical** to the source — which is what proves it appended rather than truncated or restarted. |
| §9.4 "already present" → SKIPPED | **PASS** | `anIdenticalFileIsSkippedNotResent`: the final file already exists, detection runs name → size → sha256, and the sender gets `SkippedAlreadyPresent("identical file already present on receiver")` with no bytes sent. |
| §9.8 consent before anything crosses | **PASS** | `aRejectedConnectionYieldsNoSessionAndNoFiles`: no session is returned and the destination directory is empty. |
| §9.6 both directions through one `complete()` | **PASS** | `theReceiverRecordsHistoryThroughTheSameCompletePath`: a RECEIVING row in history with state COMPLETED, and the item visible in the same queue the UI renders. |
| §11.6 protocol versioning | **PASS** | `aVersionMismatchIsAReadableSentenceNotAFramingError`, and `LanTransport` sends that sentence as the REJECT reason and surfaces it through `onVersionProblem`. |
| §11.5 manual pairing, no QR | **PASS** | `ProtocolTest` covers all three accepted forms, the default port, the rejections and the failure sentence. `grep -rn "qr\|zxing\|barcode" app/src/main --include=*.kt -i` → 5 matches, **all of them comments saying there is no QR** (ManualAddress, Ids, Permissions, DiscoveryFragment, WebShareFragment); no scanner, generator, route or dependency exists. CAMERA is absent from the manifest (asserted by `ManifestPermissionsTest`). |
| §17.4 recency shortens discovery, never consent | **PASS** | `nothingStoredCouldEverSkipConsent` asserts the stored fields are exactly id, name, transport, timestamp and summary — there is no token or trust flag for a later change to lean on. |
| A18 | **PASS** | Gate `PASS — no findings`, 213 files; **113 tests, 0 failed** in run 36301692302. |

### CI evidence

Run **36301692302**, all steps green:

```
- unit tests: 113 tests, 0 failed, 0 skipped - ... LanLoopbackTest (5), ProtocolTest (9),
  RecentDevicesTest (4) ...
```

One earlier run failed and is part of the record: **36301564476**, a JUnit
class-validation error (`@After` returning a value).

### Not yet done in this stage

- **Nothing is wired to a screen.** Discovery is not started by the dashboard,
  the consent callback is not connected to `ConsentDialog`, and no session is
  handed to the engine yet — Stage 8 owns Connect/Discovery/Consent and Stage 9
  the transfer screens. `LanTransport.consent` currently defaults to accepting,
  which is safe only because nothing starts the server yet; Stage 8 must set it
  before `start()` is ever called.
- PING/PONG is answered but no keepalive is *sent* on a timer; it belongs with
  the session lifecycle work in Stage 8.
- §9.5's [Overwrite] / [Skip] / [Keep both] dialog is not raised: the receiver
  applies the default "Rename duplicates" policy. The ASK branch exists and is
  unreachable until Stage 9 provides the dialog.
- The SAF-tree destination path has no sink yet (legacy file and MediaStore do);
  it lands with the Settings → Storage access screen.

### Deviations and resolved tensions

14. **Control and data share port 33456.** §11.2 names ":33456" for both
    "Data/serve" and the control connection, so one listener serves both and
    dispatches on the first line. This makes the byte-by-byte rule structural
    rather than a convention: the only reader that ever touches a fresh socket
    is the byte-wise one.
15. **On API 29+ the `.part` file is an `IS_PENDING` MediaStore row** whose
    display name still ends in `.morsecode.part`. §9.4's prescribed name and
    its resumable, visibly-unfinished semantics are preserved; a raw part file
    in shared storage is simply not something scoped storage permits.
16. **The receiver's conflict check compares against the FINAL file**, using
    the `.part` only for the resume offset. §9.5's detection order is
    unchanged; this just names which file is being detected.

---
