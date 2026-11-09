// Quote requests, deals and the FX credit limit for the corporate FX workspace.
// Backend: FxDealController at /api/v1/fx. Uses the {success,data} envelope, and the API omits
// null fields, so optional values are typed optional.

import { apiClient, ApiResponse } from './api';

export type Side = 'BUY' | 'SELL';
export type Instrument = 'SPOT' | 'FORWARD' | 'WINDOW';
export type QuoteStatus = 'REQUESTED' | 'QUOTED' | 'ACCEPTED' | 'EXPIRED' | 'REJECTED' | 'CANCELLED';
export type DealStatus =
  | 'BOOKED' | 'ACTIVE' | 'PENDING_SETTLEMENT' | 'PARTIALLY_DRAWN' | 'SETTLEMENT_FAILED'
  | 'SETTLED' | 'CLOSED_OUT' | 'ROLLED' | 'CANCELLED';
/** The bank issues a confirmation; the corporate affirms or disputes it. An unconfirmed deal cannot settle. */
export type ConfirmationStatus = 'ISSUED' | 'AFFIRMED' | 'DISPUTED';
export type SettlementKind = 'MATURITY' | 'DRAWDOWN' | 'CLOSE_OUT';
export type SettlementStatus = 'PENDING' | 'SETTLED' | 'FAILED';

export interface QuoteBody {
  corporateId: string;
  /** The legal entity the deal is for; its FX sub-limit and the group line must both have room. */
  entityId: string;
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
  /** The price converts a payment: it settles through the payment and is used by submitting it. */
  forPayment?: boolean;
}

export interface Quote {
  id: string;
  corporateId: string;
  /** The legal entity the deal is for. */
  entityId?: string;
  entityName?: string;
  /** Where the bought currency is credited and the sold one debited, when both could be found. */
  buyAccountLabel?: string;
  sellAccountLabel?: string;
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
  clientRate?: number;
  /** Forwards only: the client spot (margin already in it) and the points to the all-in rate. Never the mid or margin. */
  spotRate?: number;
  forwardPoints?: number;
  counterAmount?: number;
  dealer?: string;
  quotedAt?: string;
  validUntil?: string;
  /** Seconds the price stays firm, from the server clock. */
  secondsRemaining: number;
  limitCharge?: number;
  limitCurrency?: string;
  dealId?: string;
  requestedBy?: string;
  createdAt?: string;
  /** Times the client has asked for a better price, the most it may, and whether the desk says it is at its best. maxRounds 0 = not negotiable. */
  rounds: number;
  maxRounds: number;
  bestPrice: boolean;
}

/** What happened to the requests in a period. hitRate is accepted over those settled (accepted, expired, withdrawn). */
export interface RfqSummary {
  requests: number;
  priced: number;
  accepted: number;
  expired: number;
  declined: number;
  withdrawn: number;
  open: number;
  hitRate?: number;
  avgSecondsToAccept?: number;
}

export interface RfqRow {
  quote: Quote;
  /** The deal an accepted request became. */
  dealRef?: string;
}

export interface RfqLog {
  summary: RfqSummary;
  quotes: RfqRow[];
}

export interface Deal {
  id: string;
  dealRef: string;
  corporateId: string;
  /** The legal entity the deal is for. */
  entityId?: string;
  entityName?: string;
  /** The home-bank accounts the deal settles through: bought currency credited, sold currency debited. */
  buyAccountId?: string;
  buyAccountLabel?: string;
  sellAccountId?: string;
  sellAccountLabel?: string;
  instrument: Instrument;
  pair: string;
  side: Side;
  buyCurrency: string;
  buyAmount: number;
  sellCurrency: string;
  sellAmount: number;
  rate: number;
  /** Forwards only: the client spot (margin already in it) and the points to the all-in rate. */
  spotRate?: number;
  forwardPoints?: number;
  tradeDate: string;
  valueDate: string;
  windowStart?: string;
  status: DealStatus;
  limitCharge: number;
  limitCurrency: string;
  purpose?: string;
  exposureRef?: string;
  createdAt: string;
  confirmationStatus?: ConfirmationStatus;
  confirmationIssuedAt?: string;
  confirmedAt?: string;
  confirmedBy?: string;
  disputeReason?: string;
  /** Issued more than the allowed hours ago and still unanswered. */
  confirmationOverdue: boolean;
  /** The deal's amount in the pair's base currency, and how much of it is drawn or still to come. */
  baseAmount: number;
  drawnAmount: number;
  remainingAmount: number;
  rolledFromId?: string;
  closeRate?: number;
  /** Cash difference on close-out or roll in the pair's quote currency; positive is in the corporate's favour. */
  realisedPnl?: number;
  settledAt?: string;
  /** Live mark against today's market; absent when the market cannot price the deal. */
  mtmQuote?: number;
  mtmQuoteCurrency?: string;
  mtmHome?: number;
  mtmHomeCurrency?: string;
}

export interface Settlement {
  id: string;
  kind: SettlementKind;
  valueDate: string;
  buyCurrency: string;
  buyAmount: number;
  sellCurrency: string;
  sellAmount: number;
  status: SettlementStatus;
  reason?: string;
  settledAt?: string;
  /** The accounts posted to; a close-out uses one of them. */
  debitAccountLabel?: string;
  creditAccountLabel?: string;
  /** Set when a payment took this part of the deal: the deal's utilisation. */
  paymentRef?: string;
  paymentId?: string;
}

/** Part of a payment taken from a deal, in the payment's currency. */
export interface FxDraw {
  dealId: string;
  amount: number;
}

/** A deal that fits a payment: what it can still deliver, and what it covers of this one. */
export interface PaymentFit {
  dealId: string;
  dealRef: string;
  instrument: Instrument;
  valueDate: string;
  pair: string;
  rate: number;
  available: number;
  covers: number;
  debit: number;
}

/** The deals that fit a payment, soonest value date first, and what is left for a rate. */
export interface PaymentPlan {
  currency: string;
  accountCurrency: string;
  amount: number;
  deals: PaymentFit[];
  rest: number;
}

/** One side of a streamed price: the client buying (buy) or selling (sell) the base currency. */
export interface StreamLeg {
  rate: number;
  /** Forwards only: the client spot and the points to the all-in rate. */
  spotRate?: number;
  forwardPoints?: number;
  counterAmount: number;
}

/** A two-sided click-to-deal price, or why the trade goes to a request for quote. */
export interface StreamPrice {
  streamable: boolean;
  reason?: string;
  pair: string;
  amount: number;
  instrument: Instrument;
  valueDate?: string;
  priceId?: string;
  buy?: StreamLeg;
  sell?: StreamLeg;
  validForSeconds: number;
  maxAmount: number;
  maxTenorDays: number;
}

export type ReportKind = 'deals' | 'settlements' | 'mtm' | 'confirmations';

/** One report for a period: the same table the CSV and PDF are made from. */
export interface ReportTable {
  kind: string;
  title: string;
  subtitle: string;
  columns: string[];
  rows: string[][];
}

/**
 * Tell me when I can buy the base currency at or below a rate (side BUY), or sell it at or above one (SELL), on my
 * own price at an amount; arrives in the notification bell. Fires once.
 */
export interface RateAlert {
  id: string;
  pair: string;
  side: Side;
  amount?: number;
  targetRate: number;
  /** The client's price on that side when the alert was set. */
  rateAtCreation?: number;
  status: 'ACTIVE' | 'TRIGGERED' | 'CANCELLED';
  note?: string;
  createdAt: string;
  triggeredAt?: string;
  triggeredRate?: number;
}

/** A home-bank account an entity may settle FX through. */
export interface SettlementAccount {
  id: string;
  currency: string;
  label: string;
  bankName: string;
  balance: number;
}

/** The account a currency settles through, or why there is none. fromInstruction false = the only eligible account. */
export interface ResolvedAccount {
  currency: string;
  account?: SettlementAccount;
  fromInstruction: boolean;
  reason?: string;
}

/** One entity's standing settlement instruction for one currency. */
export interface InstructionRow {
  entityId: string;
  entityName: string;
  currency: string;
  resolved: ResolvedAccount;
  eligible: SettlementAccount[];
}

export interface CloseOutQuote {
  dealRef: string;
  pair: string;
  remainingBase: number;
  closeRate: number;
  spreadBps: number;
  realisedPnl: number;
  pnlCurrency: string;
  pricedDate: string;
}

export interface RollQuote {
  closeOut: CloseOutQuote;
  newValueDate: string;
  newRate: number;
  newForwardPoints: number;
  newSpreadBps: number;
  newCounterAmount: number;
}

export interface SettlementRun {
  asOf: string;
  settled: number;
  failed: number;
  blocked: number;
  notes: string[];
}

export interface DealAttention {
  /** LIMIT_WARNING: an FX line or entity sub-limit past 80% (high past 95%); dealId is then the limit's id. */
  type: 'CONFIRMATION_PENDING' | 'CONFIRMATION_DISPUTED' | 'SETTLEMENT_FAILED' | 'FUNDING_NEEDED' | 'LIMIT_WARNING';
  severity: 'high' | 'medium';
  corporateId: string;
  corporateName: string;
  dealId: string;
  dealRef?: string;
  headline: string;
  detail: string;
  dueDate: string;
}

export interface DealEvent {
  action: string;
  actor?: string;
  details?: string;
  timestamp: string;
}

/** One entity's FX sub-limit under the group line; allocated false when it has none. */
export interface FxAllocation {
  entityId: string;
  entityName: string;
  allocated: boolean;
  usable: boolean;
  reason?: string;
  limit: number;
  used: number;
  headroom: number;
}

/**
 * The corporate's FX line: a facility under the bank's credit agreement. limit, used and headroom are the group
 * line's; entity is the requested entity's sub-limit. usable false (with a reason) when there is no facility, or it
 * or the line is not active or not in date.
 */
export interface CreditLimit {
  corporateId: string;
  currency: string;
  usable: boolean;
  reason?: string;
  facilityName?: string;
  expiry?: string;
  limit: number;
  used: number;
  headroom: number;
  entity?: FxAllocation;
}

/** The FX line with every active entity and its sub-limit, for the Credit Limits page. */
export interface FxLineView {
  line: CreditLimit;
  allocated: number;
  unallocated: number;
  allocations: FxAllocation[];
}

export const fxDealApi = {
  /** The deals that fit a payment; {@code use} limits them to the ones the payer kept ticked. */
  paymentPlan: (accountId: string, currency: string, amount: number, use?: string[]) =>
    apiClient.get<ApiResponse<PaymentPlan>>('/fx/payments/plan', {
      // an empty list is "none": sent as an id no deal has, since an empty parameter would read as all
      params: { accountId, currency, amount, ...(use ? { use: (use.length ? use : ['00000000-0000-0000-0000-000000000000']).join(',') } : {}) },
    }).then(r => r.data.data),

  /** Ask the desk for a better price. With no targetRate it is "your best price". */
  counter: (id: string, body: { targetRate?: number; note?: string } = {}) =>
    apiClient.post<ApiResponse<Quote>>(`/fx/quotes/${id}/counter`, body).then(r => r.data.data),

  requestQuote: (body: QuoteBody) =>
    apiClient.post<ApiResponse<Quote>>('/fx/quotes', body).then(r => r.data.data),

  /** Every request a corporate made in the last `days`, newest first; summary covers all statuses. */
  rfqLog: (corporateId: string, filter: { status?: QuoteStatus; pair?: string; days?: number } = {}) =>
    apiClient.get<ApiResponse<RfqLog>>('/fx/quotes', { params: { corporateId, ...filter } }).then(r => r.data.data),

  /** One request's history, including those that never became a deal. */
  quoteEvents: (id: string) =>
    apiClient.get<ApiResponse<DealEvent[]>>(`/fx/quotes/${id}/events`).then(r => r.data.data),

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

  /** The group FX line and, with entityId, that entity's sub-limit. */
  limit: (corporateId: string, entityId?: string) =>
    apiClient.get<ApiResponse<CreditLimit>>('/fx/limits', { params: { corporateId, entityId } }).then(r => r.data.data),

  /** The FX line and every active entity's sub-limit. */
  line: (corporateId: string) =>
    apiClient.get<ApiResponse<FxLineView>>('/fx/lines', { params: { corporateId } }).then(r => r.data.data),

  /** Sets an entity's FX sub-limit; all sub-limits together may not exceed the line. */
  allocate: (corporateId: string, entityId: string, amount: number) =>
    apiClient.put<ApiResponse<FxAllocation>>('/fx/lines/allocations', { corporateId, entityId, amount }).then(r => r.data.data),

  // ---- confirmation ----
  confirm: (dealId: string) =>
    apiClient.post<ApiResponse<Deal>>(`/fx/deals/${dealId}/confirm`).then(r => r.data.data),

  dispute: (dealId: string, reason: string) =>
    apiClient.post<ApiResponse<Deal>>(`/fx/deals/${dealId}/dispute`, { reason }).then(r => r.data.data),

  /** The bank's confirmation as a PDF, fetched as a blob so the download carries the session. */
  confirmationPdf: (dealId: string) =>
    apiClient.get<Blob>(`/fx/deals/${dealId}/confirmation.pdf`, { responseType: 'blob' }).then(r => r.data),

  // ---- in-life ----
  drawdown: (dealId: string, amount: number, valueDate: string) =>
    apiClient.post<ApiResponse<Settlement>>(`/fx/deals/${dealId}/drawdowns`, { amount, valueDate }).then(r => r.data.data),

  closeOutPreview: (dealId: string) =>
    apiClient.get<ApiResponse<CloseOutQuote>>(`/fx/deals/${dealId}/close-out`).then(r => r.data.data),

  closeOut: (dealId: string) =>
    apiClient.post<ApiResponse<Deal>>(`/fx/deals/${dealId}/close-out`).then(r => r.data.data),

  rollPreview: (dealId: string, newValueDate: string) =>
    apiClient.get<ApiResponse<RollQuote>>(`/fx/deals/${dealId}/roll`, { params: { newValueDate } }).then(r => r.data.data),

  /** Closes the deal and books its replacement; returns the replacement. */
  roll: (dealId: string, newValueDate: string) =>
    apiClient.post<ApiResponse<Deal>>(`/fx/deals/${dealId}/roll`, { newValueDate }).then(r => r.data.data),

  settlements: (dealId: string) =>
    apiClient.get<ApiResponse<Settlement[]>>(`/fx/deals/${dealId}/settlements`).then(r => r.data.data),

  // ---- reports ----
  report: (kind: ReportKind, corporateId: string, from: string, to: string) =>
    apiClient.get<ApiResponse<ReportTable>>(`/fx/reports/${kind}`, { params: { corporateId, from, to } }).then(r => r.data.data),

  /** Fetched as a blob so the download carries the session. */
  reportFile: (kind: ReportKind, corporateId: string, from: string, to: string, format: 'csv' | 'pdf') =>
    apiClient.get<Blob>(`/fx/reports/${kind}/${format}`, { params: { corporateId, from, to }, responseType: 'blob' }).then(r => r.data),

  // ---- rate alerts ----
  alerts: (corporateId: string) =>
    apiClient.get<ApiResponse<RateAlert[]>>('/fx/alerts', { params: { corporateId } }).then(r => r.data.data),

  createAlert: (corporateId: string, pair: string, side: Side, amount: number, targetRate: number, note?: string) =>
    apiClient.post<ApiResponse<RateAlert>>('/fx/alerts', { corporateId, pair, side, amount, targetRate, note }).then(r => r.data.data),

  cancelAlert: (id: string) =>
    apiClient.post<ApiResponse<RateAlert>>(`/fx/alerts/${id}/cancel`).then(r => r.data.data),

  // ---- streaming ----
  /** Both sides for a small, short trade, or why it is priced on request. Ask again every second or two. */
  stream: (p: { corporateId: string; entityId: string; pair: string; amount: number; instrument: Instrument; valueDate?: string; purpose?: string; exposureRef?: string; forPayment?: boolean }) =>
    apiClient.get<ApiResponse<StreamPrice>>('/fx/stream', { params: p }).then(r => r.data.data),

  /** Books at the clicked side of a price shown a moment ago. */
  streamDeal: (priceId: string, side: Side) =>
    apiClient.post<ApiResponse<Deal>>('/fx/stream/deal', { priceId, side }).then(r => r.data.data),

  // ---- settlement accounts ----
  instructions: (corporateId: string) =>
    apiClient.get<ApiResponse<InstructionRow[]>>('/fx/settlement/instructions', { params: { corporateId } }).then(r => r.data.data),

  setInstruction: (corporateId: string, entityId: string, currency: string, accountId: string) =>
    apiClient.put<ApiResponse<InstructionRow>>('/fx/settlement/instructions', { corporateId, entityId, currency, accountId }).then(r => r.data.data),

  /** What an entity's deals would settle through in a currency, with the accounts it could use instead. */
  settlementAccount: (corporateId: string, entityId: string, currency: string) =>
    apiClient.get<ApiResponse<{ resolved: ResolvedAccount; eligible: SettlementAccount[] }>>('/fx/settlement/accounts',
      { params: { corporateId, entityId, currency } }).then(r => r.data.data),

  /** Until the day before value date, or while settlement has failed. */
  changeDealAccounts: (dealId: string, buyAccountId?: string, sellAccountId?: string) =>
    apiClient.put<ApiResponse<Deal>>(`/fx/deals/${dealId}/settlement-accounts`, { buyAccountId, sellAccountId }).then(r => r.data.data),

  /** Settles whatever is due, posting both legs to the deal's accounts. asOf moves the demo clock forward; omit it for today. */
  runSettlement: (asOf?: string, corporateId?: string) =>
    apiClient.post<ApiResponse<SettlementRun>>('/fx/deals/settlement/run', null, { params: { asOf, corporateId } }).then(r => r.data.data),

  /** What needs attention across deals: unconfirmed, disputed, failed to settle, or short of cash. */
  attention: (corporateId?: string) =>
    apiClient.get<ApiResponse<DealAttention[]>>('/fx/deals/attention', { params: corporateId ? { corporateId } : {} }).then(r => r.data.data),
};
