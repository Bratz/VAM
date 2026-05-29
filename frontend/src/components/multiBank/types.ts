// ============================================================================
// Multi-Bank Liquidity — shared types.
//
// Co-locates the small string-union types the page coordinator and the three
// view components both depend on, so the import surface for each view is
// one path (`./types`) instead of digging out from ByBankView.
// ============================================================================

export type ViewKey = 'overview' | 'by-bank' | 'by-currency';

export const VALID_VIEWS: readonly ViewKey[] = ['overview', 'by-bank', 'by-currency'] as const;

export const parseView = (s: string | null): ViewKey =>
  (VALID_VIEWS as readonly string[]).includes(s ?? '') ? (s as ViewKey) : 'overview';

// FilterKey lives on ByBankView (where it was originally defined). Re-export
// it from here so future view components can `import { FilterKey } from './types'`
// without reaching across to a sibling view.
export type { FilterKey } from './ByBankView';
