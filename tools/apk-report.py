#!/usr/bin/env python3
"""
§19.4's size report: the APK's total, and where the bytes actually are.

"A release APK materially BELOW this range — under ~4.5 MB — is evidence of
something MISSING, not of efficiency." A single number cannot tell you which
thing is missing, so this prints the composition and checks the specific
absences §19.4 names: Material, Media3, the launcher densities and the
WebShare assets.
"""
import collections
import sys
import zipfile

RANGE_LOW, RANGE_HIGH = 5.0, 9.0
SUSPICIOUS = 4.5


def mb(value: int) -> float:
    return value / (1024 * 1024)


def main() -> None:
    if len(sys.argv) < 2 or not sys.argv[1]:
        print("### APK composition (§19.4)\n\nNo APK was found to measure.")
        return
    path = sys.argv[1]
    with zipfile.ZipFile(path) as apk:
        infos = list(apk.infolist())
        names = [i.filename for i in infos]
        total = sum(int(i.compress_size or 0) for i in infos)

        buckets = collections.Counter()
        for info in infos:
            name = info.filename
            if name.startswith("classes") and name.endswith(".dex"):
                bucket = "dex (code)"
            elif name.startswith("res/"):
                bucket = "res (drawables, layouts)"
            elif name == "resources.arsc":
                bucket = "resources.arsc (strings, styles)"
            elif name.startswith("assets/web/"):
                bucket = "assets/web (WebShare site)"
            elif name.startswith("assets/"):
                bucket = "assets (other)"
            elif name.startswith("lib/"):
                bucket = "lib (native)"
            elif name.startswith("META-INF/"):
                bucket = "META-INF (signature)"
            else:
                bucket = "other"
            buckets[bucket] += info.compress_size

    print("### APK composition (§19.4)")
    print()
    print("| Part | Compressed |")
    print("| --- | --- |")
    for bucket, size in buckets.most_common():
        print("| %s | %.2f MB |" % (bucket, mb(size)))
    print("| **total** | **%.2f MB** |" % mb(total))
    print()

    # The specific absences §19.4 tells you to look for.
    checks = [
        ("Material Components", any("com/google/android/material" in n for n in names)
         or any(n.startswith("res/") and "material" in n for n in names)),
        ("Media3 / ExoPlayer", any("androidx/media3" in n for n in names)
         or any("media3" in n for n in names)),
        ("launcher icon, all densities",
         sum(1 for n in names if "ic_launcher" in n) >= 4),
        ("WebShare site packaged",
         all(any(n.endswith(asset) for n in names)
             for asset in ("web/index.html", "web/app.js", "web/styles.css"))),
    ]
    print("| §19.4 check | Present |")
    print("| --- | --- |")
    for label, present in checks:
        print("| %s | %s |" % (label, "yes" if present else "**NO**"))
    print()

    size = mb(total)
    if size < SUSPICIOUS:
        print("**%.2f MB is below §19.4's ~4.5 MB floor — investigate before "
              "celebrating.**" % size)
    elif size < RANGE_LOW:
        print("%.2f MB is under the 5–9 MB range, though above the floor." % size)
    elif size <= RANGE_HIGH:
        print("%.2f MB is inside §19.4's 5–9 MB range." % size)
    else:
        print("**%.2f MB is above §19.4's 9 MB ceiling.**" % size)


main()
