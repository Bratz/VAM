import { apiClient } from './api';

// ============================================================================
// uc05 ROUTE OPTIONS
// ============================================================================
// The types below are uc05's own, copied from its web/src/components/Quote.tsx
// rather than re-derived. The backend serialises the quote in uc05's snake_case
// field names for exactly this reason: one vocabulary across both services, so
// a field that changes shape there fails to compile here instead of quietly
// reading undefined.

/** uc05 route families, in the order it ranks them. */
export type RouteKind =
  | 'rtgs'
  | 'instant'
  | 'batch'
  | 'correspondent'
  | 'operator'
  | 'card_push'
  | 'token'
  | 'stablecoin';

/** How a route family reads to a person. */
export const ROUTE_KIND: Record<RouteKind, string> = {
  rtgs: 'Real-time gross settlement',
  instant: 'Instant payment',
  batch: 'Batch clearing',
  correspondent: 'Correspondent bank',
  operator: 'Payment operator',
  card_push: 'Card push',
  token: 'Tokenised transfer',
  stablecoin: 'Stablecoin',
};

/**
 * uc05's labels, in its own words. Not re-worded: "surest to arrive on time" is a
 * claim about a probability against a deadline, and "most reliable" would be a
 * stronger and different claim than the learner is making.
 */
export const ROUTE_LABEL: Record<string, string> = {
  cheapest: 'Lowest expected charge',
  fastest: 'Arrives first',
  surest: 'Surest to arrive on time',
};

/** One term of the explainability payload: a prior, an estimate and what moved it. */
export interface WhyTerm {
  prior: number;
  estimate: number;
  margin: number;
  /** [feature name, signed contribution], largest first. */
  top: [string, number][];
  rest: number;
}

export interface RouteOption {
  rank: number;
  labels: string[];
  route: string;
  name: string;
  kind: RouteKind;
  charge: number;
  parts: number;
  send: string;
  arrival: string;
  minutes_to_spare: number;
  expected_deduction: number;
  on_time: number;
  on_time_margin: number;
  failure: number;
  expected_total: number;
  /** The learner's confidence in its own estimate for this route. */
  proven: boolean;
  why: Record<'fail' | 'on_time' | 'deduction', WhyTerm>;
}

export interface RouteExcluded {
  route: string;
  name: string;
  reason: string;
}

export interface RouteQuote {
  asked: {
    currency: string;
    amount: number;
    destination: string | null;
    at: string;
    by_when: string;
    charge_bearer: string;
  } | null;
  options: RouteOption[];
  /** uc05's reason for offering nothing. Null when it offered something. */
  none: string | null;
  considered: number;
  excluded: RouteExcluded[];
  floor: number | null;
  sheet_version: number | null;
  /** null for a domestic payment; false when the learner never saw this corridor. */
  destination_known: boolean | null;
}

export interface RouteOptionsRequest {
  fromVaId: string;
  currency: string;
  amount: number;
  /** ISO local date-time, no offset: uc05 keeps local times and rejects a zoned one. */
  byWhen: string;
  /** Alpha-2. Present means cross-border, absent means domestic. */
  beneficiaryCountry?: string | null;
  chargeBearer?: string | null;
  purposeCode?: string | null;
  reachCard?: boolean;
  reachToken?: boolean;
}

export interface RouteOptionsResponse {
  /** False is not an error: the currency is one uc05 does not quote, or routing is off here. */
  quotable: boolean;
  note: string | null;
  quote: RouteQuote | null;
}

export interface RouteSelectResponse {
  accepted: boolean;
  selectionId: string | null;
  /** The chosen route's kind, which becomes the payment channel. */
  channel: string | null;
  requoteNote: string | null;
  /** Present only when refused: the options as they stand now. */
  quote: RouteQuote | null;
}

/**
 * A beneficiary BIC carries its country in characters 5-6, which is where the
 * domestic/cross-border answer comes from -- the payment screens collect a BIC
 * and never ask the question. Returns null for anything that is not a BIC, so a
 * half-typed one reads as domestic rather than as a wrong corridor.
 */
export function countryFromBic(bic?: string | null): string | null {
  if (!bic) return null;
  const trimmed = bic.trim().toUpperCase();
  if (!/^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$/.test(trimmed)) return null;
  return trimmed.slice(4, 6);
}

export const routingApi = {
  /** Up to three options, or a reason there are none. Writes nothing. */
  quote: async (request: RouteOptionsRequest): Promise<RouteOptionsResponse> => {
    const response = await apiClient.post('/payments/routing/quote', request);
    return response.data?.data ?? response.data;
  },

  /**
   * Commits to one route. The backend re-quotes first and may refuse, handing back
   * the current options -- 200 either way, because a moved market is not a client error.
   */
  select: async (
    ask: RouteOptionsRequest,
    route: string,
    quotedCharge: number,
    quotedArrival: string,
  ): Promise<RouteSelectResponse> => {
    const response = await apiClient.post('/payments/routing/select', {
      ask,
      route,
      quotedCharge,
      quotedArrival,
    });
    return response.data?.data ?? response.data;
  },
};
