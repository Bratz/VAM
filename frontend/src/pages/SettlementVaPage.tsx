import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
/**
 * Settlement VA Management Page - FIXED: Proper hierarchy tree display in modal
 */

import React, { useState, useEffect, useCallback, useRef } from 'react';
import {
  Scale, AlertTriangle, Plus, RefreshCw, Loader2, ChevronRight, DollarSign,
  Clock, Building2, Layers, X, Filter, Settings, ArrowLeft, Play, Info,
  FolderTree, GitBranch, CreditCard, Wallet, Shield, Banknote, Hash,
  CheckCircle, XCircle, Trash2, ChevronDown,
} from 'lucide-react';
import { Card, Button, Badge, EmptyState , StatusIconBadge, Drawer } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal, Tabs } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';

const API_BASE = '/api/v1';
const API_TIMEOUT = 8000;

interface ApiResponse<T> { success: boolean; data: T; message?: string; }

async function fetchApi<T>(url: string, options?: RequestInit): Promise<ApiResponse<T>> {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), API_TIMEOUT);
  try {
    const response = await fetch(API_BASE + url, {
      headers: { 'Content-Type': 'application/json', ...options?.headers },
      signal: controller.signal, ...options,
    });
    clearTimeout(timeoutId);
    if (!response.ok) return { success: false, data: null as unknown as T, message: `Error ${response.status}` };
    const json = await response.json();
    if (json.success !== undefined && json.data !== undefined) return { success: json.success, data: json.data, message: json.message };
    return { success: true, data: json, message: undefined };
  } catch (error: any) {
    clearTimeout(timeoutId);
    if (error.name === 'AbortError') return { success: false, data: null as unknown as T, message: 'Request timeout' };
    return { success: false, data: null as unknown as T, message: 'Network error' };
  }
}

function isValidUUID(str: string): boolean {
  if (!str || str.trim() === '') return false;
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
  return uuidRegex.test(str);
}

interface Program { id: string; programCode: string; programName: string; programType: string; currencyCode: string; status: string; }
interface ProgramListResponse { programs: Program[]; totalCount: number; }
interface SettlementVa { id: string; vaNumber: string; vaName: string; specialType: 'SETTLEMENT' | 'EXCEPTION' | 'REGULAR'; currency: string; currentBalance: number; availableBalance: number; status: string; hierarchyNodeId?: string; hierarchyPath?: string; hierarchyLevel?: number; programId: string; createdAt: string; }
interface SettlementVaListResponse { settlementVas: SettlementVa[]; exceptionVas: SettlementVa[]; summary: { totalSettlementVas: number; totalExceptionVas: number; totalSettlementBalance: number; totalExceptionBalance: number; }; hierarchyInitialized?: boolean; programName?: string; programCode?: string; currencyCode?: string; }
interface SettlementVaDetailResponse { va: SettlementVa; recentTransactions: Array<{ id: string; referenceNumber: string; movementType: string; amount: number; currency: string; balanceAfter: number; description: string; transactionDate: string; }>; coveredVas?: Array<{ vaId: string; vaNumber: string; vaName: string; }>; }
interface HierarchyLevelConfig { levelNumber: number; levelName: string; dimensionType: string; }

// Tree node from backend API
interface TreeNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  nodeType?: string;
  levelNumber: number;
  currencyCode?: string;
  isLeaf?: boolean;
  children?: TreeNode[];
  expanded?: boolean;
}

// Tree response from /programs/{id}/hierarchy/tree
interface TreeResponse {
  programId: string;
  programCode?: string;
  programName?: string;
  levelConfigs?: HierarchyLevelConfig[];
  roots?: TreeNode[];
  root?: TreeNode; // Some responses use 'root' instead of 'roots'
}

interface Toast { id: string; type: 'success' | 'error' | 'info' | 'warning'; title: string; message?: string; }

const HIERARCHY_TEMPLATES = [
  { id: 'COLLECTION_PROGRAM', name: 'Collection', description: 'For receivables', icon: CreditCard, color: 'text-info-600 dark:text-info-300', bgColor: 'bg-info-50 dark:bg-info-500/10', forProgramTypes: ['COLLECTION'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }, { levelNumber: 2, levelName: 'Channel', dimensionType: 'CHANNEL' }] },
  { id: 'IHB_PROGRAM', name: 'In-House Bank', description: 'For intercompany', icon: Building2, color: 'text-primary-600 dark:text-primary-200', bgColor: 'bg-primary-50 dark:bg-primary-800/40', forProgramTypes: ['IHB'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }, { levelNumber: 2, levelName: 'Entity', dimensionType: 'ENTITY' }] },
  { id: 'WALLET_PROGRAM', name: 'Wallet', description: 'For prepaid', icon: Wallet, color: 'text-warning-600 dark:text-warning-300', bgColor: 'bg-warning-50 dark:bg-warning-500/10', forProgramTypes: ['WALLET'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }] },
  { id: 'ESCROW_PROGRAM', name: 'Escrow', description: 'For escrow', icon: Shield, color: 'text-success-600 dark:text-success-300', bgColor: 'bg-success-50 dark:bg-success-500/10', forProgramTypes: ['ESCROW'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }] },
  { id: 'VIBAN_PROGRAM', name: 'VIBAN', description: 'For virtual IBAN', icon: Hash, color: 'text-accent-600 dark:text-accent-300', bgColor: 'bg-accent-50 dark:bg-accent-500/10', forProgramTypes: ['VIBAN'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }] },
  { id: 'PAYABLES_PROGRAM', name: 'Payables', description: 'For payables', icon: Banknote, color: 'text-error-600 dark:text-error-300', bgColor: 'bg-error-50 dark:bg-error-500/10', forProgramTypes: ['PAYABLES'], recommended: true, levels: [{ levelNumber: 1, levelName: 'Currency', dimensionType: 'CURRENCY' }] },
];

const ToastContainer: React.FC<{ toasts: Toast[]; onDismiss: (id: string) => void }> = ({ toasts, onDismiss }) => (
  <div className="fixed top-4 right-4 z-50 space-y-2">
    {toasts.map((t) => (
      <div key={t.id} className={cn('flex items-start gap-3 p-4 rounded-lg shadow-lg min-w-[300px]', t.type === 'success' && 'bg-success-50 border border-success-200 dark:bg-success-500/10 dark:border-success-500/30', t.type === 'error' && 'bg-error-50 border border-error-200 dark:bg-error-500/10 dark:border-error-500/30', t.type === 'info' && 'bg-info-50 border border-info-200 dark:bg-info-500/10 dark:border-info-500/30', t.type === 'warning' && 'bg-warning-50 border border-warning-200 dark:bg-warning-500/10 dark:border-warning-500/30')}>
        {t.type === 'success' && <CheckCircle className="w-5 h-5 text-success-600 dark:text-success-300" />}
        {t.type === 'error' && <XCircle className="w-5 h-5 text-error-600 dark:text-error-300" />}
        {t.type === 'info' && <Info className="w-5 h-5 text-info-600 dark:text-info-300" />}
        {t.type === 'warning' && <AlertTriangle className="w-5 h-5 text-warning-600 dark:text-warning-300" />}
        <div className="flex-1"><p className="font-medium">{t.title}</p>{t.message && <p className="text-sm text-neutral-600 dark:text-neutral-300">{t.message}</p>}</div>
        <button onClick={() => onDismiss(t.id)}><X className="w-4 h-4" /></button>
      </div>
    ))}
  </div>
);

const SettlementVaCard: React.FC<{ va: SettlementVa; onView: (va: SettlementVa) => void; onDelete?: (va: SettlementVa) => void; isException?: boolean }> = ({ va, onView, onDelete, isException = false }) => {
  const Icon = isException ? AlertTriangle : Scale;
  return (
    <Card padding="md" hover className="cursor-pointer hover:ring-2 hover:ring-primary-200" onClick={() => onView(va)}>
      <div className="flex items-start gap-4">
        <div className={cn('w-12 h-12 rounded-xl flex items-center justify-center', isException ? 'bg-warning-100 dark:bg-warning-500/20' : 'bg-cat-2/10 dark:bg-cat-2/15')}>
          <Icon className={cn('w-6 h-6', isException ? 'text-warning-700 dark:text-warning-300' : 'text-cat-2')} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <h3 className="font-semibold truncate">{va.vaNumber}</h3>
            <Badge variant={isException ? 'warning' : 'info'} size="sm">{isException ? 'Exception' : 'Settlement'}</Badge>
          </div>
          <p className="text-sm text-neutral-600 dark:text-neutral-300">{va.vaName}</p>
          {va.hierarchyPath && <p className="text-xs text-neutral-400 mt-1 dark:text-neutral-500">L{va.hierarchyLevel}: {va.hierarchyPath}</p>}
        </div>
        <div className="text-right">
          <p className="text-lg font-semibold">{formatCurrency(va.currentBalance || 0, va.currency)}</p>
          <Badge variant={va.status === 'ACTIVE' ? 'success' : 'neutral'} size="sm" className="mt-2">{va.status}</Badge>
        </div>
        <div className="flex items-center gap-2">
          {!isException && onDelete && va.currentBalance === 0 && (
            <button onClick={(e) => { e.stopPropagation(); onDelete(va); }} className="p-2 hover:bg-error-50 rounded-lg text-neutral-400 hover:text-error-600 dark:hover:bg-error-500/10 dark:text-neutral-500"><Trash2 className="w-4 h-4" /></button>
          )}
          <ChevronRight className="w-5 h-5 text-neutral-400 dark:text-neutral-500" />
        </div>
      </div>
    </Card>
  );
};

const SummaryStats: React.FC<{ data: SettlementVaListResponse; currency: string }> = ({ data, currency }) => (
  <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
    <Card hover className="animate-fade-in" style={{ animationDelay: '0.15s' }}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">Settlement VAs</p>
          <p className="stat-value-sm mt-1">{data.summary.totalSettlementVas}</p>
        </div>
        <StatusIconBadge tone="accent" icon={Scale} className="dark:bg-accent-500/20" />
      </div>
    </Card>
    <Card hover className="animate-fade-in" style={{ animationDelay: '0.2s' }}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">Settlement Balance</p>
          <p className="stat-value-sm mt-1 text-success-600 dark:text-success-300">{formatCurrency(data.summary.totalSettlementBalance || 0, currency)}</p>
        </div>
        <StatusIconBadge tone="success" icon={DollarSign} className="dark:bg-success-500/20" />
      </div>
    </Card>
    <Card hover className="animate-fade-in" style={{ animationDelay: '0.25s' }}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">Exception VAs</p>
          <p className="stat-value-sm mt-1">{data.summary.totalExceptionVas}</p>
        </div>
        <StatusIconBadge tone="warning" icon={AlertTriangle} className="dark:bg-warning-500/20" />
      </div>
    </Card>
    <Card hover className="animate-fade-in" style={{ animationDelay: '0.3s' }}>
      <div className="flex items-start justify-between">
        <div className="flex-1">
          <p className="label">Exception Balance</p>
          <p className="stat-value-sm mt-1 text-error-600 dark:text-error-300">{formatCurrency(data.summary.totalExceptionBalance || 0, currency)}</p>
        </div>
        <StatusIconBadge tone="error" icon={Clock} className="dark:bg-error-500/20" />
      </div>
    </Card>
  </div>
);

const DetailDrawer: React.FC<{ va: SettlementVa | null; detail: SettlementVaDetailResponse | null; loading: boolean; onClose: () => void }> = ({ va, detail, loading, onClose }) => {
  if (!va) return null;
  const isException = va.specialType === 'EXCEPTION';
  return (
    <Drawer
      isOpen
      onClose={onClose}
      size="md"
      title={va.vaNumber}
      subtitle={va.vaName}
      footer={<Button variant="outline" onClick={onClose}>Close</Button>}
    >
      <div className="p-6 space-y-4">
        {loading ? <Loader2 className="w-8 h-8 animate-spin mx-auto" /> : (
          <>
            <div className="grid grid-cols-2 gap-3">
              <Card padding="sm" className="bg-neutral-50 dark:bg-primary-950"><p className="text-xs text-neutral-500 dark:text-neutral-400">Balance</p><p className="text-lg font-semibold">{formatCurrency(va.currentBalance || 0, va.currency)}</p></Card>
              <Card padding="sm" className="bg-success-50 dark:bg-success-500/10"><p className="text-xs text-neutral-500 dark:text-neutral-400">Available</p><p className="text-lg font-semibold text-success-600 dark:text-success-300">{formatCurrency(va.availableBalance || 0, va.currency)}</p></Card>
            </div>
            {va.hierarchyPath && <div className="p-3 bg-neutral-50 rounded-lg dark:bg-primary-950"><Layers className="w-4 h-4 inline mr-2" />{va.hierarchyPath}</div>}
            {!isException && detail?.coveredVas && detail.coveredVas.length > 0 && (
              <div><h4 className="text-sm font-semibold mb-2">Covered VAs ({detail.coveredVas.length})</h4>
                <div className="space-y-2 max-h-40 overflow-y-auto">{detail.coveredVas.map((cva) => <div key={cva.vaId} className="flex justify-between p-2 bg-neutral-50 rounded-lg text-sm dark:bg-primary-950"><span>{cva.vaNumber}</span><span className="text-neutral-500 dark:text-neutral-400">{cva.vaName}</span></div>)}</div>
              </div>
            )}
            <div><h4 className="text-sm font-semibold mb-2">Recent Transactions</h4>
              {detail?.recentTransactions?.length ? detail.recentTransactions.map((txn) => (
                <div key={txn.id} className="flex justify-between p-3 bg-neutral-50 rounded-lg mb-2 dark:bg-primary-950">
                  <div><Badge variant="info" size="sm">{txn.movementType}</Badge><p className="text-sm">{txn.description}</p></div>
                  <p className="font-semibold text-success-600 dark:text-success-300">+{formatCurrency(txn.amount, txn.currency)}</p>
                </div>
              )) : <p className="text-neutral-500 text-center py-4 dark:text-neutral-400">No transactions</p>}
            </div>
          </>
        )}
      </div>
    </Drawer>
  );
};

const TemplateSelectorModal: React.FC<{ isOpen: boolean; program: Program | null; onClose: () => void; onSelect: (id: string) => void; loading?: boolean }> = ({ isOpen, program, onClose, onSelect, loading }) => {
  const [selected, setSelected] = useState<string | null>(null);
  const [preview, setPreview] = useState<any>(null);
  useEffect(() => { if (program) { const rec = HIERARCHY_TEMPLATES.find(t => t.forProgramTypes.includes(program.programType)); if (rec) { setSelected(rec.id); setPreview(rec); } } }, [program]);
  if (!program || !isOpen) return null;
  const relevant = HIERARCHY_TEMPLATES.filter(t => t.forProgramTypes.includes(program.programType));
  const others = HIERARCHY_TEMPLATES.filter(t => !t.forProgramTypes.includes(program.programType));
  return (
    <Modal isOpen={isOpen} onClose={onClose} size="xl" title="Initialize Hierarchy">
      <div className="flex gap-6 min-h-[400px]">
        <div className="w-1/2 overflow-y-auto pr-4 border-r space-y-2">
          <p className="text-sm text-neutral-600 mb-4 dark:text-neutral-300">Select template for <strong>{program.programName}</strong></p>
          {relevant.map((t) => { const Icon = t.icon; return (
            <div key={t.id} onClick={() => { setSelected(t.id); setPreview(t); }} className={cn('p-4 border rounded-lg cursor-pointer', selected === t.id ? 'border-primary-500 bg-primary-50 ring-2 ring-primary-500 dark:bg-primary-800/40' : 'border-neutral-200 hover:bg-neutral-50 dark:border-primary-800 dark:hover:bg-primary-800/50')}>
              <div className="flex items-center gap-3"><div className={cn('w-10 h-10 rounded-lg flex items-center justify-center', t.bgColor)}><Icon className={cn('w-5 h-5', t.color)} /></div><div><span className="font-medium">{t.name}</span><Badge variant="success" size="sm" className="ml-2">Recommended</Badge><p className="text-xs text-neutral-500 dark:text-neutral-400">{t.description}</p></div></div>
            </div>
          ); })}
          {others.length > 0 && <><h3 className="text-xs font-semibold text-neutral-500 uppercase mt-4 mb-2 dark:text-neutral-400">Other Templates</h3>
            {others.map((t) => { const Icon = t.icon; return (
              <div key={t.id} onClick={() => { setSelected(t.id); setPreview(t); }} className={cn('p-3 border rounded-lg cursor-pointer', selected === t.id ? 'border-primary-500 bg-primary-50 dark:bg-primary-800/40' : 'border-neutral-200 opacity-60 hover:opacity-100 dark:border-primary-800')}>
                <div className="flex items-center gap-3"><div className={cn('w-8 h-8 rounded-lg flex items-center justify-center', t.bgColor)}><Icon className={cn('w-4 h-4', t.color)} /></div><span className="text-sm font-medium">{t.name}</span></div>
              </div>
            ); })}</>}
        </div>
        <div className="w-1/2 overflow-y-auto">
          {preview ? (
            <div>
              <div className="flex items-center gap-3 mb-4"><div className={cn('w-12 h-12 rounded-xl flex items-center justify-center', preview.bgColor)}><preview.icon className={cn('w-6 h-6', preview.color)} /></div><div><h3 className="font-semibold">{preview.name}</h3><p className="text-sm text-neutral-500 dark:text-neutral-400">{preview.levels.length} levels</p></div></div>
              <div className="bg-neutral-50 rounded-lg p-4 mb-4 dark:bg-primary-950"><h4 className="text-sm font-medium mb-3">Hierarchy Structure</h4>
                {preview.levels.map((level: any, i: number) => (<div key={level.levelNumber} className="flex items-start gap-3"><div className="flex flex-col items-center"><div className={cn('w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium', i === 0 ? 'bg-primary-600 text-white' : 'bg-neutral-200 dark:bg-primary-800')}>{level.levelNumber}</div>{i < preview.levels.length - 1 && <div className="w-0.5 h-6 bg-neutral-300" />}</div><div className="pb-2"><span className="font-medium">{level.levelName}</span><Badge variant="neutral" size="sm" className="ml-2">{level.dimensionType}</Badge></div></div>))}
              </div>
              <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30"><div className="flex gap-2"><Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" /><div className="text-sm text-info-800 dark:text-info-300"><p className="font-medium">What will be created:</p><ul className="mt-1 space-y-1"><li>• Root hierarchy node</li><li>• <strong>Exception VA</strong> ({program.currencyCode})</li><li>• {preview.levels.length} level config</li></ul></div></div></div>
            </div>
          ) : <div className="flex items-center justify-center h-full text-neutral-400 dark:text-neutral-500"><FolderTree className="w-12 h-12" /></div>}
        </div>
      </div>
      <div className="flex justify-end gap-2 pt-4 border-t mt-4"><Button variant="ghost" onClick={onClose}>Cancel</Button><Button onClick={() => selected && onSelect(selected)} disabled={!selected || loading} loading={loading} leftIcon={<Play className="w-4 h-4" />}>Initialize</Button></div>
    </Modal>
  );
};

// ============================================================================
// FIXED: Create Settlement VA Modal with proper hierarchy tree display
// ============================================================================
interface FlatNode {
  id: string;
  nodeCode: string;
  nodeName: string;
  levelNumber: number;
  depth: number;
  hasChildren: boolean;
}

/**
 * Flatten tree nodes recursively for dropdown display
 */
function flattenTree(nodes: TreeNode[], depth: number = 0): FlatNode[] {
  const result: FlatNode[] = [];
  
  for (const node of nodes) {
    result.push({
      id: node.id,
      nodeCode: node.nodeCode,
      nodeName: node.nodeName,
      levelNumber: node.levelNumber,
      depth: depth,
      hasChildren: (node.children && node.children.length > 0) || false,
    });
    
    // Recursively flatten children
    if (node.children && node.children.length > 0) {
      result.push(...flattenTree(node.children, depth + 1));
    }
  }
  
  return result;
}

const CreateModal: React.FC<{
  isOpen: boolean;
  program: Program | null;
  treeData: TreeResponse | null;
  onClose: () => void;
  onCreate: (d: any) => void;
  loading?: boolean;
}> = ({ isOpen, program, treeData, onClose, onCreate, loading }) => {
  const [form, setForm] = useState({ parentNodeId: '', currency: '', vaName: '' });
  const [flatNodes, setFlatNodes] = useState<FlatNode[]>([]);

  // Initialize form and flatten tree when modal opens
  useEffect(() => {
    if (program) {
      setForm({
        parentNodeId: '',
        currency: program.currencyCode,
        vaName: `${program.programCode} - Settlement`,
      });
    }
  }, [program]);

  // Flatten tree data for dropdown
  useEffect(() => {
    if (treeData) {
      // Handle both 'roots' (array) and 'root' (single node) response formats
      let rootNodes: TreeNode[] = [];
      
      if (treeData.roots && Array.isArray(treeData.roots)) {
        rootNodes = treeData.roots;
      } else if (treeData.root) {
        rootNodes = [treeData.root];
      }
      
      console.log('[CreateModal] Tree roots:', rootNodes);
      const flattened = flattenTree(rootNodes);
      console.log('[CreateModal] Flattened nodes:', flattened);
      setFlatNodes(flattened);
    } else {
      setFlatNodes([]);
    }
  }, [treeData]);

  if (!program || !isOpen) return null;

  return (
    <Modal isOpen={isOpen} onClose={onClose} size="md" title="Create Settlement VA">
      <div className="space-y-4">
        <div className="bg-info-50 border border-info-200 rounded-lg p-3 dark:bg-info-500/10 dark:border-info-500/30">
          <div className="flex gap-2">
            <Info className="w-4 h-4 text-info-600 mt-0.5 dark:text-info-300" />
            <p className="text-sm text-info-800 dark:text-info-300">
              Settlement VAs collect fees from child VAs in the hierarchy. 
              Select the parent node where this Settlement VA will be created.
            </p>
          </div>
        </div>

        <div>
          <label className="field-label block mb-1">Parent Node *</label>
          <select
            className="w-full px-3 py-2 border rounded-lg text-sm"
            value={form.parentNodeId}
            onChange={e => setForm({ ...form, parentNodeId: e.target.value })}
          >
            <option value="">-- Select Parent Node --</option>
            {flatNodes.map(node => (
              <option key={node.id} value={node.id}>
                {'│ '.repeat(node.depth)}
                {node.hasChildren ? '├─ ' : '└─ '}
                L{node.levelNumber}: {node.nodeName} ({node.nodeCode})
              </option>
            ))}
          </select>
          {flatNodes.length === 0 && (
            <p className="text-xs text-warning-600 mt-1 dark:text-warning-300">
              No hierarchy nodes found. Please ensure the hierarchy is initialized.
            </p>
          )}
        </div>

        <div>
          <label className="field-label block mb-1">Currency *</label>
          <CurrencyPicker
            value={form.currency}
            onChange={(c) => setForm({ ...form, currency: c })}
            withName
            extra={['SAR', 'INR']}
          />
        </div>

        <div>
          <label className="field-label block mb-1">VA Name *</label>
          <input
            className="w-full px-3 py-2 border rounded-lg"
            value={form.vaName}
            onChange={e => setForm({ ...form, vaName: e.target.value })}
            placeholder="Enter Settlement VA name"
          />
        </div>

        <div className="flex justify-end gap-2 pt-4 border-t">
          <Button variant="ghost" onClick={onClose}>Cancel</Button>
          <Button
            onClick={() => onCreate(form)}
            disabled={!form.parentNodeId || !form.currency || !form.vaName || loading}
            loading={loading}
            leftIcon={<Plus className="w-4 h-4" />}
          >
            Create Settlement VA
          </Button>
        </div>
      </div>
    </Modal>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================
interface Props { programId?: string; onBack?: () => void; }

const SettlementVaPage: React.FC<Props> = ({ programId: propProgramId, onBack }) => {
  const [programs, setPrograms] = useState<Program[]>([]);
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [program, setProgram] = useState<Program | null>(null);
  const [data, setData] = useState<SettlementVaListResponse | null>(null);
  const [hierarchyLevels, setHierarchyLevels] = useState<HierarchyLevelConfig[]>([]);
  const [treeData, setTreeData] = useState<TreeResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [initLoading, setInitLoading] = useState(false);
  const [createLoading, setCreateLoading] = useState(false);
  const [showTemplateModal, setShowTemplateModal] = useState(false);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [selectedVa, setSelectedVa] = useState<SettlementVa | null>(null);
  const [vaDetail, setVaDetail] = useState<SettlementVaDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [activeTab, setActiveTab] = useState('all');
  const [toasts, setToasts] = useState<Toast[]>([]);

  const loadingRef = useRef(false);
  const programsRef = useRef<Program[]>([]);

  const addToast = (t: Omit<Toast, 'id'>) => { const id = Date.now().toString(); setToasts(p => [...p, { ...t, id }]); setTimeout(() => setToasts(p => p.filter(x => x.id !== id)), 5000); };

  // Load programs ONCE on mount
  useEffect(() => {
    const load = async () => {
      const res = await fetchApi<ProgramListResponse | Program[]>('/programs');
      if (res.success && res.data) {
        let list: Program[] = Array.isArray(res.data) ? res.data : (res.data as ProgramListResponse).programs || [];
        if (list.length > 0) {
          setPrograms(list);
          programsRef.current = list;
          const initialId = propProgramId && isValidUUID(propProgramId) ? propProgramId : list[0].id;
          setSelectedProgramId(initialId);
        }
      }
      setLoading(false);
    };
    load();
  }, [propProgramId]);

  // Load data for selected program
  const loadData = useCallback(async () => {
    if (!selectedProgramId || !isValidUUID(selectedProgramId) || loadingRef.current) return;
    loadingRef.current = true;
    console.log('[SettlementVaPage] Loading data for program:', selectedProgramId);

    const foundProgram = programsRef.current.find(p => p.id === selectedProgramId);
    if (foundProgram) setProgram(foundProgram);

    // Load Settlement VAs
    const vaRes = await fetchApi<SettlementVaListResponse>(`/treasury/settlement-vas?programId=${selectedProgramId}`);
    if (vaRes.success && vaRes.data) {
      setData(vaRes.data);
      if (vaRes.data.programName && !foundProgram) {
        setProgram({ id: selectedProgramId, programCode: vaRes.data.programCode || '', programName: vaRes.data.programName, programType: '', currencyCode: vaRes.data.currencyCode || 'AED', status: 'ACTIVE' });
      }
    } else {
      setData({ settlementVas: [], exceptionVas: [], summary: { totalSettlementVas: 0, totalExceptionVas: 0, totalSettlementBalance: 0, totalExceptionBalance: 0 }, hierarchyInitialized: false });
    }

    // Load hierarchy config
    const levelRes = await fetchApi<HierarchyLevelConfig[]>(`/programs/${selectedProgramId}/hierarchy/config`);
    setHierarchyLevels(levelRes.success && levelRes.data ? levelRes.data : []);

    // Load hierarchy tree - IMPORTANT: Store full TreeResponse
    const treeRes = await fetchApi<TreeResponse>(`/programs/${selectedProgramId}/hierarchy/tree`);
    console.log('[SettlementVaPage] Tree response:', treeRes);
    if (treeRes.success && treeRes.data) {
      setTreeData(treeRes.data);
    } else {
      setTreeData(null);
    }

    loadingRef.current = false;
  }, [selectedProgramId]);

  useEffect(() => { 
    if (selectedProgramId && isValidUUID(selectedProgramId) && !loading) {
      loadData(); 
    }
  }, [selectedProgramId, loading]);

  const handleInit = async (templateId: string) => {
    if (!program || !isValidUUID(program.id)) return;
    setInitLoading(true);
    const res = await fetchApi<SettlementVaListResponse>('/treasury/settlement-vas/initialize', { method: 'POST', body: JSON.stringify({ programId: program.id, templateType: templateId, currencies: [program.currencyCode] }) });
    if (res.success) { addToast({ type: 'success', title: 'Hierarchy Initialized' }); setShowTemplateModal(false); if (res.data) setData(res.data); loadingRef.current = false; loadData(); }
    else addToast({ type: 'error', title: 'Failed', message: res.message });
    setInitLoading(false);
  };

  const handleCreate = async (form: { parentNodeId?: string; currency: string; vaName: string }) => {
    if (!program || !isValidUUID(program.id)) return;
    if (!form.parentNodeId) {
      addToast({ type: 'error', title: 'Validation Error', message: 'Please select a parent node' });
      return;
    }
    setCreateLoading(true);
    const res = await fetchApi<SettlementVa>('/treasury/settlement-vas', { method: 'POST', body: JSON.stringify({ programId: program.id, parentNodeId: form.parentNodeId, currency: form.currency, vaName: form.vaName }) });
    if (res.success) { addToast({ type: 'success', title: 'Created', message: res.data.vaNumber }); setShowCreateModal(false); loadingRef.current = false; loadData(); }
    else addToast({ type: 'error', title: 'Failed', message: res.message });
    setCreateLoading(false);
  };

  const handleDelete = async (va: SettlementVa) => {
    if (!isValidUUID(va.id)) return;
    if (!confirm(`Delete ${va.vaNumber}?`)) return;
    const res = await fetchApi<void>(`/treasury/settlement-vas/${va.id}`, { method: 'DELETE' });
    if (res.success) { addToast({ type: 'success', title: 'Deleted', message: va.vaNumber }); loadingRef.current = false; loadData(); }
    else addToast({ type: 'error', title: 'Failed', message: res.message });
  };

  const loadDetail = async (va: SettlementVa) => {
    if (!isValidUUID(va.id)) return;
    setDetailLoading(true);
    const res = await fetchApi<SettlementVaDetailResponse>(`/treasury/settlement-vas/${va.id}`);
    setVaDetail(res.success ? res.data : { va, recentTransactions: [] });
    setDetailLoading(false);
  };

  const isInit = data?.hierarchyInitialized === true || (data?.exceptionVas?.length || 0) > 0 || hierarchyLevels.length > 0;
  const filtered = !data ? { settlement: [], exception: [] } : activeTab === 'settlement' ? { settlement: data.settlementVas, exception: [] } : activeTab === 'exception' ? { settlement: [], exception: data.exceptionVas } : { settlement: data.settlementVas, exception: data.exceptionVas };
  const total = (data?.settlementVas.length || 0) + (data?.exceptionVas.length || 0);

  if (loading) return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin" /></div>;

  if (programs.length === 0) {
    return (
      <div className="space-y-6">
        <div className="flex items-center gap-4">
          {onBack && <Button variant="ghost" size="sm" onClick={onBack} leftIcon={<ArrowLeft className="w-4 h-4" />}>Back</Button>}
          <PageHeader title="Settlement VA Management" />
        </div>
        <EmptyState icon={<Building2 className="w-8 h-8" />} title="No Programs Found" description="Create a program first to manage Settlement VAs" />
      </div>
    );
  }

  return (
    <Page>
      <ToastContainer toasts={toasts} onDismiss={id => setToasts(p => p.filter(t => t.id !== id))} />

      {/* Quick Actions */}
      <div className="flex items-center justify-between animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <div className="flex items-center gap-4">
          {onBack && <Button variant="ghost" size="sm" onClick={onBack} leftIcon={<ArrowLeft className="w-4 h-4" />}>Back</Button>}
        </div>
        <div className="flex gap-2">
          <Button variant="outline" leftIcon={refreshing ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />} onClick={async () => { setRefreshing(true); loadingRef.current = false; await loadData(); setRefreshing(false); }} disabled={refreshing || !isValidUUID(selectedProgramId)}>Refresh</Button>
          {isInit && <Button variant="primary" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>Add Settlement VA</Button>}
        </div>
      </div>

      {!propProgramId && programs.length > 0 && (
        <Card className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-xl dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
          <div className="p-4 flex items-center gap-4">
            <StatusIconBadge tone="primary" icon={Filter} className="dark:bg-primary-700" />
            <span className="field-label">Program:</span>
            <select value={selectedProgramId} onChange={e => setSelectedProgramId(e.target.value)} className="px-3 py-2 border border-neutral-200 rounded-lg text-sm bg-white focus:ring-2 focus:ring-primary-500 focus:border-primary-500 transition-all dark:border-primary-800 dark:bg-primary-900">
              {programs.map(p => <option key={p.id} value={p.id}>{p.programName} ({p.programType})</option>)}
            </select>
          </div>
        </Card>
      )}

      {program && (
        <Card className="animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="h-1 bg-gradient-to-r from-primary-50/50 via-white to-primary-50/50 rounded-t-xl dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
          <div className="p-4 flex items-center justify-between">
            <div className="flex items-center gap-4">
              <StatusIconBadge tone="primary" icon={Layers} size="lg" className="dark:bg-primary-700" />
              <div><h2 className="font-semibold text-primary-900 dark:text-neutral-50">{program.programName}</h2><p className="text-sm text-neutral-500 dark:text-neutral-400">{program.programCode} • {program.programType} • {program.currencyCode}</p></div>
            </div>
            <div className="flex items-center gap-2">
              {isInit && <Badge variant="success" size="sm"><CheckCircle className="w-3 h-3 mr-1" />Active</Badge>}
              <Badge variant="success">{program.status}</Badge>
            </div>
          </div>
        </Card>
      )}

      {!isInit && program && (
        <Card className="border-2 border-dashed border-warning-300 bg-warning-50 dark:bg-warning-500/10">
          <div className="text-center py-12">
            <AlertTriangle className="w-16 h-16 mx-auto mb-4 text-warning-500" />
            <h2 className="text-xl font-semibold mb-2">Hierarchy Not Initialized</h2>
            <p className="text-neutral-600 mb-6 dark:text-neutral-300">Initialize to create Exception VA.</p>
            <Button size="lg" onClick={() => setShowTemplateModal(true)} leftIcon={<Settings className="w-5 h-5" />}>Initialize Hierarchy</Button>
          </div>
        </Card>
      )}

      {isInit && data && (<>
        <SummaryStats data={data} currency={program?.currencyCode || 'AED'} />
        
        {hierarchyLevels.length > 0 && (
          <Card className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
            <div className="h-1 bg-gradient-to-r from-accent-50/50 via-white to-accent-50/50 rounded-t-xl dark:from-primary-900 dark:via-primary-900 dark:to-primary-900" />
            <div className="p-4 border-b"><h2 className="font-semibold flex items-center gap-2 text-primary-900 dark:text-neutral-50"><GitBranch className="w-5 h-5 text-accent-600 dark:text-accent-300" />Hierarchy ({hierarchyLevels.length} Levels)</h2></div>
            <div className="p-4 flex items-center gap-2 overflow-x-auto">
              {hierarchyLevels.map((l, i) => (
                <React.Fragment key={l.levelNumber}>
                  <div className="shrink-0 px-4 py-3 bg-neutral-50 rounded-xl text-center min-w-[100px] border border-neutral-100 hover:border-primary-200 transition-colors dark:bg-primary-950 dark:border-primary-800/60">
                    <div className="text-xs text-neutral-500 uppercase tracking-wider dark:text-neutral-400">Level {l.levelNumber}</div>
                    <div className="font-medium text-sm text-primary-900 mt-1 dark:text-neutral-50">{l.levelName}</div>
                    <Badge variant="neutral" size="sm" className="mt-2">{l.dimensionType}</Badge>
                  </div>
                  {i < hierarchyLevels.length - 1 && <ChevronRight className="w-5 h-5 text-neutral-300 shrink-0 dark:text-neutral-600" />}
                </React.Fragment>
              ))}
            </div>
          </Card>
        )}
        
        <Tabs tabs={[{ id: 'all', label: 'All', badge: total }, { id: 'settlement', label: 'Settlement', badge: data.settlementVas.length }, { id: 'exception', label: 'Exception', badge: data.exceptionVas.length }]} activeTab={activeTab} onChange={setActiveTab} variant="pills" />
        
        {filtered.settlement.length > 0 && (
          <div className="space-y-3 animate-fade-in" style={{ animationDelay: '0.4s' }}>
            <h3 className="text-lg font-semibold flex items-center gap-2 text-primary-900 dark:text-neutral-50">
              <StatusIconBadge tone="accent" icon={Scale} size="sm" rounded="lg" className="dark:bg-accent-500/20" />
              Settlement VAs
            </h3>
            {filtered.settlement.map(va => <SettlementVaCard key={va.id} va={va} onView={v => { setSelectedVa(v); loadDetail(v); }} onDelete={handleDelete} />)}
          </div>
        )}
        {filtered.exception.length > 0 && (
          <div className="space-y-3 animate-fade-in" style={{ animationDelay: '0.45s' }}>
            <h3 className="text-lg font-semibold flex items-center gap-2 text-primary-900 dark:text-neutral-50">
              <StatusIconBadge tone="warning" icon={AlertTriangle} size="sm" rounded="lg" className="dark:bg-warning-500/20" />
              Exception VAs
            </h3>
            {filtered.exception.map(va => <SettlementVaCard key={va.id} va={va} onView={v => { setSelectedVa(v); loadDetail(v); }} isException />)}
          </div>
        )}
        {total === 0 && <EmptyState icon={<Scale className="w-8 h-8" />} title="No VAs" description="Create Settlement VA" action={<Button variant="primary" leftIcon={<Plus className="w-4 h-4" />} onClick={() => setShowCreateModal(true)}>Create</Button>} />}

        <Card className="animate-fade-in border-info-200 bg-info-50 dark:border-info-500/30 dark:bg-info-500/10" style={{ animationDelay: '0.5s' }}>
          <div className="h-1 bg-gradient-to-r from-info-100 via-info-200 to-info-100 rounded-t-xl" />
          <div className="p-4 flex gap-3">
            <StatusIconBadge tone="info" icon={Info} className="shrink-0 dark:bg-info-500/20" />
            <div>
              <h3 className="font-medium text-info-900">Fee Resolution Flow</h3>
              <ol className="mt-2 text-sm text-info-800 list-decimal list-inside space-y-1 dark:text-info-300">
                <li>Search at same hierarchy level</li>
                <li>Traverse up to parent Settlement VAs</li>
                <li>Fall back to Exception VA</li>
              </ol>
            </div>
          </div>
        </Card>
      </>)}

      {selectedVa && <DetailDrawer va={selectedVa} detail={vaDetail} loading={detailLoading} onClose={() => setSelectedVa(null)} />}
      <TemplateSelectorModal isOpen={showTemplateModal} program={program} onClose={() => setShowTemplateModal(false)} onSelect={handleInit} loading={initLoading} />
      <CreateModal isOpen={showCreateModal} program={program} treeData={treeData} onClose={() => setShowCreateModal(false)} onCreate={handleCreate} loading={createLoading} />
    </Page>
  );
};

export default SettlementVaPage;