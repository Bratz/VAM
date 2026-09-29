import React from 'react';
import { Save, GitBranch, Undo2, Upload } from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { cn } from '../../utils';
import { SandboxBadge } from './SandboxBadge';
import { ViewSwitcher, type SimulatorView } from './ViewSwitcher';
import type { ScenarioStatus, SimulatorScenario } from './types';

// ============================================================================
// ScenarioHeader — pure view. Scenario identity + lifecycle + save controls.
//
// Props in, callbacks out. No network, no business logic (the page controller
// owns dirty-tracking, validation, persistence, audit). Fork is gated behind
// simulator.v4.compare — disabled in Phase 1.
// ============================================================================

const STATUS_VARIANT: Record<
  ScenarioStatus,
  'neutral' | 'info' | 'warning' | 'success'
> = {
  DRAFT: 'neutral',
  READY: 'info',
  PROPOSED: 'warning',
  ACTIVATED: 'success',
  ARCHIVED: 'neutral',
};

export interface ScenarioHeaderProps {
  scenario: SimulatorScenario;
  draftName: string;
  dirty: boolean;
  saving: boolean;
  /** dirty && name non-empty && validation passes (computed by the page). */
  canSave: boolean;
  /** e.g. "last saved 14m ago" — already humanised by the page. */
  lastSavedLabel?: string;
  onRename: (name: string) => void;
  onSave: () => void;
  onDiscard: () => void;
  onFork?: () => void;
  // ---- Phase 3 ----
  view?: SimulatorView;
  onViewChange?: (v: SimulatorView) => void;
  /** status==='READY' && score computed (page-computed). */
  proposeReady?: boolean;
  onPropose?: () => void;
}

export const ScenarioHeader: React.FC<ScenarioHeaderProps> = ({
  scenario,
  draftName,
  dirty,
  saving,
  canSave,
  lastSavedLabel,
  onRename,
  onSave,
  onDiscard,
  onFork,
  view,
  onViewChange,
  proposeReady = false,
  onPropose,
}) => {
  const statusVariant = STATUS_VARIANT[scenario.status] ?? 'neutral';

  return (
    <Card padding="sm" className="bg-primary-50/40 dark:bg-primary-800/30">
      <div className="flex flex-wrap items-start justify-between gap-4">
        {/* Identity. The floor is load-bearing: the controls opposite are
            shrink-0, so without it this block is the only thing that can give
            and the name input collapses (measured at 63px -- 8 characters of
            "Untitled scenario"). With it, the controls wrap to their own row
            when the header is too narrow for both, which is what the
            flex-wrap on the parent was there for. */}
        <div className="flex-1 min-w-[20rem]">
          <div className="flex items-center gap-2 flex-wrap">
            <input
              type="text"
              value={draftName}
              onChange={(e) => onRename(e.target.value)}
              aria-label="Scenario name"
              placeholder="Untitled scenario"
              className={cn(
                'section-title bg-transparent flex-1 min-w-[10rem]',
                'border-0 border-b border-transparent hover:border-neutral-300',
                'dark:hover:border-primary-700 focus:border-primary-500',
                'focus:outline-none focus-visible:ring-0 px-0.5 py-0.5 rounded-none',
              )}
            />
            <SandboxBadge />
            <Badge variant={statusVariant} size="sm">
              {scenario.status}
            </Badge>
          </div>
          <div className="mt-1 flex items-center gap-2 body-sm text-neutral-500 dark:text-neutral-400">
            <span className="code">{scenario.scenarioReference}</span>
            {lastSavedLabel && (
              <>
                <span aria-hidden>·</span>
                <span>{dirty ? 'unsaved changes' : lastSavedLabel}</span>
              </>
            )}
          </div>
        </div>

        {/* Controls */}
        <div className="flex items-center gap-2 shrink-0 flex-wrap justify-end">
          {view && onViewChange && (
            <ViewSwitcher value={view} onChange={onViewChange} />
          )}
          <Button
            variant="ghost"
            size="sm"
            leftIcon={<Undo2 className="w-4 h-4" />}
            onClick={onDiscard}
            disabled={!dirty || saving}
          >
            Discard
          </Button>
          <Button
            variant="secondary"
            size="sm"
            leftIcon={<GitBranch className="w-4 h-4" />}
            onClick={onFork}
            title="Fork this scenario"
          >
            Fork
          </Button>
          <Button
            variant="primary"
            size="sm"
            leftIcon={<Save className="w-4 h-4" />}
            onClick={onSave}
            loading={saving}
            disabled={!canSave || saving}
          >
            Save
          </Button>
          <Button
            variant="accent"
            size="sm"
            leftIcon={<Upload className="w-4 h-4" />}
            onClick={onPropose}
            disabled={!proposeReady}
            title={
              proposeReady
                ? 'Propose this structure as live rules'
                : 'Mark the scenario READY and compute the score first'
            }
          >
            Propose as live rules
          </Button>
        </div>
      </div>
    </Card>
  );
};
