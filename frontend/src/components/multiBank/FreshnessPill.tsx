import React from 'react';
import { AlertTriangle, CheckCircle2, XCircle, MinusCircle } from 'lucide-react';
import { cn } from '../../utils';
import { ShadowSummary } from '../../services/api';

// ============================================================================
// Multi-Bank Liquidity — freshness pill.
//
// Tiny presentational helper that renders one of {Stale, Fresh, Failed, Never}
// for a given shadow row. Lifted out so ByBankView and ByCurrencyView render
// the freshness column from the same code path.
//
// Outcome icons, not network icons: this is "what happened to the last
// fetch", not "what is the data source".
// ============================================================================

const REFRESH_STATUS_TONE: Record<string, string> = {
  SUCCESS: 'bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300',
  FAILED:  'bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300',
  STALE:   'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300',
  NEVER:   'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-400',
};

export const FreshnessPill: React.FC<{ shadow: ShadowSummary }> = ({ shadow }) => (
  <span className={cn(
    'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs',
    REFRESH_STATUS_TONE[shadow.stale ? 'STALE' : shadow.lastBalanceRefreshStatus] ?? REFRESH_STATUS_TONE.NEVER,
  )}>
    {shadow.stale
      ? <><AlertTriangle className="w-3 h-3" /> Stale</>
      : shadow.lastBalanceRefreshStatus === 'SUCCESS'
        ? <><CheckCircle2 className="w-3 h-3" /> Fresh</>
        : shadow.lastBalanceRefreshStatus === 'FAILED'
          ? <><XCircle className="w-3 h-3" /> Failed</>
          : <><MinusCircle className="w-3 h-3" /> Never</>
    }
  </span>
);
