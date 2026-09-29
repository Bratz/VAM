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
  /**
   * Edit an existing shadow instead of adding one. Only the name, role, parent
   * and notes are editable — the snapshot fields are frozen at add time by
   * design, so they (and the physicalAccountId and localId) are carried over
   * untouched. Null/undefined = add mode.
   */
  initialShadow?: SimulatedShadow | null;
}

export const AddShadowDrawer: React.FC<AddShadowDrawerProps> = ({
  open,
  physicalAccount,
  initialShadow = null,
  existingShadows,
  onClose,
  onCreate,
}) => {
  const [name, setName] = useState('');
  const [role, setRole] = useState<ShadowRole>('HEADER');
  const [parentLocalId, setParentLocalId] = useState('');
  const [notes, setNotes] = useState('');

  const isEdit = initialShadow != null;

  // Seed the form: from the shadow when editing, otherwise from the Physical
  // Account just brought into the drawer.
  useEffect(() => {
    if (!open) return;
    if (initialShadow) {
      setName(initialShadow.proposedVaName);
      setRole(initialShadow.role);
      setParentLocalId(initialShadow.parentLocalId ?? '');
      setNotes(initialShadow.notes ?? '');
      return;
    }
    if (physicalAccount) {
      setName(`${physicalAccount.accountName} — Shadow`);
      setRole(existingShadows.length === 0 ? 'HEADER' : 'CHILD');
      setParentLocalId('');
      setNotes('');
    }
  }, [open, physicalAccount, initialShadow, existingShadows.length]);

  if (!physicalAccount && !initialShadow) return null;

  // Editing has no live Physical Account to hand — the shadow's frozen snapshot
  // is the record of what it mirrors, so the context strip reads from whichever
  // is available.
  const context = physicalAccount
    ? {
        bankName: physicalAccount.bankName,
        bankCode: physicalAccount.bankCode,
        accountNumber: physicalAccount.accountNumber,
        balance: formatCurrency(physicalAccount.currentBalance, physicalAccount.currencyCode),
        dataSource: physicalAccount.dataSource,
      }
    : {
        bankName: initialShadow!.snapshotBankName,
        bankCode: initialShadow!.snapshotBankCode,
        accountNumber: undefined,
        balance: undefined,
        dataSource: initialShadow!.snapshotDataSource,
      };

  const parentOptions = [
    { value: '', label: 'No parent (top of group)' },
    ...existingShadows
      // a shadow cannot be its own parent
      .filter((s) => s.localId !== initialShadow?.localId)
      .map((s) => ({
        value: s.localId,
        label: `${s.proposedVaName} (${s.role === 'HEADER' ? 'Header' : 'Child'})`,
      })),
  ];

  const canCreate = name.trim().length > 0;

  const handleCreate = () => {
    if (!canCreate) return;
    const edits = {
      proposedVaName: name.trim(),
      role,
      parentLocalId:
        role === 'CHILD' && parentLocalId ? parentLocalId : undefined,
      notes: notes.trim() || undefined,
    };
    if (initialShadow) {
      // Snapshot fields and identity carry over untouched.
      onCreate({ ...initialShadow, ...edits });
      return;
    }
    const pa = physicalAccount!;
    onCreate({
      localId: newLocalId('sh'),
      physicalAccountId: pa.id,
      ...edits,
      snapshotBankCode: pa.bankCode,
      snapshotBankName: pa.bankName,
      snapshotBankRelationship: classifyRelationship(pa),
      snapshotBankCountry: pa.bankCountry,
      snapshotDataSource: pa.dataSource,
      snapshotCurrencyCode: pa.currencyCode,
      snapshotConsentExpiresAt: pa.consentExpiresAt,
      snapshotInterestRate: pa.interestRate,
      snapshotOverdraftLimit: pa.overdraftLimit,
    });
  };

  return (
    <Modal
      isOpen={open}
      onClose={onClose}
      title={isEdit ? 'Edit Shadow VA' : 'Add Shadow VA'}
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
            {isEdit ? 'Save shadow' : 'Add shadow'}
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        {/* Frozen Physical Account context */}
        <div className="rounded-lg border border-neutral-200/80 dark:border-primary-800/60 bg-neutral-50 dark:bg-primary-900/40 px-4 py-3">
          <div className="flex items-center gap-2">
            <Building2
              className="w-4 h-4 text-neutral-500 dark:text-neutral-400 shrink-0"
              aria-hidden
            />
            <span className="section-title truncate">{context.bankName}</span>
            <span className="code text-neutral-400">{context.bankCode}</span>
          </div>
          <div className="mt-1 flex items-center gap-2 flex-wrap body-sm text-neutral-500 dark:text-neutral-400">
            {context.accountNumber && <span className="code">{context.accountNumber}</span>}
            {context.balance && (
              <>
                <span aria-hidden>·</span>
                <span>{context.balance}</span>
              </>
            )}
            {context.dataSource && (
              <>
                <span aria-hidden>·</span>
                <span>{context.dataSource}</span>
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
