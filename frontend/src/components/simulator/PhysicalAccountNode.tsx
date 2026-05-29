import React from 'react';
import { Building2 } from 'lucide-react';
import { cn } from '../../utils';
import type { BankRelationship } from './types';
import type { ShadowBankGroup } from '../../utils/simulator/inventoryGrouping';

// ============================================================================
// PhysicalAccountNode — a bank group in the structure tree. Pure view.
//
// Bank-relationship pill recipe lifted UNCHANGED from cockpit / multi-bank:
//   Home    → success-100/800  + border-l-success-500
//   Group   → info-100/800     + border-l-info-500
//   External→ warning-100/800  + border-l-warning-500
// Semantic palette only — no hex, no gradient.
// ============================================================================

const REL: Record<
  BankRelationship,
  { pill: string; border: string; label: string }
> = {
  INTERNAL: {
    pill:
      'bg-success-100 text-success-800 dark:bg-success-500/20 dark:text-success-300',
    border: 'border-l-success-500',
    label: 'Home bank',
  },
  GROUP: {
    pill:
      'bg-info-100 text-info-800 dark:bg-info-500/20 dark:text-info-300',
    border: 'border-l-info-500',
    label: 'Group bank',
  },
  EXTERNAL: {
    pill:
      'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300',
    border: 'border-l-warning-500',
    label: 'External bank',
  },
};

export interface PhysicalAccountNodeProps {
  group: ShadowBankGroup;
  /** The ShadowVaNode children for this bank group. */
  children: React.ReactNode;
  className?: string;
}

// R4 — human label + freshness/consent hint for the bank-header chip,
// derived from the frozen snapshot fields (data source + consent expiry).
function sourceLabel(ds: string): string {
  if (ds === 'CORE_BANKING') return 'CBS';
  if (ds === 'INTERNAL_API') return 'Internal API';
  if (ds.startsWith('OPEN_BANKING')) return 'Open Banking';
  if (ds === 'SWIFT_MT940') return 'SWIFT MT940';
  if (ds === 'SWIFT_MT942') return 'SWIFT MT942';
  if (ds === 'SWIFT_CAMT053') return 'SWIFT CAMT.053';
  if (ds === 'SWIFT_CAMT052') return 'SWIFT CAMT.052';
  return ds;
}
function sourceFreshness(ds: string): string {
  if (ds === 'CORE_BANKING' || ds === 'INTERNAL_API') return 'sync < 1m';
  if (ds === 'SWIFT_MT940' || ds === 'SWIFT_CAMT053') return 'EOD batch';
  if (ds === 'SWIFT_MT942' || ds === 'SWIFT_CAMT052') return 'intraday';
  if (ds.startsWith('OPEN_BANKING')) return 'consent-based';
  return '';
}
function minConsentDays(group: ShadowBankGroup): number | null {
  const days = group.shadows
    .map((s) => s.snapshotConsentExpiresAt)
    .filter((x): x is string => Boolean(x))
    .map((iso) => Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000))
    .filter((n) => !Number.isNaN(n))
    .sort((a, b) => a - b);
  return days.length ? days[0] : null;
}

export const PhysicalAccountNode: React.FC<PhysicalAccountNodeProps> = ({
  group,
  children,
  className,
}) => {
  const rel = REL[group.relationship] ?? REL.INTERNAL;
  const shadowCount = group.shadows.length;

  return (
    <div
      className={cn(
        'rounded-xl border border-neutral-200/80 dark:border-primary-800/60',
        'border-l-2',
        rel.border,
        'bg-white dark:bg-primary-900/40',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-3 px-4 py-3">
        <div className="flex items-center gap-2 min-w-0">
          <Building2
            className="w-4 h-4 shrink-0 text-neutral-500 dark:text-neutral-400"
            aria-hidden
          />
          <div className="min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <span className="section-title truncate">
                {group.bankName}
              </span>
              <span
                className={cn(
                  'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
                  rel.pill,
                )}
              >
                {rel.label}
              </span>
            </div>
            <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
              <span className="code">{group.bankCode}</span>
              {' · '}
              {shadowCount} shadow{shadowCount === 1 ? '' : 's'}
            </p>
          </div>
        </div>
        {group.dataSources.length > 0 && (
          <div className="flex items-center gap-1 flex-wrap justify-end shrink-0">
            {(() => {
              const cd = minConsentDays(group);
              const consentTone =
                cd != null && cd < 14
                  ? 'bg-error-100 text-error-800 dark:bg-error-500/20 dark:text-error-300'
                  : cd != null && cd < 30
                    ? 'bg-warning-100 text-warning-800 dark:bg-warning-500/20 dark:text-warning-300'
                    : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300';
              return group.dataSources.map((ds) => {
                const hint =
                  cd != null && cd < 30
                    ? `consent ${cd < 0 ? 'expired' : `${cd}d`}`
                    : sourceFreshness(ds);
                return (
                  <span
                    key={ds}
                    className={cn(
                      'inline-flex items-center px-1.5 py-0.5 rounded-full text-xs',
                      cd != null && cd < 30
                        ? consentTone
                        : 'bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-300',
                    )}
                  >
                    {sourceLabel(ds)}
                    {hint ? ` · ${hint}` : ''}
                  </span>
                );
              });
            })()}
          </div>
        )}
      </div>
      <div className="px-4 pb-2 divide-y divide-neutral-100 dark:divide-primary-800/40">
        {children}
      </div>
    </div>
  );
};
