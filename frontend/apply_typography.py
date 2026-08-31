"""
Apply .page-title and .stat-value typography utilities across page H1s and big stat numbers.

Conservative: only rewrites <h1> tags whose className matches a recognised
"page heading" pattern, and only inserts .stat-value where a parent context
strongly suggests a stat-card big number. Skips ambiguous cases.

Run: python apply_typography.py <file1> <file2> ...
"""
import re
import sys
from pathlib import Path

# Patterns for page H1s. Each maps a (regex, replacement) pair.
# We rewrite the className contents but preserve the rest of the tag.
H1_PATTERNS = [
    # Common shapes:
    #   text-3xl font-semibold text-primary-900 dark:text-neutral-50
    #   text-3xl font-semibold text-primary-900
    #   text-2xl font-semibold text-primary-900 ...
    #   text-xl font-bold text-neutral-900 dark:text-neutral-50
    # The replacement collapses to just `page-title` plus any extra non-style classes the user added
    # (we strip the size/weight/text-color tokens we recognise; preserve everything else).
]

PAGE_TITLE_TOKENS_TO_REMOVE = {
    "text-xs", "text-sm", "text-base", "text-lg", "text-xl", "text-2xl", "text-3xl", "text-4xl", "text-5xl",
    "font-thin", "font-light", "font-normal", "font-medium", "font-semibold", "font-bold", "font-extrabold",
    "text-primary-900", "text-primary-800", "text-primary-700",
    "text-neutral-900", "text-neutral-800",
    "dark:text-neutral-50", "dark:text-neutral-100", "dark:text-neutral-200",
    "tracking-tight", "tracking-tighter", "leading-tight", "leading-snug",
}

# Match <h1 className="..."> or <h1 className={`...`}> — we only handle string literals.
H1_RE = re.compile(r'<h1(\s[^>]*?)className="([^"]+)"([^>]*)>', re.DOTALL)


def looks_like_page_title(class_str: str) -> bool:
    """Heuristic: is this className shape a 'page H1'?"""
    tokens = set(class_str.split())
    # Must contain a heading-sized text-* token
    has_heading_size = any(t in tokens for t in ("text-2xl", "text-3xl", "text-4xl"))
    # And a heavy weight
    has_weight = any(t in tokens for t in ("font-semibold", "font-bold"))
    # And a text color suggesting a heading
    has_heading_color = any(t in tokens for t in ("text-primary-900", "text-neutral-900"))
    return has_heading_size and has_weight and has_heading_color


def rewrite_h1_class(class_str: str) -> str:
    """Strip our removal tokens; keep everything else; prepend .page-title."""
    tokens = class_str.split()
    kept = [t for t in tokens if t not in PAGE_TITLE_TOKENS_TO_REMOVE]
    # Avoid duplicating page-title if already there
    if "page-title" not in kept:
        kept.insert(0, "page-title")
    return " ".join(kept).strip()


def transform_h1s(text: str) -> tuple[str, int]:
    count = 0

    def repl(m: re.Match) -> str:
        nonlocal count
        before, class_str, after = m.group(1), m.group(2), m.group(3)
        if not looks_like_page_title(class_str):
            return m.group(0)
        new_class = rewrite_h1_class(class_str)
        count += 1
        return f'<h1{before}className="{new_class}"{after}>'

    new_text = H1_RE.sub(repl, text)
    return new_text, count


# --- Stat values ---
# Heuristic: a <p> tag whose className contains text-3xl|4xl|5xl + font-bold
# AND whose immediate inner text contains formatCurrency( or formatCompactCurrency(
# OR is a percentage (text ends with %).
STAT_RE = re.compile(
    r'<p(\s[^>]*?)className="([^"]*\b(?:text-2xl|text-3xl|text-4xl|text-5xl)\b[^"]*)"([^>]*)>([\s\S]{0,200}?)</p>',
    re.DOTALL,
)

STAT_TOKENS_TO_REMOVE = {
    "text-2xl", "text-3xl", "text-4xl", "text-5xl",
    "font-thin", "font-light", "font-normal", "font-medium", "font-semibold", "font-bold", "font-extrabold",
    "text-primary-900", "text-primary-800", "text-primary-700",
    "text-neutral-900", "text-neutral-800", "text-neutral-50",
    "dark:text-neutral-50", "dark:text-neutral-100", "dark:text-neutral-200",
    "tracking-tight", "tracking-tighter",
    "tabular-nums",
    "leading-tight", "leading-snug", "leading-none",
}


def looks_like_stat_value(class_str: str, inner: str) -> bool:
    if "font-bold" not in class_str and "font-semibold" not in class_str:
        return False
    # Inner must look like a number-ish display: contains formatCurrency, or just digits+commas, or %
    inner_compact = inner.strip()
    if "formatCurrency" in inner_compact or "formatCompactCurrency" in inner_compact:
        return True
    # Bare currency-ish number e.g. "AED {something}" inside the tag
    if re.search(r'\b(AED|USD|EUR|GBP|SAR|SGD|JPY|CHF)\b', inner_compact):
        return True
    if re.search(r'^\{?\s*\d', inner_compact):
        return True
    return False


def rewrite_stat_class(class_str: str) -> str:
    tokens = class_str.split()
    # Pick stat-value vs stat-value-sm based on original size token
    use_small = "text-2xl" in tokens
    target = "stat-value-sm" if use_small else "stat-value"
    kept = [t for t in tokens if t not in STAT_TOKENS_TO_REMOVE]
    if target not in kept:
        kept.insert(0, target)
    return " ".join(kept).strip()


def transform_stats(text: str) -> tuple[str, int]:
    count = 0

    def repl(m: re.Match) -> str:
        nonlocal count
        before, class_str, after, inner = m.group(1), m.group(2), m.group(3), m.group(4)
        if not looks_like_stat_value(class_str, inner):
            return m.group(0)
        new_class = rewrite_stat_class(class_str)
        count += 1
        return f'<p{before}className="{new_class}"{after}>{inner}</p>'

    new_text = STAT_RE.sub(repl, text)
    return new_text, count


def transform_file(path: Path) -> tuple[int, int]:
    text = path.read_text(encoding="utf-8")
    text, h1_count = transform_h1s(text)
    text, stat_count = transform_stats(text)
    if h1_count or stat_count:
        path.write_text(text, encoding="utf-8")
    return h1_count, stat_count


if __name__ == "__main__":
    files = sys.argv[1:]
    total_h1 = 0
    total_stats = 0
    for f in files:
        p = Path(f)
        if not p.exists():
            print(f"MISSING: {f}")
            continue
        h1, st = transform_file(p)
        total_h1 += h1
        total_stats += st
        if h1 or st:
            print(f"{p.name}: {h1} H1s, {st} stat values")
    print(f"TOTAL: {total_h1} H1s, {total_stats} stat values")
