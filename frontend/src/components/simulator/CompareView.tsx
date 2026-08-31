import React, { useMemo } from 'react';
import { GitBranch, Loader2 } from 'lucide-react';
import { Card, Button } from '../ui';
import { cn } from '../../utils';
import { ScenarioColumn } from './ScenarioColumn';
import type { ScoreResult, SimulatorScenario } from './types';

// ============================================================================
// CompareView — 1–3 scenario columns side-by-side. Pure: the page supplies a
// `buildScore` closure (computeScore with the shared inventory/tariffs/fx).
// "Most balanced" = highest net benefit; ties → lowest operational risk.
// ============================================================================

const MAX_COLS = 3;

export interface CompareViewProps {
  scenarios: SimulatorScenario[];
  buildScore: (s: SimulatorScenario) => ScoreResult | null;
  loading?: boolean;
  onPromote?: (s: SimulatorScenario) => void;
  onForkMore?: () => void;
  className?: string;
}

function operationalRisk(score: ScoreResult | null): number {
  if (!score) return Number.NEGATIVE_INFINITY;
  // Least-negative sum of the risk lines = lowest risk (used as tiebreak).
  return score.lines
    .filter((l) =>
      ['operational', 'sourceQuality', 'consentTimeline'].includes(l.key),
    )
    .reduce((s, l) => s + l.deltaAnnual, 0);
}

export const CompareView: React.FC<CompareViewProps> = ({
  scenarios,
  buildScore,
  loading,
  onPromote,
  onForkMore,
  className,
}) => {
  const cols = useMemo(
    () =>
      scenarios.slice(0, MAX_COLS).map((s) => ({
        scenario: s,
        score: buildScore(s),
      })),
    [scenarios, buildScore],
  );

  const bestId = useMemo(() => {
    let best: { id: string; net: number; risk: number } | null = null;
    for (const c of cols) {
      if (!c.score) continue;
      const net = c.score.netAnnualBenefit;
      const risk = operationalRisk(c.score);
      if (
        !best ||
        net > best.net ||
        (net === best.net && risk > best.risk)
      ) {
        best = { id: c.scenario.id, net, risk };
      }
    }
    return best?.id ?? null;
  }, [cols]);

  if (loading) {
    return (
      <Card padding="lg" className={className}>
        <div className="flex items-center justify-center gap-2 py-12 body-sm text-neutral-500 dark:text-neutral-400">
          <Loader2 className="w-4 h-4 animate-spin" />
          Loading comparison set…
        </div>
      </Card>
    );
  }

  const placeholders = Math.max(0, MAX_COLS - cols.length);

  return (
    <div
      className={cn(
        'grid grid-cols-1 lg:grid-cols-3 gap-4 items-stretch',
        className,
      )}
    >
      {cols.map((c) => (
        <ScenarioColumn
          key={c.scenario.id}
          scenario={c.scenario}
          score={c.score}
          isBest={c.scenario.id === bestId}
          onPromote={onPromote}
        />
      ))}
      {Array.from({ length: placeholders }).map((_, i) => (
        <Card
          key={`ph-${i}`}
          padding="sm"
          className="flex flex-col items-center justify-center text-center gap-3 border-dashed"
        >
          <GitBranch className="w-7 h-7 text-neutral-400" aria-hidden />
          <p className="body-sm text-neutral-500 dark:text-neutral-400 max-w-[14rem]">
            Fork this scenario to compare A/B/C structures side-by-side.
          </p>
          {onForkMore && (
            <Button
              variant="secondary"
              size="sm"
              leftIcon={<GitBranch className="w-4 h-4" />}
              onClick={onForkMore}
            >
              Fork to compare
            </Button>
          )}
        </Card>
      ))}
    </div>
  );
};
