// ============================================================================
// FxDealDrawer — one deal after it is booked: the bank's confirmation (read it, download the PDF,
// then confirm or dispute it), what it is worth now, and what can be done with it: draw down a
// window, close out early, or roll. Settlement is a record against the deal; no balance moves.
// Prices for close-out and roll are not held firm: the preview shows today's cost and the action
// prices again when it runs, so the result can differ slightly from the preview.
// ============================================================================

import React, { useCallback, useEffect, useState } from 'react';
import { Download, Check, X, FileText, AlertTriangle } from 'lucide-react';
import toast from 'react-hot-toast';
import { Badge, Button, Drawer, Input } from '../components/ui';
import { Alert } from '../components/ui/enhanced';
import { EventTimeline } from '../components/ui/EventTimeline';
import { cn } from '../utils';
import {
  fxDealApi, CloseOutQuote, Deal, DealEvent, RollQuote, Settlement,
} from '../services/fxDealApi';
import { DEAL_BADGE, confirmationBadge } from './fxDealBadges';
import { dealRules, DealAction } from './fx/fxDealRules';

const fmt = (n: number, dp = 2) => n.toLocaleString('en-US', { minimumFractionDigits: dp, maximumFractionDigits: dp });
const fmtSigned = (n: number, dp = 2) => `${n > 0 ? '+' : n < 0 ? '-' : ''}${fmt(Math.abs(n), dp)}`;
const rateDigits = (n: number) => (n >= 50 ? 4 : 6);
const toneFor = (n: number) =>
  n > 0 ? 'text-success-700 dark:text-success-300' : n < 0 ? 'text-error-700 dark:text-error-300' : 'text-neutral-500 dark:text-neutral-400';

const errorText = (err: unknown): string => {
  const e = err as { response?: { data?: { message?: string } }; message?: string };
  return e?.response?.data?.message || e?.message || 'Request failed';
};

const Section: React.FC<{ title: string; id?: string; children: React.ReactNode }> = ({ title, id, children }) => (
  <section id={id} className="space-y-2 scroll-mt-4">
    <h3 className="text-body-sm font-semibold text-primary-900 dark:text-neutral-50">{title}</h3>
    {children}
  </section>
);

/** `action` is the row-menu item that opened the drawer: it opens the matching section and scrolls to it. */
const FxDealDrawer: React.FC<{ deal: Deal | null; action?: DealAction | null; onClose: () => void; onChanged: () => void }> = ({ deal, action, onClose, onChanged }) => {
  const [text, setText] = useState('');
  const [events, setEvents] = useState<DealEvent[]>([]);
  const [settlements, setSettlements] = useState<Settlement[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [disputing, setDisputing] = useState(false);
  const [reason, setReason] = useState('');

  const [drawAmount, setDrawAmount] = useState('');
  const [drawDate, setDrawDate] = useState('');
  const [closeQuote, setCloseQuote] = useState<CloseOutQuote | null>(null);
  const [rollDate, setRollDate] = useState('');
  const [rollQuote, setRollQuote] = useState<RollQuote | null>(null);

  const id = deal?.id;
  const version = `${deal?.status}|${deal?.confirmationStatus}|${deal?.remainingAmount}`;

  const load = useCallback(async (dealId: string) => {
    try {
      const [c, e, s] = await Promise.all([fxDealApi.confirmation(dealId), fxDealApi.events(dealId), fxDealApi.settlements(dealId)]);
      setText(c.text);
      setEvents(e);
      setSettlements(s);
    } catch (err) {
      setText(errorText(err));
    }
  }, []);

  useEffect(() => {
    if (!id) return;
    setText('');
    setEvents([]);
    setSettlements([]);
    setError(null);
    setDisputing(false);
    setReason('');
    setCloseQuote(null);
    setRollQuote(null);
    load(id);
  }, [id, version, load]);

  useEffect(() => {
    if (!id || !action) return;
    if (action === 'dispute') setDisputing(true);
    // The sections render once the deal is in; scroll after that paint.
    const t = setTimeout(() => document.getElementById(`fx-deal-${action === 'dispute' ? 'confirmation' : action}`)?.scrollIntoView({ block: 'start', behavior: 'smooth' }), 150);
    return () => clearTimeout(t);
  }, [id, action]);

  const run = async (act: () => Promise<unknown>, done: string) => {
    setBusy(true);
    setError(null);
    try {
      await act();
      toast.success(done);
      onChanged();
    } catch (err) {
      setError(errorText(err));
    } finally {
      setBusy(false);
    }
  };

  const downloadPdf = async () => {
    if (!deal) return;
    try {
      const blob = await fxDealApi.confirmationPdf(deal.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `${deal.dealRef}-confirmation.pdf`;
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(errorText(err));
    }
  };

  const preview = async (fn: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try { await fn(); } catch (err) { setError(errorText(err)); } finally { setBusy(false); }
  };

  const conf = deal ? confirmationBadge(deal) : null;
  const { inForce, canAct, canDraw } = deal ? dealRules(deal) : { inForce: false, canAct: false, canDraw: false };
  const base = deal?.pair.slice(0, 3) ?? '';

  return (
    <Drawer
      isOpen={!!deal}
      onClose={onClose}
      title={deal?.dealRef}
      subtitle={deal ? `${deal.side === 'BUY' ? 'Buy' : 'Sell'} ${deal.pair} ${deal.instrument.toLowerCase()}` : undefined}
    >
      {deal && (
        <div className="p-6 space-y-6">
          <div className="flex flex-wrap items-center gap-2">
            <Badge variant={DEAL_BADGE[deal.status].variant}>{DEAL_BADGE[deal.status].label}</Badge>
            {conf && <Badge variant={conf.variant}>{conf.label}</Badge>}
            {deal.rolledFromId && <Badge variant="neutral">Replaces a rolled deal</Badge>}
          </div>

          <dl className="grid grid-cols-2 gap-3 text-body-sm">
            <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">You buy</dt>
              <dd className="font-mono tabular-nums">{deal.buyCurrency} {fmt(deal.buyAmount)}</dd></div>
            <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">You sell</dt>
              <dd className="font-mono tabular-nums">{deal.sellCurrency} {fmt(deal.sellAmount)}</dd></div>
            <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Rate</dt>
              <dd className="font-mono tabular-nums">{deal.rate.toFixed(rateDigits(deal.rate))}</dd></div>
            <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Value date</dt>
              <dd className="font-mono tabular-nums">{deal.windowStart ? `${deal.windowStart} to ${deal.valueDate}` : deal.valueDate}</dd></div>
            {inForce && (
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Worth today (mid)</dt>
                <dd className={cn('font-mono tabular-nums', toneFor(deal.mtmHome ?? deal.mtmQuote ?? 0))}>
                  {deal.mtmQuote === undefined ? 'Not available'
                    : deal.mtmHome !== undefined ? `${fmtSigned(deal.mtmHome, 0)} ${deal.mtmHomeCurrency}`
                      : `${fmtSigned(deal.mtmQuote, 0)} ${deal.mtmQuoteCurrency}`}
                </dd></div>
            )}
            {deal.instrument === 'WINDOW' && (
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Drawn / left</dt>
                <dd className="font-mono tabular-nums">{fmt(deal.drawnAmount, 0)} / {fmt(deal.remainingAmount, 0)} {base}</dd></div>
            )}
            {deal.realisedPnl !== undefined && (
              <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Cash difference on closing</dt>
                <dd className={cn('font-mono tabular-nums', toneFor(deal.realisedPnl))}>
                  {fmtSigned(deal.realisedPnl)} {deal.pair.slice(4)} at {deal.closeRate?.toFixed(rateDigits(deal.closeRate))}</dd></div>
            )}
          </dl>

          {error && <Alert variant="error">{error}</Alert>}

          <Section title="Confirmation from the bank" id="fx-deal-confirmation">
            <pre className="whitespace-pre-wrap font-mono text-caption bg-surface-muted rounded-md p-3 text-primary-900 dark:text-neutral-50">{text || 'Loading...'}</pre>
            <Button variant="outline" size="sm" onClick={downloadPdf} leftIcon={<Download className="w-4 h-4" />}>Download PDF</Button>

            {deal.confirmationStatus === 'ISSUED' && (
              <div className="space-y-3 pt-1">
                <p className="text-body-sm text-neutral-700 dark:text-neutral-200">
                  Check this against the trade you agreed. {deal.confirmationOverdue ? 'It is overdue. ' : ''}A deal that is not confirmed cannot settle.
                </p>
                {!disputing ? (
                  <div className="flex gap-2">
                    <Button loading={busy} onClick={() => run(() => fxDealApi.confirm(deal.id), 'Deal confirmed')} leftIcon={<Check className="w-4 h-4" />}>Confirm deal</Button>
                    <Button variant="outline" onClick={() => setDisputing(true)} leftIcon={<AlertTriangle className="w-4 h-4" />}>Dispute</Button>
                  </div>
                ) : (
                  <div className="space-y-2">
                    <Input label="What does not match?" value={reason} onChange={(e) => setReason(e.target.value)} placeholder="For example: the rate is not the one I accepted" />
                    <div className="flex gap-2">
                      <Button variant="danger" loading={busy} disabled={!reason.trim()} onClick={() => run(() => fxDealApi.dispute(deal.id, reason), 'Dispute sent to the bank')}>Send dispute</Button>
                      <Button variant="ghost" onClick={() => setDisputing(false)}>Back</Button>
                    </div>
                  </div>
                )}
              </div>
            )}
            {deal.confirmationStatus === 'AFFIRMED' && (
              <p className="text-body-sm text-success-700 dark:text-success-300">
                Confirmed{deal.confirmedBy ? ` by ${deal.confirmedBy}` : ''}{deal.confirmedAt ? ` on ${new Date(deal.confirmedAt).toLocaleString()}` : ''}.
              </p>
            )}
            {deal.confirmationStatus === 'DISPUTED' && (
              <Alert variant="warning" title="Disputed">
                Waiting for the bank back office to resolve it: {deal.disputeReason}. The deal cannot settle meanwhile.
              </Alert>
            )}
          </Section>

          {canAct && (
            <Section title="Manage this deal" id="fx-deal-manage">
              {canDraw && (
                <div id="fx-deal-draw" className="space-y-2 scroll-mt-4">
                  <p className="text-caption text-neutral-500 dark:text-neutral-400">Draw down part of the window. It settles on the date you choose.</p>
                  <div className="grid grid-cols-2 gap-2">
                    <Input label={`Amount (${base})`} inputMode="decimal" value={drawAmount} onChange={(e) => setDrawAmount(e.target.value)} />
                    <Input label="Settles on" type="date" value={drawDate} onChange={(e) => setDrawDate(e.target.value)} />
                  </div>
                  <Button size="sm" loading={busy} disabled={!(Number(drawAmount) > 0) || !drawDate}
                    onClick={() => run(() => fxDealApi.drawdown(deal.id, Number(drawAmount), drawDate), 'Drawdown scheduled')}>Schedule drawdown</Button>
                </div>
              )}

              <div id="fx-deal-close" className="space-y-2 pt-2 scroll-mt-4">
                <p className="text-caption text-neutral-500 dark:text-neutral-400">
                  Close out early: the desk prices the other side now and the cash difference is recorded. The price is not held, so it is set again when you confirm.
                </p>
                {!closeQuote ? (
                  <Button size="sm" variant="outline" loading={busy} onClick={() => preview(async () => setCloseQuote(await fxDealApi.closeOutPreview(deal.id)))}>Price a close-out</Button>
                ) : (
                  <div className="space-y-2">
                    <p className="text-body-sm">
                      At about <span className="font-mono">{closeQuote.closeRate.toFixed(rateDigits(closeQuote.closeRate))}</span> the cash difference is{' '}
                      <strong className={toneFor(closeQuote.realisedPnl)}>{fmtSigned(closeQuote.realisedPnl)} {closeQuote.pnlCurrency}</strong>.
                    </p>
                    <div className="flex gap-2">
                      <Button size="sm" variant="danger" loading={busy} onClick={() => run(() => fxDealApi.closeOut(deal.id), 'Deal closed out')} leftIcon={<X className="w-4 h-4" />}>Close out now</Button>
                      <Button size="sm" variant="ghost" onClick={() => setCloseQuote(null)}>Cancel</Button>
                    </div>
                  </div>
                )}
              </div>

              <div id="fx-deal-roll" className="space-y-2 pt-2 scroll-mt-4">
                <p className="text-caption text-neutral-500 dark:text-neutral-400">Roll: close this deal and book the same amount for a later date.</p>
                <div className="flex items-end gap-2">
                  <div className="flex-1"><Input label="New value date" type="date" value={rollDate} onChange={(e) => { setRollDate(e.target.value); setRollQuote(null); }} /></div>
                  <Button size="sm" variant="outline" loading={busy} disabled={!rollDate}
                    onClick={() => preview(async () => setRollQuote(await fxDealApi.rollPreview(deal.id, rollDate)))}>Price roll</Button>
                </div>
                {rollQuote && (
                  <div className="space-y-2">
                    <p className="text-body-sm">
                      Closing now: <strong className={toneFor(rollQuote.closeOut.realisedPnl)}>{fmtSigned(rollQuote.closeOut.realisedPnl)} {rollQuote.closeOut.pnlCurrency}</strong>.
                      New forward to {rollQuote.newValueDate} at <span className="font-mono">{rollQuote.newRate.toFixed(rateDigits(rollQuote.newRate))}</span>.
                    </p>
                    <Button size="sm" loading={busy} onClick={() => run(() => fxDealApi.roll(deal.id, rollDate), 'Deal rolled')}>Roll now</Button>
                  </div>
                )}
              </div>
            </Section>
          )}

          {settlements.length > 0 && (
            <Section title="Settlement (recorded, no balance moved)">
              <ul className="space-y-2">
                {settlements.map((s) => (
                  <li key={s.id} className="text-body-sm border border-edge-subtle rounded-md p-2">
                    <div className="flex items-center justify-between gap-2">
                      <span className="font-medium">{s.kind === 'CLOSE_OUT' ? 'Close-out difference' : s.kind === 'DRAWDOWN' ? 'Drawdown' : 'Maturity'} · {s.valueDate}</span>
                      <Badge size="sm" variant={s.status === 'SETTLED' ? 'success' : s.status === 'FAILED' ? 'error' : 'neutral'}>{s.status.toLowerCase()}</Badge>
                    </div>
                    <p className="font-mono text-caption tabular-nums mt-1">
                      {s.kind === 'CLOSE_OUT'
                        ? (s.buyAmount > 0 ? `Corporate receives ${s.buyCurrency} ${fmt(s.buyAmount)}` : `Corporate pays ${s.sellCurrency} ${fmt(s.sellAmount)}`)
                        : `Buy ${s.buyCurrency} ${fmt(s.buyAmount)} · sell ${s.sellCurrency} ${fmt(s.sellAmount)}`}
                    </p>
                    {s.reason && <p className="text-caption text-error-700 dark:text-error-300 mt-1">{s.reason}</p>}
                  </li>
                ))}
              </ul>
            </Section>
          )}

          <Section title="History">
            <EventTimeline entries={events} loading={!events.length} />
          </Section>

          <p className="text-caption text-neutral-500 dark:text-neutral-400 flex items-center gap-1.5"><FileText className="w-3.5 h-3.5" /> Simulated desk. No real trade, settlement instruction or payment is made.</p>
        </div>
      )}
    </Drawer>
  );
};

export default FxDealDrawer;
