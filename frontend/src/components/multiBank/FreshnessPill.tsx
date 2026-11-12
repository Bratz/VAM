import React from 'react';
import { AlertTriangle, XCircle, MinusCircle, CheckCircle } from 'lucide-react';
import { cn, formatDate, formatDateTime } from '../../utils';
import { ShadowSummary } from '../../services/api';

// ============================================================================
// Multi-Bank Liquidity — freshness pill.
//
// Tiny presentational helper that renders one of {Stale, Fresh, Failed, Never}
// for a given shadow row. Lifted out so ByBankView and ByCurrencyView render
// the freshness column from the same code path.
//
// Outcome icons, not network icons: this is "what happened to the last
// fetch", not "what is the data source". Under it, the source and as-of
// (spec R15); a statement-fed shadow names its last end-of-day statement
// instead — its status already follows the statement rule (D6), set by the
// backend, so an old live-connection FAILED stamp never shows for it.
// ============================================================================

const REFRESH_STATUS_TONE: Record<string, string> = {
  SUCCESS: 'bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300',
  FAILED:  'bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300',
  STALE:   'bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300',
  NEVER:   'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-400',
};

const SOURCE_LABEL: Record<string, string> = {
  CORE_BANKING: 'Core banking', SWIFT_MT940: 'Bank statements', SWIFT_MT942: 'Bank statements',
  HOST_TO_HOST: 'Bank statements', OPEN_BANKING: 'Open banking', API: 'Bank API', MANUAL: 'Manual',
};

/** Mirrors BalanceDataSource.statementFed() on the backend. */
const STATEMENT_FED = new Set(['SWIFT_MT940', 'SWIFT_MT942', 'HOST_TO_HOST']);

export const FreshnessPill: React.FC<{ shadow: ShadowSummary }> = ({ shadow }) => {
  const source = shadow.balanceDataSource;
  const detail = source && STATEMENT_FED.has(source)
    ? `Last statement ${shadow.lastStatementDate ? formatDate(`${shadow.lastStatementDate}T00:00:00`) : 'none yet'}`
    : [source && (SOURCE_LABEL[source] ?? source), shadow.bankBalanceAt && `as of ${formatDateTime(shadow.bankBalanceAt)}`]
        .filter(Boolean).join(' · ');
  return (
    <span className="inline-flex flex-col items-start gap-0.5">
      <span className={cn(
        'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-caption',
        REFRESH_STATUS_TONE[shadow.stale ? 'STALE' : shadow.lastBalanceRefreshStatus] ?? REFRESH_STATUS_TONE.NEVER,
      )}>
        {shadow.stale
          ? <><AlertTriangle className="w-3 h-3" /> Stale</>
          : shadow.lastBalanceRefreshStatus === 'SUCCESS'
            ? <><CheckCircle className="w-3 h-3" /> Fresh</>
            : shadow.lastBalanceRefreshStatus === 'FAILED'
              ? <><XCircle className="w-3 h-3" /> Failed</>
              : <><MinusCircle className="w-3 h-3" /> Never</>
        }
      </span>
      {detail && <span className="caption whitespace-nowrap">{detail}</span>}
    </span>
  );
};
