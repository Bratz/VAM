import { useEffect, useState } from 'react';
import { fxDealApi, type IndicativeFx } from '../services/fxDealApi';

/**
 * What `amount` of `currency` comes to on treasury's account at today's price (ihb-fx R4, R10): Create Payable /
 * Create Invoice of a cross-currency enrolment. Null while there is nothing to ask (no account, same currency, no
 * amount) or while asking. `incoming`: collected into the account (COBO), else paid from it (POBO).
 */
export function useIndicativeFx(accountId: string | undefined, currency: string, amount: number,
                                settleCurrency: string | null | undefined, incoming = false): IndicativeFx | null {
  const [fx, setFx] = useState<IndicativeFx | null>(null);
  const ask = !!accountId && !!settleCurrency && settleCurrency !== currency && amount > 0;
  useEffect(() => {
    setFx(null);
    if (!ask) return;
    let live = true;
    // ponytail: a short debounce while the amount is typed; the price moves anyway until payment
    const t = setTimeout(() => {
      fxDealApi.indicative(accountId!, currency, amount, incoming)
        .then(r => { if (live) setFx(r ?? null); })
        .catch(() => { if (live) setFx(null); });
    }, 400);
    return () => { live = false; clearTimeout(t); };
  }, [ask, accountId, currency, amount, incoming]);
  return fx;
}
