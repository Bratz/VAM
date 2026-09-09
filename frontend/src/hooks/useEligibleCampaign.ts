import { useCallback, useState } from 'react';

export interface Campaign {
  id: string;
  tag: string;
  headline: string;
  body: string;
  ctaLabel: string;
  ctaHref: string;
  variant: 'primary' | 'quiet';
  dismissible: boolean;
  expiresAt: string; // ISO date
}

// Stubs the future cross-sell API — swap this array for a real fetch when
// one exists; the hook's return shape doesn't need to change.
const CAMPAIGNS: Campaign[] = [
  {
    id: 'notional-pooling-eur-2026q4',
    tag: 'OFFER',
    headline: 'Notional pooling: net your EUR positions across entities',
    body: 'Corporates running 3+ EUR accounts typically consolidate 15–20% of idle float this way — no cash movement required.',
    ctaLabel: 'See pooling options',
    ctaHref: '/notional-pooling',
    variant: 'primary',
    dismissible: true,
    expiresAt: '2026-12-31T00:00:00Z',
  },
];

function dismissedKey(corporateId: string): string {
  return `dismissed_campaigns:${corporateId}`;
}

function readDismissed(corporateId: string): Set<string> {
  try {
    const raw = localStorage.getItem(dismissedKey(corporateId));
    return new Set(raw ? (JSON.parse(raw) as string[]) : []);
  } catch {
    return new Set();
  }
}

/**
 * Returns the single highest-priority eligible campaign for the current
 * corporate (or null), plus a dismiss function. Returning at most one
 * campaign is what guarantees "one banner per page, never two stacked" —
 * callers don't need their own guard.
 *
 * ponytail: dismissal is keyed by current_corporate_id (localStorage), not a
 * real user id — there is no authenticated user object in this app yet
 * (Layout's avatar is a hardcoded "John Doe"). Move to a real per-user
 * backend column (the user_preferences table already has the right
 * key/value shape, just no entity/repo/controller behind it yet) once real
 * auth exists.
 */
export function useEligibleCampaign(): [Campaign | null, (id: string) => void] {
  const corporateId = localStorage.getItem('current_corporate_id') ?? 'unscoped';
  const [dismissed, setDismissed] = useState<Set<string>>(() => readDismissed(corporateId));

  const dismiss = useCallback(
    (id: string) => {
      const next = new Set(dismissed);
      next.add(id);
      setDismissed(next);
      try {
        localStorage.setItem(dismissedKey(corporateId), JSON.stringify(Array.from(next)));
      } catch {
        // localStorage unavailable — dismissal still works for this session via state.
      }
    },
    [corporateId, dismissed]
  );

  const now = Date.now();
  const campaign =
    CAMPAIGNS.find((c) => !dismissed.has(c.id) && new Date(c.expiresAt).getTime() > now) ?? null;

  return [campaign, dismiss];
}
