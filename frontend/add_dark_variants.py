"""
Apply Tailwind dark: variants to className strings in TSX files.
Only modifies content inside className="..." or className={`...`} or cn(...) string-literal segments.
Preserves all existing classes and only ADDS dark: variants based on a mapping table.
"""
import re
import sys
from pathlib import Path

# Status color names (semantic)
STATUS = ["success", "warning", "error", "info", "accent", "danger"]
# Tailwind palette colors used in this codebase
PALETTE = ["blue", "purple", "orange", "teal", "indigo", "amber", "green", "red", "yellow", "pink", "cyan", "sky", "rose", "emerald", "lime", "violet", "fuchsia"]

# Build mapping: dict of (light_class) -> dark_class
# We'll match exact class tokens, only adding dark variants if not already present.

MAPPINGS = {}

# Backgrounds
MAPPINGS["bg-white"] = "dark:bg-primary-900"
MAPPINGS["bg-white/80"] = "dark:bg-primary-900/80"
MAPPINGS["bg-white/95"] = "dark:bg-primary-900/95"
MAPPINGS["bg-white/90"] = "dark:bg-primary-900/90"
MAPPINGS["bg-white/50"] = "dark:bg-primary-900/50"
MAPPINGS["bg-white/70"] = "dark:bg-primary-900/70"
MAPPINGS["bg-white/60"] = "dark:bg-primary-900/60"
MAPPINGS["bg-neutral-50"] = "dark:bg-primary-950"
MAPPINGS["bg-neutral-100"] = "dark:bg-primary-800"
MAPPINGS["bg-neutral-200"] = "dark:bg-primary-800"
MAPPINGS["bg-primary-50"] = "dark:bg-primary-800/40"
MAPPINGS["bg-primary-100"] = "dark:bg-primary-700"

for c in STATUS + PALETTE:
    MAPPINGS[f"bg-{c}-50"] = f"dark:bg-{c}-500/10"
    MAPPINGS[f"bg-{c}-100"] = f"dark:bg-{c}-500/20"

# Text
MAPPINGS["text-primary-900"] = "dark:text-neutral-50"
MAPPINGS["text-primary-800"] = "dark:text-neutral-100"
MAPPINGS["text-primary-700"] = "dark:text-neutral-200"
MAPPINGS["text-primary-600"] = "dark:text-primary-200"
MAPPINGS["text-neutral-900"] = "dark:text-neutral-50"
MAPPINGS["text-neutral-800"] = "dark:text-neutral-100"
MAPPINGS["text-neutral-700"] = "dark:text-neutral-200"
MAPPINGS["text-neutral-600"] = "dark:text-neutral-300"
MAPPINGS["text-neutral-500"] = "dark:text-neutral-400"
MAPPINGS["text-neutral-400"] = "dark:text-neutral-500"
MAPPINGS["text-neutral-300"] = "dark:text-neutral-600"

for c in STATUS + PALETTE:
    for shade in ["600", "700", "800"]:
        MAPPINGS[f"text-{c}-{shade}"] = f"dark:text-{c}-300"

# Borders
MAPPINGS["border-neutral-100"] = "dark:border-primary-800/60"
MAPPINGS["border-neutral-100/60"] = "dark:border-primary-800/60"
MAPPINGS["border-neutral-200"] = "dark:border-primary-800"
MAPPINGS["border-neutral-200/60"] = "dark:border-primary-800"
MAPPINGS["border-neutral-200/80"] = "dark:border-primary-800"
MAPPINGS["border-neutral-300"] = "dark:border-primary-700"
MAPPINGS["border-primary-100"] = "dark:border-primary-700/60"
MAPPINGS["border-primary-200"] = "dark:border-primary-700"
MAPPINGS["divide-neutral-100"] = "dark:divide-primary-800/60"
MAPPINGS["divide-neutral-200"] = "dark:divide-primary-800"

for c in STATUS + PALETTE:
    MAPPINGS[f"border-{c}-100"] = f"dark:border-{c}-500/30"
    MAPPINGS[f"border-{c}-200"] = f"dark:border-{c}-500/30"

# Hover/Active
MAPPINGS["hover:bg-neutral-50"] = "dark:hover:bg-primary-800/50"
MAPPINGS["hover:bg-neutral-100"] = "dark:hover:bg-primary-800"
MAPPINGS["hover:bg-primary-50"] = "dark:hover:bg-primary-800/40"
MAPPINGS["hover:bg-primary-100"] = "dark:hover:bg-primary-700"
MAPPINGS["hover:border-neutral-300"] = "dark:hover:border-primary-700"
MAPPINGS["hover:text-primary-700"] = "dark:hover:text-neutral-200"
MAPPINGS["hover:text-primary-900"] = "dark:hover:text-neutral-50"
MAPPINGS["hover:text-neutral-700"] = "dark:hover:text-neutral-200"
MAPPINGS["hover:text-neutral-900"] = "dark:hover:text-neutral-50"

for c in STATUS + PALETTE:
    MAPPINGS[f"hover:bg-{c}-100"] = f"dark:hover:bg-{c}-500/20"
    MAPPINGS[f"hover:bg-{c}-50"] = f"dark:hover:bg-{c}-500/10"

# Gradients - handled with multi-token detection (special)
# Single-token from/to/via mappings we attempt are tricky; do simple ones:
GRADIENT_MULTI = [
    # (pattern_tokens_set_subset, dark_tokens_to_add)
    # We'll detect specific sequences in the class string
]

# Convert mapping keys to a set for membership testing
LIGHT_KEYS = set(MAPPINGS.keys())


def transform_class_string(cls_str: str) -> str:
    """Given the contents of a className string (possibly multiline with whitespace),
    return new string with dark: variants added where needed."""
    # Split on whitespace but preserve structure roughly
    # Tokens with template/expression segments {...} or ${...} must be left alone.
    # We'll treat tokens as whitespace-separated.
    if not cls_str.strip():
        return cls_str

    # Don't transform if it contains JS template expressions inside ${...} - but we still can
    # add darks to literal tokens. We'll tokenize splitting on whitespace.
    tokens = cls_str.split()
    new_tokens = list(tokens)
    existing = set(tokens)

    additions = []
    for tok in tokens:
        # Skip tokens that aren't pure utility classes (skip expressions)
        if "${" in tok or "{" in tok or "}" in tok:
            continue
        if tok in MAPPINGS:
            dark = MAPPINGS[tok]
            if dark not in existing and dark not in additions:
                additions.append(dark)

    # Gradient special handling
    # from-white via-white to-neutral-50/50 with bg-gradient-to-br
    if "bg-gradient-to-br" in existing or "bg-gradient-to-r" in existing or "bg-gradient-to-b" in existing or "bg-gradient-to-tr" in existing or "bg-gradient-to-tl" in existing or "bg-gradient-to-bl" in existing or "bg-gradient-to-t" in existing or "bg-gradient-to-l" in existing:
        # from-white via-white to-neutral-50/50
        if "from-white" in existing and ("via-white" in existing) and any(t.startswith("to-neutral-50") for t in tokens):
            for add in ["dark:from-primary-900", "dark:via-primary-900", "dark:to-primary-950/50"]:
                if add not in existing and add not in additions:
                    additions.append(add)
        elif "from-neutral-50" in existing and "to-white" in existing:
            for add in ["dark:from-primary-950", "dark:to-primary-900"]:
                if add not in existing and add not in additions:
                    additions.append(add)
        elif "from-primary-50" in existing and "via-white" in existing and "to-accent-50" in existing:
            for add in ["dark:from-primary-800", "dark:via-primary-900", "dark:to-accent-500/10"]:
                if add not in existing and add not in additions:
                    additions.append(add)
        else:
            # Generic single from-{c}-50 to-white
            for tok in tokens:
                m = re.match(r"^from-([a-z]+)-50$", tok)
                if m and "to-white" in existing:
                    c = m.group(1)
                    for add in [f"dark:from-{c}-500/10", "dark:to-primary-900"]:
                        if add not in existing and add not in additions:
                            additions.append(add)
            # Same-color flank gradient: from-{c}-50(/X) via-white to-{c}-50(/X)
            # e.g. from-primary-50/50 via-white to-primary-50/50 → solid dark navy
            from_match = None
            to_match = None
            for tok in tokens:
                if not from_match:
                    fm = re.match(r"^from-([a-z]+)-50(?:/\d+)?$", tok)
                    if fm:
                        from_match = fm.group(1)
                if not to_match:
                    tm = re.match(r"^to-([a-z]+)-50(?:/\d+)?$", tok)
                    if tm:
                        to_match = tm.group(1)
            has_via_white = "via-white" in existing or any(t.startswith("via-white/") for t in tokens)
            if from_match and to_match and from_match == to_match and has_via_white:
                # Same-color flanks with white center → opaque navy panel in dark
                for add in ["dark:from-primary-900", "dark:via-primary-900", "dark:to-primary-900"]:
                    if add not in existing and add not in additions:
                        additions.append(add)
            elif from_match and to_match and not has_via_white:
                # Bicolor 50→50 (no via): low-alpha dark variants per side
                if from_match == to_match:
                    add_list = [f"dark:from-{from_match}-500/15", f"dark:to-{to_match}-500/15"]
                else:
                    add_list = [f"dark:from-{from_match}-500/15", f"dark:to-{to_match}-500/15"]
                for add in add_list:
                    if add not in existing and add not in additions:
                        additions.append(add)

    if not additions:
        return cls_str

    # Append additions at end (preserve original spacing/structure)
    # Detect trailing whitespace
    stripped = cls_str.rstrip()
    trailing = cls_str[len(stripped):]
    return stripped + " " + " ".join(additions) + trailing


# Regex to find className="..." or className={`...`} or className='...'
# Also handle cn(...) calls containing string literals
CLASSNAME_DOUBLE = re.compile(r'className="([^"]*)"', re.DOTALL)
CLASSNAME_SINGLE = re.compile(r"className='([^']*)'", re.DOTALL)
CLASSNAME_BACKTICK = re.compile(r'className=\{`([^`]*)`\}', re.DOTALL)
# String literals inside cn() / clsx() / classNames() etc
# We match double-quoted, single-quoted, and backtick (without expressions) strings inside cn(...)
# Simpler: match any "..." or '...' or `...` string used as className context — handled by being inside cn()
CN_CALL = re.compile(r'\b(cn|clsx|classNames)\s*\(', re.DOTALL)


def transform_file(path: Path) -> tuple[int, int]:
    text = path.read_text(encoding="utf-8")
    original = text
    blocks_modified = 0

    def repl_double(m):
        nonlocal blocks_modified
        inner = m.group(1)
        new_inner = transform_class_string(inner)
        if new_inner != inner:
            blocks_modified += 1
            return f'className="{new_inner}"'
        return m.group(0)

    def repl_single(m):
        nonlocal blocks_modified
        inner = m.group(1)
        new_inner = transform_class_string(inner)
        if new_inner != inner:
            blocks_modified += 1
            return f"className='{new_inner}'"
        return m.group(0)

    def repl_backtick(m):
        nonlocal blocks_modified
        inner = m.group(1)
        new_inner = transform_class_string(inner)
        if new_inner != inner:
            blocks_modified += 1
            return f'className={{`{new_inner}`}}'
        return m.group(0)

    text = CLASSNAME_BACKTICK.sub(repl_backtick, text)
    text = CLASSNAME_DOUBLE.sub(repl_double, text)
    text = CLASSNAME_SINGLE.sub(repl_single, text)

    # Handle cn(...) calls - find string literals inside them
    # Find each cn(...) call and process its content
    def process_cn_call(match_start: int, text_: str) -> tuple[str, int]:
        # find matching closing paren accounting for nesting
        depth = 1
        i = match_start
        while i < len(text_) and depth > 0:
            ch = text_[i]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    return text_[:match_start] + transform_cn_body(text_[match_start:i]) + text_[i:], i
            elif ch == '"':
                # skip string
                j = i + 1
                while j < len(text_) and text_[j] != '"':
                    if text_[j] == '\\':
                        j += 2
                        continue
                    j += 1
                i = j
            elif ch == "'":
                j = i + 1
                while j < len(text_) and text_[j] != "'":
                    if text_[j] == '\\':
                        j += 2
                        continue
                    j += 1
                i = j
            elif ch == '`':
                j = i + 1
                while j < len(text_) and text_[j] != '`':
                    if text_[j] == '\\':
                        j += 2
                        continue
                    j += 1
                i = j
            i += 1
        return text_, match_start

    def transform_cn_body(body: str) -> str:
        nonlocal blocks_modified
        # Replace each string literal inside body
        def rd(m):
            nonlocal blocks_modified
            inner = m.group(1)
            new_inner = transform_class_string(inner)
            if new_inner != inner:
                blocks_modified += 1
                return f'"{new_inner}"'
            return m.group(0)

        def rs(m):
            nonlocal blocks_modified
            inner = m.group(1)
            new_inner = transform_class_string(inner)
            if new_inner != inner:
                blocks_modified += 1
                return f"'{new_inner}'"
            return m.group(0)

        def rb(m):
            nonlocal blocks_modified
            inner = m.group(1)
            # Backtick may contain ${...}; only transform if no expressions
            if '${' in inner:
                return m.group(0)
            new_inner = transform_class_string(inner)
            if new_inner != inner:
                blocks_modified += 1
                return f"`{new_inner}`"
            return m.group(0)

        body = re.sub(r'"([^"\\]*(?:\\.[^"\\]*)*)"', rd, body)
        body = re.sub(r"'([^'\\]*(?:\\.[^'\\]*)*)'", rs, body)
        body = re.sub(r'`([^`\\]*(?:\\.[^`\\]*)*)`', rb, body)
        return body

    # Iterate over all cn(/clsx(/classNames( positions
    result_chars = []
    i = 0
    while i < len(text):
        m = CN_CALL.search(text, i)
        if not m:
            result_chars.append(text[i:])
            break
        result_chars.append(text[i:m.end()])
        # Find matching close paren
        depth = 1
        j = m.end()
        while j < len(text) and depth > 0:
            ch = text[j]
            if ch == '(':
                depth += 1
            elif ch == ')':
                depth -= 1
                if depth == 0:
                    break
            elif ch == '"':
                k = j + 1
                while k < len(text) and text[k] != '"':
                    if text[k] == '\\':
                        k += 2
                        continue
                    k += 1
                j = k
            elif ch == "'":
                k = j + 1
                while k < len(text) and text[k] != "'":
                    if text[k] == '\\':
                        k += 2
                        continue
                    k += 1
                j = k
            elif ch == '`':
                k = j + 1
                while k < len(text) and text[k] != '`':
                    if text[k] == '\\':
                        k += 2
                        continue
                    k += 1
                j = k
            j += 1
        # body is text[m.end():j]
        body = text[m.end():j]
        result_chars.append(transform_cn_body(body))
        if j < len(text):
            result_chars.append(text[j])  # the ')'
            i = j + 1
        else:
            i = j
    new_text = ''.join(result_chars)

    if new_text != original:
        path.write_text(new_text, encoding="utf-8")

    return blocks_modified, len(new_text) - len(original)


if __name__ == "__main__":
    files = sys.argv[1:]
    total_blocks = 0
    for f in files:
        p = Path(f)
        if not p.exists():
            print(f"MISSING: {f}")
            continue
        blocks, delta = transform_file(p)
        total_blocks += blocks
        print(f"{p.name}: {blocks} blocks updated (Δ {delta} chars)")
    print(f"TOTAL: {total_blocks} blocks")
