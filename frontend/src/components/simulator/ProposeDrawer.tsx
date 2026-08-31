import React, { useEffect, useState } from 'react';
import { AlertTriangle } from 'lucide-react';
import { Modal } from '../ui/enhanced';
import { Button, Input, TextArea } from '../ui';
import { DiffSummaryStrip } from './DiffSummaryStrip';
import type { DiffResult, SimulatorScenario } from './types';

// ============================================================================
// ProposeDrawer — single-user activation confirm (no co-approver). Modal
// (no Drawer primitive — Phase-1 precedent). Re-shows the diff summary as the
// confirmation; a REQUIRED "I have reviewed the score & assumptions" checkbox
// is the single-user safety gate standing in for co-approval.
// ============================================================================

export interface ProposeDrawerProps {
  open: boolean;
  scenario: SimulatorScenario;
  diff: DiffResult | null;
  busy?: boolean;
  onClose: () => void;
  onConfirm: (opts: { notes?: string; scheduledAt?: string }) => void;
}

export const ProposeDrawer: React.FC<ProposeDrawerProps> = ({
  open,
  scenario,
  diff,
  busy,
  onClose,
  onConfirm,
}) => {
  const [notes, setNotes] = useState('');
  const [scheduledAt, setScheduledAt] = useState('');
  const [reviewed, setReviewed] = useState(false);

  useEffect(() => {
    if (open) {
      setNotes('');
      setScheduledAt('');
      setReviewed(false);
    }
  }, [open]);

  const changed = diff
    ? diff.counts.add + diff.counts.modify + diff.counts.retire
    : 0;
  const canConfirm = reviewed && !busy && changed > 0;

  return (
    <Modal
      isOpen={open}
      onClose={onClose}
      title="Propose as live rules"
      subtitle="Single-user activation — writes the proposed structure to the live tables"
      size="lg"
      footer={
        <div className="flex items-center justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={onClose} disabled={busy}>
            Cancel
          </Button>
          <Button
            variant="accent"
            size="sm"
            onClick={() => onConfirm({ notes: notes.trim() || undefined, scheduledAt: scheduledAt || undefined })}
            loading={busy}
            disabled={!canConfirm}
          >
            Activate now
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        <div className="flex items-start gap-2 px-3 py-2 rounded-xl border border-warning-200 bg-warning-50 dark:border-warning-500/30 dark:bg-warning-500/10">
          <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0 text-warning-600 dark:text-warning-300" />
          <p className="body-sm text-warning-800 dark:text-warning-200">
            This is the only path from the sandbox to live operations. It
            creates live sweep rules between existing Shadow VAs and is fully
            recorded to the audit trail. The action is atomic — any failure
            rolls back with no partial writes.
          </p>
        </div>

        <div>
          <p className="label mb-2">Changes to apply</p>
          {diff ? (
            <DiffSummaryStrip counts={diff.counts} />
          ) : (
            <p className="body-sm text-neutral-500 dark:text-neutral-400">
              Open the Diff view first to capture the baseline.
            </p>
          )}
        </div>

        <Input
          label="Scheduled activation (optional — default now)"
          type="datetime-local"
          value={scheduledAt}
          onChange={(e) => setScheduledAt(e.target.value)}
          inputSize="sm"
        />

        <TextArea
          label="Notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          placeholder="Optional context recorded with the activation"
          textareaSize="sm"
        />

        <label className="flex items-start gap-2 cursor-pointer body-sm text-primary-900 dark:text-neutral-100">
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
            className="mt-0.5 accent-primary-600"
          />
          <span>
            I have reviewed the Optimisation Score and its assumptions, and the
            diff above, for{' '}
            <span className="code">{scenario.scenarioReference}</span>.
          </span>
        </label>

        {changed === 0 && (
          <p className="body-sm text-neutral-500 dark:text-neutral-400">
            Nothing to activate — the proposed structure matches the live
            baseline.
          </p>
        )}
      </div>
    </Modal>
  );
};
