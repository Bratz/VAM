import React, { useMemo } from 'react';
import { ArrowRight, Star } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { cn, formatCompactCurrency } from '../../utils';
import { groupShadowsByBank } from '../../utils/simulator/inventoryGrouping';
import type { ScoreResult, SimulatorScenario } from './types';

// ============================================================================
// ScenarioColumn — one scenario in CompareView. Pure: name + fork label,
// compact counts, the (up to) six score lines, "Promote to Diff" CTA. The
// "Most balanced" column gets a border-info accent + badge.
// ============================================================================

export interface ScenarioColumnProps {
  scenario: SimulatorScenario;
  score: ScoreResult | null;
  isBest?: boolean;
  onPromote?: (scenario: SimulatorScenario) => void;
  className?: string;
}

function tone(delta: number): string {
  return delta > 0
    ? 'text-success-700 dark:text-success-300'
    : delta < 0
      ? 'text-error-700 dark:text-error-300'
      : 'text-neutral-500 dark:text-neutral-400';
}

export const ScenarioColumn: React.FC<ScenarioColumnProps> = ({
  scenario,
  score,
  isBest,
  onPromote,
  className,
}) => {
  const shadows = useMemo(
    () => scenario.proposedShadows ?? [],
    [scenario.proposedShadows],
  );
  const rules = scenario.proposedRules ?? [];
  const bankCount = useMemo(
    () => groupShadowsByBank(shadows).length,
    [shadows],
  );

  return (
    <Card
      padding="sm"
      className={cn(
        'flex flex-col gap-3',
        isBest && 'border-2 border-info-500',
        className,
      )}
    >
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <div className="flex items-center gap-2 flex-wrap">
            {scenario.forkLabel && (
              <Badge variant="info" size="sm">
                {scenario.forkLabel}
              </Badge>
            )}
            <span className="section-title truncate">
              {scenario.scenarioName}
            </span>
          </div>
          <p className="body-sm text-neutral-500 dark:text-neutral-400 mt-0.5">
            <span className="code">{scenario.scenarioReference}</span> ·{' '}
            {scenario.status}
          </p>
        </div>
        {isBest && (
          <Badge variant="info" size="xs">
            <Star className="w-3 h-3" aria-hidden /> Most balanced
          </Badge>
        )}
      </div>

      <p className="body-sm text-neutral-500 dark:text-neutral-400">
        {bankCount} bank{bankCount === 1 ? '' : 's'} · {shadows.length} shadow
        {shadows.length === 1 ? '' : 's'} · {rules.length} rule
        {rules.length === 1 ? '' : 's'}
      </p>

      {score ? (
        <>
          <div className="flex items-end gap-2">
            <span className="stat-value-sm">{score.headline}</span>
            <span className="body-sm text-neutral-500 dark:text-neutral-400 pb-0.5">
              / 100
            </span>
          </div>
          <div className="divide-y divide-neutral-100 dark:divide-primary-800/40">
            {score.lines.map((l) => (
              <div
                key={l.key}
                className="flex items-center justify-between gap-2 py-1.5"
              >
                <span className="body-sm text-neutral-600 dark:text-neutral-300">
                  {l.label}
                </span>
                <span className={cn('code', tone(l.deltaAnnual))}>
                  {l.deltaAnnual > 0 ? '+' : l.deltaAnnual < 0 ? '−' : ''}
                  {formatCompactCurrency(
                    Math.abs(l.deltaAnnual),
                    score.baseCurrency,
                  )}
                </span>
              </div>
            ))}
          </div>
          <div className="flex items-center justify-between gap-2 pt-1">
            <span className="label">Net</span>
            <span className={cn('code', tone(score.netAnnualBenefit))}>
              {score.netAnnualBenefit > 0
                ? '+'
                : score.netAnnualBenefit < 0
                  ? '−'
                  : ''}
              {formatCompactCurrency(
                Math.abs(score.netAnnualBenefit),
                score.baseCurrency,
              )}
            </span>
          </div>
        </>
      ) : (
        <p className="body-sm text-neutral-500 dark:text-neutral-400 py-4 text-center">
          Score unavailable
        </p>
      )}

      {onPromote && (
        <Button
          variant="secondary"
          size="sm"
          className="mt-auto"
          rightIcon={<ArrowRight className="w-4 h-4" />}
          onClick={() => onPromote(scenario)}
        >
          Promote to Diff
        </Button>
      )}
    </Card>
  );
};
