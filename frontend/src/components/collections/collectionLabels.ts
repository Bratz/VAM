import type { CollectionMethod, LineStatus } from '../../services/collectionsApi';

type Variant = 'success' | 'info' | 'neutral' | 'warning' | 'error';

export const METHOD: Record<CollectionMethod, { label: string; variant: Variant }> = {
  DIRECT_DEBIT: { label: 'Direct debit', variant: 'success' },
  REQUEST_TO_PAY: { label: 'Request to Pay', variant: 'info' },
  EXCLUDED: { label: 'Left out', variant: 'neutral' },
};

export const STATUS: Record<LineStatus, { label: string; variant: Variant }> = {
  SENT: { label: 'Debit sent', variant: 'info' },
  SETTLED: { label: 'Collected, held', variant: 'warning' },
  AVAILABLE: { label: 'Collected', variant: 'success' },
  RTP_SENT: { label: 'Request sent', variant: 'info' },
  EXCLUDED: { label: 'Left out', variant: 'neutral' },
  FAILED: { label: 'Failed', variant: 'error' },
  REJECTED: { label: 'Rejected', variant: 'error' },
  RETURNED: { label: 'Returned', variant: 'error' },
};
