import React, { useState, useEffect, useCallback } from 'react';
import {
  Plus, Search, AlertCircle, Loader2,
  Eye, TrendingUp, TrendingDown, Settings,
  CheckCircle2, XCircle, Edit2,
  RefreshCw, Calendar, Building2, Calculator,
  ArrowUpRight, ArrowDownRight, Layers,
  Save, Copy, Building,
} from 'lucide-react';
import { Card, Button, Badge , StatusIconBadge } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { cn, formatDate } from '../utils';
import { interestConfigurationApi, corporatesApi, programsApi, InterestConfiguration, InterestConfigurationStatistics, Corporate } from '../services/api';
import toast from 'react-hot-toast';
import { Page } from '../components/layout/Page';

// ============================================================================
// TYPES
// ============================================================================

type ConfigType = 'EXTERNAL' | 'INTERNAL';
type ConfigStatus = 'DRAFT' | 'PENDING_APPROVAL' | 'ACTIVE' | 'SUSPENDED' | 'EXPIRED' | 'CANCELLED';
type TargetType = 'VIRTUAL_ACCOUNT' | 'PHYSICAL_ACCOUNT' | 'LEGAL_ENTITY' | 'PROGRAM' | 'CURRENCY' | 'CORPORATE';

interface ProgramOption {
  id: string;
  programName: string;
  programCode: string;
  currencyCode: string;
  programType?: string;
  status: string;
  corporateId: string;
}

interface InterestConfigForm {
  configName: string;
  configType: ConfigType;
  targetType: TargetType;
  targetId: string;
  currencyCode: string;
  // Credit Interest
  creditBaseRateType: string;
  creditBaseRate: string;
  creditSpread: string;
  creditMinBalance: string;
  // Debit Interest
  debitBaseRateType: string;
  debitBaseRate: string;
  debitSpread: string;
  penaltyRate: string;
  // Calculation Parameters
  dayCountConvention: string;
  compoundingFrequency: string;
  calculationFrequency: string;
  postingFrequency: string;
  // Tiered Rates
  isTiered: boolean;
  tierConfig: string;
  // Validity
  effectiveFrom: string;
  effectiveTo: string;
  // Status
  status: ConfigStatus;
}

// ============================================================================
// CONSTANTS
// ============================================================================

const CONFIG_TYPES: { value: ConfigType; label: string; color: string; description: string }[] = [
  { value: 'EXTERNAL', label: 'External (Bank)', color: 'blue', description: 'Rates from CBS/Bank - read only' },
  { value: 'INTERNAL', label: 'Internal (Treasury)', color: 'purple', description: 'Transfer pricing rates' },
];

const TARGET_TYPES: { value: TargetType; label: string }[] = [
  { value: 'PROGRAM', label: 'Program' },
  { value: 'CORPORATE', label: 'Corporate (Default)' },
  { value: 'LEGAL_ENTITY', label: 'Legal Entity' },
  { value: 'VIRTUAL_ACCOUNT', label: 'Virtual Account' },
  { value: 'CURRENCY', label: 'Currency Default' },
];

const CONFIG_STATUSES: { value: ConfigStatus; label: string; variant: 'success' | 'warning' | 'error' | 'neutral' }[] = [
  { value: 'ACTIVE', label: 'Active', variant: 'success' },
  { value: 'PENDING_APPROVAL', label: 'Pending Approval', variant: 'warning' },
  { value: 'DRAFT', label: 'Draft', variant: 'neutral' },
  { value: 'SUSPENDED', label: 'Suspended', variant: 'error' },
  { value: 'EXPIRED', label: 'Expired', variant: 'neutral' },
  { value: 'CANCELLED', label: 'Cancelled', variant: 'error' },
];

const DAY_COUNT_OPTIONS = ['ACT/360', 'ACT/365', '30/360', 'ACT/ACT'];
const COMPOUNDING_OPTIONS = ['SIMPLE', 'DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'];
const CALCULATION_FREQ_OPTIONS = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY'];
const POSTING_FREQ_OPTIONS = ['DAILY', 'WEEKLY', 'MONTHLY', 'QUARTERLY', 'SEMI_ANNUAL', 'ANNUAL'];
const BASE_RATE_TYPES = ['EIBOR', 'EIBOR_1M', 'EIBOR_3M', 'EIBOR_6M', 'SOFR', 'EURIBOR', 'EURIBOR_3M', 'SONIA', 'SAIBOR_3M', 'PRIME', 'FIXED'];
const CURRENCIES = ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'INR', 'JPY', 'CHF'];

const DEFAULT_FORM: InterestConfigForm = {
  configName: '',
  configType: 'INTERNAL',
  targetType: 'PROGRAM',
  targetId: '',
  currencyCode: 'AED',
  creditBaseRateType: 'EIBOR',
  creditBaseRate: '5.000',
  creditSpread: '-0.250',
  creditMinBalance: '0',
  debitBaseRateType: 'EIBOR',
  debitBaseRate: '5.000',
  debitSpread: '0.500',
  penaltyRate: '2.000',
  dayCountConvention: 'ACT/360',
  compoundingFrequency: 'DAILY',
  calculationFrequency: 'DAILY',
  postingFrequency: 'MONTHLY',
  isTiered: false,
  tierConfig: '',
  effectiveFrom: new Date().toISOString().split('T')[0],
  effectiveTo: '',
  status: 'DRAFT',
};

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

const getStatusVariant = (status: ConfigStatus): 'success' | 'warning' | 'error' | 'neutral' => {
  return CONFIG_STATUSES.find(s => s.value === status)?.variant || 'neutral';
};

const formatRate = (rate?: number | string): string => {
  if (rate === undefined || rate === null || rate === '') return '-';
  const num = typeof rate === 'string' ? parseFloat(rate) : rate;
  return `${num.toFixed(3)}%`;
};

// ============================================================================
// SUB-COMPONENTS
// ============================================================================

const StatCard: React.FC<{
  title: string;
  value: string | number;
  subtitle?: string;
  icon: React.ReactNode;
  trend?: 'up' | 'down';
  color?: string;
}> = ({ title, value, subtitle, icon, trend, color = 'info' }) => (
  <Card hover className="p-4">
    <div className="flex items-start justify-between">
      <div className="flex-1">
        <p className="label">{title}</p>
        <p className="stat-value-sm mt-1">{value}</p>
        {subtitle && (
          <p className={cn(
            "text-xs mt-1 flex items-center gap-1",
            trend === 'up' ? 'text-success-600 dark:text-success-300' : trend === 'down' ? 'text-error-600 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400'
          )}>
            {trend === 'up' && <ArrowUpRight className="w-3 h-3" />}
            {trend === 'down' && <ArrowDownRight className="w-3 h-3" />}
            {subtitle}
          </p>
        )}
      </div>
      <div className={cn(
        "w-10 h-10 rounded-xl flex items-center justify-center",
        color === 'info' && 'bg-info-100 text-info-600 dark:bg-info-500/20 dark:text-info-300',
        color === 'success' && 'bg-success-100 text-success-600 dark:bg-success-500/20 dark:text-success-300',
        color === 'accent' && 'bg-accent-100 text-accent-600 dark:bg-accent-500/20 dark:text-accent-300',
        color === 'error' && 'bg-error-100 text-error-600 dark:bg-error-500/20 dark:text-error-300',
        color === 'primary' && 'bg-primary-100 text-primary-600 dark:bg-primary-700 dark:text-primary-200',
      )}>
        {icon}
      </div>
    </div>
  </Card>
);

const ConfigCard: React.FC<{
  config: InterestConfiguration;
  onView: () => void;
  onEdit: () => void;
  onDuplicate: () => void;
}> = ({ config, onView, onEdit, onDuplicate }) => (
  <Card hover className="p-4">
    <div className="flex items-start justify-between mb-3">
      <div className="flex items-center gap-2">
        <div className={cn(
          "w-10 h-10 rounded-xl flex items-center justify-center",
          config.configType === 'EXTERNAL' ? 'bg-info-100 text-info-600 dark:bg-info-500/20 dark:text-info-300' : 'bg-accent-100 text-accent-600 dark:bg-accent-500/20 dark:text-accent-300'
        )}>
          {config.configType === 'EXTERNAL' ? <Building2 className="w-5 h-5" /> : <Layers className="w-5 h-5" />}
        </div>
        <div>
          <h4 className="font-semibold text-primary-900 dark:text-neutral-50">{config.configName || 'Unnamed Config'}</h4>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{config.currencyCode} • {config.targetType || 'CORPORATE'}</p>
        </div>
      </div>
      <Badge variant={getStatusVariant(config.status as ConfigStatus)} size="sm">
        {config.status}
      </Badge>
    </div>

    <div className="grid grid-cols-2 gap-3 mb-3">
      <div className="bg-success-50 rounded-xl p-3 dark:bg-success-500/10">
        <p className="text-xs text-success-600 mb-1 flex items-center gap-1 font-medium uppercase tracking-wider dark:text-success-300">
          <TrendingUp className="w-3 h-3" /> Credit
        </p>
        <p className="font-bold text-success-700 text-lg dark:text-success-300">{formatRate(config.effectiveCreditRate)}</p>
        {config.creditBaseRateType && (
          <p className="text-xs text-success-600 mt-1 dark:text-success-300">
            {config.creditBaseRateType}
            {config.creditSpread !== undefined && config.creditSpread !== null && (
              <span className="ml-1">
                {Number(config.creditSpread) >= 0 ? '+' : ''}{formatRate(config.creditSpread)}
              </span>
            )}
          </p>
        )}
      </div>
      <div className="bg-error-50 rounded-xl p-3 dark:bg-error-500/10">
        <p className="text-xs text-error-600 mb-1 flex items-center gap-1 font-medium uppercase tracking-wider dark:text-error-300">
          <TrendingDown className="w-3 h-3" /> Debit
        </p>
        <p className="font-bold text-error-700 text-lg dark:text-error-300">{formatRate(config.effectiveDebitRate)}</p>
        {config.debitBaseRateType && (
          <p className="text-xs text-error-600 mt-1 dark:text-error-300">
            {config.debitBaseRateType}
            {config.debitSpread !== undefined && config.debitSpread !== null && (
              <span className="ml-1">
                {Number(config.debitSpread) >= 0 ? '+' : ''}{formatRate(config.debitSpread)}
              </span>
            )}
          </p>
        )}
      </div>
    </div>

    <div className="flex items-center justify-between text-xs text-neutral-500 border-t pt-3 dark:text-neutral-400">
      <div className="flex items-center gap-1">
        <Calendar className="w-3 h-3" />
        <span>{formatDate(config.effectiveFrom)}</span>
      </div>
      <div className="font-medium">{config.dayCountConvention || 'ACT/360'}</div>
    </div>

    <div className="flex gap-2 mt-3 pt-3 border-t">
      <Button variant="ghost" size="sm" className="flex-1" onClick={(e) => { e.stopPropagation(); onView(); }}>
        <Eye className="w-4 h-4 mr-1" /> View
      </Button>
      <Button variant="ghost" size="sm" className="flex-1" onClick={(e) => { e.stopPropagation(); onEdit(); }}>
        <Edit2 className="w-4 h-4 mr-1" /> Edit
      </Button>
      <Button variant="ghost" size="sm" onClick={(e) => { e.stopPropagation(); onDuplicate(); }}>
        <Copy className="w-4 h-4" />
      </Button>
    </div>
  </Card>
);

// ============================================================================
// CORPORATE PROGRAM FILTER BAR (Same as TreasuryHierarchyPage)
// ============================================================================

interface CorporateProgramFilterBarProps {
  corporates: Corporate[];
  programs: ProgramOption[];
  selectedCorporateId: string;
  selectedProgramId: string;
  onCorporateChange: (id: string) => void;
  onProgramChange: (id: string) => void;
  loading?: boolean;
}

const CorporateProgramFilterBar: React.FC<CorporateProgramFilterBarProps> = ({
  corporates,
  programs,
  selectedCorporateId,
  selectedProgramId,
  onCorporateChange,
  onProgramChange,
  loading,
}) => {
  const selectedCorporate = corporates.find(c => c.id === selectedCorporateId);
  const selectedProgram = programs.find(p => p.id === selectedProgramId);
  const filteredPrograms = programs.filter(p => 
    !selectedCorporateId || p.corporateId === selectedCorporateId
  );
  const activePrograms = filteredPrograms.filter(p => p.status === 'ACTIVE');

  return (
    <Card padding="sm" className="bg-gradient-to-r from-primary-50 to-info-50 border-primary-200 dark:border-primary-700 dark:from-primary-500/15 dark:to-info-500/15">
      <div className="flex items-center gap-4 flex-wrap">
        {/* Corporate Selector */}
        <div className="flex items-center gap-2">
          <div className="p-2 bg-primary-100 rounded-lg dark:bg-primary-700">
            <Building className="w-5 h-5 text-primary-700 dark:text-neutral-200" />
          </div>
          <div className="min-w-[200px]">
            <label className="text-xs font-medium text-primary-700 uppercase tracking-wide dark:text-neutral-200">Corporate</label>
            <select
              value={selectedCorporateId}
              onChange={(e) => onCorporateChange(e.target.value)}
              className="w-full mt-0.5 px-2 py-1.5 bg-white border border-primary-200 rounded-lg text-sm font-medium focus:ring-2 focus:ring-primary-500 dark:bg-primary-900 dark:border-primary-700"
              disabled={loading}
            >
              <option value="">Select Corporate...</option>
              {corporates.map(corp => (
                <option key={corp.id} value={corp.id}>
                  {corp.tradeName || corp.legalName}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="h-10 w-px bg-primary-200" />

        {/* Program Selector */}
        <div className="flex items-center gap-2">
          <div className="p-2 bg-info-100 rounded-lg dark:bg-info-500/20">
            <Layers className="w-5 h-5 text-info-700 dark:text-info-300" />
          </div>
          <div className="min-w-[250px]">
            <label className="text-xs font-medium text-info-700 uppercase tracking-wide dark:text-info-300">Program</label>
            <select
              value={selectedProgramId}
              onChange={(e) => onProgramChange(e.target.value)}
              className="w-full mt-0.5 px-2 py-1.5 bg-white border border-info-200 rounded-lg text-sm font-medium focus:ring-2 focus:ring-info-500 dark:bg-primary-900 dark:border-info-500/30"
              disabled={loading || activePrograms.length === 0}
            >
              <option value="">Select Program...</option>
              {activePrograms.map(program => (
                <option key={program.id} value={program.id}>
                  {program.programName} ({program.programCode}) - {program.currencyCode}
                </option>
              ))}
            </select>
          </div>
        </div>

        <div className="h-10 w-px bg-neutral-300" />

        {/* Selected Badges */}
        <div className="flex-1 flex items-center gap-3">
          {selectedCorporate && (
            <Badge variant="info" size="sm">
              <Building className="w-3 h-3 mr-1" />
              {selectedCorporate.shortName || selectedCorporate.tradeName || selectedCorporate.legalName}
            </Badge>
          )}
          {selectedProgram && (
            <Badge variant="info" size="sm">
              <Layers className="w-3 h-3 mr-1" />
              {selectedProgram.programCode} ({selectedProgram.currencyCode})
            </Badge>
          )}
          {!selectedCorporateId && !selectedProgramId && (
            <span className="text-sm text-neutral-500 italic dark:text-neutral-400">Select a corporate and program</span>
          )}
        </div>

        {loading && <Loader2 className="w-5 h-5 text-primary-600 animate-spin dark:text-primary-200" />}
      </div>
    </Card>
  );
};

// ============================================================================
// MAIN COMPONENT
// ============================================================================

const InterestConfigurationPage: React.FC = () => {
  // State
  const [configs, setConfigs] = useState<InterestConfiguration[]>([]);
  const [statistics, setStatistics] = useState<InterestConfigurationStatistics | null>(null);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [loadingPrograms, setLoadingPrograms] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [searchTerm, setSearchTerm] = useState('');
  const [filterType, setFilterType] = useState<ConfigType | 'ALL'>('ALL');
  const [filterStatus, setFilterStatus] = useState<ConfigStatus | 'ALL'>('ALL');
  
  // Modals
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [selectedConfig, setSelectedConfig] = useState<InterestConfiguration | null>(null);
  
  // Form
  const [form, setForm] = useState<InterestConfigForm>(DEFAULT_FORM);
  const [isEditing, setIsEditing] = useState(false);

  // Load corporates on mount
  useEffect(() => {
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadPrograms();
    } else {
      setPrograms([]);
      setSelectedProgramId('');
    }
  }, [selectedCorporateId]);

  // Load configs when corporate/program changes
  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    }
  }, [selectedCorporateId, selectedProgramId]);

  const loadCorporates = async () => {
    try {
      const response = await corporatesApi.getAll();
      const data = response?.data || response;
      const corporateList = Array.isArray(data) ? data : [];
      setCorporates(corporateList);
      if (corporateList.length > 0) {
        setSelectedCorporateId(corporateList[0].id);
      }
    } catch (err) {
      console.error('Failed to load corporates:', err);
      setError('Failed to load corporates');
    } finally {
      setLoading(false);
    }
  };

  const loadPrograms = async () => {
    if (!selectedCorporateId) return;
    
    try {
      setLoadingPrograms(true);
      const response = await programsApi.getAll({ corporateId: selectedCorporateId });
      
      // Handle different response structures
      let programsData: any[] = [];
      if (response?.success && response?.data) {
        if (response.data.programs && Array.isArray(response.data.programs)) {
          programsData = response.data.programs;
        } else if (response.data.content && Array.isArray(response.data.content)) {
          programsData = response.data.content;
        } else if (Array.isArray(response.data)) {
          programsData = response.data;
        }
      } else if (Array.isArray(response)) {
        programsData = response;
      }
      
      console.log('[InterestConfig] Programs loaded:', programsData);

      const programList: ProgramOption[] = programsData.map((p: any) => ({
        id: p.id,
        programName: p.programName,
        programCode: p.programCode,
        currencyCode: p.currencyCode || 'AED',
        programType: p.programType,
        status: p.status,
        corporateId: p.corporateId,
      }));
      setPrograms(programList);

      // Auto-select first active program
      const activePrograms = programList.filter(p => p.status === 'ACTIVE');
      if (activePrograms.length > 0 && !selectedProgramId) {
        setSelectedProgramId(activePrograms[0].id);
      }
    } catch (err) {
      console.error('Failed to load programs:', err);
      setPrograms([]);
    } finally {
      setLoadingPrograms(false);
    }
  };

  const loadData = useCallback(async () => {
    if (!selectedCorporateId) return;
    
    setLoading(true);
    setError(null);
    try {
      const [configsRes, statsRes] = await Promise.all([
        interestConfigurationApi.getActiveByCorporate(selectedCorporateId),
        interestConfigurationApi.getStatistics(selectedCorporateId),
      ]);
      
      const configsData = configsRes?.data || configsRes || [];
      const statsData = statsRes?.data || statsRes || null;
      
      // Filter by program if selected
      let filteredConfigs = Array.isArray(configsData) ? configsData : [];
      if (selectedProgramId) {
        filteredConfigs = filteredConfigs.filter(c => 
          c.targetId === selectedProgramId || c.targetType === 'CORPORATE'
        );
      }
      
      setConfigs(filteredConfigs);
      setStatistics(statsData);
    } catch (err) {
      console.error('Failed to load data:', err);
      setError('Failed to load interest configurations.');
      setConfigs([]);
      setStatistics(null);
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedProgramId]);

  // Filter configs
  const filteredConfigs = configs.filter(config => {
    if (searchTerm && !config.configName?.toLowerCase().includes(searchTerm.toLowerCase()) &&
        !config.currencyCode?.toLowerCase().includes(searchTerm.toLowerCase())) {
      return false;
    }
    if (filterType !== 'ALL' && config.configType !== filterType) return false;
    if (filterStatus !== 'ALL' && config.status !== filterStatus) return false;
    return true;
  });

  // Handlers
  const handleCorporateChange = (id: string) => {
    setSelectedCorporateId(id);
    setSelectedProgramId('');
    setPrograms([]);
  };

  const handleView = (config: InterestConfiguration) => {
    setSelectedConfig(config);
    setShowDetailModal(true);
  };

  const handleEdit = (config: InterestConfiguration) => {
    setSelectedConfig(config);
    setForm({
      configName: config.configName || '',
      configType: config.configType as ConfigType,
      targetType: (config.targetType as TargetType) || 'PROGRAM',
      targetId: config.targetId || selectedProgramId,
      currencyCode: config.currencyCode || 'AED',
      creditBaseRateType: config.creditBaseRateType || 'EIBOR',
      creditBaseRate: config.creditBaseRate?.toString() || '',
      creditSpread: config.creditSpread?.toString() || '',
      creditMinBalance: config.creditMinBalance?.toString() || '0',
      debitBaseRateType: config.debitBaseRateType || 'EIBOR',
      debitBaseRate: config.debitBaseRate?.toString() || '',
      debitSpread: config.debitSpread?.toString() || '',
      penaltyRate: config.penaltyRate?.toString() || '',
      dayCountConvention: config.dayCountConvention || 'ACT/360',
      compoundingFrequency: config.compoundingFrequency || 'DAILY',
      calculationFrequency: config.calculationFrequency || 'DAILY',
      postingFrequency: config.postingFrequency || 'MONTHLY',
      isTiered: config.isTiered || false,
      tierConfig: config.tierConfig || '',
      effectiveFrom: config.effectiveFrom || new Date().toISOString().split('T')[0],
      effectiveTo: config.effectiveTo || '',
      status: (config.status as ConfigStatus) || 'DRAFT',
    });
    setIsEditing(true);
    setShowCreateModal(true);
  };

  const handleCreate = () => {
    const selectedProgram = programs.find(p => p.id === selectedProgramId);
    setSelectedConfig(null);
    setForm({
      ...DEFAULT_FORM,
      targetId: selectedProgramId,
      currencyCode: selectedProgram?.currencyCode || 'AED',
    });
    setIsEditing(false);
    setShowCreateModal(true);
  };

  const handleDuplicate = (config: InterestConfiguration) => {
    setSelectedConfig(null);
    setForm({
      configName: `${config.configName} (Copy)`,
      configType: 'INTERNAL',
      targetType: (config.targetType as TargetType) || 'PROGRAM',
      targetId: config.targetId || selectedProgramId,
      currencyCode: config.currencyCode || 'AED',
      creditBaseRateType: config.creditBaseRateType || 'EIBOR',
      creditBaseRate: config.creditBaseRate?.toString() || '',
      creditSpread: config.creditSpread?.toString() || '',
      creditMinBalance: config.creditMinBalance?.toString() || '0',
      debitBaseRateType: config.debitBaseRateType || 'EIBOR',
      debitBaseRate: config.debitBaseRate?.toString() || '',
      debitSpread: config.debitSpread?.toString() || '',
      penaltyRate: config.penaltyRate?.toString() || '',
      dayCountConvention: config.dayCountConvention || 'ACT/360',
      compoundingFrequency: config.compoundingFrequency || 'DAILY',
      calculationFrequency: config.calculationFrequency || 'DAILY',
      postingFrequency: config.postingFrequency || 'MONTHLY',
      isTiered: config.isTiered || false,
      tierConfig: config.tierConfig || '',
      effectiveFrom: new Date().toISOString().split('T')[0],
      effectiveTo: '',
      status: 'DRAFT',
    });
    setIsEditing(false);
    setShowCreateModal(true);
  };

  const handleSave = async () => {
    if (!form.configName) {
      toast.error('Configuration name is required');
      return;
    }
    
    setSaving(true);
    try {
      const payload = {
        corporateId: selectedCorporateId,
        configName: form.configName,
        configType: form.configType,
        targetType: form.targetType,
        targetId: form.targetId || null,
        currencyCode: form.currencyCode,
        creditBaseRateType: form.creditBaseRateType || null,
        creditBaseRate: form.creditBaseRate ? parseFloat(form.creditBaseRate) : null,
        creditSpread: form.creditSpread ? parseFloat(form.creditSpread) : null,
        creditMinBalance: form.creditMinBalance ? parseFloat(form.creditMinBalance) : null,
        debitBaseRateType: form.debitBaseRateType || null,
        debitBaseRate: form.debitBaseRate ? parseFloat(form.debitBaseRate) : null,
        debitSpread: form.debitSpread ? parseFloat(form.debitSpread) : null,
        penaltyRate: form.penaltyRate ? parseFloat(form.penaltyRate) : null,
        dayCountConvention: form.dayCountConvention,
        compoundingFrequency: form.compoundingFrequency,
        calculationFrequency: form.calculationFrequency,
        postingFrequency: form.postingFrequency,
        isTiered: form.isTiered,
        tierConfig: form.tierConfig || null,
        effectiveFrom: form.effectiveFrom,
        effectiveTo: form.effectiveTo || null,
        status: form.status,
      };

      if (isEditing && selectedConfig) {
        await interestConfigurationApi.update(selectedConfig.id, payload);
        toast.success('Configuration updated');
      } else {
        await interestConfigurationApi.create(payload);
        toast.success('Configuration created');
      }
      
      setShowCreateModal(false);
      loadData();
    } catch (err: any) {
      console.error('Failed to save:', err);
      toast.error(err.response?.data?.message || 'Failed to save configuration');
    } finally {
      setSaving(false);
    }
  };

  const handleActivate = async (config: InterestConfiguration) => {
    try {
      await interestConfigurationApi.activate(config.id);
      toast.success('Configuration activated');
      loadData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to activate');
    }
  };

  const handleSuspend = async (config: InterestConfiguration) => {
    try {
      await interestConfigurationApi.suspend(config.id);
      toast.success('Configuration suspended');
      loadData();
    } catch (err: any) {
      toast.error(err.response?.data?.message || 'Failed to suspend');
    }
  };

  // Render
  return (
    <Page>
      {/* Quick Actions */}
      <div className="flex items-center justify-end gap-2 animate-fade-in" style={{ animationDelay: '0.05s' }}>
          <Button variant="outline" size="sm" onClick={() => loadData()}>
            <RefreshCw className="w-4 h-4" />
          </Button>
          <Button size="sm" onClick={handleCreate} disabled={!selectedCorporateId}>
            <Plus className="w-4 h-4 mr-2" /> New Config
          </Button>
      </div>

      {/* Corporate/Program Filter Bar */}
      <CorporateProgramFilterBar
        corporates={corporates}
        programs={programs}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={handleCorporateChange}
        onProgramChange={setSelectedProgramId}
        loading={loadingPrograms}
      />

      {/* Statistics */}
      {statistics && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-4 animate-fade-in" style={{ animationDelay: '0.15s' }}>
          <StatCard
            title="Total Configurations"
            value={statistics.totalConfigs || 0}
            icon={<Settings className="w-5 h-5" />}
            color="info"
          />
          <StatCard
            title="External (Bank)"
            value={statistics.externalConfigs || 0}
            icon={<Building2 className="w-5 h-5" />}
            color="primary"
          />
          <StatCard
            title="Internal (Treasury)"
            value={statistics.internalConfigs || 0}
            icon={<Layers className="w-5 h-5" />}
            color="accent"
          />
          <StatCard
            title="Avg Credit Rate"
            value={formatRate(statistics.avgCreditRate)}
            icon={<TrendingUp className="w-5 h-5" />}
            color="success"
          />
          <StatCard
            title="Avg Debit Rate"
            value={formatRate(statistics.avgDebitRate)}
            icon={<TrendingDown className="w-5 h-5" />}
            color="error"
          />
        </div>
      )}

      {/* Filters */}
      <Card className="p-4 animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="flex flex-wrap items-center gap-4">
          <div className="flex-1 min-w-[200px]">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400 dark:text-neutral-500" />
              <input
                type="text"
                placeholder="Search configurations..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="w-full pl-10 pr-4 py-2 border border-neutral-300 rounded-lg text-sm font-medium focus:ring-2 focus:ring-primary-500 focus:border-primary-500 dark:border-primary-700"
              />
            </div>
          </div>
          <select
            value={filterType}
            onChange={(e) => setFilterType(e.target.value as any)}
            className="px-3 py-2 border border-neutral-300 rounded-lg text-sm font-medium bg-white min-w-[140px] dark:border-primary-700 dark:bg-primary-900"
          >
            <option value="ALL">All Types</option>
            {CONFIG_TYPES.map(type => (
              <option key={type.value} value={type.value}>{type.label}</option>
            ))}
          </select>
          <select
            value={filterStatus}
            onChange={(e) => setFilterStatus(e.target.value as any)}
            className="px-3 py-2 border border-neutral-300 rounded-lg text-sm font-medium bg-white min-w-[140px] dark:border-primary-700 dark:bg-primary-900"
          >
            <option value="ALL">All Statuses</option>
            {CONFIG_STATUSES.map(status => (
              <option key={status.value} value={status.value}>{status.label}</option>
            ))}
          </select>
        </div>
      </Card>

      {/* Loading / Error */}
      {loading && (
        <div className="flex items-center justify-center py-12">
          <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
        </div>
      )}

      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="error" icon={AlertCircle} className="dark:bg-error-500/20" />
              <span className="text-error-700 font-medium dark:text-error-300">{error}</span>
            </div>
            <button onClick={() => setError(null)} className="text-error-500 hover:text-error-700 p-1">×</button>
          </div>
        </Card>
      )}

      {/* Configurations Grid */}
      {!loading && (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4 animate-fade-in" style={{ animationDelay: '0.25s' }}>
          {filteredConfigs.map((config, index) => (
            <div key={config.id} className="animate-fade-in" style={{ animationDelay: `${0.3 + index * 0.03}s` }}>
              <ConfigCard
                config={config}
                onView={() => handleView(config)}
                onEdit={() => handleEdit(config)}
                onDuplicate={() => handleDuplicate(config)}
              />
            </div>
          ))}
          {filteredConfigs.length === 0 && !error && (
            <div className="col-span-full text-center py-12">
              <div className="w-16 h-16 rounded-xl bg-neutral-100 flex items-center justify-center mx-auto mb-4 dark:bg-primary-800">
                <Settings className="w-8 h-8 text-neutral-400 dark:text-neutral-500" />
              </div>
              <p className="text-neutral-500 font-medium dark:text-neutral-400">No interest configurations found</p>
              <Button variant="outline" className="mt-4" onClick={handleCreate} disabled={!selectedCorporateId}>
                <Plus className="w-4 h-4 mr-2" /> Create First Configuration
              </Button>
            </div>
          )}
        </div>
      )}

      {/* Detail Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title="Interest Configuration Details"
        size="lg"
      >
        {selectedConfig && (
          <div className="space-y-6">
            {/* Header */}
            <div className="flex items-start justify-between">
              <div>
                <h3 className="text-lg font-semibold">{selectedConfig.configName}</h3>
                <p className="text-sm text-neutral-500 dark:text-neutral-400">
                  {selectedConfig.currencyCode} • {selectedConfig.configType} • {selectedConfig.targetType || 'CORPORATE'}
                </p>
              </div>
              <Badge variant={getStatusVariant(selectedConfig.status as ConfigStatus)}>
                {selectedConfig.status}
              </Badge>
            </div>

            {/* Rates */}
            <div className="grid grid-cols-2 gap-4">
              <div className="bg-success-50 rounded-xl p-4 dark:bg-success-500/10">
                <h4 className="text-sm font-semibold text-success-700 mb-3 uppercase tracking-wider dark:text-success-300">Credit Interest</h4>
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between">
                    <span className="text-success-600 dark:text-success-300">Base Rate Type</span>
                    <span className="font-medium">{selectedConfig.creditBaseRateType || '-'}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-success-600 dark:text-success-300">Base Rate</span>
                    <span className="font-medium">{formatRate(selectedConfig.creditBaseRate)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-success-600 dark:text-success-300">Spread</span>
                    <span className="font-medium">{formatRate(selectedConfig.creditSpread)}</span>
                  </div>
                  <div className="flex justify-between pt-2 border-t border-success-200 dark:border-success-500/30">
                    <span className="font-semibold">Effective Rate</span>
                    <span className="font-bold text-success-700 text-lg dark:text-success-300">{formatRate(selectedConfig.effectiveCreditRate)}</span>
                  </div>
                </div>
              </div>

              <div className="bg-error-50 rounded-xl p-4 dark:bg-error-500/10">
                <h4 className="text-sm font-semibold text-error-700 mb-3 uppercase tracking-wider dark:text-error-300">Debit Interest</h4>
                <div className="space-y-2 text-sm">
                  <div className="flex justify-between">
                    <span className="text-error-600 dark:text-error-300">Base Rate Type</span>
                    <span className="font-medium">{selectedConfig.debitBaseRateType || '-'}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-error-600 dark:text-error-300">Base Rate</span>
                    <span className="font-medium">{formatRate(selectedConfig.debitBaseRate)}</span>
                  </div>
                  <div className="flex justify-between">
                    <span className="text-error-600 dark:text-error-300">Spread</span>
                    <span className="font-medium">{formatRate(selectedConfig.debitSpread)}</span>
                  </div>
                  <div className="flex justify-between pt-2 border-t border-error-200 dark:border-error-500/30">
                    <span className="font-semibold">Effective Rate</span>
                    <span className="font-bold text-error-700 text-lg dark:text-error-300">{formatRate(selectedConfig.effectiveDebitRate)}</span>
                  </div>
                </div>
              </div>
            </div>

            {/* Calculation Parameters */}
            <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
              <h4 className="text-sm font-medium mb-3">Calculation Parameters</h4>
              <div className="grid grid-cols-2 gap-4 text-sm">
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Day Count</span>
                  <span className="font-medium">{selectedConfig.dayCountConvention || 'ACT/360'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Compounding</span>
                  <span className="font-medium">{selectedConfig.compoundingFrequency || 'DAILY'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Calculation</span>
                  <span className="font-medium">{selectedConfig.calculationFrequency || 'DAILY'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Posting</span>
                  <span className="font-medium">{selectedConfig.postingFrequency || 'MONTHLY'}</span>
                </div>
              </div>
            </div>

            {/* Validity */}
            <div className="flex items-center justify-between text-sm">
              <div>
                <span className="text-neutral-500 dark:text-neutral-400">Effective From: </span>
                <span className="font-medium">{formatDate(selectedConfig.effectiveFrom)}</span>
              </div>
              {selectedConfig.effectiveTo && (
                <div>
                  <span className="text-neutral-500 dark:text-neutral-400">Effective To: </span>
                  <span className="font-medium">{formatDate(selectedConfig.effectiveTo)}</span>
                </div>
              )}
            </div>

            {/* Actions */}
            <div className="flex gap-3 pt-4 border-t">
              {selectedConfig.status === 'DRAFT' && (
                <Button className="flex-1" onClick={() => handleActivate(selectedConfig)}>
                  <CheckCircle2 className="w-4 h-4 mr-2" /> Activate
                </Button>
              )}
              {selectedConfig.status === 'ACTIVE' && (
                <Button variant="outline" className="flex-1" onClick={() => handleSuspend(selectedConfig)}>
                  <XCircle className="w-4 h-4 mr-2" /> Suspend
                </Button>
              )}
              <Button variant="outline" className="flex-1" onClick={() => {
                setShowDetailModal(false);
                handleEdit(selectedConfig);
              }}>
                <Edit2 className="w-4 h-4 mr-2" /> Edit
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Create/Edit Modal */}
      <Modal
        isOpen={showCreateModal}
        onClose={() => setShowCreateModal(false)}
        title={isEditing ? 'Edit Interest Configuration' : 'New Interest Configuration'}
        size="xl"
      >
        <div className="space-y-6 max-h-[70vh] overflow-y-auto p-1">
          {/* Basic Info */}
          <div className="grid grid-cols-2 gap-4">
            <div className="col-span-2">
              <label className="field-label block mb-1">Configuration Name *</label>
              <input
                type="text"
                value={form.configName}
                onChange={(e) => setForm(prev => ({ ...prev, configName: e.target.value }))}
                placeholder="e.g., AED Treasury Internal Rate"
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
              />
            </div>
            <div>
              <label className="field-label block mb-1">Type</label>
              <select
                value={form.configType}
                onChange={(e) => setForm(prev => ({ ...prev, configType: e.target.value as ConfigType }))}
                disabled={isEditing}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
              >
                {CONFIG_TYPES.map(t => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">Currency</label>
              <select
                value={form.currencyCode}
                onChange={(e) => setForm(prev => ({ ...prev, currencyCode: e.target.value }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
              >
                {CURRENCIES.map(c => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">Target Type</label>
              <select
                value={form.targetType}
                onChange={(e) => setForm(prev => ({ ...prev, targetType: e.target.value as TargetType }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
              >
                {TARGET_TYPES.map(t => (
                  <option key={t.value} value={t.value}>{t.label}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">Status</label>
              <select
                value={form.status}
                onChange={(e) => setForm(prev => ({ ...prev, status: e.target.value as ConfigStatus }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
              >
                {CONFIG_STATUSES.map(s => (
                  <option key={s.value} value={s.value}>{s.label}</option>
                ))}
              </select>
            </div>
          </div>

          {/* Credit Interest */}
          <div className="bg-success-50 rounded-xl p-4 dark:bg-success-500/10">
            <h4 className="text-sm font-semibold text-success-800 mb-4 flex items-center gap-2 dark:text-success-300">
              <TrendingUp className="w-4 h-4" /> Credit Interest (on positive balances)
            </h4>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Base Rate Type</label>
                <select
                  value={form.creditBaseRateType}
                  onChange={(e) => setForm(prev => ({ ...prev, creditBaseRateType: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {BASE_RATE_TYPES.map(t => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Base Rate (%)</label>
                <input
                  type="number"
                  step="0.001"
                  value={form.creditBaseRate}
                  onChange={(e) => setForm(prev => ({ ...prev, creditBaseRate: e.target.value }))}
                  placeholder="5.000"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
              </div>
              <div>
                <label className="field-label block mb-1">Spread (%)</label>
                <input
                  type="number"
                  step="0.001"
                  value={form.creditSpread}
                  onChange={(e) => setForm(prev => ({ ...prev, creditSpread: e.target.value }))}
                  placeholder="-0.250"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Negative = treasury pays less</p>
              </div>
              <div>
                <label className="field-label block mb-1">Min Balance</label>
                <input
                  type="number"
                  value={form.creditMinBalance}
                  onChange={(e) => setForm(prev => ({ ...prev, creditMinBalance: e.target.value }))}
                  placeholder="0"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
              </div>
            </div>
          </div>

          {/* Debit Interest */}
          <div className="bg-error-50 rounded-xl p-4 dark:bg-error-500/10">
            <h4 className="text-sm font-semibold text-error-800 mb-4 flex items-center gap-2 dark:text-error-300">
              <TrendingDown className="w-4 h-4" /> Debit Interest (on negative/overdraft)
            </h4>
            <div className="grid grid-cols-3 gap-4">
              <div>
                <label className="field-label block mb-1">Base Rate Type</label>
                <select
                  value={form.debitBaseRateType}
                  onChange={(e) => setForm(prev => ({ ...prev, debitBaseRateType: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {BASE_RATE_TYPES.map(t => (
                    <option key={t} value={t}>{t}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Base Rate (%)</label>
                <input
                  type="number"
                  step="0.001"
                  value={form.debitBaseRate}
                  onChange={(e) => setForm(prev => ({ ...prev, debitBaseRate: e.target.value }))}
                  placeholder="5.000"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
              </div>
              <div>
                <label className="field-label block mb-1">Spread (%)</label>
                <input
                  type="number"
                  step="0.001"
                  value={form.debitSpread}
                  onChange={(e) => setForm(prev => ({ ...prev, debitSpread: e.target.value }))}
                  placeholder="0.500"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">Positive = treasury charges more</p>
              </div>
              <div>
                <label className="field-label block mb-1">Penalty Rate (%)</label>
                <input
                  type="number"
                  step="0.001"
                  value={form.penaltyRate}
                  onChange={(e) => setForm(prev => ({ ...prev, penaltyRate: e.target.value }))}
                  placeholder="2.000"
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
                />
                <p className="text-xs text-neutral-500 mt-1 dark:text-neutral-400">For unauthorized overdraft</p>
              </div>
            </div>
          </div>

          {/* Calculation Parameters */}
          <div className="bg-neutral-50 rounded-lg p-4 dark:bg-primary-950">
            <h4 className="text-sm font-semibold text-neutral-700 mb-4 flex items-center gap-2 dark:text-neutral-200">
              <Calculator className="w-4 h-4" /> Calculation Parameters
            </h4>
            <div className="grid grid-cols-4 gap-4">
              <div>
                <label className="field-label block mb-1">Day Count</label>
                <select
                  value={form.dayCountConvention}
                  onChange={(e) => setForm(prev => ({ ...prev, dayCountConvention: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {DAY_COUNT_OPTIONS.map(o => (
                    <option key={o} value={o}>{o}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Compounding</label>
                <select
                  value={form.compoundingFrequency}
                  onChange={(e) => setForm(prev => ({ ...prev, compoundingFrequency: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {COMPOUNDING_OPTIONS.map(o => (
                    <option key={o} value={o}>{o}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Calculation Freq</label>
                <select
                  value={form.calculationFrequency}
                  onChange={(e) => setForm(prev => ({ ...prev, calculationFrequency: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {CALCULATION_FREQ_OPTIONS.map(o => (
                    <option key={o} value={o}>{o}</option>
                  ))}
                </select>
              </div>
              <div>
                <label className="field-label block mb-1">Posting Freq</label>
                <select
                  value={form.postingFrequency}
                  onChange={(e) => setForm(prev => ({ ...prev, postingFrequency: e.target.value }))}
                  className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm bg-white dark:border-primary-700 dark:bg-primary-900"
                >
                  {POSTING_FREQ_OPTIONS.map(o => (
                    <option key={o} value={o}>{o}</option>
                  ))}
                </select>
              </div>
            </div>
          </div>

          {/* Validity */}
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">Effective From</label>
              <input
                type="date"
                value={form.effectiveFrom}
                onChange={(e) => setForm(prev => ({ ...prev, effectiveFrom: e.target.value }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
              />
            </div>
            <div>
              <label className="field-label block mb-1">Effective To (Optional)</label>
              <input
                type="date"
                value={form.effectiveTo}
                onChange={(e) => setForm(prev => ({ ...prev, effectiveTo: e.target.value }))}
                className="w-full px-3 py-2 border border-neutral-300 rounded-lg text-sm dark:border-primary-700"
              />
            </div>
          </div>

          {/* Actions */}
          <div className="flex gap-3 pt-4 border-t sticky bottom-0 bg-white dark:bg-primary-900">
            <Button variant="outline" className="flex-1" onClick={() => setShowCreateModal(false)}>
              Cancel
            </Button>
            <Button className="flex-1" onClick={handleSave} disabled={saving}>
              {saving ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Save className="w-4 h-4 mr-2" />}
              {isEditing ? 'Update Configuration' : 'Create Configuration'}
            </Button>
          </div>
        </div>
      </Modal>
    </Page>
  );
};

export default InterestConfigurationPage;