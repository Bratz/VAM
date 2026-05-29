"""
Tier 3 codemod: migrate the canonical "rounded medallion + tinted icon"
pattern to the new <StatusIconBadge> component.

The pattern (with allowed variations):

    <div className="w-{10|12} h-{10|12} rounded-{lg|xl} bg-{tone}-{50|100}
                    [dark:bg-{tone}-500/{10|20|30}]
                    flex items-center justify-center [extra utility classes]">
      <{IconName} className="w-{4|5|6} h-{4|5|6} text-{tone}-{600|700}
                              [dark:text-{tone}-{200|300}]" />
    </div>

Migration target:

    <StatusIconBadge tone={tone} icon={IconName} [size="lg"] [subtle] />

Strategy:
  - Match medallions with semantic tones (success / warning / error /
    info / primary / neutral / accent). Skip any with raw chromatic
    tones (purple, cyan, pink, emerald, indigo, teal) — those are
    intentional categorical palettes outside the canonical 7.
  - Match only the COMMON shape. Anything with extra inner children,
    nested elements, or unusual attribute orders is left for manual
    migration.
  - Detect `subtle` variant from bg-{tone}-50 (vs bg-{tone}-100).
  - Detect `size` from the box dimension: 10 -> md (default, omitted),
    12 -> lg, 8 -> sm.
  - Detect `rounded` from the radius class: xl is default (omitted),
    lg -> rounded="lg".
  - Detect `className` extras — anything between `justify-center` and
    `">` other than whitespace is passed through.
  - Track which files needed a fresh `StatusIconBadge` import and add
    it to the import list if not already present (best-effort string
    rewrite).

Dry-run by default unless --apply.
"""
import re
import sys
from pathlib import Path

SEMANTIC_TONES = ('success', 'warning', 'error', 'info', 'primary', 'neutral', 'accent')

# Multi-line regex matching the canonical medallion shape.
# Notes on the design:
#   - We allow any order of `bg-...` and `dark:bg-...` because pages do both.
#   - We allow optional extra utility classes after `justify-center` (e.g.
#     `shrink-0`, `mt-1`) and capture them so they pass through unchanged.
#   - We require the icon's tone family to MATCH the medallion's tone family.
#     Mixed-tone medallions are unusual enough that we leave them for manual.
#   - We allow whitespace flexibility but require the icon-only child.
MEDALLION_PATTERN = re.compile(
    r'''
    <div\s+className="
        (?P<pre1>\s*)
        w-(?P<wbox>10|12|8)\s+h-(?P=wbox)\s+
        rounded-(?P<radius>lg|xl)\s+
        # First colour declaration — either bg- or dark:bg- can come first.
        (?:
            bg-(?P<tone>success|warning|error|info|primary|neutral|accent)-(?P<bg_shade>50|100)
            (?:\s+dark:bg-(?P=tone)-(?:500/(?:10|20|30)|700|800|900))?
            |
            dark:bg-(?P<tone2>success|warning|error|info|primary|neutral|accent)-(?:500/(?:10|20|30)|700|800|900)
            \s+bg-(?P=tone2)-(?P<bg_shade2>50|100)
        )
        \s+flex\s+items-center\s+justify-center
        (?P<extras>(?:\s+[A-Za-z0-9\-/\[\]:.]+)*)
        \s*">\s*
        <(?P<icon>[A-Z][A-Za-z0-9]+)\s+className="
            w-(?P<wicon>4|5|6)\s+h-(?P=wicon)\s+
            text-(?P<itone>[a-z]+)-\d+
            # Dark-text tone may differ from the light tone — for `primary`
            # medallions the icon is often `dark:text-neutral-200` so it reads
            # cleanly on the dark navy surface. Accept any palette name here.
            (?:\s+dark:text-[a-z]+-\d+)?
        "\s*/>\s*
    </div>
    ''',
    re.VERBOSE,
)


def size_from_box(wbox: str) -> str | None:
    return {'8': 'sm', '12': 'lg'}.get(wbox)  # None -> md (omit attr)


def build_replacement(m: re.Match) -> str | None:
    tone = m.group('tone') or m.group('tone2')
    itone = m.group('itone')
    if tone != itone:
        # Mixed tones — leave for manual.
        return None

    bg_shade = m.group('bg_shade') or m.group('bg_shade2')
    radius = m.group('radius')
    wbox = m.group('wbox')
    icon = m.group('icon')
    extras = (m.group('extras') or '').strip()

    parts = [f'tone="{tone}"', f'icon={{{icon}}}']

    size = size_from_box(wbox)
    if size:
        parts.append(f'size="{size}"')

    if radius != 'xl':
        parts.append(f'rounded="{radius}"')

    if bg_shade == '50':
        parts.append('subtle')

    if extras:
        parts.append(f'className="{extras}"')

    return f'<StatusIconBadge {" ".join(parts)} />'


def ensure_import(text: str) -> str:
    """Add `StatusIconBadge` to an existing barrel import or add a fresh line.

    Handles three cases:
      1. Already imported — no-op.
      2. Existing `{ ... } from '<any-path-ending-in /ui>'` line — append to it.
      3. None of the above — insert a new import line AFTER the last `from '...'`
         line in the file's import block. We must look for the closing `from`
         line because multi-line `import {\n  A,\n  B\n} from 'lucide-react';`
         imports otherwise trap a naive line-by-line scan into the middle of
         the block.
    """
    # 1. Already imported.
    if 'StatusIconBadge' in text and re.search(r"import\s+\{[^}]*\bStatusIconBadge\b", text):
        return text

    # 2. Extend an existing `from '<path-ending-in /ui>'` import. Matches both
    #    `'../components/ui'` and `'../ui'` (and the rare `'./ui'`).
    pattern = re.compile(
        r"(import\s+\{)([^}]*)(\}\s+from\s+['\"](?:\.{1,2}/)*(?:[\w\-]+/)*ui(?:/index)?['\"])"
    )

    def add(m: re.Match) -> str:
        names = m.group(2)
        if 'StatusIconBadge' in names:
            return m.group(0)
        sep = ',' if names.strip() and not names.rstrip().endswith(',') else ''
        return f"{m.group(1)}{names}{sep} StatusIconBadge {m.group(3)}"

    new_text, n = pattern.subn(add, text, count=1)
    if n > 0:
        return new_text

    # 3. Fresh import. Insert AFTER the last `from '...'` line in the file's
    #    initial import block. Walk lines top-to-bottom; track the position of
    #    the last line ending with `from '...';` (possibly with trailing
    #    comment); stop scanning at the first non-import, non-comment,
    #    non-empty line. This correctly handles multi-line imports.
    insert = "import { StatusIconBadge } from '../components/ui';\n"
    lines = text.splitlines(keepends=True)
    last_from_idx = -1
    in_multiline = False
    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped or stripped.startswith('//') or stripped.startswith('/*') or stripped.startswith('*'):
            continue
        # Are we still in the import block? Allow lines that start with `import`
        # OR continuation lines (when in_multiline is True).
        if stripped.startswith('import '):
            in_multiline = '{' in line and '}' not in line
            if re.search(r"from\s+['\"][^'\"]+['\"]\s*;?\s*$", stripped):
                last_from_idx = i
            continue
        if in_multiline:
            # Continue inside the multiline import until we hit the closing `}`.
            if re.search(r"\}\s+from\s+['\"][^'\"]+['\"]\s*;?\s*$", stripped):
                last_from_idx = i
                in_multiline = False
            continue
        # First non-import, non-comment, non-empty line. Stop.
        break

    if last_from_idx >= 0:
        lines.insert(last_from_idx + 1, insert)
        return ''.join(lines)
    # Fallback — prepend.
    return insert + text


def transform(text: str) -> tuple[str, int]:
    count = 0

    def sub(m: re.Match) -> str:
        nonlocal count
        replacement = build_replacement(m)
        if replacement is None:
            return m.group(0)  # leave unchanged
        count += 1
        return replacement

    new_text = MEDALLION_PATTERN.sub(sub, text)
    if count > 0:
        new_text = ensure_import(new_text)
    return new_text, count


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: codemod_status_icon_badge.py <files-or-dirs> [--apply]")
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
    print(f"[{mode}] {total} medallion replacements across {len(changed)} files")
    for p, c in changed:
        try:
            display = p.relative_to(Path.cwd())
        except ValueError:
            display = p
        print(f"  {display}  ({c})")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
