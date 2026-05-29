"""
Phase 10 follow-up — migrate top-level page stat-strip grids to <StatStrip>.

Per-file: locate the exact opening div for the strip, find its matching
</div> via bracket balance, replace both with <StatStrip>/</StatStrip>.
Adds StatStrip import if not present.
"""
import re
import sys
from pathlib import Path

# Each entry: (file_path, exact_opening_string)
TARGETS = [
    ('frontend/src/pages/DashboardPage.tsx',
     '<div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-6">'),
    ('frontend/src/pages/CreditFacilitiesPage.tsx',
     '<div className="grid grid-cols-5 gap-4 animate-fade-in" style={{ animationDelay: \'0.1s\' }}>'),
    ('frontend/src/pages/BalanceAggregationPage.tsx',
     '<div className="grid grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: \'0.1s\' }}>'),
    ('frontend/src/pages/IntercompanyDashboardPage.tsx',
     '<div className="grid grid-cols-2 md:grid-cols-4 gap-4">'),
    ('frontend/src/pages/PhysicalAccountsPage.tsx',
     '<div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-5 gap-4">'),
]

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


def ensure_statstrip_import(text: str) -> str:
    if re.search(
        r"import\s+\{[^}]*\bStatStrip\b",
        text,
    ):
        return text
    # Add a new import after the existing Page import line if any, otherwise
    # after the last 'from X' import.
    page_import_re = re.compile(
        r"(import\s+\{\s*Page\s*\}\s+from\s+['\"][^'\"]+['\"]\s*;?\s*\n)"
    )
    m = page_import_re.search(text)
    if m:
        insert = "import { StatStrip } from '../components/layout/StatStrip';\n"
        return text[:m.end()] + insert + text[m.end():]
    # Fallback — prepend.
    return "import { StatStrip } from '../components/layout/StatStrip';\n" + text


def transform_file(path: Path, open_pattern: str) -> tuple[bool, str]:
    text = path.read_text(encoding='utf-8')
    open_idx = text.find(open_pattern)
    if open_idx < 0:
        return False, f'opening not found'
    close_idx = find_matching_close(text, open_idx)
    if close_idx is None:
        return False, f'matching close not found'
    # Replace close first
    text = text[:close_idx] + '</StatStrip>' + text[close_idx + len('</div>'):]
    # Replace open
    text = text[:open_idx] + '<StatStrip>' + text[open_idx + len(open_pattern):]
    text = ensure_statstrip_import(text)
    path.write_text(text, encoding='utf-8')
    return True, ''


def main() -> int:
    success = 0
    for path_str, pattern in TARGETS:
        p = Path(path_str)
        ok, msg = transform_file(p, pattern)
        if ok:
            success += 1
            print(f'  {path_str}: migrated')
        else:
            print(f'  {path_str}: SKIPPED ({msg})')
    print(f'\nTotal: {success}/{len(TARGETS)} files migrated')
    return 0


if __name__ == '__main__':
    sys.exit(main())
