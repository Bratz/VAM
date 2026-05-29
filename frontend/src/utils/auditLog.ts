// ============================================================================
// Audit-trail helper.
//
// Every action button in the Treasurer's Morning Cockpit calls this BEFORE
// executing the action — the audit ID returned is then included in the
// action's payload so the downstream system can correlate the user's
// decision with whatever changed.
//
// V1 implementation: console.info + fire-and-forget POST to /audit/v1/record.
// V2 implementation: structured audit pipeline (e.g. Kafka → audit DB),
// same `record` signature so V2 is a wiring change, not a refactor.
//
// The signature is permanent. Callers depend on:
//   - Returns `{ auditId }` synchronously-ish (a Promise that resolves fast).
//   - Never throws — failures degrade to a console warning. An audit-trail
//     failure must NOT block the user's action.
// ============================================================================

import { apiClient } from '../services/api';

export interface AuditEntry {
  /**
   * Dotted action name. Convention: `<surface>.<noun>.<verb>`. Examples:
   *   - `cockpit.attention.action.executed`
   *   - `cockpit.attention.snoozed`
   *   - `cockpit.attention.dismissed`
   *   - `cockpit.action.confirmed`
   */
  action: string;
  itemId?: string;
  actionId?: string;
  /** Free-form context payload; serialised as-is for the audit POST body. */
  payload?: unknown;
  /** Filled by record() from the auth context if available. */
  userId?: string;
  /** ISO timestamp; record() fills this. */
  timestamp: string;
  /**
   * Hash of the visible state at decision time — lets V2 reconstruct what
   * the user saw when they clicked. V1 callers may pass a simple JSON.stringify
   * hash; V2 will likely move to a server-side snapshot.
   */
  dataStateHash?: string;
}

/**
 * Generate an audit ID without depending on `crypto.randomUUID` (which
 * isn't available in older browsers / strict CSP contexts).
 */
function generateAuditId(): string {
  // Simple time + entropy. Server is the source of truth in V2; this is
  // purely a client-side correlation handle that survives the request.
  const t = Date.now().toString(36);
  const r = Math.random().toString(36).slice(2, 10);
  return `audit_${t}_${r}`;
}

export const auditLog = {
  /**
   * Record an audit entry. Always resolves with an audit ID, even if the
   * backend POST fails — the action must continue regardless.
   */
  async record(
    entry: Omit<AuditEntry, 'timestamp'>,
  ): Promise<{ auditId: string }> {
    const auditId = generateAuditId();
    const fullEntry: AuditEntry = {
      ...entry,
      timestamp: new Date().toISOString(),
    };

    // V1: telemetry-style console line. The cockpit's audit preview block
    // shows what _will_ be recorded, but the actual record happens here.
    // eslint-disable-next-line no-console
    console.info('[audit]', auditId, fullEntry);

    // Fire-and-forget POST. Swallow any error — audit failures must not
    // block the user. V2 swaps this for the real audit pipeline.
    try {
      void apiClient
        .post('/audit/v1/record', { auditId, ...fullEntry })
        .catch((err: unknown) => {
          // eslint-disable-next-line no-console
          console.warn('[audit] backend record failed (non-blocking):', err);
        });
    } catch (err) {
      // eslint-disable-next-line no-console
      console.warn('[audit] dispatch failed (non-blocking):', err);
    }

    return { auditId };
  },
};
