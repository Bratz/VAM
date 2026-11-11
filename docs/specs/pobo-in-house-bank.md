# Payments on Behalf Of (POBO) through the In-House Bank — specification

Status: **implemented** (2026-11-11). Implementation plan: `tasks/todo.md` ("POBO through the In-House Bank"). Decisions D1–D10 were taken in an interview; they are marked in the text and
listed at the end.
Companion to `docs/specs/cobo-in-house-bank.md` (COBO, the collection side).

## 1. Why

Today there are two POBO mechanisms that do not meet (verified in code and data, 11 Nov 2026):

| | What it is | Moves money? |
|---|---|---|
| **A. The "POBO" workflow** | Payable flag `isPobo` / paying entity; **Request POBO** (→ *Pending POBO*); treasury approval (no screen calls it); `PoboExecutionService`; POBO authorisations (daily/monthly/single limits, vendor list); previews with hardcoded fees; one auto-approved "recharge" per payment | **No** — execution refuses ("Not available"); a treasury-approved payable fails even earlier (`canRequestPobo`) |
| **B. Implicit IHB routing** | Paying any payable / ISO payment **from an IHB current account** runs `TransactionService.makeIhb6LegPoboPayment` (or the 4-leg fallback) | **Yes** |

Problems found:

| Problem | Effect |
|---|---|
| Path A never pays | Payables sit in *Pending POBO* with no way forward (2 locally) |
| Path B ignores the payable's chosen paying entity and the POBO limits | Treasury is taken from the account's `treasuryPoolVaId` |
| pain.001: `Dbtr` = subsidiary, `DbtrAcct` = the VA's internal UUID, no `UltmtDbtr` anywhere | Not a payable message; the supplier's bank cannot see who pays |
| 6-leg posting debits treasury's settlement VA without a ledger row | A balance moves with no transaction to explain it |
| IC receivable mirror moves only on POBO legs | Credits into the subsidiary's IHB current account (collections) don't reduce treasury's claim — MNC Dubai: IC receivable 3,369 vs IHB current +14.48 |
| Per-payment "recharge" (auto-approved; creation errors only logged) settled through netting debits the IHB current account again | The subsidiary can be charged twice for one payment |
| Fees: real path takes the configured POBO fee per payment; previews add a hardcoded 25 + 0.25% "FX markup" on non-AED | Preview and posting disagree; preview fees are invented |
| Payables file ingest passes "BANK_TRANSFER" as a payment *channel* | Every ingested payable fails at payment |
| (Separate) a "cash concentration sweep fee" AED 5 hits MNC Dubai's IHB current account about every 5 minutes | Fees with no sweep behind them |

Kept: the 6-leg in-house posting (IHB current −, treasury IC receivable +, treasury cash −), real FX conversion on the
payment path (`FxPaymentService`), IHB interest accrual, the subsidiary's payable marked paid.

## 2. How it should work (target)

```
Set-up (once)    Subsidiary X is enrolled: "paid by Treasury Centre T from account A" for currency C.
Payable          X approves its payable as usual (its own AP approval). Enrolled + currency C -> POBO automatically.
Payment          T's account A pays the supplier once. pain.001: debtor account = A's bank IBAN, UltmtDbtr = X.
In-house         X's IHB current account -amount ; T's IC receivable from X +amount ; T's cash -amount.
                 The IC receivable always equals X's position (also when X's account is credited by anything else).
Interest/limits  On X's IHB balance, within X's IHB credit limit; no per-payment recharge.
Subsidiary view  Payable PAID; one line per payment on X's IHB statement (supplier, invoice number).
```

## 3. Functional requirements

### 3.1 Enrolment (replaces Request POBO + treasury approval)
- R1. A subsidiary is enrolled per currency: `paidBy = treasury centre`, `paymentAccount = T's account that pays`
  (backed by a real bank account), `effectiveFrom/To`. (D1, D2)
- R2. Enrolment requires X's IHB current account in that currency with treasury's IC receivable mirror and the IHB
  settlement accounts the posting needs; refused with a clear message otherwise.
- R3. Managed on the In-House Bank page next to COBO enrolments.

### 3.2 Paying a payable
- R4. An approved payable of an enrolled subsidiary in that currency is POBO automatically: Pay Now / scheduled /
  batch payments run the in-house posting with T as payer (from the enrolment, not the source account's link). (D3)
- R5. Explicit per-payable opt-out "pay directly from <X>'s own account". (D4)
- R6. A payable in a currency the subsidiary isn't enrolled for is paid directly from the subsidiary's own
  account, and the payable says why (D5 — same as COBO).
- R7. The Create Payable POBO toggle / paying-entity picker and the list's Request POBO, treasury approval and
  execute endpoints are retired; existing *Pending POBO* / *POBO approved* payables are handled at cut-over (D9).

### 3.3 Payment message
- R8. pain.001 for a POBO payment: `Dbtr` = treasury centre T, `DbtrAcct` = T's payment account's real IBAN (never
  a VA UUID), `UltmtDbtr` = subsidiary X (D6, mirroring the COBO pain.008); remittance carries the supplier's invoice
  number.

### 3.4 Ledger integrity
- R9. Every balance movement of the posting has a ledger row (fixes the unrecorded treasury settlement debit).
- R10. Treasury's mirrors for X always equal X's IHB current position P: when P < 0, IC receivable = −P and IC
  payable = 0 (X owes treasury); when P ≥ 0, IC payable = P and IC receivable = 0 (treasury owes X). Kept on every
  movement of X's IHB current account (collections, sweeps, fees, interest, POBO, COBO), not only on POBO/COBO legs.
- R11. No per-payment recharge. Netting / settlement of IHB positions settles the position once; it never debits
  the IHB current account a second time for a payment already posted there. (D8)

### 3.5 Limits, interest, fees
- R12. One limit: X's IHB credit limit, checked on the real payment path before money moves; over the limit the
  payment is refused; the preview shows the headroom. POBO authorisation limits are retired. (D7)
- R13. Interest on X's IHB balance at configured rates (defaults labelled, as today).
- R14. No POBO fee in v1 (D10): none is taken, previews show none (the hardcoded 25 / 0.25% go).

### 3.6 Fixes along the way
- R15. Payables file ingest: payment channel/method mapping fixed so ingested payables pay.
- R16. Sweep fee charged only when a sweep actually moves money (investigate the 5-minute charge).

## 4. Data model (indicative)

- New `pobo_enrolment` (as `cobo_enrolment`): subsidiary, currency, payer (treasury centre), payment account,
  effective from/to.
- `payables`: `is_pobo`, `payment_via_entity_id`, route POBO set from the enrolment; `pobo_request_status` legacy.
- Recharges of type POBO_PAYMENT become legacy (D8).
- One-off repair (D8): set every mirror to the R10 rule from its IHB current position, mark existing POBO_PAYMENT
  recharges settled-by-IHB (excluded from netting); report what changed; safe to re-run.
- Cut-over (D9, as COBO): an action on the In-House Bank POBO tab — preview, then *Pending POBO* / *POBO approved*
  payables back to *Approved* (they pay normally, POBO if enrolled); re-running changes nothing.

## 5. Acceptance criteria

1. Enrolled subsidiary, approved AED payable, Pay Now: one payment from T's account; X IHB current −amount; T IC
   receivable +amount; every movement has a ledger row; payable PAID; no recharge created.
2. pain.001: `DbtrAcct` is T's IBAN; `UltmtDbtr` (or debtor) names X per D6.
3. A collection credited to X's IHB current account reduces T's IC receivable by the same amount.
4. A payment beyond X's IHB credit limit is refused before money moves; the preview shows the headroom.
5. Opt-out pays from X's own account directly; not enrolled for the currency -> per D5.
6. Netting of a period with POBO payments never debits X's IHB current account twice.
7. Ingested payables file pays; no sweep fee without a sweep.
8. Cut-over (D9) leaves no payable in *Pending POBO* / *POBO approved*; mirrors equal positions after D8's repair.

## 6. Out of scope (v1)

Payment-factory batching by bank cut-off; multiple payment accounts per enrolment; per-vendor enrolment.

## 7. Decisions (interview, 2026-11-11)

| # | Question | Decision |
|---|---|---|
| D1 | Enrolment granularity | Per subsidiary **and currency** (as COBO) |
| D2 | Who may pay on behalf | Treasury centre only |
| D3 | Release | The subsidiary's own AP approval; no extra treasury step |
| D4 | Per-payable opt-out | Allowed: "pay directly instead" |
| D5 | Payable in a currency not enrolled | Paid directly from the subsidiary's account, with the reason (same as COBO) |
| D6 | pain.001 | `Dbtr` = treasury, `DbtrAcct` = treasury's IBAN, `UltmtDbtr` = subsidiary |
| D7 | Limits | The subsidiary's IHB credit limit only; refuse when exceeded; POBO authorisation limits retired |
| D8 | Existing recharges / drifted mirrors | One-off repair: mirrors set from positions (R10 rule), POBO recharges closed as settled-by-IHB |
| D9 | Stuck payables at cut-over | Same as COBO: preview-then-run action; back to Approved |
| D10 | Fees | No POBO fee in v1 |
