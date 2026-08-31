"""
Phase 10 follow-up — migrate the remaining 35 pages with
`<div className="space-y-6 animate-page-enter">` root to <Page>.

Uses the same bracket-balance walker as phase10_task_d_apply.py.
Reports any file that produces an unbalanced match (manual review).

The animation is now handled by <main> in Layout.tsx; <Page> only
applies space-y-6. So removing the animate-page-enter class on the
page root is the desired outcome.
"""
import re
import sys
from pathlib import Path

OPEN_PATTERN = '<div className="space-y-6 animate-page-enter">'

DIV_OPEN_RE = re.compile(r'<div\b[^>]*>')
DIV_CLOSE_RE = re.compile(r'</div>')


def find_matching_close(text: str, open_start: int) -> int | None:
    m = DIV_OPEN_RE.match(text, open_start)
    if not m:
        return None
    cursor = m.end()
    depth = 1

    while cursor < len(text):
        next_open = DIV_OPEN_RE.search(text, cursor)
        next_close = DIV_CLOSE_RE.search(text, cursor)
        if next_close is None:
            return None
        if next_open is None or next_close.start() < next_open.start():
            depth -= 1
            if depth == 0:
                return next_close.start()
            cursor = next_close.end()
        else:
            depth += 1
            cursor = next_open.end()
    return None


def ensure_page_import(text: str) -> str:
    if re.search(
        r"import\s+\{[^}]*\bPage\b[^}]*\}\s+from\s+['\"][^'\"]*components/layout(?:/Page)?['\"]",
        text,
    ):
        return text
    lines = text.splitlines(keepends=True)
    last_from_idx = -1
    in_multiline = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith('//'):
            continue
        if stripped.startswith('import '):
            in_multiline = '{' in line and '}' not in line
            if re.search(r"from\s+['\"][^'\"]+['\"]\s*;?\s*$", stripped):
                last_from_idx = i
            continue
        if in_multiline:
            if re.search(r"\}\s+from\s+['\"][^'\"]+['\"]\s*;?\s*$", stripped):
                last_from_idx = i
                in_multiline = False
            continue
        break
    if last_from_idx >= 0:
        lines.insert(last_from_idx + 1, "import { Page } from '../components/layout/Page';\n")
        return ''.join(lines)
    return "import { Page } from '../components/layout/Page';\n" + text


def transform_file(path: Path) -> tuple[bool, int, list[int]]:
    text = path.read_text(encoding='utf-8')
    original = text

    starts: list[int] = []
    idx = 0
    while True:
        i = text.find(OPEN_PATTERN, idx)
        if i < 0:
            break
        starts.append(i)
        idx = i + 1

    count = 0
    unbalanced: list[int] = []
    for start in reversed(starts):
        close_start = find_matching_close(text, start)
        if close_start is None:
            unbalanced.append(start)
            continue
        text = text[:close_start] + '</Page>' + text[close_start + len('</div>'):]
        text = text[:start] + '<Page>' + text[start + len(OPEN_PATTERN):]
        count += 1

    if count > 0:
        text = ensure_page_import(text)

    if text != original:
        path.write_text(text, encoding='utf-8')
        return True, count, unbalanced
    return False, 0, unbalanced


def main() -> int:
    pages_dir = Path('frontend/src/pages')
    total = 0
    changed_files = 0
    unbalanced_files: list[Path] = []
    skipped_files: list[Path] = []

    for path in sorted(pages_dir.rglob('*.tsx')):
        text = path.read_text(encoding='utf-8')
        if OPEN_PATTERN not in text:
            continue
        changed, n, unbalanced = transform_file(path)
        if changed:
            total += n
            changed_files += 1
            print(f'  {path.relative_to(Path("frontend"))}: {n} replacement(s)')
        if unbalanced:
            unbalanced_files.append(path)
            print(f'  UNBALANCED {path.relative_to(Path("frontend"))}: {len(unbalanced)} match(es) skipped')

    print(f'\nTotal: {total} replacements across {changed_files} files')
    if unbalanced_files:
        print(f'\nFiles needing manual migration ({len(unbalanced_files)}):')
        for p in unbalanced_files:
            print(f'  {p.relative_to(Path("frontend"))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
