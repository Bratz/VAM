# Bank statement intake — specification

Status: **decided, planned** (2026-11-12). Plan: `tasks/todo.md` ("Bank statement intake"). Decisions D1–D8 were taken in an interview (D2 and D7 revised the same day:
statements come from the bank and are taken in automatically by the backend); they are marked in the text and listed
at the end.

## 1. Why

External bank accounts can be defined but nothing brings their balances or statements in (verified in code, 12 Nov
2026):

| Today | Effect |
|---|---|
| A physical account carries a data source (CORE_BANKING, INTERNAL_API, OPEN_BANKING_*, SWIFT_MT940/MT942/CAMT053/CAMT052, HOST_TO_HOST, SFTP_IMPORT, aggregators) and its shadow VA (PHYSICAL_MIRROR) a `BalanceDataSource` | The channel is recorded, never used |
| `BalanceRefreshService` refreshes stale shadows every 15 min; only `CoreBankingAdapter` exists; every other source goes to `StubAdapter`, which fails "not connected" | External shadows never change; each one is re-stamped "not connected" every 15 minutes |
| Physical account `/sync` and `/sync-all` refuse "Not available: no bank connection" | No way to update an external balance short of editing data |
| File ingest takes RECEIVABLES, RECEIVABLES_INVOICE, PAYABLES, PAYMENTS, COLLECTIONS — no statements | MT940 / camt.053 files have nowhere to go |
| Inbound ISO: camt.054, pacs.004, pain.002, pain.012 post single transactions; camt.053/054 are only *generated* for VAs | No account statement is ever read |
| `shadow_balance_snapshot` holds one end-of-day row per shadow (used by notional pooling) | External accounts' snapshots repeat a stale seed balance; pooling flags "earlier balance used" |

Effect for the client: the multi-bank liquidity view is real only for home-bank core-banking accounts; everything held
at other banks — the point of a multi-bank view — is frozen.

## 2. How it should work (target)

```
Statement arrives   end of day MT940 / camt.053, intraday MT942 / camt.052 (D1), delivered by the bank and taken in
                    automatically by the backend — no person uploads it (D2, D7) — for an account identified by
                    IBAN or account number in the statement (field :25: / Acct/Id).
Validated           known account of the corporate, currency matches, not a duplicate, sequence checked (R6-R8).
Applied             end of day: the shadow's bank balance, available balance and as-of = the statement's closing
                    balances, the statement wins (D4); one end-of-day snapshot per statement date (back-dated
                    statements fill their own day). Intraday: available/interim balance and provisional lines only (D8).
                    Statement lines stored and shown (D3); no matching in v1 (D5).
Seen                multi-bank liquidity and the account show balance, as-of date and source; an account with no
                    end-of-day statement for the last business day is flagged stale (D6).
```

## 3. Functional requirements

### 3.1 Formats and channels
- R1. Formats (D1): end of day **MT940** and **camt.053**; intraday **MT942** and **camt.052**. Parsing tolerant of bank
  variants of the same standard (field order, optional tags, structured :86:); a file may carry several statements
  (several accounts or days).
- R2. Channel (D2): **automatic backend intake**. The bank's integration (host-to-host / middleware) delivers files to
  an inbound folder the backend polls on a schedule (`vam.statements.inbox`, e.g. every 5 minutes; processed files
  move to `processed/`, unreadable ones to `failed/` with the reason). The same intake service also accepts a file
  posted system-to-system to an internal endpoint (`POST /api/v1/statements/intake`), used by middleware that pushes
  instead of dropping files, and for testing and demos. One service, so validation and effects are identical.
- R2a. Each file taken in is recorded (file name, received at, channel, statements applied / duplicate / refused, and
  why) and shown read-only in the File Ingest Pipeline as source **Bank (automatic)**; a file that cannot be read at
  all, or refused statements, raise an alert for bank operations.

### 3.2 Matching and validation
- R3. The account is found by IBAN, else account number + BIC, among the corporate's physical accounts; unknown
  account → that statement refused with the reason (the rest of the file proceeds).
- R4. Currency in the statement must equal the account's.
- R5. Each statement is stored once: same account + statement id/sequence + date + closing balance = duplicate,
  answered as such and not applied again.
- R6. Sequence: a gap in statement numbers (MT940 :28C:, camt ElctrncSeqNb) is applied but flagged on the account.
- R7. An older statement than the latest applied fills its own day's snapshot but never overwrites the current balance.
- R8. Opening balance of a statement ≠ closing balance of the previous one → applied and flagged (break).

### 3.3 Effects
- R9. Balance (D4, statement wins): an end-of-day statement sets the shadow's `bankBalance` / `bankAvailableBalance` /
  as-of from its closing booked / available balance, and the physical account follows. The difference between that
  closing balance and the balance the platform held just before is recorded on the statement for information only
  (on an external account it is mostly the bank's own movements); the break flag comes from R8 alone.
- R9a. Intraday (D8): an MT942 / camt.052 updates only the available (and interim) balance and as-of, and stores its
  lines as provisional; no snapshot, booked balance untouched. The next end-of-day statement replaces them: its lines
  supersede provisional lines with the same bank reference, provisional lines it doesn't contain are dropped.
- R10. Snapshot: one `shadow_balance_snapshot` row per statement date (closing booked), so pooling and trends use real
  end-of-day figures.
- R11. Statement lines (D3): stored per account with value date, booking date, amount, sign, references (end-to-end id,
  bank reference), remittance text; shown on the account, newest first, with the statement they came from.
- R12. No matching of lines in v1 (D5): they are shown, not matched to the platform's postings or to invoices.
- R13. Home-bank accounts fed by statements, and postings the platform makes itself (pool interest, sweeps): the
  statement's balance wins (D4), so a posting already reflected in the statement is not counted twice; a posting the
  statement does not yet show surfaces as the break of R9 until a later statement includes it.

### 3.4 Freshness and the stub
- R14. Accounts whose source is statement-based are no longer polled by the 15-minute refresh (no "not connected"
  stamps). Stale (D6) = no end-of-day statement for the last business day (weekends skipped; the corporate's market
  calendar where one exists).
- R15. Multi-bank liquidity shows per account: balance, as-of, source, stale/gap/break flags.

### 3.5 Who
- R16. Only the system submits statements (D7): there is no upload screen; corporate users and bank operations see
  balances, lines, flags and the intake log, but nobody types or uploads a statement. Every intake is audited (channel,
  file, statements applied / duplicate / refused). The internal endpoint is for system callers only (service
  credentials where sign-in is on; not exposed through the corporate UI).

## 4. Data model (indicative)

- New `bank_statements` (account, format, kind END_OF_DAY / INTRADAY, statement id, sequence, statement date,
  opening/closing booked, closing available, currency, file ref, status APPLIED / DUPLICATE / REFUSED, flags
  gap / break (+ amount) / older).
- New `bank_statement_lines` (statement, value date, booking date, amount, credit/debit, bank ref, end-to-end id,
  remittance, status FINAL / PROVISIONAL / SUPERSEDED).
- `bank_statement_files` (file name, channel INBOX / API, received at, status, counts, error) — or the File Ingest job
  tables with domain STATEMENTS and source BANK, whichever is smaller.
- Config: `vam.statements.inbox` (folder), poll cadence; the OCI compose mounts the folder as a volume.
  Built (Phase 3): `vam.statements.inbox` / `VAM_STATEMENTS_INBOX` (blank = off), `vam.statements.poll-ms` /
  `VAM_STATEMENTS_POLL_MS` (default 300000); OCI: `/data/statements-inbox` on the named volume `statements_inbox`.

## 5. Acceptance criteria

1. An MT940 for an external account updates its shadow balance, available balance and as-of; multi-bank liquidity
   shows the new figure.
2. A camt.053 with two accounts updates both; one unknown account is refused, the other applied.
3. The same file again: duplicates, nothing changes.
4. A statement for last week fills last week's snapshot and leaves today's balance alone.
5. Opening ≠ previous closing → applied, break flagged; a sequence gap → flagged.
6. Statement-based accounts are no longer stamped "not connected" every 15 minutes; one without a statement past its
   rhythm shows stale.
7. Lines visible on the account with their statement.
8. An MT942 moves the available balance and adds provisional lines; the evening MT940 replaces them and sets the
   booked balance and snapshot.
9. A file dropped in the inbox is taken in within one poll, moved to `processed/`, and listed as Bank (automatic);
   the same file posted to the intake endpoint gives the same result (or duplicates if already taken in).
10. There is no statement upload screen.

## 6. Out of scope (v1)

Live open-banking connections (consent flows, Tarabut/Lean/Plaid); SWIFT network connectivity and the bank's
middleware itself (the backend only reads what it delivers); manual or corporate uploads; matching lines to postings or
invoices; BAI2; per-account statement rhythms; external mandates UI for sweeps; FX revaluation of foreign accounts.

## 7. Decisions (interview, 2026-11-12)

| # | Question | Decision |
|---|---|---|
| D1 | Formats in v1 | **MT940 + camt.053, and intraday MT942 + camt.052** |
| D2 | Channels | **Automatic backend intake**: files the bank delivers to a polled inbound folder, or posted system-to-system to an intake endpoint (revised 12 Nov; was file upload) |
| D3 | What is kept | **Balances + lines** |
| D4 | Authoritative balance | **Statement wins**; differences to the platform's expectation flagged as breaks |
| D5 | Matching lines | **Store and show only** in v1 |
| D6 | Staleness | **1 business day** without an end-of-day statement |
| D7 | Who submits | **The system only** — no person uploads statements; everyone sees the results read-only (revised 12 Nov) |
| D8 | Intraday effect | **Available / interim balance and provisional lines only**; replaced by the end-of-day statement |
