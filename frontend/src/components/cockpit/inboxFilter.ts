// ============================================================================
// Cockpit — inbox filter helpers.
//
// Split from AttentionInbox.tsx so the component file can export only its
// component (keeps React Fast Refresh happy — same pattern used for the
// MetricCard tone tokens in components/multiBank).
// ============================================================================

export type InboxFilterKey =
  | 'all'
  | 'critical'
  | 'high'
  | 'medium'
  | 'by-bank'
  | 'by-entity'
  | 'by-currency';

export const VALID_INBOX_FILTERS: readonly InboxFilterKey[] = [
  'all', 'critical', 'high', 'medium', 'by-bank', 'by-entity', 'by-currency',
] as const;

export const parseInboxFilter = (s: string | null): InboxFilterKey =>
  (VALID_INBOX_FILTERS as readonly string[]).includes(s ?? '')
    ? (s as InboxFilterKey)
    : 'all';
