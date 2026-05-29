// ============================================================================
// inventoryGrouping — group Physical Accounts by bank with relationship tier
//
// Pure + framework-free. Returns structured groups; the bank-relationship
// pill *classes* are a UI concern and stay in the component (the design-system
// recipe: Home=success, Group=info, External=warning). This file only
// classifies and orders.
// ============================================================================

import type {
  BankGroup,
  BankRelationship,
  SimulatedShadow,
  SimulatorPhysicalAccount,
} from '../../components/simulator/types';

/**
 * Resolve a Physical Account's bank-relationship tier. Prefers the explicit
 * `bankRelationship`, then the `isHomeBank` / `isExternalBank` booleans the
 * backend also sets, defaulting to INTERNAL (home) when nothing says
 * otherwise — consistent with `HomeBankProperties`-driven classification used
 * elsewhere (MultiBankLiquidity, PhysicalAccountsPage).
 */
export function classifyRelationship(
  a: Pick<
    SimulatorPhysicalAccount,
    'bankRelationship' | 'isHomeBank' | 'isExternalBank'
  >,
): BankRelationship {
  if (a.bankRelationship === 'EXTERNAL' || a.isExternalBank === true) return 'EXTERNAL';
  if (a.bankRelationship === 'GROUP') return 'GROUP';
  return 'INTERNAL';
}

/** Stable human label. Pure string — safe in a util (no Tailwind). */
export function relationshipLabel(rel: BankRelationship): string {
  switch (rel) {
    case 'EXTERNAL':
      return 'External bank';
    case 'GROUP':
      return 'Group bank';
    default:
      return 'Home bank';
  }
}

// Home first, then Group, then External — the order the structure tree reads.
const REL_ORDER: Record<BankRelationship, number> = {
  INTERNAL: 0,
  GROUP: 1,
  EXTERNAL: 2,
};

/**
 * Group accounts by `bankCode`. Group order: relationship tier, then bank
 * name. Accounts within a group: account number ascending. A group's
 * relationship is taken from its strongest (lowest-order) member so a bank is
 * never split across tiers.
 */
export function groupByBank(
  accounts: SimulatorPhysicalAccount[],
): BankGroup[] {
  const groups = new Map<string, BankGroup>();

  for (const acc of accounts) {
    const code = acc.bankCode || '—';
    let g = groups.get(code);
    if (!g) {
      g = {
        bankCode: code,
        bankName: acc.bankName || code,
        bankCountry: acc.bankCountry,
        relationship: classifyRelationship(acc),
        accounts: [],
      };
      groups.set(code, g);
    }
    // Keep the strongest relationship for the group header.
    const accRel = classifyRelationship(acc);
    if (REL_ORDER[accRel] < REL_ORDER[g.relationship]) {
      g.relationship = accRel;
    }
    if (!g.bankCountry && acc.bankCountry) g.bankCountry = acc.bankCountry;
    g.accounts.push(acc);
  }

  const ordered = Array.from(groups.values());
  for (const g of ordered) {
    g.accounts.sort((x, y) =>
      (x.accountNumber || '').localeCompare(y.accountNumber || ''),
    );
  }
  ordered.sort((a, b) => {
    const r = REL_ORDER[a.relationship] - REL_ORDER[b.relationship];
    return r !== 0 ? r : a.bankName.localeCompare(b.bankName);
  });
  return ordered;
}

/** Set of Physical Account ids in the inventory — for orphan validation. */
export function physicalAccountIdSet(
  accounts: SimulatorPhysicalAccount[],
): Set<string> {
  return new Set(accounts.map((a) => a.id));
}

// ---- structure tree grouping ----------------------------------------------

export interface ShadowBankGroup {
  bankCode: string;
  bankName: string;
  relationship: BankRelationship;
  /** Distinct `snapshotDataSource` values across the group's shadows. */
  dataSources: string[];
  shadows: SimulatedShadow[];
}

function normRel(r: BankRelationship | undefined): BankRelationship {
  return r === 'EXTERNAL' || r === 'GROUP' ? r : 'INTERNAL';
}

/**
 * Group the scenario's proposed shadows by their *frozen snapshot* bank —
 * NOT the live inventory (a scenario must render identically even if the
 * underlying Physical Account later moves banks or is deleted). Group order:
 * relationship tier then bank name; within a group: HEADER before CHILD,
 * then name. The group's relationship is its strongest member's.
 */
export function groupShadowsByBank(
  shadows: SimulatedShadow[],
): ShadowBankGroup[] {
  const groups = new Map<string, ShadowBankGroup>();

  for (const sh of shadows) {
    const code = sh.snapshotBankCode || '—';
    let g = groups.get(code);
    if (!g) {
      g = {
        bankCode: code,
        bankName: sh.snapshotBankName || code,
        relationship: normRel(sh.snapshotBankRelationship),
        dataSources: [],
        shadows: [],
      };
      groups.set(code, g);
    }
    const r = normRel(sh.snapshotBankRelationship);
    if (REL_ORDER[r] < REL_ORDER[g.relationship]) g.relationship = r;
    if (
      sh.snapshotDataSource &&
      !g.dataSources.includes(sh.snapshotDataSource)
    ) {
      g.dataSources.push(sh.snapshotDataSource);
    }
    g.shadows.push(sh);
  }

  const ordered = Array.from(groups.values());
  for (const g of ordered) {
    g.shadows.sort((a, b) => {
      if (a.role !== b.role) return a.role === 'HEADER' ? -1 : 1;
      return (a.proposedVaName || '').localeCompare(b.proposedVaName || '');
    });
  }
  ordered.sort((a, b) => {
    const r = REL_ORDER[a.relationship] - REL_ORDER[b.relationship];
    return r !== 0 ? r : a.bankName.localeCompare(b.bankName);
  });
  return ordered;
}
