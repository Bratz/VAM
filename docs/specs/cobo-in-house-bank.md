# Collect on Behalf Of (COBO) through the In-House Bank — specification

Status: **decided, planned** (2026-11-11). Implementation plan: `tasks/todo.md` ("COBO through the In-House Bank"). Decisions D1–D8 were taken in an interview; they are marked
in the text and listed at the end.

## 1. Why

Today COBO is a per-invoice workflow: the invoice is created, treasury approves the request, and a treasurer
clicks **Collect**, which credits the treasury account whether or not any money arrived. The customer meanwhile
is told to pay the invoice's own collection account (usually the subsidiary's). Verified in the code:

| Problem | Effect |
|---|---|
| Collection is a click, not a cash event | Treasury's account is credited with money that may never have arrived |
| The invoice's pay link / VIBAN point at the account picked on the invoice | The customer pays the subsidiary while treasury "collects" — two money paths |
| Payment matching (`ReconciliationService`) and collection settlement ignore COBO | One invoice can be credited twice: once by the customer's payment, once by Collect |
| The subsidiary is credited on "the invoice's collection account" | The proper in-house posting (`makeIhb6LegCoboCollection`) only runs if that account happens to be the subsidiary's IHB current account; otherwise a plain transfer |
| The COBO fee is deducted from the amount forwarded | The subsidiary receives less than the invoice and cannot settle it in full |
| pain.008 has no `UltmtCdtr` | Direct debits collected on behalf cannot name the subsidiary to the payer |
| Per-invoice treasury approval | Not how in-house banks run COBO; adds a manual step to every invoice |

What is already right and is kept: the subsidiary owns the receivable; treasury is the collector; the in-house
posting that raises the subsidiary's **IHB current account** and treasury's **IC payable** to it.

## 2. How it should work (target)

```
Set-up (once)      Subsidiary X is enrolled: "collected by Treasury Centre T" for currency C
Invoice            X raises the invoice to its customer; it is COBO automatically.
                   Pay link and VIBAN are issued under T's collection account, named "T re: X".
Customer pays      Bank statement credit (camt.054 / MT940 / inbound pacs.008) lands on T's account via the VIBAN.
Matching           The VIBAN identifies the invoice -> payment applied in full to X's invoice.
In-house posting   T's collection account +gross (real cash) ; T owes X: IC payable +gross ;
                   X's IHB current account +gross ; X's invoice -gross (paid).
Fee                None in v1 (D4): X is credited the full amount collected.
Subsidiary view    X sees the invoice paid and a line on its IHB current account statement per invoice.
```

## 3. Functional requirements

### 3.1 Enrolment (replaces the per-invoice request)
- R1. A subsidiary legal entity can be enrolled for COBO per currency: `collectedBy = <treasury entity>`,
  `collectionAccount = <treasury's collection account in that currency>`, `effectiveFrom`. (D1, D2)
- R2. Only a legal entity flagged as the treasury centre (`isTreasuryCenter`) can be the collector. Holding
  companies are no longer offered as collectors unless flagged as the treasury centre; a corporate without one
  cannot use COBO until one is flagged. (D2)
- R3. Enrolment requires that the subsidiary has an **IHB current account** in that currency
  (`mirrorAccountType = IHB_CURRENT`, owning entity = subsidiary) and treasury has the matching **IC payable**
  — the accounts the in-house posting needs. Enrolment is refused with a clear message otherwise.
- R4. Enrolments are listed and editable on the Legal Entities / In-House Bank screens; ending an enrolment
  affects new invoices only.

### 3.2 Creating an invoice
- R5. An invoice whose owning entity is enrolled for its currency is COBO automatically. Create Invoice shows
  "Collected by Treasury Centre T into <account>"; the collection-account picker is fixed to T's account. (D3)
- R6. The invoice's VIBAN and pay link are issued under T's collection account, account name
  "<T legal name> re: <X legal name>"; the email / pain.013 tell the customer to pay that IBAN.
- R7. Invoices of non-enrolled entities behave as today (direct collection), except the collection-account
  list offers only the owning entity's accounts.
- R8. The per-invoice "Request COBO" path and treasury approval are removed from the normal flow. (D3)

### 3.3 Receiving the money (cash-driven)
- R9. A credit on T's collection account that matches a COBO invoice (by VIBAN, else reference) triggers the
  in-house posting automatically — no Collect click.
- R10. The in-house posting credits **gross**: T's collection account receives the cash; IC payable (T owes X)
  +gross; X's IHB current account +gross; the invoice is paid by the gross amount. One `ReceivablePayment`
  records it, linked to the bank transaction.
- R11. Partial payments credit X with the amount received and leave the remainder outstanding; overpayments
  follow the invoice's existing overpayment rule, the surplus held on T's account as unapplied cash.
- R12. A credit that cannot be matched stays as an unmatched payment on T's account (existing exception flow).
- R13. **One path per invoice**: payment matching and collection settlement must not also apply a COBO
  invoice's payment directly to the subsidiary's account. The manual **Collect** button is removed; a
  treasurer can instead **record a cash receipt** for money that arrived outside the VIBAN (cheque, wrong
  reference), with a mandatory bank reference that is checked against unmatched credits. (D6)

### 3.4 Fees
- R14. No COBO fee in v1 (D4): nothing is deducted from receipts and no charge is raised. The fee posting in
  today's Collect path goes with it. A charging model can be added later without changing the collection flow.

### 3.5 Direct debits collected on behalf
- R15. For COBO invoices collected by direct debit, the pain.008 creditor is T and `UltmtCdtr` is X
  (name, and identifier if held). Mandates stay the subsidiary's customer relationship. (D8)
- R16. On settlement of such a debit (camt.054 / date-driven simulation) the same in-house posting (R10) runs;
  a return reverses it and reopens the invoice (as returns do today).

### 3.6 Currency
- R17. v1: the invoice currency must equal the currency of the enrolment and of X's IHB current account.
  An invoice in another currency is either refused for COBO or collected directly. (D5)

### 3.7 Reporting
- R18. X's IHB current account statement shows one line per collected invoice (invoice number, customer, gross).
- R19. Treasury sees COBO collections by subsidiary and currency.

## 4. Data model (indicative)

- New `cobo_enrolment`: id, corporate_id, subsidiary_entity_id, currency, collector_entity_id,
  collection_va_id, effective_from, effective_to, created_by/at.
- `receivables`: reuse `is_cobo`, `cobo_collector_entity_id/code/name`; `cobo_request_status` becomes legacy.
- `vibans`: the VIBAN of a COBO invoice sits on T's collection VA; its account name carries "T re: X".

## 5. Changes to existing behaviour

- The Collect button and the per-invoice approval in COBO History go (kept read-only for history).
- In-flight COBO invoices at cut-over (PENDING_TREASURY_APPROVAL / APPROVED) need a rule. (D7)
- `CoboReceivableService.executeCoboCollection` is replaced by the cash-driven posting; its account checks
  (collector's account, same currency, active) move into enrolment validation.
- The "recharge" record created for COBO today is replaced by the IHB current account line (no fee in v1).

## 6. Acceptance criteria

1. Enrolled subsidiary, AED invoice: pay link and VIBAN are on T's collection account; nothing is credited
   until money arrives.
2. A camt.054 credit to that VIBAN: invoice paid in full; X's IHB current account +gross; T's IC payable +gross;
   one payment record; no fee deducted or charged.
3. The same invoice cannot be credited twice (second credit -> unapplied / overpayment rule).
4. Partial payment leaves the remainder outstanding on X's invoice.
5. Direct debit collected on behalf: pain.008 shows T as creditor and X as `UltmtCdtr`; settlement runs the
   same posting; a return reverses it.
6. Non-enrolled entity: direct collection unchanged; collection accounts limited to the owning entity's.
7. Enrolled for AED, invoice in USD: collected directly, and Create Invoice says why.
8. Money outside the VIBAN can only be applied by picking an existing unmatched credit on T's account.
9. Cut-over: a pending COBO request becomes direct collection; an approved-not-collected one gets new pay
   details on T's account.

## 7. Out of scope (v1)

COBO fees (D4); in-house FX into the subsidiary's functional currency; per-customer enrolment; netting COBO
positions; regulatory (agent / payment-institution) checks.

## 8. Decisions (interview, 2026-11-11)

| # | Question | Decision |
|---|---|---|
| D1 | Enrolment granularity | Per subsidiary **and currency** |
| D2 | Who may collect | Treasury centre only (`isTreasuryCenter`); holding companies not offered |
| D3 | Per-invoice behaviour once enrolled | COBO automatically; explicit per-invoice opt-out to direct collection; no treasury approval |
| D4 | Fee | **No COBO fee in v1** — credit gross, no charge |
| D5 | Invoice currency ≠ enrolment currency | Collected directly; Create Invoice says why |
| D6 | Cash arriving outside the VIBAN | Treasurer applies an existing unmatched credit on treasury's account; no free-form receipt |
| D7 | In-flight COBO invoices at cut-over | Pending approval → direct collection; approved-not-collected → new cash-driven flow with reissued pay details |
| D8 | Direct debits collected on behalf | Treasury as creditor, subsidiary as `UltmtCdtr` |
