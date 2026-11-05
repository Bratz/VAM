# FX UI/UX: review of what was built, and the redesign plan

Status: IMPLEMENTED (sections 2 to 5 as planned, with the user's go-ahead "go with recommendations": all five decisions
in section 6 took the recommended or deferred option, plus RFQ negotiation against the simulated desk). Outcome and
verification are in `fx-corporate-design.md`, "Phase 5b". Not built: per-deal Settle, role gating, the bank-side
dealer screen. The text below is the original review and plan. Trigger: the user asked whether the FX screens
followed the app's UI/UX standards, and said FX should be its own left-hand menu with sub-menus for rates and
lists, with the lists carrying the actions.

## 1. Did the FX UI follow the app's standards?

Short answer: at the component and token level yes (and it is machine-checked); at the page-pattern and
information-architecture level no. I read the design system and the primitives but never audited how the
existing list pages are laid out, how scope is chosen, or how navigation is organised.

### What conforms (evidence)
- `eslint` on all six FX page files exits clean. That config enforces the design system: semantic colour
  classes only, the type scale, radii/shadows, `DataTable` instead of raw tables, `Checkbox`, canonical icons.
- Shared primitives are used throughout: `Page`, `PageHeader`, `Card`/`CardHeader`, `DataTable`, `Badge`,
  `Button`, `Input`, `Select`, `Drawer`, `Alert`, `ProgressBar`, `EventTimeline`, `Tabs`, `ScopeSelector`.
- Light and dark tokens, tabular numerals, loading/empty/error states, narrow-width column dropping.

### What does not conform

| # | Area | The app's pattern | What I built | Severity |
|---|------|-------------------|--------------|----------|
| 1 | Navigation | A titled sidebar section holds its pages as menu items (Accounts & Structure has nine). Each page is its own route, page id and title. | One menu item, "FX Workspace", inside Liquidity Management, with three in-page tabs hiding five distinct jobs. | High |
| 2 | Row actions | Lists end in an Actions column with a `...` menu (Accounts, Beneficiaries, Parties, Programs, Wallet, Payables, Receivables, Escrow). The row click opens detail. The primary create action is a header button ("+ New Account"). | Deals and RFQ log rows only open a drawer. Every action (confirm, dispute, draw down, close out, roll, download) is buried inside that drawer. The exposure list has one inline "Quote" button. No header action. | High |
| 3 | Corporate scope | The header carries a global corporate/entity switcher (`UserContext`, saved in `localStorage`). Newer pages (Cash Forecast) read `useUser().currentCorporateId`. Older pages add an in-page `ScopeSelector`. | Two in-page selectors, each with its own state, ignoring the header. Seen live: header says MNC Holdings while the FX panel says TestMNC, on one screen. Switching tabs resets the choice. | High |
| 4 | Page anatomy | Scope bar, then a `StatStrip` of metrics, then a toolbar (search, Filters, Refresh, Export), then the list. | No stat strip (the RFQ log hand-rolled its own `Tile`). No search, no export, filters only on the RFQ log. Credit limit is a bar in a card. | Medium |
| 5 | Data access | `corporatesApi` / `apiClient` (base URL, interceptors). | Two raw `fetch('/api/v1/corporates')` calls that bypass `apiClient`. | Medium |
| 6 | One long page | A page does one job. | The Deal tab stacks a scope card, a request form, a price card, deals, the RFQ log and a settlement runner (about five screens deep). | Medium |
| 7 | Naming collision | "FX Rates" (Administration) is the platform reporting-rate table. | "FX Workspace > Market" is a different thing (simulated market board and curve) with a similar name, in a different menu. | Medium |
| 8 | Routing | Each page has an id (`PageType`), a title in `pageTitles`, a `sectionFor` theme, and works as a `?page=` deep link. | One id for everything. No deep link to Deals, RFQ log or a tab. | Medium |
| 9 | Form controls | `Input`, `Select` primitives. | A raw `<input type="date">` with hand-copied classes in the Market tab, and a raw file input (the latter has no primitive). | Low |
| 10 | Role gating | `PermissionGate` exists but pages barely use it. | Confirm, dispute, close out and roll are open to every user. Not a regression against the app, but a real gap for dealing (maker-checker is already listed as deferred). | Note |

## 2. Proposed information architecture

A new titled sidebar section, **Foreign Exchange**, placed directly after Liquidity Management and removed from
it. It uses the existing two-level model (section, then items), so no new navigation mechanism is needed.

| Menu item | Page id | Job | Kind |
|-----------|---------|-----|------|
| Rates | `fx-market` | See the market and the forward curve; start a price request from any row | List |
| Exposure & Hedging | `fx-exposure` | See exposure against policy; hedge a gap; declare ERP exposures | List |
| Deals | `fx-deals` | The blotter and everything done to a deal | List with actions |
| RFQ Log | `fx-rfq` | Every price request and what became of it | List with actions |

Not a menu item: **New deal** (request a price, accept). It is an action, not a place, so it is a header button
("+ New Deal") that opens a drawer with the ticket. Rate rows, exposure rows and RFQ rows open the same drawer
pre-filled. This follows "+ New Account" and removes the long Deal page.

The old `fx-workspace` id stays routable through `covers` so existing links land on Deals.

### Naming collision (item 7)
Keep Administration's "FX Rates" as it is. Name the new item **Rates** under Foreign Exchange, with the page title
"FX Rates" for the market board; to keep the two apart the Administration entry is relabelled **FX Rate
Table** in the menu only (page id and title unchanged). Needs sign-off, since the earlier IA review put it there.

## 3. Screen designs

Common to every FX list page: `Page` + `PageHeader`; the **header carries the primary action**; scope from the
**global switcher** (no in-page selector); a `StatStrip`; a toolbar (search, filters, Refresh, Export CSV);
`DataTable` with a trailing Actions `...` column; row click opens the detail drawer; empty, loading and error
states; light and dark.

### Rates (`fx-market`)
- Header: no primary action. Badge "Simulated".
- StatStrip: pairs listed, biggest mover (bps), feed status.
- List: rate board, columns Pair, Mid, Change vs open, Last minute (sparkline). Row actions: **Request price**
  (opens the New Deal drawer for that pair), **Forward curve** (selects the pair), **Copy rate**.
- Detail panel beside the board: forward curve for the selected pair and the "price an exact value date" input
  (using the `Input` primitive).

### Exposure & Hedging (`fx-exposure`)
- Header: **Upload exposures** (opens a modal with the file input, Replace checkbox, Template download).
- StatStrip: currencies exposed, buckets outside policy, hedged share, home currency.
- List 1, ladder per currency (as today). Row actions: **Hedge to policy** (New Deal drawer pre-filled), **View
  entries** (filters list 2 to that currency and bucket).
- List 2, declared exposures and hedges. Row actions: **View**, **Hedge this**. (A delete action needs a backend
  endpoint that does not exist; replace-on-upload remains the only edit, stated in the page.)

### Deals (`fx-deals`)
- Header: **+ New Deal**; secondary **Run settlement** (bulk, record only, with the as-of date in a small modal).
- StatStrip: open deals, net mark-to-market (home currency, where every deal has a mark), FX credit limit used
  with headroom bar, **Needs attention** (count, clickable: filters the list).
- Toolbar: search (ref, pair), Status (Open default, All, each status), Confirmation (Any, Awaiting, Confirmed,
  Disputed), Refresh, Export.
- Row actions menu, shown only when valid for that row (the same rules the drawer uses today):

| Action | Shown when |
|--------|-----------|
| View details | always (opens the drawer: confirmation text, timeline, settlements) |
| Download confirmation (PDF) | always |
| Confirm | confirmation ISSUED and deal in force |
| Dispute | confirmation ISSUED and deal in force |
| Draw down | window forward, confirmed, amount remaining |
| Close out early | in force, not spot, not disputed |
| Roll | in force, not spot, not disputed |

  Confirm opens a confirm dialog; Dispute asks for the reason (`Modal`); Draw down, Close out and Roll open a
  small modal with the priced preview and a final confirm, as the drawer does now. Destructive items (Close out)
  sit below a divider in the error tone, as in `AccountActions`.
- Settlement of one deal is not a row action: the backend only settles in bulk (`POST /fx/deals/settlement/run`).
  Adding `POST /fx/deals/{id}/settle` is a small change if wanted (see decisions).

### RFQ Log (`fx-rfq`)
- Header: **+ New Deal**.
- StatStrip: requests, accepted, expired or withdrawn, declined by the desk, hit rate (replaces the hand-rolled tiles).
- Toolbar: Outcome, Pair, Period, Refresh, Export.
- Row actions: **View history** (drawer, as now), **Request again** (New Deal drawer pre-filled from the row;
  closes the "not done" item from the RFQ log note), **Withdraw** (only while the price is still firm).

### New Deal drawer (shared)
- One component, opened from the header buttons and the row actions. Steps in one surface: ticket, firm price
  with countdown, Accept / Withdraw, success with a link to the deal. Same logic and server checks as the
  current panel; only its home changes. Uses the `Drawer` primitive.

## 4. Shared pieces to build
1. **`RowActions`** (`components/ui`): the `...` menu that `AccountsPage`, `BeneficiariesPage` and others each
   hand-roll. One accessible primitive (button, popover, keyboard, outside-click, danger tone, divider). Adopting
   it elsewhere is out of scope, but it removes a ninth copy.
2. **`useFxScope`**: reads `useUser().currentCorporateId`; one place to show "pick a corporate" when none.
3. `fxCorporatesApi` removal: use `corporatesApi` only if a list is still needed (it will not be).
4. Split the 742-line `FxRatesPage` untouched; the new code lives in `pages/fx/` (`FxRatesBoard`,
   `FxExposurePage`, `FxDealsPage`, `FxRfqPage`, `NewDealDrawer`, `DealActions`).

## 5. Wiring checklist (the app registers pages in four places)
`navigation.tsx` (section, items, `pageTitles`, `covers`, `keywords` for search); `App.tsx` (`PageType` union,
`renderPage` cases, `treasuryPages` in `sectionFor`); `docs/manual/capture.mjs` page list; the `?page=` deep link
for each new id. Remove the FX item from Liquidity Management.

## 6. Decisions needed from the user
1. **Menu name and slot**: "Foreign Exchange" after Liquidity Management (recommended) or elsewhere.
2. **New Deal as a drawer** launched from the header and rows (recommended) or a fifth menu item "New Deal".
3. **Rename the Administration entry** to "FX Rate Table" to avoid two "Rates" (recommended) or leave both.
4. **Per-deal Settle action**: add `POST /fx/deals/{id}/settle` (small backend change) or keep settlement bulk only.
5. **Role gating** for Confirm, Dispute, Close out and Roll with `PermissionGate`: now (needs a rule for who may
   deal) or keep deferred with maker-checker.

## 7. Verification plan
`tsc` and `eslint` clean; each page opened live at 1440 and about 800 wide in light and dark; walk the full
story (request a price, accept, confirm, draw down, close out, roll, dispute, settle) using only the row menus;
header switcher changes the data on every FX page; deep links `?page=fx-deals` etc.; old `fx-workspace` link
lands on Deals; sidebar highlight, auto-expand and search find the new items; no test data left in the DB.

## 8. Order of work
1. Nav section, page ids, routing, `useFxScope`, `RowActions` (shell with the four pages reading from existing panels).
2. Rates, then Deals (+ `DealActions`, `NewDealDrawer`), then RFQ Log, then Exposure & Hedging.
3. Delete `FxWorkspacePage` and the old panels once each is replaced; verify; update this doc and `lessons.md`.
