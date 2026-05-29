import React from 'react';
import { Layers, RefreshCw, ArrowRight, Banknote, Building2 } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { cn, formatCurrency, formatCompactAmount } from '../../utils';
import { Card, StatusIconBadge, Skeleton } from '../ui';
import { TreasurySummary } from '../../services/api';

// ============================================================================
// Cockpit — context strip.
//
// Below-the-fold supporting context. Four operational tiles (Pooling /
// Sweeping / Netting / IHB) pulled from TreasurySummary, plus a paired
// 2-column block underneath: 30-day sparkline on the left, last-5
// transactions on the right.
//
// These tiles are intentionally smaller than the Overview's stat strip —
// they're context the treasurer scans only when something above-the-fold
// raises a question.
// ============================================================================

export interface ContextStripTransaction {
  id: string;
  description: string;
  amount: number;
  currencyCode: string;
  movementType: string;
  transactionDate: string;
}

interface ContextStripProps {
  treasury: TreasurySummary | null;
  loading?: boolean;
  recentTransactions: ContextStripTransaction[];
  /** 30-day daily totals (home / external split). */
  trend?: Array<{ day: string; home: number; external: number }>;
  onNavigate: (page: string) => void;
}

const TILES: Array<{
  key: keyof TreasurySummary;
  label: string;
  page: string;
  icon: LucideIcon;
  primary: (t: TreasurySummary) => { value: string; sub: string };
}> = [
  {
    key: 'pooling',
    label: 'Pooling',
    page: 'pooling',
    icon: Layers,
    primary: (t) => ({
      value: formatCompactAmount(t.pooling.totalPooledBalance),
      sub: `${t.pooling.activePools} pools · ${t.pooling.memberCount} members`,
    }),
  },
  {
    key: 'sweeping',
    label: 'Sweeping',
    page: 'sweeping',
    icon: RefreshCw,
    primary: (t) => ({
      value: formatCompactAmount(t.sweeping.totalSweptToday),
      sub: `${t.sweeping.executionsToday} runs today · ${t.sweeping.activeRules} rules`,
    }),
  },
  {
    key: 'netting',
    label: 'Netting',
    page: 'netting',
    icon: ArrowRight,
    primary: (t) => ({
      value: formatCompactAmount(t.netting.totalSavingsYtd),
      sub: `${t.netting.pendingCycles} pending · ${t.netting.averageSavingsPercent.toFixed(1)}% avg savings`,
    }),
  },
  {
    key: 'inHouseBank',
    label: 'In-house bank',
    page: 'ihb',
    icon: Building2,
    primary: (t) => ({
      value: formatCompactAmount(t.inHouseBank.totalLoansOutstanding),
      sub: `${t.inHouseBank.activeEntities} entities · ${formatCompactAmount(t.inHouseBank.totalDeposits)} deposits`,
    }),
  },
];

// Tiny inline sparkline — flex of points scaled to the bounding box.
function Sparkline({ values, colour }: { values: number[]; colour: string }) {
  if (values.length === 0) return null;
  const max = Math.max(...values, 1);
  return (
    <div className="flex items-end h-10 gap-px">
      {values.map((v, i) => (
        <div
          key={i}
          className={cn('flex-1 rounded-sm', colour)}
          style={{ height: `${(v / max) * 100}%`, minHeight: '1px' }}
        />
      ))}
    </div>
  );
}

export const ContextStrip: React.FC<ContextStripProps> = ({
  treasury,
  loading,
  recentTransactions,
  trend,
  onNavigate,
}) => {
  if (loading || !treasury) {
    return (
      <div className="space-y-4">
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <Card key={i} padding="sm"><Skeleton className="h-16" /></Card>
          ))}
        </div>
      </div>
    );
  }

  const homeValues = (trend ?? []).map((d) => d.home);
  const externalValues = (trend ?? []).map((d) => d.external);

  return (
    <div className="space-y-4">
      {/* 4 compact operational tiles. */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {TILES.map(({ key, label, page, icon: Icon, primary }) => {
          const m = primary(treasury);
          return (
            <Card
              key={key as string}
              padding="sm"
              hover
              interactive
              onClick={() => onNavigate(page)}
            >
              <div className="flex items-center gap-2 mb-2">
                <StatusIconBadge tone="primary" icon={Icon} size="sm" />
                <span className="body-sm text-neutral-700 dark:text-neutral-200">{label}</span>
              </div>
              <p className="stat-value-xs">{m.value}</p>
              <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-1 truncate">{m.sub}</p>
            </Card>
          );
        })}
      </div>

      {/* Paired sparkline + recent activity. */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-3">
        <Card padding="sm">
          <div className="flex items-center justify-between mb-2 flex-wrap gap-2">
            <p className="label">30-day balance trend</p>
            <div className="flex items-center gap-3 body-sm text-neutral-500 dark:text-neutral-400">
              <span className="inline-flex items-center gap-1.5"><span className="w-2 h-2 rounded-sm bg-success-500" /> Home</span>
              <span className="inline-flex items-center gap-1.5"><span className="w-2 h-2 rounded-sm bg-info-500" /> External</span>
            </div>
          </div>
          <div className="space-y-1.5">
            <Sparkline values={homeValues} colour="bg-success-500 dark:bg-success-400" />
            <Sparkline values={externalValues} colour="bg-info-500 dark:bg-info-400" />
          </div>
          {homeValues.length === 0 && (
            <p className="body-sm text-neutral-500 dark:text-neutral-400">No trend data.</p>
          )}
        </Card>

        <Card padding="sm">
          <div className="flex items-center justify-between mb-2">
            <p className="label">Recent activity</p>
            <button
              onClick={() => onNavigate('transactions')}
              className="body-sm text-info-600 hover:text-info-700 dark:text-info-300 inline-flex items-center gap-1"
            >
              View all <ArrowRight className="w-3.5 h-3.5" />
            </button>
          </div>
          {recentTransactions.length === 0 ? (
            <p className="body-sm text-neutral-500 dark:text-neutral-400">No recent activity.</p>
          ) : (
            <ul className="space-y-1.5">
              {recentTransactions.slice(0, 5).map((t) => {
                const isCredit = ['CREDIT', 'TRANSFER_IN', 'SWEEP_IN', 'POOL_CREDIT', 'TOPUP', 'INTEREST', 'SETTLEMENT_CREDIT'].includes(t.movementType);
                return (
                  <li key={t.id} className="flex items-center gap-2 body-sm">
                    <Banknote className={cn(
                      'w-3.5 h-3.5 shrink-0',
                      isCredit ? 'text-success-500 dark:text-success-300' : 'text-error-500 dark:text-error-300'
                    )} />
                    <span className="flex-1 min-w-0 truncate text-neutral-700 dark:text-neutral-200">{t.description || t.movementType}</span>
                    <span className={cn(
                      'font-mono shrink-0',
                      isCredit ? 'text-success-700 dark:text-success-300' : 'text-error-700 dark:text-error-300',
                    )}>
                      {isCredit ? '+' : '-'}{formatCurrency(t.amount, t.currencyCode)}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
        </Card>
      </div>
    </div>
  );
};
