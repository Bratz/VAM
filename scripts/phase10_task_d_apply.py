"""
Phase 10 Task D — apply the <div className="space-y-6 animate-page-enter">
→ <Page> migration on 8 representative pages.

Strategy: locate every opening pattern in each file, walk forward through
the source tracking <div> depth, find the matching </div>, replace both
opening and closing tags. JSX-naive but tolerant of nested divs because
we just count opens and closes by literal token match.

Also adds the Page import to each file.
"""
import re
import sys
from pathlib import Path

TARGET_FILES = [
    'frontend/src/pages/DashboardPage.tsx',
    'frontend/src/pages/AccountsPage.tsx',
    'frontend/src/pages/IntercompanyDashboardPage.tsx',
    'frontend/src/pages/CreditAgreementsPage.tsx',
    'frontend/src/pages/CreditFacilitiesPage.tsx',
    'frontend/src/pages/CashConcentrationPage.tsx',
    'frontend/src/pages/BalanceAggregationPage.tsx',
    'frontend/src/pages/PhysicalAccountsPage.tsx',
]

OPEN_PATTERN = '<div className="space-y-6 animate-page-enter">'

# Find <div ... > and </div> as separate JSX tokens (naive regex; doesn't
# account for div inside string literals or comments, but on these files
# the assumption holds).
DIV_OPEN_RE = re.compile(r'<div\b[^>]*>')
DIV_CLOSE_RE = re.compile(r'</div>')


def find_matching_close(text: str, open_start: int) -> int | None:
    """Given the start index of an open <div ...> tag, find the matching
    </div> by counting depth. Returns the start index of the matching
    closing tag, or None if unbalanced."""
    # Find end of the opening tag.
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
    if re.search(r"import\s+\{[^}]*\bPage\b[^}]*\}\s+from\s+['\"][^'\"]*components/layout(?:/Page)?['\"]", text):
        return text
    # Add a new import after the last existing import-statement-with-from line.
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
    text = path.read_text(encoding='utf-8')
    original = text

    # Walk through every occurrence of the OPEN_PATTERN, find its match,
    # replace both ends. We work backwards (last match first) so earlier
    # indices remain valid as we edit.
    starts: list[int] = []
    idx = 0
    while True:
        i = text.find(OPEN_PATTERN, idx)
        if i < 0:
            break
        starts.append(i)
        idx = i + 1

    count = 0
    for start in reversed(starts):
        close_start = find_matching_close(text, start)
        if close_start is None:
            print(f'  WARN: unbalanced <div> starting at offset {start} in {path}')
            continue
        # Replace closing tag first (so its index stays valid).
        text = text[:close_start] + '</Page>' + text[close_start + len('</div>'):]
        # Replace opening tag.
        text = text[:start] + '<Page>' + text[start + len(OPEN_PATTERN):]
        count += 1

    if count > 0:
        text = ensure_page_import(text)

    if text != original:
        path.write_text(text, encoding='utf-8')
        return True, count
    return False, 0


def main() -> int:
    total = 0
    for fname in TARGET_FILES:
        p = Path(fname)
        if not p.exists():
            print(f'MISSING: {fname}')
            continue
        changed, n = transform_file(p)
        if changed:
            print(f'  {fname}: {n} replacements')
            total += n
        else:
            print(f'  {fname}: (no changes)')
    print(f'\nTotal: {total} replacements across {len(TARGET_FILES)} files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
