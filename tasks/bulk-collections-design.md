# Bulk Collections — design

Status: spec agreed 2026-11-09 (interview), not built.

## What exists today (review, 2026-11-09)

- Bulk invoice raising: file ingest `RECEIVABLES_INVOICE` → one Receivable per row, VIBAN-linked, Party auto-onboarded.
- Bulk posting of money already received: file ingest `RECEIVABLES`; camt.054 (fixed 2026-11-09: every credit line,
  each in its own transaction, routed by its own CdtrAcct; duplicate guard now keys on the stored EndToEndId).
- VIBAN-per-customer matching, unmatched queue (match / return / escalate), COBO, single-invoice Request to Pay (pain.013/014).
- Missing: direct debit of any kind (no pain.008, no customer mandates, no returns), bulk Request to Pay, collection runs.
  `ExternalMandate` is the corporate's mandate over its *own* external accounts (sweeps) — not reused here.

## Decisions (from the interview)

| Topic | Decision |
|---|---|
| Model | Both: direct debit where a mandate allows, Request to Pay otherwise; one run can mix them |
| Scheme | Scheme-neutral pain.008; scheme is a code on the mandate (no scheme-specific rules yet) |
| Initiation | From open invoices (portal), file upload (ingest), API |
| Routing per invoice | Mandate first: active mandate that allows the amount and date → DD; else RTP; neither → excluded with a reason |
| Mandates in | Corporate registers (portal / file, active from its given date); electronic flow (pain.009 → pain.012 accept; pain.010 amend, pain.011 cancel); API |
| Mandate limits | Max per collection; one-off vs recurring; valid from/to; frequency cap (N per week/month/quarter/year) |
| Collection date | Each line on its invoice due date; if past or inside the lead time, next possible business day. A run spans dates |
| Crediting | On settlement date, held until the hold period ends; hold length is **per corporate** |
| Returns / rejects | Reverse the held credit, reopen the invoice with the reason, then send a Request to Pay for it |
| Approval | None: submitting a run sends it |
| Currency | Convert on credit: collect in the invoice currency; if the VA differs, convert using the corporate's FX deals first, then the live rate |

## Model

**CustomerMandate** (new table `customer_mandates`)
- corporateId, customerPartyId, debtor IBAN/BIC (or customerPartyBankAccountId), creditor VA (receiving account)
- mandateReference (unique per corporate), schemeCode (free text, e.g. `UAE_DDS`, `SEPA_CORE`), currency
- type ONE_OFF | RECURRING; validFrom, validTo; maxPerCollection; frequencyCap {count, period}
- status PENDING (e-mandate sent) | ACTIVE | SUSPENDED | CANCELLED | EXPIRED | USED (one-off done)
- source PORTAL | FILE | API | EMANDATE; signedDate; sequence state FIRST used? (drives FRST/RCUR in pain.008)

**CollectionRun** / **CollectionLine** (new)
- Run: corporateId, ref, source (INVOICES | FILE | API), status BUILDING → SUBMITTED → PARTLY_SETTLED → COMPLETED, totals per method.
- Line: receivableId, method DD | RTP | EXCLUDED (+ reason), mandateId, amount, currency, collectionDate, endToEndId,
  status PLANNED → SENT → SETTLED (held) → AVAILABLE | RETURNED → RTP_SENT, returnReason, fx fields (rate, deal ref).

**Corporate setting**: `collectionHoldBusinessDays` (default 5).

## Flows

1. **Build** — from selected open invoices, a file (new ingest domain `COLLECTIONS`: rows reference an invoice or raise one
   the way `RECEIVABLES_INVOICE` does), or `POST /api/v1/collections/runs`. Routing runs per line (mandate first).
   Mandate checks: ACTIVE, date within validity, amount ≤ max, frequency cap not reached, one-off not used.
2. **Submit** — DD lines grouped by (creditor VA, scheme, collection date, currency, sequence type) → one pain.008 each
   (GrpHdr NbOfTxs/CtrlSum, PmtInf per group). RTP lines → pain.013 each via the existing service. No approval.
3. **Settle** — DD settlement arrives (camt.054 credit or a settlement call on the collection date): credit the VA via the
   existing 4-leg `processCollection`, amount moved to `held_balance`; the invoice is marked paid-pending.
   Cross-currency: convert on credit, deal first (deals that **sell** the invoice currency and **buy** the account currency) then live rate.
4. **Release** — daily job lifts holds whose `settled + collectionHoldBusinessDays` has passed; invoice → PAID.
5. **Return / reject** — pacs.004 or pain.002 (and camt.054 DBIT return entries) matched by EndToEndId: debit the held
   amount back, reopen the invoice with the reason code, auto-send a Request to Pay, notify treasury. Mandate
   reason codes that mean the mandate is dead (e.g. MD01/AC04/MD07) suspend the mandate too.
6. **E-mandate** — pain.009 out (mandate PENDING); pain.012 in accepts/rejects; pain.010/011 for amend/cancel.

## API (sketch)

- `GET/POST /api/v1/collections/mandates`, `PUT …/{id}`, `POST …/{id}/cancel`, `POST …/mandates/file`, `POST /api/v1/iso20022/inward/pain012`
- `POST /api/v1/collections/runs` (from receivableIds or raw lines) → routed preview; `POST …/runs/{id}/submit`
- `GET …/runs`, `GET …/runs/{id}` (lines, per-method totals, pain.008 downloads)
- `POST /api/v1/iso20022/inward/pacs004`, `…/pain002` for returns/rejects

## UI

Receivables gets a **Collections** area: Runs (list + run detail with lines, method, status, hold-until),
Mandates (register, upload, e-mandate status), and "Collect" from the invoice list's selection.

## Defaults I chose (say if wrong)

- Settlement of a DD is simulated on the collection date when no camt.054 arrives (no live clearing link), like other rails here.
- Lead time per scheme code is a small config map (default 1 business day).
- A line whose mandate fails a check falls to RTP; with no debtor Party for RTP it is EXCLUDED.
- Returned-then-RTP happens once; a returned RTP-sourced payment is not retried.

## Build order

1. ✅ Mandates (entity, API, portal register/upload) + routing preview for a run from invoices. *(built 2026-11-09)*
   - `com.bank.vam.collections`: `CustomerMandate` (table `customer_mandates`, ddl-auto), `MandateService`
     (register / CSV import all-or-nothing / update / suspend-resume-cancel; IBAN mod-97 check; blank IBAN →
     customer's primary bank account), `CollectionRoutingService` (`POST /collections/runs/preview`), `CollectionsController`.
   - Portal: Receivables → **Mandates** tab (list, register, upload, row actions); **Plan collection (n)** on selected invoices.
   - Not yet: frequency cap is stored but not counted (needs collection history, step 2); lead time is one business day for
     every scheme, weekends only. The invoice list only lets OPEN/PARTIAL be selected (its COBO rule), so OVERDUE invoices
     can be planned through the API but not picked in the portal yet.
2. ✅ pain.008 generation + submit + simulated settlement with hold and release job. *(built 2026-11-09)*
   - `CollectionRun` / `CollectionLine` (tables `collection_runs`, `collection_lines`). `POST /collections/runs` routes and
     sends at once (no approval): direct debits → **one pain.008 per run**, one PmtInf per (receiving account, scheme, date,
     currency, SeqTp FRST/RCUR/OOFF); Request to Pay lines are sent now via the existing pain.013 service (each in its own
     transaction). `GET /runs`, `/runs/{id}`, `/runs/{id}/pain008`.
   - Routing now counts history: frequency cap per calendar week/month/quarter/year (earlier collections + this batch),
     one-off used once, invoices with a debit in flight left out, receiving account in another currency → RTP until step 5.
   - `CollectionSettlementService`: daily (`vam.collections.eod-cron`, default 18:30) or `POST /collections/process?asOf=`.
     On the collection date: 4-leg `processCollection`, net amount **held**, invoice **paid** (gross) at settlement so a
     return can reopen it; one-off mandate → USED. Hold until collection date + the corporate's
     `collectionHoldBusinessDays` (default 5; `GET/PUT /collections/settings`), then released to available.
     A line that fails to credit stays SENT with the reason and is retried next run.
   - Portal: the plan modal has **Send collections**, shows each line's status and downloads the pain.008.
   - Fixed on the way (existing bugs found live): `processCollection` charged the fee twice (credited net, then posted the
     fee leg) — the target is now credited gross; and 11 places set available = current, releasing every hold on the
     account — now `syncAvailableToCurrent()` (available = current − held).
   - Not yet: hold-days setting has no portal field (step 7); a real camt.054 credit for a debit is not matched to its line (step 3).
3. ✅ Returns/rejects (pacs.004 / pain.002 / camt.054 DBIT) → reverse, reopen, RTP fallback, mandate suspend. *(built 2026-11-09)*
   - `CollectionReturnService`: by EndToEndId. SENT → REJECTED (no money). SETTLED/AVAILABLE → hold released, gross back
     out through `TransactionService.reverseCollection` (shadow + target REVERSAL legs; the fee is not refunded), invoice
     reopened (OVERDUE when past due) → RETURNED. Then a Request to Pay for the invoice. AC01/AC04/AC06/AG01/MD01/MD07
     suspend the mandate; otherwise a returned one-off goes back to ACTIVE. Returned/rejected debits don't use the cap.
   - In: `POST /iso20022/inward/pacs004`, `/inward/pain002` (TxSts RJCT, or a rejected PmtInf / whole message = every
     waiting debit of that run), `/inward/camt054` DBIT entries with RtrInf; portal/API `POST /collections/lines/{id}/return`
     with `GET /collections/return-reasons`. A camt.054 **credit** for a waiting debit settles that line instead of the
     simulated settlement (if it cannot settle, the credit posts as an ordinary inward payment).
   - V45 widens `collection_lines_status_check` (Hibernate's enum CHECK isn't updated by ddl-auto).
   - V44 refunds the fee that collections before 0eb5fcc were charged twice (FEEFIX-* ADJUSTMENT credits).
   - Invoice list: OVERDUE (and PARTIAL) now selectable for both Plan collection and COBO; `canRequestCobo` accepts them.
4. ✅ Bulk RTP inside runs; file ingest `COLLECTIONS` domain; runs API. *(built 2026-11-09; RTP came with step 2)*
   - `CollectionIntake`: a line naming an existing invoice of the corporate collects it (amount = outstanding, same
     currency and account, else the line fails with the reason); a line naming none raises it first via
     `ReceivableInvoiceProcessor.raiseInvoice` (customer by legal name or onboarded), due on its date or today.
     Duplicates within a batch are refused.
   - File: ingest domain `COLLECTIONS` ("Collections" on the upload page). The hand-written CSV shape
     (`amount,currency,viban,debtorName,debtorAccount,remittanceInformation,endToEndId`) is accepted without the agent;
     other layouts go to the agent as usual (its prompt now says invoice numbers go in remittanceInformation). The
     file's invoices go out as one run (source FILE); each row is PROCESSED with "CR-…: direct debit on … / Request to
     Pay sent", or FAILED with why.
   - API: `POST /collections/runs/lines` {corporateId, lines:[{invoiceNumber?, customer?, customerAccount?, account,
     amount, currency, dueDate?, description?}]} → the run (source API) plus `rejected` lines with reasons.
5. ✅ Cross-currency convert-on-credit (deal first). *(built 2026-11-09)*
   - `FxPaymentService.convertIncoming`: deals of the account's entity that **sell** the invoice currency and **buy** the
     account's, affirmed and drawable today, soonest value date first; a SELL spot at the live price (for-payment) for
     the rest. `settleIncoming` records each deal's use (`FxSettlementService.receivedByCollection`, event
     USED_BY_COLLECTION) against the credit and puts instructed amount/currency, rate and deal refs on it.
   - Settlement converts before the 4-leg credit (same transaction: a failed credit books no FX), credits the account
     in its currency, and the line keeps `creditCurrency`, `creditGross`, `fxRate`, `fxDealRef`.
   - Routing: an account in another currency is fine when it belongs to a legal entity (FX is booked per entity).
   - ponytail: a converted collection is returned at its original rate (the AED credited goes back out); a bank
     would reconvert at the day's rate, and the deal's use is not undone.
6. E-mandate flow (pain.009/010/011/012).
7. Collections UI.
