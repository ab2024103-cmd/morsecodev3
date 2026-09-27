#!/usr/bin/env python3
"""
Summarise the JUnit XML that `testDebugUnitTest` leaves behind, as one line.

The build machine's artifacts are not reachable from the development sandbox,
so CI carries this line into the run summary and the release notes, which are
readable through the API. §22.1 asks for evidence that can be inspected.
"""
import glob
import sys
import xml.etree.ElementTree as ET

tests = failed = skipped = 0
suites = []
for path in sorted(glob.glob("app/build/test-results/testDebugUnitTest/*.xml")):
    root = ET.parse(path).getroot()
    tests += int(root.get("tests", 0))
    failed += int(root.get("failures", 0)) + int(root.get("errors", 0))
    skipped += int(root.get("skipped", 0))
    suites.append("%s (%s)" % (root.get("name", "?").split(".")[-1], root.get("tests")))

if not suites:
    print("no test results found")
    sys.exit(1)
print("%d tests, %d failed, %d skipped - %s" % (tests, failed, skipped, ", ".join(suites)))
