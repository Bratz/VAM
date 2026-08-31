import React, { useEffect, useMemo, useState } from 'react';
import { Modal } from '../ui/enhanced';
import { Button, Input, Select, Badge } from '../ui';
import { newLocalId } from '../../utils/simulator/scenarioModel';
import type { SimulatedPool, SimulatedShadow } from './types';

// ============================================================================
// AddPoolDrawer — add a notional pool over scenario shadows (R3).
//
// Modal-primitive based (same precedent as AddRuleDrawer — no Drawer
// primitive). Pool membership is HOME-BANK-ONLY: the live
// NotionalPoolService.assertPoolEligible rejects any non-home-bank
// PHYSICAL_MIRROR, so only INTERNAL shadows are offered (external / group
// accounts are sweep participants, not pool members). The rate is captured
// EXPLICITLY as an annual percent — engine-truth, no heuristic. A member may
// ALSO be a sweep source/target (hybrid). Mixed-currency members are allowed
// with an FX-honest disclosure (warning, not a blocker) — mirrors the
// scenarioModel validation.
// ============================================================================

export interface AddPoolDrawerProps {
  open: boolean;
  shadows: SimulatedShadow[];
  onClose: () => void;
  onCreate: (pool: SimulatedPool) => void;
}

const CALC_METHODS: Array<{ value: string; label: string }> = [
  { value: 'DAILY_BALANCE', label: 'Daily balance' },
  { value: 'AVERAGE_BALANCE', label: 'Average balance' },
  { value: 'MONTH_END_BALANCE', label: 'Month-end balance' },
];

export const AddPoolDrawer: React.FC<AddPoolDrawerProps> = ({
  open,
  shadows,
  onClose,
  onCreate,
}) => {
  const [poolName, setPoolName] = useState('Notional pool');
  const [poolRatePct, setPoolRatePct] = useState('');
  const [calcMethod, setCalcMethod] = useState('DAILY_BALANCE');
  const [memberIds, setMemberIds] = useState<string[]>([]);

  useEffect(() => {
    if (open) {
      setPoolName('Notional pool');
      setPoolRatePct('');
      setCalcMethod('DAILY_BALANCE');
      setMemberIds([]);
    }
  }, [open]);

  // Home-bank only — mirrors the live assertPoolEligible guard.
  const eligible = useMemo(
    () => shadows.filter((s) => s.snapshotBankRelationship === 'INTERNAL'),
    [shadows],
  );
  const byLocalId = useMemo(
    () => new Map(shadows.map((s) => [s.localId, s])),
    [shadows],
  );

  const toggleMember = (id: string) =>
    setMemberIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );

  const memberCurrencies = useMemo(
    () => [
      ...new Set(
        memberIds
          .map((id) => byLocalId.get(id)?.snapshotCurrencyCode)
          .filter((c): c is string => Boolean(c)),
      ),
    ],
    [memberIds, byLocalId],
  );
  const poolCurrency = memberCurrencies[0] ?? '';
  const mixedCcy = memberCurrencies.length > 1;

  const rateNum = Number(poolRatePct);
  const rateValid =
    poolRatePct.trim() !== '' && Number.isFinite(rateNum) && rateNum >= 0;
  const canCreate =
    poolName.trim().length > 0 && memberIds.length >= 2 && rateValid;

  const handleCreate = () => {
    if (!canCreate) return;
    onCreate({
      localId: newLocalId('pool'),
      poolName: poolName.trim(),
      poolCurrency,
      poolRatePct: rateNum,
      interestCalcMethod: calcMethod,
      memberLocalIds: memberIds,
    });
  };

  return (
    <Modal
      isOpen={open}
      onClose={onClose}
      title="Add notional pool"
      subtitle="Home-bank accounts notionally offset balances — no cash moves"
      size="lg"
      footer={
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={onClose}>
            Cancel
          </Button>
          <Button
            variant="primary"
            size="sm"
            onClick={handleCreate}
            disabled={!canCreate}
          >
            Add pool
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        <Input
          label="Pool name"
          value={poolName}
          onChange={(e) => setPoolName(e.target.value)}
          inputSize="sm"
        />

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Input
            label="Pool rate (% / yr)"
            type="number"
            value={poolRatePct}
            onChange={(e) => setPoolRatePct(e.target.value)}
            inputSize="sm"
            placeholder="e.g. 3.5"
          />
          <Select
            label="Interest calc method"
            value={calcMethod}
            onChange={(e) => setCalcMethod(e.target.value)}
            selectSize="sm"
            options={CALC_METHODS}
          />
        </div>

        {/* Member picker — home-bank shadows only */}
        <div>
          <div className="flex items-center justify-between gap-2 mb-1">
            <p className="label">Members</p>
            {poolCurrency && (
              <span className="code text-neutral-400">
                pool ccy · {poolCurrency}
              </span>
            )}
          </div>
          <div className="rounded-xl border border-neutral-300 dark:border-primary-700 divide-y divide-neutral-100 dark:divide-primary-800/40 max-h-40 overflow-auto">
            {eligible.length === 0 ? (
              <p className="px-3 py-3 body-sm text-neutral-500 dark:text-neutral-400">
                No home-bank (INTERNAL) shadows yet. Only home-bank accounts
                can be pooled — external / group accounts are sweep
                participants, not pool members.
              </p>
            ) : (
              eligible.map((s) => (
                <label
                  key={s.localId}
                  className="flex items-center gap-2 px-3 py-2 cursor-pointer body-sm"
                >
                  <input
                    type="checkbox"
                    checked={memberIds.includes(s.localId)}
                    onChange={() => toggleMember(s.localId)}
                    className="accent-primary-600"
                  />
                  <span className="truncate text-primary-900 dark:text-neutral-100">
                    {s.proposedVaName}
                  </span>
                  <span className="code text-neutral-400 ml-auto">
                    {s.snapshotCurrencyCode}
                  </span>
                </label>
              ))
            )}
          </div>
          <p className="mt-1 body-sm text-neutral-500 dark:text-neutral-400">
            {memberIds.length} selected · a pool needs at least two members; a
            member may also be a sweep source/target (hybrid).
          </p>
        </div>

        {mixedCcy && (
          <div className="flex items-center gap-2 flex-wrap pt-1">
            <Badge variant="warning" size="sm">
              Mixed currency
            </Badge>
            <span className="body-sm text-warning-700 dark:text-warning-300">
              Members span {memberCurrencies.join(', ')} — the score discloses
              an FX-honest cross-currency conversion.
            </span>
          </div>
        )}
      </div>
    </Modal>
  );
};
