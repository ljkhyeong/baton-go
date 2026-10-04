#!/usr/bin/env python3
"""Markdown 상대 링크의 대상 파일과 GitHub 제목 앵커를 확인한다.

인자가 없으면 Git이 추적하거나 무시되지 않은 모든 *.md 파일을 검사한다. 외부 URL은 확인하지 않는다.
표준 라이브러리만 사용한다.
"""

import re
import subprocess
import sys
import unicodedata
from functools import lru_cache
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(subprocess.run(
    ["git", "rev-parse", "--show-toplevel"], check=True, capture_output=True, text=True
).stdout.strip())
FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^\s{0,3}(#{1,6})\s+(.*?)\s*#*\s*$")
INLINE_CODE = re.compile(r"`[^`]*`")
LINK = re.compile(r"!?\[([^\]]*)\]\(\s*<?([^)\s>]+)>?(?:\s+\"[^\"]*\")?\s*\)")
REFERENCE = re.compile(r"^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$")
HTML_ANCHOR = re.compile(r"<a\s+(?:[^>]*\s)?(?:id|name)=\"([^\"]+)\"")
SCHEME = re.compile(r"^[a-zA-Z][a-zA-Z0-9+.-]*:")


def slug(text):
    """github-slugger와 같은 방식: 소문자화, 문장 부호·기호 제거, 공백마다 '-'."""
    text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
    text = re.sub(r"<[^>]+>", "", text).lower()
    kept = []
    for char in text:
        category = unicodedata.category(char)
        if char == " ":
            kept.append("-")
        elif char in "-_" or category[0] in "LNM":
            kept.append(char)
    return "".join(kept)


@lru_cache(maxsize=None)
def anchors(path):
    found = set()
    counts = {}
    in_fence = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        found.update(HTML_ANCHOR.findall(line))
        match = HEADING.match(line)
        if match:
            base = slug(match.group(2))
            count = counts.get(base, 0)
            counts[base] = count + 1
            found.add(base if count == 0 else f"{base}-{count}")
    return frozenset(found)


def links(path):
    in_fence = False
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if FENCE.match(line):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        stripped = INLINE_CODE.sub("", line)
        for match in LINK.finditer(stripped):
            yield number, match.group(2)
        reference = REFERENCE.match(stripped)
        if reference:
            yield number, reference.group(1)


def check(path):
    problems = []
    for number, target in links(path):
        if SCHEME.match(target):
            continue
        file_part, _, fragment = target.partition("#")
        file_part = unquote(file_part)
        if not file_part:
            destination = path
        elif file_part.startswith("/"):
            destination = ROOT / file_part.lstrip("/")
        else:
            destination = (path.parent / file_part).resolve()
        if not destination.exists():
            problems.append((number, target, "대상 없음"))
            continue
        if fragment and destination.suffix == ".md" and unquote(fragment) not in anchors(destination):
            problems.append((number, target, "앵커 없음"))
    return problems


def markdown_files(arguments):
    if arguments:
        return [Path(argument).resolve() for argument in arguments]
    listed = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "*.md"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    ).stdout.splitlines()
    return [ROOT / name for name in listed if (ROOT / name).exists()]


def main(arguments):
    files = markdown_files(arguments)
    total = 0
    for path in files:
        for number, target, reason in check(path):
            total += 1
            print(f"{path.relative_to(ROOT)}:{number}: {reason}: {target}")
    print(f"검사 파일 {len(files)}개, 문제 {total}건")
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
