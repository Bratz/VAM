import React, { createContext, useContext, useEffect, useState } from 'react';
import { setActiveMarket } from '../utils';
import { marketProfileApi, MarketProfile } from '../services/api';

/**
 * MarketContext — exposes the active deployment's market profile to the React tree.
 *
 * Fetches `/api/v1/config/market-profile` once at boot and sets the global
 * fallback currency/locale used by `formatCurrency`, `formatDate`, etc. The
 * full profile (timezone, base rate, weekend, home bank, suggested currencies)
 * is exposed via `useMarket()` for components that need it directly.
 *
 * If the backend call fails we fall back to UAE defaults so the UI never blocks
 * on the config endpoint.
 */

const FALLBACK_PROFILE: MarketProfile = {
  code: 'UAE',
  displayName: 'United Arab Emirates',
  defaultCurrency: 'AED',
  defaultCountryCode: 'AE',
  defaultLocale: 'en-AE',
  defaultTimezone: 'Asia/Dubai',
  defaultBaseRateType: 'EIBOR',
  weekend: 'SAT_SUN',
  homeBankBic: 'EABORAEAD',
  homeBankName: 'Emirates NBD',
  ibanCountryCode: 'AE',
  suggestedCurrencies: ['AED', 'USD', 'EUR', 'GBP', 'SAR', 'SGD'],
};

interface MarketContextValue {
  profile: MarketProfile;
  loaded: boolean;
}

const MarketContext = createContext<MarketContextValue>({
  profile: FALLBACK_PROFILE,
  loaded: false,
});

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

export function useMarket(): MarketContextValue {
  return useContext(MarketContext);
}

/** Shortcut for the common case — defaultCurrency from the active profile. */
export function useDefaultCurrency(): string {
  return useContext(MarketContext).profile.defaultCurrency;
}
