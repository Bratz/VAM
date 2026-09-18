import { useEffect, useState } from 'react';
import { fxRateApi } from '../../services/api';

// ============================================================================
// Multi-Bank Liquidity — shared reporting-currency rate map.
//
// One rate per unique currency code (fxRateApi.convert(1, code, reportingCurrency))
// instead of a convert() call per figure — every consolidated/converted number
// on the page (Overview's hero, the value-weighted bank distribution, the
// By Country map's bubble sizes) multiplies locally against this map rather
// than each re-fetching its own conversions. A code equal to `reportingCurrency`
// short-circuits to rate 1 with no network call.
// ============================================================================

export interface ReportingRates {
  rates: Map<string, number>;
  excluded: string[];
  loading: boolean;
}

export function useReportingRates(currencyCodes: string[], reportingCurrency: string, enabled = true): ReportingRates {
  const [state, setState] = useState<ReportingRates>({ rates: new Map(), excluded: [], loading: false });

  useEffect(() => {
    if (!enabled || currencyCodes.length === 0) return;
    let alive = true;
    setState((prev) => ({ ...prev, loading: true }));
    Promise.allSettled(
      currencyCodes.map((code) =>
        code === reportingCurrency
          ? Promise.resolve({ code, rate: 1 })
          : fxRateApi.convert(1, code, reportingCurrency).then((res) => {
              if (!res.success || !res.data) throw new Error(`No rate ${code} → ${reportingCurrency}`);
              return { code, rate: res.data.convertedAmount };
            })
      )
    ).then((results) => {
      if (!alive) return;
      const rates = new Map<string, number>();
      const excluded: string[] = [];
      results.forEach((r, i) => {
        if (r.status === 'fulfilled') rates.set(r.value.code, r.value.rate);
        else excluded.push(currencyCodes[i]);
      });
      setState({ rates, excluded, loading: false });
    });
    return () => { alive = false; };
    // currencyCodes is expected to be a stable reference across renders where
    // the underlying set hasn't changed (callers memoize it), same contract
    // as the effect this was extracted from.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currencyCodes, reportingCurrency, enabled]);

  return state;
}
