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
