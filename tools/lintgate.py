#!/usr/bin/env python3
"""
Morsecode lint gate — spec §20.8. Runs in CI and fails the build.

Checks, in the order the specification lists them:
  1. unresolved resource references from the referencing module's view;
  2. Java-8 default collection methods without an SDK_INT >= 24 guard (§3.2);
  3. more than one companion object per class; nested data classes inside
     inner class bodies; coroutine extensions used without their import (§20.9);
  4. interactive views without contentDescription (or an explicit decorative
     marker); touch targets under 48 dp (§15.1, §15.2);
  5. hardcoded hex colours in layouts/Kotlin outside the token file (§4.13);
  6. user-visible string literals outside strings.xml (§3.8);
  7. duplicate string names and unescaped apostrophes in strings.xml (§3.8);
  8. identity: the legacy product name appears nowhere the build ships or
     generates (A19). The needle is assembled at runtime so this gate is not
     itself a hit.

This is a linter, not a compiler (§19.5): a green run here never means "it
builds". Only the remote build says that.
"""

from __future__ import annotations

import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(APP, "res")
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
TOOLS_NS = "{http://schemas.android.com/tools}"

# The only files allowed to contain hex colour literals (§4.13).
TOKEN_FILES = {
    os.path.join(RES, "values", "colors.xml"),
    os.path.join(RES, "values-night", "colors.xml"),
}

SKIP_DIRS = {".git", "build", ".gradle", ".idea", "docs", "node_modules"}

failures: list[str] = []
checked = 0


def fail(path: str, line: int | None, message: str) -> None:
    where = os.path.relpath(path, ROOT)
    if line:
        where += f":{line}"
    failures.append(f"{where}: {message}")


def walk(base: str, exts: tuple[str, ...]):
    for dirpath, dirnames, filenames in os.walk(base):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            if name.endswith(exts):
                yield os.path.join(dirpath, name)


def strip_xml_comments(text: str) -> str:
    return re.sub(r"<!--.*?-->", "", text, flags=re.S)


def strip_kotlin_comments(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


# ---------------------------------------------------------------------------
# 1. Resource references
# ---------------------------------------------------------------------------
def collect_defined_resources() -> set[str]:
    defined: set[str] = set()
    if not os.path.isdir(RES):
        return defined
    for entry in sorted(os.listdir(RES)):
        folder = os.path.join(RES, entry)
        if not os.path.isdir(folder):
            continue
        kind = entry.split("-")[0]
        if kind == "values":
            for path in sorted(os.listdir(folder)):
                if not path.endswith(".xml"):
                    continue
                try:
                    tree = ET.parse(os.path.join(folder, path))
                except ET.ParseError as exc:
                    fail(os.path.join(folder, path), None, f"malformed XML: {exc}")
                    continue
                for child in tree.getroot():
                    if not isinstance(child.tag, str):
                        continue
                    name = child.attrib.get("name")
                    if not name:
                        continue
                    tag = child.tag
                    if tag == "item":
                        tag = child.attrib.get("type", "item")
                    if tag in ("string-array", "integer-array"):
                        tag = "array"
                    if tag == "declare-styleable":
                        for attr in child:
                            if attr.tag == "attr" and attr.attrib.get("name"):
                                defined.add("attr/" + attr.attrib["name"])
                        continue
                    defined.add(f"{tag}/{name}")
        else:
            for path in sorted(os.listdir(folder)):
                base = path.split(".")[0]
                defined.add(f"{kind}/{base}")
    # ids declared inline with @+id in any XML
    for path in walk(RES, (".xml",)):
        for match in re.finditer(r'@\+id/([A-Za-z0-9_]+)', open(path, encoding="utf-8").read()):
            defined.add("id/" + match.group(1))
    return defined


def check_resource_refs(defined: set[str]) -> None:
    global checked
    sources = list(walk(RES, (".xml",)))
    sources.append(os.path.join(APP, "AndroidManifest.xml"))
    sources += list(walk(os.path.join(APP, "java"), (".kt",)))
    ref_re = re.compile(r'[@?](?!\+)(?:android:)?([a-z]+)/([A-Za-z0-9_.]+)')
    # `android.R.*` is the platform's, not ours; only local R references are checked.
    kt_ref_re = re.compile(r'(?<!android\.)\bR\.([a-z]+)\.([A-Za-z0-9_]+)')
    for path in sources:
        if not os.path.exists(path):
            continue
        checked += 1
        raw = open(path, encoding="utf-8").read()
        text = strip_kotlin_comments(raw) if path.endswith(".kt") else strip_xml_comments(raw)
        for lineno, line in enumerate(text.splitlines(), 1):
            if path.endswith(".kt"):
                for kind, name in kt_ref_re.findall(line):
                    # R field names replace '.' with '_'; styles are declared dotted.
                    candidates = {f"{kind}/{name}", f"{kind}/{name.replace('_', '.')}"}
                    if not (candidates & defined):
                        fail(path, lineno, f"unresolved resource reference R.{kind}.{name}")
                continue
            for match in ref_re.finditer(line):
                if "android:" in match.group(0):
                    continue
                kind, name = match.group(1), match.group(2)
                if kind in ("null", "empty") or "." in name:
                    continue
                key = f"{kind}/{name}"
                if kind == "style" and key not in defined:
                    # styles may inherit from AppCompat; only flag local look-ups
                    if not name.startswith(("Theme.Morsecode", "Widget.Morsecode", "TextAppearance.Morsecode")):
                        continue
                if key not in defined:
                    fail(path, lineno, f"unresolved resource reference @{kind}/{name}")


# ---------------------------------------------------------------------------
# 2. Java-8 default collection methods (§3.2)
# ---------------------------------------------------------------------------
JAVA8_TOKENS = (
    "removeIf", "putIfAbsent", "computeIfAbsent", "computeIfPresent",
    "getOrDefault", "replaceAll", ".stream(", "Stream.", "Collectors.",
)


def check_java8_guards() -> None:
    for path in walk(os.path.join(APP, "java"), (".kt",)):
        text = strip_kotlin_comments(open(path, encoding="utf-8").read())
        lines = text.splitlines()
        for lineno, line in enumerate(lines, 1):
            for token in JAVA8_TOKENS:
                if token not in line:
                    continue
                window = "\n".join(lines[max(0, lineno - 25):lineno])
                if re.search(r"SDK_INT\s*>=\s*(24|Build\.VERSION_CODES\.N)", window):
                    continue
                fail(path, lineno,
                     f"Java-8 default collection method '{token.strip('.(')}' needs an "
                     f"SDK_INT >= 24 guard with a manual fallback (§3.2)")


# ---------------------------------------------------------------------------
# 2b. Accessibility (§15.1): every icon carries a description or is declared
#     decorative. An ImageView with neither is invisible to TalkBack, which
#     §15 treats as a functional defect rather than a nicety.
# ---------------------------------------------------------------------------
IMAGE_VIEW_CREATION = re.compile(r"=\s*AppCompatImageView\(|=\s*ImageView\(")
A11Y_SATISFIED = re.compile(
    r"contentDescription|importantForAccessibility|IMPORTANT_FOR_ACCESSIBILITY_NO|A11y\."
)


def check_accessibility() -> None:
    for path in walk(os.path.join(APP, "java"), (".kt",)):
        text = strip_kotlin_comments(open(path, encoding="utf-8").read())
        lines = text.splitlines()
        for lineno, line in enumerate(lines, 1):
            if not IMAGE_VIEW_CREATION.search(line):
                continue
            window = "\n".join(lines[lineno - 1:lineno + 24])
            if A11Y_SATISFIED.search(window):
                continue
            fail(path, lineno,
                 "an ImageView needs a contentDescription or an explicit "
                 "importantForAccessibility=NO (§15.1)")


# ---------------------------------------------------------------------------
# 3. Kotlin language constraints (§20.9)
# ---------------------------------------------------------------------------
COROUTINE_IMPORTS = {
    "launch": "kotlinx.coroutines.launch",
    "async": "kotlinx.coroutines.async",
    "withContext": "kotlinx.coroutines.withContext",
    "delay": "kotlinx.coroutines.delay",
    "coroutineScope": "kotlinx.coroutines.coroutineScope",
    "withTimeout": "kotlinx.coroutines.withTimeout",
}


def check_kotlin_constraints() -> None:
    for path in walk(os.path.join(APP, "java"), (".kt",)):
        text = strip_kotlin_comments(open(path, encoding="utf-8").read())

        classes = len(re.findall(r"\b(?:class|object|interface)\s+[A-Z]", text))
        companions = len(re.findall(r"\bcompanion object\b", text))
        if companions > max(classes, 1):
            fail(path, None, "more than one companion object per class (§20.9)")

        for match in re.finditer(r"\binner class\b", text):
            tail = text[match.end():match.end() + 2000]
            if re.search(r"\bdata class\b", tail.split("\n\n")[0] if "\n\n" in tail else tail[:600]):
                fail(path, text[:match.start()].count("\n") + 1,
                     "nested data class inside an inner class body (§20.9)")

        for name, imp in COROUTINE_IMPORTS.items():
            if re.search(rf"[^.\w]{name}\s*[({{]", text):
                if imp not in text and "kotlinx.coroutines.*" not in text and f"fun {name}" not in text:
                    fail(path, None, f"coroutine extension '{name}' used without importing {imp} (§20.9)")


# ---------------------------------------------------------------------------
# 4-6. Layout rules
# ---------------------------------------------------------------------------
INTERACTIVE_TAGS = ("Button", "ImageButton", "ImageView", "CheckBox", "RadioButton",
                    "Switch", "SwitchCompat", "SeekBar", "EditText")
TEXT_ATTRS = ("text", "hint", "contentDescription", "title", "summary", "label")


def dp_value(value: str | None) -> float | None:
    if not value or not value.endswith("dp"):
        return None
    try:
        return float(value[:-2])
    except ValueError:
        return None


def check_layouts() -> None:
    global checked
    for path in walk(RES, (".xml",)):
        raw = open(path, encoding="utf-8").read()
        text = strip_xml_comments(raw)
        checked += 1

        # hardcoded hex outside the token file
        if path not in TOKEN_FILES:
            for lineno, line in enumerate(text.splitlines(), 1):
                if re.search(r'#[0-9a-fA-F]{3,8}\b', line):
                    fail(path, lineno, "hardcoded hex colour outside the token file (§4.13)")

        if "/layout" not in path and "/drawable" not in path:
            continue
        try:
            root = ET.fromstring(text)
        except ET.ParseError as exc:
            fail(path, None, f"malformed XML: {exc}")
            continue
        for element in root.iter():
            if not isinstance(element.tag, str):
                continue
            tag = element.tag.split(".")[-1]
            attrs = element.attrib

            # literal user-visible strings
            for attr in TEXT_ATTRS:
                value = attrs.get(ANDROID_NS + attr)
                if value and not value.startswith(("@", "?")):
                    fail(path, None,
                         f"<{tag}> android:{attr} carries the literal \"{value}\" — "
                         f"user-visible strings live in strings.xml (§3.8)")

            clickable = attrs.get(ANDROID_NS + "clickable") == "true"
            interactive = tag in INTERACTIVE_TAGS or clickable

            if interactive and tag in ("ImageView", "ImageButton"):
                has_desc = (ANDROID_NS + "contentDescription") in attrs
                decorative = attrs.get(ANDROID_NS + "importantForAccessibility") == "no"
                if not has_desc and not decorative:
                    fail(path, None,
                         f"<{tag}> has no contentDescription and is not marked decorative (§15.1)")

            if interactive:
                for dim in ("layout_width", "layout_height"):
                    value = dp_value(attrs.get(ANDROID_NS + dim))
                    if value is not None and value < 48:
                        padded = any(k.startswith(ANDROID_NS + "padding") or
                                     k in (ANDROID_NS + "minWidth", ANDROID_NS + "minHeight")
                                     for k in attrs)
                        if not padded:
                            fail(path, None,
                                 f"<{tag}> {dim}={value:g}dp is under the 48 dp touch target "
                                 f"and has no padding to reach it (§15.2)")


def check_kotlin_hex_and_strings() -> None:
    for path in walk(os.path.join(APP, "java"), (".kt",)):
        text = strip_kotlin_comments(open(path, encoding="utf-8").read())
        for lineno, line in enumerate(text.splitlines(), 1):
            if re.search(r'"#[0-9a-fA-F]{3,8}"', line) or re.search(r'0xFF[0-9A-Fa-f]{6}', line):
                fail(path, lineno, "hardcoded colour in Kotlin — use a token (§4.13)")


# ---------------------------------------------------------------------------
# 7. strings.xml hygiene (§3.8)
# ---------------------------------------------------------------------------
def check_strings() -> None:
    for path in walk(RES, ("strings.xml",)):
        raw = open(path, encoding="utf-8").read()
        names: set[str] = set()
        try:
            root = ET.fromstring(raw)
        except ET.ParseError as exc:
            fail(path, None, f"malformed XML: {exc}")
            continue
        for child in root:
            if not isinstance(child.tag, str):
                continue
            name = child.attrib.get("name")
            if not name:
                continue
            if name in names:
                fail(path, None, f"duplicate string name '{name}' (§3.8)")
            names.add(name)
        for lineno, line in enumerate(strip_xml_comments(raw).splitlines(), 1):
            match = re.search(r"<string[^>]*>(.*)</string>", line)
            if match and re.search(r"(?<!\\)'", match.group(1)):
                fail(path, lineno, "unescaped apostrophe in a string — write \\' (§3.8)")


# ---------------------------------------------------------------------------
# 8. Identity (A19)
# ---------------------------------------------------------------------------
def check_identity() -> None:
    # Assembled, never written literally: the gate must not flag itself (A19).
    pattern = re.compile("morse" + "link", re.I)
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for name in filenames:
            path = os.path.join(dirpath, name)
            if name.endswith((".png", ".jar", ".jks", ".apk", ".webp")):
                continue
            try:
                with open(path, encoding="utf-8", errors="ignore") as handle:
                    for lineno, line in enumerate(handle, 1):
                        if pattern.search(line):
                            fail(path, lineno,
                                 "the legacy product name appears in a shipped file (A19, §1.1.1)")
            except OSError:
                continue


def main() -> int:
    defined = collect_defined_resources()
    check_resource_refs(defined)
    check_java8_guards()
    check_accessibility()
    check_kotlin_constraints()
    check_layouts()
    check_kotlin_hex_and_strings()
    check_strings()
    check_identity()

    print(f"Morsecode lint gate (§20.8) — {checked} resource/source files scanned")
    if failures:
        print(f"\nFAIL — {len(failures)} finding(s):\n")
        for item in failures:
            print("  " + item)
        return 1
    print("PASS — no findings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
