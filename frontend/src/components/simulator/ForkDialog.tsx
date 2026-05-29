import React from 'react';
import { GitBranch } from 'lucide-react';
import { Modal } from '../ui/enhanced';
import { Button } from '../ui';
import type { SimulatorScenario } from './types';

// ============================================================================
// ForkDialog — confirm forking a scenario into a sibling A/B/C set. Modal
// (no Drawer primitive — Phase-1 precedent). Pure: callbacks out.
// ============================================================================

export interface ForkDialogProps {
  open: boolean;
  scenario: SimulatorScenario;
  busy?: boolean;
  onClose: () => void;
  onConfirm: () => void;
}

export const ForkDialog: React.FC<ForkDialogProps> = ({
  open,
  scenario,
  busy,
  onClose,
  onConfirm,
}) => (
  <Modal
    isOpen={open}
    onClose={onClose}
    title="Fork scenario"
    subtitle="Create an independent A/B/C sibling for side-by-side comparison"
    size="md"
    footer={
      <div className="flex items-center justify-end gap-2">
        <Button variant="ghost" size="sm" onClick={onClose} disabled={busy}>
          Cancel
        </Button>
        <Button
          variant="accent"
          size="sm"
          leftIcon={<GitBranch className="w-4 h-4" />}
          onClick={onConfirm}
          loading={busy}
          disabled={busy}
        >
          Fork
        </Button>
      </div>
    }
  >
    <div className="space-y-3">
      <p className="body-sm text-primary-900 dark:text-neutral-100">
        Forking copies the current proposed structure of{' '}
        <span className="code">{scenario.scenarioReference}</span> into a new
        sibling scenario.
      </p>
      <ul className="space-y-1 body-sm text-neutral-500 dark:text-neutral-400">
        <li>• The fork is edited independently — changes don’t affect the original.</li>
        <li>• It’s auto-labelled (A / B / C…) within the comparison set.</li>
        <li>• All siblings appear side-by-side in the Compare view.</li>
      </ul>
    </div>
  </Modal>
);
