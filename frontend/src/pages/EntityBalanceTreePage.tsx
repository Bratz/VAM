import React, { useState, useEffect, useCallback, useMemo } from 'react';
import toast from 'react-hot-toast';
import {
  ChevronRight, Building2, Wallet, TrendingUp, TrendingDown, Globe,
  DollarSign, RefreshCw, Layers, Loader2, AlertCircle, Eye, GitBranch, Crown,
  PiggyBank, Coins, Scale, AlertTriangle, Banknote, ArrowLeftRight,
  Search, Maximize2, Minimize2, LayoutGrid, FolderKanban,
  Network, TreeDeciduous, Users,
} from 'lucide-react';
import { Card, Button, Badge, Skeleton, Select, StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import {
  balanceStructureApi,
  BalanceHierarchyNode,
  corporatesApi,
  legalEntityApi,
  programsApi,
  LegalEntity,
} from '../services/api';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

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
  owningEntityId?: string;
  owningEntityCode?: string;
  owningEntityName?: string;
  mirrorBalance?: number;
  fxRate?: number;
  balanceInBase?: number;
  baseCurrency?: string;
  ihbParticipant?: boolean;
  ihbSettlementVaId?: string;
  icReceivableVaId?: string;
  bankBalance?: number;
  bankAccountNumber?: string;
  linkedPhysicalAccountId?: string;
}

interface EntityGroup {
  entityId: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'TREASURY_CENTER';
  countryCode?: string;
  functionalCurrency: string;
  totalBalance: number;
  totalBalanceInBase: number;
  accounts: {
    shadow: ExtendedNode[];
    currencyMirrors: ExtendedNode[];
    ihbAccounts: ExtendedNode[];
    settlementVas: ExtendedNode[];
    exceptionVas: ExtendedNode[];
    aggregations: ExtendedNode[];
    transactionVas: ExtendedNode[];
    intercompanyVas: ExtendedNode[];
  };
  isTreasuryCenter: boolean;
  canLend: boolean;
  canBorrow: boolean;
}

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  status: string;
}

// Extended interface for entities with their accounts and calculated balances
interface EntityWithAccounts extends LegalEntity {
  accounts: {
    shadow: ExtendedNode[];
    currencyMirrors: ExtendedNode[];
    ihbAccounts: ExtendedNode[];
    settlementVas: ExtendedNode[];
    exceptionVas: ExtendedNode[];
    aggregations: ExtendedNode[];
    transactionVas: ExtendedNode[];
    intercompanyVas: ExtendedNode[];
  };
  ownBalance: number;           // Balance from own leaf accounts only
  ownBalanceInBase: number;     // Own balance converted to base currency
  rolledUpBalance: number;      // Total including all descendants
  rolledUpBalanceInBase: number;// Rolled-up in base currency
  childEntities: EntityWithAccounts[];  // Child entities in hierarchy
  accountCount: number;         // Total accounts including children
  hierarchyDepth: number;       // Depth level in tree (0 = root)
}

interface ProgramOption {
  id: string;
  programName: string;
  programCode: string;
  currencyCode: string;
  programType: string;
  status: string;
}

// ============================================================================
// ACCOUNT TYPE CONFIGURATIONS
// ============================================================================

const ACCOUNT_TYPE_CONFIG: Record<string, {
  label: string;
  icon: React.FC<{ className?: string }>;
  color: string;
  bgColor: string;
  borderColor: string;
  description: string;
  isLeaf: boolean; // Whether to include in balance totals
}> = {
  shadow: {
    label: 'Shadow Accounts',
    icon: Layers,
    color: 'text-warning-600 dark:text-warning-300',
    bgColor: 'bg-warning-50 dark:bg-warning-500/10',
    borderColor: 'border-warning-200 dark:border-warning-500/30',
    description: 'Physical bank account mirrors (CBS)',
    isLeaf: true,
  },
  currencyMirrors: {
    label: 'Currency Mirrors',
    icon: Coins,
    color: 'text-info-600 dark:text-info-300',
    bgColor: 'bg-info-50 dark:bg-info-500/10',
    borderColor: 'border-info-200 dark:border-info-500/30',
    description: 'Currency-wise balance aggregation',
    isLeaf: false, // Aggregation - don't double count
  },
  ihbAccounts: {
    label: 'IHB Accounts',
    icon: PiggyBank,
    color: 'text-primary-600 dark:text-primary-200',
    bgColor: 'bg-primary-50 dark:bg-primary-800/40',
    borderColor: 'border-primary-200',
    description: 'In-House Banking current accounts',
    isLeaf: true,
  },
  settlementVas: {
    label: 'Settlement VAs',
    icon: Scale,
    color: 'text-purple-600 dark:text-purple-300',
    bgColor: 'bg-purple-50 dark:bg-purple-500/10',
    borderColor: 'border-purple-200 dark:border-purple-500/30',
    description: 'Fee collection and settlement',
    isLeaf: true,
  },
  exceptionVas: {
    label: 'Exception VAs',
    icon: AlertTriangle,
    color: 'text-error-600 dark:text-error-300',
    bgColor: 'bg-error-50 dark:bg-error-500/10',
    borderColor: 'border-error-200 dark:border-error-500/30',
    description: 'Suspense for unmatched transactions',
    isLeaf: true,
  },
  aggregations: {
    label: 'Aggregation Nodes',
    icon: GitBranch,
    color: 'text-neutral-600 dark:text-neutral-300',
    bgColor: 'bg-neutral-100 dark:bg-primary-800',
    borderColor: 'border-neutral-200 dark:border-primary-800',
    description: 'Hierarchy grouping nodes',
    isLeaf: false, // Aggregation - don't double count
  },
  transactionVas: {
    label: 'Transaction VAs',
    icon: Wallet,
    color: 'text-success-600 dark:text-success-300',
    bgColor: 'bg-success-50 dark:bg-success-500/10',
    borderColor: 'border-success-200 dark:border-success-500/30',
    description: 'Operational virtual accounts',
    isLeaf: true,
  },
  intercompanyVas: {
    label: 'Intercompany VAs',
    icon: ArrowLeftRight,
    color: 'text-pink-600 dark:text-pink-300',
    bgColor: 'bg-pink-50 dark:bg-pink-500/10',
    borderColor: 'border-pink-200 dark:border-pink-500/30',
    description: 'IC Receivable/Payable tracking',
    isLeaf: true,
  },
};

// ============================================================================
// SELECTOR BAR COMPONENT - extends shared ScopeSelector with a 3rd currency
// dropdown (this page is the only one that has it). Reporting currency
// affects the FX-converted aggregate tree below; passed through ScopeSelector's
// `rightSlot`.
// ============================================================================

interface SelectorBarProps {
  corporates: Corporate[];
  programs: ProgramOption[];
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  reportingCurrency: string;
  onCurrencyChange: (currency: string) => void;
  loading?: boolean;
  onRefresh?: () => void;
  refreshing?: boolean;
}

const CURRENCY_OPTIONS = [
  { value: 'AED', label: 'AED - UAE Dirham' },
  { value: 'USD', label: 'USD - US Dollar' },
  { value: 'EUR', label: 'EUR - Euro' },
  { value: 'GBP', label: 'GBP - British Pound' },
  { value: 'SAR', label: 'SAR - Saudi Riyal' },
];

const SelectorBar: React.FC<SelectorBarProps> = ({
  corporates,
  programs,
  selectedCorporateId,
  selectedProgramId,
  onCorporateChange,
  onProgramChange,
  reportingCurrency,
  onCurrencyChange,
  loading,
  onRefresh,
  refreshing,
}) => (
  <ScopeSelector
    mode="corporate-program"
    corporates={corporates}
    programs={programs}
    selectedCorporateId={selectedCorporateId}
    selectedProgramId={selectedProgramId}
    onCorporateChange={onCorporateChange}
    onProgramChange={onProgramChange}
    loading={loading}
    onRefresh={onRefresh}
    refreshing={refreshing}
    // Third dropdown: reporting currency for FX-converted aggregates.
    rightSlot={(
      <div className="flex items-center gap-2">
        <StatusIconBadge tone="success" icon={DollarSign} size="sm" />
        <div className="min-w-[160px]">
          <label className="label">Currency</label>
          <Select
            value={reportingCurrency}
            onChange={(e) => onCurrencyChange(e.target.value)}
            options={CURRENCY_OPTIONS}
            disabled={loading}
            selectSize="sm"
            aria-label="Reporting currency"
          />
        </div>
      </div>
    )}
  />
);

// Legacy inline implementation deleted — ScopeSelector now drives the picker.

// ============================================================================
// STAT CARD COMPONENT - Dashboard-inspired (white background with iconBg)
// ============================================================================

interface StatCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  iconBg?: string;
  loading?: boolean;
  delay?: number;
  onClick?: () => void;
}

const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  iconBg = 'bg-primary-100 dark:bg-primary-700',
  loading,
  delay = 0,
  onClick,
}) => {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="flex items-start justify-between">
          <div className="space-y-3 flex-1">
            <Skeleton className="h-4 w-24" />
            <Skeleton className="h-8 w-32" />
            <Skeleton className="h-3 w-20" />
          </div>
          <Skeleton className="w-12 h-12 rounded-xl" />
        </div>
      </Card>
    );
  }

  return (
    <Card
      interactive={!!onClick}
      hover
      className={cn(
        'animate-fade-in group',
        onClick && 'cursor-pointer'
      )}
      style={{ animationDelay: `${delay}s` }}
      onClick={onClick}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-sm font-medium text-neutral-500 tracking-wide dark:text-neutral-400">{title}</p>
          <p className="stat-value-sm mt-1.5">{value}</p>
          {subtitle && (
            <p className="text-sm text-neutral-500 mt-2 dark:text-neutral-400">{subtitle}</p>
          )}
        </div>
        <div className={cn(
          'p-3 rounded-xl transition-transform duration-300',
          iconBg,
          onClick && 'group-hover:scale-110'
        )}>
          {icon}
        </div>
      </div>
    </Card>
  );
};

// ============================================================================
// ENTITY CARD COMPONENT - Premium Design
// ============================================================================

interface EntityCardProps {
  entity: EntityGroup;
  isExpanded: boolean;
  onToggle: () => void;
  expandedSections: Set<string>;
  onToggleSection: (sectionId: string) => void;
  reportingCurrency: string;
  onViewAccount: (node: ExtendedNode) => void;
  delay?: number;
}

const EntityCard: React.FC<EntityCardProps> = ({
  entity,
  isExpanded,
  onToggle,
  expandedSections,
  onToggleSection,
  reportingCurrency,
  onViewAccount,
  delay = 0,
}) => {
  const getSectionId = (type: string) => `${entity.entityId}-${type}`;

  const renderAccountSection = (
    type: keyof typeof ACCOUNT_TYPE_CONFIG,
    accounts: ExtendedNode[]
  ) => {
    if (accounts.length === 0) return null;

    const config = ACCOUNT_TYPE_CONFIG[type];
    const sectionId = getSectionId(type);
    const isSectionExpanded = expandedSections.has(sectionId);
    const Icon = config.icon;

    const totalBalance = accounts.reduce((sum, acc) =>
      sum + (acc.localBalance || acc.mirrorBalance || 0), 0);

    return (
      <div key={type} className="border-t border-neutral-100 dark:border-primary-800/60">
        <button
          onClick={(e) => { e.stopPropagation(); onToggleSection(sectionId); }}
          className={cn(
            'w-full flex items-center gap-3 px-4 py-3 transition-all duration-200',
            'hover:bg-neutral-50 group dark:hover:bg-primary-800/50',
            `border-l-4 ${config.borderColor}`
          )}
        >
          <div className={cn(
            'transition-transform duration-200',
            isSectionExpanded && 'rotate-90'
          )}>
            <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          </div>
          <div className={cn('p-2 rounded-lg transition-transform duration-300 group-hover:scale-110', config.bgColor)}>
            <Icon className={cn('w-4 h-4', config.color)} />
          </div>
          <div className="flex-1 text-left">
            <div className="flex items-center gap-2">
              <span className={cn('text-sm font-semibold', config.color)}>
                {config.label}
              </span>
              <Badge variant="neutral" size="sm" className="font-bold">
                {accounts.length}
              </Badge>
              {!config.isLeaf && (
                <Badge variant="neutral" size="sm" className="text-neutral-400 text-xs dark:text-neutral-500">
                  Rolled-up
                </Badge>
              )}
            </div>
            <p className="text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">{config.description}</p>
          </div>
          <div className="text-right">
            <p className={cn('text-sm font-bold', config.color)}>
              {formatCurrency(totalBalance, entity.functionalCurrency)}
            </p>
          </div>
        </button>

        {isSectionExpanded && (
          <div className="bg-white divide-y divide-neutral-50 animate-fade-in dark:bg-primary-900">
            {accounts.map((account, idx) => (
              <AccountRow
                key={account.id}
                account={account}
                type={type}
                reportingCurrency={reportingCurrency}
                onView={() => onViewAccount(account)}
                delay={idx * 0.02}
              />
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <Card
      className="overflow-hidden hover:shadow-lg transition-all duration-300 animate-fade-in"
      style={{ animationDelay: `${delay}s` }}
    >
      {/* Entity Header */}
      <button
        onClick={onToggle}
        className={cn(
          'w-full flex items-center gap-4 p-5 transition-all duration-200',
          entity.isTreasuryCenter
            ? 'bg-gradient-to-r from-warning-50 via-white to-warning-50 hover:from-warning-100 hover:to-warning-100 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900'
            : 'bg-gradient-to-r from-neutral-50 via-white to-neutral-50 hover:from-neutral-100 hover:to-neutral-100 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900'
        )}
      >
        <div className={cn(
          'transition-transform duration-200',
          isExpanded && 'rotate-90'
        )}>
          <ChevronRight className="w-5 h-5 text-neutral-500 dark:text-neutral-400" />
        </div>

        <div className={cn(
          'p-3 rounded-xl transition-transform duration-300 hover:scale-110',
          entity.isTreasuryCenter
            ? 'bg-warning-100 dark:bg-warning-500/20'
            : 'bg-primary-100 dark:bg-primary-700'
        )}>
          {entity.isTreasuryCenter ? (
            <Crown className="w-6 h-6 text-warning-600 dark:text-warning-300" />
          ) : (
            <Building2 className="w-6 h-6 text-primary-600 dark:text-primary-200" />
          )}
        </div>

        <div className="flex-1 text-left">
          <div className="flex items-center gap-2 flex-wrap">
            <h3 className="text-lg font-bold text-neutral-900 dark:text-neutral-50">
              {entity.entityName}
            </h3>
            <Badge variant="neutral" size="sm" className="font-mono">
              {entity.entityCode}
            </Badge>
            {entity.isTreasuryCenter && (
              <Badge variant="warning" size="sm" className="flex items-center gap-1">
                <Crown className="w-3 h-3" />
                Treasury Center
              </Badge>
            )}
            {entity.canLend && !entity.isTreasuryCenter && (
              <Badge variant="info" size="sm">Lender</Badge>
            )}
            {entity.canBorrow && (
              <Badge variant="success" size="sm">Borrower</Badge>
            )}
          </div>
          <div className="flex items-center gap-4 text-sm text-neutral-500 mt-1.5 dark:text-neutral-400">
            <span className="flex items-center gap-1">
              <Globe className="w-3.5 h-3.5" />
              {entity.countryCode || 'N/A'}
            </span>
            <span className="flex items-center gap-1">
              <DollarSign className="w-3.5 h-3.5" />
              {entity.functionalCurrency}
            </span>
            <span className="flex items-center gap-1">
              <Wallet className="w-3.5 h-3.5" />
              {Object.values(entity.accounts).flat().length} accounts
            </span>
          </div>
        </div>

        <div className="text-right">
          <div className="flex items-center justify-end gap-2">
            {entity.totalBalance >= 0 ? (
              <TrendingUp className="w-5 h-5 text-success-500" />
            ) : (
              <TrendingDown className="w-5 h-5 text-error-500" />
            )}
            <p className={cn(
              'text-xl font-bold',
              entity.totalBalance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
            )}>
              {formatCurrency(entity.totalBalance, entity.functionalCurrency)}
            </p>
          </div>
          {entity.functionalCurrency !== reportingCurrency && (
            <p className="text-sm text-neutral-400 mt-0.5 dark:text-neutral-500">
              ≈ {formatCurrency(entity.totalBalanceInBase, reportingCurrency)}
            </p>
          )}
        </div>
      </button>

      {/* Account Sections */}
      {isExpanded && (
        <div className="divide-y divide-neutral-100 animate-fade-in dark:divide-primary-800/60">
          {renderAccountSection('shadow', entity.accounts.shadow)}
          {renderAccountSection('currencyMirrors', entity.accounts.currencyMirrors)}
          {renderAccountSection('ihbAccounts', entity.accounts.ihbAccounts)}
          {renderAccountSection('settlementVas', entity.accounts.settlementVas)}
          {renderAccountSection('exceptionVas', entity.accounts.exceptionVas)}
          {renderAccountSection('aggregations', entity.accounts.aggregations)}
          {renderAccountSection('transactionVas', entity.accounts.transactionVas)}
          {renderAccountSection('intercompanyVas', entity.accounts.intercompanyVas)}
        </div>
      )}
    </Card>
  );
};

// ============================================================================
// ACCOUNT ROW COMPONENT
// ============================================================================

interface AccountRowProps {
  account: ExtendedNode;
  type: keyof typeof ACCOUNT_TYPE_CONFIG;
  reportingCurrency: string;
  onView: () => void;
  delay?: number;
  indentLevel?: number;
}

const AccountRow: React.FC<AccountRowProps> = ({
  account,
  type,
  reportingCurrency,
  onView,
  delay = 0,
  indentLevel = 0,
}) => {
  const config = ACCOUNT_TYPE_CONFIG[type];
  const Icon = config.icon;
  const balance = account.localBalance || account.mirrorBalance || 0;

  const getMirrorTypeBadge = () => {
    if (!account.mirrorAccountType || account.mirrorAccountType === 'NONE') return null;

    switch (account.mirrorAccountType) {
      case 'IHB_CURRENT':
        return <Badge variant="info" size="sm">IHB Current</Badge>;
      case 'IHB_SETTLEMENT':
        return <Badge variant="info" size="sm">IHB Settlement</Badge>;
      case 'IC_RECEIVABLE':
        return <Badge variant="warning" size="sm">IC Receivable</Badge>;
      case 'TREASURY_SETTLEMENT':
        return <Badge variant="accent" size="sm">Treasury Settlement</Badge>;
      default:
        return null;
    }
  };

  return (
    <div
      className={cn(
        'flex items-center gap-3 px-4 py-3',
        'hover:bg-neutral-50 cursor-pointer transition-all duration-200 group animate-fade-in dark:hover:bg-primary-800/50'
      )}
      style={{ animationDelay: `${delay}s`, paddingLeft: `${Math.max(56, indentLevel * 24 + 56)}px` }}
      onClick={onView}
    >
      <div className={cn('p-2 rounded-lg transition-transform duration-300 group-hover:scale-110', config.bgColor)}>
        <Icon className={cn('w-4 h-4', config.color)} />
      </div>

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium text-neutral-900 truncate dark:text-neutral-50">
            {account.name}
          </p>
          {getMirrorTypeBadge()}
          {account.ihbParticipant && (
            <Badge variant="info" size="sm" className="flex items-center gap-1">
              <PiggyBank className="w-3 h-3" />
              IHB
            </Badge>
          )}
        </div>
        <div className="flex items-center gap-2 text-xs text-neutral-500 mt-0.5 dark:text-neutral-400">
          {account.accountNumber && (
            <span className="font-mono">{account.accountNumber}</span>
          )}
          {account.bankAccountNumber && (
            <span className="flex items-center gap-1">
              <Banknote className="w-3 h-3" />
              {account.bankAccountNumber}
            </span>
          )}
        </div>
      </div>

      <Badge variant="neutral" size="sm" className="font-mono">
        {account.currencyCode}
      </Badge>

      <div className="text-right min-w-[120px]">
        <p className={cn('text-sm font-semibold', balance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300')}>
          {formatCurrency(balance, account.currencyCode)}
        </p>
        {account.currencyCode !== reportingCurrency && account.balanceInBase !== undefined && (
          <p className="text-xs text-neutral-400 dark:text-neutral-500">
            ≈ {formatCurrency(account.balanceInBase, reportingCurrency)}
          </p>
        )}
      </div>

      <Eye className="w-4 h-4 text-neutral-400 opacity-0 group-hover:opacity-100 transition-all duration-200 group-hover:translate-x-1 dark:text-neutral-500" />
    </div>
  );
};

// ============================================================================
// PYRAMID VISUALIZATION COMPONENT
// ============================================================================

interface PyramidViewProps {
  entityGroups: EntityGroup[];
  reportingCurrency: string;
  onEntityClick: (entityId: string) => void;
}

const PyramidView: React.FC<PyramidViewProps> = ({
  entityGroups,
  reportingCurrency,
  onEntityClick,
}) => {
  const sortedEntities = useMemo(() => {
    return [...entityGroups].sort((a, b) => {
      if (a.isTreasuryCenter && !b.isTreasuryCenter) return -1;
      if (!a.isTreasuryCenter && b.isTreasuryCenter) return 1;
      return b.totalBalanceInBase - a.totalBalanceInBase;
    });
  }, [entityGroups]);

  const totalBalance = entityGroups.reduce((sum, e) => sum + e.totalBalanceInBase, 0);
  const treasuryCenter = entityGroups.find(e => e.isTreasuryCenter);

  const colorClasses = [
    'bg-primary-500',
    'bg-success-500',
    'bg-purple-500',
    'bg-pink-500',
    'bg-info-500',
    'bg-warning-500',
  ];

  return (
    <div className="flex flex-col items-center gap-6 py-10 animate-fade-in">
      {/* Corporate Total - Top of Pyramid */}
      <div className="w-72 bg-neutral-800 rounded-2xl p-6 text-center shadow-xl">
        <div className="w-14 h-14 bg-white/10 rounded-xl flex items-center justify-center mx-auto mb-3">
          <Globe className="w-7 h-7 text-white" />
        </div>
        <p className="text-white/70 text-sm font-medium">Corporate Total</p>
        <p className="stat-value text-white mt-1">
          {formatCurrency(totalBalance, reportingCurrency)}
        </p>
        <Badge variant="neutral" size="sm" className="mt-3 bg-white/10 text-white border-0">
          {entityGroups.length} Entities
        </Badge>
      </div>

      {/* Treasury Center - Second Level */}
      {treasuryCenter && (
        <div
          className="w-80 bg-warning-500 rounded-2xl p-5 text-center shadow-lg cursor-pointer transform hover:scale-105 transition-all duration-300 hover:shadow-xl"
          onClick={() => onEntityClick(treasuryCenter.entityId)}
        >
          <div className="flex items-center justify-center gap-2 mb-2">
            <Crown className="w-6 h-6 text-white" />
            <p className="text-white font-bold text-lg">{treasuryCenter.entityName}</p>
          </div>
          <p className="text-white/80 text-sm">Treasury Center</p>
          <p className="stat-value-sm text-white mt-1">
            {formatCurrency(treasuryCenter.totalBalance, treasuryCenter.functionalCurrency)}
          </p>
        </div>
      )}

      {/* Subsidiaries - Pyramid Rows */}
      <div className="flex flex-wrap justify-center gap-4 max-w-5xl">
        {sortedEntities.filter(e => !e.isTreasuryCenter).map((entity, index) => {
          const widthPercent = Math.max(35, Math.min(100, (entity.totalBalanceInBase / totalBalance) * 400));

          return (
            <div
              key={entity.entityId}
              className={cn(
                'rounded-xl p-4 text-center shadow-md cursor-pointer',
                'transform hover:scale-105 transition-all duration-300 hover:shadow-lg',
                colorClasses[index % colorClasses.length]
              )}
              style={{ width: `${widthPercent}%`, minWidth: '200px', maxWidth: '280px' }}
              onClick={() => onEntityClick(entity.entityId)}
            >
              <p className="text-white font-semibold truncate">{entity.entityName}</p>
              <p className="text-white/70 text-xs font-mono">{entity.entityCode}</p>
              <p className="text-white font-bold text-lg mt-1">
                {formatCurrency(entity.totalBalance, entity.functionalCurrency)}
              </p>
            </div>
          );
        })}
      </div>
    </div>
  );
};

// ============================================================================
// ENTITY HIERARCHY NODE COMPONENT - Recursive tree display
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
  const hasChildren = entity.childEntities && entity.childEntities.length > 0;
  const hasAccounts = Object.values(entity.accounts).flat().length > 0;

  const getSectionId = (type: string) => `${entity.id}-${type}`;

  // Entity type styling
  const getEntityTypeStyle = () => {
    if (entity.isTreasuryCenter) {
      return {
        bg: 'bg-warning-100 dark:bg-warning-500/20',
        icon: <Crown className="w-5 h-5 text-warning-600 dark:text-warning-300" />,
        badge: 'warning' as const,
      };
    }
    switch (entity.entityType) {
      case 'HOLDING':
        return {
          bg: 'bg-purple-100 dark:bg-purple-500/20',
          icon: <Network className="w-5 h-5 text-purple-600 dark:text-purple-300" />,
          badge: 'accent' as const,
        };
      case 'SUBSIDIARY':
        return {
          bg: 'bg-primary-100 dark:bg-primary-700',
          icon: <Building2 className="w-5 h-5 text-primary-600 dark:text-primary-200" />,
          badge: 'info' as const,
        };
      case 'BRANCH':
        return {
          bg: 'bg-success-100 dark:bg-success-500/20',
          icon: <GitBranch className="w-5 h-5 text-success-600 dark:text-success-300" />,
          badge: 'success' as const,
        };
      default:
        return {
          bg: 'bg-neutral-100 dark:bg-primary-800',
          icon: <Building2 className="w-5 h-5 text-neutral-600 dark:text-neutral-300" />,
          badge: 'neutral' as const,
        };
    }
  };

  const typeStyle = getEntityTypeStyle();

  const renderAccountSection = (
    type: keyof typeof ACCOUNT_TYPE_CONFIG,
    accounts: ExtendedNode[]
  ) => {
    if (accounts.length === 0) return null;

    const config = ACCOUNT_TYPE_CONFIG[type];
    const sectionId = getSectionId(type);
    const isSectionExpanded = expandedSections.has(sectionId);
    const Icon = config.icon;

    const totalBalance = accounts.reduce((sum, acc) =>
      sum + (acc.localBalance || acc.mirrorBalance || 0), 0);

    return (
      <div key={type} className="border-t border-neutral-100 dark:border-primary-800/60">
        <button
          onClick={(e) => { e.stopPropagation(); onToggleSection(sectionId); }}
          className={cn(
            'w-full flex items-center gap-3 px-4 py-2.5 transition-all duration-200',
            'hover:bg-neutral-50 group dark:hover:bg-primary-800/50',
            `border-l-4 ${config.borderColor}`
          )}
          style={{ paddingLeft: `${(depth + 1) * 24 + 16}px` }}
        >
          <div className={cn(
            'transition-transform duration-200',
            isSectionExpanded && 'rotate-90'
          )}>
            <ChevronRight className="w-3.5 h-3.5 text-neutral-400 dark:text-neutral-500" />
          </div>
          <div className={cn('p-1.5 rounded-lg', config.bgColor)}>
            <Icon className={cn('w-3.5 h-3.5', config.color)} />
          </div>
          <div className="flex-1 text-left">
            <div className="flex items-center gap-2">
              <span className={cn('text-xs font-semibold', config.color)}>
                {config.label}
              </span>
              <Badge variant="neutral" size="sm" className="font-bold text-xs">
                {accounts.length}
              </Badge>
              {!config.isLeaf && (
                <Badge variant="neutral" size="sm" className="text-neutral-400 text-xs dark:text-neutral-500">
                  Rolled-up
                </Badge>
              )}
            </div>
          </div>
          <div className="text-right">
            <p className={cn('text-xs font-bold', config.color)}>
              {formatCurrency(totalBalance, entity.functionalCurrency)}
            </p>
          </div>
        </button>

        {isSectionExpanded && (
          <div className="bg-neutral-50/50 divide-y divide-neutral-100 animate-fade-in dark:divide-primary-800/60">
            {accounts.map((account, idx) => (
              <AccountRow
                key={account.id}
                account={account}
                type={type}
                reportingCurrency={reportingCurrency}
                onView={() => onViewAccount(account)}
                delay={idx * 0.02}
                indentLevel={depth + 2}
              />
            ))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="animate-fade-in">
      {/* Entity Header */}
      <div
        className={cn(
          'border-b border-neutral-100 hover:bg-neutral-50/70 transition-all duration-200 dark:border-primary-800/60',
          depth === 0 && 'bg-gradient-to-r from-neutral-50 via-white to-neutral-50 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900',
          entity.isTreasuryCenter && 'bg-gradient-to-r from-warning-50/30 via-white to-warning-50/30 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900'
        )}
      >
        <button
          onClick={() => onToggleEntity(entity.id)}
          className="w-full flex items-center gap-3 py-3 px-4"
          style={{ paddingLeft: `${depth * 24 + 16}px` }}
        >
          {/* Expand/Collapse Arrow */}
          <div className={cn(
            'transition-transform duration-200 w-5 h-5 flex items-center justify-center',
            isExpanded && 'rotate-90',
            !hasChildren && !hasAccounts && 'opacity-0'
          )}>
            {(hasChildren || hasAccounts) && (
              <ChevronRight className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            )}
          </div>

          {/* Entity Icon */}
          <div className={cn('p-2 rounded-xl transition-transform duration-300 hover:scale-110', typeStyle.bg)}>
            {typeStyle.icon}
          </div>

          {/* Entity Details */}
          <div className="flex-1 text-left min-w-0">
            <div className="flex items-center gap-2 flex-wrap">
              <h3 className={cn(
                'font-semibold text-neutral-900 truncate dark:text-neutral-50',
                depth === 0 ? 'text-base' : 'text-sm'
              )}>
                {entity.entityName}
              </h3>
              <Badge variant="neutral" size="sm" className="font-mono text-xs">
                {entity.entityCode}
              </Badge>
              {entity.isTreasuryCenter && (
                <Badge variant="warning" size="sm" className="flex items-center gap-1">
                  <Crown className="w-3 h-3" />
                  TC
                </Badge>
              )}
              {entity.entityType && (
                <Badge variant={typeStyle.badge} size="sm" className="text-xs">
                  {entity.entityType}
                </Badge>
              )}
            </div>
            <div className="flex items-center gap-3 text-xs text-neutral-500 mt-1 dark:text-neutral-400">
              {entity.countryCode && (
                <span className="flex items-center gap-1">
                  <Globe className="w-3 h-3" />
                  {entity.countryCode}
                </span>
              )}
              <span className="flex items-center gap-1">
                <DollarSign className="w-3 h-3" />
                {entity.functionalCurrency}
              </span>
              {hasChildren && (
                <span className="flex items-center gap-1">
                  <Users className="w-3 h-3" />
                  {entity.childEntities.length} child entities
                </span>
              )}
              <span className="flex items-center gap-1">
                <Wallet className="w-3 h-3" />
                {entity.accountCount} accounts
              </span>
            </div>
          </div>

          {/* Balances */}
          <div className="text-right shrink-0">
            <div className="flex items-center justify-end gap-2">
              {entity.rolledUpBalance >= 0 ? (
                <TrendingUp className="w-4 h-4 text-success-500" />
              ) : (
                <TrendingDown className="w-4 h-4 text-error-500" />
              )}
              <p className={cn(
                'font-bold',
                depth === 0 ? 'text-lg' : 'text-sm',
                entity.rolledUpBalance >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
              )}>
                {formatCurrency(entity.rolledUpBalance, entity.functionalCurrency)}
              </p>
            </div>
            {hasChildren && entity.ownBalance !== entity.rolledUpBalance && (
              <p className="text-xs text-neutral-400 mt-0.5 dark:text-neutral-500">
                Own: {formatCurrency(entity.ownBalance, entity.functionalCurrency)}
              </p>
            )}
            {entity.functionalCurrency !== reportingCurrency && (
              <p className="text-xs text-neutral-400 dark:text-neutral-500">
                ≈ {formatCurrency(entity.rolledUpBalanceInBase, reportingCurrency)}
              </p>
            )}
          </div>
        </button>
      </div>

      {/* Expanded Content */}
      {isExpanded && (
        <div className="animate-fade-in">
          {/* Account Sections */}
          {hasAccounts && (
            <div className="bg-white border-l-2 border-neutral-200 dark:bg-primary-900 dark:border-primary-800" style={{ marginLeft: `${depth * 24 + 36}px` }}>
              {renderAccountSection('shadow', entity.accounts.shadow)}
              {renderAccountSection('currencyMirrors', entity.accounts.currencyMirrors)}
              {renderAccountSection('ihbAccounts', entity.accounts.ihbAccounts)}
              {renderAccountSection('settlementVas', entity.accounts.settlementVas)}
              {renderAccountSection('exceptionVas', entity.accounts.exceptionVas)}
              {renderAccountSection('aggregations', entity.accounts.aggregations)}
              {renderAccountSection('transactionVas', entity.accounts.transactionVas)}
              {renderAccountSection('intercompanyVas', entity.accounts.intercompanyVas)}
            </div>
          )}

          {/* Child Entities - Recursive */}
          {hasChildren && entity.childEntities.map(child => (
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
// MAIN PAGE COMPONENT
// ============================================================================

const EntityBalanceTreePage: React.FC = () => {
  // State
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [entityGroups, setEntityGroups] = useState<EntityGroup[]>([]); // Flat view fallback
  const [entityHierarchy, setEntityHierarchy] = useState<EntityWithAccounts[]>([]); // Hierarchical view
  const [reportingCurrency, setReportingCurrency] = useState('AED');
  const [viewMode, setViewMode] = useState<'hierarchy' | 'flat' | 'pyramid'>('hierarchy');

  // UI State
  const [expandedEntities, setExpandedEntities] = useState<Set<string>>(new Set());
  const [expandedSections, setExpandedSections] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedAccount, setSelectedAccount] = useState<ExtendedNode | null>(null);

  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        const response = await corporatesApi.getAll();
        const data = response?.data || response;
        const corpList = Array.isArray(data) ? data : [data].filter(Boolean);
        setCorporates(corpList);
        if (corpList.length > 0) {
          setSelectedCorporateId(corpList[0].id);
        }
      } catch (err: any) {
        console.error('Failed to load corporates:', err);
      }
    };
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    const loadPrograms = async () => {
      try {
        const response = await programsApi.getAll();
        const data = response?.data?.programs || response?.data || response;
        const programList = (Array.isArray(data) ? data : [])
          .filter((p: ProgramOption) => p.status === 'ACTIVE');
        setPrograms(programList);
      } catch (err) {
        console.error('Failed to load programs:', err);
      }
    };
    loadPrograms();
  }, [selectedCorporateId]);

  // Build EntityWithAccounts tree from legal entity hierarchy and account data
  const buildEntityAccountsTree = useCallback((
    legalEntities: LegalEntity[],
    accountsByEntity: Map<string, ExtendedNode[]>,
    depth: number = 0
  ): EntityWithAccounts[] => {
    return legalEntities.map(entity => {
      // Get accounts for this entity
      const entityAccounts = accountsByEntity.get(entity.id) || [];

      // Categorize accounts
      const categorizedAccounts = {
        shadow: [] as ExtendedNode[],
        currencyMirrors: [] as ExtendedNode[],
        ihbAccounts: [] as ExtendedNode[],
        settlementVas: [] as ExtendedNode[],
        exceptionVas: [] as ExtendedNode[],
        aggregations: [] as ExtendedNode[],
        transactionVas: [] as ExtendedNode[],
        intercompanyVas: [] as ExtendedNode[],
      };

      let ownBalance = 0;
      let ownBalanceInBase = 0;

      entityAccounts.forEach(node => {
        const balance = node.localBalance || node.mirrorBalance || 0;
        const balanceInBase = node.consolidatedBalance || node.balanceInBase || balance;
        const category = node.accountCategory;
        const mirrorType = node.mirrorAccountType;
        let accountType: keyof typeof ACCOUNT_TYPE_CONFIG;

        if (category === 'PHYSICAL_MIRROR' || node.type === 'SHADOW_ACCOUNT') {
          accountType = 'shadow';
          categorizedAccounts.shadow.push(node);
        } else if (category === 'CURRENCY_MIRROR') {
          accountType = 'currencyMirrors';
          categorizedAccounts.currencyMirrors.push(node);
        } else if (mirrorType === 'IHB_CURRENT' || mirrorType === 'IHB_SETTLEMENT' || node.ihbParticipant) {
          accountType = 'ihbAccounts';
          categorizedAccounts.ihbAccounts.push(node);
        } else if (category === 'SETTLEMENT' || mirrorType === 'TREASURY_SETTLEMENT') {
          accountType = 'settlementVas';
          categorizedAccounts.settlementVas.push(node);
        } else if (category === 'EXCEPTION') {
          accountType = 'exceptionVas';
          categorizedAccounts.exceptionVas.push(node);
        } else if (category === 'AGGREGATION' || category === 'ROOT') {
          accountType = 'aggregations';
          categorizedAccounts.aggregations.push(node);
        } else if (category === 'INTERCOMPANY' || mirrorType === 'IC_RECEIVABLE') {
          accountType = 'intercompanyVas';
          categorizedAccounts.intercompanyVas.push(node);
        } else {
          accountType = 'transactionVas';
          categorizedAccounts.transactionVas.push(node);
        }

        // Only sum leaf accounts to avoid double-counting
        const config = ACCOUNT_TYPE_CONFIG[accountType];
        if (config.isLeaf) {
          ownBalance += balance;
          ownBalanceInBase += balanceInBase;
        }
      });

      // Recursively process children
      const childEntities = entity.children
        ? buildEntityAccountsTree(entity.children, accountsByEntity, depth + 1)
        : [];

      // Calculate rolled-up balance (own + all descendants)
      const childrenRolledUp = childEntities.reduce((sum, child) => sum + child.rolledUpBalance, 0);
      const childrenRolledUpBase = childEntities.reduce((sum, child) => sum + child.rolledUpBalanceInBase, 0);

      // Count total accounts including children
      const ownAccountCount = Object.values(categorizedAccounts).flat().length;
      const childAccountCount = childEntities.reduce((sum, child) => sum + child.accountCount, 0);

      return {
        ...entity,
        accounts: categorizedAccounts,
        ownBalance,
        ownBalanceInBase,
        rolledUpBalance: ownBalance + childrenRolledUp,
        rolledUpBalanceInBase: ownBalanceInBase + childrenRolledUpBase,
        childEntities,
        accountCount: ownAccountCount + childAccountCount,
        hierarchyDepth: depth,
      };
    });
  }, []);

  // Load hierarchy data - fetch both legal entity hierarchy and balance hierarchy
  const loadHierarchy = useCallback(async (isRefresh = false) => {
    if (!selectedCorporateId) return;

    if (isRefresh) {
      setRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);

    try {
      // Fetch legal entity hierarchy and balance data in parallel
      const [hierarchyResponse, balanceResponse] = await Promise.all([
        legalEntityApi.getHierarchy(selectedCorporateId).catch(() => null),
        balanceStructureApi.getHierarchy(
          selectedCorporateId,
          selectedProgramId || undefined,
          reportingCurrency
        ),
      ]);

      const balanceData = balanceResponse?.data || balanceResponse;

      // Build map of accounts by owning entity ID
      const accountsByEntity = new Map<string, ExtendedNode[]>();

      const collectAccounts = (node: ExtendedNode) => {
        if (node.owningEntityId) {
          if (!accountsByEntity.has(node.owningEntityId)) {
            accountsByEntity.set(node.owningEntityId, []);
          }
          accountsByEntity.get(node.owningEntityId)!.push(node);
        }
        if (node.children) {
          node.children.forEach(child => collectAccounts(child as ExtendedNode));
        }
      };
      collectAccounts(balanceData as ExtendedNode);

      // Try to use hierarchy if available
      // Extract data from API response - handle both wrapped and unwrapped responses
      let entityList: LegalEntity[] = [];
      if (hierarchyResponse) {
        const rawData = (hierarchyResponse as any)?.data || hierarchyResponse;
        if (Array.isArray(rawData)) {
          entityList = rawData;
        } else if (rawData && typeof rawData === 'object' && rawData.id) {
          entityList = [rawData];
        }
      }

      if (entityList.length > 0) {
        // Build hierarchical view from legal entity tree
        const tree = buildEntityAccountsTree(entityList, accountsByEntity, 0);
        setEntityHierarchy(tree);
      } else {
        // Fallback: Create flat list from account data
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
          if (node.children) {
            node.children.forEach(child => flattenAccounts(child as ExtendedNode));
          }
        };
        flattenAccounts(balanceData as ExtendedNode);

        const tree = buildEntityAccountsTree(
          Array.from(flatEntities.values()),
          accountsByEntity,
          0
        );
        setEntityHierarchy(tree);
      }

      // Also process flat entity groups for pyramid view
      processEntityGroups(balanceData as ExtendedNode);
    } catch (err: any) {
      const msg = err.response?.data?.message || err.message || 'Failed to load hierarchy';
      setError(msg);
      if (!isRefresh) {
        toast.error(msg);
      }
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [selectedCorporateId, selectedProgramId, reportingCurrency, buildEntityAccountsTree]);

  useEffect(() => {
    if (selectedCorporateId) {
      loadHierarchy();
    }
  }, [selectedCorporateId, selectedProgramId, reportingCurrency, loadHierarchy]);

  // Process hierarchy data into flat entity groups (for pyramid view fallback)
  // IMPORTANT: Only sum LEAF accounts (not aggregations) to avoid double-counting
  const processEntityGroups = (root: ExtendedNode) => {
    const groups: Map<string, EntityGroup> = new Map();

    const processNode = (node: ExtendedNode) => {
      const entityId = node.owningEntityId || 'unassigned';
      const entityCode = node.owningEntityCode || 'N/A';
      const entityName = node.owningEntityName || 'Unassigned';

      if (!groups.has(entityId)) {
        groups.set(entityId, {
          entityId,
          entityCode,
          entityName,
          entityType: 'SUBSIDIARY',
          countryCode: undefined,
          functionalCurrency: node.currencyCode || 'AED',
          totalBalance: 0,
          totalBalanceInBase: 0,
          accounts: {
            shadow: [],
            currencyMirrors: [],
            ihbAccounts: [],
            settlementVas: [],
            exceptionVas: [],
            aggregations: [],
            transactionVas: [],
            intercompanyVas: [],
          },
          isTreasuryCenter: false,
          canLend: false,
          canBorrow: false,
        });
      }

      const group = groups.get(entityId)!;
      const balance = node.localBalance || node.mirrorBalance || 0;
      const balanceInBase = node.consolidatedBalance || node.balanceInBase || balance;

      // Categorize account and determine if it should count toward totals
      const category = node.accountCategory;
      const mirrorType = node.mirrorAccountType;
      let accountType: keyof typeof ACCOUNT_TYPE_CONFIG;

      if (category === 'PHYSICAL_MIRROR' || node.type === 'SHADOW_ACCOUNT') {
        accountType = 'shadow';
        group.accounts.shadow.push(node);
      } else if (category === 'CURRENCY_MIRROR') {
        accountType = 'currencyMirrors';
        group.accounts.currencyMirrors.push(node);
      } else if (mirrorType === 'IHB_CURRENT' || mirrorType === 'IHB_SETTLEMENT' || node.ihbParticipant) {
        accountType = 'ihbAccounts';
        group.accounts.ihbAccounts.push(node);
      } else if (category === 'SETTLEMENT' || mirrorType === 'TREASURY_SETTLEMENT') {
        accountType = 'settlementVas';
        group.accounts.settlementVas.push(node);
      } else if (category === 'EXCEPTION') {
        accountType = 'exceptionVas';
        group.accounts.exceptionVas.push(node);
      } else if (category === 'AGGREGATION' || category === 'ROOT') {
        accountType = 'aggregations';
        group.accounts.aggregations.push(node);
      } else if (category === 'INTERCOMPANY' || mirrorType === 'IC_RECEIVABLE') {
        accountType = 'intercompanyVas';
        group.accounts.intercompanyVas.push(node);
      } else {
        accountType = 'transactionVas';
        group.accounts.transactionVas.push(node);
      }

      // Only add to totals if this is a LEAF account (not an aggregation)
      const config = ACCOUNT_TYPE_CONFIG[accountType];
      if (config.isLeaf) {
        group.totalBalance += balance;
        group.totalBalanceInBase += balanceInBase;
      }

      // Process children
      if (node.children) {
        node.children.forEach(child => processNode(child as ExtendedNode));
      }
    };

    processNode(root);
    setEntityGroups(Array.from(groups.values()));
  };

  // Toggle entity expansion
  const toggleEntity = (entityId: string) => {
    setExpandedEntities(prev => {
      const next = new Set(prev);
      if (next.has(entityId)) {
        next.delete(entityId);
      } else {
        next.add(entityId);
      }
      return next;
    });
  };

  // Toggle section expansion
  const toggleSection = (sectionId: string) => {
    setExpandedSections(prev => {
      const next = new Set(prev);
      if (next.has(sectionId)) {
        next.delete(sectionId);
      } else {
        next.add(sectionId);
      }
      return next;
    });
  };

  // Collect all entity IDs from hierarchy tree
  const collectAllEntityIds = useCallback((entities: EntityWithAccounts[]): string[] => {
    const ids: string[] = [];
    const collect = (list: EntityWithAccounts[]) => {
      list.forEach(entity => {
        ids.push(entity.id);
        if (entity.childEntities) {
          collect(entity.childEntities);
        }
      });
    };
    collect(entities);
    return ids;
  }, []);

  // Expand/collapse all
  const expandAll = () => {
    const allEntityIds = collectAllEntityIds(entityHierarchy);
    setExpandedEntities(new Set(allEntityIds));
  };

  const collapseAll = () => {
    setExpandedEntities(new Set());
    setExpandedSections(new Set());
  };

  // Filter entities in hierarchy by search
  const filterHierarchy = useCallback((entities: EntityWithAccounts[], query: string): EntityWithAccounts[] => {
    if (!query) return entities;
    const lowerQuery = query.toLowerCase();

    const filterEntity = (entity: EntityWithAccounts): EntityWithAccounts | null => {
      const matchesSelf =
        entity.entityName?.toLowerCase().includes(lowerQuery) ||
        entity.entityCode?.toLowerCase().includes(lowerQuery);

      const filteredChildren = entity.childEntities
        ? entity.childEntities
            .map(child => filterEntity(child))
            .filter((child): child is EntityWithAccounts => child !== null)
        : [];

      if (matchesSelf || filteredChildren.length > 0) {
        return {
          ...entity,
          childEntities: filteredChildren,
        };
      }
      return null;
    };

    return entities
      .map(entity => filterEntity(entity))
      .filter((entity): entity is EntityWithAccounts => entity !== null);
  }, []);

  const filteredHierarchy = useMemo(() => {
    return filterHierarchy(entityHierarchy, searchQuery);
  }, [entityHierarchy, searchQuery, filterHierarchy]);

  // Filter flat entities by search (for pyramid view)
  const filteredEntities = useMemo(() => {
    if (!searchQuery) return entityGroups;
    const query = searchQuery.toLowerCase();
    return entityGroups.filter(e =>
      e.entityName.toLowerCase().includes(query) ||
      e.entityCode.toLowerCase().includes(query)
    );
  }, [entityGroups, searchQuery]);

  // Calculate totals from hierarchy tree
  const totals = useMemo(() => {
    const countEntities = (entities: EntityWithAccounts[]): number => {
      return entities.reduce((sum, e) => sum + 1 + (e.childEntities ? countEntities(e.childEntities) : 0), 0);
    };

    const countTreasuryCenters = (entities: EntityWithAccounts[]): number => {
      return entities.reduce((sum, e) =>
        sum + (e.isTreasuryCenter ? 1 : 0) + (e.childEntities ? countTreasuryCenters(e.childEntities) : 0), 0);
    };

    // Only sum from root entities (they have rolledUpBalance which includes children)
    const totalBalance = entityHierarchy.reduce((sum, e) => sum + e.rolledUpBalanceInBase, 0);
    const totalAccounts = entityHierarchy.reduce((sum, e) => sum + e.accountCount, 0);

    return {
      entities: countEntities(entityHierarchy),
      accounts: totalAccounts,
      balance: totalBalance,
      treasuryCenters: countTreasuryCenters(entityHierarchy),
    };
  }, [entityHierarchy]);

  // Trigger refresh
  const handleRefresh = async () => {
    try {
      setRefreshing(true);
      await fetch('/api/v1/treasury/aggregation/trigger-scheduled', { method: 'POST' });
      toast.success('Balance refresh triggered');
      setTimeout(() => loadHierarchy(true), 1000);
    } catch (err) {
      toast.error('Failed to trigger refresh');
      setRefreshing(false);
    }
  };

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. */}
      <PageHeader
        title="Entity Balance Tree"
        description="Drill into the legal-entity hierarchy with FX-converted balances at every node. Switch reporting currency to see the same tree in different denominations."
      />

      {/* Selector Bar */}
      <SelectorBar
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={setSelectedCorporateId}
        onProgramChange={setSelectedProgramId}
        reportingCurrency={reportingCurrency}
        onCurrencyChange={setReportingCurrency}
        loading={loading}
        onRefresh={handleRefresh}
        refreshing={refreshing}
      />

      {/* Header Actions */}
      <div className="flex items-center justify-between animate-fade-in" style={{ animationDelay: '0.1s' }}>
        <div className="flex items-center gap-3">
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
            <input
              type="text"
              placeholder="Search entities..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className={cn(
                'w-64 h-10 pl-10 pr-4 rounded-xl border border-neutral-200 dark:border-primary-800',
                'bg-white text-sm placeholder:text-neutral-400 dark:bg-primary-900',
                'focus:outline-none focus:border-primary-300 focus:ring-2 focus:ring-primary-500/10',
                'transition-all duration-200'
              )}
            />
          </div>
        </div>

        <div className="flex items-center gap-2">
          <Button variant="ghost" size="sm" onClick={expandAll}>
            <Maximize2 className="w-4 h-4 mr-1" />
            Expand
          </Button>
          <Button variant="ghost" size="sm" onClick={collapseAll}>
            <Minimize2 className="w-4 h-4 mr-1" />
            Collapse
          </Button>

          <div className="flex items-center gap-1 bg-neutral-100 rounded-xl p-1 ml-2 dark:bg-primary-800">
            <button
              onClick={() => setViewMode('hierarchy')}
              title="Hierarchy View"
              className={cn(
                'px-3 py-1.5 rounded-lg text-sm font-medium transition-all duration-200 flex items-center gap-1',
                viewMode === 'hierarchy'
                  ? 'bg-white shadow-sm text-primary-700 dark:bg-primary-900 dark:text-neutral-200'
                  : 'text-neutral-600 hover:text-primary-600 dark:text-neutral-300'
              )}
            >
              <TreeDeciduous className="w-4 h-4" />
            </button>
            <button
              onClick={() => setViewMode('flat')}
              title="Flat View"
              className={cn(
                'px-3 py-1.5 rounded-lg text-sm font-medium transition-all duration-200 flex items-center gap-1',
                viewMode === 'flat'
                  ? 'bg-white shadow-sm text-primary-700 dark:bg-primary-900 dark:text-neutral-200'
                  : 'text-neutral-600 hover:text-primary-600 dark:text-neutral-300'
              )}
            >
              <LayoutGrid className="w-4 h-4" />
            </button>
            <button
              onClick={() => setViewMode('pyramid')}
              title="Pyramid View"
              className={cn(
                'px-3 py-1.5 rounded-lg text-sm font-medium transition-all duration-200 flex items-center gap-1',
                viewMode === 'pyramid'
                  ? 'bg-white shadow-sm text-primary-700 dark:bg-primary-900 dark:text-neutral-200'
                  : 'text-neutral-600 hover:text-primary-600 dark:text-neutral-300'
              )}
            >
              <Layers className="w-4 h-4" />
            </button>
          </div>
        </div>
      </div>

      {/* Stats Grid - Dashboard-style */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 lg:gap-6">
        <StatCard
          title="Total Balance"
          value={formatCurrency(totals.balance, reportingCurrency)}
          subtitle={`In ${reportingCurrency}`}
          icon={<DollarSign className="w-6 h-6 text-primary-600 dark:text-primary-200" />}
          iconBg="bg-primary-100 dark:bg-primary-700"
          loading={loading}
          delay={0.1}
        />
        <StatCard
          title="Entities"
          value={totals.entities}
          subtitle="Legal entities"
          icon={<Building2 className="w-6 h-6 text-success-600 dark:text-success-300" />}
          iconBg="bg-success-100 dark:bg-success-500/20"
          loading={loading}
          delay={0.15}
        />
        <StatCard
          title="Accounts"
          value={totals.accounts}
          subtitle="Virtual accounts"
          icon={<Wallet className="w-6 h-6 text-info-600 dark:text-info-300" />}
          iconBg="bg-info-100 dark:bg-info-500/20"
          loading={loading}
          delay={0.2}
        />
        <StatCard
          title="Treasury Centers"
          value={totals.treasuryCenters}
          subtitle="IHB enabled"
          icon={<Crown className="w-6 h-6 text-warning-600 dark:text-warning-300" />}
          iconBg="bg-warning-100 dark:bg-warning-500/20"
          loading={loading}
          delay={0.25}
        />
      </div>

      {/* Error Banner */}
      {error && (
        <div className="bg-error-50 border border-error-200 rounded-2xl p-4 flex items-center justify-between animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3">
            <AlertCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <span className="text-error-800 font-medium dark:text-error-300">{error}</span>
          </div>
          <Button variant="secondary" size="sm" onClick={() => loadHierarchy()}>
            Retry
          </Button>
        </div>
      )}

      {/* Loading State */}
      {loading && (
        <div className="flex items-center justify-center py-20 animate-fade-in">
          <div className="text-center">
            <Loader2 className="w-10 h-10 animate-spin text-primary-600 mx-auto dark:text-primary-200" />
            <p className="text-neutral-500 mt-4 font-medium dark:text-neutral-400">Loading hierarchy...</p>
          </div>
        </div>
      )}

      {/* Main Content */}
      {!loading && !error && (
        <>
          {viewMode === 'pyramid' ? (
            <Card className="p-6 animate-fade-in" style={{ animationDelay: '0.3s' }}>
              <PyramidView
                entityGroups={filteredEntities}
                reportingCurrency={reportingCurrency}
                onEntityClick={(entityId) => {
                  toggleEntity(entityId);
                  setViewMode('hierarchy');
                }}
              />
            </Card>
          ) : viewMode === 'hierarchy' ? (
            /* Hierarchical Tree View */
            <Card className="overflow-hidden animate-fade-in" style={{ animationDelay: '0.3s' }}>
              <div className="p-4 border-b border-neutral-100 bg-gradient-to-r from-neutral-50 via-white to-neutral-50 dark:border-primary-800/60 dark:from-primary-900 dark:via-primary-900 dark:to-primary-900">
                <div className="flex items-center gap-3">
                  <div className="p-2 rounded-xl bg-primary-100 dark:bg-primary-700">
                    <TreeDeciduous className="w-5 h-5 text-primary-600 dark:text-primary-200" />
                  </div>
                  <div>
                    <h3 className="font-semibold text-neutral-900 dark:text-neutral-50">Legal Entity Hierarchy</h3>
                    <p className="text-xs text-neutral-500 dark:text-neutral-400">
                      Showing balances organized by parent-child entity relationships
                    </p>
                  </div>
                </div>
              </div>

              <div className="divide-y divide-neutral-100 dark:divide-primary-800/60">
                {filteredHierarchy.map(entity => (
                  <EntityHierarchyNode
                    key={entity.id}
                    entity={entity}
                    depth={0}
                    expandedEntities={expandedEntities}
                    expandedSections={expandedSections}
                    onToggleEntity={toggleEntity}
                    onToggleSection={toggleSection}
                    reportingCurrency={reportingCurrency}
                    onViewAccount={(node) => setSelectedAccount(node)}
                  />
                ))}
              </div>

              {filteredHierarchy.length === 0 && (
                <div className="text-center py-20">
                  <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                    <TreeDeciduous className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                  </div>
                  <p className="text-neutral-600 font-medium dark:text-neutral-300">No entities found</p>
                  <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Try adjusting your search or filters</p>
                </div>
              )}
            </Card>
          ) : (
            /* Flat Card View */
            <div className="space-y-4">
              {filteredEntities.map((entity, index) => (
                <EntityCard
                  key={entity.entityId}
                  entity={entity}
                  isExpanded={expandedEntities.has(entity.entityId)}
                  onToggle={() => toggleEntity(entity.entityId)}
                  expandedSections={expandedSections}
                  onToggleSection={toggleSection}
                  reportingCurrency={reportingCurrency}
                  onViewAccount={(node) => setSelectedAccount(node)}
                  delay={0.3 + index * 0.05}
                />
              ))}

              {filteredEntities.length === 0 && (
                <div className="text-center py-20 animate-fade-in">
                  <div className="w-16 h-16 rounded-2xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                    <Wallet className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
                  </div>
                  <p className="text-neutral-600 font-medium dark:text-neutral-300">No entities found</p>
                  <p className="text-sm text-neutral-400 mt-1 dark:text-neutral-500">Try adjusting your search or filters</p>
                </div>
              )}
            </div>
          )}
        </>
      )}

      {/* Account Detail Modal */}
      {selectedAccount && (
        <Modal
          isOpen={!!selectedAccount}
          onClose={() => setSelectedAccount(null)}
          title="Account Details"
          size="md"
        >
            <div className="space-y-6">
              <div className="grid grid-cols-2 gap-4">
                <div className="p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                  <p className="text-xs text-neutral-500 font-medium dark:text-neutral-400">Account Name</p>
                  <p className="font-semibold text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.name}</p>
                </div>
                <div className="p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                  <p className="text-xs text-neutral-500 font-medium dark:text-neutral-400">Account Number</p>
                  <p className="font-mono text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.accountNumber || 'N/A'}</p>
                </div>
                <div className="p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                  <p className="text-xs text-neutral-500 font-medium dark:text-neutral-400">Currency</p>
                  <p className="font-semibold text-neutral-900 mt-1 dark:text-neutral-50">{selectedAccount.currencyCode}</p>
                </div>
                <div className="p-3 bg-neutral-50 rounded-xl dark:bg-primary-950">
                  <p className="text-xs text-neutral-500 font-medium dark:text-neutral-400">Category</p>
                  <Badge className="mt-1">{selectedAccount.accountCategory || selectedAccount.type}</Badge>
                </div>
              </div>

              <div className="p-4 bg-success-50 rounded-xl border border-success-100 dark:bg-success-500/10 dark:border-success-500/30">
                <p className="text-sm text-neutral-500 font-medium dark:text-neutral-400">Current Balance</p>
                <p className={cn(
                  'stat-value mt-1',
                  (selectedAccount.localBalance || 0) >= 0 ? 'text-success-600 dark:text-success-300' : 'text-error-600 dark:text-error-300'
                )}>
                  {formatCurrency(selectedAccount.localBalance || 0, selectedAccount.currencyCode)}
                </p>
              </div>

              {selectedAccount.mirrorAccountType && selectedAccount.mirrorAccountType !== 'NONE' && (
                <div className="pt-4 border-t border-neutral-100 dark:border-primary-800/60">
                  <p className="text-sm text-neutral-500 font-medium mb-2 dark:text-neutral-400">Mirror Account Type</p>
                  <Badge variant="info" size="md">{selectedAccount.mirrorAccountType}</Badge>
                </div>
              )}
            </div>
        </Modal>
      )}
    </Page>
  );
};

export default EntityBalanceTreePage;
