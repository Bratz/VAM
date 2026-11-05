// ============================================================================
// FxWorkspacePage — phase 1 of the corporate FX feature (tasks/fx-corporate-design.md):
// a ticking rate board and a forward curve, both from the simulated market in
// FxMarketController. Mid rates only; the bank's spread is negotiated per deal,
// so nothing here is a dealable price. Polled (no SSE/WebSocket exists in this
// app), and the board's history lives in the browser only.
// ============================================================================

import React, { useEffect, useRef, useState } from 'react';
import { Radio, AlertTriangle } from 'lucide-react';
import { Page } from '../components/layout/Page';
import { PageHeader } from '../components/layout/PageHeader';
import { Card, CardHeader, Badge, DataTable } from '../components/ui';
import { Tabs } from '../components/ui/enhanced';
import FxExposurePanel from './FxExposurePanel';
import type { Column } from '../components/ui/DataTable';
import { cn } from '../utils';
import { fxMarketApi, ForwardQuote, SpotTick } from '../services/fxMarketApi';

const SPOT_POLL_MS = 1000;
const CURVE_POLL_MS = 5000;
const HISTORY_POINTS = 60;

const digitsFor = (mid: number) => (mid >= 50 ? 3 : 5);
const fmtRate = (n: number, extra = 0) => n.toFixed(digitsFor(n) + extra);
const fmtSigned = (n: number, dp: number) => `${n > 0 ? '+' : ''}${n.toFixed(dp)}`;
const toneFor = (n: number) =>
  n > 0 ? 'text-success-700 dark:text-success-300' : n < 0 ? 'text-error-700 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400';

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string; error?: string } }; message?: string };
  return e?.response?.data?.message || e?.response?.data?.error || e?.message || 'Request failed';
};

const Sparkline: React.FC<{ values: number[]; tone: string }> = ({ values, tone }) => {
  if (values.length < 2) return <span className="inline-block w-24 h-6" />;
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const span = hi - lo || 1;
  const pts = values.map((v, i) => `${(i / (HISTORY_POINTS - 1)) * 96},${22 - ((v - lo) / span) * 20}`).join(' ');
  return (
    <svg width="96" height="24" viewBox="0 0 96 24" className={tone} aria-hidden="true">
      <polyline points={pts} fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  );
};

const FxMarketTab: React.FC = () => {
  const [ticks, setTicks] = useState<SpotTick[]>([]);
  const [feedError, setFeedError] = useState<string | null>(null);
  const [selected, setSelected] = useState('EUR/USD');
  const [curve, setCurve] = useState<ForwardQuote[]>([]);
  const [curveError, setCurveError] = useState<string | null>(null);
  const [valueDate, setValueDate] = useState('');
  const [custom, setCustom] = useState<ForwardQuote | null>(null);
  const [customError, setCustomError] = useState<string | null>(null);

  const history = useRef<Record<string, number[]>>({});
  const lastDir = useRef<Record<string, number>>({});

  useEffect(() => {
    let current = true;
    let inFlight = false;
    const load = async () => {
      if (inFlight || document.visibilityState === 'hidden') return;
      inFlight = true;
      try {
        const next = await fxMarketApi.spots();
        if (!current) return;
        for (const t of next) {
          const h = history.current[t.pair] ?? [];
          const prev = h[h.length - 1];
          if (prev !== undefined && t.mid !== prev) lastDir.current[t.pair] = Math.sign(t.mid - prev);
          history.current[t.pair] = [...h, t.mid].slice(-HISTORY_POINTS);
        }
        setTicks(next);
        setFeedError(null);
      } catch (err) {
        if (current) setFeedError(errorText(err));
      } finally {
        inFlight = false;
      }
    };
    load();
    const id = setInterval(load, SPOT_POLL_MS);
    return () => {
      current = false;
      clearInterval(id);
    };
  }, []);

  useEffect(() => {
    let current = true;
    const load = () =>
      fxMarketApi
        .curve(selected)
        .then((c) => { if (current) { setCurve(c); setCurveError(null); } })
        .catch((err) => { if (current) setCurveError(errorText(err)); });
    setCurve([]);
    load();
    const id = setInterval(load, CURVE_POLL_MS);
    return () => {
      current = false;
      clearInterval(id);
    };
  }, [selected]);

  useEffect(() => {
    let current = true;
    setCustom(null);
    setCustomError(null);
    if (valueDate) {
      fxMarketApi
        .forward(selected, valueDate)
        .then((q) => { if (current) setCustom(q); })
        .catch((err) => { if (current) setCustomError(errorText(err)); });
    }
    return () => { current = false; };
  }, [selected, valueDate]);

  const tick = ticks.find((t) => t.pair === selected);
  const first = curve[0];
  const asOf = ticks[0] ? new Date(ticks[0].asOf).toLocaleTimeString() : '';

  const boardColumns: Column<SpotTick>[] = [
    {
      key: 'pair',
      header: 'Pair',
      render: (_, t) =>
        t.pair === selected ? <Badge variant="primary" size="sm">{t.pair}</Badge> : <span className="font-medium">{t.pair}</span>,
    },
    {
      key: 'mid',
      header: 'Mid',
      align: 'right',
      render: (_, t) => (
        <span className={cn('font-mono tabular-nums', toneFor(lastDir.current[t.pair] ?? 0))}>{fmtRate(t.mid)}</span>
      ),
    },
    {
      key: 'changeBps',
      header: 'Change vs open',
      align: 'right',
      dropOrder: 2,
      render: (_, t) => (
        <span className={cn('font-mono tabular-nums', toneFor(t.changeBps))}>{fmtSigned(t.changeBps, 1)} bps</span>
      ),
    },
    {
      key: 'trend',
      header: 'Last minute',
      align: 'right',
      dropOrder: 1,
      mobileHidden: true,
      render: (_, t) => <Sparkline values={history.current[t.pair] ?? []} tone={toneFor(t.changeBps)} />,
    },
  ];

  const curveColumns: Column<ForwardQuote>[] = [
    { key: 'tenor', header: 'Tenor', render: (_, q) => <span className="font-medium">{q.tenor}</span> },
    { key: 'valueDate', header: 'Value date', render: (_, q) => <span className="font-mono tabular-nums">{q.valueDate}</span> },
    {
      key: 'forwardPoints',
      header: 'Points',
      align: 'right',
      render: (_, q) => (
        <span className={cn('font-mono tabular-nums', toneFor(q.forwardPoints))}>{fmtSigned(q.forwardPoints, 1)}</span>
      ),
    },
    {
      key: 'forwardMid',
      header: 'Forward',
      align: 'right',
      render: (_, q) => <span className="font-mono tabular-nums">{fmtRate(q.forwardMid, 1)}</span>,
    },
  ];

  return (
    <>
      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader
            title="Rate board"
            subtitle="Select a pair to see its forward curve."
            action={
              feedError ? (
                <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>Feed unavailable</Badge>
              ) : (
                <Badge variant="info" icon={<Radio className="w-3 h-3" />}>Simulated · {asOf || '…'}</Badge>
              )
            }
          />
          {feedError && <p className="text-body-sm text-error-700 dark:text-error-300 mb-3">{feedError}</p>}
          <DataTable<SpotTick>
            data={ticks}
            columns={boardColumns}
            keyExtractor={(t) => t.pair}
            onRowClick={(t) => setSelected(t.pair)}
            compact
            hairline
            emptyTitle="Waiting for rates"
            emptyDescription="The simulated market has not answered yet."
          />
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader
            title={`${selected} forward curve`}
            subtitle={tick ? `Spot ${fmtRate(tick.mid)} · value ${first?.spotDate ?? '…'}` : 'Waiting for the rate board'}
          />
          {curveError && <p className="text-body-sm text-error-700 dark:text-error-300 mb-3">{curveError}</p>}
          <DataTable<ForwardQuote>
            data={curve}
            columns={curveColumns}
            keyExtractor={(q) => q.tenor ?? q.valueDate}
            compact
            hairline
            emptyTitle="Loading curve"
            emptyDescription="Forward points appear once the pair has priced."
          />
          {first && (
            <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-3">
              Points follow the interest differential: {selected.slice(0, 3)} {first.baseRatePct.toFixed(2)}% against{' '}
              {selected.slice(4)} {first.quoteRatePct.toFixed(2)}%. Illustrative rates, not a market feed.
            </p>
          )}

          <div className="mt-5 pt-4 border-t border-edge">
            <label htmlFor="fx-value-date" className="block text-body-sm font-medium text-neutral-700 dark:text-neutral-200 mb-1.5">
              Price an exact value date
            </label>
            <input
              id="fx-value-date"
              type="date"
              value={valueDate}
              min={first?.spotDate}
              onChange={(e) => setValueDate(e.target.value)}
              className="w-full rounded-md border border-edge bg-surface-card px-3 py-2 text-body-sm text-primary-900 dark:text-neutral-50"
            />
            {customError && <p className="text-body-sm text-error-700 dark:text-error-300 mt-2">{customError}</p>}
            {custom && (
              <dl className="mt-3 grid grid-cols-3 gap-3 text-body-sm">
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">Days from spot</dt>
                  <dd className="font-mono tabular-nums">{custom.days}</dd>
                </div>
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">Points</dt>
                  <dd className={cn('font-mono tabular-nums', toneFor(custom.forwardPoints))}>{fmtSigned(custom.forwardPoints, 1)}</dd>
                </div>
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">Forward mid</dt>
                  <dd className="font-mono tabular-nums">{fmtRate(custom.forwardMid, 1)}</dd>
                </div>
              </dl>
            )}
          </div>
        </Card>
      </div>
    </>
  );
};

const TABS = [
  { id: 'market', label: 'Market' },
  { id: 'exposure', label: 'Exposure & hedging' },
];

const FxWorkspacePage: React.FC = () => {
  const [tab, setTab] = useState('market');
  return (
    <Page>
      <PageHeader
        title="FX Workspace"
        description="Indicative interbank rates and forward points, and each corporate's foreign-currency exposure against its hedge policy. Rates are simulated and are mid only, not dealable prices. Quoting and dealing arrive in the next phase."
      />
      <Tabs tabs={TABS} activeTab={tab} onChange={setTab} />
      {tab === 'market' ? <FxMarketTab /> : <FxExposurePanel />}
    </Page>
  );
};

export default FxWorkspacePage;
