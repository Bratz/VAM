import { Page } from '../components/layout/Page';
/**
 * FxRatesPage - Connected to Backend
 *
 * Uses: fxRateApi from api.ts
 * Backend: FxRateController.java at /api/v1/treasury/fx-rates/*
 *
 * Lookup-first redesign: a treasurer opens this page to find a current rate
 * fast (~90% of visits). The page is a rates *table* with freshness badges
 * (vocabulary mirrors MultiBankLiquidity's FreshnessPill). The converter and
 * per-rate detail live in context-preserving <Drawer>s, not modals, so the
 * table stays in view. Stats are operational (freshness) not inventory.
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  TrendingUp, Plus, RefreshCw, Search, Loader2,
  ArrowRightLeft, Clock, CheckCircle2, XCircle, AlertTriangle, MinusCircle,
  Copy, Globe, Building2, Edit2, ArrowUpDown, Database, Zap, X,
} from 'lucide-react';
import { Card, Button, Input, StatusIconBadge, Drawer, StatTile } from '../components/ui';
import { Modal } from '../components/ui/enhanced';
import { PageHeader } from '../components/layout/PageHeader';
import { StatStrip } from '../components/layout/StatStrip';
import { cn, formatDate, formatFxRate, relativeTime } from '../utils';
// Import from existing api.ts
import { fxRateApi, FxRate, ApiResponse } from '../services/api';
import { usePageHeaderActions } from '../context/PageHeaderContext';
import toast from 'react-hot-toast';

// ============================================================================
// CONSTANTS
// ============================================================================

// Task F: all entries use semantic palette tokens (was raw purple/teal/amber).
// Future categorical color tokens (palette-categorical-1..6) would replace
// this map once defined; until then semantic tones keep dark-mode + the
// amber→orange (warning) migration correct.
const RATE_TYPE_CONFIG: Record<string, { label: string; color: string; bgColor: string }> = {
  SPOT:       { label: 'Spot',       color: 'text-info-700 dark:text-info-300',       bgColor: 'bg-info-100 dark:bg-info-500/20' },
  FORWARD:    { label: 'Forward',    color: 'text-accent-700 dark:text-accent-300',   bgColor: 'bg-accent-100 dark:bg-accent-500/20' },
  FIXING:     { label: 'Fixing',     color: 'text-success-700 dark:text-success-300', bgColor: 'bg-success-100 dark:bg-success-500/20' },
  INTERNAL:   { label: 'Internal',   color: 'text-warning-700 dark:text-warning-300', bgColor: 'bg-warning-100 dark:bg-warning-500/20' },
  CONTRACT:   { label: 'Contract',   color: 'text-primary-700 dark:text-primary-200', bgColor: 'bg-primary-100 dark:bg-primary-800/60' },
  INDICATIVE: { label: 'Indicative', color: 'text-neutral-700 dark:text-neutral-200', bgColor: 'bg-neutral-100 dark:bg-primary-800' },
};

const SOURCE_CONFIG: Record<string, { label: string; icon: any }> = {
  REUTERS: { label: 'Reuters', icon: Globe },
  BLOOMBERG: { label: 'Bloomberg', icon: TrendingUp },
  ECB: { label: 'ECB', icon: Building2 },
  FED: { label: 'Fed', icon: Building2 },
  BOE: { label: 'BOE', icon: Building2 },
  CBS: { label: 'CBS', icon: Database },
  SWIFT: { label: 'SWIFT', icon: Zap },
  API: { label: 'API', icon: Globe },
  MANUAL: { label: 'Manual', icon: Edit2 },
};

const CURRENCIES = ['USD', 'EUR', 'GBP', 'AED', 'SAR', 'JPY', 'CHF', 'CNY', 'INR', 'SGD'];

// Freshness model. 15 min is the SPOT freshness window — a treasurer
// pricing a same-day deal treats anything older as needing a re-pull.
// (Forward/fixing tolerate older data; per-type thresholds are a future
// tuning point, hence the single constant rather than inline literals.)
const FRESH_THRESHOLD_MS = 15 * 60 * 1000;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;

type EnrichedRate = FxRate & {
  _ageMs: number;
  _isStale: boolean;
  _isFresh: boolean;
  _updatedToday: boolean;
};

type StatusFilter = 'all' | 'fresh' | 'stale' | 'outdated' | 'inactive';

// Helper to extract data from API response
const extractData = <T,>(response: ApiResponse<T>): T => {
  if (response && response.data !== undefined) return response.data;
  return response as unknown as T;
};

// ============================================================================
// FRESHNESS BADGE — mirrors MultiBankLiquidity FreshnessPill tone vocabulary
// (components/multiBank/FreshnessPill.tsx). Age-thresholded rather than
// status-driven because FX rows carry a timestamp, not a fetch-status enum.
// ============================================================================
const FreshnessBadge: React.FC<{ ageMs: number; isActive: boolean }> = ({ ageMs, isActive }) => {
  if (!isActive) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-neutral-100 text-neutral-600 dark:bg-primary-800/60 dark:text-neutral-400">
        <MinusCircle className="w-3 h-3" /> Inactive
      </span>
    );
  }
  if (ageMs < FRESH_THRESHOLD_MS) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-success-100 text-success-700 dark:bg-success-500/15 dark:text-success-300">
        <CheckCircle2 className="w-3 h-3" /> Fresh
      </span>
    );
  }
  if (ageMs < ONE_DAY_MS) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-warning-100 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300">
        <AlertTriangle className="w-3 h-3" /> Stale
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs bg-error-100 text-error-700 dark:bg-error-500/15 dark:text-error-300">
      <XCircle className="w-3 h-3" /> Outdated
    </span>
  );
};

// ============================================================================
// RATES TABLE
// ============================================================================
interface RatesTableProps {
  rates: EnrichedRate[];
  onRowClick: (r: FxRate) => void;
  onRefreshRow: (r: FxRate) => void;
  refreshingIds: Set<string>;
}

const RatesTable: React.FC<RatesTableProps> = ({ rates, onRowClick, onRefreshRow, refreshingIds }) => {
  if (rates.length === 0) {
    return (
      <Card>
        <div className="p-12 text-center">
          <TrendingUp className="w-12 h-12 text-neutral-300 mx-auto mb-4 dark:text-neutral-600" />
          <p className="body">No FX rates match your filters</p>
          <p className="body-sm mt-1">Try clearing search and filters, or add a new rate.</p>
        </div>
      </Card>
    );
  }

  return (
    <Card>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="border-b border-neutral-200 dark:border-primary-800">
              <th className="label py-3 px-4 text-left">Pair</th>
              <th className="label py-3 px-4 text-right">Rate</th>
              <th className="label py-3 px-4 text-right">Inverse</th>
              <th className="label py-3 px-4 text-right">Bid</th>
              <th className="label py-3 px-4 text-right">Ask</th>
              <th className="label py-3 px-4 text-left">Type</th>
              <th className="label py-3 px-4 text-left">Source</th>
              <th className="label py-3 px-4 text-left">Updated</th>
              <th className="label py-3 px-4 text-left">Status</th>
              <th className="label py-3 px-4 text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {rates.map((rate) => (
              <RateRow
                key={rate.id}
                rate={rate}
                onClick={() => onRowClick(rate)}
                onRefresh={() => onRefreshRow(rate)}
                refreshing={refreshingIds.has(rate.id)}
              />
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
};

const RateRow: React.FC<{
  rate: EnrichedRate;
  onClick: () => void;
  onRefresh: () => void;
  refreshing: boolean;
}> = ({ rate, onClick, onRefresh, refreshing }) => {
  const typeConfig = RATE_TYPE_CONFIG[rate.rateType] ?? RATE_TYPE_CONFIG.SPOT;
  const sourceConfig = SOURCE_CONFIG[rate.rateSource] ?? SOURCE_CONFIG.MANUAL;
  const SourceIcon = sourceConfig.icon;
  const inverse = rate.inverseRate ?? 1 / rate.rate;

  return (
    <tr
      onClick={onClick}
      className="border-b border-neutral-100 dark:border-primary-800/60 hover:bg-neutral-50 dark:hover:bg-primary-800/40 cursor-pointer transition-colors"
    >
      {/* Pair — plain text, no gradient chips */}
      <td className="py-3 px-4">
        <div className="flex items-center gap-2">
          <span className="font-mono text-sm font-medium text-primary-900 dark:text-neutral-50">
            {rate.fromCurrency}
          </span>
          <ArrowRightLeft className="w-3 h-3 text-neutral-400" />
          <span className="font-mono text-sm font-medium text-primary-900 dark:text-neutral-50">
            {rate.toCurrency}
          </span>
        </div>
      </td>

      {/* Rate — the hero number */}
      <td className="py-3 px-4 text-right">
        <span className="stat-value-xs">{formatFxRate(rate.rate, rate.fromCurrency, rate.toCurrency)}</span>
      </td>

      {/* Inverse — muted */}
      <td className="py-3 px-4 text-right font-mono text-sm text-neutral-500 dark:text-neutral-400">
        {formatFxRate(inverse, rate.toCurrency, rate.fromCurrency)}
      </td>

      {/* Bid / Ask — muted unless present */}
      <td className="py-3 px-4 text-right font-mono text-sm">
        {rate.bidRate ? (
          <span className="text-success-700 dark:text-success-300">{formatFxRate(rate.bidRate, rate.fromCurrency, rate.toCurrency)}</span>
        ) : <span className="text-neutral-400 dark:text-neutral-500">—</span>}
      </td>
      <td className="py-3 px-4 text-right font-mono text-sm">
        {rate.askRate ? (
          <span className="text-error-700 dark:text-error-300">{formatFxRate(rate.askRate, rate.fromCurrency, rate.toCurrency)}</span>
        ) : <span className="text-neutral-400 dark:text-neutral-500">—</span>}
      </td>

      {/* Type — subtle tonal badge */}
      <td className="py-3 px-4">
        <span className={cn(
          'inline-flex items-center px-2 py-0.5 rounded-full text-xs font-medium',
          typeConfig.bgColor, typeConfig.color
        )}>
          {typeConfig.label}
        </span>
      </td>

      {/* Source — icon + label */}
      <td className="py-3 px-4">
        <div className="flex items-center gap-1.5 text-sm text-neutral-700 dark:text-neutral-200">
          <SourceIcon className="w-3.5 h-3.5 text-neutral-500 dark:text-neutral-400" />
          {sourceConfig.label}
        </div>
      </td>

      {/* Updated — relative time */}
      <td className="py-3 px-4 text-sm text-neutral-600 dark:text-neutral-300">
        {relativeTime(rate._ageMs)}
      </td>

      {/* Status — Fresh / Stale / Outdated / Inactive */}
      <td className="py-3 px-4">
        <FreshnessBadge ageMs={rate._ageMs} isActive={rate.isActive} />
      </td>

      {/* Actions — refresh this pair */}
      <td className="py-3 px-4 text-right">
        <button
          onClick={(e) => { e.stopPropagation(); onRefresh(); }}
          disabled={refreshing}
          aria-label={`Refresh ${rate.fromCurrency}/${rate.toCurrency}`}
          className="text-primary-600 hover:text-primary-700 dark:text-accent-400 dark:hover:text-accent-300 p-2 -m-2 rounded focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-400"
        >
          {refreshing
            ? <Loader2 className="w-4 h-4 animate-spin" />
            : <RefreshCw className="w-4 h-4" />}
        </button>
      </td>
    </tr>
  );
};

// ============================================================================
// DETAIL FIELD (drawer metadata grid cell)
// ============================================================================
const DetailField: React.FC<{ label: string; value: React.ReactNode }> = ({ label, value }) => (
  <div>
    <p className="label">{label}</p>
    <p className="body mt-1">{value}</p>
  </div>
);

// ============================================================================
// CONVERTER BODY — converter form, drawer-hosted (no Card chrome of its own)
// ============================================================================
const ConverterBody: React.FC = () => {
  const [fromCurrency, setFromCurrency] = useState('USD');
  const [toCurrency, setToCurrency] = useState('AED');
  const [amount, setAmount] = useState(1000);
  const [result, setResult] = useState<number | null>(null);
  const [loading, setLoading] = useState(false);

  const convert = useCallback(async () => {
    if (fromCurrency === toCurrency) {
      setResult(amount);
      return;
    }
    try {
      setLoading(true);
      const response = await fxRateApi.convert(amount, fromCurrency, toCurrency);
      const data = extractData(response);
      setResult(data.convertedAmount);
    } catch (error) {
      console.error('Conversion failed:', error);
      setResult(null);
    } finally {
      setLoading(false);
    }
  }, [fromCurrency, toCurrency, amount]);

  useEffect(() => {
    const timeout = setTimeout(convert, 300);
    return () => clearTimeout(timeout);
  }, [convert]);

  return (
    <div className="space-y-4">
      <div className="flex gap-2">
        <div className="flex-1">
          <Input type="number" value={amount} onChange={(e) => setAmount(parseFloat(e.target.value) || 0)} placeholder="Amount" />
        </div>
        <select
          value={fromCurrency}
          onChange={(e) => setFromCurrency(e.target.value)}
          className="px-3 py-2 border border-neutral-300 rounded-md bg-white text-sm field-label dark:border-primary-700 dark:bg-primary-900"
        >
          {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>
      <div className="flex items-center justify-center">
        <button
          type="button"
          onClick={() => { setFromCurrency(toCurrency); setToCurrency(fromCurrency); }}
          aria-label="Swap currencies"
          className="p-2 rounded-full hover:bg-neutral-100 transition-colors dark:hover:bg-primary-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-400"
        >
          <ArrowUpDown className="w-4 h-4 text-neutral-500 dark:text-neutral-400" />
        </button>
      </div>
      <div className="flex gap-2">
        <div className="flex-1">
          <div className="px-3 py-2 bg-success-50 rounded-lg dark:bg-success-500/10">
            <span className="stat-value-sm text-success-700 dark:text-success-300">
              {loading
                ? <Loader2 className="w-5 h-5 animate-spin" />
                : result !== null
                  ? result.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
                  : 'N/A'}
            </span>
          </div>
        </div>
        <select
          value={toCurrency}
          onChange={(e) => setToCurrency(e.target.value)}
          className="px-3 py-2 border border-neutral-300 rounded-md bg-white text-sm field-label dark:border-primary-700 dark:bg-primary-900"
        >
          {CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>
      {result !== null && !loading && (
        // Audit-trail disclosure. The /convert endpoint returns the rate used
        // but not its source/timestamp, so we surface the pair + that it is a
        // mid-rate rather than inventing an age we don't have.
        <p className="caption mt-2 text-center">
          Using {fromCurrency}/{toCurrency} mid-rate
        </p>
      )}
    </div>
  );
};

// ============================================================================
// MAIN PAGE
// ============================================================================

const FxRatesPage: React.FC = () => {
  const [rates, setRates] = useState<FxRate[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshingIds, setRefreshingIds] = useState<Set<string>>(new Set());
  const [searchTerm, setSearchTerm] = useState('');
  const [filterType, setFilterType] = useState<string>('');
  const [filterSource, setFilterSource] = useState<string>('');
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all');

  const [showCreateModal, setShowCreateModal] = useState(false);
  const [showDetailDrawer, setShowDetailDrawer] = useState(false);
  const [showConverterDrawer, setShowConverterDrawer] = useState(false);
  const [selectedRate, setSelectedRate] = useState<FxRate | null>(null);
  const [processing, setProcessing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [createForm, setCreateForm] = useState({
    fromCurrency: 'USD',
    toCurrency: 'AED',
    rate: '',
    rateType: 'SPOT',
    rateSource: 'MANUAL',
  });

  // Load rates from real API
  const loadRates = useCallback(async () => {
    try {
      setLoading(true);
      setError(null);
      const response = await fxRateApi.getAllActiveRates();
      const data = extractData(response);
      setRates(Array.isArray(data) ? data : []);
    } catch (err) {
      console.error('Failed to load FX rates:', err);
      setError('Failed to load FX rates. Please try again.');
      setRates([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadRates(); }, [loadRates]);

  // Refresh cache (all rates)
  const handleRefresh = async () => {
    try {
      setRefreshing(true);
      setError(null);
      await fxRateApi.refreshCache();
      await loadRates();
    } catch (err) {
      console.error('Failed to refresh rates:', err);
      setError('Failed to refresh rates.');
    } finally {
      setRefreshing(false);
    }
  };

  // Per-row refresh. No per-rate endpoint exists on fxRateApi, so a single
  // pair refresh collapses to a full cache refresh until the backend ships
  // a `/fx-rates/{id}/refresh` route. The spinner is still scoped to the
  // clicked row so the interaction reads as per-row.
  const handleRefreshOne = async (rate: FxRate) => {
    setRefreshingIds(prev => new Set(prev).add(rate.id));
    try {
      setError(null);
      await fxRateApi.refreshCache();
      await loadRates();
    } catch (err) {
      console.error('Failed to refresh rate:', err);
      setError('Failed to refresh rate.');
    } finally {
      setRefreshingIds(prev => {
        const next = new Set(prev);
        next.delete(rate.id);
        return next;
      });
    }
  };

  // Create rate
  const handleCreate = async () => {
    if (!createForm.rate) return;
    try {
      setProcessing(true);
      setError(null);
      await fxRateApi.createSpotRate(
        createForm.fromCurrency,
        createForm.toCurrency,
        parseFloat(createForm.rate),
        createForm.rateSource
      );
      await loadRates();
      setShowCreateModal(false);
      setCreateForm({ fromCurrency: 'USD', toCurrency: 'AED', rate: '', rateType: 'SPOT', rateSource: 'MANUAL' });
    } catch (err) {
      console.error('Failed to create rate:', err);
      setError('Failed to create FX rate.');
    } finally {
      setProcessing(false);
    }
  };

  // Operational freshness model
  const now = Date.now();
  const enriched: EnrichedRate[] = rates.map(r => {
    const ts = new Date(r.rateTimestamp).getTime();
    const ageMs = now - ts;
    const isStale = ageMs > FRESH_THRESHOLD_MS;
    const isFresh = !isStale && ageMs >= 0;
    const updatedToday = ageMs < ONE_DAY_MS;
    return { ...r, _ageMs: ageMs, _isStale: isStale, _isFresh: isFresh, _updatedToday: updatedToday };
  });

  const stats = {
    freshCount: enriched.filter(r => r._isFresh).length,
    staleCount: enriched.filter(r => r._isStale).length,
    updatedTodayCount: enriched.filter(r => r._updatedToday).length,
    pairs: new Set(enriched.map(r => `${r.fromCurrency}/${r.toCurrency}`)).size,
    lastRefreshLabel: enriched.length
      ? relativeTime(Math.min(...enriched.map(r => r._ageMs)))
      : 'Never',
  };

  // Filter — search matches the slash-joined pair, either currency, or the
  // source label; plus type / source / freshness-status facets.
  const filteredRates = enriched.filter(rate => {
    const matchesSearch = !searchTerm ||
      `${rate.fromCurrency}/${rate.toCurrency}`.toLowerCase().includes(searchTerm.toLowerCase()) ||
      rate.fromCurrency.toLowerCase().includes(searchTerm.toLowerCase()) ||
      rate.toCurrency.toLowerCase().includes(searchTerm.toLowerCase()) ||
      (SOURCE_CONFIG[rate.rateSource]?.label ?? '').toLowerCase().includes(searchTerm.toLowerCase());
    const matchesType = !filterType || rate.rateType === filterType;
    const matchesSource = !filterSource || rate.rateSource === filterSource;
    const matchesStatus =
      statusFilter === 'all' ? true :
      statusFilter === 'fresh' ? rate._isFresh :
      statusFilter === 'stale' ? rate._isStale :
      statusFilter === 'outdated' ? rate._ageMs > ONE_DAY_MS :
      statusFilter === 'inactive' ? !rate.isActive : true;
    return matchesSearch && matchesType && matchesSource && matchesStatus;
  });

  // Toolbar actions in the Aperture Layout header — registered before the
  // loading guard to satisfy the Rules of Hooks.
  usePageHeaderActions(
    () => (
      <>
        <Button
          variant="outline"
          size="sm"
          leftIcon={refreshing ? <Loader2 className="w-4 h-4 animate-spin" /> : <RefreshCw className="w-4 h-4" />}
          onClick={handleRefresh}
          disabled={refreshing}
        >
          Refresh Cache
        </Button>
        <Button
          variant="outline"
          size="sm"
          leftIcon={<ArrowRightLeft className="w-4 h-4" />}
          onClick={() => setShowConverterDrawer(true)}
        >
          Convert
        </Button>
        <Button
          size="sm"
          leftIcon={<Plus className="w-4 h-4" />}
          onClick={() => setShowCreateModal(true)}
        >
          Add Rate
        </Button>
      </>
    ),
    [refreshing]
  );

  if (loading) {
    return <div className="flex items-center justify-center h-96"><Loader2 className="w-8 h-8 animate-spin text-primary-600 dark:text-primary-200" /></div>;
  }

  return (
    <Page>
      <PageHeader
        title="FX Rates"
        description={
          <span>
            {stats.staleCount > 0 && (
              <span className="text-warning-700 dark:text-warning-300 font-medium">
                {stats.staleCount} rate{stats.staleCount === 1 ? '' : 's'} stale
                <span className="text-neutral-500 dark:text-neutral-400"> · </span>
              </span>
            )}
            Last cache refresh: {stats.lastRefreshLabel}
          </span>
        }
      />

      {/* Error Banner */}
      {error && (
        <Card className="bg-error-50 border-error-200 animate-fade-in dark:bg-error-500/10 dark:border-error-500/30">
          <div className="flex items-center justify-between p-4">
            <div className="flex items-center gap-3">
              <StatusIconBadge tone="error" icon={TrendingUp} className="dark:bg-error-500/20" />
              <span className="text-error-700 font-medium dark:text-error-300">{error}</span>
            </div>
            <Button variant="ghost" size="sm" onClick={() => setError(null)} aria-label="Dismiss error">
              <X className="w-4 h-4" />
            </Button>
          </div>
        </Card>
      )}

      {/* Operational stats */}
      <StatStrip>
        <StatTile label="Fresh" value={stats.freshCount} tone="success" icon={<CheckCircle2 className="w-5 h-5" />} />
        <StatTile
          label="Stale"
          value={stats.staleCount}
          tone="warning"
          icon={<AlertTriangle className="w-5 h-5" />}
          onClick={() => setStatusFilter(s => (s === 'stale' ? 'all' : 'stale'))}
          active={statusFilter === 'stale'}
        />
        <StatTile label="Updated today" value={stats.updatedTodayCount} tone="info" icon={<Clock className="w-5 h-5" />} />
        <StatTile label="Pairs" value={stats.pairs} tone="primary" icon={<ArrowRightLeft className="w-5 h-5" />} />
      </StatStrip>

      {/* Filter bar */}
      <Card>
        <div className="p-4 flex flex-wrap items-center gap-3">
          <div className="relative flex-1 min-w-[200px]">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-neutral-400" />
            <Input
              value={searchTerm}
              onChange={(e) => setSearchTerm(e.target.value)}
              placeholder="Search pair (USD/AED), currency, or source…"
              className="pl-9"
            />
          </div>
          <select
            value={filterType}
            onChange={(e) => setFilterType(e.target.value)}
            className="px-3 py-2 border border-neutral-300 rounded-md bg-white text-sm field-label dark:border-primary-700 dark:bg-primary-900"
          >
            <option value="">All types</option>
            {Object.entries(RATE_TYPE_CONFIG).map(([key, val]) => (
              <option key={key} value={key}>{val.label}</option>
            ))}
          </select>
          <select
            value={filterSource}
            onChange={(e) => setFilterSource(e.target.value)}
            className="px-3 py-2 border border-neutral-300 rounded-md bg-white text-sm field-label dark:border-primary-700 dark:bg-primary-900"
          >
            <option value="">All sources</option>
            {Object.entries(SOURCE_CONFIG).map(([key, val]) => (
              <option key={key} value={key}>{val.label}</option>
            ))}
          </select>
        </div>
      </Card>

      {/* Active status-filter chip */}
      {statusFilter !== 'all' && (
        <div className="flex items-center gap-2">
          <span className="body-sm">
            Filtered to <span className="font-medium text-primary-900 dark:text-neutral-50">{statusFilter}</span> rates
          </span>
          <button
            type="button"
            onClick={() => setStatusFilter('all')}
            className="inline-flex items-center gap-1 text-xs text-primary-600 hover:text-primary-700 dark:text-accent-400 dark:hover:text-accent-300 focus:outline-none focus-visible:ring-2 focus-visible:ring-accent-400 rounded"
          >
            <X className="w-3 h-3" /> Clear filter
          </button>
        </div>
      )}

      {/* The rates table */}
      <RatesTable
        rates={filteredRates}
        onRowClick={(r) => { setSelectedRate(r); setShowDetailDrawer(true); }}
        onRefreshRow={handleRefreshOne}
        refreshingIds={refreshingIds}
      />

      {/* Create Modal — confirmation flow that takes focus, stays a <Modal> */}
      <Modal isOpen={showCreateModal} onClose={() => setShowCreateModal(false)} title="Add FX Rate" size="lg">
        <div className="p-4 space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">From Currency *</label><select value={createForm.fromCurrency} onChange={(e) => setCreateForm(prev => ({ ...prev, fromCurrency: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">{CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}</select></div>
            <div><label className="field-label block mb-1">To Currency *</label><select value={createForm.toCurrency} onChange={(e) => setCreateForm(prev => ({ ...prev, toCurrency: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">{CURRENCIES.map(c => <option key={c} value={c}>{c}</option>)}</select></div>
          </div>
          <div>
            <label className="field-label block mb-1">Rate *</label>
            <Input type="number" step="0.000001" value={createForm.rate} onChange={(e) => setCreateForm(prev => ({ ...prev, rate: e.target.value }))} placeholder="3.6725" />
            <p className="caption mt-1">
              Enter the rate as 1 {createForm.fromCurrency} = X {createForm.toCurrency}
            </p>
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div><label className="field-label block mb-1">Rate Type</label><select value={createForm.rateType} onChange={(e) => setCreateForm(prev => ({ ...prev, rateType: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">{Object.entries(RATE_TYPE_CONFIG).map(([key, val]) => <option key={key} value={key}>{val.label}</option>)}</select></div>
            <div><label className="field-label block mb-1">Source</label><select value={createForm.rateSource} onChange={(e) => setCreateForm(prev => ({ ...prev, rateSource: e.target.value }))} className="w-full px-3 py-2 border border-neutral-300 rounded-lg dark:border-primary-700">{Object.entries(SOURCE_CONFIG).map(([key, val]) => <option key={key} value={key}>{val.label}</option>)}</select></div>
          </div>
          <div className="flex justify-end gap-2 pt-4 border-t">
            <Button variant="ghost" onClick={() => setShowCreateModal(false)}>Cancel</Button>
            <Button onClick={handleCreate} disabled={processing || !createForm.rate}>{processing ? <Loader2 className="w-4 h-4 mr-2 animate-spin" /> : <Plus className="w-4 h-4 mr-2" />}Create Rate</Button>
          </div>
        </div>
      </Modal>

      {/* Detail Drawer — context-preserving (table stays in view) */}
      <Drawer
        isOpen={showDetailDrawer}
        onClose={() => setShowDetailDrawer(false)}
        title={selectedRate ? `${selectedRate.fromCurrency} / ${selectedRate.toCurrency}` : 'Rate Details'}
        subtitle={selectedRate ? `${RATE_TYPE_CONFIG[selectedRate.rateType]?.label ?? selectedRate.rateType} · ${SOURCE_CONFIG[selectedRate.rateSource]?.label ?? selectedRate.rateSource}` : undefined}
        size="lg"
        footer={
          selectedRate ? (
            <div className="flex justify-between items-center">
              <Button variant="ghost" size="sm" onClick={() => {
                navigator.clipboard.writeText(String(selectedRate.rate));
                toast.success('Rate copied');
              }}>
                <Copy className="w-4 h-4 mr-1" /> Copy rate
              </Button>
              <div className="flex gap-2">
                <Button variant="outline" size="sm" onClick={() => handleRefreshOne(selectedRate)}>
                  <RefreshCw className="w-4 h-4 mr-1" /> Refresh this pair
                </Button>
                <Button size="sm" onClick={() => setShowDetailDrawer(false)}>Done</Button>
              </div>
            </div>
          ) : undefined
        }
      >
        {selectedRate && (
          <div className="p-6 space-y-6">
            {/* Hero rate value */}
            <div className="text-center py-6 border-y border-neutral-200 dark:border-primary-800">
              <p className="stat-value">{formatFxRate(selectedRate.rate, selectedRate.fromCurrency, selectedRate.toCurrency)}</p>
              <p className="body-sm mt-2">
                1 {selectedRate.fromCurrency} = {formatFxRate(selectedRate.rate, selectedRate.fromCurrency, selectedRate.toCurrency)} {selectedRate.toCurrency}
              </p>
              <p className="caption mt-1">
                1 {selectedRate.toCurrency} = {formatFxRate(selectedRate.inverseRate ?? 1 / selectedRate.rate, selectedRate.toCurrency, selectedRate.fromCurrency)} {selectedRate.fromCurrency}
              </p>
            </div>

            {/* Bid / Ask if present */}
            {(selectedRate.bidRate || selectedRate.askRate) && (
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <p className="label">Bid</p>
                  <p className="stat-value-xs text-success-700 dark:text-success-300 mt-1">
                    {selectedRate.bidRate ? formatFxRate(selectedRate.bidRate, selectedRate.fromCurrency, selectedRate.toCurrency) : '—'}
                  </p>
                </div>
                <div>
                  <p className="label">Ask</p>
                  <p className="stat-value-xs text-error-700 dark:text-error-300 mt-1">
                    {selectedRate.askRate ? formatFxRate(selectedRate.askRate, selectedRate.fromCurrency, selectedRate.toCurrency) : '—'}
                  </p>
                </div>
              </div>
            )}

            {/* Metadata grid */}
            <div className="grid grid-cols-2 gap-4">
              <DetailField label="Type" value={RATE_TYPE_CONFIG[selectedRate.rateType]?.label ?? selectedRate.rateType} />
              <DetailField label="Source" value={SOURCE_CONFIG[selectedRate.rateSource]?.label ?? selectedRate.rateSource} />
              <DetailField label="Rate date" value={selectedRate.rateDate} />
              <DetailField label="Last updated" value={formatDate(selectedRate.rateTimestamp)} />
              <DetailField label="Status" value={
                <FreshnessBadge
                  ageMs={Date.now() - new Date(selectedRate.rateTimestamp).getTime()}
                  isActive={selectedRate.isActive}
                />
              } />
              <DetailField label="Active" value={selectedRate.isActive ? 'Yes' : 'No'} />
            </div>

            {/* 7-day movement. fxRateApi.getHistoricalRates exists, but wiring
                a sparkline is out of scope for this lookup-first redesign —
                surfaced as an explicit next step rather than fake data. */}
            <div className="border border-neutral-200 dark:border-primary-800 rounded-lg p-6 text-center">
              <p className="label">7-day movement</p>
              <p className="body-sm mt-2">Historical rate trend will appear here (a getHistoricalRates-backed sparkline is the next enhancement).</p>
            </div>
          </div>
        )}
      </Drawer>

      {/* Converter Drawer — a tool, context-preserving */}
      <Drawer
        isOpen={showConverterDrawer}
        onClose={() => setShowConverterDrawer(false)}
        title="Currency Converter"
        subtitle="Mid-rate. Bid/ask available in row details."
        size="md"
      >
        <div className="p-6">
          <ConverterBody />
        </div>
      </Drawer>
    </Page>
  );
};

export default FxRatesPage;
