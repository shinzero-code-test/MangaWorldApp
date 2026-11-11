#!/usr/bin/env python3
"""
U-13 cancellation-safety gate.

`runCatching` swallows `CancellationException`: a cancelled load renders as a
user-visible error, work continues after the user navigates away, and
post-cancel state writes still execute. `suspendRunCatching` (see
app/src/main/kotlin/com/exapps/mangaworld/core/util/SuspendRunCatching.kt)
rethrows it.

The Stage-1/2 sweep converted every bare `runCatching` whose lambda awaits a
suspend call; the remaining bare sites are provably sync (JSON/regex parsing,
sync IO, telemetry sinks). This gate therefore looks for the ACTUAL defect —
a bare `runCatching` block whose body awaits a suspend call — and exits 1
when it finds one. A bare-site count alone would be noise.

Usage:  python3 scripts/cancellation_gate.py [root]      (default: app/src/main/kotlin)
Exit:   0 = clean, 1 = offenders found, 2 = usage/internal error.
"""
from __future__ import annotations

import pathlib
import re
import sys

# A suspend marker: the lambda body awaits / collects / first().
MARKERS = re.compile(
    r"\.await\(\)|\.first\(\)|\.collect\s*\{|\.collect\(\)|await\s*\{|awaitAll\(\)"
    r"|\bawait[A-Za-z0-9_]*\s*\(|\.await[A-Za-z0-9_]*\s*\("
)
STRIP_STRINGS = re.compile(r'"(?:\\.|[^"\\\n])*"')
STRIP_CHARS = re.compile(r"'(?:\\.|[^'\\\n])*'")
LINE_COMMENT = re.compile(r"//[^\n]*")
BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.S)


def _blank(match: re.Match) -> str:
    """Blank the match but keep newlines so line numbers stay aligned."""
    return "".join("\n" if ch == "\n" else " " for ch in match.group(0))


def strip_noise(src: str) -> str:
    src = BLOCK_COMMENT.sub(_blank, src)
    src = LINE_COMMENT.sub(_blank, src)
    src = STRIP_STRINGS.sub(_blank, src)
    src = STRIP_CHARS.sub(_blank, src)
    return src


def lambda_bodies(lines: list[str], start: int) -> list[str]:
    """Return the balanced `{ … }` block that opens on `start` (or the next lines)."""
    depth = 0
    started = False
    body: list[str] = []
    for j in range(start, min(start + 400, len(lines))):
        for ch in lines[j]:
            if ch == "{":
                depth += 1
                started = True
            elif ch == "}":
                depth -= 1
        body.append(lines[j])
        if started and depth <= 0:
            break
    return body


def scan(root: pathlib.Path) -> list[str]:
    offenders: list[str] = []
    for path in sorted(root.rglob("*.kt")):
        raw = path.read_text()
        clean = strip_noise(raw)
        raw_lines = raw.split("\n")
        clean_lines = clean.split("\n")
        for i, line in enumerate(raw_lines):
            if "runCatching" not in line or "suspendRunCatching" in line:
                continue
            body = "\n".join(lambda_bodies(clean_lines, i))
            if MARKERS.search(body):
                offenders.append(f"{path}:{i + 1}: {line.strip()[:110]}")
    return offenders


def main(argv: list[str]) -> int:
    if len(argv) > 2:
        print(__doc__)
        return 2
    root = pathlib.Path(argv[1] if len(argv) > 1 else "app/src/main/kotlin")
    if not root.exists():
        print(f"cancellation-gate: root not found: {root}")
        return 2
    bare = migrated = 0
    for path in root.rglob("*.kt"):
        text = path.read_text()
        bare += sum(1 for line in text.split("\n") if "runCatching" in line and "suspendRunCatching" not in line)
        migrated += text.count("suspendRunCatching")
    print(f"bare runCatching sites: {bare} (sync-only by construction) · suspendRunCatching: {migrated}")
    offenders = scan(root)
    if offenders:
        print("::error::U-13 regression: bare runCatching around a suspend call at:")
        for offender in offenders:
            print("  " + offender)
        return 1
    print("No bare runCatching wraps a suspend call.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
