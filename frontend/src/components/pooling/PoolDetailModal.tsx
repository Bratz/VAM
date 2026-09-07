import React, { useMemo, useState } from 'react';
import {
  Layers,
  Loader2,
  Calculator,
  UserPlus,
  Trash2,
  Calendar,
  Percent,
} from 'lucide-react';
import { Button, Badge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCurrency } from '../../utils';
import { NotionalPool, CalculateInterestResponse } from '../../services/api';
import toast from 'react-hot-toast';
import { VirtualizedAccountList, VirtualizedAccountRow } from '../va/VirtualizedAccountList';

// ============================================================================
// POOL DETAIL MODAL - VIEW AND MANAGE POOL
// ============================================================================
export interface PoolDetailModalProps {
  isOpen: boolean;
  onClose: () => void;
  pool: NotionalPool;
  onRemoveMember: (poolId: string, memberId: string) => Promise<void>;
  onCalculateInterest: (poolId: string) => Promise<CalculateInterestResponse>;
  onAddMember: () => void;
}

export const PoolDetailModal: React.FC<PoolDetailModalProps> = ({
  isOpen,
  onClose,
  pool,
  onRemoveMember,
  onCalculateInterest,
  onAddMember
}) => {
  const [removingMemberId, setRemovingMemberId] = useState<string | null>(null);
  const [removeTargetId, setRemoveTargetId] = useState('');
  const [calculating, setCalculating] = useState(false);
  const [interestResult, setInterestResult] = useState<CalculateInterestResponse | null>(null);

  // PoolMember -> VirtualizedAccountRow: field names differ (accountNumber/
  // entityName vs. vaNumber/vaName), and members don't carry their own
  // currency, so the pool's currency is used for every row.
  const memberRows: VirtualizedAccountRow[] = useMemo(
    () => (pool.members || []).map(m => ({
      id: m.id,
      vaNumber: m.accountNumber,
      vaName: m.entityName,
      currencyCode: pool.poolCurrency,
      balance: m.currentBalance,
    })),
    [pool.members, pool.poolCurrency]
  );

  const handleRemoveMember = async (memberId: string) => {
    if (!confirm('Remove this member from the pool?')) return;

    setRemovingMemberId(memberId);
    try {
      await onRemoveMember(pool.id, memberId);
      toast.success('Member removed from pool');
      setRemoveTargetId('');
    } catch (err: any) {
      toast.error(err.message || 'Failed to remove member');
    } finally {
      setRemovingMemberId(null);
    }
  };

  const handleCalculateInterest = async () => {
    setCalculating(true);
    try {
      const result = await onCalculateInterest(pool.id);
      setInterestResult(result);
      toast.success('Interest calculated successfully');
    } catch (err: any) {
      toast.error(err.message || 'Failed to calculate interest');
    } finally {
      setCalculating(false);
    }
  };

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={pool.poolName} size="lg">
      {/* Pool Header Info */}
      <div className="bg-primary-50 dark:bg-primary-500/10 rounded-xl p-4 mb-6">
        <div className="flex items-center justify-between mb-4">
          <div className="flex items-center gap-3">
            <div className="w-12 h-12 rounded-xl bg-primary-600 flex items-center justify-center">
              <Layers className="w-6 h-6 text-white" />
            </div>
            <div>
              <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{pool.poolReference}</p>
              <Badge variant={pool.status === 'ACTIVE' ? 'success' : 'warning'}>
                {pool.status}
              </Badge>
            </div>
          </div>
          <Button
            size="sm"
            onClick={handleCalculateInterest}
            disabled={calculating}
            leftIcon={calculating ? <Loader2 className="w-4 h-4 animate-spin" /> : <Calculator className="w-4 h-4" />}
          >
            Calculate Interest
          </Button>
        </div>

        <div className="grid grid-cols-4 gap-4">
          <div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Pool Balance</p>
            <p className="section-title">
              {formatCurrency(pool.totalBalance || 0, pool.poolCurrency)}
            </p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Interest Rate</p>
            <p className="section-title">{pool.interestRate}%</p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">YTD Savings</p>
            <p className="text-lg font-semibold text-success-600 dark:text-success-300">
              {formatCurrency(pool.interestSavingsYtd || 0, pool.poolCurrency)}
            </p>
          </div>
          <div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400">Members</p>
            <p className="section-title">{pool.members?.length || 0}</p>
          </div>
        </div>
      </div>

      {/* Interest Calculation Result */}
      {interestResult && (
        <div className="bg-success-50 dark:bg-success-500/10 border border-success-200 dark:border-success-500/30 rounded-xl p-4 mb-6">
          <div className="flex items-center gap-2 mb-3">
            <Calculator className="w-5 h-5 text-success-600 dark:text-success-300" />
            <h4 className="font-medium text-success-800 dark:text-success-300">Interest Calculation Result</h4>
            <span className="text-xs text-success-600 dark:text-success-300">{interestResult.calculationDate}</span>
          </div>
          <div className="grid grid-cols-3 gap-4 mb-4">
            <div>
              <p className="text-xs text-success-600 dark:text-success-300">Pool Balance</p>
              <p className="font-semibold text-success-800 dark:text-success-300">
                {formatCurrency(interestResult.poolBalance, pool.poolCurrency)}
              </p>
            </div>
            <div>
              <p className="text-xs text-success-600 dark:text-success-300">Gross Interest</p>
              <p className="font-semibold text-success-800 dark:text-success-300">
                {formatCurrency(interestResult.grossInterest, pool.poolCurrency)}
              </p>
            </div>
            <div>
              <p className="text-xs text-success-600 dark:text-success-300">Net Interest</p>
              <p className="font-semibold text-success-800 dark:text-success-300">
                {formatCurrency(interestResult.netInterest, pool.poolCurrency)}
              </p>
            </div>
          </div>
          {interestResult.memberAllocations && interestResult.memberAllocations.length > 0 && (
            <div className="space-y-1">
              <p className="text-xs font-medium text-success-700 dark:text-success-300 mb-2">Member Allocations:</p>
              {interestResult.memberAllocations.map((alloc) => (
                <div key={alloc.memberId} className="flex justify-between text-sm bg-white/50 dark:bg-primary-900/50 rounded-lg px-3 py-2">
                  <span className="text-success-800 dark:text-success-300">{alloc.entityCode}</span>
                  <span className="text-success-700 dark:text-success-300">{alloc.contributionPercent.toFixed(2)}%</span>
                  <span className="font-medium text-success-800 dark:text-success-300">
                    {formatCurrency(alloc.interestAllocation, pool.poolCurrency)}
                  </span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* Members Section */}
      <div className="mb-4 flex items-center justify-between">
        <h4 className="font-medium text-primary-900 dark:text-neutral-50">Pool Members</h4>
        <Button size="sm" variant="outline" onClick={onAddMember} leftIcon={<UserPlus className="w-4 h-4" />}>
          Add Member
        </Button>
      </div>

      <VirtualizedAccountList items={memberRows} selectable={false} height={320} />

      {/* Per-member removal lives outside the (read-only) virtualized list —
          a trash icon per row doesn't scale to thousands of members, so this
          is a deliberate select-then-remove control instead. */}
      {pool.members && pool.members.length > 0 && (
        <div className="flex items-center gap-2 mt-3">
          <select
            value={removeTargetId}
            onChange={(e) => setRemoveTargetId(e.target.value)}
            className="flex-1 h-9 rounded-lg border border-neutral-200 dark:border-primary-800 bg-white dark:bg-primary-900 text-sm px-2 text-neutral-700 dark:text-neutral-200"
          >
            <option value="">Select a member to remove…</option>
            {pool.members.map((member) => (
              <option key={member.id} value={member.id}>
                {member.entityName} — {member.accountNumber}
              </option>
            ))}
          </select>
          <Button
            variant="ghost"
            size="sm"
            className="text-error-600 dark:text-error-300 hover:bg-error-50 dark:hover:bg-error-500/10"
            onClick={() => removeTargetId && handleRemoveMember(removeTargetId)}
            disabled={!removeTargetId || removingMemberId === removeTargetId}
          >
            {removingMemberId && removingMemberId === removeTargetId ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Trash2 className="w-4 h-4" />
            )}
          </Button>
        </div>
      )}

      {/* Pool Info Footer */}
      <div className="mt-6 pt-4 border-t grid grid-cols-3 gap-4 text-sm">
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Calendar className="w-3.5 h-3.5" /> Effective From
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{pool.effectiveFrom || '-'}</p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Percent className="w-3.5 h-3.5" /> Calculation Method
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">
            {pool.interestCalculationMethod?.replace('_', ' ')}
          </p>
        </div>
        <div>
          <p className="text-neutral-500 dark:text-neutral-400 flex items-center gap-1">
            <Calculator className="w-3.5 h-3.5" /> Last Calculated
          </p>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{pool.lastCalculationDate || 'Never'}</p>
        </div>
      </div>

      <div className="flex justify-end mt-6">
        <Button variant="outline" onClick={onClose}>Close</Button>
      </div>
    </Modal>
  );
};
