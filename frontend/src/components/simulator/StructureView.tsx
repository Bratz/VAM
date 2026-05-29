import React, { useMemo } from 'react';
import { FlaskConical, GitBranch, Layers } from 'lucide-react';
import { Card, Button } from '../ui';
import { Modal } from '../ui/enhanced';
import { cn } from '../../utils';
import { PhysicalAccountNode } from './PhysicalAccountNode';
import { ShadowVaNode } from './ShadowVaNode';
import { ProposedPoolNode } from './ProposedPoolNode';
import { InventoryPanel } from './InventoryPanel';
import { ScorePanel } from './ScorePanel';
import { groupShadowsByBank } from '../../utils/simulator/inventoryGrouping';
import type {
  ScoreResult,
  SimulatorPhysicalAccount,
  SimulatorScenario,
} from './types';

// ============================================================================
// StructureView — two-column designer. Left: the proposed structure tree
// (shadows grouped by their frozen snapshot bank).
//
// Right column depends on the Phase-2 flag:
//   simulator.v2.score OFF → InventoryPanel (Phase-1 layout, UNCHANGED).
//   simulator.v2.score ON  → ScorePanel; the InventoryPanel moves into a
//                            toolbar-triggered Modal drawer.
// Pure view; the inventory fetches itself.
// ============================================================================

export interface StructureViewProps {
  scenario: SimulatorScenario;
  corporateId: string;
  /** Opens AddShadowDrawer for the picked Physical Account. */
  onAddPhysical?: (account: SimulatorPhysicalAccount) => void;
  /** Opens AddRuleDrawer (enabled once ≥2 shadows exist). */
  onAddRule?: () => void;
  /** Opens AddPoolDrawer (enabled once ≥2 home-bank shadows exist). */
  onAddPool?: () => void;
  /** Lifts loaded inventory accounts up for orphan validation. */
  onInventoryLoaded?: (accounts: SimulatorPhysicalAccount[]) => void;
  score?: ScoreResult | null;
  scoring?: boolean;
  /** Drawer open state (controlled by the page toolbar) — score mode only. */
  inventoryDrawerOpen?: boolean;
  onInventoryDrawerOpenChange?: (open: boolean) => void;
  className?: string;
}

export const StructureView: React.FC<StructureViewProps> = ({
  scenario,
  corporateId,
  onAddPhysical,
  onAddRule,
  onAddPool,
  onInventoryLoaded,
  score = null,
  scoring = false,
  inventoryDrawerOpen = false,
  onInventoryDrawerOpenChange,
  className,
}) => {
  const shadows = useMemo(
    () => scenario.proposedShadows ?? [],
    [scenario.proposedShadows],
  );
  const rules = useMemo(
    () => scenario.proposedRules ?? [],
    [scenario.proposedRules],
  );
  const pools = useMemo(
    () => scenario.proposedPools ?? [],
    [scenario.proposedPools],
  );

  const groups = useMemo(() => groupShadowsByBank(shadows), [shadows]);

  const nameByLocalId = useMemo(() => {
    const m = new Map<string, string>();
    for (const s of shadows) m.set(s.localId, s.proposedVaName);
    return m;
  }, [shadows]);
  const ccyByLocalId = useMemo(() => {
    const m = new Map<string, string>();
    for (const s of shadows) m.set(s.localId, s.snapshotCurrencyCode);
    return m;
  }, [shadows]);
  const homeBankShadowCount = useMemo(
    () =>
      shadows.filter((s) => s.snapshotBankRelationship === 'INTERNAL').length,
    [shadows],
  );

  const rulesBySource = useMemo(() => {
    const m = new Map<string, typeof rules>();
    for (const r of rules) {
      for (const src of r.sourceLocalIds ?? []) {
        const list = m.get(src) ?? [];
        list.push(r);
        m.set(src, list);
      }
    }
    return m;
  }, [rules]);

  const addedIds = useMemo(
    () => new Set(shadows.map((s) => s.physicalAccountId)),
    [shadows],
  );

  const distinctPhysical = addedIds.size;

  return (
    <div
      className={cn(
        'grid grid-cols-1 lg:grid-cols-[1.55fr_1fr] gap-6',
        className,
      )}
    >
      {/* Left — structure tree */}
      <Card padding="sm">
        <div className="flex items-center justify-between gap-2 flex-wrap">
          <h3 className="section-title">Proposed structure</h3>
          <div className="flex items-center gap-3">
            <p className="body-sm text-neutral-500 dark:text-neutral-400">
              {groups.length} bank{groups.length === 1 ? '' : 's'} ·{' '}
              {distinctPhysical} Physical · {shadows.length} Shadow ·{' '}
              {rules.length} rule{rules.length === 1 ? '' : 's'} ·{' '}
              {pools.length} pool{pools.length === 1 ? '' : 's'}
            </p>
            {onAddRule && (
              <Button
                variant="secondary"
                size="sm"
                leftIcon={<GitBranch className="w-4 h-4" />}
                onClick={onAddRule}
                disabled={shadows.length < 2}
                title={
                  shadows.length < 2
                    ? 'Add at least two shadows first'
                    : 'Add a sweep rule'
                }
              >
                Add rule
              </Button>
            )}
            {onAddPool && (
              <Button
                variant="secondary"
                size="sm"
                leftIcon={<Layers className="w-4 h-4" />}
                onClick={onAddPool}
                disabled={homeBankShadowCount < 2}
                title={
                  homeBankShadowCount < 2
                    ? 'Add at least two home-bank shadows first'
                    : 'Add a notional pool'
                }
              >
                Add pool
              </Button>
            )}
          </div>
        </div>

        {shadows.length === 0 ? (
          <div className="flex flex-col items-center text-center gap-2 py-12">
            <FlaskConical className="w-7 h-7 text-neutral-400" aria-hidden />
            <p className="body-sm text-neutral-500 dark:text-neutral-400 max-w-xs">
              Add a Physical Account from the inventory to begin designing the
              structure.
            </p>
          </div>
        ) : (
          <div className="mt-4 space-y-4">
            {groups.map((g) => (
              <PhysicalAccountNode key={g.bankCode} group={g}>
                {g.shadows.map((sh) => (
                  <ShadowVaNode
                    key={sh.localId}
                    shadow={sh}
                    rulesFrom={rulesBySource.get(sh.localId) ?? []}
                    resolveName={(id) => nameByLocalId.get(id) ?? '—'}
                  />
                ))}
              </PhysicalAccountNode>
            ))}

            {pools.length > 0 && (
              <div className="space-y-3">
                <p className="label">Notional pools</p>
                {pools.map((p) => (
                  <ProposedPoolNode
                    key={p.localId}
                    pool={p}
                    resolveName={(id) => nameByLocalId.get(id) ?? '—'}
                    resolveCcy={(id) => ccyByLocalId.get(id)}
                  />
                ))}
              </div>
            )}
          </div>
        )}
      </Card>

      {/* Right column — Optimisation Score (inventory lives in the drawer). */}
      <ScorePanel score={score} loading={scoring} />

      {/* Inventory lives in a toolbar-triggered drawer. */}
      <Modal
        isOpen={inventoryDrawerOpen}
        onClose={() => onInventoryDrawerOpenChange?.(false)}
        title="Available Physical Accounts"
        subtitle="Add a Physical Account to the proposed structure"
        size="lg"
      >
        <InventoryPanel
          corporateId={corporateId}
          addedPhysicalAccountIds={addedIds}
          onAdd={(pa) => {
            onInventoryDrawerOpenChange?.(false);
            onAddPhysical?.(pa);
          }}
          onInventoryLoaded={onInventoryLoaded}
        />
      </Modal>
    </div>
  );
};
