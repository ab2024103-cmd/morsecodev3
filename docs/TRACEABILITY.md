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
