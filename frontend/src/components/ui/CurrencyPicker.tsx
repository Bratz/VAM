import React, { useMemo } from 'react';
import { useMarket } from '../../context/MarketContext';
import { cn } from '../../utils';

/**
 * Reusable currency dropdown. Options are sourced from the active
 * {@link MarketContext} profile's {@code suggestedCurrencies}, with the active
 * default currency listed first.
 *
 * Replaces 20+ hardcoded `<select><option value="AED">AED</option>...</select>`
 * blocks across the app. To extend the choices for a market, edit the bundled
 * profile in {@code MarketProfile.java} (backend); the frontend picks the new
 * list up at app boot via /api/v1/config/market-profile.
 *
 * Usage:
 *   <CurrencyPicker value={form.currency} onChange={c => setForm({...form, currency: c})} />
 *
 * Pass `extra` to append currencies that aren't in the profile's suggested list
 * (e.g. a historical record's currency that should remain selectable).
 */
export interface CurrencyPickerProps {
  value: string;
  onChange: (currency: string) => void;
  /** Optional currencies to include beyond the profile's suggested list. */
  extra?: string[];
  /** Render currency with its long name (e.g. "AED — UAE Dirham"). Default false. */
  withName?: boolean;
  /** Show an empty-value option at the top (for filter use cases). */
  allowEmpty?: boolean;
  /** Label for the empty option. Default "All currencies". */
  emptyLabel?: string;
  className?: string;
  disabled?: boolean;
  id?: string;
  name?: string;
}

const CURRENCY_NAMES: Record<string, string> = {
  AED: 'UAE Dirham',
  SAR: 'Saudi Riyal',
  GBP: 'Pound Sterling',
  EUR: 'Euro',
  USD: 'US Dollar',
  SGD: 'Singapore Dollar',
  CHF: 'Swiss Franc',
  JPY: 'Japanese Yen',
  CAD: 'Canadian Dollar',
  AUD: 'Australian Dollar',
  SEK: 'Swedish Krona',
};

export const CurrencyPicker: React.FC<CurrencyPickerProps> = ({
  value, onChange, extra, withName = false, allowEmpty = false, emptyLabel = 'All currencies',
  className, disabled, id, name,
}) => {
  const { profile } = useMarket();

  const options = useMemo(() => {
    const set = new Set<string>(profile.suggestedCurrencies);
    if (extra) extra.forEach(c => c && set.add(c));
    // Make sure the current value remains selectable even if it's outside the suggested list.
    if (value && !set.has(value)) set.add(value);
    // Active default first; preserve order of the rest.
    const ordered: string[] = [];
    if (profile.defaultCurrency && set.has(profile.defaultCurrency)) {
      ordered.push(profile.defaultCurrency);
      set.delete(profile.defaultCurrency);
    }
    profile.suggestedCurrencies.forEach(c => { if (set.has(c)) { ordered.push(c); set.delete(c); } });
    set.forEach(c => ordered.push(c));
    return ordered;
  }, [profile, extra, value]);

  return (
    <select
      id={id}
      name={name}
      value={value}
      onChange={(e) => onChange(e.target.value)}
      disabled={disabled}
      className={cn(
        'w-full px-3 py-2 border border-neutral-300 rounded-lg focus:outline-none focus:ring-2 focus:ring-primary-500 disabled:opacity-50 dark:border-primary-700',
        className
      )}
    >
      {allowEmpty && <option value="">{emptyLabel}</option>}
      {options.map(code => (
        <option key={code} value={code}>
          {withName && CURRENCY_NAMES[code] ? `${code} — ${CURRENCY_NAMES[code]}` : code}
        </option>
      ))}
    </select>
  );
};

export default CurrencyPicker;
