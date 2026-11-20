import React from 'react';
import { Save, GitBranch, Undo2, Upload, Archive, Building2 } from 'lucide-react';
import { Button, Badge, RowActions } from '../ui';
import { cn } from '../../utils';
import { SandboxBadge } from './SandboxBadge';
import { ViewSwitcher, type SimulatorView } from './ViewSwitcher';
import { SCENARIO_STATUS_LABEL, type ScenarioStatus, type SimulatorScenario } from './types';

// ============================================================================
// ScenarioHeader — pure view. Scenario identity + lifecycle + save controls.
//
// Props in, callbacks out. No network, no business logic (the page controller
// owns dirty-tracking, validation, persistence, audit). Renders no card of its
// own: it continues the page's scenario toolbar row, after the scenario picker.
// Fork / Inventory / Archive sit in the overflow menu.
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
  onInventory?: () => void;
  onArchive?: () => void;
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
  onInventory,
  onArchive,
  view,
  onViewChange,
  proposeReady = false,
  onPropose,
}) => {
  const statusVariant = STATUS_VARIANT[scenario.status] ?? 'neutral';

  return (
    <>
      {/* Identity. The floor keeps the name input from collapsing when the
          controls opposite take the line; they wrap to their own row instead. */}
      <div className="flex flex-1 min-w-[20rem] items-center gap-2 flex-wrap">
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
          {SCENARIO_STATUS_LABEL[scenario.status] ?? scenario.status}
        </Badge>
        {lastSavedLabel && (
          <span className="caption whitespace-nowrap">{dirty ? 'unsaved changes' : lastSavedLabel}</span>
        )}
      </div>

      {/* Controls */}
      <div className="flex items-center gap-2 flex-wrap justify-end">
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
              : 'Mark the scenario ready and compute the score first'
          }
        >
          Propose as live rules
        </Button>
        <RowActions
          label="More scenario actions"
          actions={[
            { label: 'Fork', icon: <GitBranch className="w-4 h-4" />, onClick: () => onFork?.(), hidden: !onFork },
            { label: 'Inventory', icon: <Building2 className="w-4 h-4" />, onClick: () => onInventory?.(), hidden: !onInventory },
            { label: 'Archive', icon: <Archive className="w-4 h-4" />, onClick: () => onArchive?.(), hidden: !onArchive, danger: true, divider: true },
          ]}
        />
      </div>
    </>
  );
};
