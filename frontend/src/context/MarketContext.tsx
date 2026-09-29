import React, { useEffect, useState } from 'react';
import { setActiveMarket } from '../utils';
import { marketProfileApi, MarketProfile } from '../services/api';
import { MarketContext, FALLBACK_PROFILE } from './useMarket';

/**
 * MarketProvider — exposes the active deployment's market profile to the React tree.
 *
 * Fetches `/api/v1/config/market-profile` once at boot and sets the global
 * fallback currency/locale used by `formatCurrency`, `formatDate`, etc. The
 * full profile (timezone, base rate, weekend, home bank, suggested currencies)
 * is exposed via `useMarket()` for components that need it directly.
 *
 * The context object, the fallback profile and the consumer hooks live in
 * `./useMarket`.
 */

export const MarketProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [profile, setProfile] = useState<MarketProfile>(FALLBACK_PROFILE);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    marketProfileApi.get()
      .then((res) => {
        if (cancelled) return;
        if (res.success && res.data) {
          setProfile(res.data);
          setActiveMarket({ currency: res.data.defaultCurrency, locale: res.data.defaultLocale });
        }
      })
      .catch(() => { /* keep fallback */ })
      .finally(() => { if (!cancelled) setLoaded(true); });
    return () => { cancelled = true; };
  }, []);

  return (
    <MarketContext.Provider value={{ profile, loaded }}>
      {children}
    </MarketContext.Provider>
  );
};
