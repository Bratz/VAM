# Epic — Corporate Reorganisation (acquisition, merger, divestiture, re-parenting)

Status: **Execution is built and live; governance around it is not.** The four
structural operations (acquire, merge, divest, move) run end-to-end through the
`/api/v1/treasury/hierarchy-operations` controller and are driven from the
Reorganization page with two wizards and a modal. What does *not* exist yet is
everything that makes those operations auditable and reversible: operation
history, the approval workflow, dry-run and rollback are all stubs or absent.

Written against the code as of 2026-09-30, not against intent. Where a thing is
a stub this document says so, because the endpoints exist and return `200` — an
empty list from `/history` reads like "no operations yet", not "not built".

---

## Why this exists

A corporate group's legal structure changes: it buys a subsidiary, two entities
merge into a new holding company, a division is sold off, a branch is re-parented
under a different region. In a virtual-account platform every one of those events
has to be reflected in the account hierarchy *without* breaking the things hanging
off it — settlement accounts, currency mirrors, credit limits, in-flight balances.

Doing it by hand means re-keying a hierarchy and hoping nothing was missed. The
capability Aperture offers is that the structural move is one reviewed operation
that carries its dependants with it.

---

## Capability map (what is actually wired)

### Backend — `CorporateHierarchyOperationsController`

| Endpoint | Purpose | State |
|---|---|---|
| `POST /acquire` | Target's ROOT becomes an AGGREGATION under the acquirer's ROOT | built |
| `POST /merge` | Two corporates fold into a newly created third | built |
| `POST /divest` | An AGGREGATION is promoted to ROOT of a new corporate | built |
| `POST /move/transaction-va` | Re-parent a single transaction VA | built |
| `POST /move/aggregation` | Re-parent a whole subtree | built |
| `POST /validate/move`, `/validate/acquisition` | Pre-flight checks | built |
| `GET /movable-vas`, `/movable-aggregations` | What is legal to move | built |
| `GET /policies/move`, `/policies/merge` | The rule set, served to the UI | built |
| `POST /cleanup/currency-mirrors` | Repair mirrors after a structural change | built |
| `GET /history` | Operation audit trail | **stub — returns empty page** |
| `GET /pending-approvals` | Queue of operations awaiting sign-off | **stub — returns empty list** |
| `POST /approve/{id}`, `/reject/{id}` | Act on a pending operation | **stub — returns "not yet implemented"** |

`HierarchyMergeService` holds the transactional work: `acquireCorporate`,
`mergeCorporates`, `divestAggregation`, `validateAcquisition`. Each is
`@Transactional`, so a partial reorganisation cannot be left behind.

### Frontend

| Surface | File | Role |
|---|---|---|
| Reorganization page | `HierarchyOperationsPage.tsx` (952 ln) | entry point, rules modal, operation launcher |
| Acquisition wizard | `AcquisitionWizard.tsx` (851 ln) | Select Target → Configure → Limit Policy → Review |
| Merger wizard | `MergerWizard.tsx` (758 ln) | Select Corporates → New Corporate → Limit Policy → Review |
| Divestiture | `DivestitureModal.tsx` (322 ln) | promote an aggregation out to its own corporate |
| Rules reference | modal within the ops page | Transaction VAs / Aggregations / M&A / Not Movable |

### The rule set the platform enforces

Not movable at all: `ROOT`, `CURRENCY_MIRROR`, `SETTLEMENT`, `EXCEPTION`,
`PHYSICAL_MIRROR`.

On a move: the entire subtree travels together with its internal structure
intact; settlement VAs inside it are preserved; currency mirrors are created at
the new parent for every currency in the subtree; a move into your own descendant
is rejected as circular.

### Limit policies (`MergeLimitPolicy`)

- `COMBINE_LIMITS` — add both group limits; the target's structure becomes a sub-hierarchy
- `RESET_TARGET_LIMITS` — cancel the target's limits; the CFO reallocates from the acquirer's pool
- `PRESERVE_TARGET_STRUCTURE` — the target's whole limit hierarchy survives, nested

---

## User stories

### Acquisition

> **As a** group treasurer **who has just closed an acquisition**
> **I want** the target's account hierarchy folded under our own
> **so that** its cash is visible in our position from day one, without re-keying it.

- Given a target corporate, when I acquire it, its ROOT becomes an AGGREGATION under my ROOT
- All VAs beneath it migrate with their structure intact
- Currency mirrors are created at my ROOT for every currency the target brings
- I choose a limit policy before committing, and the review step states what it will do
- The operation is atomic: if any part fails, none of it is applied

### Merger

> **As a** group treasurer **merging two entities into a new holding company**
> **I want** a new corporate created and both hierarchies moved beneath it
> **so that** the merged group has one root without either side being subordinated to the other.

- I name the new corporate; it is created as part of the same transaction
- Both corporates' structures are re-parented under it
- One limit policy applies to both sides
- Neither original corporate is left orphaned or half-moved

### Divestiture

> **As a** group treasurer **selling a division**
> **I want** that aggregation promoted out into a corporate of its own
> **so that** it can be handed over as a standalone structure.

- The selected AGGREGATION becomes the ROOT of a newly created corporate
- Child VAs migrate; settlement VAs are preserved
- The new corporate gets an independent limit structure
- **Fixed 2026-09-29:** divestiture previously never created the corporate it
  promoted into — `resolveNewCorporate` now does, inside the transaction, covered
  by `DivestitureCreatesCorporateTest`

### Re-parenting

> **As a** treasury operations user **reorganising a region**
> **I want** to move a VA or a whole aggregation to a different parent
> **so that** the hierarchy matches how the business is actually run.

- I can see what is legally movable before I try (`/movable-vas`, `/movable-aggregations`)
- Moving into a descendant is refused
- The subtree keeps its internal shape

### Guardrails

> **As a** treasury operations user
> **I want** to be told what an operation will do before it does it
> **so that** I am not discovering consequences afterwards.

- Validation runs before commit and surfaces blocking issues
- The rules the platform enforces are readable in the UI, served from the backend
  rather than duplicated in the frontend

---

## Not built — the governance half

These are the stories that turn a working operation into one a bank would let
someone run. Ordered by how load-bearing they are.

### 1. Operation history (audit trail)

> **As** an auditor **I want** every reorganisation recorded — who, when, what
> moved, under which limit policy — **so that** a structural change can be
> explained months later.

`GET /history` is a stub returning an empty page with a `TODO`. There is no
operation entity and no persistence. **This is the biggest gap**: the operations
are irreversible, mutate the hierarchy, and currently leave no record beyond
application logs.

### 2. Approval workflow

> **As** a treasury approver **I want** to sign off a reorganisation before it
> executes **so that** no single user can restructure the group unilaterally.

Today `approvedBy` is accepted on the request and carried into the limit transfer
as attribution, but **nothing gates on it** — the operation executes immediately.
`/pending-approvals`, `/approve`, `/reject` all return stub responses, the last of
which says "approval workflow not yet implemented" in its own success message.

### 3. Dry-run / preview

> **As** a treasurer **I want** to see the resulting hierarchy before committing
> **so that** I can check the shape rather than trust the description.

Validation answers "is this legal". It does not answer "what will it look like".
The Simulator already proves the pattern — propose a structure, score it, compare
— and a reorganisation preview is the same idea applied to hierarchy.

### 4. Rollback

> **As** a treasurer **I want** to reverse a reorganisation **so that** a mistake
> is recoverable.

Each operation is atomic, so a *failed* one leaves nothing behind. A *successful*
one cannot be undone — there is no inverse operation and no snapshot. Depends on
(1): you cannot reverse what you did not record.

---

## Risks and notes

- **Irreversible + unaudited is the real exposure.** Not the individual gaps.
  Either one alone would be tolerable; together they mean a wrong merge is
  undiscoverable and unfixable.
- **Stubs return 200.** `/history` and `/pending-approvals` return empty
  successes, so a UI built against them shows "nothing to approve" rather than
  "this does not work yet". Anything consuming them should be treated as
  unimplemented, not empty.
- **Limit policy is chosen but its effect is not previewed.** The user picks
  between combining, resetting and preserving without seeing the resulting limit
  tree. (A divestiture limit-policy picker that did nothing at all was removed on
  2026-09-29 — the merger/acquisition ones are wired.)
- Currency-mirror creation on move is automatic; `/cleanup/currency-mirrors`
  exists as a repair path, which implies the automatic path has been known to
  drift.

---

## Suggested sequence

1. **Operation history** — persist an entity per operation. Unblocks everything else.
2. **Approval workflow** — gate execution on it; `approvedBy` already threads through.
3. **Dry-run preview** — reuse the Simulator's propose/compare vocabulary.
4. **Rollback** — only meaningful once (1) exists.
