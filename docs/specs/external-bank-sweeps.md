# Sweeps with external-bank accounts — specification

Status: **decided, planned** (2026-11-12). Plan: `tasks/todo.md` ("Sweeps with external-bank accounts"). Decisions D1–D10 were taken in an interview; they are marked in the text and listed
at the end. Builds on bank statement intake (`docs/specs/bank-statement-intake.md`, 017c033).

Ground rule (user, 12 Nov): **the home bank always runs the sweep.** An account at another bank is never swept "by"
that bank; the home bank instructs it, under an authority the corporate gave, and that bank's statement confirms it.

## 1. Why

Recorded on vam-five on 12 Nov 2026 (`docs/demo/external-bank-sweeps-walkthrough.md`) and verified in code:

| Today | Effect |
|---|---|
| Rule creation requires an ACTIVE `ExternalMandate` for any leg on an external shadow (`SweepService.assertShadowLegEligible`); no screen or API creates one, the seed has none | A newly linked external account can never be swept |
| `executeSweep` never re-checks the mandate; SQL-seeded external rules (Albion Barclays → Lloyds, Mercator IT → DE) run without one; IHB auto-rules skip the check too | The check is a creation-time gate only |
| Execution posts ledger entries only (`executeTransfer`); `mirrorIfHomeBankShadow` is a no-op for external shadows | No instruction reaches the other bank; its bank balance and physical account do not move |
| The next statement overwrites the ledger (statement wins) | The sweep "disappears" on external legs; the sweep fee drops out of the balance |
| No freshness check before sizing a sweep | Lloyds header, stale since 14 Oct, was credited without comment |
| Cross-entity sweeps outside the IHB record no intercompany position | ALBN-EN → ALBN-GB moved money with no loan between the entities |
| `ExternalMandate` exists (reference, shadow, rail scope, type DIRECT_DEBIT / SEPA_DIRECT_DEBIT / STANDING_INSTRUCTION, status PENDING_ACTIVATION / ACTIVE / SUSPENDED / EXPIRED / REVOKED, validity, max per transaction / daily, document ref) | Model is there; nothing uses it beyond the gate |
| `SweepExecution` has COMMITTED ("position committed, awaiting EOD settlement") and SETTLED | A pending state exists to reuse |
| Sweep fee is fixed in code: 0.05%, min 5, max 500 per source (`SweepService.calculateSweepFee`) | No separate pricing for external work |

Effect for the client: multi-bank concentration — the reason to link other banks — does not happen; it is only drawn
on the platform's ledger.

## 2. How it should work (target)

```
Authority      Bank operations register the corporate's authority for the home bank to instruct its account at
               another bank, with its format (MT101 or pain.001), limits and document (D2, D3).
Rule           Source = that account, target = a home-bank account of the same legal entity, same currency
               (D1, D10).
Run            The home bank's sweep job sizes the sweep on the account's last end-of-day statement balance; a
               stale balance or an authority that is not ACTIVE / within limits skips the run with the reason (D6).
Instruct       The home bank produces the request for transfer in the authority's format (D2): debit the corporate's
               account at the other bank, credit the home-bank account, with an end-to-end reference. The home-bank
               target is credited at once, reversibly; the external leg is pending (D4). Fee = standard + a flat
               charge per instruction (D8).
Confirmed      The other bank's statement shows the debit (reference, else amount + value date, D5) → SETTLED.
               Not seen within 2 business days (D7) → FAILED: credit reversed, instruction fee refunded, alert.
```

## 3. Functional requirements

### 3.1 Authority (D2, D3)
- R1. An authority (`external_mandates`) belongs to one external account (its shadow) and names the instruction
  format **MT101 or pain.001** (D2), validity, max per transaction and per day, document reference; status
  PENDING_ACTIVATION → ACTIVE → SUSPENDED / REVOKED / EXPIRED.
- R2. Registered, activated, suspended and revoked by **bank operations** (D3); the corporate sees it read-only on the
  account. Every change audited.
- R3. Checked at rule creation **and at every run** (status, validity, per-transaction and daily limits). At run time a
  failing check skips that source with the reason; nothing is instructed.

### 3.2 Which rules (D1, D10)
- R4. An external account may be a **source only**, and the target must be a **home-bank** account (D1): no
  home → external, no external → external.
- R5. Source and target belong to the **same legal entity** (D10); cross-entity concentration goes through the
  in-house bank. Refused at rule creation with the reason.
- R6. Same currency (FX in external sweeps is out of scope).
- R7. Seeded rules (D9): a migration points each seeded external rule's target at the **source entity's** home-bank
  account in the rule currency and creates an ACTIVE demo authority for each external source (format MT101). A rule
  whose source entity has no home-bank account in that currency is deactivated with the reason (no cross-entity
  fallback, D10).

### 3.3 Sizing and freshness (D6)
- R8. An external source is sized on its **last end-of-day statement balance** (booked), never on intraday figures or
  the ledger.
- R9. A **stale** source (no end-of-day statement for the last business day, statement intake R14) is skipped with
  "stale balance"; the execution records the skip.
- R10. A source with a sweep still pending (R12) is not swept again until it settles or fails — no double instruction
  against the same balance.

### 3.4 Execution (D2, D4, D8)
- R11. Per executed source the home bank produces one request for transfer in the authority's format: **MT101**
  (:20: reference, :50H: the account at the other bank, :57A: the home bank, :59: the home-bank account, :32B:
  amount, :30: execution date) or **pain.001** (Dbtr / DbtrAcct / DbtrAgt = the account at the other bank, Cdtr /
  CdtrAcct / CdtrAgt = the home-bank account), with the end-to-end reference the statement will carry back.
  Stored on the execution, viewable and downloadable.
- R12. Booking (D4, **at instruction, reversible**): the home-bank target is credited at once (bank balance and physical
  account follow as for any home-bank shadow); the external leg is recorded as pending (execution COMMITTED). The
  external shadow's bank balance is **not** changed (statement wins); its ledger shows the pending debit as such.
- R13. Fee (D8): the standard sweep fee (0.05%, min 5, max 500) **plus a flat charge per instruction** (default 25 in
  the account currency, configurable `vam.sweeps.external-instruction-fee`), charged at instruction from the target
  entity, refunded if the leg fails.
- R14. There is no SWIFT / host-to-host link: the instruction is generated, not transmitted; "the other bank rejected
  it" can be played from the portal (Mark rejected on the execution), as in bulk collections.

### 3.5 Confirmation (D5, D7)
- R15. On intake of an end-of-day statement for the external account, a pending leg is confirmed by a debit line with
  the instruction's end-to-end reference, else by a debit of the same amount with the instruction's execution date as
  value date (D5) → execution SETTLED, the line linked to the instruction.
- R16. Not confirmed within **2 business days** of the execution date (D7), or marked rejected → FAILED: the target's
  credit reversed (the target may go below its target balance; it may not be refused), the instruction fee refunded,
  alert to bank operations and the corporate.
- R17. A confirming line is not counted as a break (statement intake R8/R9 unchanged — the debit is in the statement's
  own lines).

### 3.6 Screens
- R18. Account (bank side): authority panel — register, activate, suspend, revoke; corporate sees it read-only.
- R19. Sweep execution: per source the instruction (format, reference, text), status COMMITTED / SETTLED / FAILED /
  skipped with reason, the confirming statement line; "Mark rejected" while pending.
- R20. Create / edit rule: external accounts offered only as sources with an ACTIVE authority; the refusal reason is
  shown (today it fails silently).
- R21. Fixes found in the demo recording that sit on this path: Today's Swept counts SETTLED and COMMITTED (it filters
  on SUCCESS only); per-rule history endpoint (500); execution "balance after" includes the fee.

## 4. Data model (indicative)

- `external_mandates`: + `instruction_format` MT101 / PAIN_001 (ddl-auto); the existing rail scope is ignored for
  these (rail is the request for transfer).
- `sweep_instructions` (existing, unused multi-bank v1 table, reused): + format, message text, rule, execution date,
  confirmed-by statement line, fee and target-credit refs. Its own statuses stand for the spec's: INSTRUCTED = pending,
  SETTLED = confirmed, REJECTED (marked rejected) / EXPIRED (window passed) = failed.
- `sweep_executions`: COMMITTED while any instruction is pending, SETTLED when all confirmed, FAILED / PARTIAL as now.
- Flyway (V55+): the seeded-rule re-point and demo authorities (R7) — data only.

## 5. Acceptance criteria

1. A new external account with no authority cannot be a rule source; the form shows why. An external account cannot
   be a target; a cross-entity rule is refused.
2. With an ACTIVE MT101 authority, a rule external → home bank (same entity) runs: an MT101 is produced, the home-bank
   target is credited, fee = standard + 25, execution COMMITTED.
3. The same with a pain.001 authority produces a pain.001.
4. The next end-of-day statement of the external account with the debit (by reference; and, separately, without the
   reference but same amount and value date) → execution SETTLED, no break.
5. No confirming line within 2 business days, or Mark rejected → FAILED, credit reversed, instruction fee refunded,
   alert.
6. Revoked / expired / over-limit authority, stale source, or a sweep already pending on the source → skipped with the
   reason; nothing instructed.
7. Seeded rules re-pointed to the source entity's home-bank account with demo authorities; the rest deactivated with
   the reason.
8. The demo walkthrough runs end to end on vam-five: link account → statement → authority → rule → run → MT101 →
   next statement confirms.

## 6. Out of scope (v1)

SWIFT / host-to-host transmission (instructions are generated, not sent); home → external and external → external
sweeps; cross-entity external sweeps (use the IHB); corporate self-service authorities; FX in external sweeps;
intraday-sized sweeps; per-corporate pricing; the other bank's own sweep products; notional pooling across banks.

## 7. Decisions (interview, 2026-11-12)

| # | Question | Decision |
|---|---|---|
| D1 | Which legs may be at another bank | **External → home only**: other-bank accounts are sources, the target is a home-bank account |
| D2 | Instruction format | **MT101 or pain.001**, chosen per authority |
| D3 | Who registers the authority | **Bank operations**; the corporate sees it read-only |
| D4 | When the home-bank side books | **At instruction, reversible**; the external leg pending until confirmed |
| D5 | How a leg is confirmed | **End-to-end reference, else amount + value date** on the other bank's statement |
| D6 | Sizing and stale balances | **Last end-of-day booked balance; stale → skipped** |
| D7 | Confirmation window | **2 business days**, then FAILED and reversed |
| D8 | Fees | **Higher for external: standard sweep fee + a flat charge per instruction**, refunded on failure |
| D9 | Seeded external rules | **Re-point to the home bank and seed demo authorities** (migration) |
| D10 | Cross-entity external sweeps | **Same entity only**; cross-entity goes through the in-house bank |
