// PaymentFxPanel — converting a payment whose currency is not its account's (backend: FxPaymentService). Within the
// pair's streaming limits the live price shows and locks when the payment is submitted; above them the payer asks
// the desk for a firm price. Or the payment draws on a deal booked earlier: a window forward open today, or a forward
// due today. The panel reports what it would submit (or null) through onChange; the server re-checks everything.

import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRightLeft, Clock } from 'lucide-react';
import { Button, Select } from '../ui';
import { fxDealApi, Deal, Quote, StreamPrice } from '../../services/fxDealApi';
import { cn } from '../../utils';

export interface PaymentFx {
  fxPriceId?: string;
  fxQuoteId?: string;
  fxDealId?: string;
}

interface Props {
  corporateId?: string | null;
  /** The legal entity owning the paying account; the FX deal is booked for it. */
  entityId?: string;
  accountCurrency?: string;
  currency?: string;
  amount: number;
  onChange: (fx: PaymentFx | null) => void;
}

const fmt = (n: number) => n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export const PaymentFxPanel: React.FC<Props> = ({ corporateId, entityId, accountCurrency, currency, amount, onChange }) => {
  const needed = !!currency && !!accountCurrency && currency !== accountCurrency && amount > 0;
  const pair = `${currency}/${accountCurrency}`;
  const today = new Date().toLocaleDateString('en-CA');
  const [mode, setMode] = useState<'rate' | 'deal'>('rate');
  const [stream, setStream] = useState<StreamPrice | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [left, setLeft] = useState(0);
  const [asking, setAsking] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [deals, setDeals] = useState<Deal[]>([]);
  const [dealId, setDealId] = useState('');

  // The live price, every two seconds, while the rate is to be locked and no firm price is held.
  useEffect(() => {
    setStream(null);
    setQuote(null);
    setError(null);
    if (!needed || mode !== 'rate' || !corporateId || !entityId) return;
    let current = true;
    const load = () => fxDealApi.stream({ corporateId, entityId, pair, amount, instrument: 'SPOT', purpose: 'Payment conversion', forPayment: true })
      .then((s) => { if (current) setStream(s); })
      .catch((e) => { if (current) { setStream(null); setError(e?.response?.data?.message ?? 'No price for this currency pair'); } });
    load();
    const id = setInterval(load, 2000);
    return () => { current = false; clearInterval(id); };
  }, [needed, mode, corporateId, entityId, pair, amount]);

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

  // Deals this payment may draw on today.
  useEffect(() => {
    if (!needed || mode !== 'deal' || !corporateId) return;
    fxDealApi.deals(corporateId).then(setDeals).catch(() => setDeals([]));
  }, [needed, mode, corporateId]);
  const usable = useMemo(() => deals.filter((d) => d.buyCurrency === currency && d.sellCurrency === accountCurrency
    && (d.status === 'ACTIVE' || d.status === 'PARTIALLY_DRAWN') && d.confirmationStatus === 'AFFIRMED'
    && ((d.instrument === 'WINDOW' && (d.windowStart ?? '') <= today && today <= d.valueDate) || (d.instrument === 'FORWARD' && d.valueDate === today))),
  [deals, currency, accountCurrency, today]);

  const streaming = !!stream?.streamable && !!stream.priceId;
  useEffect(() => {
    if (!needed) onChange(null);
    else if (mode === 'deal') onChange(dealId ? { fxDealId: dealId } : null);
    else if (quote?.status === 'QUOTED') onChange({ fxQuoteId: quote.id });
    else if (streaming) onChange({ fxPriceId: stream?.priceId });
    else onChange(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onChange is the parent's setter; reporting on its identity would loop.
  }, [needed, mode, dealId, quote, streaming, stream?.priceId]);

  if (!needed) return null;

  const askFirm = async () => {
    if (!corporateId || !entityId) return;
    setAsking(true);
    setError(null);
    try {
      const q = await fxDealApi.requestQuote({ corporateId, entityId, pair, side: 'BUY', instrument: 'SPOT', amount, purpose: 'Payment conversion', forPayment: true });
      if (q.status !== 'QUOTED') setError(q.statusReason ?? 'The desk did not price this');
      else setQuote(q);
    } catch (e) {
      setError((e as { response?: { data?: { message?: string } } })?.response?.data?.message ?? 'The desk did not price this');
    } finally {
      setAsking(false);
    }
  };

  const chosen = usable.find((d) => d.id === dealId);
  return (
    <div className="rounded-lg border border-edge bg-surface-muted p-4 space-y-3">
      <div className="flex items-center justify-between gap-2">
        <span className="body-strong inline-flex items-center gap-1.5"><ArrowRightLeft className="w-4 h-4" /> Currency conversion</span>
        <span className="text-caption text-neutral-500 dark:text-neutral-400">Pays {currency} from a {accountCurrency} account</span>
      </div>
      {!entityId ? (
        <p className="text-body-sm text-warning-700 dark:text-warning-300">This account belongs to no legal entity, so the bank cannot book the conversion for it. Pay from an entity's account.</p>
      ) : (
        <>
          <div className="grid grid-cols-2 gap-2" role="radiogroup" aria-label="How to convert">
            {(['rate', 'deal'] as const).map((m) => (
              <Button key={m} size="sm" variant={mode === m ? 'primary' : 'outline'} aria-pressed={mode === m} onClick={() => setMode(m)}>
                {m === 'rate' ? 'Lock a rate' : 'Use a booked deal'}
              </Button>
            ))}
          </div>

          {mode === 'rate' && (
            <div className="space-y-2">
              {quote?.status === 'QUOTED' ? (
                <p className="text-body-sm">
                  Firm price <span className="font-mono tabular-nums">{quote.clientRate}</span>: the account is debited{' '}
                  <strong className="font-mono tabular-nums">{accountCurrency} {fmt(quote.counterAmount ?? 0)}</strong>.{' '}
                  <span className={cn('inline-flex items-center gap-1 tabular-nums', left <= 10 && 'text-error-700 dark:text-error-300')}>
                    <Clock className="w-3.5 h-3.5" /> submit within {left}s
                  </span>
                </p>
              ) : streaming && stream?.buy ? (
                <p className="text-body-sm">
                  Live rate <span className="font-mono tabular-nums">{stream.buy.rate}</span>: the account is debited about{' '}
                  <strong className="font-mono tabular-nums">{accountCurrency} {fmt(stream.buy.counterAmount)}</strong>. The rate locks when you submit.
                </p>
              ) : stream && !stream.streamable ? (
                <div className="space-y-2">
                  <p className="text-body-sm text-neutral-600 dark:text-neutral-300">{stream.reason}, so ask for a firm price for this payment.</p>
                  <Button size="sm" variant="outline" loading={asking} onClick={askFirm}>Get a firm price</Button>
                </div>
              ) : !error ? (
                <p className="text-caption text-neutral-500 dark:text-neutral-400">Getting a price...</p>
              ) : null}
            </div>
          )}

          {mode === 'deal' && (
            usable.length ? (
              <div className="space-y-2">
                <Select label="Deal to draw on" value={dealId} onChange={(e) => setDealId(e.target.value)} placeholder="Choose a deal"
                  options={usable.map((d) => ({ value: d.id, label: `${d.dealRef}, ${d.instrument === 'WINDOW' ? 'window' : 'forward'} at ${d.rate}, ${d.remainingAmount.toLocaleString()} ${d.pair.slice(0, 3)} left` }))} />
                {chosen && (
                  <p className="text-caption text-neutral-500 dark:text-neutral-400">
                    At {chosen.rate} the account is debited about {accountCurrency}{' '}
                    {fmt(chosen.pair.slice(0, 3) === currency ? amount * chosen.rate : amount / chosen.rate)}; the rest of the deal stays open.
                  </p>
                )}
              </div>
            ) : (
              <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
                No deal buying {currency} with {accountCurrency} can pay today: a confirmed window forward open today, or a forward due today. Lock a rate instead.
              </p>
            )
          )}
          {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
        </>
      )}
    </div>
  );
};
