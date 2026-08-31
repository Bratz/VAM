import React, { useMemo, useState } from 'react';
import { ArrowRight, Loader2 } from 'lucide-react';
import { Card, Button } from '../ui';
import { Modal } from '../ui/enhanced';
import { cn, formatCompactCurrency } from '../../utils';
import { ScoreLine } from './ScoreLine';
import type { ConsentWindow, ScoreResult } from './types';

// ============================================================================
// ScorePanel — the right-rail Optimisation Score. Pure view over a
// ScoreResult (computed upstream by scoreCalculator). The dollar figures are
// the defensible output; the 0–100 headline is visual anchoring. Every
// constant / FX rate / tariff source is disclosed in the assumptions Modal
// (no Drawer primitive — same precedent as the Phase-1 Add drawers).
// ============================================================================

export interface ScorePanelProps {
  score: ScoreResult | null;
  loading?: boolean;
  /** Phase-4: renewal CTA for a near-expiry consent window. */
  onRenewConsent?: (window: ConsentWindow) => void;
  className?: string;
}

const SEVERITY_TONE: Record<ConsentWindow['severity'], string> = {
  severe: 'text-error-700 dark:text-error-300',
  moderate: 'text-warning-700 dark:text-warning-300',
  ok: 'text-neutral-500 dark:text-neutral-400',
};

function netTone(net: number): string {
  return net > 0
    ? 'text-success-700 dark:text-success-300'
    : net < 0
      ? 'text-error-700 dark:text-error-300'
      : 'text-neutral-500 dark:text-neutral-400';
}

function signedCompact(amount: number, ccy: string): string {
  const sign = amount > 0 ? '+' : amount < 0 ? '−' : '';
  return `${sign}${formatCompactCurrency(Math.abs(amount), ccy)}`;
}

export const ScorePanel: React.FC<ScorePanelProps> = ({
  score,
  loading,
  onRenewConsent,
  className,
}) => {
  const [assumptionsOpen, setAssumptionsOpen] = useState(false);

  const maxAbsDelta = useMemo(
    () =>
      score
        ? Math.max(0, ...score.lines.map((l) => Math.abs(l.deltaAnnual)))
        : 0,
    [score],
  );

  if (loading) {
    return (
      <Card padding="md" className={className}>
        <div className="flex items-center justify-center gap-2 py-12 body-sm text-neutral-500 dark:text-neutral-400">
          <Loader2 className="w-4 h-4 animate-spin" />
          Computing score…
        </div>
      </Card>
    );
  }

  if (!score) {
    return (
      <Card padding="md" className={className}>
        <p className="label">Optimisation score</p>
        <p className="mt-3 body-sm text-neutral-500 dark:text-neutral-400">
          Add shadows and a sweep rule — the score computes from the proposed
          structure.
        </p>
      </Card>
    );
  }

  const tone = netTone(score.netAnnualBenefit);

  return (
    <Card padding="md" className={className}>
      <p className="label">Optimisation score</p>

      <div className="mt-2 flex items-end gap-2">
        <span className="stat-value">{score.headline}</span>
        <span className="body-sm text-neutral-500 dark:text-neutral-400 pb-1">
          / 100
        </span>
      </div>
      <p className={cn('mt-1 body-sm', tone)}>
        {signedCompact(score.netAnnualBenefit, score.baseCurrency)} net annual
        benefit vs live
      </p>
      <p className="mt-1 body-sm text-neutral-500 dark:text-neutral-400">
        Annualised · base {score.baseCurrency} · assumptions disclosed
        {score.fxDisclosures.length > 0 ? ' · FX rates disclosed' : ''}
      </p>

      <div className="mt-4 divide-y divide-neutral-100 dark:divide-primary-800/40">
        {score.lines.map((l) => (
          <ScoreLine
            key={l.key}
            line={l}
            maxAbsDelta={maxAbsDelta}
            baseCurrency={score.baseCurrency}
          />
        ))}
      </div>

      <div className="mt-3 pt-3 border-t border-neutral-200 dark:border-primary-800/60 flex items-center justify-between gap-3">
        <span className="label">Net annual benefit</span>
        <span className={cn('code', tone)}>
          {signedCompact(score.netAnnualBenefit, score.baseCurrency)}
        </span>
      </div>

      {score.consentWindows && score.consentWindows.length > 0 && (
        <div className="mt-4 pt-3 border-t border-neutral-200 dark:border-primary-800/60">
          <p className="label">Operational risk profile</p>
          <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
            Data-sharing consent windows by bank
          </p>
          <ul className="mt-2 divide-y divide-neutral-100 dark:divide-primary-800/40">
            {score.consentWindows.map((w) => (
              <li
                key={w.physicalAccountId}
                className="flex items-center justify-between gap-3 py-2"
              >
                <div className="min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="body-sm text-primary-900 dark:text-neutral-100 truncate">
                      {w.bankName}
                    </span>
                    <span className="code text-neutral-400">
                      {w.bankCode}
                    </span>
                  </div>
                  <p
                    className={cn(
                      'body-sm mt-0.5',
                      SEVERITY_TONE[w.severity],
                    )}
                  >
                    {w.daysToExpiry < 0
                      ? 'consent expired'
                      : `consent expires in ${w.daysToExpiry}d`}
                    {w.surchargeAnnual > 0
                      ? ` · ${signedCompact(-w.surchargeAnnual, w.currencyCode)} surcharge`
                      : ' · no surcharge'}
                  </p>
                </div>
                {w.severity !== 'ok' && onRenewConsent && (
                  <button
                    type="button"
                    onClick={() => onRenewConsent(w)}
                    className="shrink-0 inline-flex items-center gap-1 body-sm font-medium text-primary-700 hover:text-primary-800 dark:text-primary-300 dark:hover:text-primary-200 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 rounded"
                  >
                    Renew
                    <ArrowRight className="w-4 h-4" aria-hidden />
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {score.caveats.length > 0 && (
        <ul className="mt-3 space-y-1">
          {score.caveats.map((c, i) => (
            <li
              key={i}
              className="body-sm text-warning-700 dark:text-warning-300"
            >
              {c}
            </li>
          ))}
        </ul>
      )}

      <button
        type="button"
        onClick={() => setAssumptionsOpen(true)}
        className="mt-4 inline-flex items-center gap-1 body-sm font-medium text-primary-700 hover:text-primary-800 dark:text-primary-300 dark:hover:text-primary-200 underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-500 rounded"
      >
        View assumptions
        <ArrowRight className="w-4 h-4" aria-hidden />
      </button>

      <Modal
        isOpen={assumptionsOpen}
        onClose={() => setAssumptionsOpen(false)}
        title="Score assumptions"
        subtitle="Every constant, FX rate and tariff source behind the figures"
        size="md"
        footer={
          <div className="flex justify-end">
            <Button
              variant="primary"
              size="sm"
              onClick={() => setAssumptionsOpen(false)}
            >
              Close
            </Button>
          </div>
        }
      >
        <div className="space-y-4">
          <div>
            <p className="label mb-1">Assumptions</p>
            <ul className="divide-y divide-neutral-100 dark:divide-primary-800/40">
              {score.assumptions.map((a, i) => (
                <li
                  key={i}
                  className="flex items-start justify-between gap-4 py-2 body-sm"
                >
                  <span className="text-neutral-500 dark:text-neutral-400">
                    {a.label}
                  </span>
                  <span className="text-primary-900 dark:text-neutral-100 text-right">
                    {a.value}
                  </span>
                </li>
              ))}
            </ul>
          </div>

          {score.fxDisclosures.length > 0 && (
            <div>
              <p className="label mb-1">FX rates used</p>
              <ul className="divide-y divide-neutral-100 dark:divide-primary-800/40">
                {score.fxDisclosures.map((f, i) => (
                  <li
                    key={i}
                    className="flex items-center justify-between gap-4 py-2 body-sm"
                  >
                    <span className="code">{f.pair}</span>
                    <span className="text-primary-900 dark:text-neutral-100">
                      {f.rate} · {f.source}
                      {typeof f.spreadBps === 'number'
                        ? ` · ${f.spreadBps}bps spread`
                        : ''}
                      {f.asOf ? ` · ${f.asOf}` : ''}
                    </span>
                  </li>
                ))}
              </ul>
            </div>
          )}

          {score.caveats.length > 0 && (
            <div>
              <p className="label mb-1">Caveats</p>
              <ul className="space-y-1">
                {score.caveats.map((c, i) => (
                  <li
                    key={i}
                    className="body-sm text-warning-700 dark:text-warning-300"
                  >
                    {c}
                  </li>
                ))}
              </ul>
            </div>
          )}

          <p className="body-sm text-neutral-500 dark:text-neutral-400">
            Computed {new Date(score.computedAt).toLocaleString()}
          </p>
        </div>
      </Modal>
    </Card>
  );
};
