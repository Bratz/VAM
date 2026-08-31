// ============================================================================
// Lightweight telemetry helper for the cockpit.
//
// The codebase doesn't have a dedicated telemetry layer today — error
// reporting goes via `console.error` and there is no analytics SDK wired
// in. The cockpit's spec asks for "lightweight `console.info` events at:
// page load, attention row click, action executed, drawer opened, snooze",
// wired to whatever telemetry layer exists. With nothing to wire to, we
// stay on console.info — the sink swap is a one-file change when V2 lands
// (Segment, Posthog, Mixpanel — whichever).
// ============================================================================

export type TelemetryEvent =
  | 'cockpit.loaded'
  | 'cockpit.attention.row.clicked'
  | 'cockpit.attention.action.executed'
  | 'cockpit.attention.drawer.opened'
  | 'cockpit.attention.snoozed'
  | 'cockpit.refresh.clicked'
  | 'cockpit.classic.switch.clicked';

export const telemetry = {
  /**
   * Record a structured event. Currently a console.info — V2 swaps the
   * sink. The signature is permanent: any callsite written today keeps
   * working.
   */
  emit(event: TelemetryEvent, payload?: Record<string, unknown>): void {
    // eslint-disable-next-line no-console
    console.info('[telemetry]', event, payload ?? {});
  },
};
