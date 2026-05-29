"""
Phase 9 Task D — form-label migration.

Replace hand-rolled `text-sm font-medium text-neutral-700 dark:text-neutral-200`
patterns with the new `.field-label` utility. ~300 sites across the codebase.

The `.field-label` utility already includes `text-sm font-medium
text-neutral-700 dark:text-neutral-200 font-sans line-height 1.4` — so any
hand-roll matching that class string can be replaced verbatim.

Also includes the eyebrow patterns that should become `.label` and the
microlabel patterns that should become `.overline` or `.label-cased`.

Constraints (per Phase 9 spec):
  - Don't touch files under src/styles/, src/design-system/, or
    HeroMetricCard.tsx.
  - Preserve `block`, `mb-N` (which I keep as trailing utilities).
  - Don't change semantic HTML.
"""
import sys
from pathlib import Path

# Order: longer match strings first (with spacing trailers) so the no-trailer
# version doesn't shadow them.
REPLACEMENTS = [
    # Form-field labels with `block` + `mb-N` spacing (the dominant shape).
    ('block text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5', 'field-label block mb-1.5'),
    ('block text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1',   'field-label block mb-1'),
    ('block text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-2',   'field-label block mb-2'),
    ('block text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-3',   'field-label block mb-3'),
    ('block text-sm font-medium text-neutral-700 mb-1 dark:text-neutral-200',   'field-label block mb-1'),
    ('block text-sm font-medium text-neutral-700 mb-2 dark:text-neutral-200',   'field-label block mb-2'),

    # Without explicit `block` (parents apply display:block, e.g. inline label):
    ('text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5', 'field-label mb-1.5'),
    ('text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1',   'field-label mb-1'),
    ('text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-2',   'field-label mb-2'),
    ('text-sm font-medium text-neutral-700 dark:text-neutral-200 mb-3',   'field-label mb-3'),
    ('text-sm font-medium text-neutral-700 mb-1 dark:text-neutral-200',   'field-label mb-1'),
    ('text-sm font-medium text-neutral-700 mb-2 dark:text-neutral-200',   'field-label mb-2'),

    # Canonical form-label with no spacing trailer:
    ('block text-sm font-medium text-neutral-700 dark:text-neutral-200', 'field-label block'),
    ('text-sm font-medium text-neutral-700 dark:text-neutral-200',       'field-label'),

    # ----- Eyebrow / uppercase variants → existing `.label` -----
    ('text-xs font-medium text-neutral-500 uppercase tracking-wide dark:text-neutral-400',  'label'),
    ('text-xs font-medium text-neutral-500 uppercase tracking-wider dark:text-neutral-400', 'label'),
    ('text-xs font-medium text-neutral-500 uppercase tracking-wide',                        'label'),
    ('text-xs font-medium text-neutral-500 uppercase tracking-wider',                       'label'),
    ('text-xs font-semibold text-neutral-600 dark:text-neutral-300 uppercase tracking-wider', 'label'),
    ('text-xs font-semibold text-neutral-600 uppercase tracking-wider dark:text-neutral-300', 'label'),

    # ----- 10px overline variants → `.overline` (uppercase) -----
    ('text-[10px] font-medium uppercase tracking-wide text-neutral-500 dark:text-neutral-400',  'overline'),
    ('text-[10px] font-medium uppercase tracking-wider text-neutral-500 dark:text-neutral-400', 'overline'),
    ('text-[10px] font-medium uppercase tracking-wide text-neutral-400 dark:text-neutral-500',  'overline'),
    ('text-[10px] uppercase tracking-wider text-neutral-500 dark:text-neutral-400',             'overline'),

    # ----- 10px non-uppercase micro labels → `.label-cased` -----
    # Plain 10px neutral muted text — non-uppercase, used for badge counts / status copy.
    ('text-[10px] font-medium text-neutral-500 dark:text-neutral-400', 'label-cased'),
    ('text-[10px] text-neutral-500 dark:text-neutral-400',              'label-cased'),
]

SKIP_FRAGMENTS = ['HeroMetricCard', 'design-system', '/styles/', '\\styles\\']

def is_skipped(path: Path) -> bool:
    s = str(path)
    return any(frag in s for frag in SKIP_FRAGMENTS)

def main() -> int:
    target_dirs = [Path('frontend/src/pages'), Path('frontend/src/components')]
    total = 0
    per_file = []
    per_rule = [0] * len(REPLACEMENTS)
    for d in target_dirs:
        for path in d.rglob('*.tsx'):
            if is_skipped(path):
                continue
            text = path.read_text(encoding='utf-8')
            original = text
            count = 0
            for i, (old, new) in enumerate(REPLACEMENTS):
                n = text.count(old)
                if n:
                    text = text.replace(old, new)
                    count += n
                    per_rule[i] += n
            if count and text != original:
                path.write_text(text, encoding='utf-8')
                per_file.append((str(path.relative_to('frontend')), count))
                total += count
    print(f'Total: {total} rewrites across {len(per_file)} files')
    print('By rule:')
    for i, ((old, new), n) in enumerate(zip(REPLACEMENTS, per_rule)):
        if n:
            print(f'  [{n:>4}] {old[:70]:<70} -> {new}')
    return 0

if __name__ == '__main__':
    sys.exit(main())
