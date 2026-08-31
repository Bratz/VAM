// ============================================================================
// Consent timeline (Phase-4 operational-risk line + "Operational risk
// profile"). Pure.
//
// For each proposed rule depending on a NON-INTERNAL physical account with a
// consent expiry: a surcharge on the rule's annual principal —
//   days_to_expiry < severeDays   → × consentSevereRate   (e.g. 0.5%)
//   days_to_expiry < moderateDays → × consentModerateRate (e.g. 0.2%)
//   else                          → 0
// Also emits a per-bank `ConsentWindow[]` for the score panel's
// "Operational risk profile" sub-section. Live baseline 0; danger tone.
// ============================================================================

import type {
  ConsentWindow,
  ScoreInputs,
  SimulatorPhysicalAccount,
} from '../../../components/simulator/types';
import { PerCurrencyLine, accountOf, addAmount, emptyLine } from './shared';

function daysToExpiry(iso?: string): number | null {
  if (!iso) return null;
  const d = Math.ceil((new Date(iso).getTime() - Date.now()) / 86_400_000);
  return Number.isNaN(d) ? null : d;
}

function rateFor(
  days: number,
  c: ScoreInputs['constants'],
): { rate: number; severity: ConsentWindow['severity'] } {
  if (days < c.consentSevereDays) {
    return { rate: c.consentSevereRate, severity: 'severe' };
  }
  if (days < c.consentModerateDays) {
    return { rate: c.consentModerateRate, severity: 'moderate' };
  }
  return { rate: 0, severity: 'ok' };
}

function involvedAccounts(
  input: ScoreInputs,
): Array<{ acc: SimulatorPhysicalAccount; execs: number }> {
  const byLocalId = new Map(
    input.proposedShadows.map((s) => [s.localId, s]),
  );
  const out: Array<{ acc: SimulatorPhysicalAccount; execs: number }> = [];
  for (const rule of input.proposedRules) {
    const execs = input.constants.executionsPerYear[rule.frequency] ?? 0;
    const locals = [...(rule.sourceLocalIds ?? []), rule.targetLocalId];
    for (const lid of locals) {
      const sh = byLocalId.get(lid);
      if (!sh) continue;
      const acc = accountOf(sh, input.accountsById);
      if (acc) out.push({ acc, execs });
    }
  }
  return out;
}

export function consentTimeline(input: ScoreInputs): PerCurrencyLine {
  const line = emptyLine(
    'Surcharge on rules that depend on a non-internal account whose data-sharing consent is near expiry.',
  );
  for (const { acc, execs } of involvedAccounts(input)) {
    if (acc.bankRelationship === 'INTERNAL') continue;
    const d = daysToExpiry(acc.consentExpiresAt);
    if (d === null) continue;
    const { rate } = rateFor(d, input.constants);
    if (rate === 0) continue;
    const principalAnnual = (acc.currentBalance ?? 0) * execs;
    addAmount(line.proposed, acc.currencyCode, principalAnnual * rate);
  }
  return line;
}

/** Per-bank consent windows for the "Operational risk profile" sub-section.
 *  Deduped by physical account; worst (largest) surcharge wins. */
export function consentWindows(input: ScoreInputs): ConsentWindow[] {
  const byPhys = new Map<string, ConsentWindow>();
  for (const { acc, execs } of involvedAccounts(input)) {
    if (acc.bankRelationship === 'INTERNAL') continue;
    const d = daysToExpiry(acc.consentExpiresAt);
    if (d === null) continue;
    const { rate, severity } = rateFor(d, input.constants);
    const surcharge = (acc.currentBalance ?? 0) * execs * rate;
    const existing = byPhys.get(acc.id);
    if (!existing || surcharge > existing.surchargeAnnual) {
      byPhys.set(acc.id, {
        physicalAccountId: acc.id,
        bankCode: acc.bankCode,
        bankName: acc.bankName,
        currencyCode: acc.currencyCode,
        daysToExpiry: d,
        severity,
        surchargeAnnual: surcharge,
      });
    }
  }
  return [...byPhys.values()].sort(
    (a, b) => a.daysToExpiry - b.daysToExpiry,
  );
}
