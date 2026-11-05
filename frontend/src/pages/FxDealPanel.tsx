// ============================================================================
// FxDealPanel — phase 3 of the corporate FX feature (tasks/fx-corporate-design.md):
// request a price from the bank's (simulated) desk, accept it inside its validity window,
// and follow the resulting deals. The price is firm only for a short time; the countdown
// comes from the server clock, and accepting re-checks validity and the credit limit on
// the server, so what the screen shows is never the authority.
// ============================================================================

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Send, Check, X, Clock, Play } from 'lucide-react';
import toast from 'react-hot-toast';
import { ScopeSelector } from '../components/layout/ScopeSelector';
import { Card, CardHeader, Badge, Button, Input, Select, DataTable } from '../components/ui';
import type { Column } from '../components/ui/DataTable';
import { Alert, ProgressBar } from '../components/ui/enhanced';
import FxDealDrawer from './FxDealDrawer';
import { DEAL_BADGE, confirmationBadge } from './fxDealBadges';
import { cn } from '../utils';
import { fxMarketApi } from '../services/fxMarketApi';
import {
  fxDealApi, CreditLimit, Deal, Instrument, Quote, QuoteStatus, SettlementRun, Side,
} from '../services/fxDealApi';

export interface DealPrefill {
  corporateId: string;
  pair: string;
  side: Side;
  amount: number;
  /** 1W..12M; resolved to a value date from the forward curve. */
  tenor: string;
  exposureRef: string;
}

const fmt = (n: number, dp = 2) => n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
const rateDigits = (n: number) => (n >= 50 ? 4 : 6);

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || 'Request failed';
};

const QUOTE_BADGE: Record<QuoteStatus, { label: string; variant: 'success' | 'warning' | 'error' | 'neutral' | 'info' }> = {
  REQUESTED: { label: 'Requested', variant: 'neutral' },
  QUOTED: { label: 'Firm price', variant: 'info' },
  ACCEPTED: { label: 'Accepted', variant: 'success' },
  EXPIRED: { label: 'Expired', variant: 'warning' },
  REJECTED: { label: 'Declined', variant: 'error' },
  CANCELLED: { label: 'Cancelled', variant: 'neutral' },
};

const INSTRUMENTS: { value: Instrument; label: string }[] = [
  { value: 'SPOT', label: 'Spot' },
  { value: 'FORWARD', label: 'Forward' },
  { value: 'WINDOW', label: 'Window forward' },
];

const FxDealPanel: React.FC<{ prefill: DealPrefill | null; onPrefillUsed: () => void }> = ({ prefill, onPrefillUsed }) => {
  const [corporates, setCorporates] = useState<{ id: string; legalName?: string; tradeName?: string }[]>([]);
  const [corporateId, setCorporateId] = useState('');
  const [pairs, setPairs] = useState<string[]>([]);

  const [pair, setPair] = useState('EUR/USD');
  const [side, setSide] = useState<Side>('BUY');
  const [amount, setAmount] = useState('100000');
  const [instrument, setInstrument] = useState<Instrument>('SPOT');
  const [valueDate, setValueDate] = useState('');
  const [windowStart, setWindowStart] = useState('');
  const [purpose, setPurpose] = useState('');
  const [exposureRef, setExposureRef] = useState('');

  const [quote, setQuote] = useState<Quote | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [requesting, setRequesting] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);

  const [deals, setDeals] = useState<Deal[]>([]);
  const [limit, setLimit] = useState<CreditLimit | null>(null);
  const [openId, setOpenId] = useState<string | null>(null);
  const [asOf, setAsOf] = useState('');
  const [runResult, setRunResult] = useState<SettlementRun | null>(null);
  const [running, setRunning] = useState(false);
  const [runError, setRunError] = useState<string | null>(null);

  const quoteRef = useRef<Quote | null>(null);
  quoteRef.current = quote;

  useEffect(() => {
    let current = true;
    Promise.all([fetch('/api/v1/corporates').then((r) => r.json()), fxMarketApi.spots().catch(() => [])])
      .then(([result, spots]) => {
        if (!current) return;
        const list = (result.data || result || []) as typeof corporates;
        setCorporates(list);
        setCorporateId((prev) => prev || list[0]?.id || '');
        const listed = spots.map((s) => s.pair);
        setPairs([...new Set([...listed, ...listed.map((p) => `${p.slice(4)}/${p.slice(0, 3)}`)])].sort());
      })
      .catch((err) => current && setFormError(errorText(err)));
    return () => { current = false; };
  }, []);

  const refresh = useCallback(async (id: string) => {
    const [d, l] = await Promise.all([fxDealApi.deals(id), fxDealApi.limit(id)]);
    setDeals(d);
    setLimit(l);
  }, []);

  useEffect(() => {
    if (!corporateId) return;
    let current = true;
    setQuote(null);
    setDeals([]);
    setLimit(null);
    Promise.all([fxDealApi.deals(corporateId), fxDealApi.limit(corporateId)])
      .then(([d, l]) => { if (current) { setDeals(d); setLimit(l); setFormError(null); } })
      .catch((err) => current && setFormError(errorText(err)));
    return () => { current = false; };
  }, [corporateId]);

  // A hedge suggested on the Exposure tab arrives here as a filled-in ticket.
  const applied = useRef<DealPrefill | null>(null);
  useEffect(() => {
    if (!prefill || applied.current === prefill) return;
    applied.current = prefill;
    setCorporateId(prefill.corporateId);
    setPair(prefill.pair);
    setSide(prefill.side);
    setAmount(String(Math.abs(prefill.amount)));
    setInstrument('FORWARD');
    setExposureRef(prefill.exposureRef);
    setPurpose(`Hedge ${prefill.exposureRef.replace('|', ' ')}`);
    setQuote(null);
    fxMarketApi.curve(prefill.pair)
      .then((curve) => setValueDate(curve.find((q) => q.tenor === prefill.tenor)?.valueDate ?? ''))
      .catch(() => undefined);
    onPrefillUsed();
  }, [prefill, onPrefillUsed]);

  // Count the validity window down locally, then ask the server what became of the quote.
  useEffect(() => {
    if (quote?.status !== 'QUOTED') return;
    setRemaining(quote.secondsRemaining);
    const id = setInterval(() => {
      setRemaining((s) => {
        if (s <= 1) {
          clearInterval(id);
          const q = quoteRef.current;
          if (q) fxDealApi.getQuote(q.id).then(setQuote).catch(() => undefined);
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(id);
  }, [quote?.id, quote?.status, quote?.secondsRemaining]);

  const onRequest = async () => {
    const n = Number(amount);
    if (!corporateId || !(n > 0)) { setFormError('Enter an amount greater than zero.'); return; }
    if (instrument !== 'SPOT' && !valueDate) { setFormError('Pick a value date.'); return; }
    if (instrument === 'WINDOW' && !windowStart) { setFormError('Pick the first drawdown date.'); return; }
    setRequesting(true);
    setFormError(null);
    setQuoteError(null);
    try {
      setQuote(await fxDealApi.requestQuote({
        corporateId, pair, side, instrument, amount: n,
        valueDate: instrument === 'SPOT' ? undefined : valueDate,
        windowStart: instrument === 'WINDOW' ? windowStart : undefined,
        purpose: purpose || undefined,
        exposureRef: exposureRef || undefined,
      }));
    } catch (err) {
      setQuote(null);
      setFormError(errorText(err));
    } finally {
      setRequesting(false);
    }
  };

  const onAccept = async () => {
    if (!quote) return;
    setAccepting(true);
    setQuoteError(null);
    try {
      const deal = await fxDealApi.accept(quote.id);
      toast.success(`${deal.dealRef} booked`);
      setQuote(await fxDealApi.getQuote(quote.id));
      await refresh(corporateId);
    } catch (err) {
      setQuoteError(errorText(err));
      fxDealApi.getQuote(quote.id).then(setQuote).catch(() => undefined);
    } finally {
      setAccepting(false);
    }
  };

  const onCancel = async () => {
    if (!quote) return;
    try {
      setQuote(await fxDealApi.cancel(quote.id));
    } catch (err) {
      setQuoteError(errorText(err));
    }
  };

  const onRunSettlement = async () => {
    setRunning(true);
    setRunError(null);
    try {
      setRunResult(await fxDealApi.runSettlement(asOf || undefined, corporateId));
      await refresh(corporateId);
    } catch (err) {
      setRunError(errorText(err));
    } finally {
      setRunning(false);
    }
  };

  const pairOptions = useMemo(
    () => (pairs.includes(pair) ? pairs : [...pairs, pair]).map((p) => ({ value: p, label: p })),
    [pairs, pair],
  );
  const openDeal = deals.find((d) => d.id === openId) ?? null;
  const used = limit && limit.limit > 0 ? Math.min(100, (limit.used / limit.limit) * 100) : 0;
  const base = pair.slice(0, 3);
  const quoteCcy = pair.slice(4);

  const dealColumns: Column<Deal>[] = [
    { key: 'dealRef', header: 'Deal', render: (_, d) => <span className="font-mono text-caption">{d.dealRef}</span> },
    { key: 'pair', header: 'Trade', render: (_, d) => <span className="font-medium">{d.side === 'BUY' ? 'Buy' : 'Sell'} {d.pair} <span className="text-neutral-500 dark:text-neutral-400">{d.instrument.toLowerCase()}</span></span> },
    { key: 'buyAmount', header: 'You buy', align: 'right', dropOrder: 2, render: (_, d) => <span className="font-mono tabular-nums">{d.buyCurrency} {fmt(d.buyAmount)}</span> },
    { key: 'sellAmount', header: 'You sell', align: 'right', dropOrder: 3, render: (_, d) => <span className="font-mono tabular-nums">{d.sellCurrency} {fmt(d.sellAmount)}</span> },
    { key: 'rate', header: 'Rate', align: 'right', dropOrder: 1, render: (_, d) => <span className="font-mono tabular-nums">{d.rate.toFixed(rateDigits(d.rate))}</span> },
    { key: 'valueDate', header: 'Value date', render: (_, d) => <span className="font-mono tabular-nums">{d.windowStart ? `${d.windowStart} to ${d.valueDate}` : d.valueDate}</span> },
    { key: 'mtmQuote', header: 'Worth today', align: 'right', dropOrder: 4, render: (_, d) => {
      const v = d.mtmHome ?? d.mtmQuote;
      if (v === undefined) return <span className="text-neutral-500 dark:text-neutral-400">-</span>;
      return <span className={cn('font-mono tabular-nums', v > 0 ? 'text-success-700 dark:text-success-300' : v < 0 ? 'text-error-700 dark:text-error-300' : '')}>{v > 0 ? '+' : ''}{fmt(v, 0)} {d.mtmHome !== undefined ? d.mtmHomeCurrency : d.mtmQuoteCurrency}</span>;
    } },
    { key: 'confirmationStatus', header: 'Confirmation', render: (_, d) => {
      const c = ['SETTLED', 'CLOSED_OUT', 'ROLLED', 'CANCELLED'].includes(d.status) ? null : confirmationBadge(d);
      return c ? <Badge variant={c.variant} size="sm">{c.label}</Badge> : <span className="text-neutral-500 dark:text-neutral-400">-</span>;
    } },
    { key: 'status', header: 'Status', render: (_, d) => <Badge variant={DEAL_BADGE[d.status].variant} size="sm">{DEAL_BADGE[d.status].label}</Badge> },
  ];

  const buyLeg = quote ? (quote.side === 'BUY' ? [quote.baseCurrency, quote.amount] as const : [quote.quoteCurrency, quote.counterAmount ?? 0] as const) : null;
  const sellLeg = quote ? (quote.side === 'BUY' ? [quote.quoteCurrency, quote.counterAmount ?? 0] as const : [quote.baseCurrency, quote.amount] as const) : null;

  return (
    <div className="space-y-4">
      <Card>
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div className="min-w-[16rem] flex-1 max-w-md">
            <ScopeSelector
              mode="corporate-only"
              corporates={corporates}
              selectedCorporateId={corporateId}
              onCorporateChange={setCorporateId}
              loading={!corporates.length}
            />
          </div>
          {limit && (
            <div className="min-w-[16rem] flex-1 max-w-md">
              <div className="flex justify-between text-body-sm mb-1.5">
                <span className="text-neutral-600 dark:text-neutral-300">FX credit limit</span>
                <span className="font-mono tabular-nums text-primary-900 dark:text-neutral-50">
                  {fmt(limit.used, 0)} of {fmt(limit.limit, 0)} {limit.currency}
                </span>
              </div>
              <ProgressBar value={used} variant={used > 85 ? 'error' : used > 60 ? 'warning' : 'default'} size="sm" />
              <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-1">
                {fmt(limit.headroom, 0)} {limit.currency} left. Forwards use more of the limit the longer they run.
              </p>
            </div>
          )}
        </div>
      </Card>

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-2">
          <CardHeader title="Request a price" subtitle="The desk prices instantly and holds the price for a short time." />
          <div className="space-y-4">
            <Select label="Currency pair" value={pair} onChange={(e) => setPair(e.target.value)} options={pairOptions} />
            <div>
              <span className="block body-strong mb-2">Direction</span>
              <div className="grid grid-cols-2 gap-2">
                <Button variant={side === 'BUY' ? 'primary' : 'outline'} onClick={() => setSide('BUY')} aria-pressed={side === 'BUY'}>Buy {base}</Button>
                <Button variant={side === 'SELL' ? 'primary' : 'outline'} onClick={() => setSide('SELL')} aria-pressed={side === 'SELL'}>Sell {base}</Button>
              </div>
              <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-1.5">
                You {side === 'BUY' ? 'buy' : 'sell'} {base} and {side === 'BUY' ? 'pay' : 'receive'} {quoteCcy}.
              </p>
            </div>
            <Input label={`Amount (${base})`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
            <Select label="Instrument" value={instrument} onChange={(e) => setInstrument(e.target.value as Instrument)} options={INSTRUMENTS} />
            {instrument === 'WINDOW' && (
              <Input label="First drawdown date" type="date" value={windowStart} onChange={(e) => setWindowStart(e.target.value)} />
            )}
            {instrument !== 'SPOT' && (
              <Input label={instrument === 'WINDOW' ? 'Last drawdown date' : 'Value date'} type="date" value={valueDate} onChange={(e) => setValueDate(e.target.value)} />
            )}
            <Input label="Purpose (optional)" value={purpose} onChange={(e) => setPurpose(e.target.value)} />
            {exposureRef && <p className="text-caption text-neutral-500 dark:text-neutral-400">Hedging exposure {exposureRef.replace('|', ' ')}.</p>}
            {formError && <p className="text-body-sm text-error-700 dark:text-error-300">{formError}</p>}
            <Button onClick={onRequest} loading={requesting} disabled={!corporateId} leftIcon={<Send className="w-4 h-4" />} fullWidth>
              Request price
            </Button>
          </div>
        </Card>

        <Card className="lg:col-span-3">
          <CardHeader
            title="Price"
            subtitle={quote ? `${quote.side === 'BUY' ? 'Buy' : 'Sell'} ${quote.pair} ${quote.instrument.toLowerCase()}` : 'Request a price to see it here.'}
            action={quote ? <Badge variant={QUOTE_BADGE[quote.status].variant}>{QUOTE_BADGE[quote.status].label}</Badge> : undefined}
          />
          {quote?.status === 'QUOTED' && buyLeg && sellLeg && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-baseline justify-between gap-3">
                <span className="font-mono tabular-nums text-display text-primary-900 dark:text-neutral-50">
                  {quote.clientRate?.toFixed(rateDigits(quote.clientRate))}
                </span>
                <span className={cn('inline-flex items-center gap-1.5 text-body-sm font-medium tabular-nums',
                  remaining <= 10 ? 'text-error-700 dark:text-error-300' : 'text-neutral-600 dark:text-neutral-300')}>
                  <Clock className="w-4 h-4" /> Firm for {remaining}s
                </span>
              </div>
              <p className="text-body text-primary-900 dark:text-neutral-50">
                You buy <strong>{buyLeg[0]} {fmt(buyLeg[1])}</strong> and sell <strong>{sellLeg[0]} {fmt(sellLeg[1])}</strong>.
              </p>
              <dl className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-body-sm">
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Value date</dt>
                  <dd className="font-mono tabular-nums">{quote.windowStart ? `${quote.windowStart} to ${quote.valueDate}` : quote.valueDate}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Interbank mid</dt>
                  <dd className="font-mono tabular-nums">{quote.midRate?.toFixed(rateDigits(quote.midRate))}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Forward points</dt>
                  <dd className="font-mono tabular-nums">{quote.forwardPoints?.toFixed(1)}</dd></div>
                <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Bank margin</dt>
                  <dd className="font-mono tabular-nums">{quote.spreadBps?.toFixed(2)} bps</dd></div>
              </dl>
              <p className="text-caption text-neutral-500 dark:text-neutral-400">
                Uses {fmt(quote.limitCharge ?? 0, 0)} {quote.limitCurrency} of your FX credit limit. Simulated desk and rates.
              </p>
              {quoteError && <p className="text-body-sm text-error-700 dark:text-error-300">{quoteError}</p>}
              <div className="flex gap-2">
                <Button onClick={onAccept} loading={accepting} leftIcon={<Check className="w-4 h-4" />}>Accept price</Button>
                <Button variant="outline" onClick={onCancel} disabled={accepting} leftIcon={<X className="w-4 h-4" />}>Withdraw</Button>
              </div>
            </div>
          )}
          {quote?.status === 'ACCEPTED' && (
            <Alert variant="success" title="Deal booked">
              {deals.find((d) => d.id === quote.dealId)?.dealRef ?? 'Your deal'} is booked and confirmed. Open it in the blotter below.
            </Alert>
          )}
          {quote && ['REJECTED', 'EXPIRED', 'CANCELLED'].includes(quote.status) && (
            <Alert variant={quote.status === 'REJECTED' ? 'error' : 'warning'} title={QUOTE_BADGE[quote.status].label}>
              {quote.statusReason ?? (quote.status === 'CANCELLED' ? 'You withdrew this request.' : 'This price is no longer available.')} Request a new price to continue.
            </Alert>
          )}
          {quoteError && quote?.status !== 'QUOTED' && <p className="text-body-sm text-error-700 dark:text-error-300 mt-3">{quoteError}</p>}
        </Card>
      </div>

      <Card>
        <CardHeader title="Deals" subtitle="Select a deal to read the bank's confirmation, confirm it, and manage it." />
        <DataTable<Deal>
          data={deals}
          columns={dealColumns}
          keyExtractor={(d) => d.id}
          onRowClick={(d) => setOpenId(d.id)}
          compact
          hairline
          emptyTitle="No deals yet"
          emptyDescription="Accepted prices appear here."
        />
      </Card>

      <Card>
        <CardHeader
          title="Value-date settlement"
          subtitle="Settles every confirmed deal that is due, if the cash is there. It is a record only: no balance moves. Pick a later date to move the demo clock forward."
        />
        <div className="flex flex-wrap items-end gap-3">
          <div className="w-48"><Input label="Settle as of (optional)" type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} /></div>
          <Button size="sm" variant="outline" onClick={onRunSettlement} loading={running} disabled={!corporateId} leftIcon={<Play className="w-4 h-4" />}>Run settlement</Button>
        </div>
        {runError && <p className="text-body-sm text-error-700 dark:text-error-300 mt-3">{runError}</p>}
        {runResult && (
          <div className="mt-3 text-body-sm space-y-1">
            <p>
              As of {runResult.asOf}: <strong>{runResult.settled}</strong> settled, <strong>{runResult.failed}</strong> failed, <strong>{runResult.blocked}</strong> blocked.
            </p>
            {runResult.notes.map((n, i) => <p key={i} className="text-caption text-neutral-600 dark:text-neutral-300">{n}</p>)}
          </div>
        )}
      </Card>

      <FxDealDrawer deal={openDeal} onClose={() => setOpenId(null)} onChanged={() => { refresh(corporateId).catch(() => undefined); }} />
    </div>
  );
};

export default FxDealPanel;
