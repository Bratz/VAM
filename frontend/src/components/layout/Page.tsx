import React from 'react';
import { cn } from '../../utils';

/**
 * Standard page wrapper.
 *
 * Owns the page-level layout contract: max-width and vertical rhythm.
 * Replaces the ~70 hand-rolled `<div className="space-y-6 animate-page-enter">`
 * page roots and the 3 outliers using their own
 * `max-w-7xl mx-auto px-6` wrappers (CreatePayablePage, CreateReceivablePage,
 * IntegrationsPage).
 *
 * Width rules (Phase 10):
 *  - `default` (1280px / max-w-7xl) — every dashboard, list, and detail page
 *  - `narrow` (1024px / max-w-5xl) — forms-first pages with a single column
 *    of inputs (e.g. CreatePayablePage, CreateReceivablePage)
 *  - `full` — uncapped, only for pages that need full-bleed canvas behaviour
 *    (e.g. EntityBalanceTreePage's force-directed graph)
 *
 * The `<main>` element in Layout.tsx already applies horizontal padding
 * (`p-4 lg:p-8`) AND the `animate-page-enter` animation. Page does NOT add
 * another layer of padding or the animation — those live on the shell.
 * Do not pass `px-*` to Page.
 */
export interface PageProps {
  maxWidth?: 'default' | 'narrow' | 'full';
  className?: string;
  children: React.ReactNode;
}

export const Page: React.FC<PageProps> = ({
  maxWidth = 'default',
  className,
  children,
}) => (
  <div
    className={cn(
      'space-y-4',
      maxWidth === 'default' && 'max-w-7xl mx-auto w-full',
      maxWidth === 'narrow' && 'max-w-5xl mx-auto w-full',
      // maxWidth === 'full' has no cap
      className
    )}
  >
    {children}
  </div>
);
