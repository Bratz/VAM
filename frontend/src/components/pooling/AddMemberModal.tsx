import React, { useMemo, useState } from 'react';
import { Loader2, User, Network, Upload, ChevronDown, ChevronUp, AlertTriangle } from 'lucide-react';
import { Button } from '../ui';
import { Modal, Tabs } from '../ui/enhanced';
import { NotionalPool, AddMemberRequest, BulkAddMembersResponse } from '../../services/api';
import toast from 'react-hot-toast';
import { AccountSelector } from './AccountSelector';
import { ScopePicker, ScopedVaSummary } from '../va/ScopePicker';
import { CsvAccountUpload, ResolvedVaSummary } from '../va/CsvAccountUpload';
import { VirtualizedAccountList } from '../va/VirtualizedAccountList';

// ============================================================================
// ADD MEMBER MODAL - FOR EXISTING POOLS
// Three modes: Manual (original single-account picker, unchanged), By Scope
// (hierarchy/legal-entity tree resolves to accounts via ScopePicker), By CSV
// (bulk account-number upload via CsvAccountUpload). Scope and CSV modes
// share a review-then-confirm step (VirtualizedAccountList, pre-checked) and
// submit through the bulk endpoint.
// ============================================================================
export interface AddMemberModalProps {
  isOpen: boolean;
  onClose: () => void;
  pool: NotionalPool;
  onAddMember: (poolId: string, member: AddMemberRequest) => Promise<NotionalPool>;
  onAddMembersBulk?: (poolId: string, accountIds: string[]) => Promise<BulkAddMembersResponse>;
  corporateId?: string;
}

type Mode = 'manual' | 'scope' | 'csv';

export const AddMemberModal: React.FC<AddMemberModalProps> = ({ isOpen, onClose, pool, onAddMember, onAddMembersBulk, corporateId }) => {
  const [mode, setMode] = useState<Mode>('manual');
  const [saving, setSaving] = useState(false);
  const [selectedMembers, setSelectedMembers] = useState<AddMemberRequest[]>([]);

  // By-scope state
  const [scopeCandidates, setScopeCandidates] = useState<ScopedVaSummary[]>([]);
  const [scopeExcluded, setScopeExcluded] = useState(0);
  const [scopeSelectedIds, setScopeSelectedIds] = useState<Set<string>>(new Set());

  // By-CSV state
  const [csvCandidates, setCsvCandidates] = useState<ResolvedVaSummary[]>([]);
  const [csvExcluded, setCsvExcluded] = useState(0);
  const [csvSelectedIds, setCsvSelectedIds] = useState<Set<string>>(new Set());

  const [bulkResult, setBulkResult] = useState<BulkAddMembersResponse | null>(null);
  const [showSkipped, setShowSkipped] = useState(false);

  // Memoize existing account IDs to prevent infinite loops
  const existingAccountIds = useMemo(
    () => pool.members?.map(m => m.accountId) || [],
    [pool.members]
  );

  const resetAll = () => {
    setMode('manual');
    setSelectedMembers([]);
    setScopeCandidates([]);
    setScopeExcluded(0);
    setScopeSelectedIds(new Set());
    setCsvCandidates([]);
    setCsvExcluded(0);
    setCsvSelectedIds(new Set());
    setBulkResult(null);
    setShowSkipped(false);
  };

  const handleClose = () => {
    resetAll();
    onClose();
  };

  const changeMode = (next: string) => {
    setMode(next as Mode);
    setBulkResult(null);
    setShowSkipped(false);
  };

  const handleSubmit = async () => {
    if (selectedMembers.length === 0) return;

    setSaving(true);
    try {
      // Add members one by one
      for (const member of selectedMembers) {
        await onAddMember(pool.id, member);
      }
      toast.success(`Added ${selectedMembers.length} member(s) to pool`);
      handleClose();
    } catch (err: any) {
      toast.error(err.message || 'Failed to add members');
    } finally {
      setSaving(false);
    }
  };

  const bulkSelectedIds = mode === 'scope' ? scopeSelectedIds : csvSelectedIds;

  const handleBulkSubmit = async () => {
    if (!onAddMembersBulk || bulkSelectedIds.size === 0) return;

    setSaving(true);
    try {
      const result = await onAddMembersBulk(pool.id, Array.from(bulkSelectedIds));
      setBulkResult(result);
      if (result.skipped.length === 0) {
        toast.success(`Added ${result.added} member(s) to pool`);
        handleClose();
      } else {
        toast.success(`Added ${result.added} member(s), ${result.skipped.length} skipped`);
      }
    } catch (err: any) {
      toast.error(err.message || 'Failed to add members');
    } finally {
      setSaving(false);
    }
  };

  const tabs = [
    { id: 'manual', label: 'Manual', icon: <User className="w-4 h-4" /> },
    { id: 'scope', label: 'By Scope', icon: <Network className="w-4 h-4" /> },
    { id: 'csv', label: 'By CSV', icon: <Upload className="w-4 h-4" /> },
  ];

  return (
    <Modal isOpen={isOpen} onClose={handleClose} title={`Add Members to ${pool.poolName}`} size={mode === 'manual' ? 'md' : 'lg'}>
      <Tabs tabs={tabs} activeTab={mode} onChange={changeMode} variant="pills" size="sm" />

      <div className="mt-4">
        {mode === 'manual' && (
          <AccountSelector
            selectedAccounts={selectedMembers}
            onSelect={setSelectedMembers}
            currency={pool.poolCurrency}
            corporateId={corporateId}
            excludeAccountIds={existingAccountIds}
          />
        )}

        {mode === 'scope' && !bulkResult && (
          <div className="space-y-3">
            <ScopePicker
              mode="legalEntity"
              contextId={corporateId}
              onResolved={(accounts) => {
                const filtered = accounts.filter(a => !existingAccountIds.includes(a.id));
                setScopeCandidates(filtered);
                setScopeExcluded(accounts.length - filtered.length);
                setScopeSelectedIds(new Set(filtered.map(a => a.id)));
              }}
            />
            {scopeExcluded > 0 && (
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {scopeExcluded} account(s) already in this pool were excluded.
              </p>
            )}
            {scopeCandidates.length > 0 && (
              <VirtualizedAccountList
                items={scopeCandidates}
                selectable
                selectedIds={scopeSelectedIds}
                onSelectionChange={setScopeSelectedIds}
                height={320}
              />
            )}
          </div>
        )}

        {mode === 'csv' && !bulkResult && (
          <div className="space-y-3">
            <CsvAccountUpload
              onResolved={(matched) => {
                const filtered = matched.filter(a => !existingAccountIds.includes(a.id));
                setCsvCandidates(filtered);
                setCsvExcluded(matched.length - filtered.length);
                setCsvSelectedIds(new Set(filtered.map(a => a.id)));
              }}
            />
            {csvExcluded > 0 && (
              <p className="text-xs text-neutral-500 dark:text-neutral-400">
                {csvExcluded} account(s) already in this pool were excluded.
              </p>
            )}
            {csvCandidates.length > 0 && (
              <VirtualizedAccountList
                items={csvCandidates}
                selectable
                selectedIds={csvSelectedIds}
                onSelectionChange={setCsvSelectedIds}
                height={320}
              />
            )}
          </div>
        )}

        {(mode === 'scope' || mode === 'csv') && bulkResult && (
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-sm bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 rounded-lg px-3 py-2 text-success-700 dark:text-success-300">
              Added {bulkResult.added} member(s) to pool.
            </div>
            {bulkResult.skipped.length > 0 && (
              <div className="border border-warning-200 dark:border-warning-500/30 bg-warning-50/50 dark:bg-warning-500/10 rounded-lg">
                <button
                  onClick={() => setShowSkipped(s => !s)}
                  className="w-full flex items-center justify-between gap-2 px-3 py-2 text-sm font-medium text-warning-700 dark:text-warning-300"
                >
                  <span className="flex items-center gap-1.5">
                    <AlertTriangle className="w-3.5 h-3.5" /> {bulkResult.skipped.length} account(s) skipped
                  </span>
                  {showSkipped ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
                </button>
                {showSkipped && (
                  <div className="px-3 pb-3 space-y-1 max-h-48 overflow-y-auto">
                    {bulkResult.skipped.map(s => (
                      <div key={s.accountId} className="text-xs text-neutral-600 dark:text-neutral-300 flex items-start gap-2">
                        <span className="font-mono text-neutral-400 dark:text-neutral-500 shrink-0">{s.accountId.slice(0, 8)}</span>
                        <span>{s.reason}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        {bulkResult ? (
          <Button onClick={handleClose}>Done</Button>
        ) : (
          <>
            <Button variant="ghost" onClick={handleClose}>Cancel</Button>
            {mode === 'manual' ? (
              <Button onClick={handleSubmit} disabled={saving || selectedMembers.length === 0}>
                {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
                Add {selectedMembers.length} Member(s)
              </Button>
            ) : (
              <Button onClick={handleBulkSubmit} disabled={saving || bulkSelectedIds.size === 0 || !onAddMembersBulk}>
                {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
                Add {bulkSelectedIds.size} Member(s)
              </Button>
            )}
          </>
        )}
      </div>
    </Modal>
  );
};
