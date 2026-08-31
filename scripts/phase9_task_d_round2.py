"""Phase 9 Task D round-2: catch form-label variants missed by the
first pass (mb-N AFTER dark:, no-dark-variant inline labels)."""
import sys
from pathlib import Path

REPLACEMENTS = [
    ('block text-sm font-medium text-neutral-700 mb-1.5 dark:text-neutral-200',                 'field-label block mb-1.5'),
    ('block text-sm font-medium text-neutral-700 mb-3 dark:text-neutral-200',                   'field-label block mb-3'),
    ('text-sm font-medium text-neutral-700 mb-3 dark:text-neutral-200',                         'field-label mb-3'),
    ('block text-sm font-medium text-neutral-700 mb-1',                                          'field-label block mb-1'),
    ('block text-sm font-medium text-neutral-700',                                               'field-label block'),
    ('text-sm font-medium text-neutral-700 flex items-center gap-2 dark:text-neutral-200',      'field-label flex items-center gap-2'),
]
SKIP_FRAGMENTS = ['HeroMetricCard', 'design-system']
SKIP_PATHS = ['styles']

def is_skipped(path: Path) -> bool:
    s = str(path)
    if any(f in s for f in SKIP_FRAGMENTS):
        return True
    return any(p in path.parts for p in SKIP_PATHS)

def main() -> int:
    total = 0
    for dir_str in ['frontend/src/pages', 'frontend/src/components']:
        for path in Path(dir_str).rglob('*.tsx'):
            if is_skipped(path):
                continue
            text = path.read_text(encoding='utf-8')
            n = 0
            for old, new in REPLACEMENTS:
                c = text.count(old)
                if c:
                    text = text.replace(old, new)
                    n += c
            if n:
                path.write_text(text, encoding='utf-8')
                total += n
    print(f'Round 2: {total} rewrites')
    return 0

if __name__ == '__main__':
    sys.exit(main())
