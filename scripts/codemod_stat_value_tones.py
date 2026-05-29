"""
Carry-over codemod (post-Tier-8): replace the long-form tone-coloured
stat-value Tailwind class soup
    text-2xl font-(bold|semibold) text-{tone}-{600|700} dark:text-{tone}-{200|300}
            [tracking-tight] [mt-N | mb-N | other utility extras]
with the canonical `.stat-value-{tone}` utility classes (added to
index.css 2026-05-13).

Where `{tone}` ∈ {success, warning, error, info} and the light/dark
shades match (i.e. the same tone on both sides).

Why: ~40 sites across the codebase author their tone-coloured stat
values as inline Tailwind soup, bypassing the display tier (Fraunces +
tabular-nums). The Tier 4 codemod handled the neutral-primary case
(.stat-value-sm); this completes the migration for the coloured ones.

What we do NOT migrate:
  - className expressions that mix tones conditionally (e.g.
    `utilizationPct > 80 ? 'text-warning-600 ...' : 'text-success-600 ...'`).
    The codemod only operates on `className="..."` LITERAL strings,
    not on `cn(...)` calls or template literals. The conditional
    cases stay inline.
  - Cross-tone variants (e.g. text-success-600 + dark:text-info-300).
    Almost certainly a bug; left alone to be visible.
  - Different font sizes (text-3xl, text-4xl, text-xl, etc.).

Idempotent. Dry-run by default unless --apply.
"""
import re
import sys
from pathlib import Path

TONES = ('success', 'warning', 'error', 'info')

# Classes whose meaning is fully absorbed by .stat-value-{tone} — drop.
ABSORBED_BASE = {
    'text-2xl',
    'font-bold',
    'font-semibold',
    'tracking-tight',
    'tabular-nums',
}

# Light text shades we accept (text-{tone}-NN). 600 is canonical;
# 700 is sometimes used for slightly heavier emphasis. The utility
# uses 600 — migration to 700 callers loses 1 weight step, design call.
LIGHT_SHADES = ('600', '700')
DARK_SHADES  = ('200', '300')

# Match a `className="..."` literal containing the stat-value-tone soup.
PATTERN = re.compile(r'className="([^"]+)"')


def detect_tone(classes: list[str]) -> str | None:
    """Return the tone name if classes contain matching light + dark tone refs."""
    light_tones: set[str] = set()
    dark_tones: set[str] = set()
    for cls in classes:
        # text-{tone}-{600|700}
        m = re.match(r'text-(success|warning|error|info)-(600|700)$', cls)
        if m:
            light_tones.add(m.group(1))
            continue
        # dark:text-{tone}-{200|300}
        m = re.match(r'dark:text-(success|warning|error|info)-(200|300)$', cls)
        if m:
            dark_tones.add(m.group(1))
            continue
    # Must have exactly one matching tone on each side.
    common = light_tones & dark_tones
    if len(common) == 1 and len(light_tones) == 1 and len(dark_tones) == 1:
        return next(iter(common))
    return None


def should_migrate(classes: list[str]) -> str | None:
    """Return tone name if migratable; None otherwise."""
    classes_set = set(classes)
    # Must include text-2xl, exactly one weight class.
    if 'text-2xl' not in classes_set:
        return None
    if not (('font-bold' in classes_set) ^ ('font-semibold' in classes_set)):
        return None
    # Skip responsive cases.
    for cls in classes:
        if re.match(r'(sm|md|lg|xl|2xl):text-', cls):
            return None
    # Skip if any OTHER text-* size override is present (e.g. text-3xl mixed in).
    for cls in classes:
        if cls.startswith('text-') and not re.match(
            r'text-(2xl|(success|warning|error|info)-(600|700))$', cls
        ):
            return None
    return detect_tone(classes)


def migrate_classes(classes: list[str], tone: str) -> list[str]:
    """Drop absorbed + tone-text classes; prepend `stat-value-{tone}`."""
    out = [f'stat-value-{tone}']
    for cls in classes:
        if cls in ABSORBED_BASE:
            continue
        # text-{tone}-NN and dark:text-{tone}-NN absorbed.
        if re.match(r'text-(success|warning|error|info)-(600|700)$', cls):
            continue
        if re.match(r'dark:text-(success|warning|error|info)-(200|300)$', cls):
            continue
        out.append(cls)
    return out


def transform(text: str) -> tuple[str, dict[str, int]]:
    counts: dict[str, int] = {t: 0 for t in TONES}

    def sub(m: re.Match) -> str:
        classes = m.group(1).split()
        tone = should_migrate(classes)
        if tone is None:
            return m.group(0)
        new_classes = migrate_classes(classes, tone)
        counts[tone] += 1
        return f'className="{" ".join(new_classes)}"'

    return PATTERN.sub(sub, text), counts


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: codemod_stat_value_tones.py <files-or-dirs> [--apply]")
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

    grand_total: dict[str, int] = {t: 0 for t in TONES}
    changed: list[tuple[Path, dict[str, int]]] = []
    for path in targets:
        text = path.read_text(encoding='utf-8')
        new_text, counts = transform(text)
        nonzero = {k: v for k, v in counts.items() if v > 0}
        if not nonzero:
            continue
        for k, v in counts.items():
            grand_total[k] += v
        changed.append((path, nonzero))
        if apply:
            path.write_text(new_text, encoding='utf-8')

    mode = 'APPLIED' if apply else 'DRY RUN'
    total_n = sum(grand_total.values())
    print(f"[{mode}] {total_n} tone-coloured stat-value rewrites across {len(changed)} files")
    for tone in TONES:
        print(f"  {tone:<8s} : {grand_total[tone]}")
    if changed:
        print("Files touched:")
        for p, counts in changed:
            try:
                display = p.relative_to(Path.cwd())
            except ValueError:
                display = p
            summary = ", ".join(f"{k}:{v}" for k, v in sorted(counts.items()))
            print(f"    {display}  ({summary})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
