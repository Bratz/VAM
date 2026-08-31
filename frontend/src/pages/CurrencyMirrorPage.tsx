import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { ScopeSelector } from '../components/layout/ScopeSelector';
/**
 * CurrencyMirrorPage - Connected to Backend
 * 
 * Uses:
 * - currencyMirrorApi from api.ts → CurrencyMirrorController.java at /api/v1/treasury/currency-mirrors/*
 * - fxRateApi from api.ts → FxRateController.java at /api/v1/treasury/fx-rates/*
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  Globe, RefreshCw, ArrowRightLeft,
  DollarSign, Euro, PoundSterling, Coins, BarChart3,
  Clock, AlertTriangle, CheckCircle2, Settings,
  Loader2, AlertCircle, ChevronRight, Eye,
  Calculator, Building, X,
} from 'lucide-react';
import { Card, Button, Badge, Input , StatusIconBadge } from '../components/ui';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { Modal } from '../components/ui/enhanced';
import { formatCurrency, cn } from '../utils';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import { 
  currencyMirrorApi, 
  fxRateApi, 
  ApiResponse,
  CurrencyBreakdown as ApiCurrencyBreakdown,
  FxRate as ApiFxRate
} from '../services/api';
import axios from 'axios';

// ============================================================================
// TYPES (extend API types for local use)
// ============================================================================

interface CurrencyBreakdown extends ApiCurrencyBreakdown {
  percentOfTotal?: number;
}

// Use the API FxRate type directly - it already has isActive: boolean
type FxRate = ApiFxRate;

interface ConsolidatedBalance {
  corporateId: string;
  baseCurrency: string;
  totalBalance: number;
  currencyCount: number;
  asOf: string;
}


// Corporate type for selection
interface Corporate {
  id: string;
  corporateId: string;
  legalName: string;
  tradeName?: string;
  shortName?: string;
  status: string;
}

// Program type for selection
interface ProgramOption {
  id: string;
  programName: string;
  programCode: string;
  currencyCode: string;
  programType: string;
  status: string;
  corporateId?: string;
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8053/api/v1';
const apiClient = axios.create({
  baseURL: API_BASE_URL,
  headers: { 'Content-Type': 'application/json' },
  timeout: 30000,
});

// Corporate API
const corporatesApi = {
  getAll: async (): Promise<Corporate[]> => {
    try {
      const response = await apiClient.get('/corporates');
      return response.data.data || [];
    } catch { return []; }
  },
};

// Programs API
const programsApi = {
  getByCorporate: async (corporateId: string): Promise<ProgramOption[]> => {
    try {
      const response = await apiClient.get(`/programs/corporate/${corporateId}`);
      return response.data.data || [];
    } catch { return []; }
  },
};

// Demo data fallback when API fails
const DEMO_BREAKDOWNS: CurrencyBreakdown[] = [
  { currency: 'AED', baseCurrency: 'AED', originalBalance: 5000000, fxRate: 1, fxRateAt: new Date().toISOString(), convertedBalance: 5000000, mirrorVaId: 'demo-1', mirrorVaNumber: 'M-AED-001', percentOfTotal: 50 },
  { currency: 'USD', baseCurrency: 'AED', originalBalance: 500000, fxRate: 3.6725, fxRateAt: new Date().toISOString(), convertedBalance: 1836250, mirrorVaId: 'demo-2', mirrorVaNumber: 'M-USD-001', percentOfTotal: 18.36 },
  { currency: 'EUR', baseCurrency: 'AED', originalBalance: 400000, fxRate: 4.05, fxRateAt: new Date().toISOString(), convertedBalance: 1620000, mirrorVaId: 'demo-3', mirrorVaNumber: 'M-EUR-001', percentOfTotal: 16.20 },
  { currency: 'GBP', baseCurrency: 'AED', originalBalance: 200000, fxRate: 4.65, fxRateAt: new Date().toISOString(), convertedBalance: 930000, mirrorVaId: 'demo-4', mirrorVaNumber: 'M-GBP-001', percentOfTotal: 9.30 },
  { currency: 'SAR', baseCurrency: 'AED', originalBalance: 600000, fxRate: 0.98, fxRateAt: new Date().toISOString(), convertedBalance: 588000, mirrorVaId: 'demo-5', mirrorVaNumber: 'M-SAR-001', percentOfTotal: 5.88 },
];

const DEMO_FX_RATES: FxRate[] = [
  { id: 'fx-1', fromCurrency: 'USD', toCurrency: 'AED', rate: 3.6725, rateDate: new Date().toISOString(), rateTimestamp: new Date().toISOString(), rateType: 'SPOT', rateSource: 'REUTERS', isActive: true },
  { id: 'fx-2', fromCurrency: 'EUR', toCurrency: 'AED', rate: 4.05, rateDate: new Date().toISOString(), rateTimestamp: new Date().toISOString(), rateType: 'SPOT', rateSource: 'REUTERS', isActive: true },
  { id: 'fx-3', fromCurrency: 'GBP', toCurrency: 'AED', rate: 4.65, rateDate: new Date().toISOString(), rateTimestamp: new Date().toISOString(), rateType: 'SPOT', rateSource: 'REUTERS', isActive: true },
  { id: 'fx-4', fromCurrency: 'SAR', toCurrency: 'AED', rate: 0.98, rateDate: new Date().toISOString(), rateTimestamp: new Date().toISOString(), rateType: 'FIXING', rateSource: 'CBS', isActive: true },
];

// Helper to extract data from ApiResponse
const extractData = <T,>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) return response.data;
  return response as unknown as T;
};

// ============================================================================
// CURRENCY ICONS & STYLES
// ============================================================================

const CURRENCY_ICONS: Record<string, React.FC<{ className?: string }>> = {
  USD: DollarSign,
  EUR: Euro,
  GBP: PoundSterling,
  AED: Coins,
};

const CURRENCY_COLORS: Record<string, { bg: string; text: string; border: string; solid: string }> = {
  // `bg` / `text` / `border` — pale variants for currency cards and inline chips on light surfaces.
  // `solid` — saturated fill for use ON dark surfaces (the navy hero card's distribution bar).
  USD: { bg: 'bg-success-50 dark:bg-success-500/10',     text: 'text-success-600 dark:text-success-300',     border: 'border-success-200 dark:border-success-500/30',     solid: 'bg-success-400' },
  EUR: { bg: 'bg-info-50 dark:bg-info-500/10',       text: 'text-info-600 dark:text-info-300',       border: 'border-info-200 dark:border-info-500/30',       solid: 'bg-info-400' },
  GBP: { bg: 'bg-cat-2-soft dark:bg-cat-2/15',   text: 'text-cat-2',   border: 'border-cat-2/20 dark:border-cat-2/30',   solid: 'bg-cat-2' },
  AED: { bg: 'bg-warning-50 dark:bg-warning-500/10',     text: 'text-warning-600 dark:text-warning-300',     border: 'border-warning-200 dark:border-warning-500/30',     solid: 'bg-warning-400' },
  SAR: { bg: 'bg-cat-5-soft dark:bg-cat-5/15', text: 'text-cat-5', border: 'border-cat-5/20 dark:border-cat-5/30', solid: 'bg-cat-5' },
  CHF: { bg: 'bg-error-50 dark:bg-error-500/10',         text: 'text-error-600 dark:text-error-300',         border: 'border-error-200 dark:border-error-500/30',         solid: 'bg-error-400' },
  JPY: { bg: 'bg-cat-4-soft dark:bg-cat-4/15',       text: 'text-cat-4',       border: 'border-cat-4/20 dark:border-cat-4/30',       solid: 'bg-cat-4' },
  INR: { bg: 'bg-warning-50 dark:bg-warning-500/10',   text: 'text-warning-600 dark:text-warning-300',   border: 'border-warning-200 dark:border-warning-500/30',   solid: 'bg-warning-400' },
};

const getCurrencyStyle = (currency: string) => {
  return CURRENCY_COLORS[currency] || { bg: 'bg-neutral-50 dark:bg-primary-950', text: 'text-neutral-600 dark:text-neutral-300', border: 'border-neutral-200 dark:border-primary-800', solid: 'bg-neutral-400' };
};

const CurrencyIcon: React.FC<{ currency: string; className?: string }> = ({ currency, className }) => {
  const Icon = CURRENCY_ICONS[currency] || Coins;
  return <Icon className={className} />;
};

// ============================================================================
// CURRENCY BREAKDOWN CARD
// ============================================================================

interface CurrencyCardProps {
  breakdown: CurrencyBreakdown;
  baseCurrency: string;
  onView: () => void;
  onRecalculate: () => void;
  recalculating: boolean;
}

const CurrencyCard: React.FC<CurrencyCardProps> = ({ breakdown, baseCurrency, onView, onRecalculate, recalculating }) => {
  const style = getCurrencyStyle(breakdown.currency);
  const rateAge = breakdown.fxRateAt
    ? Math.floor((Date.now() - new Date(breakdown.fxRateAt).getTime()) / (1000 * 60))
    : null;
  const isStale = rateAge !== null && rateAge > 60;

  return (
    <div className={cn(
      "bg-white dark:bg-primary-900 rounded-xl border shadow-sm hover:shadow-md transition-all",
      style.border
    )}>
      {/* Header */}
      <div className={cn("p-4 rounded-t-xl", style.bg)}>
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className={cn("w-12 h-12 rounded-xl bg-white dark:bg-primary-900 shadow-sm flex items-center justify-center")}>
              <CurrencyIcon currency={breakdown.currency} className={cn("w-6 h-6", style.text)} />
            </div>
            <div>
              <h3 className="section-title">{breakdown.currency}</h3>
              <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider">Currency Mirror</p>
            </div>
          </div>
          <Badge variant="info" size="sm">{breakdown.percentOfTotal?.toFixed(1)}%</Badge>
        </div>
      </div>

      {/* Balances */}
      <div className="p-4 space-y-4">
        <div>
          <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Original Balance</p>
          <p className="stat-value-sm">
            {formatCurrency(breakdown.originalBalance, breakdown.currency)}
          </p>
        </div>

        <div className="flex items-center gap-2 py-2 border-y border-dashed border-neutral-200 dark:border-primary-800">
          <ArrowRightLeft className="w-4 h-4 text-neutral-400 dark:text-neutral-500" />
          <span className="text-sm text-neutral-600 dark:text-neutral-300">
            1 {breakdown.currency} = {breakdown.fxRate?.toFixed(4) || 'N/A'} {baseCurrency}
          </span>
          {isStale && (
            <Badge variant="warning" size="sm">
              <AlertTriangle className="w-3 h-3 mr-1" />
              Stale
            </Badge>
          )}
        </div>

        <div>
          <p className="text-xs font-medium text-neutral-500 dark:text-neutral-400 uppercase tracking-wider mb-1">Converted to {baseCurrency}</p>
          {/* Phase 12 Task E: .stat-value-success replaces the raw
              `text-xl font-bold` hand-roll (matches the sibling figure's
              .stat-value-sm display tier). */}
          <p className="stat-value-success">
            {formatCurrency(breakdown.convertedBalance, baseCurrency)}
          </p>
        </div>
      </div>

      {/* Footer */}
      <div className="px-4 py-3 border-t border-neutral-100 dark:border-primary-800/60 bg-neutral-50/50 dark:bg-primary-950/50 rounded-b-xl">
        <div className="flex items-center justify-between text-xs text-neutral-500 dark:text-neutral-400 mb-3">
          <span className="font-mono">{breakdown.mirrorVaNumber}</span>
          {rateAge !== null && (
            <span className="flex items-center gap-1">
              <Clock className="w-3 h-3" />
              {rateAge < 60 ? `${rateAge}m ago` : `${Math.floor(rateAge / 60)}h ago`}
            </span>
          )}
        </div>
        <div className="flex gap-2">
          <Button variant="outline" size="sm" className="flex-1" onClick={onView}>
            <Eye className="w-3 h-3 mr-1" />
            Details
          </Button>
          <Button variant="outline" size="sm" className="flex-1" onClick={onRecalculate} disabled={recalculating}>
            {recalculating ? <Loader2 className="w-3 h-3 mr-1 animate-spin" /> : <RefreshCw className="w-3 h-3 mr-1" />}
            Recalc
          </Button>
        </div>
      </div>
    </div>
  );
};

// ============================================================================
// FX RATE ROW
// ============================================================================

interface FxRateRowProps {
  rate: FxRate;
  onEdit: () => void;
}

const FxRateRow: React.FC<FxRateRowProps> = ({ rate, onEdit }) => {
  const fromStyle = getCurrencyStyle(rate.fromCurrency);
  const toStyle = getCurrencyStyle(rate.toCurrency);

  return (
    <div className="flex items-center justify-between p-3 bg-white dark:bg-primary-900 rounded-lg border border-neutral-200 dark:border-primary-800 hover:border-primary-300 dark:hover:border-primary-700 transition-colors">
      <div className="flex items-center gap-3">
        <div className="flex items-center">
          <div className={cn("w-8 h-8 rounded-full flex items-center justify-center", fromStyle.bg)}>
            <CurrencyIcon currency={rate.fromCurrency} className={cn("w-4 h-4", fromStyle.text)} />
          </div>
          <ArrowRightLeft className="w-4 h-4 text-neutral-400 dark:text-neutral-500 mx-2" />
          <div className={cn("w-8 h-8 rounded-full flex items-center justify-center", toStyle.bg)}>
            <CurrencyIcon currency={rate.toCurrency} className={cn("w-4 h-4", toStyle.text)} />
          </div>
        </div>
        <div>
          <p className="font-medium text-primary-900 dark:text-neutral-50">{rate.fromCurrency}/{rate.toCurrency}</p>
          <p className="text-xs text-neutral-500 dark:text-neutral-400">{rate.rateSource}</p>
        </div>
      </div>

      <div className="text-right">
        <p className="font-mono font-medium text-primary-900 dark:text-neutral-50">{rate.rate?.toFixed(4)}</p>
        <p className="text-xs text-neutral-500 dark:text-neutral-400">{new Date(rate.rateDate).toLocaleDateString()}</p>
      </div>

      <Badge variant={rate.isActive ? 'success' : 'neutral'} size="sm">
        {rate.rateType}
      </Badge>

      <Button variant="ghost" size="sm" onClick={onEdit}>
        <Settings className="w-4 h-4" />
      </Button>
    </div>
  );
};

// ============================================================================
// MAIN PAGE COMPONENT
// ============================================================================

const CurrencyMirrorPage: React.FC = () => {
  const [breakdowns, setBreakdowns] = useState<CurrencyBreakdown[]>([]);
  const [fxRates, setFxRates] = useState<FxRate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [baseCurrency, setBaseCurrency] = useState('AED');
  const [recalculating, setRecalculating] = useState(false);
  const [recalculatingMirror, setRecalculatingMirror] = useState<string | null>(null);
  const [selectedMirror, setSelectedMirror] = useState<CurrencyBreakdown | null>(null);
  const [corporates, setCorporates] = useState<Corporate[]>([]);
  const [selectedCorporateId, setSelectedCorporateId] = useState<string>('');
  const [loadingCorporates, setLoadingCorporates] = useState(true);
  const [programs, setPrograms] = useState<ProgramOption[]>([]);
  const [selectedProgramId, setSelectedProgramId] = useState<string>('');
  const [loadingPrograms, setLoadingPrograms] = useState(false);
  const [isDemo, setIsDemo] = useState(false);

  // Modals
  const [showDetailModal, setShowDetailModal] = useState(false);
  const [showFxRateModal, setShowFxRateModal] = useState(false);
  const [showConverterModal, setShowConverterModal] = useState(false);

  // Converter state
  const [converterFrom, setConverterFrom] = useState('USD');
  const [converterTo, setConverterTo] = useState('AED');
  const [converterAmount, setConverterAmount] = useState<number>(1000);
  const [convertedResult, setConvertedResult] = useState<number | null>(null);
  const [converting, setConverting] = useState(false);

  // Stats
  const [stats, setStats] = useState({
    totalInBase: 0,
    currencyCount: 0,
    lastRecalculated: null as string | null,
    staleRates: 0,
  });


  // Load corporates on mount
  useEffect(() => {
    const loadCorporates = async () => {
      try {
        setLoadingCorporates(true);
        const corps = await corporatesApi.getAll();
        setCorporates(corps);
        if (corps.length > 0) {
          setSelectedCorporateId(corps[0].id);
        }
      } catch (err) {
        console.error('Failed to load corporates:', err);
      } finally {
        setLoadingCorporates(false);
      }
    };
    loadCorporates();
  }, []);

  // Load programs when corporate changes
  useEffect(() => {
    const loadPrograms = async () => {
      if (!selectedCorporateId) {
        setPrograms([]);
        setSelectedProgramId('');
        return;
      }

      try {
        setLoadingPrograms(true);
        const programList = await programsApi.getByCorporate(selectedCorporateId);
        setPrograms(programList);

        // Auto-select first active program
        const activePrograms = programList.filter((p: ProgramOption) => p.status?.toUpperCase() === 'ACTIVE');
        if (activePrograms.length > 0) {
          setSelectedProgramId(activePrograms[0].id);
          // Set base currency from program
          if (activePrograms[0].currencyCode) {
            setBaseCurrency(activePrograms[0].currencyCode);
          }
        } else {
          setSelectedProgramId('');
        }
      } catch (err) {
        console.error('Failed to load programs:', err);
        setPrograms([]);
      } finally {
        setLoadingPrograms(false);
      }
    };
    loadPrograms();
  }, [selectedCorporateId]);

  // Load data from real APIs with demo fallback
  const loadData = useCallback(async () => {
    if (!selectedCorporateId) {
      setLoading(false);
      return;
    }

    try {
      setLoading(true);
      setError(null);
      setIsDemo(false);

      // Validate IDs are valid UUIDs
      const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      if (!uuidRegex.test(selectedCorporateId)) {
        console.warn('Invalid UUID, using demo data');
        setBreakdowns(DEMO_BREAKDOWNS);
        setFxRates(DEMO_FX_RATES);
        setIsDemo(true);
        const total = DEMO_BREAKDOWNS.reduce((sum, b) => sum + (b.convertedBalance || 0), 0);
        setStats({ totalInBase: total, currencyCount: DEMO_BREAKDOWNS.length, lastRecalculated: new Date().toISOString(), staleRates: 0 });
        setLoading(false);
        return;
      }

      // Load currency breakdown - prefer program-based API for accurate breakdown
      let breakdownData: CurrencyBreakdown[] = [];
      let useDemo = false;
      try {
        let breakdownResponse;
        if (selectedProgramId && uuidRegex.test(selectedProgramId)) {
          // Use program-based API (preferred for multi-program corporates)
          breakdownResponse = await currencyMirrorApi.getBreakdownListByProgram(selectedProgramId);
        } else {
          // Fallback to corporate-based API
          breakdownResponse = await currencyMirrorApi.getBreakdownList(selectedCorporateId);
        }
        const data = extractData(breakdownResponse);
        breakdownData = Array.isArray(data) ? data.map(d => ({ ...d })) : [];
        if (breakdownData.length === 0) useDemo = true;
      } catch (e) {
        console.warn('Failed to load breakdown, using demo data:', e);
        useDemo = true;
      }

      if (useDemo) {
        breakdownData = DEMO_BREAKDOWNS;
        setIsDemo(true);
      }

      // Calculate percentages
      const totalBase = breakdownData.reduce((sum, b) => sum + (b.convertedBalance || 0), 0);
      breakdownData.forEach(b => {
        b.percentOfTotal = totalBase > 0 ? ((b.convertedBalance || 0) / totalBase) * 100 : 0;
      });
      setBreakdowns(breakdownData);

      // Load consolidated balance - prefer program-based API for accuracy
      try {
        let consolidatedResponse;
        const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
        if (selectedProgramId && uuidRegex.test(selectedProgramId)) {
          // Use program-based API (preferred)
          consolidatedResponse = await currencyMirrorApi.getConsolidatedBalanceByProgram(selectedProgramId, baseCurrency);
        } else {
          // Fallback to corporate-based API
          consolidatedResponse = await currencyMirrorApi.getConsolidatedBalance(selectedCorporateId, baseCurrency);
        }
        const consolidatedData = extractData(consolidatedResponse) as ConsolidatedBalance;
        setStats({
          totalInBase: consolidatedData?.totalBalance ?? totalBase,
          currencyCount: consolidatedData?.currencyCount ?? breakdownData.length,
          lastRecalculated: consolidatedData?.asOf ?? new Date().toISOString(),
          staleRates: 0,
        });
      } catch (e) {
        console.warn('Failed to load consolidated:', e);
        setStats({ totalInBase: totalBase, currencyCount: breakdownData.length, lastRecalculated: new Date().toISOString(), staleRates: 0 });
      }

      // Load FX rates
      try {
        const ratesResponse = await fxRateApi.getAllActiveRates();
        const ratesData = extractData(ratesResponse);
        setFxRates(Array.isArray(ratesData) && ratesData.length > 0 ? ratesData : (useDemo ? DEMO_FX_RATES : []));
      } catch (e) {
        console.warn('Failed to load FX rates:', e);
        if (useDemo) setFxRates(DEMO_FX_RATES);
      }

    } catch (err) {
      console.error('Failed to load currency mirrors:', err);
      // Fallback to demo on any error
      setBreakdowns(DEMO_BREAKDOWNS);
      setFxRates(DEMO_FX_RATES);
      setIsDemo(true);
      const total = DEMO_BREAKDOWNS.reduce((sum, b) => sum + (b.convertedBalance || 0), 0);
      setStats({ totalInBase: total, currencyCount: DEMO_BREAKDOWNS.length, lastRecalculated: new Date().toISOString(), staleRates: 0 });
    } finally {
      setLoading(false);
    }
  }, [selectedCorporateId, selectedProgramId, baseCurrency]);


  useEffect(() => {
    if (selectedCorporateId) {
      loadData();
    }
  }, [loadData, selectedCorporateId, selectedProgramId]);

  // Recalculate all mirrors - prefer program-based API when available
  const handleRecalculateAll = async () => {
    setRecalculating(true);
    setError(null);
    try {
      const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
      if (selectedProgramId && uuidRegex.test(selectedProgramId)) {
        // Use program-based recalculate (preferred for multi-program corporates)
        await currencyMirrorApi.recalculateAllByProgram(selectedProgramId);
      } else {
        // Fallback to corporate-based recalculate
        await currencyMirrorApi.recalculateAll(selectedCorporateId);
      }
      await loadData();
    } catch (err) {
      console.error('Failed to recalculate:', err);
      setError('Failed to recalculate mirrors.');
    } finally {
      setRecalculating(false);
    }
  };

  // Recalculate single mirror
  const handleRecalculateMirror = async (mirrorVaId: string) => {
    setRecalculatingMirror(mirrorVaId);
    try {
      await currencyMirrorApi.recalculate(mirrorVaId);
      await loadData();
    } catch (err) {
      console.error('Failed to recalculate mirror:', err);
      setError('Failed to recalculate mirror.');
    } finally {
      setRecalculatingMirror(null);
    }
  };

  // Convert currency using real API
  const handleConvert = async () => {
    if (converterAmount <= 0) return;
    setConverting(true);
    try {
      const response = await fxRateApi.convert(converterAmount, converterFrom, converterTo);
      const data = extractData(response);
      // Handle response shape from FxConversionResult
      if (data && typeof data === 'object' && 'convertedAmount' in data) {
        setConvertedResult(data.convertedAmount as number);
      }
    } catch (err) {
      console.error('Failed to convert:', err);
      // Fallback: try to find rate manually
      const rate = fxRates.find(r => r.fromCurrency === converterFrom && r.toCurrency === converterTo);
      if (rate) {
        setConvertedResult(converterAmount * rate.rate);
      } else {
        // Try inverse
        const inverseRate = fxRates.find(r => r.fromCurrency === converterTo && r.toCurrency === converterFrom);
        if (inverseRate && inverseRate.rate) {
          setConvertedResult(converterAmount / inverseRate.rate);
        } else {
          setError('Conversion rate not available.');
        }
      }
    } finally {
      setConverting(false);
    }
  };

  // Refresh FX rates
  const handleRefreshRates = async () => {
    try {
      await fxRateApi.refreshCache();
      await loadData();
    } catch (err) {
      console.error('Failed to refresh rates:', err);
      setError('Failed to refresh FX rates.');
    }
  };

  // Toolbar actions in Aperture Layout header. The Base-currency selector
  // stays inline as a filter input (not an action).
  usePageHeaderActions(
    () => (
      <>
        <Button variant="outline" size="sm" onClick={() => setShowConverterModal(true)}>
          <Calculator className="w-4 h-4 mr-1" />
          Converter
        </Button>
        <Button variant="outline" size="sm" onClick={() => setShowFxRateModal(true)}>
          <BarChart3 className="w-4 h-4 mr-1" />
          FX Rates
        </Button>
        <Button size="sm" onClick={handleRecalculateAll} disabled={recalculating}>
          {recalculating
            ? <Loader2 className="w-4 h-4 mr-1 animate-spin" />
            : <RefreshCw className="w-4 h-4 mr-1" />}
          Recalculate All
        </Button>
      </>
    ),
    [recalculating]
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" />
      </div>
    );
  }

  return (
    <Page>
      {/* In-body identity (Phase 10) — aligns with the rest of Aperture's
          conformed pages. Title + action buttons (Converter / FX Rates /
          Recalculate All) migrated to the Aperture Layout header via
          usePageHeaderActions. */}
      <PageHeader
        title="Currency Mirrors"
        description="Multi-currency exposure across program-scoped currency mirror VAs. Base-currency view re-denominates the breakdown for FX-honest reporting."
      />

      {/* Base-currency selector — page-level filter that scopes the
          breakdown view below. Kept inline because it's a single
          per-page control that doesn't fit the ScopeSelector contract. */}
      <div className="flex items-center justify-end animate-fade-in" style={{ animationDelay: '0.05s' }}>
        <div className="flex items-center gap-2 px-3 py-2 bg-neutral-100 dark:bg-primary-800 rounded-lg">
          <span className="text-sm text-neutral-600 dark:text-neutral-300">Base:</span>
          <CurrencyPicker
            value={baseCurrency}
            onChange={(c) => setBaseCurrency(c)}
            className="bg-transparent text-sm font-medium text-primary-900 dark:text-neutral-50 outline-none cursor-pointer"
          />
        </div>
      </div>

      {/* Demo Mode Banner */}
      {isDemo && (
        <Card className="bg-warning-50 dark:bg-warning-500/10 border-warning-200 dark:border-warning-500/30 animate-fade-in" style={{ animationDelay: '0.1s' }}>
          <div className="flex items-center gap-3 p-4">
            <StatusIconBadge tone="warning" icon={AlertTriangle} />
            <div>
              <span className="font-semibold text-warning-800 dark:text-warning-300">Demo Mode:</span>
              <span className="text-warning-700 dark:text-warning-300 ml-1">Showing sample data. Select a valid corporate with currency mirrors to view real data.</span>
            </div>
          </div>
        </Card>
      )}

      {/* Corporate / Program picker — shared `<ScopeSelector>` primitive.
          The base-currency side-effect on Program change is preserved at
          the prop boundary. */}
      <ScopeSelector
        mode="corporate-program"
        corporates={corporates}
        programs={programs.filter(p => p.status?.toUpperCase() === 'ACTIVE')}
        selectedCorporateId={selectedCorporateId}
        selectedProgramId={selectedProgramId}
        onCorporateChange={(id) => {
          setSelectedCorporateId(id);
          setSelectedProgramId('');
          setBreakdowns([]);
        }}
        onProgramChange={(id) => {
          setSelectedProgramId(id);
          // Update base currency from selected program — preserved from
          // the previous inline implementation.
          const prog = programs.find(p => p.id === id);
          if (prog?.currencyCode) {
            setBaseCurrency(prog.currencyCode);
          }
        }}
        loading={loadingCorporates || loadingPrograms}
        disableChildUntilParent
      />

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 dark:bg-error-500/10 border-error-200 dark:border-error-500/30 animate-fade-in">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="error" icon={AlertCircle} />
              <span className="text-error-700 dark:text-error-300 font-medium">{error}</span>
            </div>
            <button onClick={() => setError(null)} className="text-error-500 dark:text-error-300 hover:text-error-700 dark:hover:text-error-200 p-1">
              <X className="w-5 h-5" />
            </button>
          </div>
        </Card>
      )}

      {/* Consolidated Summary — hero card.
          The big number uses `!text-white` because the parent's `text-white`
          would otherwise lose to `.stat-value`'s baked-in `text-primary-900`
          (same specificity, later-declared utility wins → dark navy on dark
          navy = invisible). The `!` forces it. */}
      <div className="bg-gradient-to-r from-primary-900 to-primary-800 text-white rounded-xl p-6 animate-fade-in" style={{ animationDelay: '0.15s' }}>
        <div className="flex items-center justify-between">
          <div>
            <p className="text-xs font-medium text-primary-200 uppercase tracking-wider">Total Consolidated Position</p>
            {/* Hero-sized inverse variant — no `!important` override needed.
                Was previously `stat-value !text-white` to defeat the
                utility's text-primary-900 default on this intentionally-dark
                hero. Added `.stat-value-inverse-lg` to index.css for this. */}
            <p className="stat-value-inverse-lg mt-1">
              {formatCurrency(stats.totalInBase, baseCurrency)}
            </p>
            <p className="text-primary-300 text-sm mt-2">
              Across {stats.currencyCount} currencies
            </p>
          </div>
          <div className="text-right">
            <div className="flex items-center gap-2 text-primary-200 mb-2">
              <Clock className="w-4 h-4" />
              <span className="text-sm">
                Last updated: {stats.lastRecalculated ? new Date(stats.lastRecalculated).toLocaleTimeString() : 'Never'}
              </span>
            </div>
            <Badge variant="success" className="bg-white/20 text-white">
              <CheckCircle2 className="w-3 h-3 mr-1" />
              All Rates Current
            </Badge>
          </div>
        </div>

        {/* Currency Distribution Bar — uses the `solid` palette variant
            (saturated 400-shade colours) so each segment reads against the
            navy hero background. The pale `bg-{ccy}-50` / `dark:bg-{ccy}-500/10`
            used elsewhere washes out here. */}
        {breakdowns.length > 0 && (
          <div className="mt-6">
            <div className="flex rounded-lg overflow-hidden h-4 ring-1 ring-white/10">
              {breakdowns.map((b) => {
                const style = getCurrencyStyle(b.currency);
                return (
                  <div
                    key={b.currency}
                    className={cn('h-full', style.solid)}
                    style={{ width: `${b.percentOfTotal}%` }}
                    title={`${b.currency}: ${b.percentOfTotal?.toFixed(1)}%`}
                  />
                );
              })}
            </div>
            <div className="flex flex-wrap gap-4 mt-3">
              {breakdowns.map(b => {
                const style = getCurrencyStyle(b.currency);
                return (
                  <div key={b.currency} className="flex items-center gap-2">
                    <div className={cn('w-3 h-3 rounded-sm', style.solid)} />
                    <span className="text-sm text-primary-200">
                      {b.currency}: {b.percentOfTotal?.toFixed(1)}%
                    </span>
                  </div>
                );
              })}
            </div>
          </div>
        )}
      </div>

      {/* Info Banner */}
      <Card padding="sm" className="bg-gradient-to-r from-info-50/50 via-white to-primary-50/50 dark:from-info-500/10 dark:via-primary-900 dark:to-primary-800/40 border-info-200/60 dark:border-info-500/30 animate-fade-in" style={{ animationDelay: '0.2s' }}>
        <div className="flex items-start gap-3 p-4">
          <StatusIconBadge tone="info" icon={Globe} className="flex-shrink-0" />
          <div>
            <p className="text-sm font-semibold text-info-800 dark:text-info-300">Currency Mirror Architecture</p>
            <p className="text-sm text-info-700 dark:text-info-300 mt-1">
              Currency mirrors (<strong>CURRENCY_MIRROR</strong> VAs) aggregate all VAs of the same currency
              and convert to base currency using live FX rates. They enable multi-currency liquidity visibility
              without physical FX conversion.
            </p>
          </div>
        </div>
      </Card>

      {/* Currency Cards Grid */}
      {breakdowns.length > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4 animate-fade-in" style={{ animationDelay: '0.25s' }}>
          {breakdowns.map((breakdown, index) => (
            <div key={breakdown.currency} className="animate-fade-in" style={{ animationDelay: `${0.3 + index * 0.05}s` }}>
              <CurrencyCard
                breakdown={breakdown}
                baseCurrency={baseCurrency}
                onView={() => { setSelectedMirror(breakdown); setShowDetailModal(true); }}
                onRecalculate={() => handleRecalculateMirror(breakdown.mirrorVaId)}
                recalculating={recalculatingMirror === breakdown.mirrorVaId}
              />
            </div>
          ))}
        </div>
      ) : (
        <Card className="p-12 text-center animate-fade-in" style={{ animationDelay: '0.25s' }}>
          <Globe className="w-12 h-12 text-neutral-300 dark:text-neutral-600 mx-auto mb-4" />
          <p className="text-neutral-500 dark:text-neutral-400">No currency mirrors found</p>
          <p className="text-sm text-neutral-400 dark:text-neutral-500 mt-2">Currency mirrors will appear when multi-currency accounts are created.</p>
        </Card>
      )}

      {/* FX Rates Quick View */}
      <Card hover className="animate-fade-in" style={{ animationDelay: '0.35s' }}>
        <div className="p-4 border-b border-neutral-100 dark:border-primary-800/60">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="accent" icon={BarChart3} />
              <h2 className="section-title">Active FX Rates</h2>
            </div>
            <Button variant="ghost" size="sm" onClick={() => setShowFxRateModal(true)}>
              View All <ChevronRight className="w-4 h-4 ml-1" />
            </Button>
          </div>
        </div>
        <div className="p-4 space-y-2">
          {fxRates.length > 0 ? (
            fxRates.slice(0, 3).map(rate => (
              <FxRateRow
                key={rate.id}
                rate={rate}
                onEdit={() => console.log('Edit rate:', rate.id)}
              />
            ))
          ) : (
            <p className="text-sm text-neutral-500 dark:text-neutral-400 text-center py-4">No FX rates available</p>
          )}
        </div>
      </Card>

      {/* Detail Modal */}
      <Modal
        isOpen={showDetailModal}
        onClose={() => setShowDetailModal(false)}
        title="Currency Mirror Details"
        size="lg"
      >
        {selectedMirror && (
          <div className="p-4 space-y-6">
            <div className="flex items-center gap-4">
              <div className={cn("w-16 h-16 rounded-xl flex items-center justify-center", getCurrencyStyle(selectedMirror.currency).bg)}>
                <CurrencyIcon currency={selectedMirror.currency} className={cn("w-8 h-8", getCurrencyStyle(selectedMirror.currency).text)} />
              </div>
              <div>
                {/* Currency code is the hero metric of this panel — use
                    stat-value-sm typography on the heading for visual weight;
                    heading semantics remain for screen readers. Phase 9.1
                    Task D: documented role-vs-style mismatch. */}
                <h3 className="stat-value-sm">{selectedMirror.currency}</h3>
                <p className="text-neutral-500 dark:text-neutral-400 font-mono">{selectedMirror.mirrorVaNumber}</p>
              </div>
            </div>

            <div className="grid grid-cols-2 gap-4">
              <div className="p-4 bg-neutral-50 dark:bg-primary-950 rounded-xl">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Original Balance</p>
                <p className="stat-value-sm">
                  {formatCurrency(selectedMirror.originalBalance, selectedMirror.currency)}
                </p>
              </div>
              <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-xl">
                <p className="text-sm text-neutral-500 dark:text-neutral-400">Converted ({baseCurrency})</p>
                <p className="stat-value-sm text-success-600 dark:text-success-300">
                  {formatCurrency(selectedMirror.convertedBalance, baseCurrency)}
                </p>
              </div>
            </div>

            <div className="p-4 border rounded-xl">
              <h4 className="font-semibold text-primary-900 dark:text-neutral-50 mb-3">FX Rate Information</h4>
              <div className="space-y-2">
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Exchange Rate</span>
                  <span className="font-mono font-medium">
                    1 {selectedMirror.currency} = {selectedMirror.fxRate?.toFixed(4) || 'N/A'} {baseCurrency}
                  </span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">Last Updated</span>
                  <span>{selectedMirror.fxRateAt ? new Date(selectedMirror.fxRateAt).toLocaleString() : 'N/A'}</span>
                </div>
                <div className="flex justify-between">
                  <span className="text-neutral-600 dark:text-neutral-300">% of Total</span>
                  <span className="font-medium">{selectedMirror.percentOfTotal?.toFixed(2)}%</span>
                </div>
              </div>
            </div>

            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setShowDetailModal(false)}>Close</Button>
              <Button variant="outline" onClick={() => handleRecalculateMirror(selectedMirror.mirrorVaId)}>
                <RefreshCw className="w-4 h-4 mr-2" />
                Recalculate
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* FX Rates Modal */}
      <Modal
        isOpen={showFxRateModal}
        onClose={() => setShowFxRateModal(false)}
        title="FX Rate Management"
        size="lg"
      >
        <div className="p-4 space-y-4">
          <div className="flex justify-between items-center">
            <p className="text-sm text-neutral-500 dark:text-neutral-400">Manage exchange rates used for currency conversion</p>
            <Button size="sm" onClick={handleRefreshRates}>
              <RefreshCw className="w-4 h-4 mr-2" />
              Refresh All
            </Button>
          </div>

          <div className="space-y-2 max-h-96 overflow-y-auto">
            {fxRates.length > 0 ? (
              fxRates.map(rate => (
                <FxRateRow
                  key={rate.id}
                  rate={rate}
                  onEdit={() => console.log('Edit:', rate.id)}
                />
              ))
            ) : (
              <p className="text-sm text-neutral-500 dark:text-neutral-400 text-center py-8">No FX rates available</p>
            )}
          </div>

          <div className="pt-4 border-t">
            <Button variant="outline" className="w-full">
              <Settings className="w-4 h-4 mr-2" />
              Configure Rate Sources
            </Button>
          </div>
        </div>
      </Modal>

      {/* Converter Modal */}
      <Modal
        isOpen={showConverterModal}
        onClose={() => setShowConverterModal(false)}
        title="Currency Converter"
        size="sm"
      >
        <div className="p-4 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="field-label block mb-1">From</label>
              <select
                value={converterFrom}
                onChange={(e) => { setConverterFrom(e.target.value); setConvertedResult(null); }}
                className="w-full px-3 py-2 border rounded-lg"
              >
                {['USD', 'EUR', 'GBP', 'AED', 'SAR', 'CHF'].map(c => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
            <div>
              <label className="field-label block mb-1">To</label>
              <select
                value={converterTo}
                onChange={(e) => { setConverterTo(e.target.value); setConvertedResult(null); }}
                className="w-full px-3 py-2 border rounded-lg"
              >
                {['USD', 'EUR', 'GBP', 'AED', 'SAR', 'CHF'].map(c => (
                  <option key={c} value={c}>{c}</option>
                ))}
              </select>
            </div>
          </div>

          <div>
            <label className="field-label block mb-1">Amount</label>
            <Input
              type="number"
              value={converterAmount}
              onChange={(e) => { setConverterAmount(parseFloat(e.target.value) || 0); setConvertedResult(null); }}
            />
          </div>

          <Button className="w-full" onClick={handleConvert} disabled={converting || converterAmount <= 0}>
            {converting ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Calculator className="w-4 h-4 mr-2" />}
            Convert
          </Button>

          {convertedResult !== null && (
            <div className="p-4 bg-success-50 dark:bg-success-500/10 rounded-xl text-center">
              <p className="text-sm text-neutral-600 dark:text-neutral-300">
                {formatCurrency(converterAmount, converterFrom)} =
              </p>
              <p className="stat-value-sm text-success-600 dark:text-success-300 mt-1">
                {formatCurrency(convertedResult, converterTo)}
              </p>
            </div>
          )}
        </div>
      </Modal>
    </Page>
  );
};

export default CurrencyMirrorPage;