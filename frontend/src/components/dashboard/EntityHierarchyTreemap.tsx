import React, { useEffect, useState, useMemo } from 'react';
import { ResponsiveContainer, Treemap, Tooltip } from 'recharts';
import { balanceStructureApi, BalanceHierarchyNode } from '../../services/api';
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
  name: string;
  size: number;
}

// Container-type nodes (GROUP/REGION/ENTITY) do NOT already roll up their
// own consolidatedBalance from their children — confirmed live: a
// "Corporate Root Account" GROUP node read consolidatedBalance: 0 while a
// SHADOW_ACCOUNT nested 1 level below it held AED 9.18M. So each top-level
// box's value has to be a recursive sum over its whole subtree, not a
// direct field read. This also naturally flattens the tree to one level
// (a real multi-depth treemap needs recharts' `content` render prop, not
// <Cell>, to color every depth — more machinery than a compact dashboard
// tile needs for now) — a flat array of {name, size} is exactly the shape
// <Cell>-based coloring already works for (same pattern as the currency
// bar chart).
function sumBalance(node: BalanceHierarchyNode): number {
  const own = Math.max(node.consolidatedBalance, 0);
  const childrenSum = (node.children ?? []).reduce((total, c) => total + sumBalance(c), 0);
  return own + childrenSum;
}

interface EntityHierarchyTreemapProps {
  corporateId?: string;
  categoricalColors: string[];
  tooltipBg: string;
  tooltipText: string;
  tooltipShadow: string;
  currency?: string;
}

export const EntityHierarchyTreemap: React.FC<EntityHierarchyTreemapProps> = ({
  corporateId,
  categoricalColors,
  tooltipBg,
  tooltipText,
  tooltipShadow,
  currency = 'AED',
}) => {
  const [root, setRoot] = useState<BalanceHierarchyNode | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    balanceStructureApi.getHierarchy(corporateId)
      .then((res) => { if (!cancelled) setRoot(res?.data ?? null); })
      .catch(() => { if (!cancelled) setRoot(null); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [corporateId]);

  const treemapData = useMemo<TreemapNode[]>(() => {
    if (!root?.children || root.children.length === 0) return [];
    // The root itself (GROUP, e.g. "Test Multinational Corp") is rarely a
    // useful single box — the root's own direct children become the
    // top-level boxes instead of one box for the whole company.
    return root.children
      .map((node) => ({ name: node.name, size: sumBalance(node) }))
      .filter((n) => n.size > 0)
      .sort((a, b) => b.size - a.size);
  }, [root]);

  if (loading) {
    return <div className="h-[220px] animate-pulse bg-neutral-100 dark:bg-primary-800/40 rounded" />;
  }

  if (treemapData.length === 0) {
    return <p className="body-sm py-6 text-center">No hierarchy data available for this scope.</p>;
  }

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
          data={treemapData}
          dataKey="size"
          stroke="var(--color-bg-page, #fff)"
          isAnimationActive={false}
          colorPanel={categoricalColors as unknown as []}
        >
          <Tooltip
            contentStyle={{
              backgroundColor: tooltipBg,
              border: 'none',
              borderRadius: '12px',
              boxShadow: tooltipShadow,
              padding: '10px 14px',
              color: tooltipText,
            }}
            labelStyle={{ color: tooltipText, fontWeight: 600 }}
            formatter={(value: number) => [formatCurrency(value, currency), 'Balance']}
          />
        </Treemap>
      </ResponsiveContainer>
    </div>
  );
};
