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
  tenor: string | null;
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

export const fxMarketApi = {
  spots: () =>
    apiClient.get<ApiResponse<SpotTick[]>>('/fx/market/spots').then(r => r.data.data),

  curve: (pair: string) =>
    apiClient.get<ApiResponse<ForwardQuote[]>>('/fx/market/curve', { params: { pair } }).then(r => r.data.data),

  forward: (pair: string, valueDate: string) =>
    apiClient.get<ApiResponse<ForwardQuote>>('/fx/market/forward', { params: { pair, valueDate } }).then(r => r.data.data),
};
