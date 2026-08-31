"""
Tier 4 codemod: replace the long-form Tailwind class soup
    text-2xl font-(bold|semibold) text-primary-900 dark:text-neutral-50
            [tracking-tight] [mt-N | mb-N | other utility extras]
with the canonical `.stat-value-sm` utility class (defined in index.css).

Why: 40+ neutral primary-text stat-value sites still author their styling
inline. The display tier exists (.stat-value, .stat-value-sm, .page-title,
.section-title) but pages haven't migrated. The utility:
  - applies Fraunces (display serif) at 24px / weight 600
  - tabular-nums + tnum + ss01 features
  - tight letter-spacing (-0.02em) — so `tracking-tight` is redundant
  - text-primary-900 / dark:text-neutral-50

Visible changes for callers:
  - Font family: Bricolage sans -> Fraunces serif (intentional — stat
    values across the platform now read as the display tier)
  - Weight: font-bold (700) -> 600 (intentional — design system spec)
  - Letter-spacing: -0.025em -> -0.02em (de minimis)

What we do NOT migrate:
  - Coloured stat values (text-success-600 etc.) — semantic state, not
    neutral primary. A future tier may add .stat-value-{tone} variants.
  - Responsive cases (text-xl sm:text-2xl) — intentionally shrink on
    small screens, and .stat-value-sm has no responsive variant.
  - Anything inside a className expression that's a function call,
    template literal, or cn(...) helper — too risky to rewrite in those
    contexts; leave for manual.

Idempotent. Dry-run by default unless --apply.
"""
import re
import sys
from pathlib import Path

# Required class fragments. Order may vary inside the className string;
# we accept any permutation.
REQUIRED = ('text-2xl', 'text-primary-900', 'dark:text-neutral-50')
WEIGHT = ('font-bold', 'font-semibold')

# Classes whose meaning is fully absorbed by .stat-value-sm — drop.
ABSORBED = {
    'text-2xl', 'text-primary-900', 'dark:text-neutral-50',
    'font-bold', 'font-semibold',
    'tracking-tight',  # .stat-value-sm sets letter-spacing internally
    'tabular-nums',     # .stat-value-sm sets font-variant-numeric internally
}

# Look for className="..." string literals (NOT className={...} or cn(...) calls).
# This is intentionally narrow — JS expressions are too varied to rewrite safely.
PATTERN = re.compile(r'className="([^"]+)"')


def should_migrate(classes: list[str]) -> bool:
    """Decide whether this className list is a long-form stat-value to migrate."""
    classes_set = set(classes)
    if not all(req in classes_set for req in REQUIRED):
        return False
    # Must include exactly one weight class.
    if not any(w in classes_set for w in WEIGHT):
        return False
    # Skip responsive cases — text-xl sm:text-2xl etc. shrinks on small screens.
    # If we see any sm:/md:/lg:/xl: text-* override, leave it alone.
    for cls in classes:
        if re.match(r'(sm|md|lg|xl|2xl):text-', cls):
            return False
    # Skip if any text-* variant other than `text-2xl` and `text-primary-900` is
    # present (e.g. `text-success-600` mixed in — colored stat).
    for cls in classes:
        if cls.startswith('text-') and cls not in {'text-2xl', 'text-primary-900'}:
            # Allow `dark:text-neutral-50` because that's a different prefix
            return False
    return True


def migrate_classes(classes: list[str]) -> list[str]:
    """Drop absorbed classes; prepend `stat-value-sm`. Preserve order of remaining."""
    out = ['stat-value-sm']
    for cls in classes:
        if cls in ABSORBED:
            continue
        # `dark:text-neutral-50` is absorbed even though it's not in our literal set.
        if cls == 'dark:text-neutral-50':
            continue
        out.append(cls)
    return out


def transform(text: str) -> tuple[str, int]:
    count = 0

    def sub(m: re.Match) -> str:
        nonlocal count
        classes = m.group(1).split()
        if not should_migrate(classes):
            return m.group(0)
        new_classes = migrate_classes(classes)
        count += 1
        return f'className="{" ".join(new_classes)}"'

    return PATTERN.sub(sub, text), count


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: codemod_stat_value.py <files-or-dirs> [--apply]")
        return 2

    apply = '--apply' in argv
    paths_arg = [a for a in argv[1:] if not a.startswith('--')]

    targets: list[Path] = []
    for arg in paths_arg:
        p = Path(arg).resolve()
        if p.is_file():
            targets.append(p)
        elif p.is_dir():
            for child in p.rglob('*'):
                if child.is_file() and child.suffix in ('.tsx', '.ts'):
                    if any(part in {'node_modules', 'dist', 'build', '.next'} for part in child.parts):
                        continue
                    targets.append(child)

    total = 0
    changed: list[tuple[Path, int]] = []
    for path in targets:
        text = path.read_text(encoding='utf-8')
        new_text, count = transform(text)
        if count > 0:
            changed.append((path, count))
            total += count
            if apply:
                path.write_text(new_text, encoding='utf-8')

    mode = 'APPLIED' if apply else 'DRY RUN'
    print(f"[{mode}] {total} long-form stat-value rewrites across {len(changed)} files")
    for p, c in changed:
        try:
            display = p.relative_to(Path.cwd())
        except ValueError:
            display = p
        print(f"  {display}  ({c})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
