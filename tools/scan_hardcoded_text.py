#!/usr/bin/env python3
"""Scan for hardcoded (non-ASCII) string literals in game code.

Purpose
- Help migrate UI/dialog/HUD text out of code into data/strings/*.json
  to avoid localization pain later.

Heuristics
- Looks for string literals that contain CJK characters.
- Skips obvious comment-only matches.
- Not a full parser; it is intentionally conservative.

Usage
  python3 tools/scan_hardcoded_text.py
  python3 tools/scan_hardcoded_text.py --root src/main --max 500

Exit code
- 0: scan completed
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

CJK_RE = re.compile(r"[\u4e00-\u9fff]")
# Kotlin/Java string literal (single line, not raw/multiline): "..."
STR_RE = re.compile(r"\"([^\"\\]|\\.)*\"")
# Very rough comment detection
LINE_COMMENT_RE = re.compile(r"^\s*//")


def iter_files(root: pathlib.Path):
    for ext in (".kt", ".java"):
        yield from root.rglob(f"*{ext}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default="src/main", help="scan root")
    ap.add_argument("--max", type=int, default=300, help="max hits to print")
    args = ap.parse_args()

    repo = pathlib.Path(__file__).resolve().parents[1]
    root = (repo / args.root).resolve()

    hits = []
    for path in iter_files(root):
        try:
            text = path.read_text("utf-8")
        except Exception:
            continue
        lines = text.splitlines()
        for i, line in enumerate(lines, start=1):
            if LINE_COMMENT_RE.match(line):
                continue
            if not CJK_RE.search(line):
                continue
            for m in STR_RE.finditer(line):
                lit = m.group(0)
                if CJK_RE.search(lit):
                    snippet = line.strip()
                    hits.append((str(path.relative_to(repo)), i, lit, snippet))

    print(f"hits: {len(hits)}")
    for idx, (rel, ln, lit, snippet) in enumerate(hits[: args.max], start=1):
        print(f"{idx:4d}. {rel}:{ln}: {lit}")
        print(f"      {snippet}")

    if len(hits) > args.max:
        print(f"... truncated, showing first {args.max}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
