"""
Phase 9 Task B — residual stat-tile migration. Targets the variants
Tier 4 + tone-variant carry-over didn't catch: neutral-900 variants,
no-color variants (color inherited from parent), spacing+tracking
combinations.

Exact-string replacements only. We are NOT doing fuzzy matching.
Order is longest-first so prefixes don't get mangled by earlier rules.

Constraints (per Phase 9 spec):
  - Don't touch files under src/styles/, src/design-system/, or
    HeroMetricCard.tsx (those DEFINE the utilities).
  - Don't reflow JSX.
  - Don't change semantic HTML.
"""
import sys
from pathlib import Path

REPLACEMENTS = [
    # Color-bearing variants where colors are equivalent to primary-900/neutral-50.
    ('text-2xl font-bold text-neutral-900 mt-1.5 tracking-tight dark:text-neutral-50', 'stat-value-sm mt-1.5'),
    ('text-2xl font-bold text-primary-900 dark:text-neutral-100', 'stat-value-sm'),
    ('text-2xl font-bold text-neutral-900 dark:text-neutral-50',  'stat-value-sm'),
    # No-color variants — color inherited from parent (typically primary text).
    ('text-2xl font-bold mt-1 tracking-tight',  'stat-value-sm mt-1'),
    ('text-2xl font-bold tracking-tight mt-3',  'stat-value-sm mt-3'),
    ('text-2xl font-bold tracking-tight mt-1',  'stat-value-sm mt-1'),
]

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
