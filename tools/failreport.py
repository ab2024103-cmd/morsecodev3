#!/usr/bin/env python3
"""
Turn a failed CI build into something readable from outside the runner.

Job logs are not reachable with the workflow token from the development
sandbox, so on failure CI writes this report into the rolling prerelease's
notes, which the API does return. §22.1 asks for evidence that can be
inspected; that has to include the evidence of a failure.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

MAX = 6000
out = []

log = sys.argv[1] if len(sys.argv) > 1 else "build.log"
if os.path.exists(log):
    text = open(log, errors="replace").read()
    compile_errors = [l for l in text.splitlines() if l.startswith("e: ") or "error:" in l]
    if compile_errors:
        out.append("### Compilation errors\n")
        out.append("```")
        out.extend(compile_errors[:40])
        out.append("```")

failures = []
for path in sorted(glob.glob("app/build/test-results/**/*.xml", recursive=True)):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    for case in root.iter("testcase"):
        for bad in list(case.findall("failure")) + list(case.findall("error")):
            message = (bad.get("message") or "").strip()
            body = (bad.text or "").strip().splitlines()
            trace = "\n".join(body[:12])
            failures.append(
                "%s.%s\n%s\n%s" % (case.get("classname"), case.get("name"), message, trace)
            )

if failures:
    out.append("### Failing tests (%d)\n" % len(failures))
    for failure in failures[:15]:
        out.append("```")
        out.append(failure)
        out.append("```")

if not out:
    out.append("No compilation errors and no failing test cases were found; "
               "the failure is in a build step outside the test task.")

report = "\n".join(out)
print(report[:MAX])
