// ============================================================================
// Multi-Bank Liquidity — shared types.
//
// Co-locates the small string-union types the page coordinator and the view
// components both depend on, so each view imports from one path (`./types`).
// ============================================================================

export type ViewKey = 'overview' | 'accounts' | 'trend';
export type GroupBy = 'bank' | 'currency' | 'entity' | 'country';
export type FilterKey = 'all' | 'home' | 'external' | 'stale' | 'failed' | 'never';

const VIEWS: readonly ViewKey[] = ['overview', 'accounts', 'trend'];
const GROUPS: readonly GroupBy[] = ['bank', 'currency', 'entity', 'country'];

// The By Bank / Currency / Entity / Country tabs became one Accounts table with a
// Group-by control (density audit, round 3); an old ?view= id opens it grouped that way.
const LEGACY_VIEWS = new Map<string | null, GroupBy>([
  ['by-bank', 'bank'], ['by-currency', 'currency'], ['by-entity', 'entity'], ['by-country', 'country'],
]);

export const parseView = (s: string | null): ViewKey =>
  LEGACY_VIEWS.has(s) ? 'accounts'
    : (VIEWS as readonly string[]).includes(s ?? '') ? (s as ViewKey) : 'overview';

export const parseGroupBy = (view: string | null, group: string | null): GroupBy =>
  LEGACY_VIEWS.get(view)
    || ((GROUPS as readonly string[]).includes(group ?? '') ? (group as GroupBy) : 'bank');
