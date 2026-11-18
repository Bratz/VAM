# UI density audit — text, busyness and duplication (18 Nov 2026)

Read-only, code-based audit of every page in the navigation, section by section: where pages carry too much
explanatory text, are visually busy, or repeat the same figure, fact or explanation (on one page, or across
tabs/pages). Each finding cites `file:line` (paths under `frontend/src/`) and a concrete fix, preferring deletion
and merging. Severity: **High** = confuses or misleads, or dominates the page; **Medium** = noticeably busy;
**Low** = polish.

## Summary

48 pages audited (five parallel reviews). Verdicts: **Very busy** — Dashboard, Statements, Transfers, Receivables,
Wallets, Simulator, In-House Bank, Exposure & Hedging, Credit Limits, Interest Configuration, Bank Accounts, VIBAN
Management, Balance Hierarchy, Entity Balance Tree. **Clean or nearly** — FX Reports, Settlement Instructions,
E-Commerce Collections (but a duplicate page), Programs list. Everything else is **Busy**.

The patterns are the same everywhere, which makes the fixes mostly deletions:

1. **Wrong or made-up numbers (fix first — they mislead, not just clutter).** Hard-coded nav badges (2, 1, 12);
   hard-coded trends (+8.2%, +12%); demo-data fallbacks on All Transactions and Currency Mirrors; sums across
   currencies shown in one currency (Credit Limits, Interest Configuration, Cash Forecast "All ccy", Netting Savings
   in hard-coded AED, Entity Balance Tree section totals, Bank Accounts bank cards); Transactions Credits/Debits tabs
   showing the same count; Netting "Without netting N payments" counting entities; Cash Concentration headline
   ignoring the corporate filter; Bank Accounts "not linked" counting one page; three VIBAN utilisation formulas;
   two Dashboard copy lines contradicting the page ("no grand total", "live rates"); Create Receivable's "created as
   draft"; fake hierarchy options in Wallets.
2. **Controls that do nothing or the wrong thing** (~30): Export/Filters/Date Range/History/View All/Configure
   buttons with no handler; toast-only Investigate/Write Off; Edit Account opening an empty create form; a Program
   picker "sent to nothing" on In-House Bank; the Settings nav item leading to "coming soon".
3. **Stat tiles that repeat tab badges, the hero or the table** — on almost every page. Delete the strip; put the
   one or two counts that matter in the hero line or the filter.
4. **The same thing explained 2–4 times** — "simulated" on every FX surface, the return window, settlement
   mechanics, benefit definitions, default-rate notices, ISO 20022 branding, "will generate on save".
5. **Whole pages or tabs that duplicate each other** — four hierarchy trees; Multi-Bank Liquidity's four "By X"
   tabs; the Dashboard's accounts table vs Multi-Bank Liquidity; E-Commerce vs Seller Collections; FX Rate Table vs
   Market Rates; Statements' three download paths and two statement views; In-House Bank's Entities and Current
   Accounts tabs vs Position; Netting's Overview/Positions/Settlement tabs vs the settle modal; Account Linking vs
   the Bank Accounts attach modal.
6. **Internal words on screen** — shadow, mirror, PHYSICAL_MIRROR, ROOT, M-Node, GrpHdr/CreDtTm, table names
   (`virtual_accounts`, `corporate_id`), `CreditLimitService`, raw enums (PENDING_APPROVAL, VIRTUAL_ACCOUNT).

**Decision needed:** In-House Bank vs Intercompany still share two figures (an entity's IHB balance; "net owed to
entities"), which spec R3 asks for and acceptance 3 forbids. Either Intercompany shows one IHB row per pair linking
to In-House Bank › Position, or acceptance 3 is amended. See the Liquidity section.

**Suggested order:** (1) wrong numbers and dead controls — small, mostly one-line deletions across many files;
(2) stat strips, repeated explanations and jargon, page by page; (3) structural merges (one hierarchy tree, Multi-Bank
grouped table, Statements single path, Collections pages, In-House Bank set-up table, Netting tabs), each its own change.

---

## Foreign Exchange and Administration

Overall: the FX pages are structurally tidy but repetitive ("simulated" said 3–4 times per page, descriptions
re-explaining columns). The Administration pages are older and much busier: duplicated stat tiles, figures summed
across currencies, dead buttons, and corporate pickers that ignore the header's corporate.

### Market Rates (`fx-market`, `pages/fx/FxMarketPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Whole page | DUPLICATE | "Simulated" 4×: description (:324), rate-board badge (:344), curve badge (:372), caption "They are simulated…" (:406) | Keep the rate-board badge only |
| Med | PageHeader | TEXT-HEAVY | Four-clause buy/sell explanation (:324); columns already say "You sell"/"You buy" (:249, :256) | "Your buy and sell prices at the amount you choose. Click a price to deal." |
| Med | Rate board header | DUPLICATE | Subtitle (:339) repeats the description and the "Prices for [amount]" input (:360) | Cut the subtitle |
| Med | Stat strip | DUPLICATE | Feed tile (:331-332) = board badge "Feed unavailable" (:341); "Pairs listed" (:328) counts table rows | Drop the stat strip; "as of {time}" in the board subtitle |
| Low | Curve card | DUPLICATE | Subtitle repeats the board's selected row (:371) | "Spot value {date}" |
| Low | Rate alerts | TEXT-HEAVY | Subtitle (:413) repeats the empty state (:421) | "Fires once, in the bell." |

### Exposure & Hedging (`fx-exposure`, `pages/fx/FxExposurePage.tsx`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | PageHeader | TEXT-HEAVY | ~70-word description listing every source (:228) | "Currency exposure by time bucket against your hedge policy. Amounts stay in their own currency." Sources list → upload dialog |
| High | Default-policy warning | DUPLICATE | N+1 times: tile sub (:237) + "Default policy" badge on every currency card (:264) | One warning `Alert` above the cards |
| Med | Currency card header | DUPLICATE | "Within/Outside policy" badge (:265-267) repeats row Status badges (:180-185) and the tile (:235) | Cut the card badge |
| Med | Ladder table | BUSY-LAYOUT | Hedge ratio bar + % (:179), Status badge (:181), "To reach policy" (:188): one fact three ways | Merge bar and Status; keep "To reach policy" |
| Low | Currency card subtitle | DUPLICATE | "Amounts are in {ccy}" (:260) repeats the title (:258) | "1 {ccy} = {rate} {base}" |
| Low | Declared entries | DUPLICATE | Tile (:238) = card subtitle "{n} entries" (:284) | Drop the tile |
| Low | Upload modal | TEXT-HEAVY | 3-sentence column spec (:319-323) | Keep the rejection rule; the template carries the format |

### FX Deals (`fx-deals` / `fx-workspace`, `pages/fx/FxDealsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | PageHeader | TEXT-HEAVY | Explains row menu, settlement mechanics and simulation (:208) | "Your FX deals with the bank. Open a row's menu to confirm, roll, draw down or close out." |
| Med | Settlement mechanics | DUPLICATE | Same sentence in description (:208), Run-settlement modal (:311) and Settlement Instructions (`FxSettlementPage.tsx:113`) | Keep it in the modal only |
| Med | Run-settlement modal | DUPLICATE | Both paragraphs say a later date settles early (:311, :313-315) | One sentence |
| Med | Simulated messaging | DUPLICATE | Description (:208), badge (:236), tile sub (:222), modal (:313), drawer footer (`FxDealDrawer.tsx:380`) | Card badge only |
| Low | Stat tiles | TEXT-HEAVY | "Open deals" sub = corporate name (:219); label "FX line (default, not agreed terms)" (:223) | Drop the sub; "default" → badge/tooltip |
| Low | Export | DUPLICATE | Export CSV (:145-147, :255) = Reports "Deal history" (`FxReportsPage.tsx:20`) | Keep one |
| Low | New-deal drawer | DUPLICATE | "Simulated" 3× (`fx/NewDealDrawer.tsx:357, :401, :437`) | Badge only |

### RFQ Log (`fx-rfq`, `pages/fx/FxRfqPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | PageHeader / Hit rate | JARGON, DUPLICATE | Hit-rate formula in description (:171) and tile sub (:182); "settled" means value-date settlement elsewhere in FX | Description "Every price request, including those that never became a deal."; sub "accepted of decided" |
| Med | Tiles vs filter | BUSY-LAYOUT | 5 outcome tiles (:176-182) plus an Outcome select (:204) for the same split | Make the tiles the filter; remove the select |
| Low | Card header | DUPLICATE | "{n} shown" (:188) repeats the Requests tile | Drop the subtitle |

### Settlement Instructions (`fx-settlement`, `pages/fx/FxSettlementPage.tsx`) — Mostly clean
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | PageHeader | TEXT-HEAVY | Three sentences of rules (:113) the tile subs repeat (:118, :120) | "The account each entity's FX deals settle through, per currency." |
| Low | Stat strip | DUPLICATE | "Entity currencies" (:117) = Ready (:118) + Need an account (:119) | Drop the first tile |
| Low | Table | DUPLICATE | "Choose one / No account" badge (:98) repeats the reason text (:91) | Hide the badge when there is no account |

### FX Reports (`fx-reports`, `pages/fx/FxReportsPage.tsx`) — Clean, one duplicate
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | Report card | DUPLICATE | `report.hint` as subtitle fallback (:115) and caption (:116); card title (:115) repeats the tab (:109) | Drop the title; subtitle = period; hint once |
| Low | Overlap with FX Deals | DUPLICATE | MTM statement vs "Worth today"; confirmation archive vs row action (`FxDealsPage.tsx:189`) | Acceptable as the export surface |

### Credit Limits (`credit-limits`, `pages/CreditLimitsPage.tsx`, `components/credit/FxLineCard.tsx`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Stat row | DUPLICATE | Currency count 4×: "Currencies" (:532), "Total Limits … currencies" (:543), group card (:421), currency tabs (:430) | Delete the stat row (:512-589, used only at :1263) |
| High | Stat row | DUPLICATE (misleading) | Utilisation "across all currencies" sums AED+USD+EUR (:518-524, :544, :573) | Same: delete the row |
| High | Group limit card | DUPLICATE (misleading) | Allocated/unallocated in bar label (:478-479) and tiles (:486-491); bar says "avail." while the Available tile (:499) shows another number | Bar + Utilized and Available tiles only |
| High | Page layout | DUPLICATE | Internal limits tree and FX line sub-limits (`FxLineCard`, :1322) both list entities with limit/used/left, relationship unexplained | Tabs "Credit limits" / "FX line" |
| High | Corporate picker | INCONSISTENT | Local select (:1243) defaults to the first corporate (:1080), ignores the header corporate | `useUser().currentCorporateId`; delete the picker |
| Med | Entity hierarchy | DUPLICATE | Subtitle (:1273) repeats "Entity Limits x / y" tile (:561) | Goes with the stat row |
| Med | Currency chips | BUSY-LAYOUT | 3 icon buttons per chip (:699-701) plus "Add Limit" (:664) | One `RowActions` menu |
| Med | Header | INCONSISTENT | "Export" has no onClick (:1222-1228) | Remove |
| Low | Tree column | JARGON | "External" (:656, :1292) | "Bank limit" |
| Low | FX line card | TEXT-HEAVY | "Each entity deals within its own sub-limit." (`FxLineCard.tsx:100`) | Cut |

### Interest Configuration (`interest-config`, `pages/InterestConfigurationPage.tsx`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Config grid | BUSY-LAYOUT | One `ConfigCard` per config with credit/debit panels, dates and 3 buttons (:178-252, :797-807) | One `DataTable` (Name, Type, Target, Ccy, Credit %, Debit %, Status, Effective from); row → detail |
| High | Stats | DUPLICATE (misleading) | Total = External + Internal (:706-722); average rates across currencies (:724-733); stats whole-corporate (:416) while the grid is program-filtered (:424-428) | Drop all 5 cards |
| Med | Filter bar | DUPLICATE | "Selected" badges repeat dropdown values (:339-355); two filter cards (:286, :739) | One toolbar, no badges |
| Med | Corporate picker | INCONSISTENT | Local select defaults to the first corporate (:295, :458) | `useUser` |
| Med | Cards / modal | JARGON | Raw enums "PENDING_APPROVAL", "VIRTUAL_ACCOUNT", "EXTERNAL" (:189, :194, :834, :838); label maps exist (:62-81) | Render the labels |
| Low | Create form | INCONSISTENT | Status select allows "Active" (:1007-1016) while the modal has Activate (:928) | New configs start as Draft |
| Low | Actions row | INCONSISTENT | Refresh/New Config in-page (:682-689) | Header actions |

### FX Rate Table (`fx-rates`, `pages/FxRatesPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Naming / overlap | INCONSISTENT, DUPLICATE | Three names: "FX Rate Table" (`config/navigation.tsx:201`), "FX Rates" (:515), "FX Rate Management" (`navigation.tsx:264`); Bid/Ask like Market Rates with different numbers; dashboard "FX deal" action lands here (`Treasury2030DashboardPage.tsx:1228`) | "Reference Rates" everywhere; "Rates the platform uses to value balances and reports; not dealing prices."; drop Bid/Ask (:155-178); dashboard link → `fx-deals` |
| Med | Header vs tiles | DUPLICATE | "{n} rates stale" (:518-522) = Stale tile (:547) | "Last refresh {x}" |
| Med | Table | DUPLICATE | "Updated" (:205) and freshness badge (:206); Inverse column = 1/rate (:146) | One Status cell (time in tooltip); Inverse → drawer |
| Med | Freshness | INCONSISTENT (misleading) | 15-min threshold applied to Fixing/Internal/Contract rates (:61) | Score Spot only |
| Med | Row refresh | INCONSISTENT (misleading) | Per-row refresh refreshes everything (:389-397, :660) | Remove |
| Med | Drawer | DUPLICATE, TEXT-HEAVY | Rate printed twice (:673, :675); Status vs Active (:706-712); dev placeholder "Historical rate trend will appear here…" (:720) | Remove :675, :712, :718-721 |
| Low | Filters | BUSY-LAYOUT | Filter card (:560-592) + chip row (:595-608) | `DataTable` search/actions |
| Low | Converter | DUPLICATE | Subtitle (:731) and caption (:319) both say mid-rate | Caption only |

### Tax & Charges (`tax-charges`, `pages/TaxChargeSetupPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Quick Reference | DUPLICATE (misleading) | Hard-coded "UAE VAT 5% / KSA 15% / UK 20% / India GST 18%" (:437) beside the configured table | Delete |
| High | Jurisdictions tab | INCONSISTENT | "Add Jurisdiction" does nothing there (:429); edit pencil `onEdit={() => {}}` (:435) | Hide both |
| Med | Stat row vs tabs | DUPLICATE | Counts (:431) repeat tab badges (:433) | Drop (or keep "Withholding taxes" only) |
| Med | Header | INCONSISTENT | Export/Import are `console.log` stubs (:423-429) | Remove |
| Low | Tax table | DUPLICATE | Type icon (:200) + Type badge (:206); "WHT" flag when type is WHT (:230) | Drop the icon and redundant flag |
| Low | Chrome | INCONSISTENT | Title "Tax & Charges Setup" (:421) vs nav; custom `TabButton` (:146), no `Page`; raw "ACTIVE" | Align with nav, `Tabs`, `Page`, labels |

### Integrations (`integrations`, `pages/IntegrationsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Tabs vs stats | INCONSISTENT (misleading) | Tab "Active Connections" (:1181) lists all, incl. Error/Inactive; "Active" stat (:1162) counts connected only | Tab "Connections" |
| High | Dead buttons | INCONSISTENT | API Docs (:1145), Documentation (:133), Re-authenticate (:803), Settings (:839), Credentials (:843), certificate drop zone (:450) | Remove |
| Med | Stats | DUPLICATE | Total/Connectors (:1161, :1165) repeat tab counts (:1181-1182); `sub` texts never rendered | Keep Errors only, as an alert |
| Med | Connection card | INCONSISTENT | Trash tooltip "Disconnect" (:231) but it deletes (:1220) | "Delete connection" |
| Low | Category chips | JARGON | "Erp" (:1258) | `categoryConfig[cat].label` |
| Low | Chrome | INCONSISTENT | Custom stats/tabs/black buttons (:1159-1199) | Design system when next touched |

### Settings (`settings`, `App.tsx:647`) — Placeholder
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Nav item | INCONSISTENT | Nav entry (`config/navigation.tsx:204`) → "Settings page coming soon..." (`App.tsx:647`) | Remove the nav entry |

**Top fixes (FX & Administration):** delete the Credit Limits stat row and group-card mini tiles; remove the stubs and
placeholders (Settings nav, Tax Export/Import/Quick Reference/no-op jurisdiction actions, Credit Limits Export, 6
Integrations buttons, FX Rate Table drawer placeholder); separate FX Rate Table ("Reference Rates") from Market Rates;
say "Simulated" once per FX surface; one-sentence Exposure description with one default-policy alert.

---

## Home and Parties & Entities

### Dashboard (`dashboard`, `pages/Treasury2030DashboardPage.tsx`) — Very busy (~16 text blocks, 9 sections + rail + tabs + footer)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Description vs headline | DUPLICATE (misleading) | "there is deliberately no synthetic cross-currency grand total" (:528) above a "Consolidated position" in one currency (:631-645) | "Cash, payments and FX across your connected banks." or no description |
| High | Consolidated sublabel | INCONSISTENT (misleading) | "FX-converted, live rates" (:640); Multi-Bank Liquidity says indicative, "not a live market feed" (`components/multiBank/OverviewView.tsx:359-363`) | "converted at indicative rates" |
| High | "Consolidated position" on 2 pages | INCONSISTENT | Dashboard sums the entity hierarchy, excluding unassigned bank cash (:633); Multi-Bank sums account balances × rates (`OverviewView.tsx:164-174, :351`): same label, two numbers | Rename one (e.g. "Position in programs") or share one source |
| High | Currency breakdown toggle | BUSY-LAYOUT (misleading) | "By currency / By bank" sits on the chart (:659-675) but only changes the Accounts table below (:483-505); the chart ignores it (:686) | Move it to the Accounts table header (:825) |
| High | Accounts table | DUPLICATE (cross-page) | Groups by currency/bank with freshness and refresh (:822-923) = Multi-Bank By Bank/Currency tabs; "Full liquidity view" link already there (:832) | Delete the table (keep the link, or top-5 stale only) |
| High | "avail." subtotal | INCONSISTENT | Subtotal "X avail." sums effective balance (:502) next to an Available column with another figure (:897-899) | "effective", or goes with the table |
| Med | Account counts 3× | DUPLICATE | Scope row (:596), Total accounts tile (:650), footer (:1256) | Keep the tile |
| Med | Stale count 3× | DUPLICATE | "Refresh all (N stale)" (:402), Stale tile (:649), footer (:1260) | Cut the footer |
| Med | Position strip | DUPLICATE | Home + External = Total accounts (:647-650) | One tile "N accounts · N at home bank" |
| Med | Tab strip | DUPLICATE | Payments/Receivables/Liquidity/FX/Admin repeat the sidebar (:87-95); disabled "Reports · Soon" (:575-579) | Delete the strip |
| Med | Quick actions | DUPLICATE | New payment (= header button :407), Statement/Run sweep/FX deal (= card links :1113, :1069, :1199); jargon subs "Wire · ACH · SEPA", "NL assist", "CAMT · MT940" (:1227-1232) | Delete the card (or keep Ask Aperture / Open account) |
| Med | Payments card | DUPLICATE | "N to action" badge (:780) vs footer "N items pending approval (incl. N transactions)" (:815) with different counts; two links to the same page (:788, :816) | Badge + one link |
| Med | Position breakdown | BUSY-LAYOUT | Five toggles (:964), two only say "Select a corporate above" (:998, :1027); "By country" = Multi-Bank tab | By corporate / program / entity; hide corporate-only views until one is picked |
| Med | Sweeps & pooling | DUPLICATE | "Composition · {ccy} (largest balance)" bar (:1078-1079) repeats the chart tooltip split (:712-716) and Multi-Bank bars | Delete the bar |
| Med | Action queue | INCONSISTENT | Action label with arrow (:1182-1184) but no click handler | Wire it or remove the arrow |
| Low | Title | INCONSISTENT | Header "Cash position" (:523) vs nav "Dashboard" | One name |

### Multi-Bank Liquidity (`multi-bank-liquidity`, `pages/MultiBankLiquidityPage.tsx`, `components/multiBank/*`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | By Bank / Currency / Entity / Country tabs | DUPLICATE | Same accounts as a card per group with near-identical columns and the same filter chips (`ByBankView.tsx:218-255`, `ByCurrencyView.tsx:~86-120`, `ByEntityView.tsx:~41-60`, `ByCountryView.tsx:~53-75`) | One paged DataTable with "Group by: Bank / Currency / Entity / Country"; tabs 6 → 3 (Overview, Accounts, Trend) |
| High | Overview "By currency" | DUPLICATE | Total + bank split per currency (`OverviewView.tsx:556-605`) = By Currency tab (`ByCurrencyView.tsx:145-170`) | Delete the section |
| High | Stale/Failed/Never tiles vs chips | DUPLICATE | Tiles (`OverviewView.tsx:498-524`) and chips (:618-620) show the same counts and both jump to By Bank | Delete the chip row (:612-641) |
| Med | Freshness banner | DUPLICATE | Banner + "Refresh all" (`OverviewView.tsx:530-551`) vs header "Refresh stale" (`MultiBankLiquidityPage.tsx:250-264`) and the tiles | Delete; move the exclusion note to the Stale tile tooltip |
| Med | Header actions | BUSY / JARGON | "Refresh stale" and "Reload view" share an icon (:262, :270) | Keep "Refresh stale"; "Reload view" → "Reload" or remove |
| Med | Vocabulary | JARGON | "Total Shadows" (:494), "mirror(s)" (:580, `ByBankView.tsx:201`), "Shadow" column (:223), "Effective", "Committed", "In transit (pending sweeps)" | "accounts"; "Effective" → "Balance after holds" |
| Med | By Bank footer | DUPLICATE | "Effective by currency" chips (`ByBankView.tsx:264-279`) repeat card subtotals (:207-215) | Delete |
| Low | Hero | TEXT-HEAVY | Badge, excluded note, In transit, picker, period tabs, sparkline in one block (`OverviewView.tsx:355-431`); sparkline = Trend tab | Badge + period toggle; drop the sparkline |

### Statements (`statements`, `pages/StatementsPage.tsx`, `components/statements/*`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Three download paths | DUPLICATE | Generator (:1612-1743); Download Panel with its own dates and "Include Child Accounts" default false vs generator "Include Children" default true (`StatementDownloadPanel.tsx:125-134, 248-383`); XML/PDF/CSV/MT940 buttons (:1799-1831); format lists differ | Delete the Download Panel and its toggle (:1383-1390, :1597-1609); one date range, one Download menu |
| High | Inline view vs Preview modal | DUPLICATE | Modal re-renders the same sections (`StatementPreviewModal.tsx:397, 1045-1133, 1228`) as :1836-2131 | History "View" loads inline; drop the modal (or the reverse) |
| High | Closing / net / counts | DUPLICATE | Hero (:1490-1502), Transaction Summary (:1991-2029), Balances card (:1937) / legacy stat (:1979) | Delete Transaction Summary |
| Med | Period 3× | DUPLICATE | Hero sub (:1494), "Period: … to …" (:1795), Statement Header From/To (:1872-1873) | Hero only |
| Med | Stat strip | BUSY | "Statements in History", "Accounts available", "ISO 20022 ready", "Latest generated" (:1509-1550) | Delete; history count in its header |
| Med | ISO 20022 branding | TEXT-HEAVY / DUPLICATE | Description (:1462), page badge (:1467-1470), card title + subtitle (:1621-1622), result badge (:1780) | "View, download and look up past account statements."; card "Statement"; no badges |
| Med | Mode buttons | JARGON / BUSY | "camt.053 - Account Statement", "camt.054 - Notifications", "Aggregated", "Standard" as 4 large buttons (:1363-1368) | Segmented "Statement · Notifications · Aggregated" |
| Med | Notification type 3× | DUPLICATE | Header badge (:1781-1788), section badge (:1909-1916), Type row (:1920) | Header badge only |
| Low | Entry list | TEXT-HEAVY | "Click on any entry to expand details" (:2063-2065) | Cut |
| Low | Two hierarchy panels | DUPLICATE | `VAHierarchyViewer` (:1553-1572) and "Legacy Hierarchy View" (:1574-1595) | Delete the legacy card |

### Legal Entities (`legal-entities`, `pages/LegalEntitiesPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Legend card | INCONSISTENT (misleading) | Legend icons differ from tree badges: Bank Customer Landmark (:1537) vs Wallet "Bank" (:843); Treasury Center Landmark (:1533) vs Crown "Treasury" (:848), Crown also = Holding (:1521) | Delete the legend (:1516-1549) |
| High | Treasury/IHB counts | DUPLICATE | Hero sub "N treasury centers · N IHB-enabled" (:1377-1382) = "IHB Enabled" tile + sub (:755-758) | Drop one |
| Med | Detail panel flags 3× | DUPLICATE | Header pills (:957-959), "Status: Bank Customer" (:1055), capabilities grid (:1075-1080); Type 2× (:956, :1064) | Keep the capabilities grid; one "Core banking ID" row |
| Med | Tree row | BUSY | Treasury: icon + badge + coloured border (:818, :834, :846-850); every row: Bank, IHB, status, BaNCS ID, country, currency, limit block (:840-900) | Drop the border and ID chip; status only when not Active |
| Med | Entity count | DUPLICATE | Hero (:1372) = Hierarchy header (:1407) | Cut the header count |
| Med | Corporate selector | DUPLICATE | Name echoed beside the dropdown (:1348-1352) | Delete; use `ScopeSelector` |
| Med | Form, Treasury tab | JARGON / TEXT-HEAVY | "…tracked via CreditLimitService." (:649); two banners (:644-650, :655-657), one repeating the checkbox label (:667) | Cut both |
| Med | Form, Basic tab | TEXT-HEAVY | "Fields marked with * are mandatory." (:516-518) | Delete |
| Med | Header Export | INCONSISTENT | No onClick (:1312-1314) | Remove or wire |
| Low | Limit block | TEXT-HEAVY | "From Group Limit"/"Sub-limit from parent pool" under every limit (:1011), (:1019), (:1036) | Tooltip |
| Low | Jargon | JARGON | "With BANCS accounts" (:744), "BANCS ID" (:1056) | "Bank customers", "Core banking ID" |

### Parties & Counterparties (`parties`, `pages/PartiesPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Same flag, different fields | INCONSISTENT (can contradict) | Badge/table read `poboEligible` (:1601, :2554), Overview reads `canReceivePoboPayment` (:1658); Netting badge `nettingEligible \|\| isIntercompany` (:1603, :2556) vs `canParticipateInNetting` (:1660) | One field per flag |
| High | Edit form vs detail modal | DUPLICATE | Edit form has Bank Accounts/Documents/Compliance tabs (:578-584), Compliance read-only "managed from the View Details modal" (~:1230); detail modal has the editable versions (:1442-1449, :1667, :1840-1960) | Drop Compliance from the edit form; bank accounts and documents in one place; cut the "View Details" note (:1314-1320) |
| High | Detail flags 3–4× | DUPLICATE | Header badges (:1599-1605), Overview "Payment Factory" (:1656-1661), POBO tab (:2024-2053), IC tab (:2056-2147) | Delete the Payment Factory card |
| Med | Overview Compliance | DUPLICATE | KYC/Risk/Sanctions (:1650-1655) = Compliance tab (:1850-1984) | Drop from Overview |
| Med | Filter cards + Active Filters | DUPLICATE / BUSY | Six gradient filter cards (:2412-2477) + "Active Filters:" chips repeating them (:2480-2489) | Segmented All/Customers/Vendors + 3 toggle chips; delete the row |
| Med | POBO / IC tabs | TEXT-HEAVY | Banners (:2032-2034, :2064-2066, :2070) and a static 3-step flow diagram (:2041-2050) | Key/value rows only |
| Med | Row actions | DUPLICATE / INCONSISTENT | Eye = row click (:2514, :2581); "…" no handler (:2583); header Export no handler (:2333) | Edit only; remove dead buttons |
| Low | Status | DUPLICATE | Error-toned icon (:2527) + Status column (:2570); Netting badge on every IC party | Status badge only when not Active |
| Low | Empty scope | TEXT-HEAVY | Warning card "Please select a corporate…" (:2391-2398) | `EmptyState` or preselect |

**Top fixes (Home & Parties):** two-line Dashboard copy fix (no "no grand total" claim; "indicative rates"); move the
Dashboard toggle or delete its Accounts table, tab strip, Quick actions and footer stamps (~40% less page);
Multi-Bank Overview deletions (chips, banner, By currency section), then one grouped table instead of four tabs;
Statements: one download path and one set of figures; Parties/Legal Entities: delete duplicate panels, the
misleading legend and "CreditLimitService".

---

## Liquidity Management

### Cash Forecast (`forecasting`, `pages/ForecastingPage.tsx`) — Busy (mild)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Currency picker "All ccy" | INCONSISTENT (misleading) | Empty = all currencies summed (:338) then formatted as one currency `summary?.currency \|\| currency \|\| 'USD'` (:294) | Remove "All"; default to base currency (or per-currency totals) |
| Med | Header strip card | DUPLICATE | "Cash Forecast" eyebrow (:306-309) repeats the page title | Timestamp → PageHeader description; controls → header actions; delete the card |
| Med | Category breakdown | BUSY-LAYOUT | One column per week (:686-693): 13 at 13w, ~52 at 12m | Monthly at 12m, or totals + drawer |
| Med | Chart | BUSY-LAYOUT | Two unlabelled Y axes (:509-525) | Label both or one axis |
| Low | Empty state | DUPLICATE | Two "Run forecast now" buttons (:355, :847) | Empty-state button only |
| Low | Tiles / drawer | JARGON | "Starting point for this projection" (:448); "% conf" (:811); "Pattern / AR aging / ML / Driver" (:77-83) | Cut; "Recurring / Open invoices / Model / Manual" |

### Cash Concentration (`sweeping`, `pages/CashConcentrationPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Hero + stat strip | INCONSISTENT (misleading) | `totalSwept` from all `rules` (:260), `todaySwept` from all executions (:254), stats unfiltered (`hooks/useSweeping.ts:121-122`): the corporate picker changes the cards but not the headline | Compute from `filteredRules` and their executions |
| Med | Execution History | INCONSISTENT | History and tab count ignore scope (:264); "today" from the latest 20 rows (:252) | Scope it; label "last 20 runs" or a backend daily total |
| Med | "Linked Accounts" tile | DUPLICATE (misleading) | Sums `sourceAccounts.length` across rules, double counting (`useSweeping.ts:122`); "Active Rules" = tab count (:263) | Cut the strip |
| Med | Rule Details modal | BUSY-LAYOUT | ~9 nested Cards, one per field (`components/concentration/ViewRuleModal.tsx:28-154`) | One definition list + source list |
| Low | Tabs | INCONSISTENT | Hand-rolled tabs (:331-352) | `Tabs` |
| Low | Rule card | JARGON | Raw `status`/`frequency` (`RuleCard.tsx:31, :71`) | Labels |

### Simulator (`simulator`, `pages/SimulatorPage.tsx`, `components/simulator/*`) — Very busy (~12 text blocks)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Top of page | BUSY-LAYOUT / DUPLICATE | Two toolbars: Scenario select card (:1050-1105) and `ScenarioHeader` (:1136), name in both | One header row; Archive/Fork/Inventory in an overflow menu |
| Med | Header copy | DUPLICATE / JARGON | `SandboxBadge` in description (:1021) and `ScenarioHeader.tsx:91`; "Sandbox over your three-layer model…" (:1023-1024) | One badge; "Try out sweep and pool structures without changing live accounts." |
| Med | Score panel | DUPLICATE | Net benefit twice (`ScorePanel.tsx:95-98, 115-120`); caveats in panel (:173-184) and modal (:256-269); filler (:99-102) | Benefit once; caveats in the modal |
| Med | Diff vs live | JARGON / DUPLICATE | Banner names DB tables "virtual_accounts and sweep_rules" (`DiffView.tsx:100-104`), repeated in `ProposeDrawer.tsx:77-80`; "· N changes" (:63) = `DiffSummaryStrip` | Delete the banner; plain propose warning |
| Med | Proposed structure | JARGON | "Physical · Shadow", "Shadow VA", "INTERNAL" (`StructureView.tsx:139-144`, `AddPoolDrawer.tsx:170`, `AddShadowDrawer.tsx:145`) | "bank accounts", "planned accounts" |
| Low | Inventory modal | DUPLICATE | Title = panel heading (`StructureView.tsx:243`, `InventoryPanel.tsx:127`) | Drop the inner heading |
| Low | Fork / status | TEXT / JARGON | 3 bullets (`ForkDialog.tsx:58-60`); raw status (`ScenarioHeader.tsx:93`) | One line; label |

### Notional Pooling (`notional-pooling`, `pages/NotionalPoolingPage.tsx`, `components/pooling/*`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | Corporate badge | DUPLICATE | Repeats the ScopeSelector (:281-290) | Delete |
| Med | Stat strip | DUPLICATE | Counts the grid already shows (:319-348) | Cut, or "n active" in the hero |
| Med | Benefit definition | DUPLICATE | "Benefit = pooled − standalone" (:312-313, `PoolDetailModal.tsx:176, :356`) | One tooltip |
| Med | Pool detail header | DUPLICATE | 8-figure grid (`PoolDetailModal.tsx:140-191`); net and last-day interest = first Daily runs row (:144, :160 vs :389, :392); footer "Last Calculated" (:269) | Keep MTD/YTD, rates, breaches |
| Low | Captions | TEXT-HEAVY | Four-clause captions (:356, :459) | "Click a date to see its members." |
| Low | Pool card | JARGON | Raw `pool.status` (`PoolCard.tsx:39`); method half-humanised (`PoolDetailModal.tsx:262`) | Labels |

### In-House Bank (`ihb`, `pages/InHouseBankPage.tsx`, `IhbBookPanels.tsx`, `Cobo/PoboEnrolmentsPanel.tsx`) — Very busy (8 tabs, ~16 blocks + ~10 modal captions)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Scope row | INCONSISTENT | Program picker rendered (:528-542) but "sent to nothing" (:531-534) | `corporate-only`; delete the badges (:547-564) |
| High | Current Accounts tab | DUPLICATE | Cards (:683-835) repeat balance, available, limit, accrued (= Position `IhbBookPanels.tsx:84-88`) and rates (= Interest :332-333) | Set-up DataTable: account, entity, currency, sweep, opened |
| High | New Current Account modal | TEXT-HEAVY / INCONSISTENT | ~10 captions (:855-1035); "Use Treasury Rates" copies indicative rates into overrides that apply only without a configuration (:863-885, :984) — against D10 | Delete the preview and intro; overrides and sweep under "Advanced" |
| Med | Entities tab | DUPLICATE | Cards show role + credit limit only (:253-311); limit = Position (:85) | Merge into the accounts table (role column, Open account); 8 → 6 tabs |
| Med | Stat strip | DUPLICATE | Counts (:596-617) = tab badges (:511-512) and "{n} IHB Current Accounts" (:686) | Cut |
| Med | New account button 3× | DUPLICATE | Header (:434), tab (:687), empty state (:831) | Header + empty state |
| Med | Position / Changes intros | TEXT-HEAVY | 3-sentence (`IhbBookPanels.tsx:66-70`) and 2-sentence (:404-407) intros | "Positive balance: the in-house bank owes the entity."; Changes: headers suffice |
| Med | POBO cut-over | JARGON | "Treasury's mirrors set from each IHB position" (`PoboEnrolmentsPanel.tsx:247`) — R19 bans "mirror"; one-off migration buttons (:73, `CoboEnrolmentsPanel.tsx:72-76`) | "In-house balances corrected"; migrations in an overflow menu |
| Low | Default-rate notice 3× | DUPLICATE | Card caption (:794), Interest warning (`IhbBookPanels.tsx:339`), row tag (:356) | Row tag + one warning |
| Low | POBO headroom | DUPLICATE | Enrolments "IHB headroom" (`PoboEnrolmentsPanel.tsx:122`) = Position Headroom (:87) | Keep only if needed to decide |
| Low | Messages / tabs | INCONSISTENT | `alert()` (:443, :469-475); hand-rolled tabs (:622-645) | toast, `Tabs` |

**IHB vs Intercompany (spec D1).** No shared tabs remain, but two figures still appear on both pages: an entity's
IHB balance (`IhbBookPanels.tsx:84`) = Intercompany "MNC-TREASURY owes MNC-UK AED 1,100.01" (`IntercompanyDashboardPage.tsx:82, :126`),
and "Net owed to entities" (`IhbBookPanels.tsx:93`) = the Intercompany control line (:155-156). The spec asks for
both (R3 puts IHB positions on Intercompany; acceptance 3 forbids shared figures) — **needs a decision**: one net
IHB row per pair linking to In-House Bank › Position, or amend acceptance 3. Medium.

### Netting Cycles (`netting-enhanced`, `pages/EnhancedNettingCyclesPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Savings tile | INCONSISTENT (misleading) | `currency="AED"` hard-coded (:1043) over a sum across base currencies (:979-981) | Per currency, or cut |
| High | "Netting Impact" | misleading / DUPLICATE | "Without Netting {positions.length} payments" counts entities, not payments (:539-542); "Reduction %" = Efficiency tile (:553 vs :505-509) | Delete the card |
| High | Create cycle | INCONSISTENT | "Include pending (unapproved) POBO recharges" (:183, "for testing" :100) vs Intercompany "not owed until approved" (`IntercompanyDashboardPage.tsx:131`) | Remove the option |
| Med | Stats + filter | BUSY / misleading | Total/Open/Pending/Settled tiles (:1039-1042) + select (:1049-1060) + count (:1061-1063); "Open" always 0 (:824); Draft missing | One segmented status filter with counts |
| Med | Detail Overview vs card | DUPLICATE | Gross/Net/Savings/% (:489-510 vs :763-790); entries/participants (:516-530, :793-806, :430-431); status 3× (:466-484, :745-750, title) | Delete the Overview tab; open on Entries |
| Med | Positions / Settlement tabs | DUPLICATE | Positions (:632-687) = settle modal "Per entity" (:298-313); Settlement tab (:690-698) = settle modal legs (:317) | One "Settlement" tab reused by Settle |
| Low | Legs | TEXT-HEAVY | LEDGER explanation on every leg (:235-237) | One legend |
| Low | Cycle card | DUPLICATE | Eye icon (:754-759) = "Details" (:843-845); Period shows start only (:803-804) | Drop the eye; full range |
| Low | Header / entries | INCONSISTENT / JARGON | Description only while loading/error (:1015, :1025); raw PAYABLE/RECEIVABLE/sourceType (:609, :618) | One PageHeader; labels |

### Intercompany Dashboard (`intercompany`, `pages/IntercompanyDashboardPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | Hero vs currency sections | DUPLICATE | "Group control: n breaks" with amounts (:395-399) = per-currency `ControlBadge` (:429, :149-158); single-currency hero total (:390-392) = "{x} outstanding" (:427) | Hero: reporting total only |
| Med | Stat strip | BUSY | Four counts of lines/pairs (:405-408) | Cut; "n awaiting approval" badge on its section |
| Med | IHB lines | DUPLICATE (cross-page) | See D1 note above | — |
| Low | Line sections | DUPLICATE | "Net: X owes Y" (:72) repeats a single line (:82) | Net only for 2+ lines |
| Low | Hero sub / netting modal | TEXT-HEAVY | Rate sentence per currency (:161-167); 2-sentence eligibility rule (:248-249) | Tooltip; "Nothing here can be netted yet." |
| Low | Section title | TEXT-HEAVY | "Recharges awaiting approval (not owed until approved)" (:131) | "Awaiting approval" + tooltip |

**Top fixes (Liquidity):** remove misleading Netting figures (Netting Impact card, AED-hard-coded Savings,
"include pending recharges"); make scope controls work (Cash Concentration headline from `filteredRules`; IHB
corporate-only, no dead Program picker; drop scope-echo badges on IHB and Pooling); stop summing currencies in Cash
Forecast; IHB: one set-up table instead of Entities + Current Accounts tabs, no rates preview; Simulator: one header
row, one Sandbox badge, no table-name banner.

---

## Payments & Collections and Specialty Programs

Across pages: **hard-coded nav badges** — `config/navigation.tsx:124, :125, :127` show 2 on Receivables, 1 on Payables,
12 on Exceptions, never matching the pages (High: remove or wire to real counts). **Dead buttons** (no onClick):
Exceptions Filters/Export (`ExceptionDashboardPage.tsx:474, :383`), Escrow Filters/Export (`EscrowPage.tsx:486-491`),
Transactions Date Range and Download Receipt (`TransactionsPage.tsx:1863-1868, :595`), Transfers history Export
(`TransfersPage.tsx:3296`), Wallets Export (`WalletPage.tsx:1258`), both e-commerce Exports
(`EcommerceCollectionsPage.tsx:73`, `SellerCollectionsPage.tsx:63`).

### All Transactions (`transactions`, `pages/TransactionsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Tabs | INCONSISTENT | Credits and Debits both show `stats?.completedCount` (:1652-1653) | Real per-direction counts or none |
| High | Data load | INCONSISTENT | On API failure shows `demoStats`/`demoTransactions` (:1467, :1531, :1538-1539): invented rows | Error `Alert` + empty table |
| Med | Tiles vs tabs | DUPLICATE | Pending tile (:1918) = Pending tab badge (:1654); "Today's Total" (:1926) beside All count (:1651) | Keep the money tiles only |
| Med | View toggle | DUPLICATE | Business view explained in tooltip (:1982) and an always-on banner (:2015-2023) | Tooltip only |
| Med | Header actions | DUPLICATE / BUSY | "New Transaction" (:1878-1884) = Transfers with raw VA IDs (:678-700); "Simulate Collection" (:1870-1877) is a test tool | Remove New Transaction (link to Transfers); move Simulate behind a dev flag |
| Med | Simulate modal | JARGON / TEXT-HEAVY | ISO tag labels "GrpHdr (Group Header)", "(CreDtTm)", "(Ustrd)" (:1056-1376); simulate explained 3× (:988, :996, :1382-1391) | Plain labels; one line |
| Low | Details modal | DUPLICATE | Movement type badge + text (:298, :301); "involved N fund movements" (:466-468) = tab badge (:345-348) | Cut |
| Low | Business view | BUSY-LAYOUT | Grouped table unpaged (:2028-2042) vs Ledger paged (:2050-2054) | Page it |

### Transfers (`transfers`, `pages/TransfersPage.tsx`) — Very busy (~20 text blocks)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Wizard progress | DUPLICATE | 5 signals: Stepper (:2644), Completion % (:420-434), status dot (:769-777), missing list (:794-800), footer (:3257-3260) | Stepper + missing list |
| High | Source balance | DUPLICATE | 5×: option label (:264), selected card (:276-278), sidebar (:464-466), fee preview (:695-696), insufficient box (:758) | Selected card + "Effective available" |
| High | Funds check | DUPLICATE | Caption (:713-717) and red box (:751-764) | Caption only |
| Med | Stats row | TEXT-HEAVY / INCONSISTENT | 4 subtitled tiles (:2545-2569); "Completed" ignores status (:2313-2316) | 2 tiles, no subtitles |
| Med | Type cards | DUPLICATE / JARGON | "ISO 20022 pain.001", "Collection / ROBO" (:2618, :2626) repeated in the sidebar (:381-387); "Selected" badge (:213-217) | Segmented Internal / Outward / Inward / Bulk |
| Med | Inward | DUPLICATE | = Transactions "Simulate Collection" (`TransactionsPage.tsx:1876`) | One entry point |
| Med | History tab | DUPLICATE | Subset of All Transactions with its own detail modal (`TransferDetailModal` :1087 vs `TransactionDetails`) | Link to Transactions filtered, or share the modal |
| Med | Detail modal | DUPLICATE | Header ref/type/date/amount (:1096-1104) repeated in the grid (:1188-1219), raw `movementType` | Cut the grid cells |
| Low | Payer / destination | TEXT-HEAVY | Two lines per section (:2672, :2730, :2787, :2798) | One each |
| Low | Scope bar | INCONSISTENT | Hand-rolled 3-select card (:2394-2493) plus a hidden copy (:2496-2537) | `ScopeSelector` |

### Receivables (AR) (`receivables`, `pages/EnhancedReceivablesPage.tsx`, `components/collections/*`) — Very busy (~18 text blocks)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Stats vs tabs | DUPLICATE | 6 tiles (:879-926); Intercompany / In Netting / COBO repeat tabs (:788-791) | 3 tiles: Total, Not yet due, Overdue |
| High | Tab bar | BUSY-LAYOUT | 8 tabs (:786-795); Intercompany / Pending Netting / COBO History are client filters of All Invoices (:629-645); VIBANs = the VIBAN Management page | One invoices table with filter chips; keep Collection runs, Mandates, Outbox; link VIBANs |
| Med | Search / scope | BUSY-LAYOUT | Search in its own card (:829-841) above the scope card (:846-874) | Table toolbar |
| Med | Header | BUSY-LAYOUT | Select all / Clear in the page header (:801-807) | Header checkbox; "Plan collection (n)" on selection |
| Med | Collection runs | DUPLICATE | Return window explained 3× (`CollectionRunsTab.tsx:129, :137-139`, `CollectionPlanModal.tsx:67`) + a second warning (:140-142) | Hint + "Settle is simulated: no clearing link" |
| Med | Mandates | TEXT-HEAVY | Intro (`MandatesTab.tsx:223-224`); 13 CSV columns in prose (:401-405) | Template + "the whole file is rejected if a row is wrong" |
| Low | Return modal | TEXT-HEAVY | 3-clause caption (`CollectionRunsTab.tsx:344-349`) | "Money goes back, the invoice reopens, a Request to Pay is sent." |
| Low | Invoice detail | INCONSISTENT | Raw enums (:1535, :1570, :1576) | Badge helpers |

**Create Receivable** (`pages/CreateReceivablePage.tsx`)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Summary "Ready" box | INCONSISTENT | "Invoice will be created as draft" (:1813) but Create submits `draft: false` (:1228) | "Invoice will be issued", or delete |
| High | VIBAN | DUPLICATE | "Will generate on save" 3× (:1678, :1681-1686, :1769) + caption (:1670) | Inline note only |
| Med | COBO block | TEXT-HEAVY / JARGON | 3 paragraphs incl. "IHB current account" (:1599-1612) | "Collected by {collector} for {entity}" |
| Med | Optional tabs | DUPLICATE | "Payment Link" tab holds one sentence (:1106, :793); placeholder lists COBO, which isn't a tab (:1730) | Delete both |
| Low | Actions | DUPLICATE | Buttons in header (:1332-1351) and bottom bar (:1824+) | One set |

### Payables (AP) (`payables`, `pages/EnhancedPayablesPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Stats vs tabs | DUPLICATE | 5 of 6 tiles (:192-199) read the same `stats.*` as tab badges (:974-982, :1031-1036) | Delete the row (Overdue as a tab or one tile) |
| Med | AR vs AP stats | INCONSISTENT | AR shows amounts + subtitles, AP bare counts | One pattern |
| Med | Netting tab | INCONSISTENT | Badge counts included (:981), rows include eligible (:838) | Align, or "Netting-eligible" |
| Med | Status column | BUSY-LAYOUT | Up to 3 stacked badges (:1134-1140) + Route badge (:1142) + Overdue (:1127) | One status badge; POBO/FX in Route |
| Med | Pay Now modal | JARGON / TEXT-HEAVY | "Booked to IHB current account …; headroom …" (:474-479); 3-line negotiation paragraph (:486-493) | "Paid by {treasury} on behalf of {entity}"; details in a disclosure |
| Low | Invoice cell | DUPLICATE | `payableNumber` on both lines when no invoice number (:1091-1092) | Caption only with an invoice number |
| Low | Details modal | INCONSISTENT | Raw `poboRequestStatus` (:1249-1250) | Badge helper |

**Create Payable** (`pages/CreatePayablePage.tsx`)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | POBO panel | TEXT-HEAVY / DUPLICATE | 3 lines (:662-675) repeated in the summary box (:890-894) | First line + summary |
| Med | Summary | TEXT-HEAVY | Explains the buttons (:898-899) | Delete or tooltips |
| Low | VAT | DUPLICATE | Label (:627) repeats the toggle description (:841) | "VAT share recorded with the payable" |
| Low | Actions | DUPLICATE | Header (:552) and bottom bar (:917) | One set |

### Exceptions (`exceptions`, `pages/ExceptionDashboardPage.tsx`, `components/treasury/AllocationModal.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Drawer actions | INCONSISTENT | "Investigate" and "Write Off" only toast (:418-427), no API | Wire or remove |
| Med | Allocate modal | DUPLICATE | Target VA 3× (:1676-1695, :1716-1729, confirm :303-350); "Ready to allocate" 2× (:1321-1324, :1417-1418) | Delete the preview banner and one "Ready" |
| Med | List | BUSY-LAYOUT | Hand-rolled list, `size: 20` (:307), no pager, under "{totalElements} exceptions found" (:469) | Paged `DataTable` |
| Low | Card header | DUPLICATE | Status chip (:473) = highlighted tile (:451-454) | Drop |
| Low | Confirm | TEXT-HEAVY | "Please review before proceeding" (:279) + 2-sentence warning (:356-359) | "Can only be undone by a reversal." |

### Escrow (`escrow`, `pages/EscrowPage.tsx`) — Busy (mild)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | Disputes | DUPLICATE | Disputed count 3× (tile :441-446, alert :457-467, tab :367); Active tile (:437) = tab (:366) | Delete the alert and count tiles; keep money tiles |
| Low | Fund/Release modal | DUPLICATE | Fee in hint (:810) and caption (:849-853); subtitle repeats held balance | Hint only |
| Low | Stat tiles | INCONSISTENT | Local `StatCard` (:429-452) | `StatTile` |

### Wallets (`wallet`, `pages/WalletPage.tsx`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Overview tab | DUPLICATE | Quick Actions (:1199-1219) = header buttons (:1155-1163) | Delete; tiles above the Wallets table; drop the tab |
| High | Partner strip | DUPLICATE | "N Wallets"/"Float" (:1179-1180) = tiles (:1194-1195) | Filter only |
| High | Issue modal step 3 | INCONSISTENT | Hard-coded fake hierarchy "UAE Region / Dubai / Abu Dhabi / Sharjah" (:1456-1460) | Real nodes or remove |
| High | Programs tab | INCONSISTENT | Card click sets `selectedPartnerId` to a program id (:1231); select keyed by corporate (:1137, :1173) | Partner from `p.corporateId`, or filter by program |
| Med | Programs link 4× | DUPLICATE | Header (:1158), quick action (:1210), tab text + button (:1227-1228), empty state (:1232) | One header link; drop the tab |
| Low | Detail modal | DUPLICATE | "Monthly Spent" tile (:1618) = Limits row (:1624) | Drop the tile |
| Low | Issue modal | TEXT-HEAVY / JARGON | Subtitle = title (:1396); alert (:1441); "Partner View", "Float" (:1171, :1180) | Cut; "Partner", "Total balance" |

### E-Commerce Collections (`ecommerce-collections`, `pages/EcommerceCollectionsPage.tsx`) — Clean, duplicate page
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Whole page | DUPLICATE | Same `ecommerceApi.getCollections()` (:22) as Seller Collections' Collections tab (`SellerCollectionsPage.tsx:24`); detail modals show different fields (:174-185) | One "Collections" page (Collections / Settlements) with one detail modal |

### Seller Collections (`seller-collections`, `pages/SellerCollectionsPage.tsx`) — Busy (mild)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| Med | Stats vs tabs | DUPLICATE | "Total Collections" tile (:74) = tab count (:122) | Drop; keep amounts |
| Med | Settlements tab | BUSY / TEXT-HEAVY | A card for a heading and caption (:183-189) | Empty-state description |
| Low | Stats | INCONSISTENT | Hand-rolled cards (:68-105); warning tone on a neutral figure (:90-93) | `StatTile`, neutral |
| Low | Search | INCONSISTENT | Placeholder "…or seller..." (:138) but searches payer/VA | "Search by reference, payer or account" |

**Top fixes (Payments & Specialty):** fix misleading numbers and text (nav badges, Credits/Debits counts, demo-data
fallback, "created as draft", fake wallet hierarchy); delete stat tiles that repeat tab badges (Payables, Receivables,
Escrow, Seller); delete the ~10 dead buttons and the toast-only Investigate/Write Off; Transfers sidebar to one
progress signal and one balance; Wallets: one table-first page.

---

## Accounts & Structure

Note: `PageHeader` descriptions sit behind a "?" popover (`components/layout/HeaderHelpPopover.tsx`), so they cost
little; the clutter is text in the page body (hero subtitles, tiles, banners, captions).

### Virtual Accounts (`accounts`, `pages/AccountsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Hero + stat strip | INCONSISTENT (made-up) | Hard-coded trends `'+8.2%'` (:1726) and `'+12%'` (:365) | Delete |
| High | Drawer "Edit Account" | INCONSISTENT | Opens `VaCreateModal`, which has no account prop (:1964-1975, `VaCreateModal.tsx:101-109`): an empty create form, then "Account updated" | Hide until a real edit exists |
| High | Table / Tree toggle | DUPLICATE (misleading) | Tree built from the current page of 15 rows (:804-860, :1519): parents on other pages show as roots | Remove the toggle; link to Balance Hierarchy |
| Med | Stat strip | DUPLICATE | "Total Accounts" (:364) = pager total (:1847) | "N accounts · N active · N suspended" in the hero |
| Med | Legend card | DUPLICATE | Icon legend (:1927-1941); Category column has text badges (:569-581) | Delete |
| Med | Drawer Activity tab | DUPLICATE | Status history repeats Overview (:1321-1355 vs :1040-1050, :1162); "Recent Activity" only points to Statements (:1360) | Delete the tab |

### Bank Accounts (`physical-accounts`, `pages/PhysicalAccountsPage.tsx`) — Very busy (~16 text blocks)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Unlinked alert | INCONSISTENT | Counts only the current page of 20 (:1321, :1463) | Server count, or a "Not linked" filter chip |
| High | Detail "Configure" | INCONSISTENT (dead) | No onClick (:1784) | Delete |
| High | Stat strip vs Banks Overview | DUPLICATE / BUSY | Home/External tiles (:1432-1448) repeat bank cards with pills and counts (:426-435); hero + 5 tiles + alert + 6 cards + filters + table | "Total accounts" only; Banks Overview → a Bank filter |
| Med | Table | DUPLICATE | "Entity Link" column (:1637-1644) = Entity column (:1592-1601); eye (:1662) = row click (:1507) | Drop both |
| Med | Detail balances | DUPLICATE | Current/Available/Ledger (:1694-1705) and shadow Bank Balance/Available/On Hold (:1757-1759) | One balance row |
| Med | "Link" ×3 meanings | JARGON / INCONSISTENT | "Link External" creates (:1333); "Link to Hierarchy" creates a shadow (:1654); modal title vs button (:570, :726); nav "Account Linking" | "Add external account", "Add to hierarchy", "Assign to entity" |
| Med | Link modal | TEXT-HEAVY / JARGON | 4 bullets with `PHYSICAL_MIRROR`, `ROOT`, `Exception VA` (:689-702); preview repeats the selection (:705-714) | One caption; no preview |
| Med | Link modal empty | INCONSISTENT | "Go to Treasury → Balance Structure" (:632) — no such menu | Accounts & Structure → Balance Hierarchy (link) |
| Low | Bank card | INCONSISTENT | Multi-currency total labelled with the first currency (:439) | Single-currency banks only |

### VIBAN Management (`viban`, `pages/VibanManagementPage.tsx`) — Very busy (~15 text blocks)
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Hero vs strip | DUPLICATE | Hero subs (:953, :962) = Available/Reserved/Total Pools tiles (:972-998) | Delete the strip |
| High | Utilisation | INCONSISTENT | 3 formulas: assigned/total (:940), (assigned+reserved)/size (:1035, :1115), assigned/size called "Used" (`programs/ProgramDetailModal.tsx:545, :553`) | One formula, the word "Assigned" |
| High | Overview vs Pools tab | DUPLICATE | Pool Summary (:1028-1059) = Pools tab (:1091-1165); low-availability alert (:1009-1025) = card note (:1125-1129) | Merge into Pools (low availability first); 2 tabs |
| Med | Pool card | BUSY-LAYOUT | 4 mini tiles, bar, TTL/prefix, warning, 6 buttons (:1108-1159) | `DataTable` row; Assign + ⋯ |
| Med | Pool Summary status | INCONSISTENT | Raw status (:1052) vs `getStatusBadge` (:1105) | Badge helper |
| Med | Generate form | INCONSISTENT | Two "Available" stats, count and % (:1350, :1352) | "Available N of M" |
| Med | Pool detail | DUPLICATE | Count cards (:1917-1922); "Active VIBANs (N)" = "N Assigned" (:1948-1950); table = VIBANs tab | Config + payment stats; "View N VIBANs →" |
| Low | Assign forms | DUPLICATE | Selection echoed in coloured boxes (:1488-1498, :1530-1542, :1758-1768) | Delete |
| Low | Status filter | INCONSISTENT | "Available (In Pool)" filters RETURNED only (:1199); badge maps AVAILABLE and RETURNED (:765-767) | Both |

### Account Linking (`account-attachments`, `pages/AccountAttachmentsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Duplicate of a Bank Accounts modal | DUPLICATE / INCONSISTENT | Same five roles defined twice with different meanings (`PhysicalAccountsPage.tsx:344-348` vs `AccountAttachmentsPage.tsx:65-69`) | One config; Bank Accounts opens this page's modal |
| Med | Hero vs strip | DUPLICATE | "N pending approval" (:579) = Pending tile (:587); 3 type tiles (:588-590) repeat the filter (:611-614), missing two types | Delete; counts in the filter options |
| Med | Naming | INCONSISTENT | "Account Linking" (:544) vs "Attachment" (:573, :529, :750, :800); promises physical accounts (:545), shows VAs only (:643) | One noun; "Which legal entities own or can act on each virtual account." |
| Low | Actions | DUPLICATE | Eye (:687) = row click (:629) | Delete |

### Programs (`programs`, `pages/ProgramsPage.tsx`, `programs/*`) — Page clean, modal very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Detail modal | BUSY / DUPLICATE | 7 tabs (`ProgramDetailModal.tsx:138-144`); code/currency 3× (:160-162, :220-222, Config); prefix and max VAs 3× (:191, :222, :902-904) | Config into Overview; a `Drawer` |
| Med | Hero subtitle | TEXT-HEAVY | Up to 5 clauses incl. FX notes (:392-394) | "N programs · N active"; FX in a tooltip |
| Med | Row actions | BUSY-LAYOUT | Eye, pencil, #, ⋯ (:452-468); rows not clickable | Row click → detail; ⋯ only |
| Med | VIBAN Pool tab | DUPLICATE / TEXT-HEAVY | Strategy 3× (:427-453, :477-480, :502-505); "About VIBAN Generation" (:591-603); "Pool Capacity" (:575-585); overlaps VIBAN Management | One settings list, one pool line, link |
| Med | Wallet Fees tab | TEXT-HEAVY | Caption (:624-626), "Fee Status Summary" (:797-822), "About Wallet Fees" (:825-837) | Caption only |
| Med | Create wizard step 2 | DUPLICATE / INCONSISTENT | "+Step" badge (`ProgramFormModal.tsx:859`) + banner (:870-886); "Configure Hierarchy now" (:842) vs "Every program is created with a hierarchy" (`ProgramDetailModal.tsx:296`) | Delete banners; fix the option |

### Balance Hierarchy (`hierarchy`, `pages/TreasuryHierarchyPage.tsx`) — Very busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Hero + strip | DUPLICATE | "Excluding intercompany" (:3893-3894) = Consolidated − "IC Net" (:3904); pool rate/interest (:3911-3923) belong to pooling | Consolidated + IC Net |
| High | Physical + Shadow banners | DUPLICATE / BUSY / JARGON | Navy "Real Bank Account (Physical)" (:3933-3964) and warning-orange "Shadow Accounts (PHYSICAL_MIRROR)" with a card per shadow (:3967-4010); also in the tree, Bank Accounts and Entity Balance Tree | One neutral line "Bank account: X · balance · N mirrors →" |
| Med | Tree rows | BUSY / DUPLICATE | Up to 3 badges + "+N", currency, balance + "≈", IC figure + caption per row (:1502-1626); sweep shown twice (:1529-1535, :1552-1553) | Entity + one role badge; one sweep badge; IC as a column |
| Med | Legend | TEXT-HEAVY | 8 icon definitions (:4089-4101) | "?" popover |
| Med | Detail panel | DUPLICATE / INCONSISTENT | "Entity Details" for any node (:4070); IHB "Enable" (:2008) and "Join IHB Scheme" (:2028) open the same modal (:2415); "EOD Sweep" with "Real-time" (:1343, :1346); "Currency Mirror (M-Node)" (:1846) | Node name as title; one IHB action; fix the label; drop "M-Node" |
| Med | "Recalc Mirrors" | DUPLICATE | = Currency Mirrors "Recalculate All" (`CurrencyMirrorPage.tsx:585`); breakdown (:1889-1945) = that page's cards | Mirror tools on Currency Mirrors only |

### Entity Balance Tree (`entity-balance-tree`, `pages/EntityBalanceTreePage.tsx`) — Very busy, overlaps Balance Hierarchy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Whole page | DUPLICATE | Same scope, reporting currency, FX-converted tree, expand/collapse and badges as Balance Hierarchy; near-identical descriptions (:1528 vs `TreasuryHierarchyPage.tsx:3843`) | "Group by legal entity" toggle on Balance Hierarchy; retire this page |
| High | Section totals | INCONSISTENT | Sums mixed-currency accounts (:382-383) printed in the functional currency (:422) | Converted balances or no totals |
| Med | View toggle | BUSY-LAYOUT | Three icon-only views (:1576-1613); Pyramid with decorative colours (:670-753, :686-693), its total = the tile (:700-702) | Hierarchy view only |
| Med | Stat tiles | TEXT-HEAVY / INCONSISTENT | Subtitles restate titles (:1622, :1631, :1640); "Treasury Centers / IHB enabled" merged (:1647-1649) | Delete subtitles; hero + one line |
| Med | Account sections | TEXT-HEAVY / JARGON | 8 captions like "Physical bank account mirrors (CBS)" (:418, :130) + "Rolled-up" badge (:412-416) | Delete |
| Low | Labels | INCONSISTENT | "Treasury Center"/"TC"/"Treasury" (:491, :946); trend arrows for a balance's sign (:519-523, :982-986); "Currency" with 5 hard-coded options (:219-225, :256) | One label each; no arrows |

### Currency Mirrors (`currency-mirrors`, `pages/CurrencyMirrorPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Sample-data fallback | INCONSISTENT (misleading) | On empty/failed API, `DEMO_BREAKDOWNS` (AED 5M, USD 500k…) fill the cards and hero, `lastRecalculated` = now (:404-408, :427-436, :476-481); can mix with real totals (:453-458); real empty state never shows (:752-756) | Delete; `EmptyState` |
| Med | Hero vs strip | DUPLICATE | "Across N currencies" (:695) = Currencies tile (:705-706) | Delete the tile; stale rates in the hero |
| Med | Architecture banner | TEXT-HEAVY / JARGON | "(CURRENCY_MIRROR VAs) aggregate all VAs…" (:722-734) | Delete |
| Med | Dead controls | INCONSISTENT | FX edit logs only (:850); "Configure Rate Sources" no handler (:860) | Hide |
| Low | Card vs modal | DUPLICATE | Same figures on card (:186-219) and modal (:780-811); every card labelled "Currency Mirror" (:183) | Drop Details and the label |

### Reorganization (`hierarchy-operations`, `pages/HierarchyOperationsPage.tsx`) — Busy
| Sev | Section | Type | Evidence | Fix |
|---|---|---|---|---|
| High | Dead controls | INCONSISTENT | "History" (:750-756), "View All" (:888), "View All Pending" (:880) no onClick; view details logs only (:903); an `operation-history` page exists (`App.tsx:639`) | Point them there, or remove |
| Med | Tree + legend | DUPLICATE / JARGON | A fourth hierarchy tree (:828-865); legend of raw enums ROOT/AGGREGATION/… (:857-861) differing from Balance Hierarchy's | Tree as a picker in the Move modal, or "Move…" on Balance Hierarchy's node menu |
| Med | Quick Actions | BUSY / INCONSISTENT | 5 large tiles (:692-733, :817-822); labels differ from modal and history ("MOVE TRANSACTION VA", :325, :350) | One "Reorganize ▾" button; friendly history labels |
| Med | Divestiture modal | DUPLICATE / TEXT-HEAVY | Same facts 4× (`DivestitureModal.tsx:201-208, :255-276, :278-282, :284-296`), incl. "Update corporate_id for all child VAs" | Before/After + confirm checkbox |
| Low | Title / primitives | INCONSISTENT | Nav "Reorganization" vs header "Corporate Reorganization" (:778); local Badge/Card/Button (:105-164) | Align; shared components |

**Overlapping pages:** four hierarchy trees (Virtual Accounts Tree view, Balance Hierarchy, Entity Balance Tree,
Reorganization) → Balance Hierarchy only, with an entity grouping and Move on the node menu; mirror/shadow accounts
shown on three pages and recalculated from four places; entity links edited in two places with different role
definitions; VIBAN pools in two places with different formulas.

**Top fixes (Accounts & Structure):** remove made-up figures (trends, Currency Mirrors sample data); fix or hide
dead/wrong buttons (Edit Account → empty create form, Configure, Reorganization History/View All, Currency Mirrors
edit/configure); delete stat strips that repeat the hero (5 pages); cut self-explaining banners; one VIBAN
utilisation formula and one hierarchy tree.
