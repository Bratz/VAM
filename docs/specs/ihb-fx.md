# In-house FX for POBO and COBO — specification

Status: **implemented** (2026-11-11). Decisions D1–D8 were taken in an interview; they are marked in the text and listed
at the end. Builds on `docs/specs/pobo-in-house-bank.md` and `docs/specs/cobo-in-house-bank.md`.

## 1. Why

POBO and COBO are single-currency today (verified in code and data, 11 Nov 2026):

| Today | Effect |
|---|---|
| An enrolment is per subsidiary **and** currency; the payable/invoice, treasury's account and the subsidiary's IHB current account must all be in that currency | Treasury never converts anything for a subsidiary |
| A payable or invoice in another currency is paid / collected **directly** by the subsidiary (POBO D5, COBO D5) | The subsidiary needs its own foreign-currency account and its own FX |
| FX deals and FX limits are per legal entity (`FxPaymentService.check`, `FxLimitService`) | A subsidiary's own conversion draws on **its** FX sub-limit |
| Locally, MNC's FX line is allocated to **MNC-TREASURY only** | A subsidiary paying EUR directly is refused ("No FX sub-limit is allocated to …") |
| Subsidiaries' IHB current accounts exist only in AED (MNC-UK's functional currency is GBP, its IHB account is AED) | There is no "home" IHB account per subsidiary to settle in |
| Inward credits in another currency are now converted on credit (e13cc54), with the account owner's FX | A COBO VIBAN on treasury's AED account converts a USD payment with treasury's FX — the only in-house FX that exists |

Centralised FX is one of the main reasons groups run an in-house bank: subsidiaries keep one currency, treasury
buys and sells the others with one FX line, and the currency risk sits in one place.

## 2. How it should work (target)

```
Set-up        Subsidiary X is enrolled for payment currency EUR: "paid by T from T's AED account, settled on X's
              AED IHB current account" (D2). T's account is in the settle currency, not the payment currency.
POBO          X approves a EUR 1,000 payable. Pay Now: T buys the EUR back-to-back on its own FX line (D3) and its AED
              account pays the supplier EUR 1,000 as a cross-currency payment (pain.001: InstdAmt EUR 1,000, Dbtr T,
              DbtrAcct T's AED IBAN, UltmtDbtr X). X's AED IHB current account is debited the deal's AED leg (D4),
              fixed at payment (D5).
COBO          A customer pays X's EUR invoice into a VIBAN on T's AED collection account. The EUR is sold back-to-back
              on T's FX line as it arrives (as any inward credit in another currency, e13cc54); X's AED IHB current
              account is credited the AED amount.
Statement     X sees one line: "EUR 1,000.00 to <supplier> · rate 4.0123 · AED 4,012.30". T sees the deal (if any).
```

## 3. Functional requirements

### 3.1 Scope and enrolment
- R1. In-house FX applies to both POBO and COBO (D1).
- R2. An enrolment names the **payment/collection currency** and the **IHB current account it settles on**; when the
  two currencies differ, the enrolment is cross-currency (D2). Same-currency enrolments work as today.
- R3. Enrolment checks: T's payment (POBO) / collection (COBO) account is in the **settle currency** (the named IHB
  account's), with the checks of today (bank account with an IBAN, shadow in the same program); X has the named IHB
  current account with its mirrors. Back-to-back cover (D3) converts on T's settle-currency account, so T needs no
  account in the foreign currency.
- R4. Create Payable / Create Invoice show the route as today plus the conversion: "Paid by T in EUR; settled on
  X's AED account at the rate on the payment date".

### 3.2 Covering the currency
- R5. Back-to-back per payment / collection (D3), through the FX module on **T's** FX line: T's booked deals in the
  pair first (soonest value date first), then the live price (`FxPaymentService.convert` / `convertIncoming` with T's
  account as the FX side). T holds no open position.
- R6. When T cannot cover it (no FX sub-limit, over its line) nothing moves (D6): POBO — the payable stays
  *Approved* with the reason, to retry; COBO — the inward credit is refused, as for any account today.

### 3.2a Amounts above the live-price threshold
The bank streams click-to-deal prices only up to a size per pair (base currency: 1,000,000 for the majors, 250,000 for
every other pair, incl. all AED pairs; `FxDealProperties.streaming`). Above it, the desk prices on request: a firm
quote valid 30 s, negotiable a few rounds, booked on accept. An unattended conversion cannot negotiate, so:
- R6a. POBO (D7): Pay Now / a scheduled or file run that is not covered by T's deals and is above the threshold
  refuses with "needs a negotiated rate"; the payable stays *Approved*, flagged *Awaiting FX*, and is listed on
  treasury's POBO tab. Treasury requests the quote there (for payment, on T's entity), may counter, and on accept the
  payment executes at once on that quote — the deal is the payment's, no other payment can draw on it. The subsidiary
  sees *Awaiting FX (treasury)* on its payable.
- R6b. COBO (D8): an inward credit above the threshold that T's deals do not cover is **accepted, never bounced**:
  recorded as *awaiting FX* (status PDNG to the sender), not posted, and listed on treasury's COBO tab with payer,
  amount and invoice. Treasury requests the quote (T sells the currency), may counter, and on accept the credit is
  posted converted at that rate, applied to the invoice and credited to X — the same as an automatic conversion.
- R6c. Below the threshold, or covered by T's deals, nothing waits: conversion is automatic as in R5.

### 3.3 Internal rate
- R7. X is debited / credited at T's deal rate, passed through (D4): X's settle-currency amount equals what T's
  deals moved, so T has no FX result. The rate, both amounts and the deal reference(s) are stored on X's IHB movement
  (`instructed_amount/currency`, `fx_rate`, `fx_deal_ref`, as for converted payments); a payment drawing on several
  deals shows the blended rate.
- R8. Corporate screens never show the bank's mid, margin or floor — only the all-in client rate, as today.

### 3.4 Limits, timing, messages
- R9. X's IHB credit-limit check (POBO D7) uses the settle-currency amount at the rate the payment would get
  (preview); the payment is refused if it would exceed the headroom.
- R10. The rate is fixed at payment (D5): when Pay Now / the scheduled or batch run executes (POBO), when the money
  arrives (COBO). Before that, Create Payable, approval and the payables list show an indicative settle-currency
  amount labelled *indicative*.
- R11. pain.001 / pain.008 / pain.013 carry the payment currency and amount; nothing about the rate X is settled at.
- R12. The IC mirrors (R10 of POBO) follow X's IHB current account in its own currency, unchanged.

### 3.5 Exposure
- R13. The FX exposure ladder is consolidated per corporate, intercompany excluded: unchanged. The open foreign
  payables/receivables of enrolled subsidiaries are now covered by T, which the ladder needs no change to show.

## 4. Data model (indicative)

- `pobo_enrolment` / `cobo_enrolment`: add `ihb_account_id` (the IHB current account it settles on; today implied by
  the currency). Cross-currency when its currency differs from the enrolment's currency.
- IHB movements of X: reuse `instructed_amount`, `instructed_currency`, `fx_rate`, `fx_deal_ref`.
- No treasury FX result account and no margin setting (D3 back-to-back + D4 pass-through leave T flat).
- No foreign-currency treasury accounts needed (R3). Prerequisites are those of today: T's settle-currency payment
  account with an IBAN (POBO), and an FX sub-limit for T on the corporate's FX line (MNC-TREASURY has one locally).
- `receivables`: the IHB account a COBO invoice settles on, set at creation from the enrolment (as the collector is).

## 5. Acceptance criteria

1. X enrolled for EUR settled on AED: a EUR 1,000 payable paid by T's EUR account; X's AED IHB account debited the
   AED amount at T's deal rate; T's IC receivable follows; the IHB line shows both amounts and the rate.
2. T's cover is back-to-back: a deal drawn or booked on T's sub-limit (not X's); X's AED amount equals the deal's AED leg.
3. X has no FX sub-limit and the payment still goes through (T's FX is used).
4. Over X's IHB headroom at the payment's rate: refused, nothing moves.
5. COBO: a EUR payment to T's EUR VIBAN credits X's AED IHB account at T's deal rate; invoice PAID.
6. T cannot cover (no sub-limit): nothing moves; the payable stays Approved with the reason.
8. POBO EUR 300,000 (above 250,000, no deal): refused as *Awaiting FX*; treasury negotiates on the POBO tab; accept
   pays it on that quote; the deal is used by no other payment.
9. COBO EUR 300,000 inward: accepted as awaiting FX, nothing posted; treasury's accept posts it converted, the invoice
   is PAID and X credited the AED amount.
7. No bank mid/margin/floor on any corporate screen.

## 6. Out of scope (v1)

Paying from T's own currency balance / holding positions and netting them across subsidiaries before covering (one
deal per day); internal margins or a daily internal fixing; locking a rate at approval; forward cover of scheduled
payments; multi-currency IHB accounts; revaluation of open IHB positions; falling back to direct payment when T
cannot cover.

## 7. Decisions (interview, 2026-11-11)

| # | Question | Decision |
|---|---|---|
| D1 | Scope | **Both** POBO and COBO |
| D2 | Which IHB account settles a foreign-currency item | Enrolment stays per subsidiary + payment currency and **names the IHB account** it settles on |
| D3 | How treasury covers the currency | **Back-to-back** per payment/collection via the FX module on treasury's FX line (deals first, then live price) |
| D4 | Rate to the subsidiary | **Treasury's deal rate, passed through**; no internal margin, no treasury FX result |
| D5 | When the rate is fixed | **At payment** (POBO) / on arrival (COBO); indicative before |
| D6 | Treasury cannot cover | **Refuse**, nothing moves; payable stays Approved with the reason |
| D7 | POBO above the live-price threshold | **Treasury FX queue**: *Awaiting FX* on the POBO tab; treasury negotiates, accept pays at once on that quote |
| D8 | COBO credit above the threshold | **Hold it, awaiting FX** (never bounced); treasury negotiates, accept converts, applies and credits X |
