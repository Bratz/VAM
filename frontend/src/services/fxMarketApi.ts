// Indicative interbank market data for the corporate FX workspace (simulated, mid rates only).
// Backend: FxMarketController at /api/v1/fx/market. Uses the {success,data} envelope.

import { apiClient, ApiResponse } from './api';

export interface SpotTick {
  pair: string;
  mid: number;
  changeBps: number;
  asOf: string;
  source: string;
}

export interface ForwardQuote {
  pair: string;
  tenor?: string | null;
  spotDate: string;
  valueDate: string;
  days: number;
  spotMid: number;
  forwardPoints: number;
  forwardMid: number;
  baseRatePct: number;
  quoteRatePct: number;
  source: string;
}

/** The corporate's own spot price for a pair: it buys the base currency at {@code buy}, sells it at {@code sell}. Never the mid. */
export interface BoardRow {
  pair: string;
  buy: number;
  sell: number;
  changeBps: number;
  valueDate: string;
  asOf: string;
  /** Where the market rate came from: 'SIMULATED' from the mock market. */
  source: string;
}

/** One forward tenor: the outright rate each way, and the client points from the client spot. */
export interface CurveRow {
  pair: string;
  tenor?: string | null;
  valueDate: string;
  days: number;
  buy: number;
  buyPoints?: number;
  sell: number;
  sellPoints?: number;
  source: string;
}

export const fxMarketApi = {
  /** Market Rates: the corporate's two-way prices at an amount of the base currency (FxBoardController). */
  board: (amount: number) =>
    apiClient.get<ApiResponse<BoardRow[]>>('/fx/board', { params: { amount } }).then(r => r.data.data),

  boardCurve: (pair: string, amount: number) =>
    apiClient.get<ApiResponse<CurveRow[]>>('/fx/board/curve', { params: { pair, amount } }).then(r => r.data.data),

  boardForward: (pair: string, amount: number, valueDate: string) =>
    apiClient.get<ApiResponse<CurveRow>>('/fx/board/forward', { params: { pair, amount, valueDate } }).then(r => r.data.data),

  spots: () =>
    apiClient.get<ApiResponse<SpotTick[]>>('/fx/market/spots').then(r => r.data.data),

  curve: (pair: string) =>
    apiClient.get<ApiResponse<ForwardQuote[]>>('/fx/market/curve', { params: { pair } }).then(r => r.data.data),

  forward: (pair: string, valueDate: string) =>
    apiClient.get<ApiResponse<ForwardQuote>>('/fx/market/forward', { params: { pair, valueDate } }).then(r => r.data.data),
};

// ---------------------------------------------------------------------------
// Exposure ladder, hedge-policy breaches and declared exposures/hedges.
// Backend: FxExposureController at /api/v1/fx/exposure. Amounts are native to
// their currency and are never summed across currencies.
// ---------------------------------------------------------------------------

export type HedgeStatus = 'WITHIN_POLICY' | 'UNDER_HEDGED' | 'OVER_HEDGED' | 'NO_POLICY' | 'NOT_POLICED' | 'NONE';
export type ExposureBucketId = 'ON_HAND' | 'D0_30' | 'D31_60' | 'D61_90' | 'D91_180' | 'D181_PLUS';

export interface ExposureBucket {
  bucket: ExposureBucketId;
  label: string;
  receivables: number;
  payables: number;
  /** Cash Forecast flows no invoice carries (payroll, rent, tax, manual), signed; counted in full. */
  forecast: number;
  balances: number;
  declared: number;
  /** receivables - payables + balances + declared; positive = long the currency. */
  exposure: number;
  /** The hedge deals' own position: selling forward is negative. */
  hedged: number;
  open: number;
  hedgeRatio?: number | null;
  minRatio?: number | null;
  maxRatio?: number | null;
  status: HedgeStatus;
  /** Hedge notional to add (signed like `hedged`) to reach the nearest band edge. */
  adjustToBand?: number | null;
}

export interface ExposureCurrencyRow {
  currency: string;
  /** Stored platform rate to the base currency; absent when none exists (the API omits nulls). */
  rateToBase?: number | null;
  buckets: ExposureBucket[];
}

export interface ExposureLadder {
  corporateId: string;
  corporateName: string;
  baseCurrency: string;
  asOf: string;
  currencies: ExposureCurrencyRow[];
  /** When the Cash Forecast run that fed the Forecast column ran; absent when the corporate has none. */
  forecastRunAt?: string;
  forecastStale: boolean;
  /** The hedge policy is the application's default bands, not one the corporate set. */
  policyDefault?: boolean;
}

export interface PolicyBreach {
  corporateId: string;
  corporateName: string;
  baseCurrency: string;
  currency: string;
  bucket: ExposureBucketId;
  bucketLabel: string;
  horizonDays: number;
  exposure: number;
  hedged: number;
  hedgeRatio?: number | null;
  minRatio?: number | null;
  maxRatio?: number | null;
  status: HedgeStatus;
  adjustToBand?: number | null;
  rateToBase?: number | null;
  severity: 'high' | 'medium';
}

export interface ExposureEntry {
  id: string;
  kind: 'EXPOSURE' | 'HEDGE';
  currency: string;
  amount: number;
  valueDate: string;
  reference?: string | null;
  description?: string | null;
  source: string;
}

/** What an item behind a bucket is. */
export type ExposureItemKind = 'RECEIVABLE' | 'PAYABLE' | 'FORECAST' | 'CASH' | 'DECLARED' | 'HEDGE';

/**
 * One item behind a bucket, signed as it counts (receivables, cash, inflows positive; payables, outflows negative).
 * reference: invoice / account / deal number or forecast category; counterparty: customer, vendor or bank (forecast:
 * its source engine; hedge: where it came from).
 */
export interface ExposureItem {
  kind: ExposureItemKind;
  id?: string | null;
  reference?: string | null;
  counterparty?: string | null;
  date?: string | null;
  amount: number;
}

/** The items behind one currency in one bucket; exposure and hedged are what they add up to (as on the ladder). */
export interface ExposureBucketItems {
  currency: string;
  bucket: ExposureBucketId;
  label: string;
  exposure: number;
  hedged: number;
  items: ExposureItem[];
}

export const fxExposureApi = {
  ladder: (corporateId: string) =>
    apiClient.get<ApiResponse<ExposureLadder>>('/fx/exposure', { params: { corporateId } }).then(r => r.data.data),

  /** The invoices, forecast lines, cash, entries and hedges behind one bucket of the ladder. */
  items: (corporateId: string, currency: string, bucket: ExposureBucketId) =>
    apiClient.get<ApiResponse<ExposureBucketItems>>('/fx/exposure/items', { params: { corporateId, currency, bucket } })
      .then(r => r.data.data),

  /** Omit corporateId for every corporate (what the cockpit reads). */
  breaches: (corporateId?: string) =>
    apiClient.get<ApiResponse<PolicyBreach[]>>('/fx/exposure/breaches', { params: corporateId ? { corporateId } : {} })
      .then(r => r.data.data),

  entries: (corporateId: string) =>
    apiClient.get<ApiResponse<ExposureEntry[]>>('/fx/exposure/entries', { params: { corporateId } }).then(r => r.data.data),

  upload: (corporateId: string, file: File, replace: boolean) => {
    const form = new FormData();
    form.append('file', file);
    return apiClient
      .post<ApiResponse<{ imported: number; replaced: number }>>('/fx/exposure/entries/upload', form, {
        params: { corporateId, replace },
        headers: { 'Content-Type': 'multipart/form-data' },
      })
      .then(r => r.data.data);
  },
};
