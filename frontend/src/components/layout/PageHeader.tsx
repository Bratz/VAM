import React from 'react';
import { usePageHeaderActions, usePageHeaderTitle } from '../../context/PageHeaderContext';

/**
 * Registers a page's title + description + actions with the Aperture Layout
 * header instead of rendering an in-body heading. Kept as a component (not a
 * bare hook) purely for call-site compatibility with the ~35 existing pages
 * that render `<PageHeader title=… description=… actions=… />` as the first
 * child of `<Page>` — it renders nothing itself.
 *
 * NOT for slide-in detail panels or modals — those use their own header
 * (e.g. `<Modal title=…>`).
 */
export interface PageHeaderProps {
  title: string;
  /**
   * Description / subtitle. Accepts a `React.ReactNode` (not just `string`)
   * so pages can inline contextual chrome — e.g. the BIC pill on
   * MultiBankLiquidityPage. Rendered inside the header's help popover.
   */
  description?: React.ReactNode;
  actions?: React.ReactNode;
  /** No longer used (nothing renders here) — kept so existing call sites don't need to drop it. */
  className?: string;
}

export const PageHeader: React.FC<PageHeaderProps> = ({ title, description, actions }) => {
  // Deps are `[title]` only, not `[title, description]`/`[actions]` — those
  // are React nodes, freshly created on every render of the calling page
  // (e.g. inline JSX like `description={<span>…</span>}`), so including them
  // made the registration effect re-fire every render -> setState every
  // render -> re-render -> infinite loop ("Maximum update depth exceeded").
  // `title` is a stable string and changes exactly when a page's header
  // content should refresh, so it's the right (and only) trigger here —
  // same intentionally-incomplete-deps idiom the pre-existing
  // usePageHeaderActions callers already use elsewhere in this codebase.
  usePageHeaderTitle(title, description ?? null, [title]);
  usePageHeaderActions(() => actions ?? null, [title]);
  return null;
};
