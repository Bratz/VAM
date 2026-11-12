# Sweeps with external banks — demo walkthrough

Recorded on **https://vam-five.vercel.app** on **12 Nov 2026** (build 6e070ad, UAE profile, home bank Emirates NBD
`EBILAEADXXX`), by clicking through the portal. Every value below is what was entered; every "You see" is what the
portal showed at the time. No sign-in is needed. There is no link to any bank, so the bank's side is played from the
browser console: the bank's end-of-day statement is posted to the intake endpoint the bank's middleware would call.

**Story.** Albion Industrial PLC is a UK group that banks at four UK banks and concentrates its sterling into one header
account at Lloyds in London. Treasury opens a new collections account at NatWest in Leeds and wants it in the
concentration: the account is set up, its shadow is created, the bank's end-of-day statement brings its balance in, and
treasury tries to add it to a sweep. The portal refuses: money at another bank cannot be moved without a mandate from
the client, and there is no way to give it one yet (finding 1). The sweep is then shown on the group's existing
external sweep, Manchester (Barclays) to London (Lloyds): it runs by hand, and you see what moved and what did not.

Corporate used throughout: **Albion** (*Albion Industrial PLC*, `cc000003-…-0003`), GBP.

| Account | Bank | Legal entity | Role |
|---|---|---|---|
| **DEMO Albion Leeds GBP Collections** · 56789105 · GB17 NWBK 6016 1356 7891 05 | National Westminster Bank plc · NWBKGB2LXXX | ALBN-EN Albion Engineering Ltd | new, created in step 1 |
| Albion Manchester GBP Operating · 23456702 · shadow **ALBN0000002** | Barclays Bank UK plc · BARCGB22XXX | ALBN-EN | sweep source (rule SWP-ALBN-001) |
| Albion London GBP Header · 12345601 · shadow **ALBN0000001** | Lloyds Bank plc · LOYDGB2LXXX | ALBN-GB Albion Industrial PLC | concentration (header) account |

> **Watch the Corporate filter.** Leaving Bank Accounts and coming back, the Corporate box showed *TestShop* while the
> list still showed Albion rows, and **Link to Hierarchy** then offered TestShop's hierarchy (finding 9). Before each
> step, set **Corporate** to **Albion** again.

---

## Records this walkthrough created

| What | Reference | Notes |
|---|---|---|
| Bank account (physical) | **56789105** · DEMO Albion Leeds GBP Collections · id `de0266f5-889f-4917-ae63-a2e576824a65` | data source SWIFT_MT940; created from the console (step 1) |
| Shadow account | **SHADOW-GBP-56789105** · id `245da729-0adc-4086-8ae5-d55cc8e15bcd` | under TRES-G-ROOT-81781 (Albion Treasury Program) |
| Statement file | **NWBK_MT940_20261112.sta** · file `f764cf21-7ea2-466d-8b65-3b6e3560b93e` | statement NWBK261112-001, id `78fc81ad-2493-4de7-a435-9b0584953caf` |
| Sweep rule | none | *DEMO Albion Leeds Target 250k → London* was refused (step 4) |
| Sweep execution | **EXE-20261112132923-8109** · rule SWP-ALBN-001 · GBP 3,073,799.60 | step 5 |
| Ledger postings | SWP17944901636336282 (sweep out, ALBN0000002), SWP17944901636331003 (sweep in, ALBN0000001), FEE-1794490163658-4537 (fee, ALBN0000002), SET-1794490163660-6725 (fee credit, SETTLEMENT-TRESGBPALBN-GBP) | step 5 |

Balances the walkthrough changed (before → after step 5):

| Account | Before | After |
|---|---|---|
| ALBN0000002 Barclays shadow — ledger (current = available) | 3,573,799.60 | **499,500.00** |
| ALBN0000002 — bank balance (from statements) | 3,573,799.60 | 3,573,799.60 (unchanged) |
| Physical 23456702 (Barclays) | 3,573,799.60 | 3,573,799.60 (unchanged) |
| ALBN0000001 Lloyds header shadow — ledger | 8,183,101.00 | **11,256,900.60** |
| ALBN0000001 — bank balance / bank available | 8,183,101.00 / 8,210,000.00 | unchanged |
| Physical 12345601 (Lloyds) | 8,210,000.00 | 8,210,000.00 (unchanged) |
| SETTLEMENT-TRESGBPALBN-GBP | 131,602.62 | 132,102.62 (sweep fee) |
| Rule SWP-ALBN-001 total swept / runs / last run | 4,079,349,000.00 / 3,896 / 28 Sep 2026 18:00 | 4,082,422,799.60 / 3,897 / 12 Nov 2026 13:29 |

To repeat the demo, use a new account number and IBAN in step 1 (both must be unique), and give the statement a new
`:20:` reference. Step 5 sweeps whatever Barclays holds above 500,000: after this recording its ledger is 499,500.00,
so a repeat run is **skipped** (*"No funds to sweep or below threshold"*) until a Barclays end-of-day statement above
500,000 has been taken in (statement wins, step 6).

---

## 1. The bank account at NatWest (≈2 min)

**Sidebar Accounts & Structure → Bank Accounts**, Corporate **Albion**.

You see seven Albion accounts: three at **Emirates NBD** (*HOME BANK*, GBP/USD/EUR 5,000,000.00 each) and four
*EXTERNAL*: Lloyds GBP 8,210,000.00, Barclays GBP 3,573,799.60, HSBC UK GBP 2,040,000.00, Citibank USD 1,820,000.00.
Tiles: *External Banks 4 — Sweep sources*, *Sweep Enabled 2*.

The button for this is **Link External** (window *Link External Bank Account*: Legal Entity, Account Name, Account
Type, Account Number, IBAN, Bank Name, Bank Code (SWIFT), Country, Currency, Branch). The window has **no data source
field**: it always saves the account as `OPEN_BANKING_UAE` (finding 3), which statements do update, but which is not
counted as statement-fed, so the account would never show *Fresh / Last statement …*. To get a statement-fed
(`SWIFT_MT940`) account, the same request the window sends is posted from the browser console (F12 → Console), with the
data source added:

```js
await fetch('/api/v1/physical-accounts', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    corporateId: 'cc000003-0000-0000-0000-000000000003',        // Albion
    legalEntityId: 'cc000003-1000-0000-0000-000000000002',      // ALBN-EN Albion Engineering Ltd
    accountNumber: '56789105', iban: 'GB17NWBK60161356789105',
    accountName: 'DEMO Albion Leeds GBP Collections', accountType: 'CURRENT',
    bankName: 'National Westminster Bank plc', bankCode: 'NWBKGB2LXXX', bankCountry: 'GB', branchName: 'Leeds',
    dataSource: 'SWIFT_MT940', currencyCode: 'GBP', initialBalance: 0, interestType: 'CREDIT' }) })
  .then(r => r.json())
```

You see `success: true`, *"Physical account created successfully"*, with `bankRelationship: EXTERNAL`,
`dataSource: SWIFT_MT940`, `isExternalBank: true`, `canInitiatePayments: false`, `sweepEligible: false`,
`currentBalance: 0`, `syncStatus: PENDING`. The relationship is decided by the BIC: anything other than the home bank
is EXTERNAL.

Back on **Bank Accounts** (Corporate **Albion**) the row reads
`DEMO Albion Leeds GBP Collections · GB17 NWBK 6016 1356 7891 05 · Albion Engineering Ltd ALBN-EN · National
Westminster Bank plc NWBKGB2LXXX · GBP 0.00 · — · PENDING`. The Shadow column is empty: for an account at another bank
the shadow is **not** created automatically (it is for home-bank accounts).

> Talking point: the platform never moves money in this account itself. It sees it through the shadow account,
> whose balance the bank's statements keep up to date.

## 2. Its shadow account (≈1 min)

1. On the new row, click **Link to Hierarchy** (first action icon).
2. Window *Link Physical Account to Hierarchy*: *DEMO Albion Leeds GBP Collections · National Westminster Bank plc ·
   GB17NWBK60161356789105 · External Bank · GBP 0.00*. Under *Select Parent Node (AGGREGATION)* there are two cards,
   **ROOT AED** and **ROOT GBP**, with no names. **ROOT AED is preselected: click ROOT GBP** (that is
   *Albion Treasury Program - Group Treasury*, TRES-G-ROOT-81781, where Albion's other shadows sit; ROOT AED is the
   corporate itself, not an account — finding 10).
3. **Create Shadow Account**.

You see the toast *"✓ Created Shadow Account"* (no account number in it). The row's Shadow column now reads **In
program**. The shadow is **SHADOW-GBP-56789105** *"Shadow: DEMO Albion Leeds GBP Collections"*, PHYSICAL_MIRROR, owned
by ALBN-EN, in the Albion Treasury Program, data source SWIFT_MT940, bank balance 0.00, *stale*.

## 3. The bank delivers the end-of-day statement (≈2 min)

There is no upload screen, by design: statements come from the bank's delivery folder or its system-to-system feed. The
bank's feed is played by posting the statement from the console. Its opening balance (0.00) equals the shadow's bank
balance, so it is not a break.

```js
const mt940 = `:20:NWBK261112-001
:25:GB17NWBK60161356789105
:28C:00001/001
:60F:C261111GBP0,00
:61:2611121112C780000,00NTRFINV-ALB-7781//NW2611120001
:86:Northern Rail Supplies Ltd payment INV-ALB-7781
:61:2611121112C470000,00NTRFINV-ALB-7790//NW2611120002
:86:Pennine Fabrication plc payment INV-ALB-7790
:62F:C261112GBP1250000,00
:64:C261112GBP1250000,00
-`;
await fetch('/api/v1/statements/intake?fileName=NWBK_MT940_20261112.sta',
  { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: mt940 }).then(r => r.json())
```

You see *"1 applied, 0 duplicate, 0 refused"*: statement **NWBK261112-001**, END_OF_DAY, 2026-11-12, APPLIED, no gap,
no break, `differenceToPlatform 1250000`, `cbsFed false`.

Where it shows:

- **Payments & Collections → File Ingest Pipeline**, card **Bank (automatic)** (*"Bank statements taken in from the
  bank's delivery folder or its system-to-system feed. Read-only: nobody uploads a statement."*): the top line is
  `NWBK_MT940_20261112.sta · API · 12 Nov 2026, 01:24 PM · 1 applied · 0 duplicate · 0 refused · Processed`. Click it:
  `GB17NWBK60161356789105 · APPLIED · NWBK261112-001 · 12 Nov 2026`. Below it, the four sample statements posted the
  same day (BofA MT942, Commerzbank camt.053, Barclays MT940 and BofA MT940).
- **Bank Accounts** (Corporate **Albion**): the row now reads **GBP 1,250,000.00 · In program · SYNCED**. Click the
  eye icon (**View Details**): *Current / Available / Ledger GBP 1,250,000.00*; *Shadow: DEMO Albion Leeds GBP
  Collections (SHADOW-GBP-56789105)* — Bank Balance GBP 1,250,000.00, Available GBP 1,250,000.00, *"Updated by bank
  statements · last statement 12 Nov 2026"*, *As of 11/12/2026, 11:59:59 PM*; and **Bank statement lines**:

| Value date | Status | Amount | Details |
|---|---|---|---|
| 12 Nov 2026 | FINAL | GBP 780,000.00 | Northern Rail Supplies Ltd payment INV-ALB-7781 |
| 12 Nov 2026 | FINAL | GBP 470,000.00 | Pennine Fabrication plc payment INV-ALB-7790 |

- **Dashboard → Accounts · ALL BANKS**, open the **GBP** group: `Shadow: DEMO Albion Leeds GBP Collections ·
  SHADOW-GBP-56789105 · ALBN-EN · National Westminster Bank plc · 1,250,000.00 · 1,250,000.00 · Fresh · Last statement
  12 Nov 2026`. Next to it, Barclays (ALBN0000002) is *Fresh · Last statement 11 Nov 2026*, HSBC UK is *Stale · Last
  statement none yet*, and the Lloyds header is *Stale · Core banking · as of 14 Oct 2026*.
- **Multi-Bank Liquidity**, Corporate **Albion**: *National Westminster Bank plc 4.2%* in the distribution; GBP
  *5 mirrors · 5 banks · External-heavy · GBP 20,073,799.60*, NatWest *GBP 1,250,000.00 · 6.2%*. Tab **By Bank**:
  `National Westminster Bank plc · NWBKGB2LXXX · 1 shadow · SHADOW-GBP-56789105 · ALBN-EN · 56789105 · GBP
  1,250,000.00 · GBP 1,250,000.00`.

> Talking point: the statement wins. Its closing balance becomes the shadow's bank balance and ledger, and the
> physical account follows; any difference to what the platform expected is recorded on the statement, for
> information.

## 4. Put it in a sweep — refused: no mandate (≈2 min)

**Sidebar Liquidity Management → Cash Concentration** → **Create Rule**.

1. *Setup*: Corporate **Albion Industrial PLC**; Program left on *All Programs (Optional)* (the list offered nothing
   else); Rule Name **DEMO Albion Leeds Target 250k → London**; Sweep Type **Target Balance**; Frequency **Daily** →
   **Continue**.
2. *Accounts*: Target Account **Shadow · Lloyds GBP (Header) - ALBN0000001 (GBP)**; Source Accounts (Manual): tick
   **Shadow: DEMO Albion Leeds GBP Collections · SHADOW-GBP-56789105 · GBP 1,250,000.00** → **Continue**.
3. *Configuration*: Currency **GBP - British Pound**, Priority **1**, Target Balance Amount **250000** → **Continue**.
4. *Review*: *DEMO Albion Leeds Target 250k → London · Target Balance • DAILY · Albion Industrial PLC · GBP · 1*;
   Source Accounts 1 → Lloyds header ALBN0000001 → **Create Rule**.

You see: nothing. The window stays on *Review* with no message (finding 6). The request was refused (HTTP 400); the
reason is only in the browser console:

> *Account SHADOW-GBP-56789105 mirrors an external/non-home-bank account (BIC NWBKGB2LXXX). Real movement requires an
> ACTIVE ExternalMandate for rail AUTO — add one first, or remove this account from the rule.*

Close the window. No rule was created.

> Talking point (and the honest one): the platform will not pull money out of an account at another bank on its own
> say-so; it needs the client's mandate to the home bank for that account. The mandate record exists in the platform
> (`external_mandates`), but there is no screen or API to register one yet, so a new external account cannot join a
> sweep today. The same rule would be refused with the Albion GBP account at Emirates NBD as the target: the refusal
> is about the external source.

## 5. Run the group's existing external sweep by hand (≈2 min)

Albion's seeded rule **Albion Manchester Target 500k → London** (SWP-ALBN-001) sweeps Barclays (ALBN0000002) down to
500,000 into the Lloyds header. It is a DAILY rule (18:00); it last ran on 28 Sep 2026, and nothing has run on vam-five
since, so it is run by hand.

Before: Barclays ledger **3,573,799.60** (from the 11 Nov statement, *Fresh*); Lloyds header ledger **8,183,101.00**.

1. **Cash Concentration** → **Run Sweeps**. Window *"Select specific rules to execute or run all active sweep rules at
   once."* with the 15 active rules.
2. Tick only **Albion Manchester Target 500k → London · SWP-ALBN-001** → **Run 1 Rule(s)**.

You see *Sweeps Complete · 1 Executed · 1 Successful · 0 Failed* (no amount shown) → **Close**.

3. Tab **Execution History**, top row:
   `EXE-20261112132923-8109 · GBP 3,073,799.60 · GBP 3,573,799.60 → GBP 500,000.00 · SETTLED · Just now`.
   Click it (**Execution Details**): *Albion Manchester Target 500k → London · SETTLED · Transfer flow ALBN-EN
   ALBN0000002 → GBP 3,073,799.60 → ALBN-GB ALBN0000001 · Amount swept GBP 3,073,799.60 · Balance before GBP
   3,573,799.60 · Balance after GBP 500,000.00 · 11/12/2026, 1:29:23 PM*. (Rule Reference and Sweep Type read *N/A*.)

What moved, and what did not:

- **Dashboard → Recent statement activity**, newest first:

| Time | Line | Account · reference | | Amount |
|---|---|---|---|---|
| 01:29 PM | FEE CREDIT: Cash concentration sweep fee - EXE-20261112132923-8109 | SETTLEMENT-TRESGBPALBN-GBP · SET-1794490163660-6725 | CR | GBP 500.00 |
| 01:29 PM | FEE DEBIT: Cash concentration sweep fee - EXE-20261112132923-8109 | ALBN0000002 · FEE-1794490163658-4537 | DR | GBP 500.00 |
| 01:29 PM | Cash concentration sweep - Albion Manchester Target 500k → London | ALBN0000001 · SWP17944901636331003 | CR | GBP 3,073,799.60 |
| 01:29 PM | Cash concentration sweep - Albion Manchester Target 500k → London | ALBN0000002 · SWP17944901636336282 | DR | GBP 3,073,799.60 |

- **Accounts & Structure → Virtual Accounts**, search `ALBN000000`: *Shadow · Lloyds GBP (Header) ALBN0000001 · GBP
  11,256,900.60* and *Shadow · Barclays GBP ALBN0000002 · GBP 499,500.00* (500,000 less the 500.00 sweep fee — 0.05%,
  capped at 500).
- **Bank Accounts** still shows Manchester **GBP 3,573,799.60** and London **GBP 8,210,000.00**, and the Dashboard's
  GBP group still shows Barclays *3,573,799.60 · Fresh · Last statement 11 Nov 2026* and Lloyds *8,183,101.00 /
  8,210,000.00*. Those are the banks' figures, and nothing has been sent to either bank.
- No payment instruction exists: no MT101, pain.001 or transfer to download anywhere for EXE-20261112132923-8109.
- No intercompany position: the money moved from Albion Engineering (ALBN-EN) to Albion Industrial (ALBN-GB), and
  neither entity is IHB-enabled, so nothing records that ALBN-GB now owes ALBN-EN 3,073,799.60.

> Talking point, said plainly: on an external-bank sweep the platform moves its own books at once, both shadows'
> ledgers, and leaves the bank balances to the next statements. Today it does not send the bank anything, so in a
> real deployment treasury would still have to instruct Barclays itself (findings 2 and 4).

## 6. Next day: the statement reconciles it (≈1 min) — *script, not recorded*

> Not posted on 12 Nov: in the recording session, posting a statement to the existing Barclays demo account was not
> permitted, so this step was not run. What follows is the text to post and what the code says will happen
> (`StatementIntakeService.take` / `applyEndOfDay`); check it when it is first run.

Barclays' end-of-day statement for 12 Nov, standing for the bank having executed the instruction treasury sent. Its
opening balance is the 11 Nov closing, so no break:

```js
const barc = `:20:BARC261112-078
:25:BARCGB22/23456702
:28C:00212/001
:60F:C261111GBP3573799,60
:61:2611121112D3073799,60NTRFEXE-20261112132923-8109//BC2611120001
:86:Sweep to Albion London GBP Header LOYDGB2L 12345601
:62F:C261112GBP500000,00
:64:C261112GBP500000,00
-`;
await fetch('/api/v1/statements/intake?fileName=BARC_MT940_20261112.sta',
  { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: barc }).then(r => r.json())
```

Expected: *1 applied*; ALBN0000002 bank balance, ledger and physical 23456702 all **500,000.00**; *Fresh · Last
statement 12 Nov 2026*; one FINAL line *GBP 3,073,799.60*. The statement's *difference to platform* reads
**−3,073,799.60**: it is measured against the bank balance, which the sweep never moved, not against the ledger. The
ledger goes from 499,500.00 to 500,000.00: the statement wins, so the 500.00 fee drops out of the balance (its posting
remains).

---

## For a technical audience (Swagger: `/api/swagger-ui.html`)

| Endpoint | What it does |
|---|---|
| `POST /api/v1/physical-accounts` | Create a bank account; `dataSource` decides how its balance arrives; EXTERNAL unless the BIC is the home bank's |
| `POST /api/v1/treasury/shadow-accounts` | Create the PHYSICAL_MIRROR shadow (automatic only for home-bank accounts) |
| `POST /api/v1/statements/intake?fileName=` | Take in an MT940 / MT942 / camt.053 / camt.052 (raw body or multipart) |
| `GET /api/v1/statements/files?limit=` · `GET /api/v1/statements/lines?physicalAccountId=` | Intake log; statements and lines of one account |
| `POST /api/v1/sweeping/rules` | Create a rule; refused for an external-bank leg without an ACTIVE external mandate |
| `POST /api/v1/sweeping/execute-async` `{ruleIds}` · `GET /api/v1/sweeping/runs/{runId}` | Run rules now (what **Run Sweeps** calls); poll the run |
| `POST /api/v1/sweeping/execute` | The same, synchronously |
| `GET /api/v1/sweeping/history` | All executions, newest first (`/rules/{id}/history` returned 500 on 12 Nov) |

## Gotchas seen while recording

- **Corporate filter**: coming back to Bank Accounts showed *TestShop* in the Corporate box while the list still
  showed Albion. Reselect **Albion** before **Link to Hierarchy**, or the window offers TestShop's hierarchy.
- **Link External** cannot set the data source; use the console call in step 1 for a statement-fed account.
- **Link to Hierarchy** preselects *ROOT AED* (the corporate, not an account); pick **ROOT GBP**.
- **Create Rule** shows no error when refused; the reason is in the browser console.
- **Today's Swept** stayed *0.00* after the run, and **Total Swept** (31.7B) adds amounts in different currencies.
- A repeat of step 5 is skipped until Barclays is above 500,000 again.

## Findings (12 Nov 2026)

1. **No way to give an external account a mandate.** Rule creation requires an ACTIVE `ExternalMandate` for any
   non-home-bank shadow leg (`SweepService.assertShadowLegEligible`), but no controller or screen creates one (the
   statement-intake spec lists "external mandates UI for sweeps" as out of scope), and the seed dump has none. A new
   external account can never join a sweep.
2. **No payment instruction.** An external-bank sweep posts ledger entries only (`SweepService.executeTransfer`); no
   MT101 / pain.001 / transfer is produced for the bank.
3. **Link External has no data source field**: it always sends `OPEN_BANKING_UAE` (`PhysicalAccountsPage.tsx`,
   `CreateAccountModal`), which is not statement-fed, so such an account never shows statement freshness.
4. **Only the ledger moves.** For external shadows `mirrorIfHomeBankShadow` is a no-op: bank balance and physical
   account stay put until the next statement, which then overwrites the ledger; its *difference to platform* is taken
   against the unmoved bank balance.
5. **Mandate checked only at creation.** `executeSweep` never checks it, so the seeded external rules (created by SQL)
   run without one; `IhbUnifiedService.createIhbSweepRules` also creates rules without the check.
6. **Create Rule fails silently**: `CreateRuleModal.handleSubmit` only logs the error.
7. **Staleness ignored.** Sweeps size off the ledger with no freshness check; the Lloyds header (stale since 14 Oct)
   was credited without comment.
8. **Cross-entity sweep, no intercompany record** (ALBN-EN → ALBN-GB, neither IHB-enabled), as the code comment in
   `executeSweep` says.
9. **Corporate filter** on Bank Accounts showed TestShop while listing Albion; Link to Hierarchy then used TestShop.
10. **Link to Hierarchy** shows parent nodes without names and preselects the corporate pseudo-root.
11. **Today's Swept** counts only status `SUCCESS` but executions are saved `SETTLED` (`CashConcentrationPage.tsx`);
    Total Swept adds currencies together.
12. `GET /api/v1/sweeping/rules/{id}/history` returns 500.
13. Execution *Balance after* excludes the sweep fee (500,000.00 shown, 499,500.00 actual); *Rule Reference* and
    *Sweep Type* show N/A.
14. **Add Account** (home bank) defaults the BIC to `EABORAEDXXX`, not the home bank's `EBILAEADXXX`, so a home-bank
    account saved with the default is classed EXTERNAL.
15. Data-source mismatches in the seed: Lloyds shadow CORE_BANKING vs physical OPEN_BANKING_UK; Barclays physical
    OPEN_BANKING_UK vs shadow SWIFT_MT940.
