import React, { useState, useEffect, useMemo, useRef } from 'react';
import { Search, Check, Building2 } from 'lucide-react';
import { Input, Badge, Skeleton } from '../ui';
import { cn, formatCompactCurrency } from '../../utils';
import { virtualAccountsApi, VirtualAccount, AddMemberRequest } from '../../services/api';

// ============================================================================
// ACCOUNT SELECTOR FOR POOL MEMBERS
// Reusable account picker shared by CreatePoolModal and AddMemberModal.
// ============================================================================
export interface AccountSelectorProps {
  selectedAccounts: AddMemberRequest[];
  onSelect: (accounts: AddMemberRequest[]) => void;
  currency: string;
  corporateId?: string;
  excludeAccountIds?: string[];
}

export const AccountSelector: React.FC<AccountSelectorProps> = ({
  selectedAccounts,
  onSelect,
  currency,
  corporateId,
  excludeAccountIds = []
}) => {
  const [accounts, setAccounts] = useState<VirtualAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');

  // Memoize excludeAccountIds to prevent infinite loops from array reference changes
  const excludeIdsKey = excludeAccountIds.join(',');
  const stableExcludeIds = useMemo(() => excludeAccountIds, [excludeIdsKey]);

  // Track fetch dependencies to prevent duplicate/infinite calls
  const hasFetchedRef = useRef(false);
  const lastFetchKeyRef = useRef('');

  useEffect(() => {
    const fetchKey = `${currency}-${corporateId || 'all'}`;

    // Only fetch if dependencies changed or we haven't fetched yet
    if (hasFetchedRef.current && lastFetchKeyRef.current === fetchKey) {
      return;
    }

    const fetchAccounts = async () => {
      try {
        setLoading(true);
        const response = await virtualAccountsApi.getAll();
        if (response.success) {
          // Filter by currency and corporate
          const filtered = (response.data || []).filter(acc => {
            // Match currency (check both currency and currencyCode fields)
            const currencyMatch = acc.currency === currency || acc.currencyCode === currency;
            // Match corporate if specified
            const corporateMatch = !corporateId || acc.corporateId === corporateId;
            return currencyMatch && corporateMatch;
          });
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
  }, [currency, corporateId]);

  const filteredAccounts = accounts.filter(acc =>
    // Exclude already added accounts
    !stableExcludeIds.includes(acc.id) &&
    // Apply search filter
    (acc.accountNumber?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    acc.vaNumber?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    acc.vaName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    acc.accountName?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    acc.entityCode?.toLowerCase().includes(searchTerm.toLowerCase()) ||
    acc.corporateName?.toLowerCase().includes(searchTerm.toLowerCase()))
  );

  const toggleAccount = (account: VirtualAccount) => {
    const isSelected = selectedAccounts.some(a => a.accountId === account.id);
    if (isSelected) {
      onSelect(selectedAccounts.filter(a => a.accountId !== account.id));
    } else {
      // Use correct VirtualAccount field names
      const accountNumber = account.vaNumber || account.accountNumber || '';
      const entityName = account.owningEntityName || account.vaName || account.corporateName || 'Unknown Entity';
      // entityCode is limited to 20 chars in database - use entityCode field or truncate ID
      const rawEntityCode = account.entityCode || account.owningEntityId || account.corporateId || accountNumber.substring(0, 8);
      const entityCode = rawEntityCode.substring(0, 20);

      onSelect([...selectedAccounts, {
        accountId: account.id,
        accountNumber: accountNumber,
        entityCode: entityCode,
        entityName: entityName
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
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
        <Input
          placeholder="Search accounts..."
          value={searchTerm}
          onChange={(e) => setSearchTerm(e.target.value)}
          className="pl-9"
        />
      </div>

      {/* Selected Count */}
      {selectedAccounts.length > 0 && (
        <div className="flex items-center gap-2 text-sm text-primary-600 dark:text-primary-200 bg-primary-50 dark:bg-primary-800/40 rounded-lg px-3 py-2">
          <Check className="w-4 h-4" />
          <span>{selectedAccounts.length} account(s) selected</span>
        </div>
      )}

      {/* Account List */}
      <div className="max-h-64 overflow-y-auto space-y-2 border rounded-lg p-2">
        {filteredAccounts.length === 0 ? (
          <div className="text-center py-6 text-neutral-500 dark:text-neutral-400 text-sm">
            <Building2 className="w-8 h-8 mx-auto mb-2 text-neutral-300 dark:text-neutral-600" />
            No {currency} accounts available
          </div>
        ) : (
          filteredAccounts.map(account => {
            const isSelected = selectedAccounts.some(a => a.accountId === account.id);
            // Use correct VirtualAccount field names for display
            const displayName = account.vaName || account.owningEntityName || account.corporateName || 'Unnamed Account';
            const displayNumber = account.vaNumber || account.accountNumber || account.id;
            const entityInfo = account.owningEntityName || account.corporateName || '';

            return (
              <button
                key={account.id}
                type="button"
                onClick={() => toggleAccount(account)}
                className={cn(
                  'w-full flex items-center gap-3 p-3 rounded-lg border transition-all text-left',
                  isSelected
                    ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40 ring-1 ring-primary-500'
                    : 'border-neutral-200 dark:border-primary-800 hover:border-primary-300 dark:hover:border-primary-700 hover:bg-neutral-50 dark:hover:bg-primary-800/50'
                )}
              >
                <div className={cn(
                  'w-5 h-5 rounded border-2 flex items-center justify-center flex-shrink-0',
                  isSelected ? 'border-primary-500 bg-primary-500' : 'border-neutral-300 dark:border-primary-700'
                )}>
                  {isSelected && <Check className="w-3 h-3 text-white" />}
                </div>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-primary-900 dark:text-neutral-50 truncate">{displayName}</p>
                  <p className="text-xs text-neutral-500 dark:text-neutral-400 font-mono">{displayNumber}</p>
                  {entityInfo && entityInfo !== displayName && (
                    <p className="text-xs text-neutral-400 dark:text-neutral-500 truncate">{entityInfo}</p>
                  )}
                </div>
                <div className="text-right">
                  <p className="text-sm font-medium text-primary-900 dark:text-neutral-50">
                    {formatCompactCurrency(account.availableBalance || 0, currency)}
                  </p>
                  <Badge variant={account.status === 'ACTIVE' ? 'success' : 'warning'} size="sm">
                    {account.status}
                  </Badge>
                </div>
              </button>
            );
          })
        )}
      </div>
    </div>
  );
};
