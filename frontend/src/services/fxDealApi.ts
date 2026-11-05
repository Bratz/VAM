// Quote requests, deals and the FX credit limit for the corporate FX workspace.
// Backend: FxDealController at /api/v1/fx. Uses the {success,data} envelope, and the API omits
// null fields, so optional values are typed optional.

import { apiClient, ApiResponse } from './api';

export type Side = 'BUY' | 'SELL';
export type Instrument = 'SPOT' | 'FORWARD' | 'WINDOW';
export type QuoteStatus = 'REQUESTED' | 'QUOTED' | 'ACCEPTED' | 'EXPIRED' | 'REJECTED' | 'CANCELLED';
export type DealStatus = 'BOOKED' | 'CONFIRMED' | 'ACTIVE' | 'PENDING_SETTLEMENT';

export interface QuoteBody {
  corporateId: string;
  pair: string;
  side: Side;
  instrument: Instrument;
  /** In the pair's base currency. */
  amount: number;
  /** The contracted date, or the last drawdown date of a window. */
  valueDate?: string;
  windowStart?: string;
  purpose?: string;
  /** The exposure being hedged, as "CCY|BUCKET". */
  exposureRef?: string;
}

export interface Quote {
  id: string;
  corporateId: string;
  pair: string;
  baseCurrency: string;
  quoteCurrency: string;
  side: Side;
  instrument: Instrument;
  amount: number;
  valueDate: string;
  windowStart?: string;
  purpose?: string;
  exposureRef?: string;
  status: QuoteStatus;
  statusReason?: string;
  midRate?: number;
  forwardPoints?: number;
  spreadBps?: number;
  clientRate?: number;
  counterAmount?: number;
  dealer?: string;
  quotedAt?: string;
  validUntil?: string;
  /** Seconds the price stays firm, from the server clock. */
  secondsRemaining: number;
  limitCharge?: number;
  limitCurrency?: string;
  dealId?: string;
}

export interface Deal {
  id: string;
  dealRef: string;
  corporateId: string;
  instrument: Instrument;
  pair: string;
  side: Side;
  buyCurrency: string;
  buyAmount: number;
  sellCurrency: string;
  sellAmount: number;
  rate: number;
  midRate?: number;
  forwardPoints?: number;
  spreadBps?: number;
  tradeDate: string;
  valueDate: string;
  windowStart?: string;
  status: DealStatus;
  limitCharge: number;
  limitCurrency: string;
  purpose?: string;
  exposureRef?: string;
  createdAt: string;
}

export interface DealEvent {
  action: string;
  actor?: string;
  details?: string;
  timestamp: string;
}

export interface CreditLimit {
  corporateId: string;
  currency: string;
  limit: number;
  used: number;
  headroom: number;
}

export const fxDealApi = {
  requestQuote: (body: QuoteBody) =>
    apiClient.post<ApiResponse<Quote>>('/fx/quotes', body).then(r => r.data.data),

  getQuote: (id: string) =>
    apiClient.get<ApiResponse<Quote>>(`/fx/quotes/${id}`).then(r => r.data.data),

  /** Accepting a quote twice returns the same deal. */
  accept: (id: string) =>
    apiClient.post<ApiResponse<Deal>>(`/fx/quotes/${id}/accept`).then(r => r.data.data),

  cancel: (id: string) =>
    apiClient.post<ApiResponse<Quote>>(`/fx/quotes/${id}/cancel`).then(r => r.data.data),

  deals: (corporateId: string) =>
    apiClient.get<ApiResponse<Deal[]>>('/fx/deals', { params: { corporateId } }).then(r => r.data.data),

  events: (dealId: string) =>
    apiClient.get<ApiResponse<DealEvent[]>>(`/fx/deals/${dealId}/events`).then(r => r.data.data),

  confirmation: (dealId: string) =>
    apiClient.get<ApiResponse<{ dealRef: string; text: string }>>(`/fx/deals/${dealId}/confirmation`).then(r => r.data.data),

  limit: (corporateId: string) =>
    apiClient.get<ApiResponse<CreditLimit>>('/fx/limits', { params: { corporateId } }).then(r => r.data.data),
};
