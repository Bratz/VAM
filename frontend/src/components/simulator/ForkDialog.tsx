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
    <p className="body-sm text-primary-900 dark:text-neutral-100">
      Copies <span className="code">{scenario.scenarioReference}</span> into a
      new scenario you can edit on its own and compare side by side.
    </p>
  </Modal>
);
