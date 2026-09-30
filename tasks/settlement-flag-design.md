# Settlement accounts: the mark, its placement rules, and the missing half of routing

Rewritten 2026-09-30 against the domain rules and the code. Supersedes the first draft, which
validated a flag against a routing chain that turns out to implement half the rule.

## The rules this has to satisfy

A settlement account is a **transaction account carrying a settlement mark**. It is not a separate
kind of account: it still takes postings, and one of its jobs is to receive the physical account's
prices as transactions. Whether the mark may be set at all is a property of the account type
category, i.e. configuration rather than a fixed list in code.

Settlement routing has **two directions, for two different purposes**:

| | contra leg of customer-internal results | results of non-transaction accounts |
|---|---|---|
| direction | **upward** | **downward** |
| from an aggregation account | start at that account | start at that account |
| from a currency account | start at its **mother** | start at its **mother** |
| tie-break | nearest same-currency match | nearest same-currency match |
| nothing found | exception transaction | exception account |

The rule that makes those cohere: **a transaction account settles on itself.** Aggregation and
currency accounts cannot hold their own transactions — their balance is the sum of the transaction
accounts beneath them — so their results have to settle somewhere else, and that is what the
downward search is for.

Placement: a marked account may not sit under an aggregation account that already has a marked
sibling in the same currency.

Removal, and this is the part that matters for a flag you can clear — the condition is **directional
and per purpose**:

- marked account used for customer-internal prices: releasable only if another same-currency
  settlement account exists at the **same or a higher** level;
- marked account used for results of non-transaction accounts: only if one exists at the **same or
  a lower** level.

An account can therefore be free for one purpose and still required for the other. The same shape
governs closing: blocked while the account is relied on as a settlement account, unless another at
the same or a higher level carries the same role.

## Where we stand

1. **Settlement is a category for us, not a mark.** `AccountCategory.SETTLEMENT` *replaces*
   `TRANSACTION`, so our settlement VAs are not transaction VAs — `isTransactionVa()` covers
   TRANSACTION, COLLECTION and DISBURSEMENT only. That one choice cascades:
   `AccountCategory.isSystemCreated()` blocks SETTLEMENT on the generic create
   (`VirtualAccountService:392`), which is why a separate `POST /treasury/settlement-vas` exists,
   which is the side door that produced settlement VAs nothing could route to.

2. **Only the upward search is wired.** `SettlementVaResolverService.resolveSettlementVaWithResult`
   walks upward and then falls back to a program-level *parentless* settlement VA.
   `findSettlementVaBelow` is implemented and is called from exactly one place,
   `FeePostingService:105` — never from the main chain.

3. **That parentless fallback is ours, not the domain's.** The correct fallback is the exception
   account. This matters concretely: the root-level currency mirrors (`M-ROOT-GBP`, `M-EUR-*`) are
   currency accounts, so they should resolve **downward from their mother**. They are currently
   unresolvable, and a parentless settlement VA — which the create endpoint cannot actually produce
   despite its comment claiming otherwise — is the wrong fix for them.

4. Depth cap of 10 in `SettlementVaResolverService.java:358`, against programs that permit deeper
   hierarchies (`Program.java:107-118`).

## Sequence

The ordering is the main correction to the first draft: **routing before the flag.** Validating a
mark against today's chain would check the upward condition and silently skip the downward one, so
clearing a mark could pass a check that never looked where it needed to.

### 1. Implement the downward search (no model change, fixes a measured gap)

Route non-transaction accounts through `findSettlementVaBelow` in the main chain, starting from the
account itself for an aggregation account and from the mother for a currency account, nearest match
wins, exception account when nothing is found. Drop the parentless STEP 3 in the same change, or
keep it behind an explicit comment saying it is a local extension — it currently masks the absence
of the downward search by failing differently.

Raise or remove the depth cap so it cannot be hit before a program's real depth.

### 2. Make settlement a mark

Category returns to `TRANSACTION`; a separate boolean carries the mark.

**This is not a return to the field we just collapsed.** `specialType` was removed because it
*duplicated* `accountCategory` — both answered "what is this account", and they drifted apart. The
mark answers a different question: the category says what the account *is*, the mark says what role
it *also* plays. Neither is derivable from the other, which is exactly why the settlement mark can
be a field and `specialType` could not.

Gate the mark on the account type category rather than a hardcoded refusal list, so the permission
is data.

Migration: 25 VAs currently carry `accountCategory = SETTLEMENT` and would move to
`TRANSACTION` + mark. Every settlement lookup keys on the category today, so this is the
change with real blast radius and wants its own commit and its own before/after sweep.

### 3. The flag, with both-direction validation

Two explicit transitions rather than a writable category, so the create guard stays exactly as it
is and the only route to becoming a settlement account is the one that validates:

- `POST /virtual-accounts/{id}/settlement` — checks the account type category permits the mark, and
  that no marked sibling in the same currency already sits under the same aggregation.
- `DELETE /virtual-accounts/{id}/settlement` — evaluates **both** release conditions, reports each
  separately, and blocks while either fails, naming the accounts that would be stranded. For money
  routing, block rather than warn: unresolved means the next payment parks to exception with no
  signal, which is the failure that started this work.
- `GET /virtual-accounts/{id}/settlement/preview` and a placement-shaped preview for an
  unsaved account, so the UI states the verdict before submit rather than after a failed write.

The validator must **run the resolver**, not restate its rules. A re-implemented rules check is how
a settlement VA came to exist that satisfied every plausible rule and that the resolver never found.
The question at mark-time is "after this change, does every account still resolve?", answered by
executing both searches.

Note that once the model is right, the "must serve at least one account" guard from the first draft
is no longer needed: marking an account already in the hierarchy cannot be unreachable when both
searches exist. Keep it as a warning at most. The orphan it was designed to prevent was an artefact
of creating a new account through a side door with no placement validation — step 2 removes the
side door.

### 4. Screens

`VaCreateModal.tsx` hardcodes `accountCategory: 'TRANSACTION'` at :708 and already picks a
placement through `HierarchyTreePicker` — the mark is a toggle beside it, calling the preview and
rendering what it will serve, or why it is refused, before submit enables.

Account detail gets the same toggle with the release-impact list when turning it off.

`ProgramDetailModal.tsx:343` lists settlement VAs read-only and its empty state claims they "are
created when hierarchy nodes are added", which is false — TEST-ESCROW-NEW had a five-level
hierarchy and none. Correct the text and link to the create flow.

## Verification

- Unit tests per rule: sibling uniqueness, category permission, and both release conditions against
  a built hierarchy fixture. JUnit/Mockito, already in use.
- The sweep is the integration test. Re-run it before and after each step. Current reading is 44
  unresolved across 13 programs, but that number applies the upward chain to every credit-capable
  account — it measures conformance to today's code, not to the rules above. Step 1 should change
  what it counts as much as how many, so recalibrate the sweep to the two directions as part of it.
- Live: mark an account in TEST-IHB-NEW (4 unresolved, no settlement VAs), confirm its count drops;
  then attempt to clear the mark and confirm the block names the affected accounts.

## Open

- Whether step 2 is worth its blast radius, or whether we keep `AccountCategory.SETTLEMENT` and
  accept that our settlement accounts are not transaction accounts. Step 1 stands on its own and
  does not depend on this.
