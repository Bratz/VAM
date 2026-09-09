import React from 'react';
import { cn, formatCurrency } from '../../utils';
import { Card, Skeleton } from '../ui';
import { TodayHorizon } from '../../types/cockpit';

// ============================================================================
// Cockpit — Today's horizon panel.
//
// Second above-the-fold band. Four-column metric strip on top (Net position
// EOD as the dominant figure, then scheduled outflows / expected inflows /
// credit headroom), a 24-hour flow bar beneath, and an FX disclosure line at
// the bottom.
//
// FX-honesty contract: each base-currency figure carries a `title` attribute
// breaking down the per-currency components from `byCurrency`. V1 uses the
// browser tooltip; V2 may upgrade to a portal-based popover but the data
// surface stays the same.
//
// Inbox→horizon link: `markedHours` is a set of hours that have an
// AttentionItem with `timePressure.kind === 'absolute'` pointing at them.
// The flow bar marks those hour labels with a thin warning underline.
// ============================================================================

interface TodayHorizonPanelProps {
  horizon: TodayHorizon | null;
  loading?: boolean;
  /** Hours (0..23 local) that have a matching attention item with a deadline. */
  markedHours?: Set<number>;
}

function byCurrencyTooltip(
  horizon: TodayHorizon,
  field: 'netPositionEndOfDay' | 'scheduledOutflowsNext8h' | 'expectedInflowsNext8h',
): string {
  if (horizon.byCurrency.length === 0) return '';
  return horizon.byCurrency
    .map((c) => `${c.currencyCode} ${c[field].toLocaleString()}`)
    .join(' · ');
}

export const TodayHorizonPanel: React.FC<TodayHorizonPanelProps> = ({ horizon, loading, markedHours }) => {
  if (loading || !horizon) {
    return (
      <Card padding="md">
        <p className="label mb-3">Today's horizon</p>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i}>
              <Skeleton className="h-3 w-24 mb-2" />
              <Skeleton className="h-7 w-32" />
            </div>
          ))}
        </div>
        <Skeleton className="h-16 rounded-lg" />
      </Card>
    );
  }

  // Peak hour (for bolding).
  const peakHour = horizon.hourlyFlows.reduce(
    (peak, h) => (h.inflow + h.outflow > peak.inflow + peak.outflow ? h : peak),
    horizon.hourlyFlows[0],
  );

  // Max flow magnitude — used to scale bar heights so the chart reads well
  // even when the day is light.
  const maxFlow = horizon.hourlyFlows.reduce(
    (m, h) => Math.max(m, h.inflow, h.outflow),
    1,
  );

  return (
    <Card padding="md">
      <div className="flex items-center justify-between mb-3 flex-wrap gap-2">
        <p className="label">Today's horizon</p>
        <p className="body-sm text-neutral-500 dark:text-neutral-400">
          Base · {horizon.baseCurrency}
        </p>
      </div>

      {/* 4 metric blocks. Net EOD takes the dominant treatment via .stat-value-sm
          (Fraunces serif). Outflows lean error-tone, inflows success-tone,
          headroom neutral. Each carries a per-currency tooltip. */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-4">
        <div title={byCurrencyTooltip(horizon, 'netPositionEndOfDay')}>
          <p className="label">Net position EOD</p>
          <p className="stat-value-sm mt-0.5">
            {formatCurrency(horizon.netPositionEndOfDay, horizon.baseCurrency)}
          </p>
        </div>
        <div title={byCurrencyTooltip(horizon, 'scheduledOutflowsNext8h')}>
          <p className="label">Scheduled outflows · next 8h</p>
          <p className="stat-value-error mt-0.5">
            {formatCurrency(horizon.scheduledOutflowsNext8h, horizon.baseCurrency)}
          </p>
        </div>
        <div title={byCurrencyTooltip(horizon, 'expectedInflowsNext8h')}>
          <p className="label">Expected inflows · next 8h</p>
          <p className="stat-value-success mt-0.5">
            {formatCurrency(horizon.expectedInflowsNext8h, horizon.baseCurrency)}
          </p>
        </div>
        <div>
          <p className="label">Credit headroom</p>
          <p className="stat-value-sm mt-0.5">
            {formatCurrency(horizon.creditHeadroom, horizon.baseCurrency)}
          </p>
        </div>
      </div>

      {/* Hourly flow bar — flex of 24 columns, inflow bar above the axis,
          outflow bar below. Hours with marked attention items get a warning
          underline so the eye moves from inbox row to its horizon hour. */}
      <div className="rounded-lg border border-neutral-200 dark:border-primary-800 p-3">
        <div className="flex items-end gap-px h-16 mb-1">
          {horizon.hourlyFlows.map((h) => {
            const inflowH = (h.inflow / maxFlow) * 28;
            const outflowH = (h.outflow / maxFlow) * 28;
            const isPeak = h.hour === peakHour.hour;
            return (
              <div key={h.hour} className="flex-1 flex flex-col items-center justify-center">
                <div
                  className={cn('w-full bg-success-500 dark:bg-success-400', isPeak && 'opacity-100', !isPeak && 'opacity-80')}
                  style={{ height: `${inflowH}px` }}
                  title={`${h.hour}:00 inflow ${h.inflow.toLocaleString()}`}
                />
                <div className="w-full h-px bg-neutral-300 dark:bg-primary-700" />
                <div
                  className={cn('w-full bg-error-500 dark:bg-error-400', isPeak && 'opacity-100', !isPeak && 'opacity-80')}
                  style={{ height: `${outflowH}px` }}
                  title={`${h.hour}:00 outflow ${h.outflow.toLocaleString()}`}
                />
              </div>
            );
          })}
        </div>
        <div className="flex gap-px">
          {horizon.hourlyFlows.map((h) => {
            const isPeak = h.hour === peakHour.hour;
            const isMarked = markedHours?.has(h.hour);
            return (
              <span
                key={h.hour}
                className={cn(
                  'flex-1 text-center text-xs text-neutral-500 dark:text-neutral-400',
                  isPeak && 'text-primary-900 dark:text-neutral-50 font-semibold',
                  isMarked && 'border-b border-warning-500',
                )}
              >
                {h.hour % 3 === 0 ? `${h.hour}` : ''}
              </span>
            );
          })}
        </div>
      </div>

      {/* FX disclosure line. The base totals above are derived using these
          rates — clicking "Refresh rates" would re-fetch in V2 (V1 stub). */}
      <div className="mt-3 body-sm text-neutral-500 dark:text-neutral-400 flex items-center gap-2 flex-wrap">
        <span>Converted at</span>
        {horizon.fxDisclosure.rates.map((r) => (
          <span key={r.pair} className="inline-flex items-center gap-1 font-mono text-xs">
            {r.pair} {r.rate.toFixed(4)}
          </span>
        ))}
        <span className="opacity-70">· {horizon.fxDisclosure.rates[0]?.source}</span>
      </div>
    </Card>
  );
};
