// Program page building blocks, split out of ProgramsPage.tsx.
import React, { useState, useEffect } from 'react';
import { Plus, Building2, CreditCard, Wallet, Shield, Banknote, CheckCircle, XCircle, Clock, Copy, ChevronRight, Loader2, Layers, X, PauseCircle, PlayCircle, TrendingUp, Hash, GitBranch, FolderTree, Info, Settings, Pencil } from 'lucide-react';
import { Card, Badge, Button, StatusIconBadge } from '../../components/ui';
import { Modal, Tabs, Alert } from '../../components/ui/enhanced';
import { formatCurrency, formatDate, cn } from '../../utils';
import { HIERARCHY_TEMPLATES } from '../../config/templateHierarchy';
import type { ProgramConfigStep } from './ProgramFormModal';
import { useNavigation } from '../../App';

import { fetchApi, CHARGES_API_BASE, WalletChargesResponse, Program, ProgramDetail, SettlementVa, VibanPool, programApi, treasuryApi, vibanPoolApi, vibanStrategyConfig, statusConfig } from './shared';

// ============================================================================
// DETAIL MODAL
// ============================================================================

interface ProgramDetailModalProps {
  program: Program | null;
  onClose: () => void;
  onEdit: (program: Program) => void;
  onStatusChange: (programId: string, status: string) => void;
  onClone: (program: Program) => void;
  /** Open one settings screen for this program (the same ones as the list's row actions). */
  onConfigure: (program: Program, step: ProgramConfigStep) => void;
}

export const ProgramDetailModal: React.FC<ProgramDetailModalProps> = ({ program, onClose, onEdit, onClone, onConfigure, onStatusChange }) => {
  const [activeTab, setActiveTab] = useState<'overview' | 'hierarchy' | 'viban' | 'wallet' | 'accounts' | 'config' | 'history'>('overview');
  const [detail, setDetail] = useState<ProgramDetail | null>(null);
  const [loading, setLoading] = useState(false);
  // NEW: State for hierarchy tab
  const [settlementVas, setSettlementVas] = useState<SettlementVa[]>([]);
  const [exceptionVas, setExceptionVas] = useState<SettlementVa[]>([]);
  const [hierarchyLoading, setHierarchyLoading] = useState(false);
  // NEW: State for VIBAN pool tab
  const [vibanPool, setVibanPool] = useState<VibanPool | null>(null);
  const [vibanLoading, setVibanLoading] = useState(false);

  // Wallet Charges state
  const [walletCharges, setWalletCharges] = useState<WalletChargesResponse | null>(null);
  const [walletChargesLoading, setWalletChargesLoading] = useState(false);
  const [walletChargesError, setWalletChargesError] = useState(false);

  useEffect(() => {
    if (program) {
      setLoading(true);
      programApi.getDetail(program.id).then(res => {
        if (res.success) setDetail(res.data);
      }).finally(() => setLoading(false));
    }
  }, [program]);

  // NEW: Fetch Settlement VAs when hierarchy tab is active
  useEffect(() => {
    if (program && activeTab === 'hierarchy') {
      setHierarchyLoading(true);
      treasuryApi.getSettlementVas(program.id).then(res => {
        if (res.success && res.data) {
          setSettlementVas(res.data.settlementVas || []);
          setExceptionVas(res.data.exceptionVas || []);
        }
      }).finally(() => setHierarchyLoading(false));
    }
  }, [program, activeTab]);

  // NEW: Fetch VIBAN Pool when viban tab is active
  useEffect(() => {
    if (program && activeTab === 'viban') {
      setVibanLoading(true);
      if (program.defaultVibanPoolId) {
        vibanPoolApi.getById(program.defaultVibanPoolId).then(res => {
          if (res.success && res.data) {
            setVibanPool(res.data);
          }
        }).finally(() => setVibanLoading(false));
      } else {
        vibanPoolApi.getByProgram(program.id).then(res => {
          if (res.success && res.data) {
            setVibanPool(res.data);
          }
        }).finally(() => setVibanLoading(false));
      }
    }
  }, [program, activeTab]);

  // Fetch wallet charges when the Wallet Fees tab is opened. Every program can
  // hold wallets now, so fetching on every modal open would hit this endpoint
  // for treasury and collection programs that never look at it.
  useEffect(() => {
    if (program && activeTab === 'wallet') {
      setWalletChargesLoading(true);
      fetchApi<WalletChargesResponse>(`/programs/${program.id}/wallet`, {}, CHARGES_API_BASE)
        .then(res => {
          // No invented fallback: a failed load shows an error state, never made-up fees.
          setWalletCharges(res.success && res.data ? res.data : null);
          setWalletChargesError(!(res.success && res.data));
        })
        .finally(() => setWalletChargesLoading(false));
    } else {
      setWalletCharges(null);
    }
  }, [program, activeTab]);

  const { navigate } = useNavigation();
  // The program's bank accounts (their shadows): home bank and any others it runs on.
  const [bankAccounts, setBankAccounts] = useState<Array<{ id: string; physicalAccountNumber?: string; bankName?: string; currencyCode: string; bankBalance?: number; linkedPhysicalAccountId?: string }>>([]);
  useEffect(() => {
    setBankAccounts([]);
    if (!program) return;
    fetch(`/api/v1/treasury/shadow-accounts/program/${program.id}`)
      .then(res => res.json())
      .then(data => { if (data.success) setBankAccounts(data.data || []); })
      .catch(console.error);
  }, [program?.id]);

  if (!program) return null;

  // NEW: Check if hierarchy is initialized
  const hasHierarchy = !!program.rootHierarchyNodeId;

  const tabs = [
    { id: 'overview' as const, label: 'Overview' },
    { id: 'hierarchy' as const, label: 'Hierarchy', badge: !hasHierarchy ? 'Setup' : undefined },
    { id: 'viban' as const, label: 'VIBAN Pool' },
    { id: 'wallet' as const, label: 'Wallet Fees' },
    { id: 'accounts' as const, label: 'Virtual Accounts', count: program.virtualAccountCount },
    { id: 'config' as const, label: 'Configuration' },
    { id: 'history' as const, label: 'Activity' },
  ];

  return (
    <Modal isOpen={!!program} onClose={onClose} size="xl" showCloseButton={false}>
      <div className="flex flex-col h-full max-h-[85vh]">
        {/* Header */}
        <div className="flex items-start gap-4 pb-4 border-b border-edge">
          <StatusIconBadge tone="neutral" icon={Layers} size="lg" subtle />
          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-2">
              <h2 className="section-title truncate">{program.programName}</h2>
              <Badge variant={statusConfig[program.status]?.variant}>{statusConfig[program.status]?.label}</Badge>
              {hasHierarchy && <Badge variant="info" size="sm"><GitBranch className="w-3 h-3 mr-1" />Hierarchy</Badge>}
            </div>
            <div className="flex items-center gap-4 mt-1 body-sm">
              <span className="font-mono">{program.programCode}</span>
              <span>•</span>
              <span>{program.currencyCode}</span>
            </div>
            {program.corporateName && (
              <p className="mt-1 body-sm flex items-center gap-1">
                <Building2 className="w-3 h-3" /> {program.corporateName}
              </p>
            )}
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={program.status === 'CLOSED'} title={program.status === 'CLOSED' ? 'Closed programs cannot be edited' : undefined} onClick={() => onEdit(program)} leftIcon={<Pencil className="w-4 h-4" />}>Edit</Button>
            <Button variant="outline" size="sm" aria-label="Close" onClick={onClose}><X className="w-4 h-4" /></Button>
          </div>
        </div>

        {/* Stats Row */}
        <div className="grid grid-cols-4 gap-4 py-4 border-b border-edge">
          <div className="text-center">
            <p className="stat-value-sm">{program.virtualAccountCount}</p>
            <p className="caption">Virtual Accounts</p>
          </div>
          <div className="text-center">
            <p className="stat-value-success">{program.activeVirtualAccountCount}</p>
            <p className="caption">Active VAs</p>
          </div>
          <div className="text-center">
            <p className="stat-value-xs whitespace-nowrap">{formatCurrency(program.totalBalance, program.currencyCode)}</p>
            <p className="caption">Total Balance</p>
          </div>
          <div className="text-center">
            <p className="stat-value-sm">{program.maxVirtualAccounts || '∞'}</p>
            <p className="caption">Max VAs</p>
          </div>
        </div>

        {/* Tabs */}
        <Tabs
          variant="underline"
          size="sm"
          activeTab={activeTab}
          onChange={(id) => setActiveTab(id as typeof activeTab)}
          tabs={tabs.map((tab) => ({
            id: tab.id,
            label: tab.label,
            icon: tab.id === 'hierarchy' ? <GitBranch className="w-4 h-4" /> : tab.id === 'viban' ? <Hash className="w-4 h-4" /> : undefined,
            badge: tab.badge ?? tab.count,
          }))}
        />

        {/* Content */}
        <div className="flex-1 overflow-y-auto py-4">
          {loading && <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-primary-500" /></div>}

          {/* Overview Tab */}
          {!loading && activeTab === 'overview' && (
            <div className="grid grid-cols-2 gap-6">
              <div className="space-y-4">
                <h3 className="body-strong font-semibold">Program Details</h3>
                <Card padding="sm" className="space-y-3">
                  <div className="flex justify-between"><span className="body-sm">Program Code</span><span className="text-body-sm font-mono text-primary-900 dark:text-neutral-50">{program.programCode}</span></div>
                  <div className="flex justify-between"><span className="body-sm">Currency</span><span className="text-body-sm text-primary-900 dark:text-neutral-50">{program.currencyCode}</span></div>
                  <div className="flex justify-between"><span className="body-sm">VA Prefix</span><span className="text-body-sm font-mono text-primary-900 dark:text-neutral-50">{program.vaPrefix || '-'}</span></div>
                  <div className="flex justify-between"><span className="body-sm">Created</span><span className="text-body-sm text-primary-900 dark:text-neutral-50">{formatDate(program.createdAt)}</span></div>
                  {program.effectiveFrom && (
                    <div className="flex justify-between"><span className="body-sm">Effective From</span><span className="text-body-sm text-primary-900 dark:text-neutral-50">{formatDate(program.effectiveFrom)}</span></div>
                  )}
                  {program.effectiveTo && (
                    <div className="flex justify-between"><span className="body-sm">Effective To</span><span className="text-body-sm text-primary-900 dark:text-neutral-50">{formatDate(program.effectiveTo)}</span></div>
                  )}
                </Card>
                {/* Description */}
                {program.description && (
                  <>
                    <h3 className="body-strong font-semibold">Description</h3>
                    <Card padding="sm">
                      <p className="text-body-sm text-neutral-700 dark:text-neutral-200">{program.description}</p>
                    </Card>
                  </>
                )}
                <h3 className="body-strong font-semibold">Bank accounts</h3>
                {detail?.physicalAccount && !bankAccounts.some(a => a.linkedPhysicalAccountId === detail.physicalAccount!.id) && (
                  <Alert variant="warning">
                    <p className="body-strong">
                      Backing account {detail.physicalAccount.accountNumber} ({detail.physicalAccount.bankName}) is not one of this program's bank accounts
                    </p>
                    <p className="body-sm mt-1">
                      {detail.physicalAccount.heldByProgramName
                        ? `It belongs to ${detail.physicalAccount.heldByProgramName}${detail.physicalAccount.heldByProgramCode ? ` (${detail.physicalAccount.heldByProgramCode})` : ''}.`
                        : 'Nothing mirrors it.'}
                      {' '}Payments from this program's accounts are refused until it has its own {program.currencyCode} bank account.
                    </p>
                  </Alert>
                )}
                {bankAccounts.length === 0 ? (
                  <Card padding="sm"><p className="body-sm">No bank accounts yet. Add one with Edit.</p></Card>
                ) : (
                  <Card padding="sm" className="divide-y divide-edge-subtle">
                    {bankAccounts.map(a => (
                      <div key={a.id} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
                        <div className="min-w-0">
                          <p className="text-body-sm font-mono text-primary-900 dark:text-neutral-50 flex items-center gap-2">
                            {a.physicalAccountNumber}
                            {a.linkedPhysicalAccountId === program.physicalAccountId && <Badge variant="info" size="sm">Main</Badge>}
                          </p>
                          <p className="caption truncate">{a.bankName}</p>
                        </div>
                        <span className="body-strong shrink-0">{formatCurrency(a.bankBalance ?? 0, a.currencyCode)}</span>
                      </div>
                    ))}
                  </Card>
                )}
              </div>
              <div className="space-y-4">
                <h3 className="body-strong font-semibold">Actions</h3>
                <div className="flex flex-wrap gap-2">
                  {program.status === 'ACTIVE' && <Button variant="outline" size="sm" onClick={() => onStatusChange(program.id, 'SUSPENDED')} leftIcon={<PauseCircle className="w-4 h-4" />}>Suspend</Button>}
                  {(program.status === 'SUSPENDED' || program.status === 'INACTIVE') && <Button variant="outline" size="sm" onClick={() => onStatusChange(program.id, 'ACTIVE')} leftIcon={<PlayCircle className="w-4 h-4" />}>Activate</Button>}
                  {program.status === 'PENDING_APPROVAL' && <Button size="sm" onClick={() => onStatusChange(program.id, 'ACTIVE')} leftIcon={<CheckCircle className="w-4 h-4" />}>Approve</Button>}
                  <Button variant="outline" size="sm" onClick={() => onClone(program)} leftIcon={<Copy className="w-4 h-4" />}>Clone</Button>
                </div>
              </div>
            </div>
          )}

          {/* Hierarchy Tab - NEW */}
          {!loading && activeTab === 'hierarchy' && (
            <div>
              {!hasHierarchy ? (
                /* Hierarchy Not Initialized */
                <div className="text-center py-12">
                  <StatusIconBadge tone="warning" icon={GitBranch} size="xl" rounded="full" className="mx-auto mb-4" />
                  <h3 className="section-title mb-2">
                    Hierarchy Not Initialized
                  </h3>
                  <p className="text-neutral-500 mb-6 max-w-md mx-auto dark:text-neutral-400">
                    Every program is created with a hierarchy. This one predates that and is initialized on the next restart.
                  </p>
                </div>
              ) : (
                /* Hierarchy Initialized - Show Settlement VAs and Exception VAs */
                <div className="grid grid-cols-2 gap-6">
                  {/* Left Column: Configuration & Exception VAs */}
                  <div className="space-y-4">
                    <h3 className="body-strong font-semibold">Hierarchy Configuration</h3>
                    <Card padding="sm" className="space-y-3">
                      <div className="flex justify-between">
                        <span className="body-sm">Template</span>
                        <span className="text-body-sm font-medium">{HIERARCHY_TEMPLATES.find(t => t.id === program.defaultHierarchyTemplate)?.name ?? 'Standard'}</span>
                      </div>
                      <div className="flex justify-between">
                        <span className="body-sm">Depth</span>
                        <span className="text-body-sm">{program.hierarchyDepth || 7} levels</span>
                      </div>
</Card>

                    <h3 className="body-strong font-semibold flex items-center gap-2">
                      <XCircle className="w-4 h-4 text-error-500 dark:text-error-300" />Exception VAs
                    </h3>
                    {hierarchyLoading ? (
                      <div className="flex items-center gap-2 p-4"><Loader2 className="w-4 h-4 animate-spin" /><span className="body-sm">Loading...</span></div>
                    ) : exceptionVas.length > 0 ? (
                      <div className="space-y-2">
                        {exceptionVas.map((va) => (
                          <Card key={va.id} padding="sm" className="flex justify-between items-center hover:bg-neutral-50 dark:hover:bg-primary-800/50">
                            <div>
                              <p className="body-strong">{va.vaName}</p>
                              <p className="text-caption text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                            </div>
                            <div className="text-right">
                              <Badge variant="error" size="sm">Exception</Badge>
                              <p className="caption mt-1">{formatCurrency(va.currentBalance, va.currency)}</p>
                            </div>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <Card padding="sm" className="text-center body-sm py-4">
                        <p>No Exception VAs created yet</p>
                      </Card>
                    )}
                  </div>

                  {/* Right Column: Settlement VAs */}
                  <div className="space-y-4">
                    <div className="flex items-center justify-between">
                      <h3 className="body-strong font-semibold flex items-center gap-2">
                        <FolderTree className="w-4 h-4" />Settlement VAs
                      </h3>
                      <span className="caption">{settlementVas.length} total</span>
                    </div>
                    {hierarchyLoading ? (
                      <div className="flex items-center justify-center py-12"><Loader2 className="w-6 h-6 animate-spin text-primary-500" /></div>
                    ) : settlementVas.length > 0 ? (
                      <div className="space-y-2 max-h-[400px] overflow-y-auto">
                        {settlementVas.map((va) => (
                          <Card key={va.id} padding="sm" className="flex justify-between items-center hover:bg-neutral-50 cursor-pointer dark:hover:bg-primary-800/50">
                            <div className="flex items-center gap-3">
                              <div className="w-8 h-8 rounded-lg bg-accent-100 flex items-center justify-center dark:bg-accent-500/20">
                                <span className="text-caption font-medium text-accent-700 dark:text-accent-300">{va.currency}</span>
                              </div>
                              <div>
                                <p className="body-strong">{va.vaName}</p>
                                <p className="text-caption text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                                {va.hierarchyPath && (
                                  <p className="caption mt-0.5">{va.hierarchyPath}</p>
                                )}
                              </div>
                            </div>
                            <div className="text-right">
                              <p className="body-strong">{formatCurrency(va.currentBalance, va.currency)}</p>
                              <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{va.status}</Badge>
                            </div>
                          </Card>
                        ))}
                      </div>
                    ) : (
                      <Card padding="sm" className="text-center py-12">
                        <FolderTree className="w-8 h-8 text-neutral-300 mx-auto mb-3 dark:text-neutral-400" />
                        <p className="text-neutral-500 mb-2 dark:text-neutral-400">No Settlement VAs created yet</p>
                        <p className="caption">Settlement VAs are created when hierarchy nodes are added</p>
                      </Card>
                    )}

                    {/* Info Box */}
                    <div className="bg-info-50 border border-info-200 rounded-lg p-3 mt-4 dark:bg-info-500/10 dark:border-info-500/30">
                      <div className="flex items-start gap-2">
                        <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                        <div className="text-body-sm text-info-800 dark:text-info-300">
                          <p className="font-medium">About Settlement VAs</p>
                          <ul className="mt-1 space-y-1 text-info-700 text-caption dark:text-info-300">
                            <li>• Each hierarchy level can have Settlement VAs</li>
                            <li>• Balances aggregate up the hierarchy tree</li>
                            <li>• Exception VA catches unmatched transactions</li>
                          </ul>
                        </div>
                      </div>
                    </div>
                  </div>
                </div>
              )}
            </div>
          )}

          {/* VIBAN Pool Tab - NEW */}
          {!loading && activeTab === 'viban' && (
            <div className="space-y-6">
              {vibanLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
                </div>
              ) : (
                <>
                  {/* VIBAN Generation Strategy */}
                  <div>
                    <h3 className="body-strong font-semibold mb-3 flex items-center gap-2">
                      <Settings className="w-4 h-4" />Generation Strategy
                    </h3>
                    <div className="grid grid-cols-3 gap-3">
                      {Object.entries(vibanStrategyConfig).map(([key, config]) => {
                        const StrategyIcon = config.icon;
                        const isActive = program.vibanGenerationStrategy === key;
                        return (
                          <Card 
                            key={key} 
                            padding="sm" 
                            className={cn(
                              'cursor-pointer transition-all',
                              isActive 
                                ? 'ring-2 ring-primary-500 border-primary-500 bg-primary-50 dark:bg-primary-800/40' 
                                : 'hover:border-neutral-300 dark:hover:border-primary-700'
                            )}
                          >
                            <div className="flex items-start gap-3">
                              <StatusIconBadge tone={isActive ? 'primary' : 'neutral'} icon={StrategyIcon} solid={isActive} />
                              <div className="flex-1">
                                <div className="flex items-center gap-2">
                                  <span className={cn('font-medium', isActive ? 'text-primary-900 dark:text-neutral-50' : 'text-neutral-700 dark:text-neutral-200')}>
                                    {config.label}
                                  </span>
                                  {isActive && <Badge variant="success" size="sm">Active</Badge>}
                                </div>
                                <p className="caption mt-1">{config.description}</p>
                              </div>
                            </div>
                          </Card>
                        );
                      })}
                    </div>
                  </div>

                  {/* VIBAN Configuration */}
                  <div className="grid grid-cols-2 gap-6">
                    <div className="space-y-4">
                      <h3 className="body-strong font-semibold flex items-center gap-2">
                        <Hash className="w-4 h-4" />VIBAN Settings
                      </h3>
                      <Card padding="sm" className="space-y-3">
                        <div className="flex justify-between">
                          <span className="body-sm">VIBAN Prefix</span>
                          <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">
                            {program.vibanPrefix || 'Not set'}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="body-sm">Bank Code</span>
                          <span className="text-body-sm font-mono font-medium text-primary-900 dark:text-neutral-50">
                            {program.vibanBankCode || 'Not set'}
                          </span>
                        </div>
                        <div className="flex justify-between">
                          <span className="body-sm">Generation Strategy</span>
                          <Badge variant="neutral">
                            {vibanStrategyConfig[program.vibanGenerationStrategy || 'SEQUENTIAL']?.label}
                          </Badge>
                        </div>
                        {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && (
                          <div className="pt-2 border-t">
                            <div className="flex items-start gap-2 text-caption text-info-700 bg-info-50 p-2 rounded-md dark:text-info-300 dark:bg-info-500/10">
                              <Info className="w-3 h-3 mt-0.5" />
                              <span>VIBANs encode hierarchy path for automatic routing</span>
                            </div>
                          </div>
                        )}
                      </Card>

                      {/* Sample VIBAN Preview */}
                      <h3 className="body-strong font-semibold">Sample VIBAN Format</h3>
                      <Card padding="sm" className="bg-surface-page">
                        <div className="font-mono text-body-lg text-center text-primary-900 tracking-wider dark:text-neutral-50">
                          {program.vibanPrefix || 'AE'}{program.vibanBankCode || '00'}-
                          {program.vibanGenerationStrategy === 'SEQUENTIAL' && '0000-0001'}
                          {program.vibanGenerationStrategy === 'RANDOM' && 'X7K2-9M4P'}
                          {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' && 'L1L2-0001'}
                          {!program.vibanGenerationStrategy && '0000-0001'}
                        </div>
                        <p className="caption text-center mt-2">
                          {program.vibanGenerationStrategy === 'HIERARCHY_ENCODED' 
                            ? 'Includes encoded hierarchy levels'
                            : 'Standard VIBAN format'}
                        </p>
                      </Card>
                    </div>

                    <div className="space-y-4">
                      <h3 className="body-strong font-semibold flex items-center gap-2">
                        <Layers className="w-4 h-4" />VIBAN Pool
                      </h3>
                      {vibanPool ? (
                        <Card padding="sm" className="space-y-3">
                          <div className="flex justify-between">
                            <span className="body-sm">Pool Name</span>
                            <span className="body-strong">{vibanPool.poolName}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="body-sm">Pool Code</span>
                            <span className="text-body-sm font-mono">{vibanPool.poolCode}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="body-sm">Bank Code</span>
                            <span className="text-body-sm font-mono">{vibanPool.bankCode}</span>
                          </div>
                          <div className="flex justify-between">
                            <span className="body-sm">Status</span>
                            <Badge variant={vibanPool.status === 'ACTIVE' ? 'success' : 'neutral'}>
                              {vibanPool.status}
                            </Badge>
                          </div>
                          <div className="pt-3 border-t">
                            <div className="grid grid-cols-3 gap-2 text-center">
                              <div>
                                <p className="section-title">{vibanPool.poolSize}</p>
                                <p className="caption">Total</p>
                              </div>
                              <div>
                                <p className="text-body-lg font-semibold text-success-600 dark:text-success-300">{vibanPool.availableCount}</p>
                                <p className="caption">Available</p>
                              </div>
                              <div>
                                <p className="text-body-lg font-semibold text-warning-600 dark:text-warning-300">{vibanPool.assignedCount}</p>
                                <p className="caption">Used</p>
                              </div>
                            </div>
                            {/* Usage Progress Bar */}
                            <div className="mt-3">
                              <div className="flex justify-between caption mb-1">
                                <span>Pool Usage</span>
                                <span>{Math.round((vibanPool.assignedCount / vibanPool.poolSize) * 100)}%</span>
                              </div>
                              <div className="h-2 bg-neutral-200 rounded-full overflow-hidden dark:bg-primary-800">
                                <div 
                                  className="h-full bg-primary-500 rounded-full transition-all"
                                  style={{ width: `${(vibanPool.assignedCount / vibanPool.poolSize) * 100}%` }}
                                />
                              </div>
                            </div>
                          </div>
                        </Card>
                      ) : (
                        <Card padding="sm" className="text-center py-8">
                          <Hash className="w-8 h-8 text-neutral-300 mx-auto mb-3 dark:text-neutral-400" />
                          <p className="text-neutral-500 mb-2 dark:text-neutral-400">No VIBAN Pool Assigned</p>
                          <p className="caption mb-3">Assign a VIBAN pool to enable VIBAN generation</p>
                          <Button size="sm" variant="outline" leftIcon={<Hash className="w-4 h-4" />} onClick={() => onConfigure(program, 'VIBAN Pool')}>Set up VIBAN pool</Button>
                        </Card>
                      )}

                      {/* Quick Stats */}
                      {vibanPool && (
                        <div className="bg-accent-50 border border-accent-200 rounded-lg p-3 dark:bg-accent-500/10 dark:border-accent-500/30">
                          <div className="flex items-start gap-2">
                            <Info className="w-4 h-4 text-accent-600 mt-0.5 dark:text-accent-300" />
                            <div className="text-body-sm text-accent-800 dark:text-accent-300">
                              <p className="font-medium">Pool Capacity</p>
                              <p className="text-accent-700 text-caption mt-1 dark:text-accent-300">
                                {vibanPool.availableCount} VIBANs available for new virtual accounts
                              </p>
                            </div>
                          </div>
                        </div>
                      )}
                    </div>
                  </div>

                  {/* VIBAN Features Info */}
                  <div className="bg-info-50 border border-info-200 rounded-lg p-4 dark:bg-info-500/10 dark:border-info-500/30">
                    <div className="flex items-start gap-3">
                      <Info className="w-5 h-5 text-info-600 mt-0.5 dark:text-info-300" />
                      <div>
                        <p className="font-medium text-info-900">About VIBAN Generation</p>
                        <ul className="mt-2 space-y-1 text-body-sm text-info-700 dark:text-info-300">
                          <li>• <strong>Sequential:</strong> Best for predictable, ordered VIBAN allocation</li>
                          <li>• <strong>Random:</strong> Enhanced security with unpredictable identifiers</li>
                          <li>• <strong>Hierarchy Encoded:</strong> Enables automatic routing based on hierarchy structure</li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </>
              )}
            </div>
          )}
          {/* Wallet Fees Tab - NEW */}{/* Wallet Fees Tab - NEW */}
          {!loading && activeTab === 'wallet' && (
            <div className="space-y-6">
              {walletChargesLoading ? (
                <div className="flex items-center justify-center py-12">
                  <Loader2 className="w-6 h-6 animate-spin text-primary-500" />
                </div>
              ) : walletCharges ? (
                <>
                  {/* Header */}
                  <div className="flex items-center justify-between">
                    <div>
                      <h3 className="body-strong font-semibold flex items-center gap-2">
                        <Wallet className="w-4 h-4" />
                        Wallet Fee Configuration
                      </h3>
                      <p className="caption mt-1">
                        Standard rates apply unless this program overrides them
                      </p>
                    </div>
                    <Button size="sm" variant="outline" leftIcon={<Pencil className="w-4 h-4" />} onClick={() => onConfigure(program, 'Wallet Fees')}>Edit fees</Button>
                  </div>

                  {/* Transaction Fees */}
                  <div>
                    <h4 className="label mb-3">Transaction Fees</h4>
                    <div className="space-y-2">
                      {/* Topup Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.topup.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.topup.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-surface-page border-edge'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="success" icon={Plus} rounded="lg" />
                          <div>
                            <p className="body-strong">{walletCharges.topup.chargeName}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.topup.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="body-strong font-semibold">
                                {walletCharges.topup.percentage}% + {program.currencyCode} {walletCharges.topup.fixed}
                              </p>
                              <p className="caption">
                                Min: {program.currencyCode} {walletCharges.topup.minimum || 0} | Max: {program.currencyCode} {walletCharges.topup.maximum || '∞'}
                              </p>
                              {walletCharges.topup.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>

                      {/* Withdrawal Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.withdrawal.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.withdrawal.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-surface-page border-edge'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="error" icon={Banknote} rounded="lg" />
                          <div>
                            <p className="body-strong">{walletCharges.withdrawal.chargeName}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.withdrawal.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="body-strong font-semibold">
                                {walletCharges.withdrawal.percentage}% + {program.currencyCode} {walletCharges.withdrawal.fixed}
                              </p>
                              <p className="caption">
                                Min: {program.currencyCode} {walletCharges.withdrawal.minimum || 0} | Max: {program.currencyCode} {walletCharges.withdrawal.maximum || '∞'}
                              </p>
                              {walletCharges.withdrawal.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>

                      {/* Transfer Fee */}
                      <div className={cn(
                        'flex items-center justify-between p-3 rounded-lg border',
                        walletCharges.transfer.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.transfer.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : 'bg-surface-page border-edge'
                      )}>
                        <div className="flex items-center gap-3">
                          <StatusIconBadge tone="info" icon={TrendingUp} rounded="lg" />
                          <div>
                            <p className="body-strong">{walletCharges.transfer.chargeName}</p>
                          </div>
                        </div>
                        <div className="text-right">
                          {walletCharges.transfer.isWaived ? (
                            <Badge variant="warning">Waived</Badge>
                          ) : (
                            <>
                              <p className="body-strong font-semibold">
                                {walletCharges.transfer.percentage}% + {program.currencyCode} {walletCharges.transfer.fixed}
                              </p>
                              <p className="caption">
                                Min: {program.currencyCode} {walletCharges.transfer.minimum || 0} | Max: {program.currencyCode} {walletCharges.transfer.maximum || '∞'}
                              </p>
                              {walletCharges.transfer.hasOverride && <Badge variant="info" size="sm" className="mt-1">Override</Badge>}
                            </>
                          )}
                        </div>
                      </div>
                    </div>
                  </div>

                  {/* Fixed Fees */}
                  <div>
                    <h4 className="label mb-3">Fixed Fees</h4>
                    <div className="grid grid-cols-3 gap-3">
                      {/* Issuance Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.issuance.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.issuance.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <CreditCard className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="caption">Issuance</span>
                        </div>
                        {walletCharges.issuance.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.issuance.fixed}
                            </p>
                            {walletCharges.issuance.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="caption mt-1">One-time fee</p>
                      </Card>

                      {/* Monthly Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.monthly.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.monthly.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <Clock className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="caption">Monthly</span>
                        </div>
                        {walletCharges.monthly.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.monthly.fixed}
                            </p>
                            {walletCharges.monthly.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="caption mt-1">Recurring monthly</p>
                      </Card>

                      {/* Inactivity Fee */}
                      <Card padding="sm" className={cn(
                        walletCharges.inactivity.isWaived ? 'bg-warning-50 border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30' :
                        walletCharges.inactivity.hasOverride ? 'bg-primary-50 border-primary-200 dark:bg-primary-800/40 dark:border-primary-700' : ''
                      )}>
                        <div className="flex items-center gap-2 mb-2">
                          <PauseCircle className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
                          <span className="caption">Inactivity</span>
                        </div>
                        {walletCharges.inactivity.isWaived ? (
                          <Badge variant="warning">Waived</Badge>
                        ) : (
                          <>
                            <p className="section-title">
                              {program.currencyCode} {walletCharges.inactivity.fixed}
                            </p>
                            {walletCharges.inactivity.hasOverride && <Badge variant="info" size="sm">Override</Badge>}
                          </>
                        )}
                        <p className="caption mt-1">After inactivity period</p>
                      </Card>
                    </div>
                  </div>

                  {/* Fee Summary */}
                  <div className="bg-surface-page rounded-lg p-4">
                    <h4 className="label mb-3">Fee Status Summary</h4>
                    <div className="grid grid-cols-3 gap-4 text-center">
                      <div>
                        <p className="stat-value-sm">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => !c.isWaived && !c.hasOverride).length}
                        </p>
                        <p className="caption">Base Rates</p>
                      </div>
                      <div>
                        <p className="stat-value-info">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => c.hasOverride && !c.isWaived).length}
                        </p>
                        <p className="caption">Overrides</p>
                      </div>
                      <div>
                        <p className="stat-value-warning">
                          {[walletCharges.topup, walletCharges.withdrawal, walletCharges.transfer, walletCharges.issuance, walletCharges.monthly, walletCharges.inactivity]
                            .filter(c => c.isWaived).length}
                        </p>
                        <p className="caption">Waived</p>
                      </div>
                    </div>
                  </div>

                  {/* Info Box */}
                  <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
                    <div className="flex items-start gap-2">
                      <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
                      <div className="text-body-sm text-info-800 dark:text-info-300">
                        <p className="font-medium">About Wallet Fees</p>
                        <ul className="mt-1 space-y-1 text-info-700 text-caption dark:text-info-300">
                          <li>• <strong>Base:</strong> The standard rate for all programs</li>
                          <li>• <strong>Override:</strong> Program-specific customized rates</li>
                          <li>• <strong>Waived:</strong> Fee is not charged for this program</li>
                        </ul>
                      </div>
                    </div>
                  </div>
                </>
              ) : walletChargesError ? (
                <Alert variant="error" title="Couldn't load wallet fees">
                  The fee configuration for this program could not be loaded. No fees are shown rather than guessing them.
                </Alert>
              ) : (
                <div className="text-center py-12">
                  <Wallet className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-400" />
                  <p className="text-neutral-500 dark:text-neutral-400">No wallet fee configuration found</p>
                </div>
              )}
            </div>
          )}

          {/* Virtual Accounts Tab */}
          {!loading && activeTab === 'accounts' && detail && (
            <div className="space-y-4">
              {detail.recentVirtualAccounts.length > 0 && (
                <div className="flex items-center justify-between gap-3">
                  <p className="caption">
                    Newest {detail.recentVirtualAccounts.length} of {program.virtualAccountCount ?? detail.recentVirtualAccounts.length}
                  </p>
                  <Button size="sm" variant="outline" rightIcon={<ChevronRight className="w-4 h-4" />}
                    onClick={() => { onClose(); navigate('accounts', { programId: program.id, corporateId: program.corporateId }); }}>
                    See all in Virtual Accounts
                  </Button>
                </div>
              )}
              {detail.recentVirtualAccounts.length === 0 ? (
                <div className="text-center py-12 text-neutral-500 dark:text-neutral-400">
                  <CreditCard className="w-12 h-12 mx-auto mb-3 text-neutral-300 dark:text-neutral-400" />
                  <p>No virtual accounts in this program</p>
                </div>
              ) : (
                <div className="space-y-2">
                  {detail.recentVirtualAccounts.map(va => (
                    <Card key={va.id} padding="sm" className="flex items-center justify-between hover:bg-neutral-50 cursor-pointer dark:hover:bg-primary-800/50">
                      <div className="flex items-center gap-3">
                        <StatusIconBadge tone="primary" icon={CreditCard} rounded="lg" />
                        <div>
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{va.vaName}</p>
                          <p className="text-caption text-neutral-500 font-mono dark:text-neutral-400">{va.vaNumber}</p>
                        </div>
                      </div>
                      <div className="flex items-center gap-4">
                        <div className="text-right">
                          <p className="font-medium text-primary-900 dark:text-neutral-50">{formatCurrency(va.currentBalance, program.currencyCode)}</p>
                          <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm">{va.status}</Badge>
                        </div>
                        <ChevronRight className="w-4 h-4 text-neutral-400" />
                      </div>
                    </Card>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Config Tab */}
          {!loading && activeTab === 'config' && (
            <div className="space-y-6">
              <Card padding="md">
                <h4 className="font-medium text-primary-900 mb-4 dark:text-neutral-50">VA Configuration</h4>
                <div className="grid grid-cols-2 gap-4">
                  <div><p className="caption mb-1">Prefix</p><p className="font-mono">{program.vaPrefix || 'None'}</p></div>
                  <div><p className="caption mb-1">Format</p><p className="font-mono">{program.vaFormat || 'Auto'}</p></div>
                  <div><p className="caption mb-1">Max Accounts</p><p>{program.maxVirtualAccounts || 'Unlimited'}</p></div>
                </div>
              </Card>
              {/* Wallet Limits - Only show if wallet is enabled */}
              {(
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 flex items-center gap-2 dark:text-neutral-50">
                    <Wallet className="w-4 h-4" />Wallet Limits
                  </h4>
                  <div className="grid grid-cols-3 gap-4">
                    <div>
                      <p className="caption mb-1">Per Transaction</p>
                      <p className="font-medium">{program.defaultPerTransactionLimit ? formatCurrency(program.defaultPerTransactionLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="caption mb-1">Daily Limit</p>
                      <p className="font-medium">{program.defaultDailyLimit ? formatCurrency(program.defaultDailyLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="caption mb-1">Monthly Limit</p>
                      <p className="font-medium">{program.defaultMonthlyLimit ? formatCurrency(program.defaultMonthlyLimit, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                    <div>
                      <p className="caption mb-1">Max Balance</p>
                      <p className="font-medium">{program.defaultMaxBalance ? formatCurrency(program.defaultMaxBalance, program.currencyCode) : 'Unlimited'}</p>
                    </div>
                  </div>
                </Card>
              )}
              {/* KYC Settings - Only show if wallet is enabled */}
              {(
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 flex items-center gap-2 dark:text-neutral-50">
                    <Shield className="w-4 h-4" />KYC Settings
                  </h4>
                  <div className="grid grid-cols-2 gap-4">
                    <div className="flex items-center justify-between">
                      <span className="body-sm">KYC Required</span>
                      {program.kycRequired ? <CheckCircle className="w-4 h-4 text-success-500 dark:text-success-300" /> : <XCircle className="w-4 h-4 text-neutral-400" />}
                    </div>
                    <div>
                      <p className="caption mb-1">Minimum KYC Level</p>
                      <p className="font-medium">{program.minKycLevel !== undefined ? `Level ${program.minKycLevel}` : 'Not set'}</p>
                    </div>
                  </div>
                </Card>
              )}
              {/* Wallet Capabilities - Only show if wallet is enabled */}
              {(
                <Card padding="md">
                  <h4 className="font-medium text-primary-900 mb-4 dark:text-neutral-50">Wallet Capabilities</h4>
                  <div className="grid grid-cols-2 gap-4">
                    {[
                      { label: 'Allow Topup', value: program.allowTopup },
                      { label: 'Allow Withdrawal', value: program.allowWithdrawal },
                      { label: 'Allow Transfer', value: program.allowTransfer },
                    ].map(cap => (
                      <div key={cap.label} className="flex items-center justify-between">
                        <span className="body-sm">{cap.label}</span>
                        {cap.value ? <CheckCircle className="w-4 h-4 text-success-500 dark:text-success-300" /> : <XCircle className="w-4 h-4 text-neutral-400" />}
                      </div>
                    ))}
                  </div>
                </Card>
              )}
            </div>
          )}

          {/* History Tab */}
          {!loading && activeTab === 'history' && detail && (
            <div className="space-y-3">
              {detail.activityLog.length === 0 ? <p className="text-center py-8 text-neutral-500 dark:text-neutral-400">No activity</p> : detail.activityLog.map((item, i) => (
                <div key={i} className="flex items-start gap-3 p-3 bg-surface-page rounded-lg">
                  <StatusIconBadge tone={item.type === 'warning' ? 'warning' : item.type === 'error' ? 'error' : item.type === 'info' ? 'info' : 'success'} icon={item.type === 'warning' ? PauseCircle : CheckCircle} size="sm" rounded="full" />
                  <div className="flex-1">
                    <p className="body-strong">{item.action}</p>
                    {item.details && <p className="body-sm">{item.details}</p>}
                    <p className="caption">by {item.user} • {formatDate(item.timestamp)}</p>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
};
