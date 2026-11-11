// ============================================================================
// FxMarketPage ("Market Rates") — the corporate's own two-way prices: for each pair, the rate it buys the base
// currency at and the rate it sells it at, for an amount it chooses, from the same desk pricing as the deal ticket
// (FxBoardController). Never the interbank mid: beside a deal price it gives the bank's margin away. Indicative:
// clicking a price opens the ticket on that side, which streams a firm price (or requests one above the limits).
// Polled (the app has no SSE/WebSocket); the sparklines' history lives in the browser only. Rate alerts are set on
// a side of the client price and arrive in the notification bell.
// ============================================================================

import React, { useEffect, useRef, useState } from 'react';
import { Radio, AlertTriangle, Activity, ListChecks, Send, LineChart, Copy, BellRing, X } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Card, CardHeader, Badge, Button, DataTable, Input, RowActions, StatTile } from '../../components/ui';
import { Modal } from '../../components/ui/enhanced';
import { fxDealApi, RateAlert, Side } from '../../services/fxDealApi';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import { cn } from '../../utils';
import { fxMarketApi, BoardRow, CurveRow } from '../../services/fxMarketApi';
import NewDealDrawer, { TicketPrefill } from './NewDealDrawer';
import { errorText, fmt, fmtSigned, toneFor } from './fxFormat';

const BOARD_POLL_MS = 1000;
const CURVE_POLL_MS = 5000;
const HISTORY_POINTS = 60;
const DEFAULT_AMOUNT = 100000;

const digitsFor = (rate: number) => (rate >= 50 ? 3 : 5);
const fmtRate = (n: number, extra = 0) => n.toFixed(digitsFor(n) + extra);

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

/** A price that opens the ticket on its side when clicked. */
const PriceButton: React.FC<{ rate: number; label: string; tone?: string; onClick: () => void; extra?: number }> = ({ rate, label, tone, onClick, extra = 0 }) => (
  <button type="button" onClick={(e) => { e.stopPropagation(); onClick(); }} aria-label={label} title={label}
    className={cn('font-mono tabular-nums rounded px-1.5 py-0.5 -mx-1.5 hover:bg-primary-50 dark:hover:bg-primary-800/40 focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary-500', tone)}>
    {fmtRate(rate, extra)}
  </button>
);

const FxMarketPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const [ticket, setTicket] = useState<{ prefill: TicketPrefill } | null>(null);
  const [amountText, setAmountText] = useState(String(DEFAULT_AMOUNT));
  const amount = Number(amountText) > 0 ? Number(amountText) : DEFAULT_AMOUNT;
  const [rows, setRows] = useState<BoardRow[]>([]);
  const [feedError, setFeedError] = useState<string | null>(null);
  const [selected, setSelected] = useState('EUR/USD');
  const [curve, setCurve] = useState<CurveRow[]>([]);
  const [curveError, setCurveError] = useState<string | null>(null);
  const [valueDate, setValueDate] = useState('');
  const [custom, setCustom] = useState<CurveRow | null>(null);
  const [customError, setCustomError] = useState<string | null>(null);

  const [alerts, setAlerts] = useState<RateAlert[]>([]);
  const [alertFor, setAlertFor] = useState<BoardRow | null>(null);
  const [alertSide, setAlertSide] = useState<Side>('BUY');
  const [alertRate, setAlertRate] = useState('');
  const [alertNote, setAlertNote] = useState('');
  const [alertError, setAlertError] = useState<string | null>(null);
  const [savingAlert, setSavingAlert] = useState(false);

  const history = useRef<Record<string, number[]>>({});
  const lastDir = useRef<Record<string, number>>({});

  // The board, every second, at the chosen amount. A new amount starts the sparklines again.
  useEffect(() => {
    history.current = {};
    lastDir.current = {};
    let current = true;
    let inFlight = false;
    const load = async () => {
      if (inFlight || document.visibilityState === 'hidden') return;
      inFlight = true;
      try {
        const next = await fxMarketApi.board(amount);
        if (!current) return;
        for (const r of next) {
          const h = history.current[r.pair] ?? [];
          const prev = h[h.length - 1];
          if (prev !== undefined && r.buy !== prev) lastDir.current[r.pair] = Math.sign(r.buy - prev);
          history.current[r.pair] = [...h, r.buy].slice(-HISTORY_POINTS);
        }
        setRows(next);
        setFeedError(null);
      } catch (err) {
        if (current) setFeedError(errorText(err));
      } finally {
        inFlight = false;
      }
    };
    load();
    const id = setInterval(load, BOARD_POLL_MS);
    return () => {
      current = false;
      clearInterval(id);
    };
  }, [amount]);

  useEffect(() => {
    let current = true;
    const load = () =>
      fxMarketApi
        .boardCurve(selected, amount)
        .then((c) => { if (current) { setCurve(c); setCurveError(null); } })
        .catch((err) => { if (current) setCurveError(errorText(err)); });
    setCurve([]);
    load();
    const id = setInterval(load, CURVE_POLL_MS);
    return () => {
      current = false;
      clearInterval(id);
    };
  }, [selected, amount]);

  useEffect(() => {
    let current = true;
    setCustom(null);
    setCustomError(null);
    if (valueDate) {
      fxMarketApi
        .boardForward(selected, amount, valueDate)
        .then((q) => { if (current) setCustom(q); })
        .catch((err) => { if (current) setCustomError(errorText(err)); });
    }
    return () => { current = false; };
  }, [selected, valueDate, amount]);

  const row = rows.find((r) => r.pair === selected);
  const asOf = rows[0] ? new Date(rows[0].asOf).toLocaleTimeString() : '';
  const mover = rows.reduce<BoardRow | null>((m, r) => (!m || Math.abs(r.changeBps) > Math.abs(m.changeBps) ? r : m), null);
  const base = (pair: string) => pair.slice(0, 3);

  const openTicket = (pair: string, side: Side, forward?: { valueDate: string }) =>
    setTicket({ prefill: { pair, side, amount, ...(forward ? { instrument: 'FORWARD' as const, valueDate: forward.valueDate } : {}) } });

  // ---- rate alerts ----

  const loadAlerts = React.useCallback(() => {
    if (!currentCorporateId) { setAlerts([]); return; }
    fxDealApi.alerts(currentCorporateId).then(setAlerts).catch(() => undefined);
  }, [currentCorporateId]);
  // Alerts are checked on the server every 30 seconds; refresh the list as often.
  useEffect(() => {
    loadAlerts();
    const id = setInterval(loadAlerts, 30000);
    return () => clearInterval(id);
  }, [loadAlerts]);

  const startAlert = (r: BoardRow, side: Side = 'BUY') => {
    setAlertFor(r);
    setAlertSide(side);
    setAlertRate(fmtRate(side === 'BUY' ? r.buy : r.sell));
    setAlertNote('');
    setAlertError(null);
  };

  // The dialog follows the board, so "now" stays current while it is open.
  const alertRow = alertFor ? rows.find((r) => r.pair === alertFor.pair) ?? alertFor : null;
  const alertNow = alertRow ? (alertSide === 'BUY' ? alertRow.buy : alertRow.sell) : 0;

  const saveAlert = async () => {
    if (!alertFor || !currentCorporateId) return;
    setSavingAlert(true);
    try {
      await fxDealApi.createAlert(currentCorporateId, alertFor.pair, alertSide, amount, Number(alertRate), alertNote || undefined);
      setAlertFor(null);
      loadAlerts();
    } catch (err) {
      setAlertError(errorText(err));
    } finally {
      setSavingAlert(false);
    }
  };

  const cancelAlert = async (a: RateAlert) => {
    try {
      await fxDealApi.cancelAlert(a.id);
      loadAlerts();
    } catch (err) {
      toast.error(errorText(err));
    }
  };

  const ALERT_BADGE: Record<RateAlert['status'], { label: string; variant: 'info' | 'success' | 'neutral' }> = {
    ACTIVE: { label: 'Watching', variant: 'info' },
    TRIGGERED: { label: 'Reached', variant: 'success' },
    CANCELLED: { label: 'Cancelled', variant: 'neutral' },
  };

  const alertColumns: Column<RateAlert>[] = [
    { key: 'pair', header: 'Pair', render: (_, a) => <span className="font-medium">{a.pair}</span> },
    {
      key: 'targetRate', header: 'Tell me when I can', render: (_, a) => (
        <span>
          {a.side === 'BUY' ? 'buy' : 'sell'} {base(a.pair)} at or {a.side === 'BUY' ? 'below' : 'above'}{' '}
          <span className="font-mono tabular-nums">{a.targetRate}</span>
          {a.amount !== undefined && <span className="block text-caption text-neutral-500 dark:text-neutral-400">for {fmt(a.amount, 0)} {base(a.pair)}</span>}
        </span>
      ),
    },
    {
      key: 'rateAtCreation', header: 'Price when set', align: 'right', dropOrder: 1,
      render: (_, a) => <span className="font-mono tabular-nums">{a.rateAtCreation !== undefined ? fmtRate(a.rateAtCreation) : '-'}</span>,
    },
    {
      key: 'status', header: 'Status', render: (_, a) => (
        <span>
          <Badge variant={ALERT_BADGE[a.status].variant} size="sm">{ALERT_BADGE[a.status].label}</Badge>
          {a.triggeredRate !== undefined && <span className="block text-caption text-neutral-500 dark:text-neutral-400 mt-0.5">at {fmtRate(a.triggeredRate)}</span>}
        </span>
      ),
    },
    { key: 'note', header: 'Note', dropOrder: 2, render: (_, a) => <span className="text-caption">{a.note ?? '-'}</span> },
    {
      key: 'actions', header: 'Actions', width: '4rem', minWidth: 64,
      render: (_, a) => (
        <RowActions actions={[
          { label: 'Cancel alert', icon: <X className="w-4 h-4" />, onClick: () => cancelAlert(a), hidden: a.status !== 'ACTIVE', danger: true },
          { label: 'Request price', icon: <Send className="w-4 h-4" />, onClick: () => openTicket(a.pair, a.side) },
        ]} />
      ),
    },
  ];

  const boardColumns: Column<BoardRow>[] = [
    {
      key: 'pair',
      header: 'Pair',
      render: (_, r) =>
        r.pair === selected ? <Badge variant="primary" size="sm">{r.pair}</Badge> : <span className="font-medium">{r.pair}</span>,
    },
    {
      key: 'sell',
      header: 'You sell',
      align: 'right',
      render: (_, r) => <PriceButton rate={r.sell} label={`Sell ${base(r.pair)} at ${fmtRate(r.sell)}: open a deal`}
        tone={toneFor(lastDir.current[r.pair] ?? 0)} onClick={() => openTicket(r.pair, 'SELL')} />,
    },
    {
      key: 'buy',
      header: 'You buy',
      align: 'right',
      render: (_, r) => <PriceButton rate={r.buy} label={`Buy ${base(r.pair)} at ${fmtRate(r.buy)}: open a deal`}
        tone={toneFor(lastDir.current[r.pair] ?? 0)} onClick={() => openTicket(r.pair, 'BUY')} />,
    },
    {
      key: 'changeBps',
      header: 'Change vs open',
      align: 'right',
      dropOrder: 2,
      render: (_, r) => (
        <span className={cn('font-mono tabular-nums', toneFor(r.changeBps))}>{fmtSigned(r.changeBps, 1)} bps</span>
      ),
    },
    {
      key: 'trend',
      header: 'Last minute',
      align: 'right',
      dropOrder: 1,
      mobileHidden: true,
      render: (_, r) => <Sparkline values={history.current[r.pair] ?? []} tone={toneFor(r.changeBps)} />,
    },
    {
      key: 'actions',
      header: 'Actions',
      width: '4rem',
      minWidth: 64,
      render: (_, r) => (
        <RowActions actions={[
          { label: `Buy ${base(r.pair)}…`, icon: <Send className="w-4 h-4" />, onClick: () => openTicket(r.pair, 'BUY') },
          { label: `Sell ${base(r.pair)}…`, icon: <Send className="w-4 h-4" />, onClick: () => openTicket(r.pair, 'SELL') },
          { label: 'Set rate alert…', icon: <BellRing className="w-4 h-4" />, onClick: () => startAlert(r), hidden: !currentCorporateId, divider: true },
          { label: 'Forward curve', icon: <LineChart className="w-4 h-4" />, onClick: () => setSelected(r.pair) },
          { label: 'Copy prices', icon: <Copy className="w-4 h-4" />, onClick: () => { navigator.clipboard?.writeText(`${r.pair} ${fmtRate(r.sell)} / ${fmtRate(r.buy)}`).then(() => toast.success('Prices copied')).catch(() => undefined); } },
        ]} />
      ),
    },
  ];

  const points = (p?: number) => (p === undefined || p === null ? '' : `${p >= 0 ? '+' : ''}${p.toFixed(2)} pts`);

  const curveColumns: Column<CurveRow>[] = [
    { key: 'tenor', header: 'Tenor', render: (_, q) => <span className="font-medium">{q.tenor}</span> },
    { key: 'valueDate', header: 'Value date', dropOrder: 1, render: (_, q) => <span className="font-mono tabular-nums">{q.valueDate}</span> },
    {
      key: 'sell', header: 'You sell', align: 'right', render: (_, q) => (
        <span>
          <PriceButton rate={q.sell} extra={1} label={`Sell ${base(selected)} forward to ${q.valueDate} at ${fmtRate(q.sell, 1)}: open a deal`}
            onClick={() => openTicket(selected, 'SELL', { valueDate: q.valueDate })} />
          <span className="block text-caption font-mono tabular-nums text-neutral-500 dark:text-neutral-400">{points(q.sellPoints)}</span>
        </span>
      ),
    },
    {
      key: 'buy', header: 'You buy', align: 'right', render: (_, q) => (
        <span>
          <PriceButton rate={q.buy} extra={1} label={`Buy ${base(selected)} forward to ${q.valueDate} at ${fmtRate(q.buy, 1)}: open a deal`}
            onClick={() => openTicket(selected, 'BUY', { valueDate: q.valueDate })} />
          <span className="block text-caption font-mono tabular-nums text-neutral-500 dark:text-neutral-400">{points(q.buyPoints)}</span>
        </span>
      ),
    },
  ];

  return (
    <Page>
      <PageHeader
        title="Market Rates"
        description="Your indicative buy and sell prices for each pair, at the amount you choose: the rate you buy the first currency at, and the rate you sell it at. Click a price to request a deal on that side. The rates are simulated."
      />

      <StatStrip>
        <StatTile tone="primary" icon={<ListChecks className="w-5 h-5" />} label="Pairs listed" value={String(rows.length)} sub="on the rate board" />
        <StatTile tone={mover && mover.changeBps < 0 ? 'danger' : 'success'} icon={<Activity className="w-5 h-5" />} label="Biggest mover"
          value={mover ? mover.pair : '-'} sub={mover ? `${fmtSigned(mover.changeBps, 1)} bps vs open` : 'waiting for rates'} />
        <StatTile tone={feedError ? 'danger' : 'info'} icon={<Radio className="w-5 h-5" />} label="Feed"
          value={feedError ? 'Unavailable' : rows.length ? 'Running' : 'Connecting'} sub={feedError ?? (asOf ? `as of ${asOf}` : 'waiting for the first rates')} />
      </StatStrip>

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader
            title="Rate board"
            subtitle={`Spot, for ${fmt(amount, 0)} of the first currency of each pair. The spread depends on the amount.`}
            action={feedError
              ? <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>Feed unavailable</Badge>
              : (
                <span className="inline-flex gap-1.5">
                  {rows[0]?.source === 'SIMULATED' && <Badge variant="warning">Simulated</Badge>}
                  <Badge variant="info" icon={<Radio className="w-3 h-3" />}>Indicative</Badge>
                </span>
              )}
          />
          <DataTable<BoardRow>
            data={rows}
            columns={boardColumns}
            keyExtractor={(r) => r.pair}
            onRowClick={(r) => setSelected(r.pair)}
            compact
            hairline
            emptyTitle="Waiting for rates"
            emptyDescription="The market has not answered yet."
            actions={
              <label className="inline-flex items-center gap-2 text-caption text-neutral-600 dark:text-neutral-300">
                Prices for
                <Input aria-label="Amount of the first currency" inputSize="sm" inputMode="decimal" value={amountText}
                  onChange={(e) => setAmountText(e.target.value.replace(/[^0-9.]/g, ''))} placeholder="100000" />
              </label>
            }
          />
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader
            title={`${selected} forward curve`}
            subtitle={row ? `Spot ${fmtRate(row.sell)} / ${fmtRate(row.buy)} for value ${row.valueDate}; outright rates below` : 'Waiting for the rate board'}
            action={curve[0]?.source === 'SIMULATED' ? <Badge variant="warning">Simulated</Badge> : undefined}
          />
          {curveError && <p className="text-body-sm text-error-700 dark:text-error-300 mb-3">{curveError}</p>}
          <DataTable<CurveRow>
            data={curve}
            columns={curveColumns}
            keyExtractor={(q) => q.tenor ?? q.valueDate}
            compact
            hairline
            emptyTitle="Loading curve"
            emptyDescription="Forward prices appear once the pair has priced."
          />

          <div className="mt-5 pt-4 border-t border-edge">
            <Input label="Price an exact value date" type="date" value={valueDate} min={row?.valueDate} onChange={(e) => setValueDate(e.target.value)} />
            {customError && <p className="text-body-sm text-error-700 dark:text-error-300 mt-2">{customError}</p>}
            {custom && (
              <dl className="mt-3 grid grid-cols-3 gap-3 text-body-sm">
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">Days from spot</dt>
                  <dd className="font-mono tabular-nums">{custom.days}</dd>
                </div>
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">You sell</dt>
                  <dd><PriceButton rate={custom.sell} extra={1} label={`Sell forward to ${custom.valueDate}`} onClick={() => openTicket(selected, 'SELL', { valueDate: custom.valueDate })} />
                    <span className="block text-caption font-mono tabular-nums text-neutral-500 dark:text-neutral-400">{points(custom.sellPoints)}</span></dd>
                </div>
                <div>
                  <dt className="text-caption text-neutral-500 dark:text-neutral-400">You buy</dt>
                  <dd><PriceButton rate={custom.buy} extra={1} label={`Buy forward to ${custom.valueDate}`} onClick={() => openTicket(selected, 'BUY', { valueDate: custom.valueDate })} />
                    <span className="block text-caption font-mono tabular-nums text-neutral-500 dark:text-neutral-400">{points(custom.buyPoints)}</span></dd>
                </div>
              </dl>
            )}
            <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-3">Points take your spot price to the outright rate for that date. They are simulated: worked out from configured demo interest rates, not the money markets.</p>
          </div>
        </Card>
      </div>

      {currentCorporateId && (
        <Card>
          <CardHeader title="Rate alerts" subtitle="Each fires once, in the notification bell, when your buy or sell price reaches it. Set one from a pair's actions." />
          <DataTable<RateAlert>
            data={alerts}
            columns={alertColumns}
            keyExtractor={(a) => a.id}
            compact
            hairline
            emptyTitle="No rate alerts"
            emptyDescription="Use Set rate alert on a pair to be told when you can buy or sell at a level."
          />
        </Card>
      )}

      <Modal
        isOpen={!!alertFor}
        onClose={() => setAlertFor(null)}
        title={`Rate alert on ${alertFor?.pair ?? ''}`}
        subtitle={alertRow ? `Now: you sell at ${fmtRate(alertRow.sell)}, you buy at ${fmtRate(alertRow.buy)} (for ${fmt(amount, 0)} ${base(alertRow.pair)})` : undefined}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setAlertFor(null)}>Cancel</Button>
            <Button onClick={saveAlert} loading={savingAlert} disabled={!(Number(alertRate) > 0)} leftIcon={<BellRing className="w-4 h-4" />}>Set alert</Button>
          </>
        }
      >
        {alertFor && alertRow && (
          <div className="space-y-3">
            <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="Buy or sell">
              {(['BUY', 'SELL'] as Side[]).map((s) => (
                <Button key={s} size="sm" variant={alertSide === s ? 'primary' : 'outline'} aria-pressed={alertSide === s}
                  onClick={() => { setAlertSide(s); setAlertRate(fmtRate(s === 'BUY' ? alertRow.buy : alertRow.sell)); }}>
                  I want to {s === 'BUY' ? 'buy' : 'sell'} {base(alertRow.pair)}
                </Button>
              ))}
            </div>
            <Input label={`Tell me when I can ${alertSide === 'BUY' ? 'buy' : 'sell'} at or ${alertSide === 'BUY' ? 'below' : 'above'}`}
              inputMode="decimal" value={alertRate} onChange={(e) => setAlertRate(e.target.value)}
              hint={(alertSide === 'BUY' ? Number(alertRate) < alertNow : Number(alertRate) > alertNow)
                ? `Your ${alertSide === 'BUY' ? 'buy' : 'sell'} price is ${fmtRate(alertNow)} now`
                : `You can ${alertSide === 'BUY' ? 'buy' : 'sell'} at ${fmtRate(alertNow)} now; choose a rate ${alertSide === 'BUY' ? 'below' : 'above'} that`} />
            <Input label="Note (optional)" value={alertNote} onChange={(e) => setAlertNote(e.target.value)} placeholder="For example: buy EUR for the March payables" />
            {alertError && <p className="text-body-sm text-error-700 dark:text-error-300">{alertError}</p>}
          </div>
        )}
      </Modal>

      <NewDealDrawer
        isOpen={!!ticket}
        prefill={ticket?.prefill ?? null}
        corporateId={currentCorporateId ?? ''}
        onClose={() => setTicket(null)}
        onChanged={() => undefined}
      />
    </Page>
  );
};

export default FxMarketPage;
