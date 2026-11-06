// ============================================================================
// FxMarketPage ("Market Rates") — a ticking rate board and a forward curve from the simulated market in
// FxMarketController. Mid rates only: the bank's margin is set per deal, so nothing here is a dealable price.
// Polled (the app has no SSE/WebSocket), and the board's history lives in the browser only. From a row the
// corporate can start a price request for that pair, or set a rate alert on it (it arrives in the notification bell).
// ============================================================================

import React, { useEffect, useRef, useState } from 'react';
import { Radio, AlertTriangle, Activity, ListChecks, Send, LineChart, Copy, BellRing, X } from 'lucide-react';
import toast from 'react-hot-toast';
import { Page } from '../../components/layout/Page';
import { PageHeader } from '../../components/layout/PageHeader';
import { StatStrip } from '../../components/layout/StatStrip';
import { Card, CardHeader, Badge, Button, DataTable, Input, RowActions, StatTile } from '../../components/ui';
import { Modal } from '../../components/ui/enhanced';
import { fxDealApi, RateAlert } from '../../services/fxDealApi';
import type { Column } from '../../components/ui/DataTable';
import { useUser } from '../../context/useUser';
import { cn } from '../../utils';
import { fxMarketApi, ForwardQuote, SpotTick } from '../../services/fxMarketApi';
import NewDealDrawer, { TicketPrefill } from './NewDealDrawer';
import { errorText, fmtSigned, toneFor } from './fxFormat';

const SPOT_POLL_MS = 1000;
const CURVE_POLL_MS = 5000;
const HISTORY_POINTS = 60;

const digitsFor = (mid: number) => (mid >= 50 ? 3 : 5);
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

const FxMarketPage: React.FC = () => {
  const { currentCorporateId } = useUser();
  const [ticket, setTicket] = useState<{ prefill: TicketPrefill } | null>(null);
  const [alerts, setAlerts] = useState<RateAlert[]>([]);
  const [alertFor, setAlertFor] = useState<SpotTick | null>(null);
  const [alertRate, setAlertRate] = useState('');
  const [alertNote, setAlertNote] = useState('');
  const [alertError, setAlertError] = useState<string | null>(null);
  const [savingAlert, setSavingAlert] = useState(false);
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
  const mover = ticks.reduce<SpotTick | null>((m, t) => (!m || Math.abs(t.changeBps) > Math.abs(m.changeBps) ? t : m), null);

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

  const startAlert = (t: SpotTick) => {
    setAlertFor(t);
    setAlertRate(fmtRate(t.mid));
    setAlertNote('');
    setAlertError(null);
  };

  const saveAlert = async () => {
    if (!alertFor || !currentCorporateId) return;
    setSavingAlert(true);
    try {
      await fxDealApi.createAlert(currentCorporateId, alertFor.pair, Number(alertRate), alertNote || undefined);
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
      key: 'targetRate', header: 'Tell me when it', render: (_, a) => (
        <span>{a.direction === 'ABOVE' ? 'rises to' : 'falls to'} <span className="font-mono tabular-nums">{a.targetRate}</span></span>
      ),
    },
    {
      key: 'rateAtCreation', header: 'Rate when set', align: 'right', dropOrder: 1,
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
          { label: 'Request price', icon: <Send className="w-4 h-4" />, onClick: () => setTicket({ prefill: { pair: a.pair, side: 'BUY' } }) },
        ]} />
      ),
    },
  ];

  const boardColumns: Column<SpotTick>[] = [
    {
      key: 'pair',
      header: 'Pair',
      render: (_, t) =>
        t.pair === selected ? <Badge variant="primary" size="sm">{t.pair}</Badge> : <span className="font-medium">{t.pair}</span>,
    },
    {
      key: 'mid',
      header: 'Indicative rate',
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
    {
      key: 'actions',
      header: 'Actions',
      width: '4rem',
      minWidth: 64,
      render: (_, t) => (
        <RowActions actions={[
          { label: 'Request price', icon: <Send className="w-4 h-4" />, onClick: () => setTicket({ prefill: { pair: t.pair, side: 'BUY' } }) },
          { label: 'Set rate alert…', icon: <BellRing className="w-4 h-4" />, onClick: () => startAlert(t), hidden: !currentCorporateId },
          { label: 'Forward curve', icon: <LineChart className="w-4 h-4" />, onClick: () => setSelected(t.pair) },
          { label: 'Copy rate', icon: <Copy className="w-4 h-4" />, onClick: () => { navigator.clipboard?.writeText(fmtRate(t.mid)).then(() => toast.success('Rate copied')).catch(() => undefined); } },
        ]} />
      ),
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
    <Page>
      <PageHeader
        title="Market Rates"
        description="Indicative interbank rates and forward points. A dealable price comes from requesting one. The rates are simulated."
      />

      <StatStrip>
        <StatTile tone="primary" icon={<ListChecks className="w-5 h-5" />} label="Pairs listed" value={String(ticks.length)} sub="on the rate board" />
        <StatTile tone={mover && mover.changeBps < 0 ? 'danger' : 'success'} icon={<Activity className="w-5 h-5" />} label="Biggest mover"
          value={mover ? mover.pair : '-'} sub={mover ? `${fmtSigned(mover.changeBps, 1)} bps vs open` : 'waiting for rates'} />
        <StatTile tone={feedError ? 'danger' : 'info'} icon={<Radio className="w-5 h-5" />} label="Feed"
          value={feedError ? 'Unavailable' : ticks.length ? 'Running' : 'Connecting'} sub={feedError ?? (asOf ? `as of ${asOf}` : 'waiting for the first rates')} />
      </StatStrip>

      <div className="grid gap-4 lg:grid-cols-5">
        <Card className="lg:col-span-3">
          <CardHeader
            title="Rate board"
            subtitle="Select a pair to see its forward curve, or use a row’s actions to request a price."
            action={feedError
              ? <Badge variant="error" icon={<AlertTriangle className="w-3 h-3" />}>Feed unavailable</Badge>
              : <Badge variant="info" icon={<Radio className="w-3 h-3" />}>Simulated</Badge>}
          />
          <DataTable<SpotTick>
            data={ticks}
            columns={boardColumns}
            keyExtractor={(t) => t.pair}
            onRowClick={(t) => setSelected(t.pair)}
            compact
            hairline
            emptyTitle="Waiting for rates"
            emptyDescription="The market has not answered yet."
          />
        </Card>

        <Card className="lg:col-span-2">
          <CardHeader
            title={`${selected} forward curve`}
            subtitle={tick ? `Spot ${fmtRate(tick.mid)} for value ${first?.spotDate ?? '…'}` : 'Waiting for the rate board'}
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
              {selected.slice(4)} {first.quoteRatePct.toFixed(2)}%.
            </p>
          )}

          <div className="mt-5 pt-4 border-t border-edge">
            <Input label="Price an exact value date" type="date" value={valueDate} min={first?.spotDate} onChange={(e) => setValueDate(e.target.value)} />
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

      {currentCorporateId && (
        <Card>
          <CardHeader title="Rate alerts" subtitle="Each fires once, in the notification bell, when the indicative rate reaches it. Set one from a pair's actions." />
          <DataTable<RateAlert>
            data={alerts}
            columns={alertColumns}
            keyExtractor={(a) => a.id}
            compact
            hairline
            emptyTitle="No rate alerts"
            emptyDescription="Use Set rate alert on a pair to be told when it reaches a level."
          />
        </Card>
      )}

      <Modal
        isOpen={!!alertFor}
        onClose={() => setAlertFor(null)}
        title={`Rate alert on ${alertFor?.pair ?? ''}`}
        subtitle={alertFor ? `Now ${fmtRate(alertFor.mid)}` : undefined}
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setAlertFor(null)}>Cancel</Button>
            <Button onClick={saveAlert} loading={savingAlert} disabled={!(Number(alertRate) > 0)} leftIcon={<BellRing className="w-4 h-4" />}>Set alert</Button>
          </>
        }
      >
        {alertFor && (
          <div className="space-y-3">
            <Input label="Tell me when the rate reaches" inputMode="decimal" value={alertRate} onChange={(e) => setAlertRate(e.target.value)}
              hint={Number(alertRate) > alertFor.mid ? 'Above today’s rate: fires when it rises to this' : Number(alertRate) < alertFor.mid ? 'Below today’s rate: fires when it falls to this' : 'Choose a rate other than today’s'} />
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
