"""
Phase 10 Task D — migrate 8 representative pages to use <Page>.

Each page has the same shape at its top-level:
  return (
    <div className="space-y-6 animate-page-enter">
      ...content...
    </div>
  );

Migrated to:
  return (
    <Page>
      ...content...
    </Page>
  );

Note: the `animate-page-enter` animation is now applied by <main> in
Layout.tsx (Phase 10 Task B), so removing it from the page-level div
is the desired outcome — not a behaviour change.

Adds `import { Page } from '../components/layout/Page';` if not already
present. We DON'T touch StatStrip migration here — that's per-page work
that needs design-judgment per-strip.
"""
import re
import sys
from pathlib import Path

TARGET_FILES = [
    'frontend/src/pages/DashboardPage.tsx',
    'frontend/src/pages/AccountsPage.tsx',
    'frontend/src/pages/IntercompanyDashboardPage.tsx',
    'frontend/src/pages/CreditAgreementsPage.tsx',
    'frontend/src/pages/CreditFacilitiesPage.tsx',
    'frontend/src/pages/CashConcentrationPage.tsx',
    'frontend/src/pages/BalanceAggregationPage.tsx',
    'frontend/src/pages/PhysicalAccountsPage.tsx',
]

# Match the root-page-div pattern. Many pages have it as:
#   <div className="space-y-6 animate-page-enter">
# Some have additional classes:
#   <div className="space-y-6 animate-page-enter dark:bg-primary-950">
# We migrate the canonical exact form first; pages with extra classes
# require manual review.
ROOT_DIV_PATTERN = re.compile(
    r'<div className="space-y-6 animate-page-enter">'
)
# Closing tag handled separately — we walk forward from the opening
# and find the matching </div>. But JSX closing tags can be ambiguous
# in a regex. Safer approach: handle file-by-file and look for the
# return ( <div ... > ... </div> ); pattern with a single bracket
# balance scan.

# For simplicity we'll do these manually per file rather than via
# a fragile regex over the whole tree. The script just reports which
# files contain the pattern so they're easy to inspect.

def main() -> int:
    print("Pages with root `<div className=\"space-y-6 animate-page-enter\">`:")
    found = []
    for fname in TARGET_FILES:
        p = Path(fname)
        if not p.exists():
            print(f'  MISSING: {fname}')
            continue
        text = p.read_text(encoding='utf-8')
        n = ROOT_DIV_PATTERN.findall(text)
        if n:
            found.append((p, len(n)))
            print(f'  {fname}: {len(n)} match(es)')
        else:
            print(f'  {fname}: (no exact match — variant pattern, needs manual)')
    print(f'\nTotal: {len(found)} files with exact pattern.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
