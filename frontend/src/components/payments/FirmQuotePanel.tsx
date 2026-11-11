// FirmQuotePanel — treasury negotiates the rate for one queued payment or held credit (docs/specs/ihb-fx.md D7/D8).
// The desk's firm price for payment (POST /fx/quotes with forPayment), countered a few rounds as on the FX ticket
// (NewDealDrawer), then onAccept(quoteId) pays or posts the item with it in one server call, so the deal is that
// item's and nothing else's. Only the all-in client rate is shown, never the mid or margin. A price that lapses
// leaves the item waiting; ask for a new one.

import React, { useEffect, useRef, useState } from 'react';
import { Check, Clock, MessageSquareQuote, Send } from 'lucide-react';
import { Badge, Button, Input } from '../ui';
import { fxDealApi, Quote, Side } from '../../services/fxDealApi';
import { QUOTE_BADGE } from '../../pages/fxDealBadges';
import { errorText, fmt, fmtRate } from '../../pages/fx/fxFormat';
import { cn } from '../../utils';

interface Props {
  corporateId: string;
  /** The treasury centre: the deal is on its FX line. */
  entityId: string;
  /** "EUR/AED": the item's currency against the settle currency. */
  pair: string;
  /** BUY: treasury buys the currency to pay it out; SELL: treasury sells the currency it received. */
  side: Side;
  /** In the item's currency (the pair's base). */
  amount: number;
  acceptLabel: string;
  onAccept: (quoteId: string) => Promise<void>;
}

export const FirmQuotePanel: React.FC<Props> = ({ corporateId, entityId, pair, side, amount, acceptLabel, onAccept }) => {
  const [quote, setQuote] = useState<Quote | null>(null);
  const [remaining, setRemaining] = useState(0);
  const [target, setTarget] = useState('');
  const [busy, setBusy] = useState<'request' | 'counter' | 'accept' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const quoteRef = useRef<Quote | null>(null);
  quoteRef.current = quote;

  // Closing with a price still firm withdraws it, so the RFQ log says "Withdrawn" and not "Expired".
  useEffect(() => () => {
    const q = quoteRef.current;
    if (q?.status === 'QUOTED') fxDealApi.cancel(q.id).catch(() => undefined);
  }, []);

  // Count the validity down locally (server seconds), then ask the server what became of it.
  useEffect(() => {
    if (quote?.status !== 'QUOTED') return;
    setRemaining(quote.secondsRemaining);
    const id = setInterval(() => setRemaining((s) => {
      if (s <= 1) {
        clearInterval(id);
        const q = quoteRef.current;
        if (q) fxDealApi.getQuote(q.id).then(setQuote).catch(() => undefined);
        return 0;
      }
      return s - 1;
    }), 1000);
    return () => clearInterval(id);
  }, [quote?.id, quote?.status, quote?.quotedAt]);

  const run = async (what: 'request' | 'counter' | 'accept', fn: () => Promise<void>) => {
    setBusy(what);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(errorText(e));
      const q = quoteRef.current;
      if (q) fxDealApi.getQuote(q.id).then(setQuote).catch(() => undefined);
    } finally {
      setBusy(null);
    }
  };

  const request = () => run('request', async () => {
    const q = await fxDealApi.requestQuote({ corporateId, entityId, pair, side, instrument: 'SPOT', amount,
      purpose: side === 'BUY' ? 'Payment conversion' : 'Collection conversion', forPayment: true });
    setQuote(q);
    if (q.status !== 'QUOTED') setError(q.statusReason ?? 'The desk did not price this');
  });

  const counter = () => run('counter', async () => {
    const wanted = target.trim() ? Number(target) : undefined;
    if (wanted !== undefined && !(wanted > 0)) throw new Error('Enter the rate you want, or leave it blank to ask for the best price.');
    setQuote(await fxDealApi.counter(quote!.id, { targetRate: wanted }));
    setTarget('');
  });

  const accept = () => run('accept', async () => {
    await onAccept(quote!.id);
    quoteRef.current = null; // used: nothing to withdraw on close
  });

  const [base, settle] = [pair.slice(0, 3), pair.slice(4)];
  const firm = quote?.status === 'QUOTED';
  const roundsLeft = quote ? quote.maxRounds - quote.rounds : 0;

  return (
    <div className="space-y-4">
      {!firm && (
        <Button onClick={request} loading={busy === 'request'} leftIcon={<Send className="w-4 h-4" />}>
          {quote ? 'Request a new price' : 'Request a price'}
        </Button>
      )}
      {quote && !firm && quote.status !== 'REQUESTED' && (
        <p className="text-body-sm text-neutral-600 dark:text-neutral-300">
          <Badge variant={QUOTE_BADGE[quote.status].variant} size="sm">{QUOTE_BADGE[quote.status].label}</Badge>{' '}
          {quote.status === 'EXPIRED' ? 'The price lapsed; the item is still waiting.' : quote.statusReason ?? ''}
        </p>
      )}
      {firm && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-baseline justify-between gap-3">
            <span className="font-mono tabular-nums text-display text-primary-900 dark:text-neutral-50">
              {quote.clientRate !== undefined && fmtRate(quote.clientRate)}
            </span>
            <span className={cn('inline-flex items-center gap-1.5 text-body-sm font-medium tabular-nums',
              remaining <= 10 ? 'text-error-700 dark:text-error-300' : 'text-neutral-600 dark:text-neutral-300')}>
              <Clock className="w-4 h-4" /> Firm for {remaining}s
            </span>
          </div>
          <p className="text-body">
            Treasury {side === 'BUY' ? 'buys' : 'sells'} <strong>{base} {fmt(quote.amount)}</strong> and{' '}
            {side === 'BUY' ? 'pays' : 'receives'} <strong className="font-mono tabular-nums">{settle} {fmt(quote.counterAmount ?? 0)}</strong>.
          </p>
          {quote.maxRounds > 0 && (
            <div className="rounded-lg border border-edge-subtle bg-surface-muted p-3 space-y-3">
              <div className="flex items-center justify-between gap-2">
                <span className="body-strong inline-flex items-center gap-1.5"><MessageSquareQuote className="w-4 h-4" /> Ask for a better price</span>
                <span className="text-caption text-neutral-500 dark:text-neutral-400">Round {quote.rounds} of {quote.maxRounds}</span>
              </div>
              {quote.bestPrice && <p className="text-body-sm text-warning-700 dark:text-warning-300">The desk says this is its best price.</p>}
              {roundsLeft > 0 ? (
                <div className="flex items-end gap-2">
                  <Input label="Rate you want (optional)" inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)}
                    hint={side === 'BUY' ? 'Lower than the price above' : 'Higher than the price above'} />
                  <Button size="sm" variant="outline" onClick={counter} loading={busy === 'counter'} disabled={busy === 'accept'}>
                    {target.trim() ? 'Ask for this rate' : 'Ask for the best price'}
                  </Button>
                </div>
              ) : (
                <p className="text-caption text-neutral-500 dark:text-neutral-400">No more rounds on this request.</p>
              )}
            </div>
          )}
          <Button onClick={accept} loading={busy === 'accept'} disabled={busy === 'counter'} leftIcon={<Check className="w-4 h-4" />}>
            {acceptLabel}
          </Button>
        </div>
      )}
      {error && <p className="text-body-sm text-error-700 dark:text-error-300">{error}</p>}
    </div>
  );
};
