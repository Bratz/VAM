import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

// Badge variant type
type BadgeVariant = 'success' | 'warning' | 'error' | 'info' | 'neutral';

// Utility for merging Tailwind classes
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

// ----------------------------------------------------------------------------
// Active market profile — set once at app boot from /api/v1/config/market-profile.
// All format* helpers below fall back to these when the caller does not pass a
// currency/locale. Keeps existing call sites untouched while making the default
// configurable per market (UAE / KSA / UK / EU / US / SG).
// ----------------------------------------------------------------------------
type ActiveMarket = { currency: string; locale: string };
const activeMarket: ActiveMarket = { currency: 'AED', locale: 'en-AE' };

export function setActiveMarket(next: { currency?: string | null; locale?: string | null }) {
  if (next.currency) activeMarket.currency = next.currency;
  if (next.locale)   activeMarket.locale   = next.locale;
}

export function getActiveMarket(): ActiveMarket {
  return { ...activeMarket };
}

// Format currency — deterministic Anglo format (e.g. "AED 10,000,000.00").
//
// We deliberately bypass {@code Intl.NumberFormat(activeLocale)} because some
// locales (notably en-AE / en-GB-cy / Arabic numerals) render the same amount
// with European separators ("10.000.000 AED" with period as thousands mark)
// or with the currency suffix instead of prefix. The Aperture UI prioritises
// predictability — every page reads "AED 10,000,000.00" regardless of who the
// active market is. The `locale` parameter is preserved for backward
// compatibility but ignored.
//
// Phase 12 Task D3 (2026-06-12): decimals are now CURRENCY-AWARE, derived
// from the ISO 4217 minor-unit data baked into Intl (JPY → 0 dp,
// BHD/KWD/OMR/JOD/TND → 3 dp, everything else → 2 dp). This replaced the
// previous hardcoded 0-dp behaviour AND the 7 per-page formatCurrency
// clones (each with its own decimal policy) that this shared util absorbed.
// Display change is deliberate — note JPY/BHD in any release notes.

/** ISO-4217 fraction digits per currency, derived from Intl and cached. */
const currencyDecimalsCache = new Map<string, number>();
function decimalsFor(currency: string): number {
  let dp = currencyDecimalsCache.get(currency);
  if (dp === undefined) {
    try {
      dp = new Intl.NumberFormat('en-US', { style: 'currency', currency })
        .resolvedOptions().maximumFractionDigits ?? 2;
    } catch {
      dp = 2; // Unknown/garbage code — safest default.
    }
    currencyDecimalsCache.set(currency, dp);
  }
  return dp;
}

export function formatCurrency(
  amount: number,
  currency: string = activeMarket.currency,
  _locale: string = activeMarket.locale
): string {
  // Hardcoded en-US grouping/separator pattern → never produces European-style
  // periods-as-thousands. Fraction digits follow the currency's minor unit.
  const dp = decimalsFor(currency);
  const formatted = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: dp,
    maximumFractionDigits: dp,
    useGrouping: true,
  }).format(amount);
  return `${currency} ${formatted}`;
}

// formatCompactCurrency/formatCurrencyAuto/formatCompactAmount used to
// abbreviate (K/M/B) — the product rule is now full precision everywhere,
// no abbreviated notation anywhere. Rather than migrate the ~17 files that
// call these (a much larger blast radius than the density-pass's own
// direct callers), kept as full-precision wrappers: same signatures, zero
// call-site churn, abbreviation genuinely gone. New code should reach for
// <Amount /> (components/Amount.tsx) instead of these directly.
export function formatCompactCurrency(
  amount: number,
  currency: string = activeMarket.currency
): string {
  return formatCurrency(amount, currency);
}

export function formatCurrencyAuto(
  amount: number | null | undefined,
  currency: string = activeMarket.currency,
  _options?: { compact?: boolean; threshold?: number }
): string {
  return formatCurrency(amount ?? 0, currency);
}

export function formatCompactAmount(amount: number): string {
  return new Intl.NumberFormat('en-US', { maximumFractionDigits: 2 }).format(amount);
}

// Format date
export function formatDate(
  date: string | Date,
  options: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }
): string {
  return new Date(date).toLocaleDateString(activeMarket.locale, options);
}

// Format date and time
export function formatDateTime(date: string | Date): string {
  return new Date(date).toLocaleString(activeMarket.locale, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

// Format relative time (e.g., "2 hours ago")
export function formatRelativeTime(date: string | Date): string {
  const now = new Date();
  const then = new Date(date);
  const diff = now.getTime() - then.getTime();

  const minutes = Math.floor(diff / 60000);
  const hours = Math.floor(diff / 3600000);
  const days = Math.floor(diff / 86400000);

  if (minutes < 1) return 'Just now';
  if (minutes < 60) return `${minutes}m ago`;
  if (hours < 24) return `${hours}h ago`;
  if (days < 7) return `${days}d ago`;
  return formatDate(date);
}

// Format IBAN for display
export function formatIban(iban: string): string {
  return iban.replace(/(.{4})/g, '$1 ').trim();
}

// Get status variant for Badge component
export function getStatusVariant(
  status: string
): BadgeVariant {
  const statusMap: Record<string, BadgeVariant> = {
    ACTIVE: 'success',
    COMPLETED: 'success',
    APPROVED: 'success',
    VERIFIED: 'success',
    PENDING: 'warning',
    PROCESSING: 'warning',
    IN_PROGRESS: 'warning',
    PENDING_FUNDING: 'warning',
    ON_HOLD: 'warning',
    SUSPENDED: 'warning',
    FAILED: 'error',
    REJECTED: 'error',
    CANCELLED: 'error',
    DISPUTED: 'error',
    BLOCKED: 'error',
    EXPIRED: 'neutral',
    CLOSED: 'neutral',
    DRAFT: 'info',
    FUNDED: 'info',
  };
  return statusMap[status] || 'neutral';
}

// Copy to clipboard
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

// Debounce function
export function debounce<T extends (...args: unknown[]) => unknown>(
  func: T,
  wait: number
): (...args: Parameters<T>) => void {
  let timeout: ReturnType<typeof setTimeout> | null = null;
  return (...args: Parameters<T>) => {
    if (timeout) clearTimeout(timeout);
    timeout = setTimeout(() => func(...args), wait);
  };
}

// Validate email
export function isValidEmail(email: string): boolean {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email);
}

// Validate phone number (UAE format)
export function isValidPhone(phone: string): boolean {
  const phoneRegex = /^\+971[0-9]{9}$/;
  return phoneRegex.test(phone.replace(/\s/g, ''));
}

// Mask sensitive data
export function maskString(str: string, visibleChars: number = 4): string {
  if (str.length <= visibleChars) return str;
  return '****' + str.slice(-visibleChars);
}

// Generate random reference
export function generateReference(prefix: string = 'REF'): string {
  return `${prefix}-${Date.now().toString(36).toUpperCase()}`;
}

// Format file size
export function formatFileSize(bytes: number): string {
  if (bytes === 0) return '0 Bytes';
  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
}

// Parse query params
export function parseQueryParams(search: string): Record<string, string> {
  const params = new URLSearchParams(search);
  const result: Record<string, string> = {};
  params.forEach((value, key) => {
    result[key] = value;
  });
  return result;
}

// Build query string
export function buildQueryString(params: Record<string, any>): string {
  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      searchParams.append(key, String(value));
    }
  });
  return searchParams.toString();
}

// Sleep utility for async operations
export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// Truncate text
export function truncate(str: string, length: number): string {
  if (str.length <= length) return str;
  return str.slice(0, length) + '...';
}

// Calculate percentage
export function calculatePercentage(value: number, total: number): number {
  if (total === 0) return 0;
  return Math.round((value / total) * 100);
}

// Check if object is empty
export function isEmpty(obj: Record<string, any>): boolean {
  return Object.keys(obj).length === 0;
}

// Deep clone object
export function deepClone<T>(obj: T): T {
  return JSON.parse(JSON.stringify(obj));
}

// Group array by key
export function groupBy<T>(array: T[], key: keyof T): Record<string, T[]> {
  return array.reduce((result, item) => {
    const group = String(item[key]);
    if (!result[group]) result[group] = [];
    result[group].push(item);
    return result;
  }, {} as Record<string, T[]>);
}

// Sort array by key
export function sortBy<T>(
  array: T[],
  key: keyof T,
  order: 'asc' | 'desc' = 'asc'
): T[] {
  return [...array].sort((a, b) => {
    const aVal = a[key];
    const bVal = b[key];
    if (aVal < bVal) return order === 'asc' ? -1 : 1;
    if (aVal > bVal) return order === 'asc' ? 1 : -1;
    return 0;
  });
}

// ============================================================================
// FX rate display helpers
// ============================================================================

/**
 * Smart rate precision: matches market convention.
 *  - Crypto / micro-rates (rate < 0.01): 6 decimals
 *  - JPY pairs (any side): 4 decimals (kept as an explicit branch so the
 *    convention can be tuned per-pair later without restructuring callers)
 *  - Otherwise (major pairs etc.): 4 decimals
 *
 * Cross-rate computations may use 6+ decimals internally; this is for
 * display only.
 */
export function formatFxRate(rate: number, from: string, to: string): string {
  if (rate < 0.01) return rate.toFixed(6);
  if (from === 'JPY' || to === 'JPY') return rate.toFixed(4);
  return rate.toFixed(4);
}

/**
 * Compact "age" formatter for an elapsed-millisecond delta (now - timestamp).
 * Distinct from `formatRelativeTime(date)` which takes a date/string; this
 * takes a pre-computed ms delta so callers that already derived `_ageMs`
 * (e.g. the FX rates freshness model) don't re-parse timestamps.
 */
export function relativeTime(ms: number): string {
  if (ms < 60_000) return 'just now';
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m ago`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h ago`;
  if (ms < 172_800_000) return 'yesterday';
  return `${Math.floor(ms / 86_400_000)}d ago`;
}