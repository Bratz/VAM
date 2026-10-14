# Routing options on the VAM payment path, from the uc05 quote API

## What exists on each side

**uc05** (`C:\Users\BHATTACHARYAMrSUBRAT\AVD5\uc05-liquidity-demo`) already decides. `GET
/routing/quote` takes an intent and returns up to three labelled options from a LinUCB
contextual bandit, with the charge, the arrival, the on-time probability and a `why`.
Nothing writes; it is a read at one moment. Served by `uc05-api` on 8060 behind Caddy,
router mounted at `/api/v1/routing`. CORS is an explicit allow-list, never `*`.

**VAM** does not decide. `TransactionDto.PaymentRequest.channel` is a free-text String the
caller supplies; its only effects are the movement label and `getPaymentFeeCode()`. There is
no `PaymentType`, no domestic/cross-border discriminator, and `CurrencyCutoffConfig` /
`CutoffCalendarService` / the ~40 `RejectionCodeConfig` seeds are never consulted on the
payment path. So the gap is exactly the shape of the uc05 answer.

## The three limits to settle before any code

1. **Currency.** `/routing/quote` is `currency: Literal["USD","EUR","GBP"]`. OCI now runs the
   **UAE profile (AED, `EBILAEADXXX`)**, and the BoP bid needs **ILS**. Neither can be
   quoted. On today's deployment this feature is dark for the home currency. Either uc05
   gains the corridors or this ships as a USD/EUR/GBP-only capability — say which in the RFI
   rather than implying coverage.
2. **Destination coverage.** 15 alpha-2 destinations have route data; others come back
   `destination_known: false`, judged without a record of their own. Neither IL nor PS is in
   the set.
3. **A quote is not a commitment.** The author's own note: limits and the accounts behind the
   routes "start the day untouched: a quote does not know what the desk has already sent
   today." So the re-quote at confirm is **part of the feature, not a refinement** — see
   below.

## Shape

### Backend — three files, no new abstraction

`client/PaymentRoutingClient.java` — one class, not an interface plus impl. Injects the
**existing** `RestTemplate` bean from `RestTemplateConfig`; `@Value("${uc05.routing.base-url:}")`,
empty means off. `@CircuitBreaker(fallbackMethod="noOptions")` from the resilience4j starter
already in `pom.xml` (and the first real circuit breaker in the codebase — `DESIGN_DOCUMENT`
currently claims one that does not exist). One method, `quote(...)`, mapping straight onto
the query params. Response bound to records mirroring `router/quote.py` field-for-field; no
translation layer, the uc05 vocabulary *is* the vocabulary.

`service/PaymentRoutingService.java` — two operations:
- `options(intent)` — derives the uc05 params from a VAM payment intent. The only real
  mapping work: **`destination` present means cross-border**, absent means domestic, which
  is the discriminator VAM lacks. Beneficiary country comes off the BIC VAM already
  collects (characters 5-6). `by_when` is a new field (see UI).

  **Correction to the first draft of this plan:** it said to sanity-check `by_when` against
  `CutoffCalendarService`. That service's `checkEligibility` takes a `SweepRule`, so it does
  not apply to a payment; and the route sheet already carries every route's open and cut-off
  times, so a second cut-off rule in VAM would re-implement one that lives in uc05 — which is
  how the settlement resolver came to disagree with itself. VAM checks only that the deadline
  is in the future, which saves a round trip since uc05 422s on it, and leaves timing to uc05.
- `confirm(selection)` — **re-quotes**, and refuses if the chosen `route` is no longer
  offered or its charge/arrival moved beyond tolerance, returning the fresh options instead.
  This is the author's caveat turned into a control.

`entity/PaymentRouteSelection.java` + repo — one small table keyed by the transaction, not
eight nullable columns on high-volume `va_movements`: `route`, `labels`, quoted `charge`,
`arrival`, `on_time`, `proven`, `sheet_version`, `destination_known`, `quoted_at`, and the
confirm-time delta. This is the audit trail for *why this route*, and it feeds the existing
hash-chained `AuditLogService` (quote asked / options offered / option chosen / re-quote
delta).

On execution, the chosen option's `kind` populates `PaymentRequest.channel`. Nothing else on
the payment path changes.

### Frontend — reuse the contract, not the components

`web/src/components/Quote.tsx` in uc05 already declares `Option` / `Answer` / `Estimate` and
the `KIND` and `LABEL` maps. Copy those **types and label strings** verbatim — they are the
contract, and re-deriving them is how the two drift. Do **not** copy the markup: it is a
desk tool, and VAM has its own design system (`Card`, `Badge`, `Checkbox variant="card"`,
lucide icons only).

A routing step between capture and confirm on `TransfersPage` / `CreatePayablePage`:
- Up to three option cards using uc05's own wording — *Lowest expected charge* / *Arrives
  first* / *Surest to arrive on time* — each with charge, arrival, minutes to spare, on-time
  %, and the `why` behind a disclosure.
- `excluded[]` shown with its reasons. This is the answer to "why wasn't X offered", and the
  first surface where `RejectionCodeConfig` earns its keep.
- `proven: false` and `destination_known: false` each carry a visible caveat. These are
  estimates from a learner, and the UI must not dress them as quotes from a bank.
- Confirm re-quotes. If anything moved, show the diff and make them choose again.
- No options, or uc05 unreachable: the step collapses to today's behaviour — pick a channel
  by hand. The feature degrades to the status quo, never blocks a payment.

## What this does and does not close

Closes, for the RFI: domestic vs cross-border as genuinely distinct instructions; real
payment products and channels (`rtgs`, `instant`, `batch`, `correspondent`, `operator`,
`card_push`, `token`, `stablecoin`) where VAM had free text; eligibility, cut-offs and
exclusions evaluated *before* submission; and an auditable record of why a route was chosen.

Does not close: **execution.** This is a decision layer. Choosing `rtgs` does not give VAM
RTGS connectivity — there is no transport, no MT/MX emission, no clearing-system link. The
RFI answer must say "selects and evidences the route" and not imply VAM can send on it.

## Open, in order

1. Which currencies will uc05 quote? Without AED this is invisible on the current OCI
   profile, and without ILS it is invisible to BoP.
2. Service-to-service auth. VAM is `permitAll` and the uc05 router has no auth on these
   GETs. Both need fixing before this is anything but a demo path.
3. Tolerance for the confirm-time re-quote: how much charge/arrival drift silently accepts
   versus re-asks.

---

## Built

Backend, all under `com.bank.vam`:
`config/PaymentRoutingProperties` (`vam.routing.*`, off unless `base-url` is set) ·
`client/PaymentRoutingClient` (records mirroring `router/quote.py`, `@CircuitBreaker` with a
no-options fallback) · `dto/PaymentRoutingDto` · `service/PaymentRoutingService` ·
`entity/PaymentRouteSelection` + repository · `controller/PaymentRoutingController`
(`POST /api/v1/payments/routing/{quote,select}`). `Iso20022PaymentDto.OutwardPaymentRequest`
gained `routeSelectionId`, and `Iso20022OutwardPaymentService` links it to the transaction at
the one point all three payment paths converge.

`payment_route_selections` is entity-mapped, so `hibernate.ddl-auto: update` creates it. **No
Flyway migration** — a migration redefining a column an `@Entity` also maps is the thing
CLAUDE.md forbids.

Frontend: `services/routingApi.ts` (uc05's own types, copied not re-derived) ·
`components/payments/PaymentRouteOptions.tsx` + six Storybook stories · wired into
`TransfersPage` step 3, since the amount is half the question and the BIC captured in step 2
supplies the rest.

Checks: 15 tests (12 on the drift comparison and the currency gate, 3 binding uc05's own
payload shape), 313/313 backend green, `tsc` and `eslint` clean, production build green, and
all six UI states rendered in Storybook in light and dark.

**Verified against the real uc05**, started from its own venv on 8060 (`uvicorn uc05.api.main:app`)
with VAM on 8053 pointed at `http://localhost:8060/api/v1`, against the live `vam_db`:

- A quote for 10,000 USD to SG returned three real routes — `corr_usd_3` (correspondent,
  charge 42.00, 98.3% on time), `mto_1` (operator, fastest), `corr_usd_1` (`proven: false`) —
  with the learner's own feature weights intact (`route:corr_usd_3`, `corridor:SG`), and five
  excluded routes with their reasons.
- `ddl-auto` created `payment_route_selections` with all 27 columns on first boot.
- Confirming at the charge shown was accepted and recorded: route, kind, labels
  `cheapest,surest`, on-time 0.9829, sheet version 6, `destination_known` true.
- Claiming a charge of 10.00 against a live 42.00 was refused — *"The charge is now 42.0, up
  from 10.0."* — and returned the three current options. A route uc05 had stopped offering was
  refused too.
- `PAYMENT_ROUTE_QUOTED`, `PAYMENT_ROUTE_SELECTED` and `PAYMENT_ROUTE_REQUOTE_REFUSED` all
  landed in the hash-chained `audit_log`.
- AED was refused without a round trip; a domestic GBP payment quoted `instant_gbp` with no
  destination; an unbuilt corridor (ZW) returned `destination_known: false`, which survives
  serialisation and drives the weaker-evidence warning.
- With uc05 stopped, five calls fell back cleanly and the sixth hit an open breaker
  (`CircuitBreaker 'uc05Routing' is OPEN`) — still HTTP 200, still "choose a channel to
  continue". The payment path never blocks on uc05.

**The one thing the route sheet changes:** uc05's real `floor` is 0.95 and its `sheet_version`
is 6, not the 0.9/7 the Storybook fixtures use. Nothing reads those as thresholds; they are
displayed and recorded.
