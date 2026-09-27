#!/usr/bin/env python3
"""
Summarise a connectedAndroidTest run into something readable from outside the
runner (§22.1: evidence has to be inspectable).

Reads the JUnit XML the Android Gradle plugin writes, plus the screenshot
directory, and prints markdown: the tally, every failure with its message and
the first frames of its stack, and the list of screens actually photographed.
"""
import glob
import os
import xml.etree.ElementTree as ET

def main() -> None:
    xml_files = sorted(glob.glob("emulator-evidence/results/**/*.xml", recursive=True))
    if not xml_files:
        xml_files = sorted(glob.glob("emulator-evidence/results-last/**/*.xml", recursive=True))
    if not xml_files:
        xml_files = sorted(glob.glob("app/build/outputs/androidTest-results/**/*.xml", recursive=True))

    total = failed = skipped = 0
    failures = []
    for path in xml_files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        total += int(root.get("tests", 0))
        failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
        skipped += int(root.get("skipped", 0))
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                trace = (bad.text or "").strip().splitlines()
                failures.append((
                    "%s.%s" % (case.get("classname", "?").split(".")[-1], case.get("name")),
                    (bad.get("message") or "").strip()[:800],
                    "\n".join(trace[:14]),
                ))

    shots = sorted(os.path.basename(p) for p in glob.glob("emulator-evidence/screenshots/**/*.png", recursive=True))
    sweep = sorted(os.path.basename(p) for p in glob.glob("emulator-evidence/sweep/*.png"))
    notes = sorted(glob.glob("emulator-evidence/screenshots/**/*.txt", recursive=True))

    print("## Instrumented run")
    print()
    marker = "emulator-evidence/script-marker.txt"
    state = open(marker).read().strip() if os.path.exists(marker) else "never started"
    print("- harness script: **%s**" % state)
    if xml_files:
        print("- tests: **%d**, failed: **%d**, skipped: %d" % (total, failed, skipped))
    else:
        print("- **no test results were produced** — the suite did not run to the point of writing XML")
    print("- screenshots captured: **%d**" % len(shots))
    print("- size-sweep frames: **%d**" % len(sweep))
    print()

    for name, title in (
        ("emulator-evidence/diag.txt", "Device as the harness found it"),
        ("emulator-evidence/gradle-tails.txt", "Gradle output (tail)"),
        ("emulator-evidence/shots-listing.txt", "What was on the device"),
    ):
        if os.path.exists(name):
            body = open(name, errors="replace").read().strip()
            if body:
                print("### %s" % title)
                print()
                print("```")
                print(body[-2500:])
                print("```")
                print()

    if failures:
        print("### Failures")
        print()
        for name, message, trace in failures[:20]:
            print("**%s**" % name)
            print()
            print("```")
            print(message)
            print(trace)
            print("```")
            print()

    if notes:
        print("### Screens that could not be photographed")
        print()
        for note in notes[:20]:
            print("- `%s`" % os.path.basename(note))
        print()

    listing = "emulator-evidence/shots-listing.txt"
    if not shots and os.path.exists(listing):
        print("### Why no screenshots were collected")
        print()
        print("```")
        print(open(listing, errors="replace").read()[:1500])
        print("```")
        print()

    if shots:
        print("### Screens photographed")
        print()
        print("```")
        for shot in shots:
            print(shot)
        print("```")
    if sweep:
        print()
        print("### §4.14 size sweep")
        print()
        print("```")
        for frame in sweep:
            print(frame)
        print("```")

main()
