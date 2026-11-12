# Notional pooling — specification

Status: **implemented** (2026-11-12). Decisions D1–D11 were taken in an interview; they are marked in the text and listed
at the end. Review that led to it: read-only code + data review, 11 Nov 2026.

## 1. Why

Today notional pooling is a set-up screen plus a "Calculate Interest" button (verified in code and local data):

| Today | Effect |
|---|---|
| Pool reference from an in-memory counter (`NotionalPoolService` `poolSequence`, starts at 1 on every restart); `pool_reference` is unique | The first two pool creations after each restart fail (NP-0001/0002 exist); the simulator's activate uses the same path |
| Each click: one day's interest at one typed rate (default 3.5%) on the current sum of member balances (`total × rate / 36500`) | Not end-of-day or value-dated, no debit rate, no day count per currency; the offered DAILY_AVERAGE / MONTH_END / TIER_BASED are ignored |
| Result added to `interest_savings_ytd` and members' `interest_allocation`; no ledger posting, no stored run, no once-per-day guard | Never on a statement; a second click doubles it |
| Shown as "Savings / interest benefit from pooling" | It is gross interest on the total, not the benefit of pooling |
| Management fee 0.01% of the balance (min 100, any currency) posted on **every** click to whichever member is first; 25 per member joining | The fee usually exceeds the interest (NP-0002: fee 1,519.20 vs ~166/day) and lands on an arbitrary member |
| Eligibility: home-bank PHYSICAL_MIRROR only; no check on currency, corporate, entity, duplicates or agreements | An API add can mix currencies (summed 1:1) and entities without a set-off agreement |
| Seeded pools: members at Chase/Citi/BNP/…, ROOT/TRANSACTION accounts; hard-coded YTD (POOL-ALBN-GBP 392,000 vs 83,090 allocated) | The demo shows multi-bank notional pooling, which no bank offers; numbers don't reconcile |
| Hero total sums every pool's currency under "AED"; list shows stored, stale totals; remove member hard-deletes; no close | Wrong figures; history lost; pools can't be wound down |

Kept: pools, members, the home-bank eligibility rule, the allocation strategies (as a basis), the interest engine
`InterestConfiguration` (credit/debit base rate + spread, day count, tiers, posting frequency) used by the IHB, and
the daily bank-balance snapshots per account (`shadow_balance_snapshot`, since 20 Jun 2026).

## 2. How it should work (target)

```
Set-up      Treasury creates a single-currency, full-offset pool (D1) for ONE legal entity (D8): currency, header
            account (D2), members = that entity's home-bank accounts in that currency. Rates from the interest engine
            (D3). Debit limits per member and a floor for the pool (D7). No fees (D9).
Daily       After end of day, one run per pool and value date (D5): each member's end-of-day balance (D4); pooled
            interest on the net at the pool's credit/debit rates; standalone interest each member would have had;
            benefit = pooled − Σ standalone (reported). Stored; re-running the same date changes nothing.
Month end   The month's pooled interest posted automatically (D11) to the members pro rata to contribution (D6).
Screens     Pool: net, members' balances, today's accrual, month-to-date benefit, run history. Member: its interest
            line on its statement.
```

## 3. Functional requirements

### 3.1 Structure and membership
- R1. Single-currency, full offset (D1): credit and debit balances offset, interest on the net. A pool has one
  currency; members must be in it (enforced in the backend, not only the UI).
- R2. Mandatory header account (D2): one member, chosen at set-up; it takes the rounding residue of each posting and
  is the account the pool's figures are reported against.
- R3. Members: active home-bank PHYSICAL_MIRROR accounts of the pool's corporate; one pool per account; joining and
  leaving are dated (`joined_date`, `left_date`), never deleted; history kept.
- R4. One legal entity per pool (D8): every member, and the header, belong to the pool's entity, so no cross-guarantee
  or set-off agreement is needed. A member of another entity is refused.
- R5. Pool lifecycle: ACTIVE → SUSPENDED → CLOSED (closing posts what is accrued first).

### 3.2 Balances and interest
- R6. Balance basis (D4): the member's end-of-day bank balance for the value date, from `shadow_balance_snapshot`
  (the daily snapshot of its shadow). A member with no snapshot for the date is flagged in the run and counted at its
  last earlier snapshot.
- R7. Rates (D3): one `InterestConfiguration` per pool (new target type POOL): credit and debit base rate + spread,
  day count (default by currency: ACT/360 for USD/EUR, ACT/365 for GBP/AED/SAR …), optional tiers, effective dates.
  The free-typed pool rate goes.
- R8. Pooled interest: on the pool's net balance (credit rate if ≥ 0, debit rate if < 0).
- R9. Standalone interest: each member on its own balance at the same grid (credit/debit), so benefit =
  pooled − Σ standalone is the real value of pooling. "Savings" means this benefit only.

### 3.3 Runs, posting, allocation
- R10. Runs (D5): one per pool and value date, stored in `pool_interest_calculations` with per-member lines;
  idempotent; scheduled after end of day, plus a manual run for a missed date range.
- R11. Allocation (D6), pro rata to contribution: when the net is ≥ 0, the day's pooled credit interest is shared by
  the members in credit, in proportion to their credit balances; when the net is < 0, the pooled debit interest is
  shared by the members in debit, in proportion to their debit balances. The benefit (R9) is reported, not posted.
- R11a. Posting (D5, D11): at month end, automatically, one interest movement per member for the month's allocated
  interest (credit or debit), on its statement; the run is marked posted. A correction is a new run for the dates plus
  a reversing posting, never an edit.
- R12. No pool fees in v1 (D9): the per-click management fee and the joining fee go.

### 3.4 Limits and controls
- R13. Debit limits (D7): a debit limit per member and a floor for the pool's net; a breach is flagged on the pool
  and in the run (not blocked — payments are not stopped here).
- R14. Month-end posting is automatic (D11); no approval step.

### 3.5 Screens and data
- R15. Totals per currency, never summed across currencies; the list shows live (or run-dated) totals.
- R16. Hierarchy "Pool interest" KPI shows month-to-date, labelled.
- R17. Pool reference generated by the database (no in-memory counter).
- R18. Existing pools and seed data (D10): NP-0001/0002 closed; the seeded pools re-seeded with eligible members
  (home-bank, same currency, one entity, a header) and their figures built by running the daily calculation over the
  snapshot history — no hard-coded YTD.

## 4. Data model (indicative)

- `notional_pools`: + header_account_id, legal_entity_id, interest configuration link (`interest_configurations`,
  target POOL), pool floor; status SUSPENDED/CLOSED used; reference from a sequence; rate field retired.
- `pool_members`: + debit_limit; `left_date` used on leave (no delete).
- `pool_interest_calculations` (exists, unused) + new `pool_interest_lines` (member, balance, standalone, allocated,
  posted ref).

## 5. Acceptance criteria

1. Restarting the backend never breaks pool creation.
2. Two runs for the same pool and date: one stored run, one set of postings.
3. A pool of +1,000,000 and −400,000 at credit 3% / debit 6% (ACT/365): pooled interest on 600,000 at 3%;
   standalone = 1,000,000 × 3% − 400,000 × 6%; benefit = the difference; shown as the benefit.
4. Month end: one posting per member for the month's pro-rata interest, automatically; Σ postings = Σ daily pooled
   interest (residue on the header); members see their line on their statement.
5. A member in another currency, at another bank, or of another entity is refused.
6. No figure sums currencies; demo pools reconcile with their runs; no fee is charged.
7. Member debit limit and pool floor breaches flagged in the run.

## 6. Out of scope (v1)

Multi-currency pooling; interest enhancement pools; multi-bank / overlay pooling; cross-entity pools and set-off
agreements; arm's-length allocation and benefit to header; pool fees; approval of postings; IAS 32 offset and Basel
III leverage-ratio reporting; withholding tax (OPTIMIZED_WHT stays a labelled stub); intraday pooling.

## 7. Decisions (interview, 2026-11-11)

| # | Question | Decision |
|---|---|---|
| D1 | Pool type | **Single-currency, full offset** |
| D2 | Header account | **Mandatory** |
| D3 | Where the rates come from | **Interest engine** (`InterestConfiguration`, credit/debit base + spread, day count, tiers) |
| D4 | Balance basis | **End-of-day snapshot** per member and value date |
| D5 | Calculation and posting cycle | **Daily accrual** (scheduled, idempotent), **monthly posting** |
| D6 | Who gets the interest | **Pro rata to contribution** (credit interest to members in credit; debit interest to members in debit); benefit reported only |
| D7 | Debit limits | **Member limit + pool floor**, breaches flagged |
| D8 | Cross-entity pools | **Single entity only** in v1; no agreements needed |
| D9 | Fees | **No fee** in v1 |
| D10 | Existing pools and demo data | **Re-seed** with eligible members; figures from runs over the snapshot history |
| D11 | Approval of postings | **Auto-post** at month end; corrections by re-run + reversal |
