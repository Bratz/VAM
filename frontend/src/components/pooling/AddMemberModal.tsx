import React, { useState, useMemo } from 'react';
import { Loader2 } from 'lucide-react';
import { Button } from '../ui';
import { Modal } from '../ui/enhanced';
import { NotionalPool, AddMemberRequest } from '../../services/api';
import toast from 'react-hot-toast';
import { AccountSelector } from './AccountSelector';

// ============================================================================
// ADD MEMBER MODAL - FOR EXISTING POOLS
// ============================================================================
export interface AddMemberModalProps {
  isOpen: boolean;
  onClose: () => void;
  pool: NotionalPool;
  onAddMember: (poolId: string, member: AddMemberRequest) => Promise<NotionalPool>;
  corporateId?: string;
}

export const AddMemberModal: React.FC<AddMemberModalProps> = ({ isOpen, onClose, pool, onAddMember, corporateId }) => {
  const [saving, setSaving] = useState(false);
  const [selectedMembers, setSelectedMembers] = useState<AddMemberRequest[]>([]);

  // Memoize existing account IDs to prevent infinite loops
  const existingAccountIds = useMemo(
    () => pool.members?.map(m => m.accountId) || [],
    [pool.members]
  );

  const handleSubmit = async () => {
    if (selectedMembers.length === 0) return;

    setSaving(true);
    try {
      // Add members one by one
      for (const member of selectedMembers) {
        await onAddMember(pool.id, member);
      }
      toast.success(`Added ${selectedMembers.length} member(s) to pool`);
      onClose();
      setSelectedMembers([]);
    } catch (err: any) {
      toast.error(err.message || 'Failed to add members');
    } finally {
      setSaving(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={`Add Members to ${pool.poolName}`} size="md">
      <AccountSelector
        selectedAccounts={selectedMembers}
        onSelect={setSelectedMembers}
        currency={pool.poolCurrency}
        corporateId={corporateId}
        excludeAccountIds={existingAccountIds}
      />

      <div className="flex justify-end gap-2 mt-6 pt-4 border-t">
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        <Button onClick={handleSubmit} disabled={saving || selectedMembers.length === 0}>
          {saving && <Loader2 className="w-4 h-4 animate-spin mr-2" />}
          Add {selectedMembers.length} Member(s)
        </Button>
      </div>
    </Modal>
  );
};
