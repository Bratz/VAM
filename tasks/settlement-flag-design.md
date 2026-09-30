# Settlement VA as a flag on an ordinary VA

Design agreed 2026-09-30. Written against the code, not intent.

## Why

Today a settlement VA is its own creation flow (`POST /treasury/settlement-vas`),
and the result is that "a settlement VA exists" and "a settlement VA is reachable"
drifted apart. The live sweep found 50 credit-capable accounts across 13 programs
that would park to exception, from three causes:

1. **Missing** — 8 programs have no SETTLEMENT-category VA at all.
2. **Wrong marker** — `SETTLEMENT-EUR-TEST-WAL-L2-5962`, `-TEST-IHB-L2-5710`,
   `-TEST-MUL-L2-6328` are named SETTLEMENT but carry `accountCategory:
   TRANSACTION` and no `specialType`. The path lookup filters on category, so
   they are invisible to the resolver.
3. **Unreachable placement** — a settlement VA parented in a sibling branch, or a
   root-level `CURRENCY_MIRROR` target that only a *parentless* settlement VA
   could serve. Proven live: `SETTLEMENT-TESTESCROWNEW-AED` was created
   successfully, looked correct, and resolved nothing.

All three are the same underlying problem: nothing validates placement at the
moment the VA is marked as settlement.

## The model

A settlement VA is an ordinary VA that carries the settlement marker. Creation is
the normal VA flow with the flag set; the flag is what triggers hierarchy
validation, on both edges:

- **Setting the flag** validates the placement rules for this hierarchy.
- **Clearing the flag** validates that the accounts currently served by this VA
  are still served by some other settlement VA meeting the conditions.

## Non-negotiable: one walk, not two

The validator MUST call the same path walk `SettlementVaResolverService` uses to
resolve (STEP 1 sibling → STEP 2 traverse up → STEP 3 parentless top level).
A re-implemented rules check is how cause 3 above happened in the first place: a
placement that satisfies a plausible-looking rule but that the real resolver never
finds. The validation question is literally *"after this change, does every
credit-capable account in the program still resolve?"* — answered by running the
resolver, not by restating its rules.

## Rules

Setting the flag:

- `hierarchyLevel` / parent node level ≤ 6 (existing rule in
  `HierarchyService.createSettlementVa` — no settlement VA at L7).
- No other ACTIVE settlement VA under the same parent in the same currency
  (existing dedupe rule).
- The VA's category must be one that may become settlement. Structural
  categories — ROOT, AGGREGATION, CURRENCY_MIRROR, PHYSICAL_MIRROR,
  EXTERNAL_MIRROR, EXCEPTION — may not. These are containers; Tieto 4.8.2.5 says
  a container cannot hold results of its own.
- Both markers are set together: `accountCategory = SETTLEMENT` AND
  `specialType = SETTLEMENT`. Setting one without the other is exactly the
  cause-2 defect.
- **Coverage check**: at least one credit-capable account in the program must
  newly resolve to it. A settlement VA that serves nothing is accepted only with
  an explicit override, and is reported as such — otherwise we keep minting
  `SETTLEMENT-TESTESCROWNEW-AED`.

Clearing the flag:

- Compute the set of accounts that resolve to this VA today.
- Re-run resolution for each with this VA excluded.
- Any account that becomes unresolved blocks the change, and the response names
  them. This is a money-routing config: unresolved means the next payment parks
  to exception with no warning, which is the failure this whole thread started
  from.

## Changes

### Backend

1. `VirtualAccountDto.UpdateRequest` — the category is immutable today
   (`CreateRequest` has `accountCategory`, `UpdateRequest` does not). Rather than
   making the whole category mutable — which would allow re-categorising a ROOT
   into a TRANSACTION — add two explicit transitions:
   `POST /virtual-accounts/{id}/settlement` and
   `DELETE /virtual-accounts/{id}/settlement`.
2. `SettlementPlacementService` (new, beside the resolver so it shares the walk):
   - `PlacementVerdict validateCanBecomeSettlement(VirtualAccount va)`
   - `PlacementVerdict validateCanClearSettlement(VirtualAccount va)`
   - verdict carries: allowed, reasons, accounts gained, accounts stranded.
3. `VirtualAccountService` — apply both markers together on set, clear both on
   unset, run the verdict first, `@Transactional`.
4. Same verdict served read-only as `GET /virtual-accounts/{id}/settlement/preview`
   and, for a not-yet-created VA, `POST /virtual-accounts/settlement/preview`
   taking the placement — so the UI shows the outcome before submit rather than
   after a failed write.
5. `CreateRequest` path honours the flag through the same validator, so the API
   and the modal cannot diverge.

### Frontend

6. `VaCreateModal.tsx` — `accountCategory: 'TRANSACTION'` is hardcoded at :708.
   Add a "Settlement account" toggle; when on, call the preview endpoint with the
   chosen `placementSel` and render what it will cover, or why it is refused,
   before the submit button enables. The placement picker
   (`HierarchyTreePicker`) already exists and is what the rule needs.
7. Account detail — the same toggle on an existing VA, with the removal impact
   list when turning it off.
8. `ProgramDetailModal.tsx:343` — the settlement panel is read-only and its empty
   state claims "Settlement VAs are created when hierarchy nodes are added",
   which is false: TEST-ESCROW-NEW had a 5-level hierarchy and zero settlement
   VAs. Correct the text and link to the create flow.

### Data (separate, once the above exists)

9. The 3 mis-marked EUR VAs: set both markers via the new transition, which will
   validate placement rather than assume it.
10. The 8 programs with none, and the root-mirror class that needs a parentless
    settlement VA — note the create endpoint cannot currently produce one. Its
    no-`parentNodeId` branch is commented as building "a parentless, purely-virtual
    SETTLEMENT VA" but observably parents it (that is how
    `SETTLEMENT-TESTESCROWNEW-AED` ended up under `M-AED-25650B9E`). Fix or drop
    that branch as part of step 1.

## Verification

- Unit: the verdict for each rule, and the clear-flag stranding check, against a
  built hierarchy fixture. No framework beyond the JUnit/Mockito already in use.
- Live: flag a VA in TEST-IHB-NEW (currently 4 unresolved, 0 settlement VAs),
  confirm the sweep count for that program drops to 0; then attempt to clear it
  and confirm the block names the 4 accounts.
- Re-run the full 20-program sweep before and after; the numbers are the test.

## Open

- Whether the coverage check blocks or warns by default. Written as "warn +
  explicit override" above; blocking is defensible and I would rather be told.
