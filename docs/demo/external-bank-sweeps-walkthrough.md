# Sweeps with external banks — demo walkthrough

Recorded on **https://vam-five.vercel.app** on **12 Nov 2026, 20:05–20:19** (build e7dae35, migrations V55–V57
applied; frontend bundle `index-B4SiXEPv.js`; UAE profile, home bank Emirates NBD `EBILAEADXXX`), by clicking through
the portal. Every value below is what was entered; every "You see" is what the portal showed at the time. No sign-in is
needed. There is no link to any bank, so the bank's side is played from the browser console (F12 → Console): the
bank's end-of-day statement is posted to the intake endpoint the bank's middleware would call, and the other bank's
rejection is played with **Mark rejected** on the execution.

**Step 2 was re-recorded through the Create Rule form** on **13 Nov 2026, 02:33–02:37** (build 0a4f8ce, same bundle
`index-B4SiXEPv.js`; 0a4f8ce changed only the backend). On 12 Nov the form returned a 500 and the rules were created
from the console; the form now works. The rule it created, SWP-TB-401710, was paused straight away so that it does not
duplicate SWP-TB-355475, the 12 Nov rule that steps 3 and 4 run.

> **Reload the tab after a deploy.** The pane had been open since before the deploy and was still running the old
> bundle (`index-DSHZnM62.js`, by then a 404 on Vercel): no authority panel, and rule names without their "(off: …)"
> suffix. A reload fixed both.

**Story.** Helios Global Inc. banks with Bank of America in New York and with Emirates NBD, its home bank. The home
bank always runs the sweep: Helios has given it an authority to instruct its BofA account. BofA's end-of-day statement
brings the balance in; treasury adds a target-balance rule from BofA into Helios's own USD account at Emirates NBD; the
run produces an MT101 for BofA and credits Emirates NBD at once; BofA's next statement shows the debit and confirms
the sweep. Then a second sweep, from Citibank, is rejected by the other bank and everything is put back.

Corporate used throughout: **Helios** (*Helios Global Inc.*, `dd000004-0000-0000-0000-000000000004`), legal entity
**HEL-US**, USD.

| Account | Bank | Role |
|---|---|---|
| **Helios NY USD Operating** · 8765432101 · US33 BOFA 2200 0087 6543 21 · shadow **HEL0000002** (*Shadow · Bank of America USD*) | Bank of America N.A. · BOFAUS3NXXX | sweep source, authority **DEMO-AUTH-HEL0000002** (MT101) |
| **Helios Global Inc. USD current account** · USD54361989 · shadow **SHADOW-USD-54361989** | Emirates NBD · EBILAEADXXX (home bank) | target |
| **Helios Boston USD Operating** · 7654321001 · US33 CITI US33 0000 7654 3210 · shadow **HEL0000003** (*Shadow · Citibank USD (NYC)*) | Citibank N.A. · CITIUS33XXX | source of the rejected sweep (step 5), authority **DEMO-AUTH-HEL0000003** (MT101) |

The target is **SHADOW-USD-54361989** on vam-five (the 6.3 runbook in `tasks/todo.md` says …54361990, which is the
local numbering). V55 re-pointed the seeded rules SWP-HEL-001/002 to it too.

> **Watch the Corporate filter.** Bank Accounts opened on *TestShop* every time. Set **Corporate** to **Helios** before
> each step there.

---

## Records this walkthrough created

| What | Reference | Notes |
|---|---|---|
| Sweep rule | **SWP-TB-355475** · *DEMO Helios NY BofA Target 1M → ENBD USD* · id `0fa81a8a-5fb5-4ac2-8138-caf85149f663` | TARGET_BALANCE 1,000,000, DAILY, priority 1; HEL0000002 → SHADOW-USD-54361989. Created from the console on 12 Nov, before 0a4f8ce fixed the form; the one run in steps 3–4 |
| Sweep rule | **SWP-TB-401710** · *DEMO Helios BofA → Emirates NBD (form)* · id `2f31432f-abd2-4e8f-af59-9cec38fd5420` | Same rule, created through the form on 13 Nov (step 2); entity codes `HEL-US` on both legs. **PAUSED**, never run |
| Sweep execution | **EXE-20261112201308-0376** · id `9e14cad6-04ef-4cac-8fc3-f2202dad312d` · USD 5,219,974.50 | COMMITTED → **SETTLED** |
| Instruction | **XSW70D5530C6B124** · MT101 · id `3f6fcae4-3c88-43e0-8854-29490e04493a` | INSTRUCTED → SETTLED, confirmed by line `35df7ab8-ad0c-4b0d-9024-6098724c8bda`, bank reference BOFA2611120051 |
| Postings (run) | SWP17945143880303872 (sweep out, HEL0000002) · SWP17945143880313269 (sweep in, SHADOW-USD-54361989) · FEE-1794514388074-4219 / SET-1794514388076-7119 (standard fee 500.00) · FEE-1794514388108-8393 / SET-1794514388108-9780 (instruction fee 25.00) | fees debited to the target, credited to EXCEPTION-DEFAULT-USD |
| Statement | **BOFA_MT940_20261112.sta** · file `afa0b927-ea67-4ac1-9a24-c2b4bba69f0d` · statement BOFA261112-001, id `5fb7b30e-a1b2-4d9a-bce5-d035c12f9bba` | step 4 |
| Statement | **CITI_MT940_20261111.sta** · file `fc558711-73d9-45b3-8bba-b80f02a46ca8` · statement CITI261111-001, id `84b4f2e4-a222-4965-8e50-7c547cc56f94` | step 5 |
| Sweep rule | **SWP-TB-640202** · *DEMO Helios Boston Citi Target 1M → ENBD USD* · id `7c1e4ccb-4006-4000-9c92-ff02111de292` | TARGET_BALANCE 1,000,000, priority 2; HEL0000003 → SHADOW-USD-54361989. Console (step 5) |
| Sweep execution | **EXE-20261112201735-7222** · USD 3,012,000.00 | COMMITTED → **FAILED** |
| Instruction | **XSW31CB42BC11274** · MT101 | INSTRUCTED → REJECTED |
| Postings (Citi) | SWP17945146551796531 (out) · SWP17945146551798982 (in) · FEE-1794514655191-9459 / SET-1794514655192-5865 (500.00) · FEE-1794514655215-9498 / SET-1794514655215-0669 (25.00) · reversals REV17945146989114199 (target −3,012,000.00), REV17945146989167129 (HEL0000003 +3,012,000.00), REV17945146989305426 / REV17945146989305486 (25.00 refunded) | step 5 |
| Notification | `2084851d-d8fd-4b71-8c14-3453bbfd335f` · *External sweep failed: HEL0000003* | PAYMENT_FAILED, ERROR |

No authority was registered: both demo authorities come from V55 and were used as they were.

Balances the walkthrough touched (before step 1 → after step 5; "pending" = between the run and its end):

| Account | Before | Pending | After |
|---|---|---|---|
| HEL0000002 BofA shadow — bank balance / bank available | 6,219,974.50 / 6,254,500.00 | unchanged | **1,000,000.00 / 1,000,000.00** (statement BOFA261112-001) |
| HEL0000002 — committed ("Pending sweep") | 0.00 | 5,219,974.50 | 0.00 |
| HEL0000002 — ledger | 6,219,974.50 | 1,000,000.00 | 1,000,000.00 |
| Physical 8765432101 (BofA) current / available / ledger | 6,219,974.50 / 6,254,500.00 / 6,219,974.50 | unchanged | **1,000,000.00** all three |
| SHADOW-USD-54361989 ENBD — bank balance | 5,000,000.00 | 10,219,974.50 (13,231,974.50 while the Citi leg was pending) | **10,219,974.50** |
| SHADOW-USD-54361989 — bank available | 5,000,000.00 | 5,000,000.00 | 5,000,000.00 (finding 2) |
| SHADOW-USD-54361989 — ledger | 0.00 | 5,219,449.50 (8,230,924.50 with Citi pending) | **5,218,949.50** (+5,219,974.50 − 525 − 500) |
| Physical USD54361989 (ENBD) | 5,000,000.00 | 10,219,974.50 | **10,219,974.50** |
| HEL0000003 Citi shadow — bank balance / ledger / committed | 4,012,000.00 / 4,012,000.00 / 0.00, *stale, last statement none yet* | 4,012,000.00 / 1,000,000.00 / 3,012,000.00 | 4,012,000.00 / 4,012,000.00 / 0.00, last statement 11 Nov |
| EXCEPTION-DEFAULT-USD (*Exception Account - DEFAULT (USD)*) | 0.00 (525.00 just after the first run, which credited 525.00) | | **1,025.00** |
| Rule SWP-HEL-001 (*Helios NY USD ZBA → SF Header*) | ACTIVE · 8,326,300,500.00 · 3,175 runs · last 28 Sep 2026 18:00 | | unchanged (not run) |
| Rule SWP-HEL-002 | ACTIVE · 312,000,000.00 · 2,784 runs | | unchanged |
| Cash Concentration · Today's Swept | GBP 3.1M | | **USD 5.2M · GBP 3.1M** |

Not touched by this recording: the Albion Barclays shadow ALBN0000002, whose ledger the 12 Nov Albion recording moved to
**499,500.00** (bank balance 3,573,799.60, no Barclays statement since). That rule, SWP-ALBN-001, is now DISABLED by V55.

---

## 0. The external account and its authority (≈2 min)

**Sidebar Accounts & Structure → Bank Accounts**, Corporate **Helios**.

You see seven Helios accounts. Tiles: *Total Accounts 7*, *Home Bank 3 · Emirates NBD · Pool eligible*, *External
Banks 4 · Sweep sources*, *Sweep Enabled 3*. Bank tiles: Emirates NBD *HOME BANK* (3 accounts), JPMorgan Chase
USD 15,280,000.00, **Bank of America N.A. EXTERNAL · BOFAUS3NXXX · USD 6,219,974.50**, Citibank USD 4,012,000.00,
Standard Chartered SGD 2,510,000.00. The row reads
`Helios NY USD Operating · US33 BOFA 2200 0087 6543 21 · Helios Global Inc. HEL-US · Bank of America N.A. BOFAUS3NXXX ·
USD 6,219,974.50 · In program · SYNCED`.

Click the eye icon (**View Details**):

- *Helios NY USD Operating · Bank of America N.A. • Helios Global Inc. · ACTIVE · External · Shadow Linked*; Current
  USD 6,219,974.50, Available USD 6,254,500.00, Ledger USD 6,219,974.50.
- *Shadow · Bank of America USD (HEL0000002)*: Bank Balance 6,219,974.50, Available 6,254,500.00, *"Updated by bank
  statements · last statement 11 Nov 2026"*, *As of 11/12/2026, 10:30:00 AM* (BofA's 10:30 intraday MT942).
- **Authority to instruct** — *"The corporate's authority for the home bank to sweep this account. Registered and
  changed by bank operations."* — **Register**, and one row:
  `DEMO-AUTH-HEL0000002 · MT101 · Active · Suspend · Revoke · 1 Jan 2026 – open · No limits · document Demo authority (V55)`.

> Talking point: the home bank never sweeps another bank's account on its own say-so. It needs the corporate's
> authority for that account, registered by bank operations, with its format (MT101 or pain.001) and limits.

Registering one is **Register** on this panel (reference, format, validity, limits, document) → *Pending activation* →
**Activate**. Not done in this recording: BofA already has its V55 authority.

**Linking a new external account** (not recorded). It still needs the console for a statement-fed account: **Link
External** sends `dataSource: 'OPEN_BANKING_UAE'` with no field to change it (`PhysicalAccountsPage.tsx`,
`CreateAccountModal`, checked in the code at e7dae35), and that source is not counted as statement-fed. The console
call and the **Link to Hierarchy** step are in the 12 Nov Albion draft (git history of this file); after that, an
account needs a statement and an authority before it can be a rule source.

## 1. The bank's end-of-day statement is in (≈2 min)

BofA's end-of-day statement for **11 Nov** (the last business day) was already taken in on vam-five: it was posted
from the console at 12:56 the same day with the sample statements (statement intake 6.3). Sweeps are sized on it, so
no new statement was posted here; posting a second 11 Nov end-of-day statement would have invented a second close for
a day the bank had already closed. On another day, post the last business day's statement first (see *How to repeat*).

Where it shows:

- **Payments & Collections → File Ingest Pipeline**, card **Bank (automatic)** (*"Bank statements taken in from the
  bank's delivery folder or its system-to-system feed. Read-only: nobody uploads a statement."*):
  `BOFA_MT940_20261111.sta · API · 12 Nov 2026, 12:56 PM · 1 applied · 0 duplicate · 0 refused · Processed`. Click it:
  `US33BOFA22000087654321 · APPLIED · BOFA261111-001 · 11 Nov 2026`.
- **Bank Accounts → Helios NY USD Operating → View Details**, **Bank statement lines**:

| Statement | Value date | Status | Amount | Details |
|---|---|---|---|---|
| BOFA942-261112-1030 · Intraday | 12 Nov 2026 | PROVISIONAL | USD 64,000.00 | Bayview Stores invoice INV-HEL-2026-131 |
| | 12 Nov 2026 | PROVISIONAL | USD -9,500.00 | Supplier 7790 packaging |
| BOFA261111-001 · closing USD 6,219,974.50 | 11 Nov 2026 | FINAL | USD 182,450.00 | Pacific Retail Corp invoice INV-HEL-2026-118 |
| | 11 Nov 2026 | FINAL | USD -7,450.50 | Supplier 7731 freight November |
| | 11 Nov 2026 | FINAL | USD -25.00 | Wire fee |

- **Dashboard → Accounts · ALL BANKS**, open **USD**:
  `Shadow · Bank of America USD · HEL0000002 · HEL-US · Bank of America N.A. BOFAUS3NXXX · 6,219,974.50 · 6,254,500.00 ·
  Fresh · Last statement 11 Nov 2026`. Citibank HEL0000003 is *Stale · Last statement none yet*; the Emirates NBD
  target SHADOW-USD-54361989 is *Stale · Core banking · as of 6 Nov 2026, 02:56 PM* (5,000,000.00).
- **Multi-Bank Liquidity**, Corporate **Helios**: USD *4 mirrors · 4 banks · External-heavy · USD 30,546,500.00*;
  Bank of America *USD 6,254,500.00 · 20.5%*; *In transit (pending sweeps) AED 0.00*. Tab **By Bank**:
  `HEL0000002 · HEL-US · 8765432101 · USD 6,219,974.50 · USD 6,254,500.00` (bank balance · effective).

> Talking point: the sweep is sized on the bank's own end-of-day booked balance (6,219,974.50), not on the intraday
> figure or the platform's ledger. Without a statement for the last business day the run is skipped as stale.

## 2. Create the rule — one refusal, then the rule (≈3 min)

Recorded through the form on 13 Nov 2026 (build 0a4f8ce). The rule this demo runs in steps 3 and 4, **SWP-TB-355475**,
was created on 12 Nov from the console, when this form still returned a 500; it is the same rule. To avoid two
rules sweeping the same account, the form's rule was paused at the end of this step.

**Sidebar Liquidity Management → Cash Concentration**; Corporate **Helios** at the top of the page.

You see *Sweep Rules 4* (SWP-TB-355475, SWP-HEL-001, SWP-TB-640202, SWP-HEL-002), *Active Rules 13*, *Linked
Accounts 15* (these tiles count every corporate). Click **Create Rule** (top right) → window *Create Sweep Rule*, steps
*Setup · Accounts · Configuration · Review*.

1. *Setup*: **Corporate** opens on *Select Corporate...* (the page's Helios filter is not carried over) → **Helios
   Global Inc.**; **Program** left on *All Programs (Optional)*, the only entry; **Rule Name** **DEMO Helios BofA →
   Emirates NBD (form)**; **Sweep Type**: click the **Target Balance** card (*"Maintain a specific balance, sweep
   excess"*); **Frequency** **Daily** (the default) → **Continue**.
2. *Accounts*. How other-bank accounts are marked:
   - **Target Account (Concentration Account)** lists Helios's home-bank accounts — *Exception Account - DEFAULT
     (USD) - EXCEPTION-DEFAULT-USD*, the three Emirates NBD shadows *SHADOW-GBP-54361989*, *SHADOW-EUR-54361989*,
     *SHADOW-USD-54361989*, *USD Currency Mirror - M-USD-43BB74BA*, *Helios Treasury Program - Group Treasury -
     TRES-U-ROOT-81845*, the SGD and USD settlement accounts and *EXCEPTION-TRESUSDHEL-USD* — then, **greyed out and
     not selectable**, the four other-bank shadows:
     *Shadow · StanChart SGD - HEL0000004 (SGD) · other bank, source only*, *Shadow · Citibank USD (NYC) - HEL0000003
     (USD) · other bank, source only*, *Shadow · Bank of America USD - HEL0000002 (USD) · other bank, source only*,
     *Shadow · JPMorgan USD (Header) - HEL0000001 (USD) · other bank, source only*.
   - **Source Accounts**, tab **Manual**: each row shows name, number, currency and balance. The other-bank rows carry
     a label: *Shadow · Citibank USD (NYC) · HEL0000003 · **other bank · authorised** · USD 4,012,000.00* and *Shadow ·
     Bank of America USD · HEL0000002 · **other bank · authorised** · USD 1,000,000.00*; *HEL0000004 (SGD
     2,510,000.00)* and *HEL0000001 (USD 15,280,000.00)* read **other bank · no authority**, are faded, and their
     tooltip says *"At another bank with no authority: bank operations register one on the account first"*. The
     home-bank target reads *SHADOW-USD-54361989 · USD 5,218,949.50* (its ledger).

**The refusal.** **Target Account** → **Settlement Account - TRES-USD-HEL (USD) - SETTLEMENT-TRESUSDHEL-USD (USD)**.

You see under the list *"Selected: SETTLEMENT-TRESUSDHEL-USD | Entity: Settlement Account - TRES-USD-HEL (USD)"*; that
account drops out of the source list. Tick source **Shadow · Bank of America USD · HEL0000002** → *1 account(s)
selected* → **Continue**.

3. *Configuration*: **Currency** opens on *AED - UAE Dirham* → **USD - US Dollar**; **Priority** **1** (the default,
   *"1 = Highest priority"*); **Target Balance Amount** **1000000** (*"Funds will be swept to maintain this balance in
   source accounts"*) → **Continue**.
4. *Review*: *DEMO Helios BofA → Emirates NBD (form) · Target Balance • DAILY · Corporate Helios Global Inc. ·
   Currency USD · Priority 1*; *Account flow*: *Source Accounts 1 account(s) → Sweep → Settlement Account - TRES-USD-HEL
   (USD) · SETTLEMENT-TRESUSDHEL-USD*; *Source Accounts: Shadow · Bank of America USD · HEL0000002*. (The target
   amount is not shown here.) → **Create Rule**.

You see, under the review, the backend's refusal (HTTP 400): *"The rule was not created — Source HEL0000002 is at
another bank, so the target must be its legal entity's own account held at the home bank; SETTLEMENT-TRESUSDHEL-USD is
not an account held at the home bank."* Nothing is created.

**The rule.** **Back**, **Back** (to *Accounts*; the source is still ticked) → **Target Account** → **Shadow: Helios
Global Inc. USD current account - SHADOW-USD-54361989 (USD)**.

You see *"Selected: SHADOW-USD-54361989 | Entity: Shadow: Helios Global Inc. USD current account"*, *1 account(s)
selected*. The refusal message from the last submit is still on screen here and on *Review*; ignore it. **Continue**
(*Configuration* kept USD, 1, 1000000) → **Continue** → *Review* now shows *Sweep → Shadow: Helios Global Inc. USD
current account · SHADOW-USD-54361989* → **Create Rule**.

You see the window close (HTTP 200; no toast). The page now reads *Sweep Rules 5*, *Active Rules 14*, *Linked
Accounts 16*, and a new card: `DEMO Helios BofA → Emirates NBD (form) · ACTIVE · SWP-TB-401710 · Type Target ·
Frequency DAILY · Total Swept USD 0.00 · 1 source account(s)`. Behind it (`GET /api/v1/sweeping/rules`): target
SHADOW-USD-54361989, `targetEntityCode: HEL-US`, source HEL0000002 with `entityCode: HEL-US`, USD, priority 1,
TARGET_BALANCE 1,000,000, DAILY — the entity code now comes from the account's legal entity, not the account name the
form sends.

**Pause it** (this recording only; on a fresh instance, keep the rule and run it in step 3). On the
**SWP-TB-401710** card, the icons are **Edit rule**, **Pause rule**, **Delete rule** → **Pause rule**.

You see, with no confirmation, the card change to **PAUSED** and its icon to **Activate rule**; *Active Rules 13*
again (*Linked Accounts* stays 16). The rule is kept, never run; steps 3–5 use **SWP-TB-355475**, which reads exactly
as this rule does (ACTIVE, Total Swept USD 5.2M after the 12 Nov run).

> Talking point: other-bank accounts are sources only, and they sweep into their own legal entity's account at the
> home bank, in the same currency. Cross-entity concentration goes through the in-house bank.

## 3. Run it: the MT101 (≈3 min)

The seeded SWP-HEL-001 also sweeps HEL0000002 (zero balance), so run the new rule alone.

1. **Cash Concentration → Run Sweeps** (*"Select specific rules to execute or run all active sweep rules at once."*,
   12 active rules listed).
2. Tick only **DEMO Helios NY BofA Target 1M → ENBD USD · SWP-TB-355475** → **Run 1 Rule(s)**.

You see *Sweeps Complete · 1 Executed · 1 Successful · 0 Failed* → **Close**. Nothing else ran. The tiles now read
*Today's Swept USD 5.2M · GBP 3.1M*; the rule card *Total Swept USD 5.2M · Last run: Just now*.

3. Tab **Execution History**, top row:
   `EXE-20261112201308-0376 · USD 5,219,974.50 · USD 6,219,974.50 → USD 1,000,000.00 · Instructed · awaiting bank
   statement · Just now`. Click it (**Execution Details**):
   - *DEMO Helios NY BofA Target 1M → ENBD USD · Instructed · awaiting bank statement*; transfer flow *HEL-US
     HEL0000002 → USD 5,219,974.50 → HEL-US SHADOW-USD-54361989*; Amount swept USD 5,219,974.50; Balance before
     USD 6,219,974.50 (the statement's closing); Balance after USD 1,000,000.00; *11/12/2026, 8:13:08 PM*.
   - **Instruction to the other bank**: *Instructed · Format MT101 · Reference XSW70D5530C6B124 · Amount USD
     5,219,974.50 · Execution date 12 Nov 2026 · Instructed 12 Nov 2026, 08:13 PM · Standard fee USD 500.00 · charged
     to the target · Instruction fee USD 25.00* (standard fee 0.05% capped at 500, plus 25 per instruction).
   - **Message**, with **Download** (saves `XSW70D5530C6B124.txt`) and **Mark rejected**:

```
:20:XSW70D5530C6B124
:28D:1/1
:50H:/US33BOFA22000087654321
Helios Global Inc.
:52A:BOFAUS3NXXX
:30:261112
:21:XSW70D5530C6B124
:32B:USD5219974,50
:57A:EBILAEADXXX
:59:/USD54361989
Helios Global Inc.
:70:Cash sweep EXE-20261112201308-0376
:71A:SHA
```

   - *Additional details*: Rule Reference SWP-TB-355475, Sweep Type Target Balance.

What moved, and what did not:

- **Dashboard → Recent statement activity**, newest first:

| Line | Account · reference | | Amount |
|---|---|---|---|
| FEE CREDIT: External sweep instruction fee - XSW70D5530C6B124 | EXCEPTION-DEFAULT-USD · SET-1794514388108-9780 | CR | USD 25.00 |
| FEE DEBIT: External sweep instruction fee - XSW70D5530C6B124 | SHADOW-USD-54361989 · FEE-1794514388108-8393 | DR | USD 25.00 |
| FEE CREDIT: Cash concentration sweep fee - EXE-20261112201308-0376 | EXCEPTION-DEFAULT-USD · SET-1794514388076-7119 | CR | USD 500.00 |
| FEE DEBIT: Cash concentration sweep fee - EXE-20261112201308-0376 | SHADOW-USD-54361989 · FEE-1794514388074-4219 | DR | USD 500.00 |
| External sweep (instructed) - DEMO Helios NY BofA Target 1M → ENBD USD | SHADOW-USD-54361989 · SWP17945143880313269 | CR | USD 5,219,974.50 |
| External sweep (instructed) - DEMO Helios NY BofA Target 1M → ENBD USD | HEL0000002 · SWP17945143880303872 | DR | USD 5,219,974.50 |

- **Bank Accounts** (Corporate **Helios**): *Helios Global Inc. USD current account · USD54361989 · Emirates NBD ·
  USD 10,219,974.50* — the home bank's account is credited at once (bank balance and physical account). *Helios NY
  USD Operating* still reads **USD 6,219,974.50**: BofA's balance is BofA's, and only its statement moves it.
  - BofA **View Details**: Bank Balance USD 6,219,974.50, Available USD 6,254,500.00, and **"Pending sweep:
    USD 5,219,974.50 (awaiting statement)"**; *last statement 11 Nov 2026*.
  - Emirates NBD **View Details**: Current / Available / Ledger USD 10,219,974.50; shadow Bank Balance USD
    10,219,974.50, but *Available USD 5,000,000.00 · On Hold USD 5,219,974.50* (finding 2).
- **Multi-Bank Liquidity**, Helios: *In transit (pending sweeps) AED 19,170,356.35* (the USD 5,219,974.50); USD
  *25,326,525.50*, Bank of America *USD 1,034,525.50* (6,254,500.00 available less the pending 5,219,974.50),
  Emirates NBD still *USD 5,000,000.00*. **By Bank**: `SHADOW-USD-54361989 · USD 10,219,974.50 · USD 5,000,000.00` and
  `HEL0000002 · USD 6,219,974.50 · USD 1,034,525.50`.

> Talking point: the home bank books its own side at once — Helios can use the money at Emirates NBD now — and keeps
> the BofA leg pending until BofA's statement shows it. Nothing is transmitted: the MT101 is generated for the
> channel the bank would send it on.

## 4. BofA's next statement confirms it (≈2 min)

BofA's end-of-day statement for 12 Nov, played from the console. It opens at the 11 Nov closing (no break), carries
the debit with the instruction's reference as end-to-end id, and closes at 6,219,974.50 − 5,219,974.50. It is dated
today, the last day for which a statement can exist yet. (It carries only the sweep; the 10:30 intraday items are left
out so the closing lands on the 1,000,000 target, and their provisional lines become *Superseded*.)

```js
const mt940 = `:20:BOFA261112-001
:25:BOFAUS3N/8765432101
:28C:00046/001
:60F:C261111USD6219974,50
:61:2611121112D5219974,50NTRFXSW70D5530C6B124//BOFA2611120051
:86:/EREF/XSW70D5530C6B124/REMI/USTD//MT101 EBILAEAD sweep to USD54361989
:62F:C261112USD1000000,00
:64:C261112USD1000000,00
-`;
await fetch('/api/v1/statements/intake?fileName=BOFA_MT940_20261112.sta',
  { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: mt940 }).then(r => r.json())
```

You see *"1 applied, 0 duplicate, 0 refused"*: statement **BOFA261112-001**, END_OF_DAY, 2026-11-12, APPLIED,
`gap false`, **`breakFlag false`**, `differenceToPlatform -5219974.5` (measured against the bank balance, which the
sweep did not move; informational), `older false`, `cbsFed false`.

- **Cash Concentration → Execution History**: `EXE-20261112201308-0376 · USD 5,219,974.50 · … · Settled · 3m ago`.
  **Execution Details**: *Settled*; instruction *Confirmed*, and **"Confirmed by the other bank's statement — Debit of
  USD 5,219,974.50 valued 12 Nov 2026 · bank reference BOFA2611120051 · 12 Nov 2026, 08:16 PM · line
  35df7ab8-ad0c-4b0d-9024-6098724c8bda"**. **Mark rejected** is gone.
- **Bank Accounts**: *Helios NY USD Operating · USD 1,000,000.00 · SYNCED*. **View Details**: Current / Available /
  Ledger USD 1,000,000.00; Bank Balance and Available USD 1,000,000.00, On Hold —; **no "Pending sweep" line**;
  *"Updated by bank statements · last statement 12 Nov 2026"*, *As of 11/12/2026, 11:59:59 PM*. Lines: *BOFA261112-001
  · closing USD 1,000,000.00 · 12 Nov 2026 · FINAL · USD -5,219,974.50 · MT101 EBILAEAD sweep to USD54361989*; the two
  12 Nov intraday lines now *SUPERSEDED*.
- **File Ingest Pipeline → Bank (automatic)**: top line `BOFA_MT940_20261112.sta · API · 12 Nov 2026, 08:16 PM ·
  1 applied · 0 duplicate · 0 refused · Processed`.
- **Multi-Bank Liquidity**, Helios: *In transit (pending sweeps) AED 0.00*; Bank of America *USD 1,000,000.00*;
  Emirates NBD still *USD 5,000,000.00*, USD total *25,292,000.00* (finding 2).

> Talking point: the other bank's statement is the proof. The debit is matched by its reference (or, without one, by
> amount and value date); it is the account's own movement, so it is no break.

## 5. A rejection: Citibank refuses the MT101 (≈3 min)

Citibank HEL0000003 had no statement (*Stale · Last statement none yet*), so one is needed before it can be swept.

1. Citi's end-of-day statement for 11 Nov, from the console — no movements, opening = closing = its bank balance:

```js
const citi = `:20:CITI261111-001
:25:CITIUS33/7654321001
:28C:00001/001
:60F:C261110USD4012000,00
:62F:C261111USD4012000,00
:64:C261111USD4012000,00
-`;
await fetch('/api/v1/statements/intake?fileName=CITI_MT940_20261111.sta',
  { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: citi }).then(r => r.json())
```

   You see *"1 applied, 0 duplicate, 0 refused"*, CITI261111-001, no gap, no break.

2. The rule. On 12 Nov it was created from the console, because the form then returned a 500 (fixed in 0a4f8ce; on
   a later run, create it through the form as in step 2, with source **HEL0000003** and priority **2**). As recorded:

```js
await fetch('/api/v1/sweeping/rules', { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({
    ruleName: 'DEMO Helios Boston Citi Target 1M → ENBD USD',
    corporateId: 'dd000004-0000-0000-0000-000000000004',
    sweepType: 'TARGET_BALANCE', targetAmount: 1000000, frequency: 'DAILY', currencyCode: 'USD', priority: 2,
    targetAccountId: 'cb3d697f-bfa8-4ebf-b062-76e59d9aa389', targetAccountNumber: 'SHADOW-USD-54361989',
    targetEntityCode: 'HEL-US',
    sourceAccounts: [{ accountId: 'dd000004-4000-0000-0000-000000000003', accountNumber: 'HEL0000003',
                       entityCode: 'HEL-US', entityName: 'Helios Global Inc.' }] }) }).then(r => r.json())
```

   You see *"Sweep rule created successfully"*, **SWP-TB-640202**.
3. **Run Sweeps** → tick only **SWP-TB-640202** → **Run 1 Rule(s)** → *1 Executed · 1 Successful · 0 Failed*.
   **Execution History**: `EXE-20261112201735-7222 · USD 3,012,000.00 · USD 4,012,000.00 → USD 1,000,000.00 ·
   Instructed · awaiting bank statement`. Details: MT101 **XSW31CB42BC11274** (`:50H:/US33CITIUS33000076543210`,
   `:52A:CITIUS33XXX`, `:32B:USD3012000,00`, `:57A:EBILAEADXXX`, `:59:/USD54361989`), fees 500.00 + 25.00. Emirates NBD
   bank balance 13,231,974.50.
4. **Mark rejected** → reason **Citibank: MT101 refused, no MT101 agreement on file for this account** → **Mark
   rejected**.

You see, in the same window: *Failed*; instruction *Rejected*; *Instruction fee USD 25.00 · refunded*; *"Rejected 12 Nov
2026, 08:18 PM — Citibank: MT101 refused, no MT101 agreement on file for this account. The target's credit was reversed
and the instruction fee refunded; the standard fee stands."*; *Execution Failed — External sweep XSW31CB42BC11274 failed
(REJECTED): …*. The list row reads *Failed* with that message; *Today's Swept* stays *USD 5.2M · GBP 3.1M*.

Behind it: SHADOW-USD-54361989 back to bank 10,219,974.50 (ledger 5,218,949.50: the 500.00 standard fee stands);
HEL0000003 ledger back to 4,012,000.00, committed 0.00; four REVERSAL postings (records table); one notification,
*"External sweep failed: HEL0000003 — External sweep XSW31CB42BC11274 failed (REJECTED): … 3012000.0000 USD taken back
from SHADOW-USD-54361989; the instruction fee is refunded."* Rule SWP-TB-640202: 1 run, Total Swept 0.00.

Not shown: the 2-business-day expiry (executed Thu → expired the following Tue by the 18:00 job). It cannot be played
from the portal; see finding 9.

---

## How to repeat

- **Rules.** SWP-TB-355475 and SWP-TB-640202 stay on vam-five (ACTIVE, DAILY); SWP-TB-401710 (the form's copy of
  SWP-TB-355475) is PAUSED. Reuse them, or create new ones through the form (step 2). Do not activate SWP-TB-401710
  while SWP-TB-355475 is active: both sweep HEL0000002.
- **Statements.** A sweep is sized on the account's last end-of-day statement, which must be dated on or after the
  last business day. On day D: post BofA's statement for the previous business day, opening at the last closing
  (1,000,000.00 after this recording, unless a statement came since), with credits so it closes above 1,000,000, a
  new `:20:` and `:28C:00047/001`; run SWP-TB-355475; then post the statement dated D with the debit and
  `/EREF/<reference>`, opening at the previous closing. On **13 Nov** this does not work: the previous business day's
  statement (12 Nov) already exists and closes at the 1,000,000 target, so there is nothing to sweep.
- **Do not use "Run All Active"** for this demo: SWP-HEL-001 (zero balance) sweeps the same BofA account.
- **Citi** goes stale again on 13 Nov (last statement 11 Nov); post a new Citi statement before step 5.
- **Reload the tab** if a deploy happened while it was open.

## For a technical audience (Swagger: `/api/swagger-ui.html`)

| Endpoint | What it does |
|---|---|
| `GET /api/v1/treasury/external-authorities?shadowVaId=\|corporateId=` | Authorities of an account / a corporate |
| `POST /api/v1/treasury/external-authorities` · `…/{id}/activate\|suspend\|revoke` | Register (PENDING_ACTIVATION) and change an authority; audited |
| `POST /api/v1/statements/intake?fileName=` | Take in an MT940 / MT942 / camt.053 / camt.052 (raw body or multipart); confirms pending sweep instructions |
| `GET /api/v1/statements/files?limit=` · `GET /api/v1/statements/lines?physicalAccountId=` | Intake log; statements and lines of one account |
| `POST /api/v1/sweeping/rules` | Create a rule; 400 with the reason for other-bank targets, cross-entity, no authority |
| `POST /api/v1/sweeping/execute-async` `{ruleIds}` · `GET /api/v1/sweeping/runs/{runId}` | Run rules now (what **Run Sweeps** calls); poll the run |
| `GET /api/v1/sweeping/executions/{id}/instruction` | The MT101 / pain.001, its status, confirming line and fees |
| `POST /api/v1/sweeping/instructions/{id}/reject` `{reason}` | Mark rejected: reverse, refund the instruction fee, alert |

## Gotchas seen while recording

- A tab open across a deploy runs the old bundle; reload.
- **Corporate filter** on Bank Accounts opens on *TestShop*; set Helios each time.
- **Create Rule** Configuration step defaults the currency to **AED**; set USD. Its Corporate opens on *Select
  Corporate...* whatever the page filter says.
- A refusal message on the Review step stays visible after going Back and changing the accounts (still so on 13 Nov).
- Cash Concentration's tiles and Execution History do not follow the Corporate filter (Helios selected: *Today's
  Swept GBP 3.1M* from Albion, other corporates' executions listed).

## Findings (12 Nov 2026, e7dae35; updated 13 Nov, 0a4f8ce)

1. ~~Create Rule returns 500 for any account whose name is over 20 characters.~~ **Fixed in 0a4f8ce**: rule legs take
   their entity code from the account's legal entity (`SweepService.legEntityCode`), not the account name the form
   still sends. Step 2 re-recorded through the form on 13 Nov: SWP-TB-401710, `HEL-US` on both legs.
2. ~~A sweep credit to a home-bank shadow moves its bank balance but not its bank available balance.~~ **Fixed in
   0a4f8ce** (`VirtualAccount.mirrorBankBalance` moves available too), for movements from then on. The 12 Nov values
   were not repaired: on 13 Nov SHADOW-USD-54361989 still reads bank balance 10,219,974.50, bank available
   5,000,000.00.
3. The 6.3 runbook in `tasks/todo.md` names the target **SHADOW-USD-54361990**; on vam-five it is
   **SHADOW-USD-54361989** (V55 re-pointed SWP-HEL-001/002 there). It also says to post a BofA statement first; on
   vam-five the 11 Nov sample statement had already made BofA fresh.
4. The target's ledger was **0.00** against a bank balance of **5,000,000.00** before the demo (shadow created 6 Nov,
   CORE_BANKING, *Stale · as of 6 Nov*); the rule form lists it at *USD 0.00*. After the demo, ledger 5,218,949.50 and
   bank balance 10,219,974.50.
5. ~~Both fees are credited to **EXCEPTION-DEFAULT-USD**.~~ **Fixed in 0a4f8ce**: fees charged to a home-bank shadow
   now settle on the corporate's top-level settlement VA in that currency (here SETTLEMENT-TRESUSDHEL-USD) when exactly
   one exists. The 12 Nov fees stay where they landed (EXCEPTION-DEFAULT-USD reads USD 1,025.00 in the 13 Nov form).
6. **Link External** still has no data source field and always sends `OPEN_BANKING_UAE`; **Add Account** still
   defaults the BIC to `EABORAEDXXX` (`PhysicalAccountsPage.tsx`, `CreateAccountModal`). A statement-fed external
   account still needs the console. Checked in the code, not driven in the browser.
7. Bank Accounts' **Emirates NBD** tile adds currencies: *USD 15,000,000.00* for GBP, EUR and USD 5,000,000 each, then
   *USD 20,219,974.50*.
8. Multi-Bank Liquidity says *"Effective totals exclude stale and never-refreshed positions"*, but the stale Citi
   HEL0000003 (4,012,000.00) was in the USD total (30,546,500.00) before step 5. The headline showed *▲ 582.9% · 1D*.
9. The seeded DAILY rules last ran on 28 Sep, so vam-five's scheduled jobs appear to be off (`VAM_SCHEDULING_ENABLED`
   in its `.env`; not checked). If so, the 2-business-day expiry job does not run there either, and a pending
   instruction only ends by a statement or **Mark rejected**.
10. ~~The failure notification prints the amount unformatted, *"3012000.0000 USD"*.~~ **Fixed in 0a4f8ce**: alerts
    and refusals print *"3,012,000.00 USD"*. The 12 Nov notification keeps its old text.
11. Cash Concentration tiles (*Total Swept*, *Today's Swept*, *Active Rules*, *Linked Accounts*) and Execution History
    ignore the Corporate filter.
12. Create Rule: currency defaults to AED; a refusal message persists after Back; Program lists nothing to pick
    (all three still so on 13 Nov). Also seen on 13 Nov: Corporate is not preselected from the page filter; Review
    does not show the target amount; no toast on success; a source leg's `entityName` is stored as the account name
    (*"Shadow · Bank of America USD"*).
13. Seed naming: rule names *"Helios NY USD ZBA → SF Header"* / *"Helios Boston > 5M → SF"* still name the JPMorgan SF
    header after V55 re-pointed them to Emirates NBD; the Citi shadow of *Helios Boston USD Operating* is called
    *"Citibank USD (NYC)"*; BofA's physical account is `SWIFT_MT942`, its shadow `SWIFT_MT940`.
14. Seen, as designed: the confirming statement's `differenceToPlatform` is −5,219,974.50 (the sweep debit; no break);
    the standard fee is not refunded on rejection.
15. (13 Nov, 0a4f8ce) **`SweepService.createRule` is no longer `@Transactional`.** 0a4f8ce inserted `legEntityCode`
    between the `@Transactional` annotation and `createRule`, so the annotation now sits on the private helper (where
    Spring ignores it) and `createRule` runs without a transaction: the rule save and the physical-account
    sweep-participation update are no longer atomic. Rule creation worked on vam-five; found reading the diff.
16. (13 Nov) Creating a rule re-points each involved physical account's `sweepRuleId` to the newest rule. After step
    2, 8765432101 and USD54361989 point at the paused SWP-TB-401710 (before: SWP-TB-355475 and SWP-TB-640202);
    `sweepEnabled` and `sweepRole` unchanged. Display mirror only; no sweep reads it.

Fixed since the 12 Nov Albion draft (its findings 1, 2, 4–7, 11–13): authorities exist and are checked at creation and
at every run; an MT101 is produced; the home bank's side moves its bank balance and physical account; the external
leg waits for the statement; Create Rule shows the backend's refusal; stale sources are skipped; Today's Swept counts
instructed and settled sweeps per currency; per-rule history no longer 500s; Rule Reference and Sweep Type are shown.
Still open: Link External data source and the EABORAEDXXX default (finding 6), the Corporate filter on Bank Accounts,
the seed data-source mismatches.
