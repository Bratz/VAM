# Functional review: In-House Bank and Intercompany Dashboard

Reviewed 2026-11-13 on vam-five (build 0bcd487), read-only, from a group treasurer's point of view: someone who runs an
in-house bank and knows SAP In-House Cash, Kyriba and the big banks' portals. Corporates checked: TestMNC (the only
corporate with IHB entities on vam-five), plus Brato, Helios, Al Mawarid and TestShop (none has IHB entities, so they
were used to test corporate scoping), and "All Corporates".

> **Fixed (1–4), 13 Nov, uncommitted.**
> 1. C3: `/intercompany/entities`, `/entity-pairs`, `/stats`, `/transactions`, `/positions`, `/position-summary` and
>    `/pobo/recharges/pending` return only the asked corporate's group and refuse a request without `corporateId` (400,
>    as KYC and payables do). The dashboard passes the corporate everywhere, and now reads the recharges list it was
>    dropping.
> 2. C1, C2, C6, C7: tiles, the settled state, the pair cards and POBO/COBO figures come from the ledger, per currency
>    (who owes whom from the IC mirrors; POBO = the IHB accounts' POBO_DEBITs, principal with fees apart; COBO = IHB_COBO
>    credits less returns). The duplicate "Intercompany Virtual Accounts" cards and the legacy "Pending" counts are
>    gone; the pair Settle button is withdrawn until H4.
> 3. C5: V59 takes Dubai's AED 1,986.1114 debit accrual back (to 0, credit 0.0013 on its genuine 0.96); sweep and COBO
>    reversals now take back the interest accrued on what they reverse.
> 4. C4: V60 sets MNC-UK's IHB currency to AED; an entity's first IHB current account sets its IHB currency and later
>    ones must match. Both pages show the limit in that currency.

Ledger truth used for cross-checks (vam-five, 13 Nov):

| Account | Balance (AED) | Source |
|---|---|---|
| IHB-MNC-UK-AED-0002 (MNC-UK IHB current) | +1,100.01 credit | `/ihb/corporate/{id}/current-accounts`, VA list |
| IHB-MNC-UAE-DUBAI-AED-0001 | +14.48 credit | same |
| IHB-MNC-UAE-AED-0001 | 0.00 | same |
| ICP-MNC-TREASURY-MNC-UK-AED (treasury owes UK) | 1,100.01 | `/virtual-accounts/corporate/{id}/category/INTERCOMPANY` |
| ICP-MNC-TREASURY-MNC-UAE-DUBAI-AED | 14.48 | same |
| ICR-… (both) | 0.00 | same |
| `/intercompany/positions` (ledger-based, unused by the page) | UK −1,100.01, Dubai −14.48 | API |

So the group position is: **MNC Treasury owes MNC-UK AED 1,100.01 and MNC Dubai AED 14.48**, and it nets to zero across
the group (subsidiary credits = treasury IC payables). The ledger is consistent. The pages mostly don't show it.

---

## Verdict

**In-House Bank: not yet a usable IHB cockpit.** It shows configuration (entities, limits, spreads, three account cards,
POBO/COBO enrolments) but not the in-house bank's book. There's no per-entity net position across currencies, no
movements, no statement, no interest history and no export. The headline per-entity figure is wrong for MNC-UK
(currency mismatch). The entity cards show rate spreads that are not the rates actually charged. The current-account
cards do give correct balances and rates, and they are the most trustworthy part of the page.

**Intercompany Dashboard: misleading, and it should not be shown to a corporate in its current state.** It is built on
the `intercompany_transactions` table, not the ledger. Every headline figure (POBO/COBO volume, payables, receivables,
net, pairs) is AED 0.00 and says "All intercompany positions are settled". On the same page, the Settlement tab lists IC
Payable accounts holding AED 1,114.49 that treasury owes subsidiaries. Entities and Entity Pairs leak TestMNC data into
every other corporate. All amounts are hard-labelled AED. Several buttons and filters do nothing, and the one live money
action (Settle) runs without confirmation and records an entity code as the approver.

---

## Findings

### Critical: wrong money or misleading figures

**C1. The IC Dashboard reports "everything settled, 0 outstanding" while treasury owes subsidiaries AED 1,114.49.**
- *Seen:* TestMNC, Overview "Corporate Position Summary": Total Payables AED 0.00, Total Receivables AED 0.00, Net
  Position AED 0.00. Entity Pairs: three cards marked "Settled", "MNC-UK receives AED 0.00 from MNC-TREASURY". Settlement
  tab: "All intercompany positions are settled". Further down the same tab, "Intercompany Virtual Accounts" shows
  ICP-MNC-TREASURY-MNC-UK-AED at AED 1,100.01 and the Dubai ICP at AED 14.48.
- *Expected:* outstanding IC balances per pair and currency, taken from the IC Receivable/Payable mirrors (R10). A
  treasurer uses this screen to decide what to settle. "Settled" while money is owed is the worst possible answer.
- *Evidence:* `IntercompanyTransactionService.getCorporatePositionSummary` (l.1043) and `getEntityPairs` (l.230) sum
  `IntercompanyTransaction` rows with status PENDING/PROCESSED. All 37 TestMNC rows are SETTLED. The ledger-based
  `GET /intercompany/positions` exists (l.1271) and returns UK −1,100.01 and Dubai −14.48, but the page never calls it.
- *Fix:* drive tiles, pairs and the settlement list from `/intercompany/positions` (mirror balances, per currency), and
  retire the transaction-row sums.

**C2. Current POBO/COBO flows never reach the IC Dashboard: POBO/COBO volume and count show 0 while flows exist.**
- *Seen:* hero "POBO Volume AED 0.00 · 0 payments on behalf of subsidiaries", "COBO Volume AED 0.00", POBO Count 0, COBO
  Count 0. The POBO tab says "Total POBO 0", and directly below it the table lists 17 POBO rows
  (ICR-IHBPOBO-…, AED 150 / 356 / 220 …).
- *Expected:* volumes and counts that match the flows. Example: COBO INV-202611-5497 from Gulf Star Logistics, AED 1,000,
  credited to IHB-MNC-UK-AED-0002 on 11 Nov (ledger `/transactions`) appears nowhere on the page.
- *Evidence:* `IntercompanyService.getStats` (l.492) counts only `POBO`/`POBO_PAYMENT`/`COBO`/`COBO_COLLECTION` types. IHB
  POBO books `IC_RECEIVABLE`+`IC_PAYABLE` pairs (`TransactionService.createIntercompanyTransactionRecord`). Enrolment
  COBO (`CoboEnrolmentService`) writes ledger entries and mirrors but no IC transaction row.
- *Fix:* compute flow stats from ledger movements on IHB current accounts (POBO_DEBIT / COBO credits), per currency.

**C3. Corporate scoping is broken: other corporates see TestMNC's IHB entities and IC pairs.**
- *Seen:* IC Dashboard on TestShop: "Entities 4", "Entity Pairs 3", all TestMNC (MNC-TREASURY ↔ MNC-UAE …). Same for
  Brato, Helios and Al Mawarid via the API. The strip on the same TestShop page says "Entities 0" and "Active Entities 3".
- *Expected:* a corporate only ever sees its own group. For a bank portal this is a confidentiality issue as well as a
  correctness one, and the pair cards carry a Settle button.
- *Evidence:* `IntercompanyController.getEntitiesWithPositions` accepts `corporateId` but calls
  `getEntitiesWithPositions(status, entityType)`, which reads `findByIhbEnabledTrue()` + `findByCanLendTrue()` globally.
  `getEntityPairs(corporateId)` loads the corporate's entities and then iterates `transactionRepository.findAll()`
  without using them. `GET /pobo/recharges/pending` (Recharges tab) takes no corporate at all.
- *Fix:* filter all three by the corporate's entity ids, server-side.

**C4. MNC-UK's IHB exposure, limit and utilisation are computed in GBP, but its only IHB account is in AED.**
- *Seen:* IHB page, MNC UK Ltd card: "IHB debit balance GBP 0.00, Credit Limit GBP 1,000,000.00". The Current Accounts
  tab shows IHB-MNC-UK-AED-0002 with "Credit Limit AED 1,000,000.00". IC Dashboard Entities tab shows the same limit as
  "AED 1.0M". One limit, two currencies, on two pages.
- *Expected:* exposure measured on the accounts the entity actually draws on. Today, if MNC-UK overdraws its AED account
  to the limit, the card still shows GBP 0.00 / 0% with no warning.
- *Evidence:* entity `ihbCurrency = GBP`. `IhbExposure.of` keeps only accounts where
  `ccy.equals(va.getCurrencyCode())`, so AED accounts are dropped (its own ponytail note names this ceiling). The same
  figure also drives limit-change and disable checks.
- *Fix:* short term, refuse an IHB account in a currency other than the entity's IHB currency (or flag it on the card).
  Proper fix: exposure per currency, or converted at the bank's rate against a limit in one stated currency.

**C5. MNC Dubai carries AED 1,986.11 accrued debit interest that comes from an erroneous sweep that was later reversed.**
- *Seen:* Current Accounts tab, MNC Dubai Branch: balance AED 14.48 CREDIT, "Accrued Interest: Debit AED 1,986.11, Net
  AED −1,986.11".
- *Evidence:* ledger for IHB-MNC-UAE-DUBAI-AED-0001: 15 Sep SWEEP_OUT 1,000,000.96 (0.96 → −1,000,000), 1 Oct REVERSAL
  "Reversal of overdrawing sweep SWP17894952000804047". 1,000,000 × 5.5% / 365 ≈ AED 150.7 a day, so roughly 13 days of
  interest on an overdraft that should never have existed. The reversal put back the principal, not the interest. It
  will post at the next run (`postInterestForCurrentAccounts`; the scheduler question is in the debt ledger).
- *Expected:* an error reversal also reverses the interest accrued on it. A treasurer would dispute this charge
  straight away.
- *Fix:* recompute Dubai's accrual from the corrected balance history before posting, and make sweep reversals
  value-dated so the accrual follows.

**C6. Every amount on the IC Dashboard is labelled AED, whatever its currency, and sums mix currencies and fees.**
- *Seen:* hero volumes, the Outstanding / Payables / Receivables / Net tiles, pair cards and Entities tab limits all
  pass a literal `'AED'` (`IntercompanyDashboardPage.tsx` l.536, 541, 551, 559, 957, 962, 1100–1102, 1189–1191,
  1283–1285, 1329, 1670, 1674). The backend sums across currencies and stamps `currency("AED")`
  (`getCorporatePositionSummary` l.1090, `getBilateralPosition` l.370). Volumes use `netAmount` (amount + fee), so
  "volume" includes charges (a POBO of AED 150 counts as 175).
- *Expected:* figures per currency, or converted with the rate shown. Principal and fees kept separate.
- *Fix:* group by currency end-to-end and render the row's own currency. Report fees as their own figure.

**C7. Pair cards can name the wrong creditor.**
- *Seen/Code:* backend returns `netPosition` as an absolute value plus `netCreditor`/`netDebtor` ids
  (`getEntityPairs` l.283). The card picks the creditor with `pair.netPosition > 0 ? entity1Code : entity2Code`
  (`IntercompanyDashboardPage.tsx` l.500). Any non-zero position is therefore shown as "entity1 receives from entity2",
  even when entity2 is the creditor. Today all pairs are 0, so the cards read "MNC-UK receives AED 0.00 from
  MNC-TREASURY", which is still the wrong direction against the ledger.
- *Fix:* use `netCreditor`/`netDebtor` from the API.

### High: a treasurer can't do or trust a core task

**H1. The IHB page has no net position per entity per currency, and credit balances are invisible on entity cards.**
- *Seen:* entity cards and the "Position" modal show only "IHB debit balance". MNC-UK (AED 1,100.01 credit) and Dubai
  (AED 14.48 credit) both read 0.00. The Position modal calls `/ihb/entities/{id}/position`, discards the result
  (`const [, setEntityPosition]`, l.428) and re-renders the card's own fields. There's no account list, no balances and
  no movements.
- *Expected:* per entity, each IHB account with its balance (credit and debit), limit, available amount, accrued
  interest and last movement. This is the first question an IHB treasurer asks.
- *Fix:* make Position a real drill-down built from `getCurrentAccountsByCorporate` filtered by entity.

**H2. Rate spreads on entity cards are not the rates charged.**
- *Seen:* all three borrowers show "Borrowing +0.75%". Treasury shows "Lending Spread (Earns) +0.55%". The banner shows
  "Lending 5.50% / Deposit 4.50%, Base EIBOR 5%", so the treasury spread is 0.50 (API `treasuryLendingSpread 0.5`).
  Every account charges a debit rate of 5.50%.
- *Evidence:* `calculateInterestForAccount` uses only `va.getEffectiveDebitRate()/getEffectiveCreditRate()`. Entity
  spreads don't enter the calculation. The create modal also says "Override rates take precedence over entity spreads",
  which implies spreads apply otherwise.
- *Expected:* one rate per account (base + spread = all-in) and one source. Three different spreads for the same thing
  make the treasurer distrust every rate on the page.
- *Fix:* drop the entity spread display, or apply it in the calculation and show the all-in rate per account.

**H3. No movements, statements, drill-down or export on the IHB page. IC Dashboard drill-downs are dead.**
- *Seen:* IHB page: no transaction list for any IHB account, no statement, no download. IC Dashboard: the eye icons on
  POBO/COBO rows have no handler (l.1169, 1260). All Transactions "view" calls `console.log` (l.1612). Pair "Details"
  and Settlement "View" set state nothing reads (`const [, setSelectedPair]`, l.683).
- *Expected:* click any balance or figure and see the postings behind it, with an account statement
  (camt.053/PDF/CSV) per IHB account.
- *Fix:* link account cards to the existing account-transactions view, and wire or remove the dead icons.

**H4. One-click bilateral settlement with no preview or confirmation, and no real approver.**
- *Seen:* Entity Pairs/Settlement "Settle" calls `POST /intercompany/settle-bilateral` straight away (l.785). That
  service posts ledger entries (`settleBilateralPosition`). `settledBy` is `currentEntity.entityCode`. In the session
  observed, the header entity was ECOM-HOLDING (a TestShop entity) while the page showed TestMNC, so a TestShop entity
  code would be recorded as approver of a TestMNC settlement. Meanwhile the Overview "Bilateral Settlement" quick action
  is disabled "Coming soon", and the Settlement tab's own "Bilateral Settlement" and "Add to Netting" buttons have no
  onClick.
- *Expected:* a preview of amounts by currency and the accounts debited and credited, a confirmation, the maker
  identity, and ideally four-eyes. (No roles yet, noted once: settlement, enable/disable IHB, interest posting and
  account creation are all bank-side actions shown to the corporate.)
- *Fix:* use the existing `/settlement/calculate` as a preview step, confirm, record the user, and remove the dead
  duplicates.

**H5. The ad-hoc "COBO" modal on the IC Dashboard is a second, broken COBO alongside the enrolment-based one.**
- *Seen:* header button "COBO" and the Quick Action "Setup COBO Collection" open "Collect On Behalf Of (COBO)" with
  "Execute COBO". The subsidiary list includes non-IHB entities (MNC-HOLDING, MNC-USA, MNC-UAE-ABUDHABI). There's no fee
  disclosure, although the backend charges 0.1% (min 10, max 500, `calculateCharges`).
- *Evidence:* `setupCobo` is annotated `@Transactional(readOnly = true)` but saves the transaction and creates a VIBAN.
  With Hibernate's manual flush in read-only transactions the row is likely never written, while the UI shows
  "Transaction Successful" (read from code, not tested, since it is a write). Even if saved, `/cobo/{ref}/collect`
  always throws (debt ledger: "/cobo/{ref}/collect always throws").
- *Fix:* remove the ad-hoc COBO from this page and point to In-House Bank › COBO enrolments.

**H6. Interest: there's no view of what accrued, when, or what was posted.**
- *Seen:* account cards show accrued credit/debit to the fils and the rates. There's no accrual period, last accrual
  date, last posting date or amount, next posting date, day count (ACT/365 is in the API but not shown) or history. The
  Treasury Rates API says settlement is MONTHLY. The scheduler comment says posting runs 00:30 daily but posts only for
  the previous month end.
- *Expected:* an interest statement per account per period (balance × rate × days), which is what subsidiaries' local
  finance teams book and what tax/transfer-pricing teams need for WHT and arm's-length evidence.
- *Fix:* add an interest tab: accrual ledger per account and posted entries, exportable.

**H7. Limit warnings only exist as a bar colour, and the two pages use different thresholds.**
- *Seen:* IHB page: `limitWarning` ≥ 80%, breached ≥ 100% (`IhbExposure`). IC Dashboard Entities tab recomputes on the
  client: red > 80%, amber > 50% (l.1659). There's no list of entities near or over their limit and no alert. The
  treasury centre (lender) gets a "Credit Utilization" bar against a 10,000,000 "limit" that has no meaning for a
  lender.
- *Fix:* use the API's `limitWarning/limitBreached` everywhere, add a "near limit" filter or banner, and hide limits
  for lenders.

**H8. Filters that do nothing and a hidden 50-row cap.**
- *Seen:* the status selects on POBO, COBO and All Transactions, and the type select on All Transactions, have no
  value/onChange (l.1123, 1202, 1566, 1572). The page fetches only the first 50 IC transactions (`size: 50`) and builds
  POBO, COBO, Recent Settlements and the CSV export from those 50, client-side. The entity filter changes only All
  Transactions. Tiles, pairs and entities ignore it.
- *Fix:* send filters to the API (it already supports type, status and entityId), paginate, and either apply the
  entity filter to every tab or label it as a transactions filter.

**H9. Transaction dates are missing everywhere on the IC Dashboard.**
- *Seen:* every row in POBO, All Transactions and Recent Settlements shows "-" for date. "Settled At" is "-" on all
  settlements.
- *Evidence:* `mapToTransactionResponse` maps `createdAt` from an entity-local field (`IntercompanyTransaction.java`
  l.201) that is null for all 37 rows, and never maps `settledAt`, `settlementRef` or `viban`. That is also why the COBO
  VIBAN column could only ever be "—".
- *Fix:* map the audited `BaseEntity` created timestamp and the settlement fields.

### Medium: UX, terminology, consistency

**M1. System jargon shown to customers.**
- Create Current Account modal: "Creates a TRANSACTION VA with `ihbParticipant=true` … The IHB flag allows any
  operational VA to participate in treasury interest schemes". "Only entities with canBorrow=true can have IHB current
  accounts". The success alert reads "(TRANSACTION VA with ihbParticipant=true)".
- Account cards: a "TRANSACTION" category chip, "IHB Participant" badge and "Config: d36d1609..." (a UUID).
- Stat tile sub-label "IHB participant VAs".
- IC Dashboard: "Intercompany Virtual Accounts — IC Payable / IC Receivable" cards with internal numbers
  (ICP-MNC-TREASURY-…). These are the R10 mirrors, which the bank's books use and the corporate never opened.
- The error banner says "The unified API may not be deployed yet."
- Ledger narratives a user reaches from statements: "POBO cut-over repair (D8)", "posted before IHB movements were
  ledgered (backfilled to reconcile…)".
- *Fix:* plain language ("IHB current account", "overdraft limit"). Show the IC position as "Treasury owes MNC-UK
  AED 1,100.01", not as mirror accounts.

**M2. Loan-era wording survives the retirement of IHB loans and deposits (9950ceb).**
- IHB page: "Lender Entities — Available to lend funds", "Borrower Entities — Can borrow from IHB", "Lender/Borrower"
  badges, "Lending Spread (Earns)", banner "Lending 5.50% | Deposit 4.50%".
- `/ihb/corporate/{id}/treasury-rates` still returns `minLoanAmount 10,000`, `maxLoanAmount 10,000,000`,
  `minDepositAmount 10,000` (`IhbUnifiedService` l.413). TreasuryHierarchyPage (l.2729, 2744) still renders the loan and
  deposit minimums.
- No Interest Accruals links remain on either page.
- *Fix:* talk about "Treasury centre / Participants", "Debit rate / Credit rate", and drop the loan/deposit fields.

**M3. "Open Account" is offered to entities that already have one.**
- Every participant card shows "Open Account", and the create modal never shows "- Has Account", because the API
  returns no `settlementVaId` (all three already have AED IHB accounts). The modal still offers the per-account "IHB
  Sweep" (target "0 = sweep all", REAL_TIME). That is the pattern that swept Dubai's overdraft facility out (C5).
- *Fix:* key the button on existing IHB accounts per currency. Default sweep-all to the cash balance, never the
  facility.

**M4. The same number carries three labels, and entity counts disagree.**
- `stats.pendingSettlement` (= count of PROCESSED) is shown as "Pending", "Pending Distribution" (COBO tab) and "Pending
  Settlement".
- "Outstanding" on the POBO tab is `totalOutstandingPayables`. "Outstanding to Subsidiaries" on the COBO tab is
  `totalOutstandingReceivables`. From treasury's side both directions are inverted.
- Entities: strip "Entities 4", Overview "Active Entities 7", tab "Entities 4", and TestShop shows 0 / 3 / 4.
- *Fix:* one definition each, from one endpoint.

**M5. The two pages describe one business with different vocabulary and no links between them.**
- IHB page: "IHB debit balance", "Current Accounts", POBO/COBO "enrolments".
- IC Dashboard: "Payables/Receivables", "Recharges", "Credit Utilization", and its own COBO.
- Neither page links to the other. Recharges (an old POBO model) still has a tab, while the IHB POBO tab says "No fee
  is charged" although the historical POBO rows on the IC page each carry "+AED 25.00 fees".
- *Fix:* agree one glossary, link entity and account rows across the pages, and retire Recharges if POBO-by-enrolment
  replaced it.

**M6. The corporate filter isn't shared, and there's no consolidated view.**
- Both pages auto-select the first corporate (TestShop, which has no IHB) instead of the global corporate/entity context.
  The header entity can differ from the page's corporate (ECOM-HOLDING header vs TestMNC page).
- "All Corporates" on the IHB page shows only "Select a Corporate". There's no group-of-groups view.
- The IHB Program selector filters nothing (the code comment admits it).
- *Fix:* initialise from the global scope, and hide or wire the Program selector.

**M7. Fixed three-column grids on both pages.**
- `grid-cols-3` on entity, account and IC VA cards, and `grid-cols-3` on the IC overview, with no breakpoints, so cards
  squeeze at tablet/phone width. Status/entity columns on the POBO table drop out at the 1024px pane width (the table
  showed reference, amount and status only).

### Low: polish

- **L1.** A stray "0" renders on MNC-UK and MNC-UAE account cards
  (`{(account.ihbSweepEnabled || account.targetCashBalance) && …}` with target 0, InHouseBankPage l.949).
- **L2.** "⭐" emoji marks the treasury centre in the entity selector and the COBO modal.
- **L3.** Accrued interest shows 2 decimals on cards, but the API carries 4 (1986.1114). Show the accrual basis in a
  tooltip.
- **L4.** CSV export: no escaping (names with commas break columns), locale-formatted dates (currently "-"), 50 rows
  max, ICR and ICP of each POBO both exported, so every POBO appears twice. "Recent Settlements" labels every SETTLED
  row "Bilateral", including POBO mirror rows.
- **L5.** `API_BASE_URL` falls back to `http://localhost:8053` on the IC Dashboard, while the IHB page uses a relative
  `/api/v1/ihb`. Two API clients on two pages.
- **L6.** Browser `alert()` is used for success and error on Create Current Account.

---

## Missing capabilities a group treasurer would expect

1. **IHB account statements** (per account, per period, camt.053/MT940/PDF) with opening/closing balance and value
   dates. Subsidiaries book their IHB position from these. Without them each one reconciles by email.
2. **Group IHB position matrix**: entities × currencies, net credit/debit, limit, headroom, accrued interest, plus the
   IHB's own funding (treasury's bank accounts) beside it. This is the SAP IHC / Kyriba "IHB balance sheet" view that
   tells the treasurer whether the IHB is funded.
3. **IC matrix that proves zero-sum**: who owes whom, by currency, from the ledger, with the group total = 0 as a
   control check.
4. **Interest statement and rate history**: periods, day count, base fixing used, all-in rate, posted entries, WHT. This
   is needed for transfer-pricing documentation (arm's length) and local tax.
5. **"What moved today"**: intraday POBO/COBO/sweep movements across IHB accounts, with status and exceptions.
6. **Netting from this screen**: an eligible-items preview and "add to cycle" (the backend endpoints
   `/netting-eligibility` and `/add-to-netting/{cycleId}` exist; the UI only navigates away).
7. **Limit management workflow**: request/approve a limit change and see who changed limits or rates and when (audit
   trail). Neither page shows any history of configuration changes.
8. **Export of every grid** (Excel/CSV) and scheduled reports. Only the IC transaction list exports, and badly.

## What works well

- IHB current-account cards show correct balances, position type, all-in credit/debit rates and the overdraft
  availability (balance + facility) from the money. They match the VA ledger exactly.
- The underlying ledger is sound: subsidiaries' IHB credits equal treasury's IC payables (AED 1,114.49), so the group
  nets to zero. The `/intercompany/positions` endpoint already exposes this correctly per currency.
- POBO/COBO enrolment panels explain the mechanism in plain words, show IHB headroom, and the FX "Negotiate" path uses
  the firm-quote panel that shows only the all-in client rate (no mid, margin or floor seen on either page).
- The Treasury Rates banner flags "Default rates, not the bank's agreed terms" when rates are built-in, which is the
  right kind of honesty.

## Not checked

- No writes were made: Create Current Account, COBO setup (H5 is from code), settlement, recharge approval, interest
  calc/post, and enable/disable IHB were not exercised.
- Brato, Helios and Al Mawarid have no IHB entities on vam-five, so the IHB page could only be judged on TestMNC.
- Multi-Bank Liquidity's "By intercompany" breakdown was not reconciled; it showed "No hierarchy data" for All
  Corporates.
- Phone-width rendering was assessed from code, not emulated.
