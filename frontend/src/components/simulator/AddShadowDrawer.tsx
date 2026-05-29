import React, { useEffect, useState } from 'react';
import { Building2 } from 'lucide-react';
import { Modal } from '../ui/enhanced';
import { Button, Input, Select, TextArea } from '../ui';
import { formatCurrency } from '../../utils';
import { newLocalId } from '../../utils/simulator/scenarioModel';
import { classifyRelationship } from '../../utils/simulator/inventoryGrouping';
import type {
  ShadowRole,
  SimulatedShadow,
  SimulatorPhysicalAccount,
} from './types';

// ============================================================================
// AddShadowDrawer — add a Shadow VA over a selected Physical Account.
//
// No right-side Drawer primitive exists in the design system; built on the
// `Modal` primitive (Escape / scroll-lock / backdrop already handled) rather
// than hand-rolling an untested slide-over. Pure form: callbacks out.
//
// Snapshot fields are FROZEN from the Physical Account at add time so the
// scenario survives later inventory changes.
// ============================================================================

export interface AddShadowDrawerProps {
  open: boolean;
  physicalAccount: SimulatorPhysicalAccount | null;
  /** Existing scenario shadows — candidate parents for a CHILD. */
  existingShadows: SimulatedShadow[];
  onClose: () => void;
  onCreate: (shadow: SimulatedShadow) => void;
}

export const AddShadowDrawer: React.FC<AddShadowDrawerProps> = ({
  open,
  physicalAccount,
  existingShadows,
  onClose,
  onCreate,
}) => {
  const [name, setName] = useState('');
  const [role, setRole] = useState<ShadowRole>('HEADER');
  const [parentLocalId, setParentLocalId] = useState('');
  const [notes, setNotes] = useState('');

  // Reset the form whenever a new Physical Account is brought into the drawer.
  useEffect(() => {
    if (open && physicalAccount) {
      setName(`${physicalAccount.accountName} — Shadow`);
      setRole(existingShadows.length === 0 ? 'HEADER' : 'CHILD');
      setParentLocalId('');
      setNotes('');
    }
  }, [open, physicalAccount, existingShadows.length]);

  if (!physicalAccount) return null;
  const pa = physicalAccount;

  const parentOptions = [
    { value: '', label: 'No parent (top of group)' },
    ...existingShadows.map((s) => ({
      value: s.localId,
      label: `${s.proposedVaName} (${s.role === 'HEADER' ? 'Header' : 'Child'})`,
    })),
  ];

  const canCreate = name.trim().length > 0;

  const handleCreate = () => {
    if (!canCreate) return;
    const shadow: SimulatedShadow = {
      localId: newLocalId('sh'),
      physicalAccountId: pa.id,
      proposedVaName: name.trim(),
      role,
      parentLocalId:
        role === 'CHILD' && parentLocalId ? parentLocalId : undefined,
      notes: notes.trim() || undefined,
      snapshotBankCode: pa.bankCode,
      snapshotBankName: pa.bankName,
      snapshotBankRelationship: classifyRelationship(pa),
      snapshotBankCountry: pa.bankCountry,
      snapshotDataSource: pa.dataSource,
      snapshotCurrencyCode: pa.currencyCode,
      snapshotConsentExpiresAt: pa.consentExpiresAt,
      snapshotInterestRate: pa.interestRate,
      snapshotOverdraftLimit: pa.overdraftLimit,
    };
    onCreate(shadow);
  };

  return (
    <Modal
      isOpen={open}
      onClose={onClose}
      title="Add Shadow VA"
      subtitle="A simulated mirror over a live Physical Account"
      size="md"
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
            Add shadow
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        {/* Frozen Physical Account context */}
        <div className="rounded-xl border border-neutral-200/80 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-900/40 px-4 py-3">
          <div className="flex items-center gap-2">
            <Building2
              className="w-4 h-4 text-neutral-500 shrink-0"
              aria-hidden
            />
            <span className="section-title truncate">{pa.bankName}</span>
            <span className="code text-neutral-400">{pa.bankCode}</span>
          </div>
          <div className="mt-1 flex items-center gap-2 flex-wrap body-sm text-neutral-500 dark:text-neutral-400">
            <span className="code">{pa.accountNumber}</span>
            <span aria-hidden>·</span>
            <span>{formatCurrency(pa.currentBalance, pa.currencyCode)}</span>
            {pa.dataSource && (
              <>
                <span aria-hidden>·</span>
                <span>{pa.dataSource}</span>
              </>
            )}
          </div>
        </div>

        <Input
          label="Proposed VA name"
          value={name}
          onChange={(e) => setName(e.target.value)}
          inputSize="sm"
          placeholder="e.g. Treasury Master USD"
        />

        <Select
          label="Role"
          value={role}
          onChange={(e) => setRole(e.target.value as ShadowRole)}
          selectSize="sm"
          options={[
            { value: 'HEADER', label: 'Header (concentration target)' },
            { value: 'CHILD', label: 'Child (sweep source)' },
          ]}
        />

        {role === 'CHILD' && existingShadows.length > 0 && (
          <Select
            label="Parent in structure"
            value={parentLocalId}
            onChange={(e) => setParentLocalId(e.target.value)}
            selectSize="sm"
            options={parentOptions}
          />
        )}

        <TextArea
          label="Notes"
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          rows={2}
          placeholder="Optional context for this shadow"
          textareaSize="sm"
        />
      </div>
    </Modal>
  );
};
