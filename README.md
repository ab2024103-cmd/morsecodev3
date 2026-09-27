# Morsecode

Offline, peer-to-peer file transfer for Android. No cloud, no internet, no
accounts, no ads, no analytics.

- **applicationId / root package** `app.morsecode.android`
- **Version** 1.0.0 (versionCode 1)
- **minSdk** 21 · **targetSdk / compileSdk** 35 · primary reference device:
  HUAWEI MYA-L10 on Android 6.0 (API 23)
- **Ports** 33455 WebShare · 33456 TCP control+data · 33457 UDP discovery

The binding specification is [`docs/prompmaster.txt`](docs/prompmaster.txt);
the staged build plan is [`docs/STAGES.md`](docs/STAGES.md) and the
per-stage evidence log is [`docs/TRACEABILITY.md`](docs/TRACEABILITY.md).
`docs/ui-simulator.html` (and `docs/maketup.html`) are the visual reference.

## Build

```bash
./gradlew assembleDebug        # JDK 17, Android SDK 35
./gradlew testDebugUnitTest
python3 tools/lintgate.py      # the §20.8 lint gate, also run in CI
bash tools/genicons.sh         # regenerates the legacy launcher bitmaps (needs ImageMagick)
```

CI (`.github/workflows/build.yml`) runs the gate and the unit tests, assembles
the debug and release APKs and publishes both as release assets. The release
APK is signed with a single stable upload key supplied through the repository
secrets `KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD` and `STORE_PASSWORD`; when
those secrets are absent the release variant is built with the debug key,
published as `morsecode-<version>-preview-unsigned.apk`, and §19.3 / A18 are
reported as unsatisfied.

## Dependency budget

Non-AndroidX growth is capped at 5 MB (§3.3). The allowed set is exactly
AndroidX, Google Play Services Nearby Connections and NanoHTTPD, plus the
Kotlin runtime and coroutines. There is no Material Components dependency:
the UI is custom lightweight widgets on an AppCompat theme ancestry (§1.5,
§4.13).

## Status

Stages 1–17 of `docs/STAGES.md` are implemented and CI is green: the lint gate
(§20.8) passes over 256 files and 227 unit tests run on every push, with both
APKs published to the rolling prerelease.

**The build is not done in §23's sense, and the reason is written down rather
than glossed.** `docs/TRACEABILITY.md` carries a verdict for every one of the
35 acceptance criteria: 2 PASS, 33 BLOCKED, 0 FAIL. Nothing is marked pass on
the strength of code review — §22.1 classes "shipped but unconfirmed" as
BLOCKED, and this environment has no phone, no emulator, no browser and no
TalkBack.

Two things would finish it:

1. **Signing (§19.3, A18).** Add `KEYSTORE_B64`, `KEY_ALIAS`, `KEY_PASSWORD`
   and `STORE_PASSWORD` as repository secrets. CI already installs them and
   signs; until they exist it publishes `morsecode-1.0.0-preview-unsigned.apk`
   and says so. §19.3 forbids inventing a throwaway key, so none was invented.
2. **The device matrix (§21.2).** `docs/DEVICE_MATRIX.md` is the run sheet:
   sixteen cases on the reference MYA-L10 and a current phone, plus a laptop
   browser, each naming the criterion it settles.
