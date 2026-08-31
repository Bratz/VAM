import React, { Children } from 'react';
import { cn } from '../../utils';

/**
 * Standard "row of metric tiles at the top of a page" container.
 *
 * Owns the grid recipe: gap-4, responsive column count derived from
 * child count. Replaces ad-hoc `grid grid-cols-X md:grid-cols-Y
 * lg:grid-cols-Z gap-(3|4|6)` strips authored per-page.
 *
 * Column rules (Phase 10):
 *  - 1 child   → grid-cols-1
 *  - 2 children → grid-cols-1 sm:grid-cols-2
 *  - 3 children → grid-cols-1 sm:grid-cols-2 lg:grid-cols-3
 *  - 4 children → grid-cols-2 lg:grid-cols-4
 *  - 5 children → grid-cols-2 md:grid-cols-3 lg:grid-cols-5
 *  - 6+ children → grid-cols-2 md:grid-cols-3 lg:grid-cols-6
 *    (DISCOURAGED; prefer demoting metrics or splitting into two strips)
 *
 * Children should be `<Card>` from the design system or equivalent tile.
 * StatStrip does NOT impose tile styling — only the grid container.
 *
 * Pass `columns` explicitly when the child set is dynamic (e.g. rendered
 * via `.map(...)`) or when the auto-derived count is wrong for the
 * specific layout.
 */
export interface StatStripProps {
  /** Override the auto-derived column count (1–6). */
  columns?: 1 | 2 | 3 | 4 | 5 | 6;
  className?: string;
  children: React.ReactNode;
}

const gridClassByCols: Record<number, string> = {
  1: 'grid-cols-1',
  2: 'grid-cols-1 sm:grid-cols-2',
  3: 'grid-cols-1 sm:grid-cols-2 lg:grid-cols-3',
  4: 'grid-cols-2 lg:grid-cols-4',
  5: 'grid-cols-2 md:grid-cols-3 lg:grid-cols-5',
  6: 'grid-cols-2 md:grid-cols-3 lg:grid-cols-6',
};

export const StatStrip: React.FC<StatStripProps> = ({
  columns,
  className,
  children,
}) => {
  const childCount = Children.count(children);
  const auto = Math.min(Math.max(childCount, 1), 6) as 1 | 2 | 3 | 4 | 5 | 6;
  const cols = columns ?? auto;
  return (
    <div className={cn('grid gap-4', gridClassByCols[cols], className)}>
      {children}
    </div>
  );
};
