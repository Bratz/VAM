// Legal-entity view of the balance hierarchy: accounts grouped under the legal entity that owns
// them, entities nested parent → child. Rendered by Balance Hierarchy's "Group by: Legal entity"
// mode (moved here from the retired Entity Balance Tree page). Scope and reporting currency come
// from the host page.
import React, { useState, useEffect, useCallback, useMemo } from 'react';
import toast from 'react-hot-toast';
import { ChevronRight, Building2, Wallet, Globe, DollarSign, Layers, Loader2, Eye, GitBranch, Crown, PiggyBank, Coins, Scale, AlertTriangle, Banknote, ArrowLeftRight, Search, RefreshCw, Network, TreeDeciduous, Users, XCircle } from 'lucide-react';
import { Card, Button, Badge, StatusIconBadge } from '../ui';
import { Modal } from '../ui/enhanced';
import { formatCurrency, cn } from '../../utils';
import {
  balanceStructureApi,
  balanceAggregationApi,
  BalanceHierarchyNode,
  legalEntityApi,
  LegalEntity,
} from '../../services/api';

type AccountCategory =
  | 'ROOT' | 'AGGREGATION' | 'CURRENCY_MIRROR' | 'PHYSICAL_MIRROR'
  | 'SETTLEMENT' | 'EXCEPTION' | 'TRANSACTION' | 'COLLECTION'
  | 'DISBURSEMENT' | 'INTERCOMPANY';

type MirrorAccountType =
  | 'IHB_CURRENT' | 'IHB_SETTLEMENT' | 'IC_RECEIVABLE'
  | 'TREASURY_SETTLEMENT' | 'NONE';

interface ExtendedNode extends BalanceHierarchyNode {
  accountCategory?: AccountCategory;
  mirrorAccountType?: MirrorAccountType;
  mirrorBalance?: number;
  balanceInBase?: number;
  ihbParticipant?: boolean;
  bankAccountNumber?: string;
}

type AccountGroups = {
  shadow: ExtendedNode[];
  currencyMirrors: ExtendedNode[];
  ihbAccounts: ExtendedNode[];
  settlementVas: ExtendedNode[];
  exceptionVas: ExtendedNode[];
  aggregations: ExtendedNode[];
  transactionVas: ExtendedNode[];
  intercompanyVas: ExtendedNode[];
};

interface EntityWithAccounts extends LegalEntity {
  accounts: AccountGroups;
  // Balances in the reporting currency: an entity's accounts can be in several currencies, so a
  // sum is only meaningful converted.
  ownBalanceInBase: number;
  rolledUpBalanceInBase: number;
  childEntities: EntityWithAccounts[];
  accountCount: number;
}

const ACCOUNT_TYPE_CONFIG: Record<keyof AccountGroups, {
  label: string;
  icon: React.FC<{ className?: string }>;
  color: string;
  bgColor: string;
  borderColor: string;
  isLeaf: boolean; // Whether to include in balance totals
}> = {
  shadow: {
    label: 'Bank accounts',
    icon: Layers,
    color: 'text-warning-600 dark:text-warning-300',
    bgColor: 'bg-warning-50 dark:bg-warning-500/10',
    borderColor: 'border-warning-200 dark:border-warning-500/30',
    isLeaf: true,
  },
  currencyMirrors: {
    label: 'Currency Mirrors',
    icon: Coins,
    color: 'text-info-600 dark:text-info-300',
    bgColor: 'bg-info-50 dark:bg-info-500/10',
    borderColor: 'border-info-200 dark:border-info-500/30',
    isLeaf: false, // Aggregation - don't double count
  },
  ihbAccounts: {
    label: 'IHB Accounts',
    icon: PiggyBank,
    color: 'text-primary-600 dark:text-primary-200',
    bgColor: 'bg-primary-50 dark:bg-primary-800/40',
    borderColor: 'border-primary-200',
    isLeaf: true,
  },
  settlementVas: {
    label: 'Settlement VAs',
    icon: Scale,
    color: 'text-cat-2 dark:text-cat-2-fg',
    bgColor: 'bg-cat-2-soft dark:bg-cat-2/15',
    borderColor: 'border-cat-2/20 dark:border-cat-2/30',
    isLeaf: true,
  },
  exceptionVas: {
    label: 'Exception VAs',
    icon: AlertTriangle,
    color: 'text-error-600 dark:text-error-300',
    bgColor: 'bg-error-50 dark:bg-error-500/10',
    borderColor: 'border-error-200 dark:border-error-500/30',
    isLeaf: true,
  },
  aggregations: {
    label: 'Group nodes',
    icon: GitBranch,
    color: 'text-neutral-600 dark:text-neutral-300',
    bgColor: 'bg-surface-muted',
    borderColor: 'border-edge',
    isLeaf: false, // Aggregation - don't double count
  },
  transactionVas: {
    label: 'Transaction VAs',
    icon: Wallet,
    color: 'text-success-600 dark:text-success-300',
    bgColor: 'bg-success-50 dark:bg-success-500/10',
    borderColor: 'border-success-200 dark:border-success-500/30',
    isLeaf: true,
  },
  intercompanyVas: {
    label: 'Intercompany VAs',
    icon: ArrowLeftRight,
    color: 'text-cat-4 dark:text-cat-4-fg',
    bgColor: 'bg-cat-4-soft dark:bg-cat-4/15',
    borderColor: 'border-cat-4/20 dark:border-cat-4/30',
    isLeaf: true,
  },
};

const SECTION_ORDER = Object.keys(ACCOUNT_TYPE_CONFIG) as (keyof AccountGroups)[];

const categorize = (node: ExtendedNode): keyof AccountGroups => {
  const category = node.accountCategory;
  const mirrorType = node.mirrorAccountType;
  if (category === 'PHYSICAL_MIRROR' || node.type === 'SHADOW_ACCOUNT') return 'shadow';
  if (category === 'CURRENCY_MIRROR') return 'currencyMirrors';
  if (mirrorType === 'IHB_CURRENT' || mirrorType === 'IHB_SETTLEMENT' || node.ihbParticipant) return 'ihbAccounts';
  if (category === 'SETTLEMENT' || mirrorType === 'TREASURY_SETTLEMENT') return 'settlementVas';
  if (category === 'EXCEPTION') return 'exceptionVas';
  if (category === 'AGGREGATION' || category === 'ROOT') return 'aggregations';
  if (category === 'INTERCOMPANY' || mirrorType === 'IC_RECEIVABLE') return 'intercompanyVas';
  return 'transactionVas';
};

// ============================================================================
// ACCOUNT ROW
// ============================================================================

const AccountRow: React.FC<{
  account: ExtendedNode;
  type: keyof AccountGroups;
  reportingCurrency: string;
  onView: () => void;
  indentLevel: number;
}> = ({ account, type, reportingCurrency, onView, indentLevel }) => {
  const config = ACCOUNT_TYPE_CONFIG[type];
  const Icon = config.icon;
  const balance = account.localBalance || account.mirrorBalance || 0;

  const mirrorBadge = (() => {
    switch (account.mirrorAccountType) {
      case 'IHB_CURRENT': return <Badge variant="info" size="sm">IHB Current</Badge>;
      case 'IHB_SETTLEMENT': return <Badge variant="info" size="sm">IHB Settlement</Badge>;
      case 'IC_RECEIVABLE': return <Badge variant="warning" size="sm">IC Receivable</Badge>;
      case 'TREASURY_SETTLEMENT': return <Badge variant="accent" size="sm">Treasury Settlement</Badge>;
      default: return null;
    }
  })();

  return (
    <div
      className="flex items-center gap-3 px-4 py-3 hover:bg-neutral-50 cursor-pointer transition-all duration-200 group dark:hover:bg-primary-800/50"
      style={{ paddingLeft: `${Math.max(56, indentLevel * 24 + 56)}px` }}
      onClick={onView}
    >
      <div className={cn('p-2 rounded-lg', config.bgColor)}>
        <Icon className={cn('w-4 h-4', config.color)} />
      </div>

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-body-sm font-medium text-neutral-900 truncate dark:text-neutral-50">{account.name}</p>
          {mirrorBadge}
          {account.ihbParticipant && (
            <Badge variant="info" size="sm" className="flex items-center gap-1">
              <PiggyBank className="w-3 h-3" />
              IHB
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-2 caption mt-0.5">
          {account.accountNumber && <span className="font-mono">{account.accountNumber}</span>}
          {account.bankAccountNumber && (
            <span className="flex items-center gap-1">
              <Banknote className="w-3 h-3" />
              {account.bankAccountNumber}
            </span>
          )}
        </div>
      </div>

      <Badge variant="neutral" size="sm" className="font-mono">{account.currencyCode}</Badge>

      <div className="text-right min-w-[7.5rem]">
        <p className={cn('text-body-sm font-semibold', balance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
          {formatCurrency(balance, account.currencyCode)}
        </p>
        {/* consolidatedBalance is the reporting-currency figure (the same one Program hierarchy shows). */}
        {account.currencyCode !== reportingCurrency && account.consolidatedBalance != null && (
          <p className="caption">≈ {formatCurrency(account.consolidatedBalance, reportingCurrency)}</p>
        )}
      </div>

      <Eye className="w-4 h-4 text-neutral-400 opacity-0 group-hover:opacity-100 transition-all duration-200 dark:text-neutral-400" />
    </div>
  );
};

// ============================================================================
// ENTITY NODE (recursive)
// ============================================================================

interface EntityHierarchyNodeProps {
  entity: EntityWithAccounts;
  depth: number;
  expandedEntities: Set<string>;
  expandedSections: Set<string>;
  onToggleEntity: (entityId: string) => void;
  onToggleSection: (sectionId: string) => void;
  reportingCurrency: string;
  onViewAccount: (node: ExtendedNode) => void;
}

const EntityHierarchyNode: React.FC<EntityHierarchyNodeProps> = ({
  entity,
  depth,
  expandedEntities,
  expandedSections,
  onToggleEntity,
  onToggleSection,
  reportingCurrency,
  onViewAccount,
}) => {
  const isExpanded = expandedEntities.has(entity.id);
  const hasChildren = entity.childEntities.length > 0;
  const hasAccounts = Object.values(entity.accounts).flat().length > 0;

  const typeStyle = (() => {
    if (entity.isTreasuryCenter) {
      return { bg: 'bg-warning-100 dark:bg-warning-500/20', icon: <Crown className="w-5 h-5 text-warning-600 dark:text-warning-300" />, badge: 'warning' as const };
    }
    switch (entity.entityType) {
      case 'HOLDING':
        return { bg: 'bg-cat-2/10 dark:bg-cat-2/15', icon: <Network className="w-5 h-5 text-cat-2 dark:text-cat-2-fg" />, badge: 'accent' as const };
      case 'SUBSIDIARY':
        return { bg: 'bg-primary-100 dark:bg-primary-700', icon: <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />, badge: 'info' as const };
      case 'BRANCH':
        return { bg: 'bg-success-100 dark:bg-success-500/20', icon: <GitBranch className="w-5 h-5 text-success-600 dark:text-success-300" />, badge: 'success' as const };
      default:
        return { bg: 'bg-surface-muted', icon: <Building2 className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />, badge: 'neutral' as const };
    }
  })();

  const renderAccountSection = (type: keyof AccountGroups) => {
    const accounts = entity.accounts[type];
    if (accounts.length === 0) return null;

    const config = ACCOUNT_TYPE_CONFIG[type];
    const sectionId = `${entity.id}-${type}`;
    const isSectionExpanded = expandedSections.has(sectionId);
    const Icon = config.icon;
    // No section total: these accounts can be in several currencies. Rows show their own.

    return (
      <div key={type} className="border-t border-edge-subtle">
        <button
          onClick={(e) => { e.stopPropagation(); onToggleSection(sectionId); }}
          className={cn(
            'w-full flex items-center gap-3 px-4 py-2.5 transition-all duration-200',
            'hover:bg-neutral-50 group dark:hover:bg-primary-800/50',
            `border-l-2 ${config.borderColor}`
          )}
          style={{ paddingLeft: `${(depth + 1) * 24 + 16}px` }}
        >
          <div className={cn('transition-transform duration-200', isSectionExpanded && 'rotate-90')}>
            <ChevronRight className="w-4 h-4 text-neutral-400" />
          </div>
          <div className={cn('p-1.5 rounded-lg', config.bgColor)}>
            <Icon className={cn('w-4 h-4', config.color)} />
          </div>
          <div className="flex-1 text-left">
            <div className="flex items-center gap-2">
              <span className={cn('text-caption font-semibold', config.color)}>{config.label}</span>
              <Badge variant="neutral" size="sm" className="font-bold text-caption">{accounts.length}</Badge>
            </div>
          </div>
        </button>

        {isSectionExpanded && (
          <div className="bg-neutral-50/50 dark:bg-primary-950/50 divide-y divide-edge-subtle">
            {accounts.map(account => (
              <AccountRow
                key={account.id}
                account={account}
                type={type}
                reportingCurrency={reportingCurrency}
                onView={() => onViewAccount(account)}
                indentLevel={depth + 2}
              />
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div>
      <div
        className={cn(
          'border-b border-edge-subtle hover:bg-neutral-50/70 dark:hover:bg-primary-800/50 transition-all duration-200',
          entity.isTreasuryCenter && 'bg-warning-50/30 dark:bg-primary-900'
        )}
      >
        <button
          onClick={() => onToggleEntity(entity.id)}
          className="w-full flex items-center gap-3 py-3 px-4"
          style={{ paddingLeft: `${depth * 24 + 16}px` }}
          aria-expanded={hasChildren || hasAccounts ? isExpanded : undefined}
        >
          <div className={cn(
            'transition-transform duration-200 w-5 h-5 flex items-center justify-center',
            isExpanded && 'rotate-90',
            !hasChildren && !hasAccounts && 'opacity-0'
          )}>
            {(hasChildren || hasAccounts) && <ChevronRight className="w-4 h-4 text-neutral-400" />}
          </div>

          <div className={cn('p-2 rounded-lg', typeStyle.bg)}>{typeStyle.icon}</div>

          <div className="flex-1 text-left min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className={cn('font-semibold text-neutral-900 truncate dark:text-neutral-50', depth === 0 ? 'text-body' : 'text-body-sm')}>
                {entity.entityName}
              </h3>
              <Badge variant="neutral" size="sm" className="font-mono text-caption">{entity.entityCode}</Badge>
              {entity.isTreasuryCenter && (
                <Badge variant="warning" size="sm" className="flex items-center gap-1">
                  <Crown className="w-3 h-3" />
                  Treasury Center
                </Badge>
              )}
              {entity.entityType && (
                <Badge variant={typeStyle.badge} size="sm" className="text-caption">{entity.entityType}</Badge>
              )}
            </div>
            <div className="flex items-center gap-3 caption mt-1">
              {entity.countryCode && (
                <span className="flex items-center gap-1"><Globe className="w-3 h-3" />{entity.countryCode}</span>
              )}
              <span className="flex items-center gap-1"><DollarSign className="w-3 h-3" />{entity.functionalCurrency}</span>
              {hasChildren && (
                <span className="flex items-center gap-1"><Users className="w-3 h-3" />{entity.childEntities.length} child entities</span>
              )}
              <span className="flex items-center gap-1"><Wallet className="w-3 h-3" />{entity.accountCount} accounts</span>
            </div>
          </div>

          <div className="text-right shrink-0">
            <p className={cn(
              'font-bold',
              depth === 0 ? 'text-body-lg' : 'text-body-sm',
              entity.rolledUpBalanceInBase >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
            )}>
              {formatCurrency(entity.rolledUpBalanceInBase, reportingCurrency)}
            </p>
            {hasChildren && entity.ownBalanceInBase !== entity.rolledUpBalanceInBase && (
              <p className="caption mt-0.5">Own: {formatCurrency(entity.ownBalanceInBase, reportingCurrency)}</p>
            )}
          </div>
        </button>
      </div>

      {isExpanded && (
        <div>
          {hasAccounts && (
            <div className="bg-surface-card border-l-2 border-edge" style={{ marginLeft: `${depth * 24 + 36}px` }}>
              {SECTION_ORDER.map(renderAccountSection)}
            </div>
          )}
          {entity.childEntities.map(child => (
            <EntityHierarchyNode
              key={child.id}
              entity={child}
              depth={depth + 1}
              expandedEntities={expandedEntities}
              expandedSections={expandedSections}
              onToggleEntity={onToggleEntity}
              onToggleSection={onToggleSection}
              reportingCurrency={reportingCurrency}
              onViewAccount={onViewAccount}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ============================================================================
// TREE BUILDING
// ============================================================================

const buildEntityAccountsTree = (
  legalEntities: LegalEntity[],
  accountsByEntity: Map<string, ExtendedNode[]>,
): EntityWithAccounts[] =>
  legalEntities.map(entity => {
    const accounts: AccountGroups = {
      shadow: [], currencyMirrors: [], ihbAccounts: [], settlementVas: [],
      exceptionVas: [], aggregations: [], transactionVas: [], intercompanyVas: [],
    };
    let ownBalanceInBase = 0;

    (accountsByEntity.get(entity.id) || []).forEach(node => {
      const type = categorize(node);
      accounts[type].push(node);
      // Only sum leaf accounts to avoid double-counting
      if (ACCOUNT_TYPE_CONFIG[type].isLeaf) {
        const balance = node.localBalance || node.mirrorBalance || 0;
        ownBalanceInBase += node.consolidatedBalance || node.balanceInBase || balance;
      }
    });

    const childEntities = entity.children ? buildEntityAccountsTree(entity.children, accountsByEntity) : [];
    const ownAccountCount = Object.values(accounts).flat().length;

    return {
      ...entity,
      accounts,
      ownBalanceInBase,
      rolledUpBalanceInBase: ownBalanceInBase + childEntities.reduce((sum, c) => sum + c.rolledUpBalanceInBase, 0),
      childEntities,
      accountCount: ownAccountCount + childEntities.reduce((sum, c) => sum + c.accountCount, 0),
    };
  });

const filterHierarchy = (entities: EntityWithAccounts[], query: string): EntityWithAccounts[] => {
  if (!query) return entities;
  const q = query.toLowerCase();
  const filterEntity = (entity: EntityWithAccounts): EntityWithAccounts | null => {
    const matchesSelf = entity.entityName?.toLowerCase().includes(q) || entity.entityCode?.toLowerCase().includes(q);
    const childEntities = entity.childEntities
      .map(filterEntity)
      .filter((c): c is EntityWithAccounts => c !== null);
    return matchesSelf || childEntities.length > 0 ? { ...entity, childEntities } : null;
  };
  return entities.map(filterEntity).filter((e): e is EntityWithAccounts => e !== null);
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

export interface LegalEntityBalanceTreeProps {
  corporateId: string;
  /** Empty / undefined = every program of the corporate. */
  programId?: string;
  reportingCurrency: string;
}

export const LegalEntityBalanceTree: React.FC<LegalEntityBalanceTreeProps> = ({ corporateId, programId, reportingCurrency }) => {
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [entityHierarchy, setEntityHierarchy] = useState<EntityWithAccounts[]>([]);
  const [expandedEntities, setExpandedEntities] = useState<Set<string>>(new Set());
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedAccount, setSelectedAccount] = useState<ExtendedNode | null>(null);

  // Fetch the legal entity tree and the balance hierarchy, then hang each account on its owning entity.
  const loadHierarchy = useCallback(async (isRefresh = false) => {
    if (!corporateId) return;
    if (isRefresh) setRefreshing(true);
    else setLoading(true);
    setError(null);

    try {
      const [hierarchyResponse, balanceResponse] = await Promise.all([
        legalEntityApi.getHierarchy(corporateId).catch(() => null),
        balanceStructureApi.getHierarchy(corporateId, programId || undefined, reportingCurrency),
      ]);
      const balanceData = (balanceResponse?.data || balanceResponse) as ExtendedNode;

      const accountsByEntity = new Map<string, ExtendedNode[]>();
      const collectAccounts = (node: ExtendedNode) => {
        if (node.owningEntityId) {
          if (!accountsByEntity.has(node.owningEntityId)) accountsByEntity.set(node.owningEntityId, []);
          accountsByEntity.get(node.owningEntityId)!.push(node);
        }
        node.children?.forEach(child => collectAccounts(child as ExtendedNode));
      };
      collectAccounts(balanceData);

      let entityList: LegalEntity[] = [];
      if (hierarchyResponse) {
        const rawData = (hierarchyResponse as any)?.data || hierarchyResponse;
        if (Array.isArray(rawData)) entityList = rawData;
        else if (rawData && typeof rawData === 'object' && rawData.id) entityList = [rawData];
      }

      if (entityList.length === 0) {
        // No legal entity tree: one flat level built from the owning entities on the accounts.
        const flatEntities = new Map<string, LegalEntity>();
        const flattenAccounts = (node: ExtendedNode) => {
          if (node.owningEntityId && !flatEntities.has(node.owningEntityId)) {
            flatEntities.set(node.owningEntityId, {
              id: node.owningEntityId,
              entityCode: node.owningEntityCode || 'N/A',
              entityName: node.owningEntityName || 'Unknown Entity',
              functionalCurrency: node.currencyCode || 'AED',
              status: 'ACTIVE',
              entityType: 'SUBSIDIARY',
              hierarchyLevel: 0,
            } as LegalEntity);
          }
          node.children?.forEach(child => flattenAccounts(child as ExtendedNode));
        };
        flattenAccounts(balanceData);
        entityList = Array.from(flatEntities.values());
      }

      setEntityHierarchy(buildEntityAccountsTree(entityList, accountsByEntity));
    } catch (err: any) {
      const msg = err.response?.data?.message || err.message || 'Failed to load hierarchy';
      setError(msg);
      if (!isRefresh) toast.error(msg);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [corporateId, programId, reportingCurrency]);

  useEffect(() => { loadHierarchy(); }, [loadHierarchy]);

  const toggle = (setter: React.Dispatch<React.SetStateAction<Set<string>>>) => (id: string) =>
    setter(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });

  const expandAll = () => {
    const ids: string[] = [];
    const collect = (list: EntityWithAccounts[]) => list.forEach(e => { ids.push(e.id); collect(e.childEntities); });
    collect(entityHierarchy);
    setExpandedEntities(new Set(ids));
  };

  const collapseAll = () => {
    setExpandedEntities(new Set());
    setExpandedSections(new Set());
  };

  const filteredHierarchy = useMemo(() => filterHierarchy(entityHierarchy, searchQuery), [entityHierarchy, searchQuery]);

  const totals = useMemo(() => {
    const count = (list: EntityWithAccounts[], pick: (e: EntityWithAccounts) => number): number =>
      list.reduce((sum, e) => sum + pick(e) + count(e.childEntities, pick), 0);
    return {
      entities: count(entityHierarchy, () => 1),
      treasuryCenters: count(entityHierarchy, e => (e.isTreasuryCenter ? 1 : 0)),
      // Root entities carry their descendants' balances and accounts.
      accounts: entityHierarchy.reduce((sum, e) => sum + e.accountCount, 0),
      balance: entityHierarchy.reduce((sum, e) => sum + e.rolledUpBalanceInBase, 0),
    };
  }, [entityHierarchy]);

  // Re-runs the balance aggregation, then reloads.
  const handleRefresh = async () => {
    try {
      setRefreshing(true);
      await balanceAggregationApi.triggerScheduled();
      toast.success('Balance refresh triggered');
      setTimeout(() => loadHierarchy(true), 1000);
    } catch {
      toast.error('Failed to trigger refresh');
      setRefreshing(false);
    }
  };

  return (
    <>
      <Card padding="none" className="overflow-hidden">
        <div className="flex flex-wrap items-center gap-3 p-4 border-b border-edge">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <input
              type="text"
              placeholder="Search entities..."
              aria-label="Search entities"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="w-64 h-9 pl-10 pr-4 rounded-lg border border-edge bg-surface-card text-body-sm placeholder:text-neutral-400 focus:outline-none focus:border-primary-300 focus:ring-2 focus:ring-primary-500/10"
            />
          </div>
          {!loading && !error && (
            <span className="body-sm">
              {totals.entities} entities · {totals.accounts} accounts
              {totals.treasuryCenters > 0 && ` · ${totals.treasuryCenters} treasury center${totals.treasuryCenters > 1 ? 's' : ''}`}
              {' · '}<span className="body-strong">{formatCurrency(totals.balance, reportingCurrency)}</span>
            </span>
          )}
          <div className="flex items-center gap-2 ml-auto">
            <Button variant="ghost" size="sm" onClick={expandAll}>Expand All</Button>
            <Button variant="ghost" size="sm" onClick={collapseAll}>Collapse</Button>
            <Button variant="ghost" size="sm" onClick={handleRefresh} disabled={refreshing} title="Re-run balance aggregation and reload">
              <RefreshCw className={cn('w-4 h-4 mr-1', refreshing && 'animate-spin')} />Refresh
            </Button>
          </div>
        </div>

        {error ? (
          <div className="flex items-center justify-between gap-3 p-4 bg-error-50 dark:bg-error-500/10">
            <span className="flex items-center gap-2 text-error-800 font-medium dark:text-error-300">
              <XCircle className="w-5 h-5" />{error}
            </span>
            <Button variant="secondary" size="sm" onClick={() => loadHierarchy()}>Retry</Button>
          </div>
        ) : loading ? (
          <div className="flex items-center justify-center py-20">
            <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
          </div>
        ) : filteredHierarchy.length === 0 ? (
          <div className="text-center py-20">
            <StatusIconBadge tone="neutral" icon={TreeDeciduous} size="xl" className="mx-auto mb-4" />
            <p className="text-neutral-600 font-medium dark:text-neutral-300">No entities found</p>
          </div>
        ) : (
          <div className="divide-y divide-edge-subtle max-h-[600px] overflow-y-auto">
            {filteredHierarchy.map(entity => (
              <EntityHierarchyNode
                key={entity.id}
                entity={entity}
                depth={0}
                expandedEntities={expandedEntities}
                expandedSections={expandedSections}
                onToggleEntity={toggle(setExpandedEntities)}
                onToggleSection={toggle(setExpandedSections)}
                reportingCurrency={reportingCurrency}
                onViewAccount={setSelectedAccount}
              />
            ))}
          </div>
        )}
      </Card>

      {selectedAccount && (
        <Modal isOpen onClose={() => setSelectedAccount(null)} title="Account Details" size="md">
          <div className="space-y-6">
            <div className="grid grid-cols-2 gap-4">
              <div className="p-3 bg-surface-page rounded-lg">
                <p className="label-cased">Account Name</p>
                <p className="font-semibold text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.name}</p>
              </div>
              <div className="p-3 bg-surface-page rounded-lg">
                <p className="label-cased">Account Number</p>
                <p className="font-mono text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.accountNumber || 'N/A'}</p>
              </div>
              <div className="p-3 bg-surface-page rounded-lg">
                <p className="label-cased">Currency</p>
                <p className="font-semibold text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.currencyCode}</p>
              </div>
              <div className="p-3 bg-surface-page rounded-lg">
                <p className="label-cased">Category</p>
                <Badge className="mt-1">{selectedAccount.accountCategory || selectedAccount.type}</Badge>
              </div>
            </div>

            <div className="p-4 bg-success-50 rounded-lg border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
              <p className="text-body-sm text-neutral-500 font-medium dark:text-neutral-400">Current Balance</p>
              <p className={cn(
                'stat-value mt-1',
                (selectedAccount.localBalance || 0) >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
              )}>
                {formatCurrency(selectedAccount.localBalance || 0, selectedAccount.currencyCode)}
              </p>
            </div>

            {selectedAccount.mirrorAccountType && selectedAccount.mirrorAccountType !== 'NONE' && (
              <div className="pt-4 border-t border-edge-subtle">
                <p className="text-body-sm text-neutral-500 font-medium mb-2 dark:text-neutral-400">Mirror Account Type</p>
                <Badge variant="info" size="md">{selectedAccount.mirrorAccountType}</Badge>
              </div>
            )}
          </div>
        </Modal>
      )}
    </>
  );
};
