# FX deals linked to the invoices they hedge

Status: **decided, planned** (2026-11-21). Plan: `tasks/todo.md` ("Planned: FX hedge links").

## 1. Why
A deal records what it hedges only as free text (`FxDeal.exposureRef`). Exposure & Hedging matches hedges to
exposure in aggregate (currency × time bucket), so nobody can say which invoices a deal covers, an invoice does not
show that it is hedged, a hedge left behind by a cancelled or early-paid invoice is not noticed, and paying a hedged
payable does not use its deal unless the payer picks it by hand.

## 2. Decisions (2026-11-21)
| # | Question | Decision |
|---|---|---|
| D1 | Link shape | **Many invoices per deal, with amounts**; an invoice can be hedged by several deals |
| D2 | Where links are made | **At booking** (hedging selected invoices from the Exposure drawer books a deal already linked) **and afterwards** (deal drawer: link / unlink) |
| D3 | Paying a linked payable | **Draws on its linked deal by default** (paid at the hedge rate), still overridable |
| D4 | Linked invoice paid early, reduced or cancelled | **Release and flag**: the link shrinks or lapses by itself and the deal shows what is no longer backed by an invoice |

## 3. Requirements
- R1. A hedge link: deal, invoice (payable or receivable), amount in the invoice currency, who linked it and when.
- R2. Rules when linking (refused with the reason otherwise):
  - the invoice is the deal's corporate's, open, not intercompany, in a currency the deal trades that is not the
    corporate's base currency;
  - direction: a payable is hedged by a deal that **buys** its currency, a receivable by one that **sells** it;
  - the deal is live (not settled, closed or cancelled);
  - per invoice, linked amounts (all deals) ≤ its outstanding amount; per deal, linked amounts ≤ the deal's
    remaining amount in that currency.
  - A value date far from the invoice's due date (outside the invoice's exposure bucket) is allowed but flagged.
- R3. Effective amount, computed on read (D4): min(linked amount, invoice outstanding) while the invoice is open,
  else 0, with the reason (paid, cancelled, netted, reduced). No hooks in the invoice writers, so no path is missed.
- R4. A deal's **unbacked amount** = its remaining amount in the invoice currency − the effective links. Shown on the
  deal (and as attention when a deal that had links is now unbacked), so treasury can roll, close out or relink.
- R5. Booking (D2): the deal booking request can carry the links; they are created with the deal, all or nothing.
- R6. Paying (D3): the payment plan for a payable puts the deals linked to it first, drawing up to the effective
  link; the payer can still untick them. Unattended (file) payments do the same.
- R7. Screens: deal drawer lists links (linked, effective, reason) with Link invoices / Unlink; Payables and
  Receivables show a "Hedged" marker with the deal reference(s); the Exposure drill-down shows which invoices are
  hedged and can book a hedge for the ones selected; the Pay Now FX choice shows the linked deal pre-selected.
- R8. Audit: link and unlink are audited (who, when, amount).

## 4. Acceptance
1. Linking refuses wrong currency, wrong direction, a closed deal, and amounts over outstanding or remaining.
2. Paying an invoice early, cancelling it or reducing it lowers its effective link with no extra step, and the deal
   shows the unbacked amount.
3. Booking from the Exposure drawer with invoices selected creates the deal and its links together.
4. Paying a linked payable proposes its deal first and settles part of it at the deal rate.
5. Payables / Receivables show which invoices are hedged and by which deal.

## 5. Out of scope (v1)
Linking forecast lines or declared exposures; hedge-accounting effectiveness testing and documentation exports;
receivable collections drawing on a sell deal (incoming FX conversion); deep links that open one invoice.
