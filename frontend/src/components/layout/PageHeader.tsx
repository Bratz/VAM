import React from 'react';
import { cn } from '../../utils';

/**
 * Standard page-body header: title + optional description + optional
 * right-side action cluster.
 *
 * Renders the title via `.page-title` (Fraunces 30px / 500w / gold
 * underline). Use as the first child of `<Page>` whenever the page has
 * an in-body heading. NOT for slide-in detail panels or modals — those
 * use their own header (e.g. `<Modal title=…>`).
 *
 * Note that most pages already register their CTAs in the Aperture
 * Layout header via `usePageHeaderActions` (Phase 7). Use this
 * component's `actions` slot only when you genuinely need a page-body
 * action cluster (rare — usually because the actions are scoped to a
 * sub-section of the page).
 */
export interface PageHeaderProps {
  title: string;
  /**
   * Description / subtitle. Accepts a `React.ReactNode` (not just `string`)
   * so pages can inline contextual chrome — e.g. the BIC pill on
   * MultiBankLiquidityPage. The wrapper renders as `<div>` rather than
   * `<p>` so nested elements (spans, pills) don't violate HTML validity.
   */
  description?: React.ReactNode;
  actions?: React.ReactNode;
  className?: string;
}

export const PageHeader: React.FC<PageHeaderProps> = ({
  title,
  description,
  actions,
  className,
}) => (
  <div className={cn('flex flex-wrap items-start justify-between gap-4', className)}>
    <div className="min-w-0 flex-1">
      <h1 className="page-title">{title}</h1>
      {description && (
        <div className="body-sm mt-2 max-w-prose">{description}</div>
      )}
    </div>
    {actions && (
      <div className="flex items-center gap-2 shrink-0">{actions}</div>
    )}
  </div>
);
