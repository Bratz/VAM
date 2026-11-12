import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Search, Check, Building2 } from 'lucide-react';
import { Input, Badge, Skeleton } from '../ui';
import { cn, formatCompactCurrency } from '../../utils';
import { multiBankLiquidityApi, ShadowSummary, AddMemberRequest } from '../../services/api';

// ============================================================================
// ACCOUNT SELECTOR FOR POOL MEMBERS
// Reusable account picker shared by CreatePoolModal and AddMemberModal.
// Offers what a pool can take: the corporate's home-bank shadows (PHYSICAL_MIRROR) in the pool's currency and,
// once chosen, of its legal entity. The backend's membership rule stays authoritative (inactive or already-pooled
// accounts are refused there).
// ============================================================================
export interface AccountSelectorProps {
  selectedAccounts: AddMemberRequest[];
  onSelect: (accounts: AddMemberRequest[]) => void;
  currency: string;
  corporateId?: string;
  entityId?: string;
  excludeAccountIds?: string[];
}

export const AccountSelector: React.FC<AccountSelectorProps> = ({
  selectedAccounts,
  onSelect,
  currency,
  corporateId,
  entityId,
  excludeAccountIds = []
}) => {
  const [accounts, setAccounts] = useState<ShadowSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');

  // Memoize excludeAccountIds to prevent infinite loops from array reference changes
  const excludeIdsKey = excludeAccountIds.join(',');
  // eslint-disable-next-line react-hooks/exhaustive-deps -- keyed on the joined ids on purpose: callers pass a fresh array literal each render, so depending on excludeAccountIds itself would break the memo it exists to provide.
  const stableExcludeIds = useMemo(() => excludeAccountIds, [excludeIdsKey]);

  // Track fetch dependencies to prevent duplicate/infinite calls
  const hasFetchedRef = useRef(false);
  const lastFetchKeyRef = useRef('');

  useEffect(() => {
    const fetchKey = `${currency}-${corporateId || 'all'}-${entityId || 'any'}`;

    // Only fetch if dependencies changed or we haven't fetched yet
    if (hasFetchedRef.current && lastFetchKeyRef.current === fetchKey) {
      return;
    }

    const fetchAccounts = async () => {
      try {
        setLoading(true);
        const response = await multiBankLiquidityApi.getSummary(corporateId || undefined);
        if (response.success) {
          const filtered = (response.data?.banks || [])
            .flatMap(b => b.currencies)
            .flatMap(c => c.shadows)
            .filter(s => s.homeBankHeld && s.currencyCode === currency && (!entityId || s.owningEntityId === entityId));
          setAccounts(filtered);
          hasFetchedRef.current = true;
          lastFetchKeyRef.current = fetchKey;
        }
      } catch (err) {
        console.error('Failed to fetch accounts:', err);
      } finally {
        setLoading(false);
      }
    };
    fetchAccounts();
  }, [currency, corporateId, entityId]);

  const term = searchTerm.toLowerCase();
  const filteredAccounts = accounts.filter(acc =>
    // Exclude already added accounts
    !stableExcludeIds.includes(acc.vaId) &&
    // Apply search filter
    (acc.vaNumber?.toLowerCase().includes(term) ||
    acc.vaName?.toLowerCase().includes(term) ||
    acc.bankAccountNumber?.toLowerCase().includes(term) ||
    acc.owningEntityCode?.toLowerCase().includes(term) ||
    acc.owningEntityName?.toLowerCase().includes(term))
  );

  const toggleAccount = (account: ShadowSummary) => {
    const isSelected = selectedAccounts.some(a => a.accountId === account.vaId);
    if (isSelected) {
      onSelect(selectedAccounts.filter(a => a.accountId !== account.vaId));
    } else {
      // The backend takes the number and entity code from the account itself; these are display labels.
      onSelect([...selectedAccounts, {
        accountId: account.vaId,
        accountNumber: account.vaNumber,
        entityCode: (account.owningEntityCode || '').substring(0, 20),
        entityName: account.owningEntityName || account.vaName || 'Unknown Entity'
      }]);
    }
  };

  if (loading) {
    return (
      <div className="space-y-2">
        {[1, 2, 3].map(i => (
          <Skeleton key={i} className="h-14 w-full rounded-lg" />
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {/* Search */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
        <Input
          placeholder="Search accounts..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="pl-9"
        />
      </div>

      {/* Selected Count */}
      {selectedAccounts.length > 0 && (
        <div className="flex items-center gap-2 text-body-sm text-primary-600 dark:text-primary-200 bg-primary-50 dark:bg-primary-800/40 rounded-lg px-3 py-2">
          <Check className="w-4 h-4" />
          <span>{selectedAccounts.length} account(s) selected</span>
        </div>
      )}

      {/* Account List */}
      <div className="max-h-64 overflow-y-auto space-y-2 border rounded-lg p-2">
        {filteredAccounts.length === 0 ? (
          <div className="text-center py-6 body-sm">
            <Building2 className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-400" />
            No home-bank {currency} accounts{entityId ? ' of this legal entity' : ''}
          </div>
        ) : (
          filteredAccounts.map(account => {
            const isSelected = selectedAccounts.some(a => a.accountId === account.vaId);
            const displayName = account.vaName || account.owningEntityName || 'Unnamed Account';
            const displayNumber = account.vaNumber || account.vaId;
            const entityInfo = account.owningEntityName || '';

            return (
              <button
                key={account.vaId}
                type="button"
                onClick={() => toggleAccount(account)}
                className={cn(
                  'w-full flex items-center gap-3 p-3 rounded-lg border transition-all text-left',
                  isSelected
                    ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-1 ring-primary-500'
                    : 'border-edge hover:border-primary-300 dark:hover:border-primary-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                )}
              >
                <div className={cn(
                  'w-5 h-5 rounded-md border-2 flex items-center justify-center flex-shrink-0',
                  isSelected ? 'border-primary-500 bg-primary-500' : 'border-edge-strong'
                )}>
                  {isSelected && <Check className="w-3 h-3 text-white" />}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{displayName}</p>
                  <p className="text-caption text-neutral-500 dark:text-neutral-400 font-mono">{displayNumber}</p>
                  {entityInfo && entityInfo !== displayName && (
                    <p className="text-caption text-neutral-400 truncate">{entityInfo}</p>
                  )}
                </div>
                <div className="text-right">
                  <p className="body-strong">
                    {formatCompactCurrency(account.bankBalance || 0, currency)}
                  </p>
                  {account.stale && <Badge variant="warning" size="sm">Stale</Badge>}
                </div>
              </button>
            );
          })
        )}
      </div>
    </div>
  );
};
