// PaymentFxPanel — converting a payment whose currency is not its account's (backend: FxPaymentService). The
// corporate's outstanding deals that fit come first, soonest value date first, each covering what it can: a window
// open today, a forward due today or later (taken early at its rate), a spot not yet settled. The payer can untick
// any. Whatever the ticked deals leave is converted at the live price (within the pair's streaming limits; it locks
// when the payment is submitted) or at a firm price from the desk above them. The panel reports what it would submit
// (or null while something is missing) through onChange; the server re-checks everything.

import React, { useEffect, useState } from 'react';
import { ArrowRightLeft, Clock } from 'lucide-react';
import { Button, Checkbox } from '../ui';
import { fxDealApi, FxDraw, PaymentPlan, Quote, StreamPrice } from '../../services/fxDealApi';
import { cn } from '../../utils';

export interface PaymentFx {
  fxPriceId?: string;
  fxQuoteId?: string;
  /** Deals to draw on first, with the amount each covers in the payment's currency. */
  fxDeals?: FxDraw[];
}

interface Props {
  /** The paying account: its entity's deals are the ones that fit. */
  accountId?: string;
  corporateId?: string | null;
  /** The legal entity owning the paying account; an FX deal is booked for it. */
  entityId?: string;
  accountCurrency?: string;
  currency?: string;
  amount: number;
  onChange: (fx: PaymentFx | null) => void;
}

const fmt = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const what = (instrument: string, valueDate: string) =>
  instrument === 'WINDOW' ? `window to ${valueDate}` : instrument === 'SPOT' ? `spot, value ${valueDate}` : `forward, value ${valueDate}`;

export const PaymentFxPanel: React.FC<Props> = ({ accountId, corporateId, entityId, accountCurrency, currency, amount, onChange }) => {
  const needed = !!currency && !!accountCurrency && currency !== accountCurrency && amount > 0;
  const pair = `${currency}/${accountCurrency}`;
  // null: every deal that fits, in the plan's order; otherwise the ids the payer kept ticked
  const [use, setUse] = useState<string[] | null>(null);
  const [plan, setPlan] = useState<PaymentPlan | null>(null);
  const [stream, setStream] = useState<StreamPrice | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [left, setLeft] = useState(0);
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A different payment starts again from every deal that fits.
  useEffect(() => { setUse(null); }, [accountId, currency, amount]);

  // The deals that fit and what each covers; debounced, as the amount is typed.
  useEffect(() => {
    setPlan(null);
    if (!needed || !accountId || !entityId || !currency) return;
    let current = true;
    const id = setTimeout(() => {
      fxDealApi.paymentPlan(accountId, currency, amount, use ?? undefined)
        .then((p) => { if (current) setPlan(p); })
        .catch((e) => { if (current) setError(e?.response?.data?.message ?? 'Could not read the deals that fit this payment'); });
    }, 300);
    return () => { current = false; clearTimeout(id); };
  }, [needed, accountId, entityId, currency, amount, use]);

  const rest = plan ? plan.rest : 0;

  // The live price for what the deals leave, every two seconds, while no firm price is held.
  useEffect(() => {
    setStream(null);
    setQuote(null);
    setError(null);
    if (!plan || rest <= 0 || !corporateId || !entityId) return;
    let current = true;
    const load = () => fxDealApi.stream({ corporateId, entityId, pair, amount: rest, instrument: 'SPOT', purpose: 'Payment conversion', forPayment: true })
      .then((s) => { if (current) setStream(s); })
      .catch((e) => { if (current) { setStream(null); setError(e?.response?.data?.message ?? 'No price for this currency pair'); } });
    load();
    const id = setInterval(load, 2000);
    return () => { current = false; clearInterval(id); };
  }, [plan, rest, corporateId, entityId, pair]);

  // A firm price counts down; at zero it is gone.
  useEffect(() => {
    if (!quote || quote.status !== 'QUOTED') return;
    setLeft(quote.secondsRemaining);
    const id = setInterval(() => setLeft((s) => {
      if (s <= 1) { clearInterval(id); setQuote(null); return 0; }
      return s - 1;
    }), 1000);
    return () => clearInterval(id);
  }, [quote]);

  const draws: FxDraw[] = (plan?.deals ?? []).filter((d) => d.covers > 0).map((d) => ({ dealId: d.dealId, amount: d.covers }));
  const streaming = !!stream?.streamable && !!stream.priceId;
  const firm = quote?.status === 'QUOTED';
  useEffect(() => {
    if (!needed || !plan) onChange(null);
    else if (rest <= 0) onChange({ fxDeals: draws });
    else if (firm) onChange({ fxDeals: draws, fxQuoteId: quote?.id });
    else if (streaming) onChange({ fxDeals: draws, fxPriceId: stream?.priceId });
    else onChange(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onChange is the parent's setter; draws is derived from plan.
  }, [needed, plan, rest, firm, quote?.id, streaming, stream?.priceId]);

  if (!needed) return null;

  const toggle = (dealId: string) => {
    const ticked = (plan?.deals ?? []).filter((d) => (use ? use.includes(d.dealId) : d.covers > 0)).map((d) => d.dealId);
    setUse(ticked.includes(dealId) ? ticked.filter((x) => x !== dealId) : [...ticked, dealId]);
  };

  const askFirm = async () => {
    if (!corporateId || !entityId) return;
    setAsking(true);
    setError(null);
    try {
      const q = await fxDealApi.requestQuote({ corporateId, entityId, pair, side: 'BUY', instrument: 'SPOT', amount: rest, purpose: 'Payment conversion', forPayment: true });
      if (q.status !== 'QUOTED') setError(q.statusReason ?? 'The desk did not price this');
      else setQuote(q);
    } catch (e) {
      setError((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'The desk did not price this');
    } finally {
      setAsking(false);
    }
  };

  const dealDebit = (plan?.deals ?? []).reduce((s, d) => s + d.debit, 0);
  const restDebit = rest <= 0 ? 0 : firm ? quote?.counterAmount ?? 0 : streaming ? stream?.buy?.counterAmount ?? 0 : null;
  const anyDeal = (plan?.deals.length ?? 0) > 0;

  return (
    <div className="rounded-lg border border-edge bg-surface-muted p-4 space-y-3">
      <div className="flex items-center justify-between gap-2">
        <span className="body-strong inline-flex items-center gap-1.5"><ArrowRightLeft className="w-4 h-4" /> Currency conversion</span>
        <span className="text-caption text-neutral-500 dark:text-neutral-400">Pays {currency} from a {accountCurrency} account</span>
      </div>
      {!entityId || !accountId ? (
        <p className="text-body-sm text-warning-700 dark:text-warning-300">This account belongs to no legal entity, so the bank cannot book the conversion for it. Pay from an entity's account.</p>
      ) : !plan ? (
        error ? null : <p className="text-caption text-neutral-500 dark:text-neutral-400">Looking for deals that fit...</p>
      ) : (
        <>
          {anyDeal ? (
            <fieldset className="space-y-1.5">
              <div className="flex items-center justify-between">
                <legend className="text-caption font-medium text-neutral-600 dark:text-neutral-300">Your deals buying {currency} with {accountCurrency}, soonest first</legend>
                {draws.length > 0 && (
                  <button type="button" className="text-caption text-primary-700 hover:underline dark:text-primary-300" onClick={() => setUse([])}>Rate only</button>
                )}
              </div>
              {plan.deals.map((d) => {
                const on = use ? use.includes(d.dealId) : d.covers > 0;
                return (
                  <Checkbox key={d.dealId} variant="card" size="sm" checked={on} onChange={() => toggle(d.dealId)}
                    label={<><span className="font-mono">{d.dealRef}</span>{' '}
                      <span className="font-normal text-neutral-500 dark:text-neutral-400">{what(d.instrument, d.valueDate)} at <span className="font-mono tabular-nums">{d.rate}</span></span></>}
                    description={<span className="tabular-nums">{d.covers > 0
                      ? `Covers ${currency} ${fmt(d.covers)} of the ${fmt(d.available)} left; debits ${accountCurrency} ${fmt(d.debit)}`
                      : `${currency} ${fmt(d.available)} left`}</span>} />
                );
              })}
            </fieldset>
          ) : (
            <p className="text-caption text-neutral-500 dark:text-neutral-400">No outstanding deal buying {currency} with {accountCurrency} can pay this today.</p>
          )}

          {rest > 0 && (
            <div className="space-y-2">
              {firm ? (
                <p className="text-body-sm">
                  {draws.length ? <>The other {currency} {fmt(rest)} at a firm price</> : <>Firm price</>}{' '}
                  <span className="font-mono tabular-nums">{quote?.clientRate}</span>: debits{' '}
                  <strong className="font-mono tabular-nums">{accountCurrency} {fmt(quote?.counterAmount ?? 0)}</strong>.{' '}
                  <span className={cn('inline-flex items-center gap-1 tabular-nums', left <= 10 && 'text-error-700 dark:text-error-300')}>
                    <Clock className="w-3.5 h-3.5" /> submit within {left}s
                  </span>
                </p>
              ) : streaming && stream?.buy ? (
                <p className="text-body-sm">
                  {draws.length ? <>The other {currency} {fmt(rest)} at the live rate</> : <>Live rate</>}{' '}
                  <span className="font-mono tabular-nums">{stream.buy.rate}</span>: debits about{' '}
                  <strong className="font-mono tabular-nums">{accountCurrency} {fmt(stream.buy.counterAmount)}</strong>. The rate locks when you submit.
                </p>
              ) : stream && !stream.streamable ? (
                <div className="space-y-2">
                  <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
                    {stream.reason}, so ask for a firm price for {draws.length ? `the other ${currency} ${fmt(rest)}` : 'this payment'}.
                  </p>
                  <Button size="sm" variant="outline" loading={asking} onClick={askFirm}>Get a firm price</Button>
                </div>
              ) : !error ? (
                <p className="text-caption text-neutral-500 dark:text-neutral-400">Getting a price...</p>
              ) : null}
            </div>
          )}

          {restDebit !== null && (
            <p className="text-body-sm border-t border-edge pt-2 flex justify-between gap-2">
              <span className="text-neutral-600 dark:text-neutral-300">Debit</span>
              <strong className="font-mono tabular-nums">{rest > 0 && !firm ? 'about ' : ''}{accountCurrency} {fmt(dealDebit + restDebit)}</strong>
            </p>
          )}
        </>
      )}
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
    </div>
  );
};
