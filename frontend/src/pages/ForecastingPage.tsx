/**
 * ForecastingPage — Sprint 1 / T11
 *
 * The Treasury Copilot's "where will my cash be in 13 weeks" surface. Sits
 * under Liquidity Management; data comes from the new T9 controller backed
 * by Pattern (recurring payables), Aging (open AR), and ManualOverlay
 * (carry-forward adjustments) engines orchestrated by T7.
 *
 * Sprint 1 deliverables:
 *   - Headline strip with horizon toggle, currency picker, "Run forecast" CTA
 *   - Three equal-weight StatTile metrics (Opening / Closing / Trough) — was
 *     three separate HeroMetricCard instances, which contradicts that
 *     component's own "single dominant figure per page" design intent
 *     (tokens.json); switched to StatTile since these three are meant to
 *     read as co-equal, not competing "heroes".
 *   - recharts ComposedChart — bars = weekly net cashflow, line = closing balance
 *   - Category breakdown table aggregated client-side from the raw lines
 *     endpoint (backend groupBy lands Sprint 2)
 *   - Click a week → side drawer showing the raw lines for that week, each
 *     with its sourceRef so a treasurer can trace back to the invoice / SI /
 *     adjustment that produced it
 *   - Empty state when no run exists for the corporate
 *
 * Deferred (documented inline in code):
 *   - Confidence band ribbon (P10/P90)  → needs Sprint 2 ML engine
 *   - Variance heat-map                  → Sprint 2 variance back-test
 *   - Adjustments worksheet              → Sprint 3
 *   - Scenarios toggle                   → Sprint 3
 *   - Excel export                       → Sprint 3
 */

import React, { useEffect, useMemo, useState } from 'react';
import {
  ResponsiveContainer,
  ComposedChart,
  Bar,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ReferenceArea,
  Cell,
} from 'recharts';
import {
  TrendingUp,
  RefreshCw,
  AlertTriangle,
  Wallet,
  ArrowDownRight,
  ArrowUpRight,
  Sparkles,
  Info,
} from 'lucide-react';
import toast from 'react-hot-toast';

import { Page } from '../components/layout/Page';
import { Card, Button, Skeleton, Badge, Drawer, StatTile } from '../components/ui';
import { StatStrip } from '../components/layout/StatStrip';
import { TileAmount } from '../components/TileAmount';
import { CurrencyPicker } from '../components/ui/CurrencyPicker';
import { useUser } from '../context/UserContext';
import { useTheme } from '../design-system/ThemeProvider';
import {
  forecastApi,
  ForecastSummary,
  ForecastLine as ForecastLineDto,
  ForecastSource,
  ForecastWeeklyBucket,
} from '../services/api';
import { formatCurrency, formatAmountForTile, cn } from '../utils';

// ============================================================================
// Constants
// ============================================================================

type Horizon = 30 | 91 | 365;
const HORIZON_OPTIONS: { value: Horizon; label: string }[] = [
  { value: 30, label: '30d' },
  { value: 91, label: '13w' },
  { value: 365, label: '12m' },
];

/** Tagging chips for line source — colour matches the category-of-origin hue. */
const SOURCE_LABELS: Record<ForecastSource, { label: string; tone: string }> = {
  PATTERN:  { label: 'Pattern', tone: 'bg-info-50 text-info-700 dark:bg-info-500/15 dark:text-info-300' },
  AGING:    { label: 'AR aging', tone: 'bg-success-50 text-success-700 dark:bg-success-500/15 dark:text-success-300' },
  ML:       { label: 'ML',      tone: 'bg-accent-50 text-accent-700 dark:bg-accent-500/15 dark:text-accent-300' },
  DRIVER:   { label: 'Driver',  tone: 'bg-primary-50 text-primary-700 dark:bg-primary-700/40 dark:text-primary-200' },
  MANUAL:   { label: 'Manual',  tone: 'bg-warning-50 text-warning-700 dark:bg-warning-500/15 dark:text-warning-300' },
};

// ============================================================================
// Theme-aware chart palette — re-resolved when light/dark flips
// ============================================================================

function useChartColors() {
  const { resolvedMode } = useTheme();
  const isDark = resolvedMode === 'dark';
  return useMemo(() => ({
    isDark,
    grid:          isDark ? '#4c5c68' : '#dcdcdd',  // primary-800 / neutral-200
    axisLine:      isDark ? '#595b5e' : '#dcdcdd',  // primary-700 / neutral-200
    tickFill:      isDark ? '#b5b6b7' : '#5d6165',  // primary-300 / neutral-500
    inflowBar:     isDark ? '#7da698' : '#578b7a',  // success-400 / success-500
    outflowBar:    isDark ? '#cb8f8a' : '#bb6d67',  // error-400 / error-500
    balanceLine:   isDark ? '#81bccb' : '#177891',  // accent-400 / accent-600
    shortfallArea: isDark ? 'rgba(187,109,103,0.14)' : 'rgba(187,109,103,0.08)', // error-500
    tooltipBg:     isDark ? '#343638' : '#ffffff',  // neutral-800
    tooltipText:   isDark ? '#f2f2f3' : '#46494c',  // neutral-50 / primary-900
    tooltipShadow: '0 4px 12px rgba(70,73,76,0.15)',
  }), [isDark]);
}

// ============================================================================
// Helpers
// ============================================================================

const ISO_WEEK_FMT = (iso: string) =>
  new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });

const FULL_DATE_FMT = (iso: string) =>
  new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' });

/** K/M/B axis tick, currency code stripped (the currency picker already scopes the whole chart). */
const formatAxisAmount = (v: number, currency: string) => {
  const compact = formatAmountForTile(v, currency);
  const spaceIdx = compact.indexOf(' ');
  return spaceIdx >= 0 ? compact.slice(spaceIdx + 1) : compact;
};

/** Tries to surface the most useful error blurb from an axios error envelope. */
function errorMessage(err: unknown, fallback: string): string {
  if (typeof err === 'object' && err !== null) {
    const e = err as { response?: { data?: { message?: string } }; message?: string };
    return e.response?.data?.message ?? e.message ?? fallback;
  }
  return fallback;
}

// ============================================================================
// MAIN PAGE
// ============================================================================

const ForecastingPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const [horizon, setHorizon] = useState<Horizon>(91);
  const [currency, setCurrency] = useState<string>('');

  // Server state — kept in local hooks for parity with the rest of the codebase
  // (which uses useEffect/useState rather than React Query for data pages).
  const [summary, setSummary] = useState<ForecastSummary | null>(null);
  const [loading, setLoading] = useState<boolean>(true);
  const [errored, setErrored] = useState<boolean>(false);
  const [emptyState, setEmptyState] = useState<boolean>(false);

  const [running, setRunning] = useState<boolean>(false);

  const [selectedWeek, setSelectedWeek] = useState<ForecastWeeklyBucket | null>(null);
  const [drawerLines, setDrawerLines] = useState<ForecastLineDto[] | null>(null);
  const [drawerLoading, setDrawerLoading] = useState<boolean>(false);

  // ------------------------------------------------------------------------
  // Fetch summary on mount + when filters change
  // ------------------------------------------------------------------------

  useEffect(() => {
    if (!currentCorporateId) return;
    let alive = true;
    setLoading(true);
    setErrored(false);
    setEmptyState(false);
    (async () => {
      try {
        const res = await forecastApi.getLatest({
          currency: currency || undefined,
          horizonDays: horizon,
        });
        if (!alive) return;
        if (res.success && res.data) {
          setSummary(res.data);
        } else {
          // 404 reported as { success:false } — that's the "no run yet" empty state.
          const msg = (res as any).error?.message ?? res.message ?? '';
          if (typeof msg === 'string' && msg.toLowerCase().includes('no completed')) {
            setEmptyState(true);
          } else {
            setErrored(true);
          }
        }
      } catch (err: unknown) {
        if (!alive) return;
        // 404 from the controller → empty state, not an error UI.
        const status = (err as any)?.response?.status;
        if (status === 404) setEmptyState(true);
        else setErrored(true);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, [currentCorporateId, currency, horizon]);

  // ------------------------------------------------------------------------
  // POST /run — fire forecast on demand
  // ------------------------------------------------------------------------

  const handleRunForecast = async () => {
    if (running) return;
    setRunning(true);
    try {
      const res = await forecastApi.triggerRun();
      if (res.success && res.data) {
        toast.success(`Forecast generated in ${res.data.generationMs ?? '<1'} ms`);
        // Re-fetch the summary so the chart picks up the new run.
        setLoading(true);
        const latest = await forecastApi.getLatest({
          currency: currency || undefined,
          horizonDays: horizon,
        });
        if (latest.success && latest.data) {
          setSummary(latest.data);
          setEmptyState(false);
        }
      } else {
        toast.error(res.message ?? 'Forecast run failed');
      }
    } catch (err: unknown) {
      const status = (err as any)?.response?.status;
      if (status === 409) {
        toast.error('A forecast is already running for this corporate. Try again in a moment.');
      } else {
        toast.error(errorMessage(err, 'Forecast run failed'));
      }
    } finally {
      setRunning(false);
      setLoading(false);
    }
  };

  // ------------------------------------------------------------------------
  // Click a chart bar → load that week's raw lines into the drawer
  // ------------------------------------------------------------------------

  const openWeekDrawer = async (bucket: ForecastWeeklyBucket) => {
    if (!summary) return;
    setSelectedWeek(bucket);
    setDrawerLines(null);
    setDrawerLoading(true);
    try {
      const res = await forecastApi.getLines(summary.runId, {
        fromDate: bucket.weekStart,
        toDate: bucket.weekEnd,
      });
      if (res.success && res.data) {
        // Sort by descending magnitude so the biggest movers surface first.
        const sorted = [...res.data].sort(
          (a, b) => Math.abs(b.amountMid) - Math.abs(a.amountMid)
        );
        setDrawerLines(sorted);
      } else {
        setDrawerLines([]);
      }
    } catch {
      setDrawerLines([]);
    } finally {
      setDrawerLoading(false);
    }
  };

  // ------------------------------------------------------------------------
  // Derived chart data
  // ------------------------------------------------------------------------

  const chartData = useMemo(() => {
    if (!summary?.weeklyBuckets) return [];
    return summary.weeklyBuckets.map((b) => ({
      bucket: b,
      label: ISO_WEEK_FMT(b.weekStart),
      net: Number(b.netCashflow ?? 0),
      closing: Number(b.closingBalance ?? 0),
    }));
  }, [summary]);

  // X-axis indices where closingBalance < 0 — paint a red reference area there.
  const shortfallSpans = useMemo(() => {
    if (chartData.length === 0) return [];
    const spans: { from: number; to: number }[] = [];
    let active: number | null = null;
    chartData.forEach((d, i) => {
      if (d.closing < 0 && active === null) active = i;
      if (d.closing >= 0 && active !== null) {
        spans.push({ from: active, to: i - 1 });
        active = null;
      }
    });
    if (active !== null) spans.push({ from: active, to: chartData.length - 1 });
    return spans;
  }, [chartData]);

  const colors = useChartColors();
  const ccyForFormat = summary?.currency || currency || 'USD';

  // ------------------------------------------------------------------------
  // Render
  // ------------------------------------------------------------------------

  return (
    <Page>
      {/* ============ Header strip ============ */}
      <Card padding="md">
        <div className="flex items-center justify-between flex-wrap gap-4">
          <div>
            <div className="flex items-center gap-2 text-xs font-semibold text-neutral-500 dark:text-neutral-400 mb-1">
              <Sparkles className="w-3.5 h-3.5 text-accent-500" />
              Cash Forecast
            </div>
            <p className="body-sm text-neutral-600 dark:text-neutral-300 max-w-xl">
              {summary
                ? `Run generated ${new Date(summary.runAt).toLocaleString('en-GB')} · horizon to ${FULL_DATE_FMT(summary.horizonEnd)}`
                : 'Forecast lines projected from open AR, recurring payables, and carry-forward treasurer adjustments.'}
            </p>
          </div>

          <div className="flex items-center gap-3 flex-wrap">
            {/* Horizon toggle */}
            <div role="tablist" aria-label="Forecast horizon" className="inline-flex rounded-xl border border-neutral-200 dark:border-primary-800/60 overflow-hidden">
              {HORIZON_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  role="tab"
                  aria-selected={horizon === opt.value}
                  onClick={() => setHorizon(opt.value)}
                  className={cn(
                    'px-3 py-1.5 text-sm font-medium transition-colors',
                    horizon === opt.value
                      ? 'bg-primary-900 text-white dark:bg-primary-700'
                      : 'bg-white text-neutral-600 hover:bg-neutral-50 dark:bg-primary-900/40 dark:text-neutral-300 dark:hover:bg-primary-800/60'
                  )}
                >
                  {opt.label}
                </button>
              ))}
            </div>

            {/* Currency filter — empty = all currencies summed (engines emit native ccy) */}
            <CurrencyPicker
              value={currency}
              onChange={setCurrency}
              allowEmpty
              emptyLabel="All ccy"
              className="h-9 text-sm rounded-xl border-neutral-200 dark:border-primary-800/60"
            />

            <Button
              variant="primary"
              onClick={handleRunForecast}
              loading={running}
              disabled={!currentCorporateId}
              className="inline-flex items-center gap-2"
            >
              <RefreshCw className={cn('w-4 h-4', running && 'animate-spin')} />
              Run forecast now
            </Button>
          </div>
        </div>
      </Card>

      {/* ============ Body switch ============ */}
      {!currentCorporateId ? (
        <Card padding="lg">
          <div className="text-center py-8">
            <Wallet className="w-12 h-12 mx-auto text-neutral-300 mb-3" />
            <p className="body text-neutral-500 dark:text-neutral-400">
              Pick a corporate from the entity picker to view its forecast.
            </p>
          </div>
        </Card>
      ) : loading ? (
        <LoadingState />
      ) : errored ? (
        <Card padding="lg">
          <div className="text-center py-10">
            <AlertTriangle className="w-10 h-10 mx-auto text-error-500 mb-3" />
            <p className="body text-error-600 dark:text-error-300">
              Couldn't load the forecast. Try "Run forecast now" or refresh the page.
            </p>
          </div>
        </Card>
      ) : emptyState || !summary ? (
        <EmptyState onRun={handleRunForecast} running={running} />
      ) : (
        <>
          <MetricStrip summary={summary} currency={ccyForFormat} horizon={horizon} />

          <Card padding="md">
            <div className="flex items-center justify-between mb-3">
              <h2 className="section-title">Weekly cash position</h2>
              <span className="body-xs text-neutral-500 dark:text-neutral-400 inline-flex items-center gap-1">
                <Info className="w-3.5 h-3.5" />
                Click a bar to see the lines that produced it
              </span>
            </div>
            <ChartBlock
              data={chartData}
              shortfallSpans={shortfallSpans}
              colors={colors}
              currency={ccyForFormat}
              onSelectBucket={(b) => openWeekDrawer(b)}
            />
          </Card>

          <CategoryBreakdownCard
            runId={summary.runId}
            weeklyBuckets={summary.weeklyBuckets}
            currency={ccyForFormat}
          />
        </>
      )}

      {/* ============ Side drawer ============ */}
      <WeekDrawer
        bucket={selectedWeek}
        lines={drawerLines}
        loading={drawerLoading}
        currency={ccyForFormat}
        onClose={() => { setSelectedWeek(null); setDrawerLines(null); }}
      />
    </Page>
  );
};

export default ForecastingPage;

// ============================================================================
// MetricStrip — three equal-weight StatTile metrics
// ============================================================================

const MetricStrip: React.FC<{ summary: ForecastSummary; currency: string; horizon: Horizon }> = ({ summary, currency, horizon }) => {
  const closing = Number(summary.closingBalance ?? 0);
  // Kept the same three-way read on the closing balance the old trend chip
  // used, now driving the number's own colour via valueTone instead of a
  // separate (barely-visible) chip next to a value that stayed neutral.
  const closingValueTone: 'success' | 'danger' | 'neutral' =
    closing < 0 ? 'danger' : closing < 1_000_000 ? 'neutral' : 'success';

  const trough = summary.troughWeek;

  return (
    <StatStrip>
      <StatTile
        tone="accent"
        icon={<Wallet className="w-5 h-5" />}
        label="Opening balance"
        value={<TileAmount value={Number(summary.openingBalance ?? 0)} currency={currency} />}
        sub="Starting point for this projection"
        delay="0.05s"
      />

      <StatTile
        tone="accent"
        valueTone={closingValueTone}
        icon={<TrendingUp className="w-5 h-5" />}
        label={`Closing (${HORIZON_OPTIONS.find(o => o.value === horizon)?.label ?? '13w'})`}
        value={<TileAmount value={closing} currency={currency} />}
        sub={closing < 0 ? 'Shortfall projected' : undefined}
        delay="0.10s"
      />

      <StatTile
        tone="danger"
        valueTone="neutral"
        icon={<ArrowDownRight className="w-5 h-5" />}
        label="Trough week"
        value={trough ? <TileAmount value={Number(trough.closingBalance ?? 0)} currency={currency} /> : '—'}
        sub={trough ? `w/c ${FULL_DATE_FMT(trough.weekStart)}` : undefined}
        delay="0.15s"
      />
    </StatStrip>
  );
};

// ============================================================================
// ChartBlock — recharts ComposedChart
// ============================================================================

interface ChartBlockProps {
  data: { bucket: ForecastWeeklyBucket; label: string; net: number; closing: number }[];
  shortfallSpans: { from: number; to: number }[];
  colors: ReturnType<typeof useChartColors>;
  currency: string;
  onSelectBucket: (b: ForecastWeeklyBucket) => void;
}

const ChartBlock: React.FC<ChartBlockProps> = ({ data, shortfallSpans, colors, currency, onSelectBucket }) => {
  if (data.length === 0) {
    return (
      <div className="h-[300px] flex items-center justify-center">
        <p className="body-sm text-neutral-500 dark:text-neutral-400">
          The forecast contains no weekly buckets in this horizon.
        </p>
      </div>
    );
  }

  return (
    <div className="h-[360px]">
      <ResponsiveContainer width="100%" height="100%">
        <ComposedChart data={data} margin={{ top: 10, right: 20, left: 0, bottom: 10 }}>
          <CartesianGrid stroke={colors.grid} strokeDasharray="3 3" vertical={false} />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 11, fill: colors.tickFill }}
            tickLine={false}
            axisLine={{ stroke: colors.axisLine }}
          />
          <YAxis
            yAxisId="left"
            tick={{ fontSize: 11, fill: colors.tickFill }}
            tickLine={false}
            axisLine={false}
            tickFormatter={(v: number) => formatAxisAmount(v, currency)}
            width={70}
          />
          <YAxis
            yAxisId="right"
            orientation="right"
            tick={{ fontSize: 11, fill: colors.tickFill }}
            tickLine={false}
            axisLine={false}
            tickFormatter={(v: number) => formatAxisAmount(v, currency)}
            width={70}
          />
          <Tooltip
            cursor={{ fill: colors.isDark ? 'rgba(255,255,255,0.04)' : 'rgba(70,73,76,0.04)' }}
            contentStyle={{
              backgroundColor: colors.tooltipBg,
              border: 'none',
              borderRadius: '12px',
              boxShadow: colors.tooltipShadow,
              padding: '12px 16px',
              color: colors.tooltipText,
            }}
            labelStyle={{ color: colors.tooltipText, fontWeight: 600 }}
            itemStyle={{ color: colors.tooltipText }}
            formatter={(value: number, name: string) => [formatCurrency(value, currency), name]}
          />
          <Legend
            wrapperStyle={{ paddingTop: '8px', color: colors.tickFill, fontSize: 12 }}
            iconType="circle"
          />

          {/* Red shading where projected closing balance dips below 0. */}
          {shortfallSpans.map((s, i) => (
            <ReferenceArea
              key={i}
              yAxisId="right"
              x1={data[s.from].label}
              x2={data[s.to].label}
              fill={colors.shortfallArea}
              ifOverflow="visible"
            />
          ))}

          {/* Bars — left axis. Click handler attached on the whole bar series; per-bar Cell colours the bar by sign. */}
          <Bar
            yAxisId="left"
            dataKey="net"
            name="Net cashflow"
            fill={colors.inflowBar}
            radius={[4, 4, 0, 0]}
            onClick={(d: any) => d?.bucket && onSelectBucket(d.bucket as ForecastWeeklyBucket)}
            cursor="pointer"
          >
            {data.map((d, i) => (
              <Cell key={i} fill={d.net >= 0 ? colors.inflowBar : colors.outflowBar} />
            ))}
          </Bar>

          {/* Line — right axis, cumulative closing balance. */}
          <Line
            yAxisId="right"
            type="monotone"
            dataKey="closing"
            name="Closing balance"
            stroke={colors.balanceLine}
            strokeWidth={2.5}
            dot={{ r: 3, strokeWidth: 0, fill: colors.balanceLine }}
            activeDot={{ r: 5 }}
          />
        </ComposedChart>
      </ResponsiveContainer>
    </div>
  );
};

// ============================================================================
// CategoryBreakdownCard — client-side aggregation by category × week
// ============================================================================

interface CategoryBreakdownCardProps {
  runId: string;
  weeklyBuckets: ForecastWeeklyBucket[];
  currency: string;
}

const CategoryBreakdownCard: React.FC<CategoryBreakdownCardProps> = ({ runId, weeklyBuckets, currency }) => {
  const [lines, setLines] = useState<ForecastLineDto[] | null>(null);
  const [loading, setLoading] = useState<boolean>(true);

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setLines(null);
    (async () => {
      try {
        // Backend ignores groupBy in Sprint 1; we aggregate client-side.
        const res = await forecastApi.getLines(runId, {});
        if (alive) setLines(res.success && res.data ? res.data : []);
      } catch {
        if (alive) setLines([]);
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, [runId]);

  // Build a (category × week-index) sum matrix.
  const table = useMemo(() => {
    if (!lines || weeklyBuckets.length === 0) return null;

    const weekStarts = weeklyBuckets.map((b) => new Date(b.weekStart).getTime());
    const cellAt = (lineDate: string): number => {
      // Find the week whose [start, end] contains lineDate.
      const t = new Date(lineDate).getTime();
      for (let i = 0; i < weeklyBuckets.length; i++) {
        const start = weekStarts[i];
        const end = new Date(weeklyBuckets[i].weekEnd).getTime();
        if (t >= start && t <= end) return i;
      }
      return -1;
    };

    const grid: Record<string, { label: string; cells: number[]; total: number }> = {};
    for (const l of lines) {
      const idx = cellAt(l.valueDate);
      if (idx === -1) continue;
      const key = l.categoryCode || 'UNCATEGORISED';
      if (!grid[key]) {
        grid[key] = {
          label: l.categoryLabel || key,
          cells: new Array(weeklyBuckets.length).fill(0),
          total: 0,
        };
      }
      // amountMid is always a positive magnitude (category.direction carries
      // sign) — sign it here so OUT categories aggregate and colour as
      // outflows instead of every category reading as a green inflow.
      const signed = l.direction === 'OUT' ? -Number(l.amountMid ?? 0) : Number(l.amountMid ?? 0);
      grid[key].cells[idx] += signed;
      grid[key].total += signed;
    }
    const rows = Object.entries(grid).map(([code, v]) => ({ code, ...v }));
    rows.sort((a, b) => Math.abs(b.total) - Math.abs(a.total));
    return rows;
  }, [lines, weeklyBuckets]);

  return (
    <Card padding="md">
      <div className="flex items-center justify-between mb-3">
        <h2 className="section-title">Category breakdown</h2>
        <span className="body-xs text-neutral-500 dark:text-neutral-400">
          Aggregated from {lines?.length ?? 0} line{(lines?.length ?? 0) === 1 ? '' : 's'}
        </span>
      </div>
      {loading ? (
        <div className="space-y-2">
          {[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-8 rounded" />)}
        </div>
      ) : !table || table.length === 0 ? (
        <p className="body-sm text-neutral-500 dark:text-neutral-400 py-4 text-center">
          No lines in this horizon yet.
        </p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-neutral-200 dark:border-primary-800/60">
                <th className="sticky left-0 bg-white dark:bg-primary-900 text-left py-2 pr-3 text-xs uppercase tracking-wider text-neutral-500 dark:text-neutral-400 font-semibold">
                  Category
                </th>
                {weeklyBuckets.map((b) => (
                  <th
                    key={b.weekStart}
                    className="text-right py-2 px-2 text-xs uppercase tracking-wider text-neutral-500 dark:text-neutral-400 font-semibold whitespace-nowrap"
                  >
                    {ISO_WEEK_FMT(b.weekStart)}
                  </th>
                ))}
                <th className="text-right py-2 pl-3 text-xs uppercase tracking-wider text-neutral-500 dark:text-neutral-400 font-semibold whitespace-nowrap">
                  Total
                </th>
              </tr>
            </thead>
            <tbody>
              {table.map((row) => (
                <tr key={row.code} className="border-b border-neutral-100 dark:border-primary-800/40 hover:bg-neutral-50 dark:hover:bg-primary-800/30">
                  <td className="sticky left-0 bg-white dark:bg-primary-900 text-left py-2 pr-3 font-medium text-primary-900 dark:text-neutral-100">
                    {row.label}
                  </td>
                  {row.cells.map((v, i) => (
                    <td key={i} className={cn(
                      'text-right py-2 px-2 amount',
                      v === 0 ? 'text-neutral-300 dark:text-neutral-600'
                        : v > 0 ? 'text-success-600 dark:text-success-300'
                        : 'text-error-600 dark:text-error-300'
                    )}>
                      {v === 0 ? '—' : <TileAmount value={v} currency={currency} />}
                    </td>
                  ))}
                  <td className={cn(
                    'text-right py-2 pl-3 amount font-semibold',
                    row.total > 0 ? 'text-success-700 dark:text-success-200'
                      : row.total < 0 ? 'text-error-700 dark:text-error-200'
                      : 'text-neutral-500'
                  )}>
                    <TileAmount value={row.total} currency={currency} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
};

// ============================================================================
// WeekDrawer — slide-in panel for a week's raw lines
// ============================================================================

interface WeekDrawerProps {
  bucket: ForecastWeeklyBucket | null;
  lines: ForecastLineDto[] | null;
  loading: boolean;
  currency: string;
  onClose: () => void;
}

const WeekDrawer: React.FC<WeekDrawerProps> = ({ bucket, lines, loading, currency, onClose }) => {
  if (!bucket) return null;
  return (
    <Drawer
      isOpen
      onClose={onClose}
      size="md"
      title={(
        <>
          <span className="block text-xs font-semibold text-neutral-500 dark:text-neutral-400 mb-1">
            Week of {FULL_DATE_FMT(bucket.weekStart)}
          </span>
          Net {formatCurrency(Number(bucket.netCashflow ?? 0), currency)}
        </>
      )}
      subtitle={`Closing ${formatCurrency(Number(bucket.closingBalance ?? 0), currency)}`}
    >
        <div className="p-5">
          {loading ? (
            <div className="space-y-2">
              {[1, 2, 3, 4, 5].map((i) => <Skeleton key={i} className="h-12 rounded-lg" />)}
            </div>
          ) : !lines || lines.length === 0 ? (
            <p className="body-sm text-neutral-500 dark:text-neutral-400 text-center py-8">
              No lines in this week.
            </p>
          ) : (
            <ul className="space-y-2">
              {lines.map((line) => (
                <li
                  key={line.id}
                  className="border border-neutral-200 dark:border-primary-800/60 rounded-xl px-3 py-2.5 bg-neutral-50/40 dark:bg-primary-950/40"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-medium text-primary-900 dark:text-neutral-100">
                          {line.categoryLabel || line.categoryCode || 'Uncategorised'}
                        </span>
                        <span className={cn(
                          'text-xs font-semibold px-1.5 py-0 leading-4 rounded-full',
                          SOURCE_LABELS[line.source]?.tone ?? 'bg-neutral-100 text-neutral-600'
                        )}>
                          {SOURCE_LABELS[line.source]?.label ?? line.source}
                        </span>
                      </div>
                      <p className="text-xs text-neutral-500 dark:text-neutral-400 mt-0.5">
                        {FULL_DATE_FMT(line.valueDate)} · {line.currency}
                      </p>
                      {line.sourceRef && (
                        <p className="text-xs font-mono text-neutral-400 dark:text-neutral-500 mt-0.5 truncate">
                          {line.sourceRef}
                        </p>
                      )}
                    </div>
                    <div className="text-right shrink-0">
                      <span className={cn(
                        'inline-flex items-center gap-1 amount text-sm font-semibold',
                        line.direction === 'OUT' ? 'text-error-600 dark:text-error-300' : 'text-success-600 dark:text-success-300'
                      )}>
                        {line.direction === 'OUT' ? <ArrowDownRight className="w-3.5 h-3.5" /> : <ArrowUpRight className="w-3.5 h-3.5" />}
                        {formatCurrency(Math.abs(Number(line.amountMid ?? 0)), line.currency || currency)}
                      </span>
                      {line.confidence != null && (
                        <div className="mt-1">
                          <Badge variant="neutral">
                            {Math.round(Number(line.confidence) * 100)}% conf
                          </Badge>
                        </div>
                      )}
                    </div>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>
    </Drawer>
  );
};

// ============================================================================
// Empty + loading states
// ============================================================================

const EmptyState: React.FC<{ onRun: () => void; running: boolean }> = ({ onRun, running }) => (
  <Card padding="lg">
    <div className="text-center py-12">
      <div className="w-16 h-16 rounded-2xl bg-accent-100 dark:bg-accent-500/15 ring-1 ring-accent-200 dark:ring-accent-500/30 flex items-center justify-center mx-auto mb-4">
        <Sparkles className="w-8 h-8 text-accent-600 dark:text-accent-300" />
      </div>
      <h2 className="page-title-display text-2xl text-primary-900 dark:text-neutral-50">
        No forecast yet
      </h2>
      <p className="body text-neutral-500 dark:text-neutral-400 mt-3 max-w-md mx-auto">
        Run your first forecast to see a 13-week cash position projected from open AR, recurring payables, and treasurer adjustments.
      </p>
      <Button
        variant="primary"
        onClick={onRun}
        loading={running}
        className="mt-6 inline-flex items-center gap-2"
      >
        <RefreshCw className={cn('w-4 h-4', running && 'animate-spin')} />
        Run forecast now
      </Button>
    </div>
  </Card>
);

const LoadingState: React.FC = () => (
  <>
    <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
      {[1, 2, 3].map((i) => (
        <Skeleton key={i} className="h-32 rounded-2xl" />
      ))}
    </div>
    <Card padding="md">
      <Skeleton className="h-6 w-40 mb-4" />
      <Skeleton className="h-[300px] rounded-xl" />
    </Card>
    <Card padding="md">
      <Skeleton className="h-6 w-40 mb-4" />
      <div className="space-y-2">
        {[1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-8 rounded" />)}
      </div>
    </Card>
  </>
);
