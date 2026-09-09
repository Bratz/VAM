import React, { useState } from 'react';
import {
  Layers,
  Eye,
  UserPlus,
  Calculator,
  Loader2,
  ChevronUp,
  ChevronDown,
} from 'lucide-react';
import { Card, Button, Badge } from '../ui';
import { formatCompactCurrency } from '../../utils';
import { TileAmount } from '../TileAmount';
import { NotionalPool } from '../../services/api';

// ============================================================================
// POOL CARD - ENHANCED
// ============================================================================
export interface PoolCardProps {
  pool: NotionalPool;
  onView: () => void;
  onCalculateInterest: () => void;
  onAddMember: () => void;
  isCalculating: boolean;
}

export const PoolCard: React.FC<PoolCardProps> = ({ pool, onView, onCalculateInterest, onAddMember, isCalculating }) => {
  const [expanded, setExpanded] = useState(false);

  return (
    <Card className="hover:shadow-md hover:border-primary-200 dark:hover:border-primary-700 transition-all group">
      {/* Header */}
      <div className="flex items-start justify-between mb-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-primary-600 flex items-center justify-center">
            <Layers className="w-5 h-5 text-white" />
          </div>
          <div>
            <div className="flex items-center gap-2">
              <h3 className="font-medium text-primary-900 dark:text-neutral-50">{pool.poolName}</h3>
              <Badge variant={pool.status === 'ACTIVE' ? 'success' : 'warning'} size="sm">
                {pool.status}
              </Badge>
            </div>
            <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{pool.poolReference}</p>
          </div>
        </div>
        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <Button variant="ghost" size="sm" onClick={onView} title="View Details">
            <Eye className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="sm" onClick={onAddMember} title="Add Member">
            <UserPlus className="w-4 h-4" />
          </Button>
          <Button
            variant="ghost"
            size="sm"
            onClick={onCalculateInterest}
            disabled={isCalculating}
            title="Calculate Interest"
          >
            {isCalculating ? (
              <Loader2 className="w-4 h-4 animate-spin" />
            ) : (
              <Calculator className="w-4 h-4" />
            )}
          </Button>
        </div>
      </div>

      {/* Stats Grid */}
      <div className="grid grid-cols-2 gap-3 mb-4">
        <div className="bg-neutral-50 dark:bg-primary-950 rounded-xl p-3">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Pool Balance</p>
          <p className="section-title mt-0.5">
            <TileAmount value={pool.totalBalance || 0} currency={pool.poolCurrency} />
          </p>
        </div>
        <div className="bg-success-50 dark:bg-success-500/10 rounded-xl p-3">
          <p className="text-xs text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Savings YTD</p>
          <p className="text-lg font-semibold text-success-700 dark:text-success-300 mt-0.5">
            <TileAmount value={pool.interestSavingsYtd || 0} currency={pool.poolCurrency} />
          </p>
        </div>
      </div>

      {/* Info Row */}
      <div className="grid grid-cols-3 gap-2 text-sm mb-3">
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Members</p>
          <p className="font-medium text-neutral-700 dark:text-neutral-200">{pool.memberCount || pool.members?.length || 0}</p>
        </div>
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Rate</p>
          <p className="font-medium text-neutral-700 dark:text-neutral-200">{pool.interestRate}%</p>
        </div>
        <div>
          <p className="text-xs text-neutral-400 dark:text-neutral-500">Method</p>
          <p className="font-medium text-neutral-700 dark:text-neutral-200 text-xs truncate">
            {pool.interestCalculationMethod?.replace('_', ' ')}
          </p>
        </div>
      </div>

      {/* Members Section (Expandable) */}
      {pool.members && pool.members.length > 0 && (
        <>
          <button
            onClick={() => setExpanded(!expanded)}
            className="w-full flex items-center justify-between py-2 border-t border-neutral-100 dark:border-primary-800/60 text-sm text-neutral-600 dark:text-neutral-300 hover:text-primary-700 dark:hover:text-neutral-200 transition-colors"
          >
            <span className="font-medium">View Members ({pool.members.length})</span>
            {expanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </button>

          {expanded && (
            <div className="space-y-2 pt-2">
              {pool.members.slice(0, 5).map((member) => (
                <div key={member.id} className="flex items-center justify-between p-2.5 bg-neutral-50 dark:bg-primary-950 rounded-lg text-sm">
                  <div>
                    <p className="font-medium text-primary-900 dark:text-neutral-50">{member.entityName}</p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{member.accountNumber}</p>
                  </div>
                  <div className="text-right">
                    <p className="font-medium text-primary-900 dark:text-neutral-50">
                      {formatCompactCurrency(member.currentBalance || 0, pool.poolCurrency)}
                    </p>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">{member.contributionPercent?.toFixed(1)}%</p>
                  </div>
                </div>
              ))}
              {pool.members.length > 5 && (
                <button
                  onClick={onView}
                  className="w-full text-center text-sm text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:hover:text-neutral-200 py-2"
                >
                  View all {pool.members.length} members
                </button>
              )}
            </div>
          )}
        </>
      )}

      {/* Empty Members State */}
      {(!pool.members || pool.members.length === 0) && (
        <div className="border-t border-neutral-100 dark:border-primary-800/60 pt-3 mt-3">
          <button
            onClick={onAddMember}
            className="w-full flex items-center justify-center gap-2 py-2 text-sm text-primary-600 dark:text-primary-200 hover:text-primary-700 dark:hover:text-neutral-200 hover:bg-primary-50 dark:hover:bg-primary-800/40 rounded-lg transition-colors"
          >
            <UserPlus className="w-4 h-4" />
            Add accounts to this pool
          </button>
        </div>
      )}
    </Card>
  );
};
