"""
Tier 1 codemod: rewrite Tailwind class tokens of the form
    (prefix-)*(text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|outline|caret)-danger-NN(/NN)?
to use `error` in place of `danger`.

Why: `tailwind.config.js` does not define a `danger` color palette; only
`error` exists. Tailwind's JIT silently drops unknown class names — so
every existing `text-danger-600` etc. has been rendering as plain inherited
colour, not red. Audit found 100+ such sites across 19 files.

We do NOT touch:
  - Component variant strings: `variant="danger"`, `: 'danger'`, etc.
    These are semantic props on shared Button/Alert components — those
    components map `'danger'` → error styling internally, which is fine.
  - String literals containing the word "danger" outside Tailwind class
    context (toast messages, comments, etc.).

Safety: the regex anchors on Tailwind class shape `(token)-danger-(NN)`
which only matches inside class attribute values in practice. We make
the rewrite idempotent (running twice = no-op) and dry-run first.
"""
import re
import sys
from pathlib import Path

# Tailwind class tokens we expect to see prefixing `-danger-NN`.
# These are the colour-aware utility families. Adding a new one is safe;
# missing one means a few sites slip through (catchable by a follow-up grep).
TOKENS = (
    r"text|bg|border|ring|from|to|via|placeholder|fill|stroke|divide|"
    r"outline|caret|decoration|accent|shadow"
)

# State variants Tailwind allows as prefixes on colour classes.
# Order doesn't matter; we accept any chain like `dark:hover:focus:`.
STATE = r"(?:dark|hover|focus|focus-visible|active|disabled|group-hover|peer-hover|md|sm|lg|xl|2xl)"

# Full pattern: optional state prefixes, then one of TOKENS, then -danger-NN(/NN)?
PATTERN = re.compile(
    rf"(?P<prefix>(?:{STATE}:)*)(?P<token>{TOKENS})-danger-(?P<scale>\d+(?:/\d+)?)"
)


def transform(text: str) -> tuple[str, int]:
    """Return (new_text, count_changed)."""
    count = 0

    def sub(m: re.Match) -> str:
        nonlocal count
        count += 1
        return f"{m.group('prefix')}{m.group('token')}-error-{m.group('scale')}"

    return PATTERN.sub(sub, text), count


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print("usage: codemod_danger_to_error.py <root> [--dry-run]")
        return 2

    root = Path(argv[1]).resolve()
    dry_run = "--dry-run" in argv

    total_files = 0
    total_changes = 0
    changed_files: list[Path] = []

    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".tsx", ".ts"}:
            continue
        # Skip node_modules / dist / build
        if any(part in {"node_modules", "dist", "build", ".next"} for part in path.parts):
            continue
        text = path.read_text(encoding="utf-8")
        new_text, count = transform(text)
        if count > 0:
            changed_files.append(path)
            total_files += 1
            total_changes += count
            if not dry_run:
                path.write_text(new_text, encoding="utf-8")

    mode = "DRY RUN" if dry_run else "APPLIED"
    print(f"[{mode}] {total_changes} class-token rewrites across {total_files} files")
    for p in changed_files:
        print(f"  {p.relative_to(root)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
