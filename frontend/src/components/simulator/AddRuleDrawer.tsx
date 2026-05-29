import React, { useEffect, useMemo, useState } from 'react';
import { Modal } from '../ui/enhanced';
import { Button, Input, Select, Badge } from '../ui';
import {
  newLocalId,
  deriveRuleFlags,
} from '../../utils/simulator/scenarioModel';
import type {
  SimulatedRule,
  SimulatedShadow,
  SweepFrequency,
  SweepType,
} from './types';

// ============================================================================
// AddRuleDrawer — add a sweep rule between scenario shadows.
//
// Modal-primitive based (no Drawer primitive — see AddShadowDrawer note).
// `isCrossBank` / `isCrossBorder` / `paymentRail` are auto-derived from the
// source/target snapshots via the pure `deriveRuleFlags` and surfaced as
// read-only chips. In-form gating mirrors the scenarioModel validation rules.
// ============================================================================

export interface AddRuleDrawerProps {
  open: boolean;
  shadows: SimulatedShadow[];
  onClose: () => void;
  onCreate: (rule: SimulatedRule) => void;
}

const SWEEP_TYPES: Array<{ value: SweepType; label: string }> = [
  { value: 'ZERO_BALANCE', label: 'Zero-balance (ZBA)' },
  { value: 'TARGET_BALANCE', label: 'Target balance' },
  { value: 'THRESHOLD', label: 'Threshold' },
  { value: 'PERCENTAGE', label: 'Percentage' },
];

const FREQUENCIES: Array<{ value: SweepFrequency; label: string }> = [
  { value: 'DAILY', label: 'Daily' },
  { value: 'WEEKLY', label: 'Weekly' },
  { value: 'MONTHLY', label: 'Monthly' },
  { value: 'ON_DEMAND', label: 'On demand' },
];

export const AddRuleDrawer: React.FC<AddRuleDrawerProps> = ({
  open,
  shadows,
  onClose,
  onCreate,
}) => {
  const [ruleName, setRuleName] = useState('Sweep rule');
  const [sweepType, setSweepType] = useState<SweepType>('ZERO_BALANCE');
  const [frequency, setFrequency] = useState<SweepFrequency>('DAILY');
  const [executionTime, setExecutionTime] = useState('');
  const [sourceIds, setSourceIds] = useState<string[]>([]);
  const [targetId, setTargetId] = useState('');
  const [targetAmount, setTargetAmount] = useState('');
  const [thresholdMin, setThresholdMin] = useState('');
  const [thresholdMax, setThresholdMax] = useState('');
  const [percentage, setPercentage] = useState('');

  useEffect(() => {
    if (open) {
      setRuleName('Sweep rule');
      setSweepType('ZERO_BALANCE');
      setFrequency('DAILY');
      setExecutionTime('');
      setSourceIds([]);
      setTargetId('');
      setTargetAmount('');
      setThresholdMin('');
      setThresholdMax('');
      setPercentage('');
    }
  }, [open]);

  const byLocalId = useMemo(
    () => new Map(shadows.map((s) => [s.localId, s])),
    [shadows],
  );

  const toggleSource = (id: string) =>
    setSourceIds((prev) =>
      prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id],
    );

  // Mirrors scenarioModel: ≥1 source, exactly 1 target, target ∉ sources.
  const targetIsSource = targetId !== '' && sourceIds.includes(targetId);
  const canCreate =
    ruleName.trim().length > 0 &&
    sourceIds.length > 0 &&
    targetId !== '' &&
    !targetIsSource;

  // Live derived flags preview (same pure fn the model uses).
  const preview = useMemo(() => {
    if (sourceIds.length === 0 || !targetId) return null;
    const probe: SimulatedRule = {
      localId: 'preview',
      ruleName,
      sweepType,
      frequency,
      sourceLocalIds: sourceIds,
      targetLocalId: targetId,
      isCrossBank: false,
      isCrossBorder: false,
    };
    return deriveRuleFlags(probe, byLocalId);
  }, [sourceIds, targetId, ruleName, sweepType, frequency, byLocalId]);

  const handleCreate = () => {
    if (!canCreate) return;
    const base: SimulatedRule = {
      localId: newLocalId('rule'),
      ruleName: ruleName.trim(),
      sweepType,
      frequency,
      executionTime: executionTime.trim() || undefined,
      sourceLocalIds: sourceIds,
      targetLocalId: targetId,
      targetAmount:
        sweepType === 'TARGET_BALANCE' && targetAmount
          ? Number(targetAmount)
          : undefined,
      thresholdMin:
        sweepType === 'THRESHOLD' && thresholdMin
          ? Number(thresholdMin)
          : undefined,
      thresholdMax:
        sweepType === 'THRESHOLD' && thresholdMax
          ? Number(thresholdMax)
          : undefined,
      percentage:
        sweepType === 'PERCENTAGE' && percentage
          ? Number(percentage)
          : undefined,
      isCrossBank: false,
      isCrossBorder: false,
    };
    onCreate({ ...base, ...deriveRuleFlags(base, byLocalId) });
  };

  return (
    <Modal
      isOpen={open}
      onClose={onClose}
      title="Add sweep rule"
      subtitle="Routing is derived automatically from the source / target banks"
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
            Add rule
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        <Input
          label="Rule name"
          value={ruleName}
          onChange={(e) => setRuleName(e.target.value)}
          inputSize="sm"
        />

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Select
            label="Sweep type"
            value={sweepType}
            onChange={(e) => setSweepType(e.target.value as SweepType)}
            selectSize="sm"
            options={SWEEP_TYPES}
          />
          <Select
            label="Frequency"
            value={frequency}
            onChange={(e) =>
              setFrequency(e.target.value as SweepFrequency)
            }
            selectSize="sm"
            options={FREQUENCIES}
          />
        </div>

        {sweepType === 'TARGET_BALANCE' && (
          <Input
            label="Target amount"
            type="number"
            value={targetAmount}
            onChange={(e) => setTargetAmount(e.target.value)}
            inputSize="sm"
          />
        )}
        {sweepType === 'THRESHOLD' && (
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Threshold min"
              type="number"
              value={thresholdMin}
              onChange={(e) => setThresholdMin(e.target.value)}
              inputSize="sm"
            />
            <Input
              label="Threshold max"
              type="number"
              value={thresholdMax}
              onChange={(e) => setThresholdMax(e.target.value)}
              inputSize="sm"
            />
          </div>
        )}
        {sweepType === 'PERCENTAGE' && (
          <Input
            label="Percentage"
            type="number"
            value={percentage}
            onChange={(e) => setPercentage(e.target.value)}
            inputSize="sm"
          />
        )}

        <Input
          label="Execution time (optional)"
          value={executionTime}
          onChange={(e) => setExecutionTime(e.target.value)}
          inputSize="sm"
          placeholder="e.g. 18:00"
        />

        {/* Source picker (multi) */}
        <div>
          <p className="label mb-1">Sources</p>
          <div className="rounded-xl border border-neutral-300 dark:border-primary-700 divide-y divide-neutral-100 dark:divide-primary-800/40 max-h-40 overflow-auto">
            {shadows.length === 0 ? (
              <p className="px-3 py-3 body-sm text-neutral-500 dark:text-neutral-400">
                Add at least two shadows before creating a rule.
              </p>
            ) : (
              shadows.map((s) => (
                <label
                  key={s.localId}
                  className="flex items-center gap-2 px-3 py-2 cursor-pointer body-sm"
                >
                  <input
                    type="checkbox"
                    checked={sourceIds.includes(s.localId)}
                    onChange={() => toggleSource(s.localId)}
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
        </div>

        <Select
          label="Target"
          value={targetId}
          onChange={(e) => setTargetId(e.target.value)}
          selectSize="sm"
          options={[
            { value: '', label: 'Select target shadow…' },
            ...shadows.map((s) => ({
              value: s.localId,
              label: `${s.proposedVaName} (${s.snapshotCurrencyCode})`,
            })),
          ]}
        />

        {targetIsSource && (
          <p className="body-sm text-error-700 dark:text-error-300">
            The target cannot also be one of the sources.
          </p>
        )}

        {preview && (
          <div className="flex items-center gap-2 flex-wrap pt-1">
            <span className="label">Routing</span>
            <Badge
              variant={preview.isCrossBank ? 'warning' : 'neutral'}
              size="sm"
            >
              {preview.isCrossBank ? 'Cross-bank' : 'Same-bank'}
            </Badge>
            {preview.isCrossBorder && (
              <Badge variant="warning" size="sm">
                Cross-border
              </Badge>
            )}
            {preview.paymentRail && (
              <Badge variant="info" size="sm">
                {preview.paymentRail}
              </Badge>
            )}
          </div>
        )}
      </div>
    </Modal>
  );
};
