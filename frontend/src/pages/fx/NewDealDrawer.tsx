// ============================================================================
// NewDealDrawer — the one place a price is requested, negotiated and accepted. Opened from the header
// "+ New Deal" button and from row actions on every Foreign Exchange list (a rate, a hedge gap, an earlier
// request), pre-filled where the row knows the answer.
//
// The desk prices at once and holds the price for a short time; the countdown comes from the server clock and
// accepting re-checks validity and the credit limit on the server, so nothing here is the authority. Before
// accepting, the corporate may ask for a better price a few times (simulated desk, see SimulatedDealer.counter):
// each answer is a new firm price with a fresh countdown, or "this is our best".
//
// Small, short trades stream instead (what streams is set by the bank per pair): both sides refresh every two
// seconds and one click deals. Anything larger or longer is requested as above.
// ============================================================================

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Send, Check, X, Clock, MessageSquareQuote } from 'lucide-react';
import { Badge, Button, Drawer, Input, Select } from '../../components/ui';
import { Alert } from '../../components/ui/enhanced';
import { useUser } from '../../context/useUser';
import { cn } from '../../utils';
import { fxMarketApi } from '../../services/fxMarketApi';
import { fxDealApi, CreditLimit, Instrument, Quote, ResolvedAccount, Side, StreamLeg, StreamPrice } from '../../services/fxDealApi';
import { QUOTE_BADGE } from '../fxDealBadges';
import { errorText, fmt, fmtRate } from './fxFormat';

/** What a row can tell the ticket. Anything left out takes the default. A tenor is resolved to a value date. */
export interface TicketPrefill {
  pair?: string;
  side?: Side;
  amount?: number;
  instrument?: Instrument;
  tenor?: string;
  valueDate?: string;
  windowStart?: string;
  purpose?: string;
  exposureRef?: string;
  /** The legal entity the deal is for; defaults to the entity chosen in the header. */
  entityId?: string;
}

const INSTRUMENTS: { value: Instrument; label: string }[] = [
  { value: 'SPOT', label: 'Spot' },
  { value: 'FORWARD', label: 'Forward' },
  { value: 'WINDOW', label: 'Window forward' },
];

interface Props {
  isOpen: boolean;
  onClose: () => void;
  corporateId: string;
  prefill: TicketPrefill | null;
  /** The request, or a deal, changed: refresh the list behind the drawer. */
  onChanged: () => void;
}

const NewDealDrawer: React.FC<Props> = ({ isOpen, onClose, corporateId, prefill, onChanged }) => {
  const { corporates, entities, currentEntityId } = useUser();
  const corporateName = corporates.find((c) => c.id === corporateId)?.name;
  const [entityId, setEntityId] = useState('');
  const [room, setRoom] = useState<CreditLimit | null>(null);
  /** Where each leg would settle for this entity, or why it cannot. */
  const [legs, setLegs] = useState<{ buy: ResolvedAccount; sell: ResolvedAccount } | null>(null);

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
  const [prices, setPrices] = useState<number[]>([]);
  const [remaining, setRemaining] = useState(0);
  const [requesting, setRequesting] = useState(false);
  const [accepting, setAccepting] = useState(false);
  const [countering, setCountering] = useState(false);
  const [target, setTarget] = useState('');
  const [note, setNote] = useState('');
  const [bookedRef, setBookedRef] = useState<string | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [quoteError, setQuoteError] = useState<string | null>(null);

  const quoteRef = useRef<Quote | null>(null);
  quoteRef.current = quote;

  useEffect(() => {
    if (!isOpen || pairs.length) return;
    fxMarketApi.spots()
      .then((spots) => {
        const listed = spots.map((s) => s.pair);
        setPairs([...new Set([...listed, ...listed.map((p) => `${p.slice(4)}/${p.slice(0, 3)}`)])].sort());
      })
      .catch(() => undefined);
  }, [isOpen, pairs.length]);

  // Each time the drawer opens it starts from the row that opened it (or from a blank ticket).
  useEffect(() => {
    if (!isOpen) return;
    const p = prefill ?? {};
    const known = (id?: string | null) => (id && entities.some((e) => e.id === id) ? id : undefined);
    setEntityId(known(p.entityId) ?? known(currentEntityId) ?? (entities.find((e) => e.isTreasuryCenter) ?? entities[0])?.id ?? '');
    setPair(p.pair ?? 'EUR/USD');
    setSide(p.side ?? 'BUY');
    setAmount(String(p.amount ? Math.abs(p.amount) : 100000));
    setInstrument(p.instrument ?? (p.tenor || p.valueDate ? 'FORWARD' : 'SPOT'));
    setValueDate(p.valueDate ?? '');
    setWindowStart(p.windowStart ?? '');
    setPurpose(p.purpose ?? '');
    setExposureRef(p.exposureRef ?? '');
    setQuote(null);
    setPrices([]);
    setTarget('');
    setNote('');
    setBookedRef(null);
    setFormError(null);
    setQuoteError(null);
    let current = true;
    if (p.tenor) {
      fxMarketApi.curve(p.pair ?? 'EUR/USD')
        .then((curve) => { if (current) setValueDate(curve.find((q) => q.tenor === p.tenor)?.valueDate ?? ''); })
        .catch(() => undefined);
    }
    return () => { current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-run on open or a new prefill only; entities and the header's entity are read once per opening.
  }, [isOpen, prefill]);

  useEffect(() => {
    if (!isOpen || !corporateId || !entityId) { setRoom(null); return; }
    let current = true;
    fxDealApi.limit(corporateId, entityId).then((r) => { if (current) setRoom(r); }).catch(() => undefined);
    return () => { current = false; };
  }, [isOpen, corporateId, entityId, quote?.status]);

  useEffect(() => {
    if (!isOpen || !corporateId || !entityId) { setLegs(null); return; }
    const [b, q] = [pair.slice(0, 3), pair.slice(4)];
    const [buyCcy, sellCcy] = side === 'BUY' ? [b, q] : [q, b];
    let current = true;
    Promise.all([fxDealApi.settlementAccount(corporateId, entityId, buyCcy), fxDealApi.settlementAccount(corporateId, entityId, sellCcy)])
      .then(([buy, sell]) => { if (current) setLegs({ buy: buy.resolved, sell: sell.resolved }); })
      .catch(() => { if (current) setLegs(null); });
    return () => { current = false; };
  }, [isOpen, corporateId, entityId, pair, side]);
  // The stream: re-priced every two seconds while the form describes a trade and nothing is quoted or booked.
  const [stream, setStream] = useState<StreamPrice | null>(null);
  const [dealing, setDealing] = useState<Side | null>(null);
  useEffect(() => {
    const n = Number(amount);
    if (!isOpen || !corporateId || !entityId || !(n > 0) || (instrument !== 'SPOT' && !valueDate) || quote || bookedRef) {
      setStream(null);
      return;
    }
    let current = true;
    const load = () => fxDealApi.stream({
      corporateId, entityId, pair, amount: n, instrument,
      valueDate: instrument === 'SPOT' ? undefined : valueDate,
      purpose: purpose || undefined, exposureRef: exposureRef || undefined,
    }).then((s) => { if (current) setStream(s); }).catch(() => { if (current) setStream(null); });
    load();
    const id = setInterval(load, 2000);
    return () => { current = false; clearInterval(id); };
  }, [isOpen, corporateId, entityId, pair, amount, instrument, valueDate, purpose, exposureRef, quote, bookedRef]);
  const streaming = !!stream?.streamable;

  const onStreamDeal = async (s: Side) => {
    if (!stream?.priceId) return;
    setDealing(s);
    setFormError(null);
    try {
      const deal = await fxDealApi.streamDeal(stream.priceId, s);
      setBookedRef(deal.dealRef);
      onChanged();
    } catch (err) {
      setFormError(errorText(err));
    } finally {
      setDealing(null);
    }
  };

  const noAccount = legs ? (!legs.buy.account ? legs.buy.reason : !legs.sell.account ? legs.sell.reason : undefined) : undefined;

  // Count the validity window down locally, then ask the server what became of the quote. A counter-offer
  // changes quotedAt, so the countdown starts again even when the seconds remaining happen to be equal.
  useEffect(() => {
    if (quote?.status !== 'QUOTED') return;
    setRemaining(quote.secondsRemaining);
    const id = setInterval(() => {
      setRemaining((s) => {
        if (s <= 1) {
          clearInterval(id);
          const q = quoteRef.current;
          if (q) fxDealApi.getQuote(q.id).then((next) => { setQuote(next); onChanged(); }).catch(() => undefined);
          return 0;
        }
        return s - 1;
      });
    }, 1000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- onChanged is the parent's refresh; re-arming the countdown on its identity would restart it every render.
  }, [quote?.id, quote?.status, quote?.quotedAt]);

  const onRequest = async () => {
    const n = Number(amount);
    if (!entityId) { setFormError('Choose the entity the deal is for.'); return; }
    if (!corporateId || !(n > 0)) { setFormError('Enter an amount greater than zero.'); return; }
    if (instrument !== 'SPOT' && !valueDate) { setFormError('Pick a value date.'); return; }
    if (instrument === 'WINDOW' && !windowStart) { setFormError('Pick the first drawdown date.'); return; }
    setRequesting(true);
    setFormError(null);
    setQuoteError(null);
    try {
      const q = await fxDealApi.requestQuote({
        corporateId, entityId, pair, side, instrument, amount: n,
        valueDate: instrument === 'SPOT' ? undefined : valueDate,
        windowStart: instrument === 'WINDOW' ? windowStart : undefined,
        purpose: purpose || undefined,
        exposureRef: exposureRef || undefined,
      });
      setQuote(q);
      setPrices(q.clientRate !== undefined ? [q.clientRate] : []);
      onChanged();
    } catch (err) {
      setQuote(null);
      setFormError(errorText(err));
    } finally {
      setRequesting(false);
    }
  };

  const onCounter = async () => {
    if (!quote) return;
    const wanted = target.trim() ? Number(target) : undefined;
    if (wanted !== undefined && !(wanted > 0)) {
      setQuoteError('Enter the rate you want, or leave it blank to ask for the best price.');
      return;
    }
    setCountering(true);
    setQuoteError(null);
    try {
      const next = await fxDealApi.counter(quote.id, { targetRate: wanted, note: note.trim() || undefined });
      setQuote(next);
      if (next.clientRate !== undefined) setPrices((p) => [...p, next.clientRate as number]);
      setTarget('');
      setNote('');
      onChanged();
    } catch (err) {
      setQuoteError(errorText(err));
      fxDealApi.getQuote(quote.id).then(setQuote).catch(() => undefined);
    } finally {
      setCountering(false);
    }
  };

  const onAccept = async () => {
    if (!quote) return;
    setAccepting(true);
    setQuoteError(null);
    try {
      const deal = await fxDealApi.accept(quote.id);
      setBookedRef(deal.dealRef);
      setQuote(await fxDealApi.getQuote(quote.id));
      onChanged();
    } catch (err) {
      setQuoteError(errorText(err));
      fxDealApi.getQuote(quote.id).then(setQuote).catch(() => undefined);
    } finally {
      setAccepting(false);
    }
  };

  const onWithdraw = async () => {
    if (!quote) return;
    try {
      setQuote(await fxDealApi.cancel(quote.id));
      onChanged();
    } catch (err) {
      setQuoteError(errorText(err));
    }
  };

  // Closing with a price still firm withdraws it, so the RFQ log says "Withdrawn" and not "Expired".
  const close = () => {
    if (quoteRef.current?.status === 'QUOTED') fxDealApi.cancel(quoteRef.current.id).then(onChanged).catch(() => undefined);
    onClose();
  };

  const pairOptions = useMemo(
    () => (pairs.includes(pair) ? pairs : [...pairs, pair]).map((p) => ({ value: p, label: p })),
    [pairs, pair],
  );
  const base = pair.slice(0, 3);
  const quoteCcy = pair.slice(4);
  const buyLeg = quote ? (quote.side === 'BUY' ? [quote.baseCurrency, quote.amount] as const : [quote.quoteCurrency, quote.counterAmount ?? 0] as const) : null;
  const sellLeg = quote ? (quote.side === 'BUY' ? [quote.quoteCurrency, quote.counterAmount ?? 0] as const : [quote.baseCurrency, quote.amount] as const) : null;
  const firm = quote?.status === 'QUOTED';
  const roundsLeft = quote ? quote.maxRounds - quote.rounds : 0;

  return (
    <Drawer
      isOpen={isOpen}
      onClose={close}
      size="lg"
      title="New deal"
      subtitle={corporateName ? `Request a price from the desk for ${corporateName}` : 'Request a price from the desk'}
    >
      <div className="p-6 space-y-6">
        <section className="space-y-4">
          <div>
            <Select label="For entity" value={entityId} onChange={(e) => setEntityId(e.target.value)} disabled={firm}
              options={entities.map((e) => ({ value: e.id, label: e.entityName }))} placeholder={entities.length ? undefined : 'No entities'} />
            {room && (!room.usable || (room.entity && !room.entity.usable)) ? (
              <p className="text-body-sm text-warning-700 dark:text-warning-300 mt-1.5">{!room.usable ? room.reason : room.entity?.reason}.</p>
            ) : room?.entity ? (
              <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-1.5">
                FX sub-limit: {fmt(room.entity.headroom, 0)} of {fmt(room.entity.limit, 0)} {room.currency} left.
                <span className="block">Group FX line: {fmt(room.headroom, 0)} {room.currency} left.</span>
              </p>
            ) : null}
          </div>
          <Select label="Currency pair" value={pair} onChange={(e) => setPair(e.target.value)} options={pairOptions} disabled={firm} />
          <div>
            <span className="block body-strong mb-2">Direction</span>
            <div className="grid grid-cols-2 gap-2">
              <Button variant={side === 'BUY' ? 'primary' : 'outline'} onClick={() => setSide('BUY')} disabled={firm} aria-pressed={side === 'BUY'}>Buy {base}</Button>
              <Button variant={side === 'SELL' ? 'primary' : 'outline'} onClick={() => setSide('SELL')} disabled={firm} aria-pressed={side === 'SELL'}>Sell {base}</Button>
            </div>
            <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-1.5">
              You {side === 'BUY' ? 'buy' : 'sell'} {base} and {side === 'BUY' ? 'pay' : 'receive'} {quoteCcy}.
            </p>
            {legs && (noAccount
              ? <p className="text-body-sm text-warning-700 dark:text-warning-300 mt-1.5">{noAccount}.</p>
              : (
                <p className="text-caption text-neutral-500 dark:text-neutral-400 mt-1.5">
                  {legs.buy.currency} credited to {legs.buy.account?.label}.
                  <span className="block">{legs.sell.currency} debited from {legs.sell.account?.label}.</span>
                </p>
              ))}
          </div>
          <Input label={`Amount (${base})`} inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} disabled={firm} />
          <Select label="Instrument" value={instrument} onChange={(e) => setInstrument(e.target.value as Instrument)} options={INSTRUMENTS} disabled={firm} />
          {instrument === 'WINDOW' && (
            <Input label="First drawdown date" type="date" value={windowStart} onChange={(e) => setWindowStart(e.target.value)} disabled={firm} />
          )}
          {instrument !== 'SPOT' && (
            <Input label={instrument === 'WINDOW' ? 'Last drawdown date' : 'Value date'} type="date" value={valueDate} onChange={(e) => setValueDate(e.target.value)} disabled={firm} />
          )}
          <Input label="Purpose (optional)" value={purpose} onChange={(e) => setPurpose(e.target.value)} disabled={firm} />
          {exposureRef && <p className="text-caption text-neutral-500 dark:text-neutral-400">Hedging exposure {exposureRef.replace('|', ' ')}.</p>}
          {formError && <p className="text-body-sm text-error-700 dark:text-error-300">{formError}</p>}
          {streaming && stream?.buy && stream.sell && !bookedRef && (
            <div className="space-y-2">
              <div className="flex items-center justify-between gap-2">
                <span className="body-strong">Live price</span>
                <span className="text-caption text-neutral-500 dark:text-neutral-400">Click a side to deal. Simulated desk.</span>
              </div>
              <div className="grid grid-cols-2 gap-2">
                {(['SELL', 'BUY'] as Side[]).map((s) => {
                  const leg = (s === 'BUY' ? stream.buy : stream.sell) as StreamLeg;
                  return (
                    <button key={s} type="button" onClick={() => onStreamDeal(s)}
                      disabled={!!dealing || !!noAccount || (room !== null && (!room.usable || (room.entity !== undefined && !room.entity.usable)))}
                      className={cn('rounded-lg border p-3 text-left transition-colors focus-visible:outline focus-visible:outline-2 focus-visible:outline-primary-500 disabled:opacity-50',
                        s === side ? 'border-primary-500 bg-primary-50 dark:bg-primary-900/30' : 'border-edge hover:border-primary-400')}>
                      <span className="block text-caption text-neutral-500 dark:text-neutral-400">{s === 'BUY' ? 'You buy' : 'You sell'} {base}</span>
                      <span className="block font-mono tabular-nums text-stat-sm text-primary-900 dark:text-neutral-50">{fmtRate(leg.rate)}</span>
                      <span className="block text-caption font-mono tabular-nums text-neutral-500 dark:text-neutral-400">
                        {leg.spotRate !== undefined && leg.forwardPoints !== undefined
                          ? `${fmtRate(leg.spotRate)} ${leg.forwardPoints >= 0 ? '+' : ''}${leg.forwardPoints.toFixed(2)} pts`
                          : `${quoteCcy} ${fmt(leg.counterAmount)}`}
                      </span>
                      {dealing === s && <span className="block text-caption mt-1">Booking...</span>}
                    </button>
                  );
                })}
              </div>
            </div>
          )}
          {!streaming && stream?.reason && !quote && !bookedRef && (
            <p className="text-caption text-neutral-500 dark:text-neutral-400">{stream.reason}, so request a price below.</p>
          )}
          {!streaming && bookedRef && !quote && (
            <Alert variant="success" title="Deal booked">{bookedRef} is booked. Its confirmation is waiting for you in Deals.</Alert>
          )}
          {!firm && !bookedRef && !streaming && (
            <Button onClick={onRequest} loading={requesting}
              disabled={!corporateId || !entityId || !!noAccount || (room !== null && (!room.usable || (room.entity !== undefined && !room.entity.usable)))}
              leftIcon={<Send className="w-4 h-4" />} fullWidth>
              {quote ? 'Request a new price' : 'Request price'}
            </Button>
          )}
        </section>

        {quote && (
          <section className="space-y-4 border-t border-edge pt-5">
            <div className="flex items-center justify-between gap-2">
              <h3 className="text-body-sm font-semibold text-primary-900 dark:text-neutral-50">Price</h3>
              <Badge variant={QUOTE_BADGE[quote.status].variant}>{QUOTE_BADGE[quote.status].label}</Badge>
            </div>

            {firm && buyLeg && sellLeg && (
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
                {prices.length > 1 && (
                  <p className="text-caption text-neutral-500 dark:text-neutral-400 font-mono tabular-nums">
                    {prices.map(fmtRate).join(' → ')}
                  </p>
                )}
                <p className="text-body text-primary-900 dark:text-neutral-50">
                  You buy <strong>{buyLeg[0]} {fmt(buyLeg[1])}</strong> and sell <strong>{sellLeg[0]} {fmt(sellLeg[1])}</strong>.
                </p>
                <dl className="grid grid-cols-2 gap-3 text-body-sm">
                  <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Value date</dt>
                    <dd className="font-mono tabular-nums">{quote.windowStart ? `${quote.windowStart} to ${quote.valueDate}` : quote.valueDate}</dd></div>
                  {quote.spotRate !== undefined && quote.forwardPoints !== undefined && (<>
                    <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Spot rate</dt>
                      <dd className="font-mono tabular-nums">{fmtRate(quote.spotRate)}</dd></div>
                    <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">Forward points</dt>
                      <dd className="font-mono tabular-nums">{quote.forwardPoints > 0 ? '+' : ''}{quote.forwardPoints.toFixed(2)}</dd></div>
                  </>)}
                  <div><dt className="text-caption text-neutral-500 dark:text-neutral-400">FX credit limit used</dt>
                    <dd className="font-mono tabular-nums">{fmt(quote.limitCharge ?? 0, 0)} {quote.limitCurrency}</dd></div>
                </dl>
                <p className="text-caption text-neutral-500 dark:text-neutral-400">Simulated desk and rates.</p>

                {quote.maxRounds > 0 && (
                  <div className="rounded-lg border border-edge-subtle bg-surface-muted p-3 space-y-3">
                    <div className="flex items-center justify-between gap-2">
                      <span className="body-strong inline-flex items-center gap-1.5"><MessageSquareQuote className="w-4 h-4" /> Ask for a better price</span>
                      <span className="text-caption text-neutral-500 dark:text-neutral-400">Round {quote.rounds} of {quote.maxRounds}</span>
                    </div>
                    {quote.bestPrice && (
                      <p className="text-body-sm text-warning-700 dark:text-warning-300">The desk says this is its best price. Accept it or withdraw.</p>
                    )}
                    {roundsLeft > 0 ? (
                      <>
                        <div className="grid gap-2 sm:grid-cols-2">
                          <Input label="Rate you want (optional)" inputMode="decimal" value={target} onChange={(e) => setTarget(e.target.value)}
                            hint={quote.side === 'BUY' ? 'Lower than the price above' : 'Higher than the price above'} />
                          <Input label="Note to the desk (optional)" value={note} onChange={(e) => setNote(e.target.value)} />
                        </div>
                        <Button size="sm" variant="outline" onClick={onCounter} loading={countering} disabled={accepting}>
                          {target.trim() ? 'Ask for this rate' : 'Ask for the best price'}
                        </Button>
                      </>
                    ) : (
                      <p className="text-caption text-neutral-500 dark:text-neutral-400">No more rounds on this request.</p>
                    )}
                  </div>
                )}

                {quoteError && <p className="text-body-sm text-error-700 dark:text-error-300">{quoteError}</p>}
                <div className="flex gap-2">
                  <Button onClick={onAccept} loading={accepting} disabled={countering} leftIcon={<Check className="w-4 h-4" />}>Accept price</Button>
                  <Button variant="outline" onClick={onWithdraw} disabled={accepting || countering} leftIcon={<X className="w-4 h-4" />}>Withdraw</Button>
                </div>
              </div>
            )}

            {quote.status === 'ACCEPTED' && (
              <Alert variant="success" title="Deal booked">
                {bookedRef ?? 'Your deal'} is booked. Its confirmation is waiting for you in Deals.
              </Alert>
            )}
            {['REJECTED', 'EXPIRED', 'CANCELLED'].includes(quote.status) && (
              <Alert variant={quote.status === 'REJECTED' ? 'error' : 'warning'} title={QUOTE_BADGE[quote.status].label}>
                {quote.statusReason ?? (quote.status === 'CANCELLED' ? 'You withdrew this request.' : 'This price is no longer available.')} Request a new price to continue.
              </Alert>
            )}
            {quoteError && !firm && <p className="text-body-sm text-error-700 dark:text-error-300">{quoteError}</p>}
          </section>
        )}
      </div>
    </Drawer>
  );
};

export default NewDealDrawer;
