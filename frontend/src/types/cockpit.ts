// ============================================================================
// Treasurer's Morning Cockpit — domain types.
//
// The cockpit reframes the dashboard around exceptions and the today-horizon,
// not around portfolio summaries. The two domain primitives are:
//
//   - `AttentionItem`   — anything the treasurer needs to act on or
//                         consciously dismiss this morning.
//   - `TodayHorizon`    — the position the treasurer is steering toward by
//                         end-of-day, with FX-honest disclosure.
//
// The shape is permanent from V1 so the V2 backend (which will replace the
// client-side composition in `cockpitApi`) can return the same payload.
//
// Naming note: the existing `exceptionApi` (in services/api.ts, around line
// 4705) handles *unallocated settlement transactions* — a different domain.
// We keep this surface deliberately separate, calling its items "attention
// items" rather than "exceptions" everywhere user-facing.
// ============================================================================

export type AttentionSeverity = 'critical' | 'high' | 'medium';

/**
 * Operational categories the cockpit knows how to render. The schema accepts
 * all eight from V1; producers for the trailing three may return `[]` until
 * V2 wires up the reference data they depend on (see `cockpitApi.ts` for
 * the per-producer V1 status).
 */
export type AttentionCategory =
  | 'funding_shortfall'
  | 'sweep_failure'
  | 'stuck_transaction'
  | 'stale_balance'
  | 'pending_approval'
  // V2 categories — the schema accepts them now so consumers don't need a
  // conditional check; producers return `[]` until the backing data lands.
  | 'fx_exposure'
  | 'concentration_risk'
  | 'loan_rollover';

/**
 * Time pressure is data, not chrome. Every attention item carries one. The
 * UI layer maps `kind` → render shape:
 *   - `relative_future` → "in 5h 28m"
 *   - `relative_past`   → "3h ago"
 *   - `absolute`        → "rolls 17:00 GST"
 *   - `age`             → "stale 51h"
 */
export interface AttentionTimePressure {
  kind: 'relative_future' | 'relative_past' | 'absolute' | 'age';
  /** Pre-rendered human string. Producers compute this so the row layer is dumb. */
  displayText: string;
  /** Used for sort + severity heuristics; only one of these is set per row. */
  millisecondsToDeadline?: number;
  millisecondsSinceEvent?: number;
  /**
   * For `kind === 'absolute'` rows that point at a specific hour (e.g. a
   * cutoff at 17:00). The TodayHorizonPanel uses this to mark the matching
   * hour on its hourly flow bar — visually linking inbox to horizon.
   */
  anchorHourLocal?: number;
}

export interface AttentionContext {
  bankBic?: string;
  bankName?: string;
  entityId?: string;
  entityName?: string;
  currencyCode?: string;
  /** Native amount stays as value+currency — never silently FX-converted. */
  nativeAmount?: { value: number; currency: string };
  /** External reference like POBO-2026-14821 / payment ref / sweep ref. */
  reference?: string;
  counterpartyName?: string;
}

export interface AttentionAction {
  /** Verb. Convention: 'Fund' / 'Re-run' / 'Investigate' / 'Refresh' / 'Review' / 'Hedge' / 'Confirm'. */
  label: string;
  /**
   * Action kind:
   *   - `navigate` → onNavigate(actionId) — actionId is a page identifier.
   *   - `execute`  → cockpitApi.executeAction(itemId, actionId).
   *   - `drawer`   → opens AttentionDrawer with this item's full context.
   */
  kind: 'navigate' | 'execute' | 'drawer';
  actionId: string;
  requiresConfirmation: boolean;
  /** Movements > $5M equivalent require a second factor. Producers set this. */
  requiresSecondFactor?: boolean;
}

export interface AttentionItem {
  id: string;
  severity: AttentionSeverity;
  category: AttentionCategory;
  /** One-line action statement, e.g. 'Funding shortfall on Brato UAE main account'. */
  headline: string;
  /** One-line context detail rendered beneath the headline at body-sm. */
  detail: string;
  context: AttentionContext;
  timePressure: AttentionTimePressure;
  /** 1–3 actions; the first is the primary (promoted in critical rows). */
  actions: AttentionAction[];
  /** ISO timestamp; if set in the past, the row is back; if future, snoozed. */
  snoozedUntil?: string;
  createdAt: string;
}

// ----------------------------------------------------------------------------
// Today's Horizon
// ----------------------------------------------------------------------------

export interface FxRateDisclosure {
  baseCurrency: string;
  rates: Array<{
    pair: string;     // 'GBP/USD'
    rate: number;     // 1.2812
    source: string;   // 'WMR 10:00 fix'
    asOf: string;     // ISO timestamp
  }>;
}

export interface TodayHorizon {
  /** The base used for any non-per-currency totals on the horizon panel. */
  baseCurrency: string;
  netPositionEndOfDay: number;
  openingBalance: number;
  scheduledOutflowsNext8h: number;
  expectedInflowsNext8h: number;
  creditHeadroom: number;
  /** 24 entries (0..23 in user's local TZ). Hours with no flow show 0. */
  hourlyFlows: Array<{
    hour: number;
    inflow: number;
    outflow: number;
  }>;
  /** Disclosed FX rates used to derive the base-currency totals above. */
  fxDisclosure: FxRateDisclosure;
  /**
   * Per-currency breakdown so hover/click on a converted figure can reveal the
   * native components — the FX-honesty contract.
   */
  byCurrency: Array<{
    currencyCode: string;
    netPositionEndOfDay: number;
    scheduledOutflowsNext8h: number;
    expectedInflowsNext8h: number;
  }>;
}
