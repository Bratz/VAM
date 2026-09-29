import { createContext, useContext } from 'react';
import { MarketProfile } from '../services/api';

/**
 * Context object, fallback profile + consumer hooks for the active market
 * profile. The provider component lives in `MarketContext.tsx`; these live here
 * so that file can export a component alone and keep Fast Refresh working.
 *
 * If the backend call fails we fall back to UAE defaults so the UI never blocks
 * on the config endpoint.
 */

export const FALLBACK_PROFILE: MarketProfile = {
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

export interface MarketContextValue {
  profile: MarketProfile;
  loaded: boolean;
}

export const MarketContext = createContext<MarketContextValue>({
  profile: FALLBACK_PROFILE,
  loaded: false,
});

export function useMarket(): MarketContextValue {
  return useContext(MarketContext);
}

/** Shortcut for the common case — defaultCurrency from the active profile. */
export function useDefaultCurrency(): string {
  return useContext(MarketContext).profile.defaultCurrency;
}
