"""
Tier 2 codemod: rewrite raw Tailwind chromatic class tokens to semantic
equivalents.

Mappings:
    gray   -> neutral   (different hex; intentional shift to our palette)
    red    -> error     (hex-equivalent; semantic rename)
    green  -> success   (hex-equivalent; semantic rename)
    blue   -> info      (hex-equivalent; semantic rename)
    orange -> warning   (hex-equivalent post Phase 2; both are Tailwind orange)
    yellow -> warning   (VISIBLE CHANGE: gold-yellow -> orange; matches Phase 2 design intent)

Class shapes targeted (same shape as the danger->error codemod):
    (state-prefix:)* (color-token)-(family)-NN(/NN)?
where:
    state-prefix ∈ {dark, hover, focus, focus-visible, active, disabled,
                    group-hover, peer-hover, md, sm, lg, xl, 2xl}
    color-token  ∈ {text, bg, border, ring, from, to, via, placeholder,
                    fill, stroke, divide, outline, caret, decoration,
                    accent, shadow}

What we do NOT touch:
  - String literals: component variant strings, type unions, comments.
  - Files in the exclusion list (mostly chart-key / categorical palettes
    where chromatic intent is deliberate).

Exclusion list (preserve chromatic intent):
  - frontend/src/pages/TaxChargeSetupPage.tsx — uses orange/yellow/cyan/
    pink as categorical fee-type colours; migrating them would collide
    semantically.

Idempotent: running twice produces no change. Dry-run by default unless
--apply is passed.
"""
import re
import sys
from pathlib import Path

# Family rename map. Targets are the canonical semantic names defined in
# tailwind.config.js's `colors.extend` block after the Tier 2 palette expansion.
RENAMES: dict[str, str] = {
    "gray":   "neutral",
    "red":    "error",
    "green":  "success",
    "blue":   "info",
    "orange": "warning",
    "yellow": "warning",
}

# Tailwind class tokens we prefix `-{family}-NN` against.
TOKENS = (
    r"text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|"
    r"outline|caret|decoration|accent|shadow"
)

# State variants Tailwind allows as prefixes.
STATE = r"(?:dark|hover|focus|focus-visible|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl)"

# Files where chromatic intent is deliberate — codemod leaves them alone.
# Express as path suffixes (matched against str(path) endswith).
EXCLUDE_SUFFIXES: tuple[str, ...] = (
    "pages/TaxChargeSetupPage.tsx",
    "pages\\TaxChargeSetupPage.tsx",  # windows backslash form
)


def build_pattern(family: str) -> re.Pattern[str]:
    return re.compile(
        rf"(?P<prefix>(?:{STATE}:)*)(?P<token>{TOKENS})-{family}-(?P<scale>\d+(?:/\d+)?)"
    )


PATTERNS = {family: build_pattern(family) for family in RENAMES}


def transform(text: str, families: tuple[str, ...]) -> tuple[str, dict[str, int]]:
    """Return (new_text, per-family-count) after applying all rewrites."""
    counts: dict[str, int] = {family: 0 for family in families}

    for family in families:
        new_name = RENAMES[family]
        pattern = PATTERNS[family]

        def sub(m: re.Match, _family: str = family, _new: str = new_name) -> str:
            counts[_family] += 1
            return f"{m.group('prefix')}{m.group('token')}-{_new}-{m.group('scale')}"

        text = pattern.sub(sub, text)

    return text, counts


def is_excluded(path: Path) -> bool:
    s = str(path)
    return any(s.endswith(suf) for suf in EXCLUDE_SUFFIXES)


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: codemod_chromatic_to_semantic.py <root> [--apply] [--families gray,red,green,blue,orange,yellow]")
        return 2

    root = Path(argv[1]).resolve()
    apply = "--apply" in argv

    # Optional --families to limit scope. Default: all six.
    families: tuple[str, ...] = tuple(RENAMES)
    for i, arg in enumerate(argv):
        if arg == "--families" and i + 1 < len(argv):
            requested = tuple(f.strip() for f in argv[i + 1].split(","))
            unknown = set(requested) - set(RENAMES)
            if unknown:
                print(f"unknown families: {sorted(unknown)}")
                return 2
            families = requested

    print(f"families in scope: {', '.join(families)}")

    total_changes: dict[str, int] = {f: 0 for f in families}
    total_files = 0
    excluded: list[Path] = []
    changed_files: list[tuple[Path, dict[str, int]]] = []

    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".tsx", ".ts"}:
            continue
        if any(part in {"node_modules", "dist", "build", ".next"} for part in path.parts):
            continue

        text = path.read_text(encoding="utf-8")
        if is_excluded(path):
            # Track whether this file WOULD have changed (for reporting).
            _, would_change = transform(text, families)
            if any(would_change.values()):
                excluded.append(path)
            continue

        new_text, counts = transform(text, families)
        nonzero = {k: v for k, v in counts.items() if v > 0}
        if not nonzero:
            continue

        total_files += 1
        for k, v in nonzero.items():
            total_changes[k] += v
        changed_files.append((path, nonzero))
        if apply:
            path.write_text(new_text, encoding="utf-8")

    mode = "APPLIED" if apply else "DRY RUN"
    print(f"\n[{mode}] across {total_files} files:")
    for fam in families:
        print(f"    {fam:<7s} -> {RENAMES[fam]:<8s} : {total_changes[fam]} rewrites")
    print(f"    TOTAL                  : {sum(total_changes.values())} rewrites")

    if excluded:
        print(f"\nExcluded (chromatic intent preserved):")
        for p in excluded:
            print(f"    {p.relative_to(root)}")

    if changed_files:
        print(f"\nFiles touched:")
        for p, counts in changed_files:
            summary = ", ".join(f"{k}:{v}" for k, v in sorted(counts.items()))
            print(f"    {p.relative_to(root)}  ({summary})")

    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
