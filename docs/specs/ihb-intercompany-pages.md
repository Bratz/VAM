# In-House Bank and Intercompany pages — specification

Status: **decided, planned** (2026-11-13). Plan: `tasks/todo.md` ("In-House Bank and Intercompany pages"). Decisions D1–D10 were taken in an interview; they are marked in the text and listed
at the end. Input: the functional review `docs/reviews/ihb-intercompany-functional-review.md` (13 Nov) and a code check
of what intercompany capabilities exist outside the in-house bank (13 Nov).

## 1. Why

Two pages show overlapping, contradictory views of the same money, and neither answers the treasurer's core questions.

| Today | Effect |
|---|---|
| In-House Bank (1,331 lines) and Intercompany Dashboard (1,715 lines) both have Overview, Entities, POBO and COBO tabs | Two answers to one question |
| In-House Bank reads the IHB current accounts; the Intercompany Dashboard reads the legacy `intercompany_transactions` table | The dashboard said "all settled" while the ledger showed treasury owing AED 1,114.49 |
| In-House Bank shows set-up (entities, limits, spreads, accounts, enrolments) | No position per entity and currency, no movements, statement, interest history or export |
| Intercompany Dashboard: Settle posts to the ledger in one click; Bilateral Settlement / Add to Netting dead or "Coming soon"; filters, drill-down and dates broken | A treasurer can neither trust nor act on it |
| Entity cards show spreads (+0.75% / +0.55%) the interest run never applies; every account is charged 5.50% / 4.50% | Rates on screen are not the rates charged |

What exists outside the in-house bank (code, not comments):

| Capability | In code | Notes |
|---|---|---|
| IC loans between entities | **No** | IHB loans/deposits retired (9950ceb); credit facilities are bank lines only |
| Bilateral IC invoices | **Partly** | A payable/receivable flagged intercompany against a linked legal entity (via an intercompany party) gets route INTERCOMPANY, netting-eligible, excluded from POBO (local: 1 IC payable, 4 IC receivables) |
| Multilateral netting without an IHB | **In code, never exercised** | Netting cycles take IC payables, IC receivables and POBO/COBO recharges; each entity's net settles on its IHB current account, else its settlement/transaction account, by moving ledger balances only. All 6 local cycles unsettled; every entry is a POBO recharge |
| Bilateral settle | **Both** | IHB IC receivable/payable accounts when the pair has them, else the legacy table's position |

## 2. How it should work (target)

```
In-House Bank   the IHB's book (D1): position matrix per entity and currency with the IHB's funding beside it;
                movements with drill-down and per-account statements with export; interest history at the rates
                actually charged; limit/rate audit trail (D7, D10). Set-up actions stay where they are (D8).
Intercompany    the group's who-owes-whom (D1, D2): per pair and currency, IHB positions from the ledger, open
                intercompany invoices and POBO/COBO recharges awaiting netting as separate lines; zero-sum control per
                currency; a reporting-currency total (D9). No settle action: "add to netting cycle" hands off (D4).
Netting Cycles  the only place intercompany positions are settled (D4, D6). Settlement moves ledger balances when both
                accounts sit on the same bank account, otherwise produces real payments for the net amounts (D5).
Legacy table    intercompany_transactions retired: no reads, no writes, endpoints removed, table dropped (D3).
```

## 3. Functional requirements

### 3.1 Page split (D1)
- R1. Each page has one job, with no tab that duplicates the other. POBO, COBO, entities and the IHB's movements live on
  In-House Bank only; the Intercompany page has no POBO/COBO/Entities/All Transactions/Settlement tabs.
- R2. Old links and bookmarks to removed tabs land on the replacement section.

### 3.2 Intercompany page (D2, D9)
- R3. Who owes whom per pair and currency, three kinds of line shown separately (D2):
  - IHB positions from the ledger (IC receivable/payable, `/intercompany/positions`);
  - open intercompany invoices: payables/receivables flagged intercompany, not yet settled;
  - POBO/COBO recharges awaiting netting.
- R4. A zero-sum control per currency on the IHB positions (the group nets to zero; any difference is shown as a break).
- R5. Per pair, what is eligible for netting and "add to cycle" (`/netting-eligibility`, `/add-to-netting/{cycleId}`),
  opening the cycle in Netting Cycles.
- R6. Amounts per currency, plus one total in the corporate's reporting currency at the day's rate, labelled as
  converted with the rate and its date (D9); corporate screens never show FX mid/margin/floor.
- R7. Scoped to the selected corporate on every call (review fix 1).

### 3.3 Settlement (D4, D5, D6)
- R8. No settle action on the Intercompany page (D4): bilateral settle (`/settle-bilateral`) removed from the UI and the
  API; every settlement is a netting cycle (build, approve, settle).
- R9. Intercompany invoices settle only through netting (D6), as today.
- R10. Netting settlement (D5): per entity net amount, a ledger transfer when payer and receiver accounts are held on the
  same physical bank account (or are IHB current accounts); otherwise an outward payment of the net amount from the
  payer's account to the receiver's, through the existing outward payment path, and the cycle is settled when those
  payments are booked.
- R11. Settlement preview in Netting Cycles before it posts: per entity what is debited/credited and which legs become
  payments.

### 3.4 Legacy intercompany_transactions (D3)
- R12. Retired: nothing reads it, nothing writes it (POBO/COBO/recharge flows stop creating rows where the ledger or the
  recharge already records the event), its endpoints (`/intercompany/transactions*`, `/unsettled`, `/stats`,
  `/settlement/*`, `/cobo/setup`, `/cobo/{ref}/collect`, `/activity-report` where built on it) go, and a migration drops
  the table after confirming nothing references it. Anything still needed is recomputed from the ledger, payables,
  receivables or recharges.

### 3.5 In-House Bank page (D7, D8, D10)
- R13. Position matrix: entities × currencies — current account balance, credit limit, used, headroom, accrued interest
  (IhbExposure and the accounts) — with the IHB's funding (treasury's bank accounts) beside it.
- R14. Movements: sweeps, POBO, COBO, interest and settlement movements on IHB current accounts, filterable by entity,
  currency, type and date, with drill-down to the transaction; per-account statement for a period (opening/closing
  balance, value dates) with export (CSV; PDF/camt.053 per existing statement generation if it fits).
- R15. Interest history: per account and period — day count, rate actually charged, accrued and posted amounts.
- R16. Limit/rate audit trail: who changed a credit limit, rate or spread, from what to what, when (existing audit log).
- R17. Rates shown are the rates the interest run charges (D10); unused spreads are not shown as if applied.
- R18. Set-up actions stay on the page as today (D8) until roles exist.
- R19. No internal jargon (no "mirror", config ids or VA-only identifiers a corporate user doesn't know); currency on
  every amount.

## 4. Acceptance criteria

1. For TestMNC on vam-five data, Intercompany shows treasury owing MNC-UK AED 1,100.01 and MNC Dubai AED 14.48, the AED
   control nets to zero, open IC invoices and recharges as separate lines, and a reporting-currency total with its rate
   date; never "all settled" while anything is open.
2. Another corporate never sees TestMNC's data.
3. No tab or figure appears on both pages; old links land on the replacement.
4. There is no settle action outside Netting Cycles; `/settle-bilateral` is gone.
5. A netting cycle with a pair on different bank accounts produces outward payments for the net; a pair on the same bank
   account (or IHB accounts) is a ledger transfer; the preview shows which.
6. `intercompany_transactions` has no readers or writers and is dropped; POBO/COBO/recharge flows still work.
7. In-House Bank shows the position matrix, movements with drill-down, a statement with export, interest history and the
   limit/rate audit trail.
8. Rates on screen equal the rates the interest run charges.

## 5. Out of scope (v1)

IC loans between entities; paying a single IC invoice outside netting (D6); roles, four-eyes and moving set-up to a bank
section (D8); transfer-pricing documentation beyond the interest history; scheduled reports.

## 6. Decisions (interview, 2026-11-13)

| # | Question | Decision |
|---|---|---|
| D1 | Page split | **Two pages, one job each**: In-House Bank = the IHB's book; Intercompany = group who-owes-whom |
| D2 | Intercompany scope | **Everything**: IHB positions, open IC invoices and POBO/COBO recharges awaiting netting, as separate lines |
| D3 | Legacy `intercompany_transactions` | **Retire fully**: stop reading and writing, remove endpoints, drop the table |
| D4 | Bilateral settlement | **Only through Netting Cycles**; bilateral settle removed |
| D5 | Netting without an IHB | **Payments when banks differ**: ledger transfer on the same bank account, outward payments otherwise |
| D6 | One IC invoice outside netting | **Netting only** |
| D7 | In-House Bank v1 | **Position matrix, movements + statement, interest history, limit/rate audit trail** |
| D8 | Set-up actions without roles | **Keep as is** until roles exist |
| D9 | Consolidated figure | **Per currency plus a reporting-currency total**, labelled with rate and date |
| D10 | Rates shown | **Rates actually charged**; unused spreads not shown as applied |
