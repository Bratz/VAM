import React, { useEffect, useMemo, useState } from 'react';
import { ResponsiveContainer, Treemap, Tooltip } from 'recharts';
import { ChevronLeft } from 'lucide-react';
import { BalanceHierarchyNode } from '../../services/api';
import { formatCurrency } from '../../utils';

// Real hierarchy data — NOT the old components/dashboard/HierarchyWidgets.tsx
// (that file's BalanceByLevelWidget is unused dead code hardcoding 7 mock
// "levels"; left untouched, not extended here).
//
// balanceStructureApi.getHierarchy() already returns a real recursive tree
// (GROUP > REGION > ENTITY > VIRTUAL_ACCOUNT > SHADOW_ACCOUNT) with a
// consolidatedBalance per node — exactly the {name, size, children} shape
// recharts' Treemap wants, so no data reshaping beyond capping depth.

interface TreemapNode {
  id?: string;
  // What recharts actually draws on the box (its default content reads
  // this field, and hardcodes it — there's no prop to point it elsewhere
  // short of a fully custom `content` render, which crashes this recharts
  // version's internals, confirmed live: "Cannot read properties of
  // undefined (reading 'length')" inside its own chart internals as soon
  // as a custom `content` function is passed to <Treemap>, on/off
  // reproduced by adding/removing just that one prop). So EntityHierarchyTreemap
  // folds the on-box amount into this string directly (e.g. "Treasury ·
  // AED 1.5M"); flat corporate/program breakdowns leave it as the plain
  // name. Use `displayName` (falls back to this) for anything WE render
  // ourselves, like the tooltip header, so it isn't stuck showing the
  // same amount-suffixed string.
  name: string;
  displayName?: string;
  size: number;
  // Only set by EntityHierarchyTreemap (the corporate/program breakdowns
  // are always drillable via onItemClick, so they leave this undefined —
  // see the tooltip's "Click to explore" hint below). Drives whether a
  // leaf entity node (nothing further to walk into) still claims to be
  // clickable in its own tooltip.
  hasChildren?: boolean;
  // The node's own balance in ITS actual account currency, before FX
  // conversion into the single reporting currency `size` uses (needed so
  // box areas stay comparable across a mixed-currency tree — see
  // sumBalance below). Only set by EntityHierarchyTreemap, where each
  // node genuinely has one native currency; the corporate/program
  // breakdowns are themselves multi-currency aggregates with no single
  // native currency to show. Omitted (not just zero) when the node's own
  // slice is 0 — e.g. a GROUP box whose value is entirely its children's,
  // not its own — so the tooltip doesn't imply a real account reads zero.
  nativeCurrency?: string;
  nativeAmount?: number;
  // Per-currency composition of this node's WHOLE subtree, e.g.
  // [{currency:'GBP', amount:600}, {currency:'AED', amount:5000}] — only
  // set by EntityHierarchyTreemap, and only meaningfully shown in the
  // tooltip when there's more than one currency (a single-currency node
  // is already fully described by nativeAmount/size). See
  // currencyComposition below for why this is built from real leaves,
  // never from CURRENCY_MIRROR nodes.
  currencyBreakdown?: { currency: string; amount: number }[];
}

// A CURRENCY_MIRROR node mirrors a balance that's already counted
// elsewhere in the tree (e.g. the same money reflected in a different
// currency for reporting) — it should never render as its own box while
// drilling down. Checked on both fields since the backend sets both to
// the same value on every real mirror node seen live, but only one is
// guaranteed populated depending on the node's own account category vs.
// special type.
function isCurrencyMirror(node: BalanceHierarchyNode): boolean {
  return node.accountCategory === 'CURRENCY_MIRROR' || node.specialType === 'CURRENCY_MIRROR';
}

// Real (non-mirror) children only — used to decide which boxes to show
// while drilling down. (The sum itself no longer needs this filter —
// BalanceStructureService now excludes CURRENCY_MIRROR at the source; see
// sumBalance below.)
function realChildren(node: BalanceHierarchyNode): BalanceHierarchyNode[] {
  return (node.children ?? []).filter((c) => !isCurrencyMirror(c));
}

// A currency mirror restates a WHOLE branch's total in one currency —
// it's a duplicate view, not a disjoint slice (confirmed live: LONDON
// OPS's own total and its single GBP mirror read the identical figure).
// So mirrors can't be summed as if they were per-currency buckets without
// reintroducing double-counting. The safe way to get a real per-currency
// breakdown: walk every REAL (non-mirror) node in the subtree and add its
// OWN localBalance under its OWN currencyCode — same-currency amounts can
// always be summed without FX conversion, and post-fix (BalanceStructureService.
// recomputeRollup) every container node's own localBalance is already
// zero, so there's nothing to double-count by including them unfiltered.
function currencyComposition(node: BalanceHierarchyNode): { currency: string; amount: number }[] {
  const totals = new Map<string, number>();
  const walk = (n: BalanceHierarchyNode) => {
    if (isCurrencyMirror(n)) return;
    if (n.localBalance) {
      // IC Payable is Treasury's liability to a subsidiary (COBO) — it must
      // subtract, the same real money an IC Receivable (POBO) correctly
      // adds, just viewed from the other side. See recomputeRollup() in
      // BalanceStructureService for the backend half of this same fix.
      const signed = n.mirrorAccountType === 'IC_PAYABLE' ? -n.localBalance : n.localBalance;
      totals.set(n.currencyCode, (totals.get(n.currencyCode) ?? 0) + signed);
    }
    (n.children ?? []).forEach(walk);
  };
  walk(node);
  return Array.from(totals, ([currency, amount]) => ({ currency, amount }))
    .filter((c) => c.amount !== 0)
    .sort((a, b) => Math.abs(b.amount) - Math.abs(a.amount));
}

// consolidatedBalance is now a true recursive rollup computed server-side
// (BalanceStructureService.recomputeRollup) — own + all real descendants,
// CURRENCY_MIRROR excluded, for every node in the tree, not just the
// outer root. This used to have to redo that walk client-side (a
// "Corporate Root Account" GROUP node read consolidatedBalance: 0 while a
// SHADOW_ACCOUNT nested beneath it held AED 9.18M) — kept as a thin
// passthrough, rather than deleted, so every existing call site (the
// Dashboard's "Consolidated position" headline, this file's own
// treemapData) keeps working unchanged now that the backend does the sum.
export function sumBalance(node: BalanceHierarchyNode): number {
  return Math.max(node.consolidatedBalance, 0);
}

interface FlatBreakdownTreemapProps {
  data: TreemapNode[];
  loading: boolean;
  categoricalColors: string[];
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
  currency?: string;
  emptyMessage?: string;
  onItemClick?: (item: TreemapNode) => void;
}

/**
 * Flat {name,size}[] -> colored treemap boxes. Extracted from
 * EntityHierarchyTreemap (which builds its own flat list via a recursive
 * sumBalance walk) so the by-corporate/by-program dashboard breakdowns —
 * already flat lists from the backend, no tree to walk — can share the
 * same recharts rendering instead of duplicating this Treemap/Tooltip JSX.
 */
export const FlatBreakdownTreemap: React.FC<FlatBreakdownTreemapProps> = ({
  data,
  loading,
  categoricalColors,
  tooltipBg,
  tooltipText,
  tooltipShadow,
  currency = 'AED',
  emptyMessage = 'No data available for this scope.',
  onItemClick,
}) => {
  if (loading) {
    return <div className="h-[220px] animate-pulse bg-neutral-100 dark:bg-primary-800/40 rounded" />;
  }

  if (data.length === 0) {
    return <p className="body-sm py-6 text-center">{emptyMessage}</p>;
  }

  const total = data.reduce((sum, d) => sum + d.size, 0);

  // Custom content (not the default formatter/labelStyle combo) so the
  // tooltip can show more than just "name + one value" — % of the whole
  // breakdown, and a drill hint — at a deliberately smaller font than
  // recharts' default tooltip text, which reads oversized next to these
  // compact 220px-tall dashboard tiles.
  const renderTooltip = ({ active, payload }: any) => {
    if (!active || !payload?.length) return null;
    const item = payload[0].payload as TreemapNode;
    const pct = total > 0 ? (item.size / total) * 100 : 0;
    return (
      <div
        style={{
          backgroundColor: tooltipBg,
          color: tooltipText,
          borderRadius: 12,
          boxShadow: tooltipShadow,
          padding: '8px 12px',
          fontSize: 11,
          lineHeight: 1.5,
          maxWidth: 220,
        }}
      >
        <div style={{ fontWeight: 600, marginBottom: 2 }}>{item.displayName ?? item.name}</div>
        <div>{formatCurrency(item.size, currency)}</div>
        {item.nativeAmount != null && item.nativeCurrency && item.nativeCurrency !== currency && (
          <div style={{ opacity: 0.85 }}>{formatCurrency(item.nativeAmount, item.nativeCurrency)} native</div>
        )}
        {item.currencyBreakdown && item.currencyBreakdown.length > 1 && (
          <div style={{ opacity: 0.85, marginTop: 2 }}>
            {item.currencyBreakdown.map((c) => formatCurrency(c.amount, c.currency)).join(' · ')}
          </div>
        )}
        <div style={{ opacity: 0.7 }}>{pct.toFixed(1)}% of total</div>
        {onItemClick && item.hasChildren !== false && (
          <div style={{ opacity: 0.7, marginTop: 2 }}>Click to explore</div>
        )}
      </div>
    );
  };

  return (
    <div className="h-[220px]">
      <ResponsiveContainer width="100%" height="100%">
        {/* recharts' Treemap does not support <Cell> children for custom
            colors (confirmed live — they were silently ignored, rendering
            with the library's own hardcoded default palette instead); its
            actual customization hook is the colorPanel prop, indexed the
            same way Cell would be (index % colors.length). Typed as `[]`
            in this recharts version (a type bug, not a real constraint —
            confirmed against Treemap.js's own runtime `colorPanel ||
            COLOR_PANEL` fallback), hence the cast. */}
        <Treemap
          data={data}
          dataKey="size"
          stroke="var(--color-bg-page, #fff)"
          isAnimationActive={false}
          colorPanel={categoricalColors as unknown as []}
          onClick={onItemClick ? (node: any) => onItemClick(node) : undefined}
          className={onItemClick ? 'cursor-pointer' : undefined}
        >
          <Tooltip content={renderTooltip} />
        </Treemap>
      </ResponsiveContainer>
    </div>
  );
};

interface EntityHierarchyTreemapProps {
  // Fetched once by the parent page (Treasury2030DashboardPage's load())
  // and shared with the new consolidated-position headline figure — this
  // component used to fetch balanceStructureApi.getHierarchy() itself,
  // duplicating that network call.
  root: BalanceHierarchyNode | null;
  loading: boolean;
  categoricalColors: string[];
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
  currency?: string;
}

export const EntityHierarchyTreemap: React.FC<EntityHierarchyTreemapProps> = ({
  root,
  loading,
  categoricalColors,
  tooltipBg,
  tooltipText,
  tooltipShadow,
  currency = 'AED',
}) => {
  // Drill-down state: the full chain of nodes from root down to whichever
  // node's children are currently shown — not just "current vs. root" —
  // so a multi-level drill (e.g. Corporate Root > EMEA REGION > UK) shows
  // its whole parent chain, not just a single "back to the top" shortcut.
  // The whole tree is already fetched, so walking deeper needs no new
  // network call. Reset to just [root] whenever the fetched tree itself
  // changes (a fresh corporate/program scope should always start back at
  // the top, not leave the user stranded several levels deep in stale
  // data pointing at nodes that may no longer exist).
  const [path, setPath] = useState<BalanceHierarchyNode[]>(root ? [root] : []);
  useEffect(() => setPath(root ? [root] : []), [root]);
  const currentNode = path[path.length - 1] ?? null;

  const treemapData = useMemo<TreemapNode[]>(() => {
    if (!currentNode) return [];
    // The root itself (GROUP, e.g. "Test Multinational Corp") is rarely a
    // useful single box — its direct children become the top-level boxes
    // instead of one box for the whole company. Currency-mirror children
    // are dropped entirely here (never shown as their own box) — see
    // isCurrencyMirror/realChildren above.
    return realChildren(currentNode)
      .map((node) => {
        // Own balance in the account's real currency — a GROUP/aggregation
        // box's own slice is frequently 0 (its value is entirely its
        // children's, e.g. "Corporate Root Account" below), in which case
        // there's no native figure to show and the box falls back to the
        // FX-converted size instead (still real money, just not this
        // node's own single-currency slice).
        const hasNative = node.localBalance > 0;
        const nativeCurrency = hasNative ? node.currencyCode : undefined;
        const nativeAmount = hasNative ? node.localBalance : undefined;
        const size = sumBalance(node);
        // Leaf account boxes (VIRTUAL_ACCOUNT/SHADOW_ACCOUNT) are labeled by
        // their owning legal entity when one is set — a raw account name
        // like "Corporate Root Account" or "IC Payable - MNC UK Ltd AED"
        // means little to a viewer drilling by "By entity". GROUP/REGION/
        // ENTITY container boxes keep their own name: those are the
        // REGION > COUNTRY > OPS geographic/org drill path, already
        // distinct and meaningful — swapping them to the owning entity too
        // would make e.g. "EMEA REGION" > "UK" > "LONDON OPS" all read
        // identically as "MNC UK Ltd", destroying the ability to tell the
        // drill levels apart.
        const isLeafAccount = node.type === 'VIRTUAL_ACCOUNT' || node.type === 'SHADOW_ACCOUNT';
        const entityLabel = isLeafAccount && node.owningEntityName ? node.owningEntityName : undefined;
        return {
          id: node.id,
          // recharts only ever draws `name` on the box itself — just the
          // account name (combining it with the amount was tried and
          // reverted: recharts' text-fit check is all-or-nothing against
          // the full string at a font size we can't control, and a
          // combined string only fit the single largest box in most
          // views). The amount is still available on hover (tooltip).
          name: entityLabel ?? node.name,
          // Tooltip header keeps the underlying account name visible even
          // when the box itself shows the owning entity, so drilling in
          // doesn't lose which specific account a balance sits in.
          displayName: entityLabel ? `${entityLabel} · ${node.name}` : undefined,
          size,
          hasChildren: realChildren(node).length > 0,
          nativeCurrency,
          nativeAmount,
          currencyBreakdown: currencyComposition(node),
        };
      })
      .filter((n) => n.size > 0)
      .sort((a, b) => b.size - a.size);
  }, [currentNode]);

  const handleItemClick = (item: TreemapNode) => {
    const child = currentNode ? realChildren(currentNode).find((c) => c.id === item.id) : undefined;
    if (child && realChildren(child).length > 0) {
      setPath([...path, child]);
    }
  };

  // The currently-drilled-into node's own composition — a box's tooltip
  // only exists while that node is still shown as a box in its PARENT's
  // view; once you click in, its own composition (e.g. FINAL IHB's
  // "AED 13,888.48 · GBP 600.00") would otherwise vanish entirely, even
  // though it's still exactly as true of the view you're looking at. Its
  // own children are typically single-currency each (that's WHY they're
  // separate branches), so this is the one place that split is still
  // visible once you're a level in.
  const currentComposition = currentNode ? currencyComposition(currentNode) : [];

  return (
    <div>
      {(path.length > 1 || currentComposition.length > 1) && (
        <div className="mb-2 flex items-center gap-1 flex-wrap caption">
          {path.length > 1 && <ChevronLeft size={14} className="shrink-0" />}
          {path.map((node, i) => (
            <React.Fragment key={node.id}>
              {i > 0 && <span className="text-neutral-400">›</span>}
              {i === path.length - 1 ? (
                <span className="font-semibold text-primary-900 dark:text-white">{node.name}</span>
              ) : (
                <button
                  type="button"
                  onClick={() => setPath(path.slice(0, i + 1))}
                  className="hover:text-primary-900 dark:hover:text-white hover:underline"
                >
                  {node.name}
                </button>
              )}
            </React.Fragment>
          ))}
          {currentComposition.length > 1 && (
            <span className="text-neutral-400">
              · {currentComposition.map((c) => formatCurrency(c.amount, c.currency)).join(' · ')}
            </span>
          )}
        </div>
      )}
      <FlatBreakdownTreemap
        data={treemapData}
        loading={loading}
        categoricalColors={categoricalColors}
        tooltipBg={tooltipBg}
        tooltipText={tooltipText}
        tooltipShadow={tooltipShadow}
        currency={currency}
        emptyMessage="No hierarchy data available for this scope."
        onItemClick={handleItemClick}
      />
    </div>
  );
};
