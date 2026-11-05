// Badge labels and tones for deal and confirmation status, shared by the Deal tab and its drawer.

import type { ConfirmationStatus, Deal, DealStatus } from '../services/fxDealApi';

export const DEAL_BADGE: Record<DealStatus, { label: string; variant: 'success' | 'warning' | 'error' | 'neutral' | 'info' }> = {
  BOOKED: { label: 'Booked', variant: 'neutral' },
  ACTIVE: { label: 'Active', variant: 'success' },
  PENDING_SETTLEMENT: { label: 'Settling', variant: 'info' },
  PARTIALLY_DRAWN: { label: 'Part drawn', variant: 'info' },
  SETTLEMENT_FAILED: { label: 'Settlement failed', variant: 'error' },
  SETTLED: { label: 'Settled', variant: 'neutral' },
  CLOSED_OUT: { label: 'Closed out', variant: 'neutral' },
  ROLLED: { label: 'Rolled', variant: 'neutral' },
  CANCELLED: { label: 'Cancelled', variant: 'neutral' },
};

export const confirmationBadge = (d: Deal): { label: string; variant: 'success' | 'warning' | 'error' } | null => {
  const s: ConfirmationStatus | undefined = d.confirmationStatus;
  if (s === 'AFFIRMED') return { label: 'Confirmed', variant: 'success' };
  if (s === 'DISPUTED') return { label: 'Disputed', variant: 'error' };
  if (s === 'ISSUED') return d.confirmationOverdue ? { label: 'Confirmation overdue', variant: 'error' } : { label: 'Awaiting confirmation', variant: 'warning' };
  return null;
};
