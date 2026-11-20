import React, { useState, useEffect, useCallback } from 'react';
import { Building2, Wallet, Plus, Loader2, X, XCircle, ChevronRight } from 'lucide-react';
import toast from 'react-hot-toast';
import { Card, Button, Badge, Input, Select, StatusIconBadge, Checkbox, DataTable, RowActions } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal, Tabs } from '../components/ui/enhanced';
import { formatCurrency, formatDate } from '../utils';
import { corporatesApi } from '../services/api';
import { usePageHeaderActions } from '../context/usePageHeader';
import { useNavigation } from '../hooks/useNavigation';
import { Page } from '../components/layout/Page';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { CoboEnrolmentsPanel } from '../components/treasury/CoboEnrolmentsPanel';
import { PoboEnrolmentsPanel } from '../components/treasury/PoboEnrolmentsPanel';
import { IhbChangesPanel, IhbInterestPanel, IhbMovementsPanel, IhbPositionPanel } from '../components/treasury/IhbBookPanels';

// ============================================================================
// API CONFIGURATION - UNIFIED ENDPOINTS
// ============================================================================

const API_BASE = '/api/v1/ihb';

interface ApiResponse<T> { 
  success: boolean; 
  data: T; 
  message?: string; 
}

// ============================================================================
// TYPES - Aligned with Backend DTOs
// ============================================================================

interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
}

interface IhbEntity {
  id: string;
  entityCode: string;
  entityName: string;
  entityType: 'PARENT' | 'SUBSIDIARY' | 'BRANCH' | 'DIVISION' | 'JOINT_VENTURE';
  corporateId: string;
  ihbEnabled: boolean;
  creditLimit: number;
  currentExposure: number;
  availableLimit: number;
  lendingRateSpread: number;
  borrowingRateSpread: number;
  /** The spreads are built-in defaults nobody set. */
  spreadsDefault?: boolean;
  canLend: boolean;
  canBorrow: boolean;
  settlementVaId?: string;
  ihbCurrency: string;
  utilizationPercent: number;
  limitWarning: boolean;
  limitBreached: boolean;
}

interface IhbCurrentAccount {
  // Identity
  accountId: string;
  accountNumber: string;
  accountName: string;
  currencyCode: string;

  // IHB Participation Flag (NEW - TRANSACTION VA with this flag)
  ihbParticipant: boolean;
  ihbEnabledAt?: string;
  ihbEnabledBy?: string;
  accountCategory: string;  // Now TRANSACTION instead of INTERCOMPANY

  // Participant Info
  participantEntityId: string;
  participantEntityCode?: string;
  participantEntityName?: string;

  // Balances
  currentBalance: number;
  availableBalance: number;
  positionType: 'CREDIT' | 'DEBIT' | 'NEUTRAL';

  // Credit/Overdraft
  creditLimit: number;
  creditLimitUsed: number;
  creditLimitAvailable: number;

  // Accrued Interest
  accruedCreditInterest: number;
  accruedDebitInterest: number;
  netAccruedInterest: number;

  // Interest Rates: what the interest run charges (the built-in default where none is configured)
  creditRate: number;
  debitRate: number;
  penaltyRate: number;
  defaultRates?: boolean;

  // IHB Sweep Configuration
  ihbSweepEnabled?: boolean;
  targetCashBalance?: number;
  ihbSweepFrequency?: string;

  // Status
  status: string;

  // Treasury Link (parentAccountId is preferred, treasuryPoolVaId is deprecated)
  parentAccountId?: string;       // Links to Treasury's Settlement VA
  treasuryPoolVaId?: string;      // @deprecated - use parentAccountId
  treasuryEntityCode?: string;    // Treasury center entity code
}

// ============================================================================
// IHB UNIFIED API SERVICE
// ============================================================================

const ihbUnifiedApi = {
  // Entity Management
  getAllEntities: () =>
    fetch(`${API_BASE}/entities`).then(r => r.json()) as Promise<ApiResponse<IhbEntity[]>>,

  getEntitiesByCorporate: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/entities`).then(r => r.json()) as Promise<ApiResponse<IhbEntity[]>>,

  getEntityById: (entityId: string) =>
    fetch(`${API_BASE}/entities/${entityId}`).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  enableIhb: (entityId: string, data: { creditLimit: number; ihbCurrency: string; canLend?: boolean; canBorrow?: boolean; lendingRateSpread?: number; borrowingRateSpread?: number }) =>
    fetch(`${API_BASE}/entities/${entityId}/enable`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  updateSettings: (entityId: string, data: any) =>
    fetch(`${API_BASE}/entities/${entityId}/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbEntity>>,

  disableIhb: (entityId: string) =>
    fetch(`${API_BASE}/entities/${entityId}/disable`, { method: 'POST' }).then(r => r.json()) as Promise<ApiResponse<void>>,

  // IHB Current Accounts (Creates TRANSACTION VA with ihbParticipant=true)
  createCurrentAccount: (data: {
    participantEntityId: string;
    currencyCode: string;
    programId?: string;
    creditRate?: number;
    debitRate?: number;
    penaltyRate?: number;
    // Interest Configuration
    interestConfigId?: string;
    // IHB Sweep Configuration
    ihbSweepEnabled?: boolean;
    targetCashBalance?: number;
    ihbSweepFrequency?: string;
    // VA Name override
    vaName?: string;
  }) =>
    fetch(`${API_BASE}/current-account`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<any>>,

  getCurrentAccountsByCorporate: (corporateId: string) =>
    fetch(`${API_BASE}/corporate/${corporateId}/current-accounts`).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount[]>>,

  getCurrentAccountPosition: (accountId: string) =>
    fetch(`${API_BASE}/current-account/${accountId}/position`).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  depositToCurrentAccount: (accountId: string, data: { amount: number; description?: string }) =>
    fetch(`${API_BASE}/current-account/${accountId}/deposit`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  withdrawFromCurrentAccount: (accountId: string, data: { amount: number; description?: string }) =>
    fetch(`${API_BASE}/current-account/${accountId}/withdraw`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(data)
    }).then(r => r.json()) as Promise<ApiResponse<IhbCurrentAccount>>,

  calculateCurrentAccountInterest: () =>
    fetch(`${API_BASE}/current-accounts/calculate-interest`, { method: 'POST' }).then(r => r.json()),

  postCurrentAccountInterest: () =>
    fetch(`${API_BASE}/current-accounts/post-interest`, { method: 'POST' }).then(r => r.json()),
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

/** One row of the Set-up tab: an entity and one of its IHB current accounts (or none yet). */
interface SetupRow {
  key: string;
  entity?: IhbEntity;
  account?: IhbCurrentAccount;
}

const roleLabel = (e?: IhbEntity) =>
  !e ? '-' : e.canLend && !e.canBorrow ? 'Treasury centre' : e.canLend && e.canBorrow ? 'Lender and borrower' : e.canBorrow ? 'Borrower' : 'Lender';

/**
 * Entities and their IHB current accounts in one list, set-up facts only. Balances, limits and interest are on the
 * Position and Interest tabs; spreads are not shown because the interest run does not apply them (spec D10).
 */
const setupRows = (entities: IhbEntity[], accounts: IhbCurrentAccount[]): SetupRow[] => {
  const rows: SetupRow[] = entities.flatMap((entity) => {
    const own = accounts.filter((a) => a.participantEntityId === entity.id);
    return own.length ? own.map((account) => ({ key: account.accountId, entity, account })) : [{ key: entity.id, entity }];
  });
  const known = new Set(entities.map((e) => e.id));
  return rows.concat(accounts.filter((a) => !known.has(a.participantEntityId)).map((account) => ({ key: account.accountId, account })));
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const InHouseBankPage: React.FC = () => {
  // State
  // Opened from Intercompany's in-house bank row: that corporate, on Position (the default tab).
  const { params: navParams } = useNavigation();
  const [activeTab, setActiveTab] = useState<string>('position');
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [entities, setEntities] = useState<IhbEntity[]>([]);
  const [currentAccounts, setCurrentAccounts] = useState<IhbCurrentAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingSelectors, setLoadingSelectors] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Modals
  const [showCurrentAccountModal, setShowCurrentAccountModal] = useState(false);

  // Form state - Enhanced for backend alignment
  const [currentAccountForm, setCurrentAccountForm] = useState({
    participantEntityId: '',
    currencyCode: 'AED',
    creditRate: '',
    debitRate: '',
    penaltyRate: '',
    // New fields for TRANSACTION VA with ihbParticipant=true
    ihbSweepEnabled: false,
    targetCashBalance: '',
    ihbSweepFrequency: 'DAILY',
    vaName: '',
  });

  // Load corporates on mount
  useEffect(() => {
    loadCorporates();
  }, []);

  // Opened with a corporate (Intercompany's in-house bank row): show that one once it is in the list.
  useEffect(() => {
    if (navParams.corporateId && corporates.some(c => c.id === navParams.corporateId)) {
      setSelectedCorporateId(navParams.corporateId);
    }
  }, [navParams.corporateId, corporates]);

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;

    setLoading(true);
    setError(null);

    try {
      const [entitiesRes, currentAccountsRes] = await Promise.all([
        ihbUnifiedApi.getEntitiesByCorporate(selectedCorporateId),
        ihbUnifiedApi.getCurrentAccountsByCorporate(selectedCorporateId).catch(() => ({ data: [] })),
      ]);

      setEntities(entitiesRes?.data || []);
      setCurrentAccounts(currentAccountsRes?.data || []);
    } catch (err) {
      console.error('Failed to load IHB data:', err);
      setError('Failed to load IHB data. The unified API may not be deployed yet.');
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId]);

  // Load data when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    } else {
      // Clear data when no corporate selected
      setEntities([]);
      setCurrentAccounts([]);
    }
  }, [selectedCorporateId, loadData]);

  const loadCorporates = async () => {
    setLoadingSelectors(true);
    try {
      const corporatesRes = await corporatesApi.getAll();
      const corporateData = corporatesRes?.data || corporatesRes;
      const corporateList = Array.isArray(corporateData) ? corporateData : [];
      setCorporates(corporateList);

      // Auto-select first corporate (a corporate named by the link wins, below)
      if (corporateList.length > 0) {
        setSelectedCorporateId(corporateList[0].id);
      }
    } catch (err) {
      console.error('Failed to load corporates:', err);
      setError('Failed to load corporates');
    } finally {
      setLoadingSelectors(false);
    }
  };

  // IHB toolbar
  usePageHeaderActions(
    () => (
      <Button
        size="sm"
        leftIcon={<Wallet className="w-4 h-4" />}
        onClick={() => setShowCurrentAccountModal(true)}
        disabled={!selectedCorporateId}
        className="text-cat-2 dark:text-cat-2-fg border-cat-2/20 hover:bg-cat-2-soft dark:border-cat-2/30 dark:hover:bg-cat-2/15"
      >
        New Current Account
      </Button>
    ),
    [selectedCorporateId]
  );

  // Handle creating IHB Current Account (TRANSACTION VA with ihbParticipant=true)
  const handleCreateCurrentAccount = async () => {
    if (!currentAccountForm.participantEntityId) {
      toast.error('Please select a participant entity');
      return;
    }

    try {
      const result = await ihbUnifiedApi.createCurrentAccount({
        participantEntityId: currentAccountForm.participantEntityId,
        currencyCode: currentAccountForm.currencyCode,
        creditRate: currentAccountForm.creditRate ? parseFloat(currentAccountForm.creditRate) : undefined,
        debitRate: currentAccountForm.debitRate ? parseFloat(currentAccountForm.debitRate) : undefined,
        penaltyRate: currentAccountForm.penaltyRate ? parseFloat(currentAccountForm.penaltyRate) : undefined,
        // New IHB fields
        ihbSweepEnabled: currentAccountForm.ihbSweepEnabled,
        targetCashBalance: currentAccountForm.targetCashBalance ? parseFloat(currentAccountForm.targetCashBalance) : undefined,
        ihbSweepFrequency: currentAccountForm.ihbSweepFrequency,
        vaName: currentAccountForm.vaName || undefined,
      });

      if (result?.success) {
        setShowCurrentAccountModal(false);
        setCurrentAccountForm({
          participantEntityId: '', currencyCode: 'AED',
          creditRate: '', debitRate: '', penaltyRate: '',
          ihbSweepEnabled: false, targetCashBalance: '', ihbSweepFrequency: 'DAILY', vaName: '',
        });
        await loadData();
        toast.success('Current account opened at the in-house bank');
      } else {
        toast.error(result?.message || 'Failed to create current account');
      }
    } catch (err) {
      console.error('Failed to create current account:', err);
      toast.error('Failed to create current account');
    }
  };

  // Handle quick current account creation from entity card
  const handleQuickCurrentAccount = (entity: IhbEntity) => {
    setCurrentAccountForm(prev => ({
      ...prev,
      participantEntityId: entity.id,
      currencyCode: entity.ihbCurrency || 'AED',
      creditLimit: entity.creditLimit?.toString() || '',
    }));
    setShowCurrentAccountModal(true);
  };

  // Calculated values
  const borrowers = entities.filter(e => e.canBorrow);

  const tabs = [
    { id: 'position', label: 'Position' },
    { id: 'movements', label: 'Movements' },
    { id: 'interest', label: 'Interest' },
    { id: 'changes', label: 'Limit and rate changes' },
    { id: 'setup', label: 'Set-up' },
    { id: 'cobo', label: 'Collections on behalf (COBO)' },
    { id: 'pobo', label: 'Payments on behalf (POBO)' },
  ];

  if (loadingSelectors && !corporates.length) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* Corporate only: IHB accounts take their program from the parent they hang under, not from a pick here. */}
      <ScopeSelector
        mode="corporate-only"
        corporates={corporates}
        selectedCorporateId={selectedCorporateId}
        onCorporateChange={setSelectedCorporateId}
        loading={loadingSelectors}
      />

      {/* Error Banner */}
      {error && (
        <Card padding="sm" className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center gap-3">
            <XCircle className="w-5 h-5 text-error-600 dark:text-error-300" />
            <p className="text-body-sm text-error-700 flex-1 dark:text-error-300">{error}</p>
            <button onClick={() => setError(null)} className="text-error-600 hover:text-error-800 dark:text-error-300">
              <X className="w-4 h-4" />
            </button>
          </div>
        </Card>
      )}

      {/* No Corporate Selected State */}
      {!selectedCorporateId && (
        <Card padding="md" className="bg-gradient-to-r from-neutral-50 via-white to-neutral-50 border-edge animate-fade-in dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" style={{ animationDelay: '0.15s' }}>
          <div className="flex flex-col items-center justify-center py-8 text-center">
            <StatusIconBadge tone="warning" icon={Building2} size="xl" className="mb-4" />
            <p className="section-title">Select a Corporate</p>
            <p className="body-sm mt-1 max-w-md">
              Choose a corporate from the selector above to view and manage its In-House Bank entities and current accounts.
            </p>
          </div>
        </Card>
      )}

      {/* Content when corporate is selected */}
      {selectedCorporateId && (
        <>
      {/* Tabs */}
      <Card padding="none" className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
        <div className="p-2 border-b border-edge-subtle overflow-x-auto whitespace-nowrap">
          <Tabs tabs={tabs} activeTab={activeTab} onChange={setActiveTab} size="sm" />
        </div>

        <div className="p-6">
          {activeTab === 'position' && <IhbPositionPanel corporateId={selectedCorporateId} />}
          {activeTab === 'movements' && <IhbMovementsPanel corporateId={selectedCorporateId} entities={entities} accounts={currentAccounts} />}
          {activeTab === 'interest' && <IhbInterestPanel corporateId={selectedCorporateId} accounts={currentAccounts} />}
          {activeTab === 'changes' && <IhbChangesPanel corporateId={selectedCorporateId} />}

          {activeTab === 'setup' && (
            <DataTable
              hairline
              className="p-0"
              data={setupRows(entities, currentAccounts)}
              loading={loading}
              keyExtractor={(r) => r.key}
              emptyIcon={<Building2 className="w-12 h-12" />}
              emptyTitle="No in-house bank entities"
              emptyDescription="Enable the in-house bank on an entity under Legal Entities, then open a current account for it."
              emptyAction={<Button onClick={() => setShowCurrentAccountModal(true)} leftIcon={<Plus className="w-4 h-4" />}>New Current Account</Button>}
              columns={[
                { key: 'entity', header: 'Entity', render: (_, r) => (
                  <div className="flex flex-col">
                    <span className="body-strong">{r.entity?.entityName ?? r.account?.participantEntityName ?? r.account?.accountName}</span>
                    <span className="caption">
                      {r.entity ? `${r.entity.entityCode} · ${r.entity.entityType.charAt(0) + r.entity.entityType.slice(1).toLowerCase().replace('_', ' ')}` : r.account?.participantEntityCode}
                    </span>
                  </div>
                ) },
                { key: 'role', header: 'Role', render: (_, r) => <Badge variant={r.entity?.canLend && !r.entity.canBorrow ? 'warning' : 'neutral'} size="sm">{roleLabel(r.entity)}</Badge> },
                { key: 'currency', header: 'Currency', render: (_, r) => r.account?.currencyCode ?? r.entity?.ihbCurrency ?? '-' },
                { key: 'account', header: 'Current account', render: (_, r) => r.account ? (
                  <div className="flex flex-col">
                    <span className="font-mono text-body-sm">{r.account.accountNumber}</span>
                    {r.account.accountName && <span className="caption">{r.account.accountName}</span>}
                  </div>
                ) : <span className="caption">None</span> },
                { key: 'sweep', header: 'Sweep to treasury', render: (_, r) => !r.account?.ihbSweepEnabled ? <span className="caption">Off</span> : (
                  <span className="text-body-sm">
                    {(r.account.ihbSweepFrequency || 'DAILY').charAt(0) + (r.account.ihbSweepFrequency || 'DAILY').slice(1).toLowerCase().replace('_', '-')}
                    {r.account.targetCashBalance ? `, keep ${formatCurrency(r.account.targetCashBalance, r.account.currencyCode)}` : ', sweep all'}
                  </span>
                ) },
                { key: 'opened', header: 'Opened', render: (_, r) => r.account?.ihbEnabledAt ? formatDate(r.account.ihbEnabledAt) : '-' },
                { key: 'actions', header: '', width: '4rem', minWidth: 64, render: (_, r) => r.entity && (
                  <RowActions label={`${r.entity.entityName} actions`} actions={[
                    { label: 'Open current account', icon: <Wallet className="w-4 h-4" />, onClick: () => handleQuickCurrentAccount(r.entity!), hidden: !r.entity.canBorrow || !!r.entity.settlementVaId },
                  ]} />
                ) },
              ]}
            />
          )}

          {activeTab === 'cobo' && (
            <CoboEnrolmentsPanel corporateId={selectedCorporateId} currentAccounts={currentAccounts} />
          )}

          {activeTab === 'pobo' && (
            <PoboEnrolmentsPanel corporateId={selectedCorporateId} currentAccounts={currentAccounts} />
          )}

        </div>
      </Card>
        </>
      )}

      {/* Create Current Account Modal */}
      <Modal
        isOpen={showCurrentAccountModal}
        onClose={() => setShowCurrentAccountModal(false)}
        title="Open a current account at the in-house bank"
        size="lg"
      >
        <div className="space-y-5">
          {/* Participant Selection */}
          <div>
            <div>
              <label className="field-label block mb-1">Entity *</label>
              <Select
                value={currentAccountForm.participantEntityId}
                onChange={(e) => {
                  const entity = entities.find(en => en.id === e.target.value);
                  setCurrentAccountForm(prev => ({
                    ...prev,
                    participantEntityId: e.target.value,
                    currencyCode: entity?.ihbCurrency || prev.currencyCode,
                  }));
                }}
              >
                <option value="">Select an entity that may borrow…</option>
                {borrowers.map(e => (
                  <option key={e.id} value={e.id}>
                    {e.entityName} ({e.entityCode}) {e.settlementVaId ? '- Has Account' : ''}
                  </option>
                ))}
              </Select>
            </div>
          </div>

          {/* Account Details */}
          <div>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="field-label block mb-1">Currency</label>
                <CurrencyPicker
                  value={currentAccountForm.currencyCode}
                  onChange={(c) => setCurrentAccountForm({ ...currentAccountForm, currencyCode: c })}
                  extra={['SAR']}
                />
              </div>
              <div>
                <label className="field-label block mb-1">Credit limit (set on the entity)</label>
                {(() => {
                  const participant = entities.find(en => en.id === currentAccountForm.participantEntityId);
                  return (
                    <p className="body-sm py-2">
                      {participant
                        ? formatCurrency(participant.creditLimit ?? 0, participant.ihbCurrency || currentAccountForm.currencyCode)
                        : '—'}
                    </p>
                  );
                })()}
              </div>
            </div>
          </div>

          {/* Rate overrides, sweep and account name: optional, folded under Advanced. */}
          <details className="group rounded-lg border border-edge">
            <summary className="p-3 cursor-pointer list-none flex items-center justify-between">
              <span className="field-label">Advanced: rate overrides, sweep, account name</span>
              <ChevronRight className="w-4 h-4 text-neutral-400 transition-transform group-open:rotate-90" />
            </summary>
            <div className="px-3 pb-3 space-y-5">
              <div>
                <div className="grid grid-cols-3 gap-4">
                  <div>
                    <label className="field-label block mb-1">Credit Rate (%)</label>
                    <Input
                      type="number"
                      step="0.01"
                      value={currentAccountForm.creditRate}
                      onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, creditRate: e.target.value })}
                      placeholder="Treasury default"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Debit Rate (%)</label>
                    <Input
                      type="number"
                      step="0.01"
                      value={currentAccountForm.debitRate}
                      onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, debitRate: e.target.value })}
                      placeholder="Treasury default"
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Penalty Rate (%)</label>
                    <Input
                      type="number"
                      step="0.01"
                      value={currentAccountForm.penaltyRate}
                      onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, penaltyRate: e.target.value })}
                      placeholder="Optional"
                    />
                  </div>
                </div>
                <p className="caption mt-2">
                  Rate overrides apply only where neither the entity nor treasury has a rate configuration in this currency.
                </p>
              </div>

              {/* IHB Sweep Configuration */}
              <div>
                <div className="grid grid-cols-3 gap-4">
                  <div>
                    <Checkbox size="sm" label="Sweep surplus to treasury" checked={currentAccountForm.ihbSweepEnabled} onChange={(checked) => setCurrentAccountForm({ ...currentAccountForm, ihbSweepEnabled: checked })} className="field-label" />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Target Balance</label>
                    <Input
                      type="number"
                      value={currentAccountForm.targetCashBalance}
                      onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, targetCashBalance: e.target.value })}
                      placeholder="0 = sweep all"
                      disabled={!currentAccountForm.ihbSweepEnabled}
                    />
                  </div>
                  <div>
                    <label className="field-label block mb-1">Sweep Frequency</label>
                    <Select
                      value={currentAccountForm.ihbSweepFrequency}
                      onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, ihbSweepFrequency: e.target.value })}
                      disabled={!currentAccountForm.ihbSweepEnabled}
                    >
                      <option value="DAILY">Daily</option>
                      <option value="REAL_TIME">Real-Time</option>
                      <option value="WEEKLY">Weekly</option>
                    </Select>
                  </div>
                </div>
              </div>

              {/* Custom VA Name (Optional) */}
              <div>
                <label className="field-label block mb-1">
                  Account Name (Optional)
                </label>
                <Input
                  value={currentAccountForm.vaName}
                  onChange={(e) => setCurrentAccountForm({ ...currentAccountForm, vaName: e.target.value })}
                  placeholder="Default: IHB Current - {Entity Name}"
                />
              </div>
            </div>
          </details>

          {/* Actions */}
          <div className="flex gap-3 pt-4 border-t">
            <Button variant="outline" className="flex-1" onClick={() => setShowCurrentAccountModal(false)}>Cancel</Button>
            <Button
              className="flex-1 bg-cat-2 hover:bg-cat-2/90"
              onClick={handleCreateCurrentAccount}
              disabled={!currentAccountForm.participantEntityId}
            >
              <Wallet className="w-4 h-4 mr-1" /> Create Current Account
            </Button>
          </div>
        </div>
      </Modal>
    </Page>
  );
};

export default InHouseBankPage;