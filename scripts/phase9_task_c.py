"""
Phase 9 Task C — page/section title migration.

Replace hand-rolled `text-(lg|xl) font-semibold text-primary-900 ...`
headings with `.section-title` (Fraunces serif, semantic display tier).
Replace H1 hand-rolls with `.page-title` (only the small handful that
exist; most pages already get their title from the Layout header).

Exact-string replacements only. Order: longer-prefix-first so spacing
trailers get captured correctly.

Constraints (per Phase 9 spec):
  - Don't touch files under src/styles/, src/design-system/, or
    HeroMetricCard.tsx.
  - Don't change semantic HTML — <h1> stays <h1>, <h2> stays <h2>.
  - Don't reflow JSX.
  - Preserve any `mb-N` / layout extras as trailing classes.

SKIP categories (left for future tiered work):
  - Coloured section titles (`text-lg font-semibold text-success-600 ...`)
    — these aren't neutral section heads; they'd need `.section-title-tone`
    variants which is out of Phase 9 scope.
  - Muted variants (`text-xl font-semibold text-neutral-700 ...`) — could
    be intentional de-emphasis; leave for manual review.
"""
import sys
from pathlib import Path

# Order: longer string-with-extras first so prefix matches don't shadow longer ones.
REPLACEMENTS = [
    # Page H1 hand-rolls (preserve layout extras).
    ('text-2xl font-bold text-neutral-900 flex items-center gap-2', 'page-title flex items-center gap-2'),
    # Section titles with spacing trailers.
    ('text-lg font-semibold text-primary-900 dark:text-neutral-50 mb-2', 'section-title mb-2'),
    ('text-lg font-semibold text-primary-900 mb-6 dark:text-neutral-50', 'section-title mb-6'),
    ('text-lg font-semibold text-primary-900 mb-4 dark:text-neutral-50', 'section-title mb-4'),
    ('text-lg font-semibold text-primary-900 mb-3 dark:text-neutral-50', 'section-title mb-3'),
    ('text-lg font-semibold text-primary-900 mb-2 dark:text-neutral-50', 'section-title mb-2'),
    ('text-lg font-semibold text-primary-900 mb-1 dark:text-neutral-50', 'section-title mb-1'),
    ('text-xl font-semibold text-neutral-900 mb-2 dark:text-neutral-50', 'section-title mb-2'),
    ('text-xl font-semibold text-primary-900 mb-2 dark:text-neutral-50', 'section-title mb-2'),
    ('text-xl font-semibold text-primary-900 mb-4 dark:text-neutral-50', 'section-title mb-4'),
    # Canonical section titles (no spacing).
    ('text-lg font-semibold text-primary-900 dark:text-neutral-50', 'section-title'),
    ('text-lg font-semibold text-neutral-900 dark:text-neutral-50', 'section-title'),
    ('text-xl font-semibold text-primary-900 dark:text-neutral-50', 'section-title'),
    # Bare patterns (color inherited from parent).
    ('text-2xl font-semibold',  'page-title'),     # only matches when not preceded by another size class — the responsive `sm:text-2xl` form survives because it doesn't START with text-2xl.
]

# Skip patterns where the string is ambiguous and migration would damage intent.
# We DON'T migrate these:
#   - text-lg/xl font-semibold text-{success|warning|error|info}-600 ...
#   - text-xl font-semibold text-neutral-700 ... (muted variant)
# These are left as-is.

SKIP_FRAGMENTS = ['HeroMetricCard', 'design-system', '/styles/', '\\styles\\']

def is_skipped(path: Path) -> bool:
    s = str(path)
    return any(frag in s for frag in SKIP_FRAGMENTS)

def main() -> int:
    target_dirs = [Path('frontend/src/pages'), Path('frontend/src/components')]
    total = 0
    per_file = []
    for d in target_dirs:
        for path in d.rglob('*.tsx'):
            if is_skipped(path):
                continue
            text = path.read_text(encoding='utf-8')
            original = text
            count = 0
            for old, new in REPLACEMENTS:
                n = text.count(old)
                if n:
                    text = text.replace(old, new)
                    count += n
            if count and text != original:
                path.write_text(text, encoding='utf-8')
                per_file.append((str(path.relative_to('frontend')), count))
                total += count
    print(f'Total: {total} rewrites')
    for f, c in per_file:
        print(f'  {f}: {c}')
    return 0

if __name__ == '__main__':
    sys.exit(main())
