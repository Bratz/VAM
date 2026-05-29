"""
Phase 10 follow-up v2 — handle the 12 files the v1 codemod couldn't.

These pages have nested JSX (template literals containing `<div>`,
conditional renders, etc.) that confused the simple bracket counter.
Strategy: anchor on the OPEN and find the matching CLOSE by walking
the AST a different way — count *attribute-less* `<div>` and `</div>`
only, ignoring lines inside `{` JS expressions when we can detect them.

A simpler reliable heuristic for these files: the matching `</div>`
is the LAST `</div>` before the `);\n};` (end of the return → component
end) pattern. We find the end-of-component marker, walk backward to
the nearest `</div>`, and that's our match.

This relies on a single-return-statement component, which is true for
all 12 files (they're page components, not hooks/utilities).
"""
import re
import sys
from pathlib import Path

OPEN_PATTERN = '<div className="space-y-6 animate-page-enter">'

# Match end of a component: `);` then optional whitespace, then `};`
# (this is the standard React.FC pattern used everywhere here).
END_OF_COMPONENT_RE = re.compile(r'\)\s*;\s*\n\s*\}\s*;')


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


def transform_file(path: Path) -> tuple[bool, int]:
    """For each OPEN_PATTERN, find the next end-of-component marker AFTER
    the open. The matching `</div>` is the last `</div>` between the open
    and that end-of-component."""
    text = path.read_text(encoding='utf-8')
    original = text

    # Walk every occurrence
    count = 0
    while True:
        open_start = text.find(OPEN_PATTERN)
        if open_start < 0:
            break

        # Find the next end-of-component AFTER the open.
        m = END_OF_COMPONENT_RE.search(text, open_start)
        if not m:
            print(f'  {path}: no end-of-component marker after open at {open_start}')
            return False, 0

        # Find the last `</div>` between open and the end-of-component.
        # We walk from end backwards.
        region_end = m.start()
        last_close = text.rfind('</div>', open_start, region_end)
        if last_close < 0:
            print(f'  {path}: no closing </div> found in region')
            return False, 0

        # Substitute (close first, then open, working from later offset to earlier).
        text = text[:last_close] + '</Page>' + text[last_close + len('</div>'):]
        text = text[:open_start] + '<Page>' + text[open_start + len(OPEN_PATTERN):]
        count += 1

    if count > 0:
        text = ensure_page_import(text)

    if text != original:
        path.write_text(text, encoding='utf-8')
        return True, count
    return False, 0


def main() -> int:
    pages_dir = Path('frontend/src/pages')
    total = 0
    changed_files = 0
    failed_files: list[Path] = []

    for path in sorted(pages_dir.rglob('*.tsx')):
        text = path.read_text(encoding='utf-8')
        if OPEN_PATTERN not in text:
            continue
        changed, n = transform_file(path)
        if changed:
            total += n
            changed_files += 1
            print(f'  {path.relative_to(Path("frontend"))}: {n} replacement(s)')
        else:
            failed_files.append(path)
            print(f'  FAILED {path.relative_to(Path("frontend"))}')

    print(f'\nTotal: {total} replacements across {changed_files} files')
    if failed_files:
        print(f'\nFailed ({len(failed_files)}):')
        for p in failed_files:
            print(f'  {p.relative_to(Path("frontend"))}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
